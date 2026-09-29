package dlp

import "testing"

func TestHostConnectionLimitConfiguration(t *testing.T) {
	if got := DefaultConfig().IPMaxHostConns; got != 256 {
		t.Fatalf("default host connection limit=%d, want 256", got)
	}
	t.Setenv("DLP_IP_MAX_HOST_CONNS", "96")
	cfg, err := LoadConfig()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.IPMaxHostConns != 96 {
		t.Fatalf("configured host connection limit=%d, want 96", cfg.IPMaxHostConns)
	}
	t.Setenv("DLP_IP_MAX_HOST_CONNS", "63")
	if _, err := LoadConfig(); err == nil {
		t.Fatal("host connection limit below protocol minimum was accepted")
	}
}
