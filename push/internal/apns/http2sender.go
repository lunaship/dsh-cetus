package apns

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/sideshow/apns2/token"

	"golang.org/x/net/http2"
)

// TokenSender sends via the APNs token-auth API. The token JWT is minted
// per request with a short expiry; a production deployment caches and
// refreshes it.
type TokenSender struct {
	Key      *token.Token // holds the ECDSA key + team/key IDs
	TeamID   string
	BundleID string
	Host     string // api.push.apple.com (default), or an override for fakes
	client   *http.Client
	scheme   string // https by default; tests set http for a local fake
}

// NewTokenSender constructs a sender. host defaults to api.push.apple.com.
func NewTokenSender(key *token.Token, teamID, bundleID, host string) *TokenSender {
	if host == "" {
		host = "api.push.apple.com"
	}
	return &TokenSender{Key: key, TeamID: teamID, BundleID: bundleID, Host: host, scheme: "https"}
}

// SetTestClient points the sender at a local fake APNs. scheme is "http" or
// "https"; an empty scheme stays https. Production construction never calls this.
func (s *TokenSender) SetTestClient(client *http.Client, scheme string) {
	s.client = client
	if scheme != "" {
		s.scheme = scheme
	}
}

// Send delivers a single notification using the token-auth API.
func (s *TokenSender) Send(ctx context.Context, req Request) (Result, error) {
	bearer := s.Key.GenerateIfExpired()
	scheme := s.scheme
	if scheme == "" {
		scheme = "https"
	}
	url := fmt.Sprintf("%s://%s/3/%s", scheme, s.Host, req.Token)
	httpReq, err := http.NewRequestWithContext(ctx, "POST", url, bytesReader(req.Body))
	if err != nil {
		return Result{}, err
	}
	httpReq.Header.Set("Authorization", "bearer "+bearer)
	httpReq.Header.Set("apns-push-type", req.PushType)
	if req.Priority > 0 {
		httpReq.Header.Set("apns-priority", fmt.Sprintf("%d", req.Priority))
	}
	if req.CollapseID != "" {
		httpReq.Header.Set("apns-collapse-id", req.CollapseID)
	}
	if req.Expiration > 0 {
		httpReq.Header.Set("apns-expiration", fmt.Sprintf("%d", time.Now().Add(req.Expiration).Unix()))
	}
	client := s.client
	if client == nil && s.scheme == "http" {
		client = &http.Client{Timeout: 10 * time.Second}
	}
	if client == nil {
		client = &http.Client{
			Timeout: 10 * time.Second,
			Transport: &http2.Transport{
				TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12},
			},
		}
	}
	resp, err := client.Do(httpReq)
	if err != nil {
		return Result{}, err
	}
	defer resp.Body.Close()
	var body struct {
		Reason string `json:"reason"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&body)
	res := ResultFor(resp.StatusCode, body.Reason)
	if res.Gone {
		return res, ErrBadDeviceToken
	}
	return res, nil
}

// bytesReader wraps a []byte as an io.Reader.
func bytesReader(b []byte) *byteReader { return &byteReader{b: b} }

type byteReader struct {
	b []byte
	i int
}

func (r *byteReader) Read(p []byte) (int, error) {
	if r.i >= len(r.b) {
		return 0, io.EOF
	}
	n := copy(p, r.b[r.i:])
	r.i += n
	return n, nil
}

// Compile-time check that TokenSender satisfies Sender.
var _ Sender = (*TokenSender)(nil)
