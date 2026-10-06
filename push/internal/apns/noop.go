package apns

import "context"

// NoopSender is a dev-mode Sender that does not reach APNs. It accepts every
// request and reports success. Used when APNS_KEY_P8_PATH is unset so the
// gateway can still validate sealed tokens locally.
type NoopSender struct{}

// Send records nothing and always reports 200.
func (NoopSender) Send(ctx context.Context, req Request) (Result, error) {
	return Result{Status: 200}, nil
}

// Compile-time check.
var _ Sender = NoopSender{}
