// Package apns sends notifications to APNs via the token-auth HTTP/2 API.
//
// The Sender interface abstracts the transport so tests can use a fake
// APNs server. The real implementation uses HTTP/2 with a short-lived JWT
// signed by an ECDSA P-256 .p8 key (sideshow/apns2 semantics).
package apns

import (
	"context"
	"errors"
	"time"
)

// Request is the fully-formed APNs notification (body + headers) built by
// the gateway from the plugin's POST /v1/push payload.
type Request struct {
	Token      string // lowercase hex device token
	Topic      string // bundle id (alerts) or <bundle>.push-type.liveactivity
	PushType   string // "alert" | "liveactivity"
	Priority   int    // 10 (approval/question) or 5 (completion-class)
	CollapseID string
	Expiration time.Duration
	Body       []byte // JSON payload, includes "e" (ct base64) and "k" (kid)
}

// Result reports the APNs response.
type Result struct {
	Status int
	Reason string // e.g. "BadDeviceToken", "Unregistered"
	Gone   bool   // true when APNs reports the token as invalid (410-class)
}

// BadDeviceToken is returned when APNs says the token is no longer valid.
var ErrBadDeviceToken = errors.New("apns: bad device token")

// Sender delivers a single notification.
type Sender interface {
	Send(ctx context.Context, req Request) (Result, error)
}

// ResultFor maps an HTTP status to a Result, including the 410 detection.
func ResultFor(status int, reason string) Result {
	gone := status == 410 || status == 404 || reason == "BadDeviceToken" || reason == "Unregistered"
	return Result{Status: status, Reason: reason, Gone: gone}
}
