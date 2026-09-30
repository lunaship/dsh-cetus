package dlp

import (
	"bytes"
	"log"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestHealthzIncludesVersion(t *testing.T) {
	h := NewHub(Config{}, log.New(&bytes.Buffer{}, "", 0))
	defer h.Close()
	h.SetVersion("2026-09-30")
	rec := httptest.NewRecorder()
	h.Handler().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d", rec.Code)
	}
	if rec.Body.String() != "ok 2026-09-30" {
		t.Fatalf("body %q", rec.Body.String())
	}
	if strings.Contains(rec.Body.String(), "route") {
		t.Fatalf("healthz leaked route: %s", rec.Body.String())
	}
}

func TestStatsFlushUsesInjectedClockAndResets(t *testing.T) {
	now := time.Date(2026, 9, 30, 12, 0, 0, 0, time.UTC)
	var buf bytes.Buffer
	h := NewHub(Config{Now: func() time.Time { return now }}, log.New(&buf, "", 0))
	defer h.Close()
	h.noteHostRegister()
	h.noteClientOpen()
	h.noteStream()
	h.noteReject("DEVICE_LIMIT")
	h.noteReject("not a code")
	h.noteBytes(12*1024*1024 + 300*1024)
	h.maybeFlushStats()
	if buf.Len() != 0 {
		t.Fatalf("flushed too early: %s", buf.String())
	}
	now = now.Add(5 * time.Minute)
	h.maybeFlushStats()
	line := buf.String()
	for _, want := range []string{"stats hosts=1", "streams=1", "open=1", "DEVICE_LIMIT=1", "OTHER=1", "bytes=12.3MiB"} {
		if !strings.Contains(line, want) {
			t.Fatalf("missing %s in %s", want, line)
		}
	}
	if strings.Contains(line, "10.") || strings.Contains(line, "route=") {
		t.Fatalf("stats leaked address or route: %s", line)
	}
	buf.Reset()
	now = now.Add(5 * time.Minute)
	h.maybeFlushStats()
	if !strings.Contains(buf.String(), "hosts=0") {
		t.Fatalf("counters were not reset: %s", buf.String())
	}
}
