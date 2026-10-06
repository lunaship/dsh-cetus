package limit

import (
	"testing"
	"time"
)

func TestLimiterMinuteAndHour(t *testing.T) {
	now := time.Date(2026, 10, 3, 12, 0, 0, 0, time.UTC)
	clock := now
	l := New(2, 3, func() time.Time { return clock })
	var key [32]byte
	key[0] = 1

	if !l.Allow(key) || !l.Allow(key) {
		t.Fatal("first two hits in a minute must pass")
	}
	if l.Allow(key) {
		t.Fatal("third hit in the same minute must be limited")
	}

	clock = now.Add(time.Minute + time.Nanosecond)
	if !l.Allow(key) {
		t.Fatal("hit after the minute window must pass")
	}
	if l.Allow(key) {
		t.Fatal("fourth hit inside the hour must be limited")
	}

	clock = now.Add(time.Hour + time.Second)
	if !l.Allow(key) || !l.Allow(key) {
		t.Fatal("hits after the hour window must pass again")
	}
}

func TestLimiterKeysAreIndependent(t *testing.T) {
	l := New(1, 10, func() time.Time { return time.Unix(1_000, 0) })
	var a, b [32]byte
	b[0] = 2
	if !l.Allow(a) || l.Allow(a) {
		t.Fatal("key a did not hit its own limit")
	}
	if !l.Allow(b) {
		t.Fatal("key b was limited by key a")
	}
}
