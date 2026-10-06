// Command dlpush is the DLPUSH/1 push gateway entrypoint.
//
// Configuration is entirely via environment variables (RFC 0002 §9):
//
//	DLPUSH_LISTEN        listen address, default :8080
//	DLPUSH_HPKE_KEYS     kid=file path (base64url 32-byte X25519 private key),
//	                     one entry per line, comma-separated
//	APNS_KEY_P8_PATH     path to the APNs .p8 auth key (0600)
//	APNS_KEY_ID          APNs key id
//	APNS_TEAM_ID         Apple team id
//	APNS_BUNDLE_ID       App bundle id
//	DLPUSH_RATE_MINUTE   per-minute limit (default 10)
//	DLPUSH_RATE_HOUR     per-hour limit (default 120)
//	DLPUSH_VERSION       version string for /healthz
package main

import (
	"bufio"
	"encoding/base64"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/lunaship/dsh-links/push/internal/apns"
	"github.com/lunaship/dsh-links/push/internal/hpke"
	gwhttp "github.com/lunaship/dsh-links/push/internal/http"
	"github.com/lunaship/dsh-links/push/internal/limit"
	"github.com/sideshow/apns2/token"
)

func main() {
	log.SetFlags(log.LstdFlags | log.Lmicroseconds)

	listen := envOr("DLPUSH_LISTEN", ":8080")
	version := envOr("DLPUSH_VERSION", "dev")

	// Load HPKE keys: DLPUSH_HPKE_KEYS is "kid1=/path/k1,kid2=/path/k2"
	// where each file holds the raw 32-byte X25519 private key (base64url
	// without padding).
	keys, err := loadHPKEKeys(os.Getenv("DLPUSH_HPKE_KEYS"))
	if err != nil {
		log.Fatalf("dlpush: %v", err)
	}
	if len(keys) == 0 {
		log.Fatal("dlpush: no HPKE keys configured (DLPUSH_HPKE_KEYS)")
	}

	// APNs sender.
	sender, err := newAPNSSender()
	if err != nil {
		log.Fatalf("dlpush: apns sender: %v", err)
	}

	limiter := limit.New(
		intOr(envOr("DLPUSH_RATE_MINUTE", ""), 10),
		intOr(envOr("DLPUSH_RATE_HOUR", ""), 120),
		nil,
	)

	gw := &gwhttp.Gateway{
		Keys:    keys,
		Sender:  sender,
		Limiter: limiter,
		Version: version,
	}

	srv := &http.Server{
		Addr:              listen,
		Handler:           gw.Handle(),
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("dlpush listening on %s version=%s", listen, version)
	if err := srv.ListenAndServe(); err != nil {
		log.Fatalf("dlpush: server: %v", err)
	}
}

// loadHPKEKeys parses "kid=/path" pairs. Each file must hold a 32-byte
// base64url-encoded X25519 private key.
func loadHPKEKeys(spec string) (map[string]*hpke.Gateway, error) {
	out := map[string]*hpke.Gateway{}
	if spec == "" {
		return out, nil
	}
	for _, entry := range strings.Split(spec, ",") {
		parts := strings.SplitN(entry, "=", 2)
		if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
			return nil, fmt.Errorf("hpke keys spec %q: each entry is kid=path", spec)
		}
		kid, path := strings.TrimSpace(parts[0]), strings.TrimSpace(parts[1])
		raw, err := os.ReadFile(path)
		if err != nil {
			return nil, fmt.Errorf("hpke key %s: %w", kid, err)
		}
		sk, err := base64.RawURLEncoding.DecodeString(strings.TrimSpace(string(raw)))
		if err != nil {
			return nil, fmt.Errorf("hpke key %s: base64url decode: %w", kid, err)
		}
		if len(sk) != 32 {
			return nil, fmt.Errorf("hpke key %s: %d bytes, want 32", kid, len(sk))
		}
		// Wrap the raw bytes into a circl private key.
		priv, err := hpke.WrapX25519(sk)
		if err != nil {
			return nil, fmt.Errorf("hpke key %s: %w", kid, err)
		}
		gw, err := hpke.NewGateway(kid, priv)
		if err != nil {
			return nil, err
		}
		out[kid] = gw
	}
	return out, nil
}

// newAPNSSender wires the token-auth sender from environment config.
func newAPNSSender() (apns.Sender, error) {
	p8Path := os.Getenv("APNS_KEY_P8_PATH")
	if p8Path == "" {
		// No APNs key: use a no-op sender (dev mode). The gateway still
		// validates sealed tokens, but does not reach APNs.
		return apns.NoopSender{}, nil
	}
	key, err := token.AuthKeyFromFile(p8Path)
	if err != nil {
		return nil, fmt.Errorf("apns .p8: %w", err)
	}
	pk := &token.Token{
		AuthKey: key,
		KeyID:   os.Getenv("APNS_KEY_ID"),
		TeamID:  os.Getenv("APNS_TEAM_ID"),
	}
	host := os.Getenv("APNS_HOST") // empty → api.push.apple.com
	sender := apns.NewTokenSender(pk, os.Getenv("APNS_TEAM_ID"), os.Getenv("APNS_BUNDLE_ID"), host)
	if err := configureFakeAPNs(sender, os.Getenv("DLPUSH_FAKE_APNS_URL")); err != nil {
		return nil, err
	}
	return sender, nil
}

func configureFakeAPNs(sender *apns.TokenSender, raw string) error {
	if raw == "" {
		return nil
	}
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
		return fmt.Errorf("DLPUSH_FAKE_APNS_URL must be an HTTP or HTTPS URL")
	}
	host := parsed.Hostname()
	if parsed.Scheme == "http" && host != "127.0.0.1" && host != "localhost" && host != "::1" {
		return fmt.Errorf("plaintext fake APNs must stay on localhost")
	}
	sender.Host = parsed.Host
	sender.SetTestClient(&http.Client{Timeout: 10 * time.Second}, parsed.Scheme)
	return nil
}

func envOr(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func intOr(s string, d int) int {
	if s == "" {
		return d
	}
	n, err := strconv.Atoi(s)
	if err != nil {
		return d
	}
	return n
}

// bufio / log keepers (used by helpers above).
var _ = bufio.NewReader
var _ = log.Print
