package main

import (
	"context"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/lunaship/dsh-links/relay/internal/dlp"
)

// 构建时注入：go build -ldflags "-X main.version=2026-09-30"
var version = "dev"

func main() {
	cfg, err := dlp.LoadConfig()
	if err != nil {
		log.Fatalf("invalid DLP configuration: %v", err)
	}
	hub := dlp.NewHub(cfg, log.New(os.Stderr, "dlp-relay ", log.LstdFlags))
	hub.SetVersion(version)
	log.Printf("DLP Relay version %s", version)
	server := &http.Server{Handler: hub.Handler(), ReadHeaderTimeout: 5 * time.Second}
	// 先 Listen 再 Serve：DLP_LISTEN 可以写端口 0，由系统分配后把实际地址打出来（本机端到端脚本靠它取端口）
	listener, err := net.Listen("tcp", cfg.Listen)
	if err != nil {
		log.Fatalf("DLP Relay listen failed: %v", err)
	}
	log.Printf("DLP Relay listening on %s", listener.Addr())

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	serveErr := make(chan error, 1)
	go func() { serveErr <- server.Serve(listener) }()
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
