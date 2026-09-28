package main

import (
	"log"
	"net/http"
	"os"
	"time"

	"github.com/lunaship/dsh-links/relay/internal/dlp"
)

func main() {
	cfg, err := dlp.LoadConfig()
	if err != nil {
		log.Fatalf("invalid DLP configuration: %v", err)
	}
	hub := dlp.NewHub(cfg, log.New(os.Stderr, "dlp-relay ", log.LstdFlags))
	server := &http.Server{Addr: cfg.Listen, Handler: hub.Handler(), ReadHeaderTimeout: 5 * time.Second}
	log.Printf("DLP Relay listening")
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatalf("DLP Relay stopped: %v", err)
	}
}
