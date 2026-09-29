package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
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

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	serveErr := make(chan error, 1)
	go func() { serveErr <- server.ListenAndServe() }()
	select {
	case <-ctx.Done():
		hub.Close()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdownCtx); err != nil {
			_ = server.Close()
		}
	case err := <-serveErr:
		hub.Close()
		if err != nil && err != http.ErrServerClosed {
			log.Fatalf("DLP Relay stopped: %v", err)
		}
	}
}
