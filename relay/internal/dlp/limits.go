package dlp

import (
	"sync"
	"time"
)

type limiter struct {
	mu      sync.Mutex
	windows map[string]window
}
type window struct {
	tokens  float64
	updated time.Time
}

func newLimiter() *limiter { return &limiter{windows: make(map[string]window)} }

func (l *limiter) allow(key string, limit int, period time.Duration, burst int, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	w := l.windows[key]
	if burst < 1 {
		burst = 1
	}
	if w.updated.IsZero() {
		w = window{tokens: float64(burst), updated: now}
	} else if now.After(w.updated) {
		w.tokens += now.Sub(w.updated).Seconds() * float64(limit) / period.Seconds()
		if w.tokens > float64(burst) {
			w.tokens = float64(burst)
		}
		w.updated = now
	}
	if w.tokens < 1 {
		l.windows[key] = w
		return false
	}
	w.tokens--
	l.windows[key] = w
	return true
}
