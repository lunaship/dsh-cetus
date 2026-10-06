// Package limit is the gateway's in-memory per-sealed rate limiter.
//
// The limit key is sha256(sealed JSON) (RFC 0002 §5.7). Defaults: 10
// requests/minute and 120/hour, both configurable.
package limit

import (
	"sync"
	"time"
)

// Limiter enforces sliding-window per-key limits in memory.
type Limiter struct {
	mu sync.Mutex
	// per-key windows
	entries   map[[32]byte]*entry
	perMinute int
	perHour   int
	now       func() time.Time // injectable for tests
}

type entry struct {
	minute []time.Time
	hour   []time.Time
}

// New returns a limiter with the given limits and an injectable clock.
func New(perMinute, perHour int, now func() time.Time) *Limiter {
	if now == nil {
		now = time.Now
	}
	return &Limiter{entries: make(map[[32]byte]*entry), perMinute: perMinute, perHour: perHour, now: now}
}

// Allow records a hit for key and reports whether the request is within limits.
func (l *Limiter) Allow(key [32]byte) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	n := l.now()
	e, ok := l.entries[key]
	if !ok {
		e = &entry{}
		l.entries[key] = e
	}
	e.minute = prune(e.minute, n.Add(-time.Minute))
	e.hour = prune(e.hour, n.Add(-time.Hour))
	if len(e.minute) >= l.perMinute || len(e.hour) >= l.perHour {
		return false
	}
	e.minute = append(e.minute, n)
	e.hour = append(e.hour, n)
	return true
}

func prune(ts []time.Time, before time.Time) []time.Time {
	i := 0
	for i < len(ts) && ts[i].Before(before) {
		i++
	}
	return ts[i:]
}
