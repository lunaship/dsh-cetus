package apns

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/sideshow/apns2/token"
	"golang.org/x/net/http2"
)

func TestHTTP2FakeAPNs(t *testing.T) {
	tokenHex := strings.Repeat("ab", 32)
	contentCt := "AQIDBAUGBwgJCgsM"
	var proto, seenPath, pushType, priority, collapse, auth string
	var body []byte

	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		proto = r.Proto
		seenPath = r.URL.Path
		pushType = r.Header.Get("apns-push-type")
		priority = r.Header.Get("apns-priority")
		collapse = r.Header.Get("apns-collapse-id")
		auth = r.Header.Get("Authorization")
		var err error
		body, err = io.ReadAll(r.Body)
		if err != nil {
			http.Error(w, "read", http.StatusInternalServerError)
			return
		}
		if strings.HasSuffix(r.URL.Path, "bad") {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"reason":"BadDeviceToken"}`))
			return
		}
		w.WriteHeader(http.StatusOK)
	})

	srv := httptest.NewUnstartedServer(handler)
	srv.EnableHTTP2 = true
	srv.Config.TLSNextProto = map[string]func(*http.Server, *tls.Conn, http.Handler){}
	err := http2.ConfigureServer(srv.Config, &http2.Server{})
	if err != nil {
		t.Fatal(err)
	}
	srv.StartTLS()
	t.Cleanup(srv.Close)

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	sender := NewTokenSender(&token.Token{AuthKey: key, KeyID: "KEYID", TeamID: "TEAMID"}, "TEAMID", "dev.deeplinks.ios", strings.TrimPrefix(srv.URL, "https://"))
	sender.SetTestClient(srv.Client(), "https")

	res, err := sender.Send(t.Context(), Request{
		Token:      tokenHex,
		Topic:      "dev.deeplinks.ios",
		PushType:   "alert",
		Priority:   10,
		CollapseID: "device-session-approval",
		Expiration: time.Minute,
		Body:       []byte(`{"e":"` + contentCt + `","k":"gw"}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	if res.Status != http.StatusOK || res.Gone {
		t.Fatalf("result %+v", res)
	}
	if proto != "HTTP/2.0" {
		t.Fatalf("protocol %q, want HTTP/2.0", proto)
	}
	if seenPath != "/3/"+tokenHex || pushType != "alert" || priority != "10" || collapse != "device-session-approval" {
		t.Fatalf("path %s type %s priority %s collapse %s", seenPath, pushType, priority, collapse)
	}
	if !strings.HasPrefix(auth, "bearer ") {
		t.Fatalf("authorization %q", auth)
	}
	var payload struct {
		E string `json:"e"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		t.Fatal(err)
	}
	if payload.E != contentCt {
		t.Fatalf("e %q, want %q", payload.E, contentCt)
	}

	bad, err := sender.Send(t.Context(), Request{
		Token:    tokenHex + "bad",
		PushType: "alert",
		Body:     []byte(`{"e":"` + contentCt + `"}`),
	})
	if err != ErrBadDeviceToken {
		t.Fatalf("err %v", err)
	}
	if bad.Status != http.StatusBadRequest || bad.Reason != "BadDeviceToken" || !bad.Gone {
		t.Fatalf("bad result %+v", bad)
	}
}
