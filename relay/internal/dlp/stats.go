package dlp

import (
	"fmt"
	"sort"
	"strings"
	"time"
)

func (h *Hub) SetVersion(version string) {
	if version == "" {
		version = "dev"
	}
	h.version = version
}

func (h *Hub) noteHostRegister() {
	h.statsMu.Lock()
	h.statHosts++
	h.statsMu.Unlock()
}

func (h *Hub) noteClientOpen() {
	h.statsMu.Lock()
	h.statOpen++
	h.statsMu.Unlock()
}

func (h *Hub) noteStream() {
	h.statsMu.Lock()
	h.statStreams++
	h.statsMu.Unlock()
}

func (h *Hub) noteBytes(n int64) {
	if n <= 0 {
		return
	}
	h.statsMu.Lock()
	h.statBytes += n
	h.statsMu.Unlock()
}

func (h *Hub) noteReject(code string) {
	if !safeLogCode(code) {
		code = "OTHER"
	}
	h.statsMu.Lock()
	h.statReject[code]++
	h.statsMu.Unlock()
}

func (h *Hub) statsLoop() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-h.done:
			return
		case <-ticker.C:
			h.maybeFlushStats()
		}
	}
}

func (h *Hub) maybeFlushStats() {
	now := h.cfg.Now()
	h.statsMu.Lock()
	defer h.statsMu.Unlock()
	if now.Sub(h.statsAt) < 5*time.Minute {
		return
	}
	line := formatStats(h.statHosts, h.statStreams, h.statOpen, h.statReject, h.statBytes)
	h.statHosts = 0
	h.statStreams = 0
	h.statOpen = 0
	h.statBytes = 0
	h.statReject = map[string]int64{}
	h.statsAt = now
	h.logger.Printf("%s", line)
}

func formatStats(hosts, streams, open int64, reject map[string]int64, bytes int64) string {
	keys := make([]string, 0, len(reject))
	for code := range reject {
		keys = append(keys, code)
	}
	sort.Strings(keys)
	parts := make([]string, 0, len(keys))
	for _, code := range keys {
		parts = append(parts, fmt.Sprintf("%s=%d", code, reject[code]))
	}
	return fmt.Sprintf(
		"stats hosts=%d streams=%d open=%d reject{%s} bytes=%s",
		hosts, streams, open, strings.Join(parts, ","), formatBytes(bytes),
	)
}

func formatBytes(n int64) string {
	const mib = 1024 * 1024
	if n >= mib {
		return fmt.Sprintf("%.1fMiB", float64(n)/float64(mib))
	}
	if n >= 1024 {
		return fmt.Sprintf("%.1fKiB", float64(n)/1024)
	}
	return fmt.Sprintf("%dB", n)
}
