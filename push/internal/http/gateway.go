package http

import (
	"encoding/base64"
	"encoding/json"
	"log"
	"net/http"
	"time"

	"github.com/lunaship/dsh-links/push/internal/apns"
	"github.com/lunaship/dsh-links/push/internal/hpke"
	"github.com/lunaship/dsh-links/push/internal/limit"
)

// Gateway wires the three HTTP endpoints: POST /v1/push, GET /v1/keys,
// GET /healthz. No registration, no database, no user data.
type Gateway struct {
	Keys    map[string]*hpke.Gateway // kid -> gateway key
	Sender  apns.Sender
	Limiter *limit.Limiter
	Version string
}

// PushRequest is the body of POST /v1/push (RFC 0002 §5.5).
type PushRequest struct {
	Kid        string       `json:"kid"`
	Sealed     *hpke.Sealed `json:"sealed"`
	Kind       string       `json:"kind"`
	Ct         string       `json:"ct"`
	CollapseID string       `json:"collapseId"`
	Priority   string       `json:"priority"`
	ExpiresIn  int          `json:"expiresIn"`
}

// PushResponse is the body returned on 200. The body must never echo
// sealed / ct / token (RFC 0002 §5.5).
type PushResponse struct {
	Ok    bool   `json:"ok"`
	Error string `json:"error,omitempty"`
}

// Handle is the http.Handler for the gateway.
func (g *Gateway) Handle() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/push", g.handlePush)
	mux.HandleFunc("/v1/keys", g.handleKeys)
	mux.HandleFunc("/healthz", g.handleHealthz)
	return loggingMiddleware(mux)
}

// handlePush processes POST /v1/push. It:
//  1. parses the request body (sealed must be an object; 400 otherwise)
//  2. checks that sealed.kid matches the top-level kid (400 otherwise)
//  3. opens the sealed token under the gateway key for kid
//  4. builds the APNs request and sends it via the Sender
//  5. maps APNs 410 / BadDeviceToken to 410
func (g *Gateway) handlePush(w http.ResponseWriter, r *http.Request) {
	start := time.Now()
	var req PushRequest
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method")
		return
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "bad_json")
		return
	}
	// sealed must be a JSON object; decode into *hpke.Sealed (pointer).
	// If sealed was a string, the decoder fails → 400.
	if req.Sealed == nil {
		writeError(w, http.StatusBadRequest, "sealed_missing")
		return
	}
	if req.Sealed.Kid != req.Kid {
		writeError(w, http.StatusBadRequest, "kid_mismatch")
		return
	}
	gw, ok := g.Keys[req.Kid]
	if !ok {
		writeError(w, http.StatusBadRequest, "unknown_kid")
		return
	}
	// Open the sealed token.
	pt, err := gw.Open(req.Sealed)
	if err != nil {
		writeError(w, http.StatusBadRequest, "open_failed")
		return
	}
	// Parse the token plaintext to get the APNs device token.
	var tok struct {
		ApnsToken string `json:"apnsToken"`
		Env       string `json:"env"`
		BundleID  string `json:"bundleId"`
	}
	if err := json.Unmarshal(pt, &tok); err != nil {
		writeError(w, http.StatusBadRequest, "token_parse")
		return
	}
	// Rate-limit on sha256(sealed).
	if g.Limiter != nil && !g.Limiter.Allow(req.Sealed.Hash()) {
		writeError(w, http.StatusTooManyRequests, "rate_limited")
		return
	}
	// Build the APNs request.
	apnsReq := apns.Request{
		Token:      tok.ApnsToken,
		Topic:      tok.BundleID,
		CollapseID: req.CollapseID,
		Expiration: time.Duration(req.ExpiresIn) * time.Second,
	}
	switch req.Kind {
	case "alert":
		apnsReq.PushType = "alert"
		if req.Priority == "high" {
			apnsReq.Priority = 10
		} else {
			apnsReq.Priority = 5
		}
		apnsReq.Body = g.buildAlertBody(req.Ct, req.Kid)
	case "la-update", "la-start", "la-end":
		apnsReq.PushType = "liveactivity"
		apnsReq.Topic = tok.BundleID + ".push-type.liveactivity"
		apnsReq.Body = g.buildLaBody(req)
	default:
		writeError(w, http.StatusBadRequest, "bad_kind")
		return
	}
	res, err := g.Sender.Send(r.Context(), apnsReq)
	if err != nil {
		// Map the gateway's APNs errors.
		if res.Gone || res.Status == 410 {
			writeError(w, http.StatusGone, "bad_device_token")
			return
		}
		writeError(w, http.StatusBadGateway, "apns_error")
		return
	}
	if res.Gone {
		writeError(w, http.StatusGone, "bad_device_token")
		return
	}
	// Success: log only counts, status, elapsed. Never sealed/ct/token/IP.
	log.Printf("push kind=%s status=200 elapsed=%dms", req.Kind, time.Since(start).Milliseconds())
	json.NewEncoder(w).Encode(PushResponse{Ok: true})
}

// handleKeys returns the public keys (RFC 0002 §5.5 GET /v1/keys).
func (g *Gateway) handleKeys(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	type pub struct {
		Kid       string `json:"kid"`
		PublicKey string `json:"publicKey"`
	}
	keys := []pub{}
	for kid, gw := range g.Keys {
		raw, err := gw.PublicKey()
		if err != nil {
			continue
		}
		keys = append(keys, pub{Kid: kid, PublicKey: base64.StdEncoding.EncodeToString(raw)})
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"keys": keys})
}

// handleHealthz returns ok + version.
func (g *Gateway) handleHealthz(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"ok": true, "version": g.Version})
}

// buildAlertBody constructs the APNs alert payload.
//
// The title here is the pre-decryption fallback the NSE replaces with the
// decrypted title; it stays in sync with PushContent.generic on iOS.
func (g *Gateway) buildAlertBody(ct, kid string) []byte {
	body := map[string]any{
		"aps": map[string]any{
			"alert": map[string]string{
				"title": "cetus",
				"body":  "有新的任务动态",
			},
			"mutable-content": 1,
			"thread-id":       "",
		},
		"e": ct,
		"k": kid,
	}
	b, _ := json.Marshal(body)
	return b
}

// buildLaBody constructs a Live Activity payload.
func (g *Gateway) buildLaBody(req PushRequest) []byte {
	body := map[string]any{
		"aps": map[string]any{
			"event":         "update",
			"content-state": req.Ct,
		},
	}
	switch req.Kind {
	case "la-start":
		body["aps"].(map[string]any)["event"] = "start"
	case "la-end":
		body["aps"].(map[string]any)["event"] = "end"
	}
	b, _ := json.Marshal(body)
	return b
}

// writeError writes a short error code. It must never echo sealed/ct/token.
func writeError(w http.ResponseWriter, status int, code string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(PushResponse{Error: code})
}

// loggingMiddleware logs only count, status, and elapsed.
func loggingMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		w.Header().Set("Content-Type", "application/json")
		next.ServeHTTP(w, r)
		log.Printf("http %s %s elapsed=%dms", r.Method, r.URL.Path, time.Since(start).Milliseconds())
	})
}
