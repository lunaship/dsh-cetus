package dlp

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http/httptest"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func testServer(t *testing.T, cfg Config) *httptest.Server {
	t.Helper()
	_, server := testServerWithLogger(t, cfg, log.New(io.Discard, "", 0))
	return server
}

func testServerWithLogger(t *testing.T, cfg Config, logger *log.Logger) (*Hub, *httptest.Server) {
	t.Helper()
	h := NewHub(cfg, logger)
	s := httptest.NewServer(h.Handler())
	t.Cleanup(func() { h.Close(); s.Close() })
	return h, s
}

func wsURL(server *httptest.Server) string {
	return "ws" + strings.TrimPrefix(server.URL, "http") + "/ws"
}

func dialWithHello(t *testing.T, ctx context.Context, server *httptest.Server) (*websocket.Conn, []byte) {
	t.Helper()
	c, _, err := websocket.Dial(ctx, wsURL(server), nil)
	if err != nil {
		t.Fatal(err)
	}
	c.SetReadLimit(MaxDataMessage)
	typ, raw, err := readMessage(ctx, c, MaxControlBytes)
	if err != nil || typ != websocket.MessageText {
		t.Fatalf("read hello: type=%v err=%v", typ, err)
	}
	frame, err := ParseControl(raw)
	if err != nil {
		t.Fatal(err)
	}
	challengeText, err := stringField(frame, "ch")
	if err != nil {
		t.Fatal(err)
	}
	challenge, err := decodeB64(challengeText, 32)
	if err != nil {
		t.Fatal(err)
	}
	return c, challenge
}

func register(t *testing.T, ctx context.Context, server *httptest.Server, seed []byte) (*websocket.Conn, [16]byte) {
	t.Helper()
	private := ed25519.NewKeyFromSeed(seed)
	pub := private.Public().(ed25519.PublicKey)
	c, challenge := dialWithHello(t, ctx, server)
	transcript, _ := RegisterTranscript(challenge, pub)
	frame := message("host_register", map[string]any{"v": 1, "pub": encodeB64(pub), "sig": encodeB64(ed25519.Sign(private, transcript))})
	if err := c.Write(ctx, websocket.MessageText, frame); err != nil {
		t.Fatal(err)
	}
	_, raw, err := readMessage(ctx, c, MaxControlBytes)
	if err != nil {
		t.Fatal(err)
	}
	response, err := ParseControl(raw)
	if err != nil {
		t.Fatal(err)
	}
	if typ, _ := stringField(response, "t"); typ != "registered" {
		t.Fatalf("expected registered, got %s", typ)
	}
	routeID, _ := RouteID(pub)
	return c, routeID
}

func clientOpen(t *testing.T, ctx context.Context, server *httptest.Server, route [16]byte) (*websocket.Conn, error) {
	t.Helper()
	c, _ := dialWithHello(t, ctx, server)
	key := make([]byte, 16)
	nonce := make([]byte, 16)
	_, _ = rand.Read(key)
	_, _ = rand.Read(nonce)
	stamp := time.Now().Unix()
	transcript, _ := ClientTranscript(route[:], 1, key, uint64(stamp), nonce)
	mac, _ := ClientMAC(make([]byte, 32), transcript)
	frame := message("client_open", map[string]any{"v": 1, "route": encodeB64(route[:]), "kind": "device", "key": encodeB64(key), "ts": stamp, "nonce": encodeB64(nonce), "mac": encodeB64(mac)})
	if err := c.Write(ctx, websocket.MessageText, frame); err != nil {
		t.Fatal(err)
	}
	return c, nil
}

func readJSON(t *testing.T, ctx context.Context, c *websocket.Conn) map[string]json.RawMessage {
	t.Helper()
	typ, raw, err := readMessage(ctx, c, MaxControlBytes)
	if err != nil || typ != websocket.MessageText {
		t.Fatalf("read control: type=%v err=%v", typ, err)
	}
	frame, err := ParseControl(raw)
	if err != nil {
		t.Fatal(err)
	}
	return frame
}

func TestRegisterAuthenticationAndReplacement(t *testing.T) {
	cfg := DefaultConfig()
	s := testServer(t, cfg)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	seed := make([]byte, 32)
	first, _ := register(t, ctx, s, seed)
	second, _ := register(t, ctx, s, seed)
	if status := websocket.CloseStatus(readErr(ctx, first)); status != 4010 {
		t.Fatalf("old route connection close=%d, want 4010", status)
	}
	_ = second.Close(websocket.StatusNormalClosure, "")
}

func readErr(ctx context.Context, c *websocket.Conn) error { _, _, err := c.Reader(ctx); return err }

func TestClientOfflineTimeoutAndAgentReject(t *testing.T) {
	t.Run("offline", func(t *testing.T) {
		cfg := DefaultConfig()
		s := testServer(t, cfg)
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		var route [16]byte
		c, _ := clientOpen(t, ctx, s, route)
		if code, _ := stringField(readJSON(t, ctx, c), "code"); code != "ROUTE_OFFLINE" {
			t.Fatalf("got %q", code)
		}
		if status := websocket.CloseStatus(readErr(ctx, c)); status != 4003 {
			t.Fatalf("close=%d", status)
		}
	})
	t.Run("open timeout", func(t *testing.T) {
		cfg := DefaultConfig()
		cfg.OpenTimeout = 30 * time.Millisecond
		s := testServer(t, cfg)
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		ctrl, route := register(t, ctx, s, make([]byte, 32))
		defer ctrl.Close(websocket.StatusNormalClosure, "")
		c, _ := clientOpen(t, ctx, s, route)
		_ = readJSON(t, ctx, ctrl)
		if code, _ := stringField(readJSON(t, ctx, c), "code"); code != "OPEN_TIMEOUT" {
			t.Fatalf("got %q", code)
		}
		if status := websocket.CloseStatus(readErr(ctx, c)); status != 4006 {
			t.Fatalf("close=%d", status)
		}
	})
	t.Run("agent rejection", func(t *testing.T) {
		cfg := DefaultConfig()
		s := testServer(t, cfg)
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		ctrl, route := register(t, ctx, s, make([]byte, 32))
		defer ctrl.Close(websocket.StatusNormalClosure, "")
		c, _ := clientOpen(t, ctx, s, route)
		open := readJSON(t, ctx, ctrl)
		sid, _ := stringField(open, "sid")
		_ = ctrl.Write(ctx, websocket.MessageText, message("reject", map[string]any{"sid": sid, "code": "UNKNOWN_KEY"}))
		if code, _ := stringField(readJSON(t, ctx, c), "code"); code != "UNKNOWN_KEY" {
			t.Fatalf("got %q", code)
		}
		if status := websocket.CloseStatus(readErr(ctx, c)); status != 4007 {
			t.Fatalf("close=%d", status)
		}
	})
}

func TestHostAcceptRejectsUnknownCrossRouteBadSignatureAndOldChallenge(t *testing.T) {
	for _, scenario := range []string{"unknown sid", "cross route", "bad signature", "old challenge"} {
		t.Run(scenario, func(t *testing.T) {
			cfg := DefaultConfig()
			cfg.OpenTimeout = 100 * time.Millisecond
			s := testServer(t, cfg)
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			seedA := make([]byte, 32)
			ctrlA, routeA := register(t, ctx, s, seedA)
			defer ctrlA.Close(websocket.StatusNormalClosure, "")
			seedB := make([]byte, 32)
			seedB[0] = 1
			ctrlB, _ := register(t, ctx, s, seedB)
			defer ctrlB.Close(websocket.StatusNormalClosure, "")
			client, _ := clientOpen(t, ctx, s, routeA)
			defer client.Close(websocket.StatusNormalClosure, "")
			open := readJSON(t, ctx, ctrlA)
			sidText, _ := stringField(open, "sid")

			var oldChallenge []byte
			if scenario == "old challenge" {
				stale, staleChallenge := dialWithHello(t, ctx, s)
				oldChallenge = staleChallenge
				_ = stale.Close(websocket.StatusNormalClosure, "")
			}
			data, challenge := dialWithHello(t, ctx, s)
			private := ed25519.NewKeyFromSeed(seedA)
			pub := private.Public().(ed25519.PublicKey)
			acceptSID := sidText
			if scenario == "unknown sid" {
				acceptSID = encodeB64(make([]byte, 16))
			}
			if scenario == "cross route" {
				private = ed25519.NewKeyFromSeed(seedB)
				pub = private.Public().(ed25519.PublicKey)
			}
			signChallenge := challenge
			if scenario == "old challenge" {
				signChallenge = oldChallenge
			}
			acceptSIDRaw, _ := decodeB64(acceptSID, 16)
			transcript, _ := AcceptTranscript(signChallenge, pub, acceptSIDRaw)
			signature := ed25519.Sign(private, transcript)
			if scenario == "bad signature" {
				signature = make([]byte, ed25519.SignatureSize)
			}
			if err := data.Write(ctx, websocket.MessageText, message("host_accept", map[string]any{"v": 1, "pub": encodeB64(pub), "sid": acceptSID, "sig": encodeB64(signature)})); err != nil {
				t.Fatal(err)
			}
			if got, _ := stringField(readJSON(t, ctx, data), "t"); got != "error" {
				t.Fatalf("host data got %q", got)
			}
			if status := websocket.CloseStatus(readErr(ctx, data)); status != 4000 && status != 4002 {
				t.Fatalf("host data close=%d", status)
			}
			if code, _ := stringField(readJSON(t, ctx, client), "code"); code != "OPEN_TIMEOUT" {
				t.Fatalf("client error=%q", code)
			}
		})
	}
}

// §5.4 / §5.7：客户端在首条消息超时内一言不发，Relay 必须先发 error 再以 4000 关闭，
// 而不是直接断开 TCP（读 ctx 超时会让 coder/websocket 直接拆连接）。
func TestFirstMessageTimeoutSendsProtocolErrorAnd4000(t *testing.T) {
	cfg := DefaultConfig()
	cfg.FirstMessageTimeout = 80 * time.Millisecond
	s := testServer(t, cfg)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	c, _ := dialWithHello(t, ctx, s)
	defer c.CloseNow()
	if code, _ := stringField(readJSON(t, ctx, c), "code"); code != "PROTOCOL_ERROR" {
		t.Fatalf("error code=%q", code)
	}
	if status := websocket.CloseStatus(readErr(ctx, c)); status != 4000 {
		t.Fatalf("close=%d", status)
	}
}

func TestDataIdleTimeoutAndLifetimeCloseCodes(t *testing.T) {
	for _, scenario := range []struct {
		name       string
		idle, life time.Duration
		close      int
	}{
		{name: "idle timeout", idle: 40 * time.Millisecond, life: time.Second, close: 4008},
		{name: "lifetime limit", idle: time.Second, life: 40 * time.Millisecond, close: 4009},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			cfg := DefaultConfig()
			cfg.IdleTimeout, cfg.MaxLifetime = scenario.idle, scenario.life
			s := testServer(t, cfg)
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			seed := make([]byte, 32)
			private := ed25519.NewKeyFromSeed(seed)
			ctrl, route := register(t, ctx, s, seed)
			defer ctrl.Close(websocket.StatusNormalClosure, "")
			client, _ := clientOpen(t, ctx, s, route)
			open := readJSON(t, ctx, ctrl)
			sidText, _ := stringField(open, "sid")
			sid, _ := decodeB64(sidText, 16)
			data, challenge := dialWithHello(t, ctx, s)
			pub := private.Public().(ed25519.PublicKey)
			transcript, _ := AcceptTranscript(challenge, pub, sid)
			if err := data.Write(ctx, websocket.MessageText, message("host_accept", map[string]any{"v": 1, "pub": encodeB64(pub), "sid": sidText, "sig": encodeB64(ed25519.Sign(private, transcript))})); err != nil {
				t.Fatal(err)
			}
			_ = readJSON(t, ctx, data)
			_ = readJSON(t, ctx, client)
			if got := websocket.CloseStatus(readErr(ctx, client)); int(got) != scenario.close {
				t.Fatalf("close=%d want=%d", got, scenario.close)
			}
			_ = data.Close(websocket.StatusNormalClosure, "")
		})
	}
}

func TestClientConnectionAndStreamLimits(t *testing.T) {
	t.Run("per IP concurrent client connections", func(t *testing.T) {
		cfg := DefaultConfig()
		cfg.IPMaxConns = 16
		s := testServer(t, cfg)
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		held := make([]*websocket.Conn, 0, 16)
		for i := 0; i < cfg.IPMaxConns; i++ {
			c, _ := dialWithHello(t, ctx, s)
			held = append(held, c)
		}
		defer func() {
			for _, c := range held {
				_ = c.Close(websocket.StatusNormalClosure, "")
			}
		}()
		rejected, _ := dialWithHello(t, ctx, s)
		if code, _ := stringField(readJSON(t, ctx, rejected), "code"); code != "SERVER_BUSY" {
			t.Fatalf("error=%s", code)
		}
		if got := websocket.CloseStatus(readErr(ctx, rejected)); int(got) != 4005 {
			t.Fatalf("close=%d", got)
		}
	})
	t.Run("per route concurrent streams", func(t *testing.T) {
		cfg := DefaultConfig()
		cfg.RouteMaxStreams = 16
		cfg.OpenTimeout = 300 * time.Millisecond
		s := testServer(t, cfg)
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		ctrl, route := register(t, ctx, s, make([]byte, 32))
		defer ctrl.Close(websocket.StatusNormalClosure, "")
		clients := make([]*websocket.Conn, 0, 17)
		for i := 0; i < cfg.RouteMaxStreams; i++ {
			c, _ := clientOpen(t, ctx, s, route)
			clients = append(clients, c)
			_ = readJSON(t, ctx, ctrl)
		}
		rejected, _ := clientOpen(t, ctx, s, route)
		if code, _ := stringField(readJSON(t, ctx, rejected), "code"); code != "SERVER_BUSY" {
			t.Fatalf("error=%s", code)
		}
		if got := websocket.CloseStatus(readErr(ctx, rejected)); int(got) != 4005 {
			t.Fatalf("close=%d", got)
		}
		for _, c := range clients {
			_ = c.Close(websocket.StatusNormalClosure, "")
		}
	})
	t.Run("global concurrent streams", func(t *testing.T) {
		cfg := DefaultConfig()
		cfg.MaxStreams = 1
		cfg.OpenTimeout = 300 * time.Millisecond
		s := testServer(t, cfg)
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		ctrl, route := register(t, ctx, s, make([]byte, 32))
		defer ctrl.Close(websocket.StatusNormalClosure, "")
		first, _ := clientOpen(t, ctx, s, route)
		_ = readJSON(t, ctx, ctrl)
		second, _ := clientOpen(t, ctx, s, route)
		if code, _ := stringField(readJSON(t, ctx, second), "code"); code != "SERVER_BUSY" {
			t.Fatalf("error=%s", code)
		}
		if got := websocket.CloseStatus(readErr(ctx, second)); int(got) != 4005 {
			t.Fatalf("close=%d", got)
		}
		_ = first.Close(websocket.StatusNormalClosure, "")
	})
	t.Run("per IP open burst", func(t *testing.T) {
		cfg := DefaultConfig()
		cfg.OpenTimeout = 500 * time.Millisecond
		s := testServer(t, cfg)
		ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
		defer cancel()
		ctrl, route := register(t, ctx, s, make([]byte, 32))
		defer ctrl.Close(websocket.StatusNormalClosure, "")
		clients := make([]*websocket.Conn, 0, 21)
		for i := 0; i < 20; i++ {
			c, _ := clientOpen(t, ctx, s, route)
			clients = append(clients, c)
		}
		for i := 0; i < 20; i++ {
			_ = readJSON(t, ctx, ctrl)
		}
		rejected, _ := clientOpen(t, ctx, s, route)
		if code, _ := stringField(readJSON(t, ctx, rejected), "code"); code != "RATE_LIMITED" {
			t.Fatalf("error=%s", code)
		}
		if got := websocket.CloseStatus(readErr(ctx, rejected)); int(got) != 4004 {
			t.Fatalf("close=%d", got)
		}
		for _, c := range clients {
			_ = c.Close(websocket.StatusNormalClosure, "")
		}
	})
}

func TestRelayDataBridgeEchoesFiveMiBBothDirections(t *testing.T) {
	cfg := DefaultConfig()
	cfg.IdleTimeout = time.Minute
	cfg.MaxLifetime = time.Hour
	s := testServer(t, cfg)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	seed := make([]byte, 32)
	private := ed25519.NewKeyFromSeed(seed)
	ctrl, route := register(t, ctx, s, seed)
	defer ctrl.Close(websocket.StatusNormalClosure, "")
	client, _ := clientOpen(t, ctx, s, route)
	open := readJSON(t, ctx, ctrl)
	sidText, _ := stringField(open, "sid")
	sid, _ := decodeB64(sidText, 16)
	data, challenge := dialWithHello(t, ctx, s)
	pub := private.Public().(ed25519.PublicKey)
	transcript, _ := AcceptTranscript(challenge, pub, sid)
	accept := message("host_accept", map[string]any{"v": 1, "pub": encodeB64(pub), "sid": sidText, "sig": encodeB64(ed25519.Sign(private, transcript))})
	if err := data.Write(ctx, websocket.MessageText, accept); err != nil {
		t.Fatal(err)
	}
	if got, _ := stringField(readJSON(t, ctx, data), "t"); got != "ready" {
		t.Fatalf("Agent got %q", got)
	}
	if got, _ := stringField(readJSON(t, ctx, client), "t"); got != "ready" {
		t.Fatalf("client got %q", got)
	}

	want := make([]byte, 5*1024*1024)
	for i := range want {
		want[i] = byte(i*31 + 7)
	}
	echoDone := make(chan error, 1)
	go func() {
		for offset := 0; offset < len(want); {
			typ, reader, err := data.Reader(ctx)
			if err != nil {
				echoDone <- err
				return
			}
			if typ != websocket.MessageBinary {
				echoDone <- fmt.Errorf("unexpected message type %v", typ)
				return
			}
			chunk, err := io.ReadAll(reader)
			if err != nil {
				echoDone <- err
				return
			}
			writer, err := data.Writer(ctx, websocket.MessageBinary)
			if err != nil {
				echoDone <- err
				return
			}
			if _, err := writer.Write(chunk); err != nil {
				echoDone <- err
				return
			}
			if err := writer.Close(); err != nil {
				echoDone <- err
				return
			}
			offset += len(chunk)
		}
		echoDone <- nil
	}()
	for offset := 0; offset < len(want); {
		end := min(offset+64*1024, len(want))
		if err := client.Write(ctx, websocket.MessageBinary, want[offset:end]); err != nil {
			t.Fatal(err)
		}
		_, reader, err := client.Reader(ctx)
		if err != nil {
			t.Fatal(err)
		}
		got, err := io.ReadAll(reader)
		if err != nil {
			t.Fatal(err)
		}
		if string(got) != string(want[offset:end]) {
			t.Fatalf("echo differs at byte %d", offset)
		}
		offset = end
	}
	if err := <-echoDone; err != nil {
		t.Fatal(err)
	}
	_ = client.Close(websocket.StatusNormalClosure, "")
}

func TestRelayBackpressureBoundsHeapWhenClientDoesNotRead(t *testing.T) {
	cfg := DefaultConfig()
	cfg.IdleTimeout = time.Minute
	cfg.MaxLifetime = time.Hour
	s := testServer(t, cfg)
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	seed := make([]byte, 32)
	private := ed25519.NewKeyFromSeed(seed)
	ctrl, route := register(t, ctx, s, seed)
	defer ctrl.Close(websocket.StatusNormalClosure, "")
	client, _ := clientOpen(t, ctx, s, route)
	open := readJSON(t, ctx, ctrl)
	sidText, _ := stringField(open, "sid")
	sid, _ := decodeB64(sidText, 16)
	data, challenge := dialWithHello(t, ctx, s)
	pub := private.Public().(ed25519.PublicKey)
	transcript, _ := AcceptTranscript(challenge, pub, sid)
	accept := message("host_accept", map[string]any{"v": 1, "pub": encodeB64(pub), "sid": sidText, "sig": encodeB64(ed25519.Sign(private, transcript))})
	if err := data.Write(ctx, websocket.MessageText, accept); err != nil {
		t.Fatal(err)
	}
	if got, _ := stringField(readJSON(t, ctx, data), "t"); got != "ready" {
		t.Fatalf("Agent got %q", got)
	}
	if got, _ := stringField(readJSON(t, ctx, client), "t"); got != "ready" {
		t.Fatalf("client got %q", got)
	}
	defer client.Close(websocket.StatusNormalClosure, "")
	defer data.Close(websocket.StatusNormalClosure, "")

	const total = 20 * 1024 * 1024
	chunk := make([]byte, MaxDataMessage)
	writerCtx, writerCancel := context.WithTimeout(ctx, 8*time.Second)
	defer writerCancel()
	var written atomic.Int64
	writeResult := make(chan error, 1)
	var before, after runtime.MemStats
	runtime.GC()
	runtime.ReadMemStats(&before)
	go func() {
		for written.Load() < total {
			if err := data.Write(writerCtx, websocket.MessageBinary, chunk); err != nil {
				writeResult <- err
				return
			}
			written.Add(int64(len(chunk)))
		}
		writeResult <- nil
	}()

	lastProgress := written.Load()
	lastChange := time.Now()
	stalled := false
	for {
		select {
		case err := <-writeResult:
			if err != nil && !errors.Is(err, context.DeadlineExceeded) {
				t.Fatalf("Agent write failed: %v", err)
			}
			goto measured
		default:
		}
		current := written.Load()
		if current != lastProgress {
			lastProgress, lastChange = current, time.Now()
		}
		if time.Since(lastChange) >= 200*time.Millisecond {
			stalled = true
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
measured:
	runtime.GC()
	runtime.ReadMemStats(&after)
	growth := int64(after.HeapAlloc) - int64(before.HeapAlloc)
	if growth >= 8*1024*1024 {
		t.Fatalf("Relay heap grew by %d bytes while client read was stalled", growth)
	}
	if !stalled || written.Load() == 0 {
		t.Fatalf("did not observe backpressure after a 20 MiB write attempt: stalled=%t written=%d", stalled, written.Load())
	}
	writerCancel()
	select {
	case <-writeResult:
	case <-time.After(time.Second):
		t.Fatal("Agent writer did not stop after cancellation")
	}
}

func TestTextFrameInDataStageClosesPair(t *testing.T) {
	cfg := DefaultConfig()
	s := testServer(t, cfg)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	seed := make([]byte, 32)
	private := ed25519.NewKeyFromSeed(seed)
	ctrl, route := register(t, ctx, s, seed)
	defer ctrl.Close(websocket.StatusNormalClosure, "")
	client, _ := clientOpen(t, ctx, s, route)
	open := readJSON(t, ctx, ctrl)
	sidText, _ := stringField(open, "sid")
	sid, _ := decodeB64(sidText, 16)
	data, challenge := dialWithHello(t, ctx, s)
	pub := private.Public().(ed25519.PublicKey)
	transcript, _ := AcceptTranscript(challenge, pub, sid)
	if err := data.Write(ctx, websocket.MessageText, message("host_accept", map[string]any{"v": 1, "pub": encodeB64(pub), "sid": sidText, "sig": encodeB64(ed25519.Sign(private, transcript))})); err != nil {
		t.Fatal(err)
	}
	_ = readJSON(t, ctx, data)
	_ = readJSON(t, ctx, client)
	if err := client.Write(ctx, websocket.MessageText, []byte("not binary")); err != nil {
		t.Fatal(err)
	}
	closeErr := readErr(ctx, data)
	if status := websocket.CloseStatus(closeErr); status != 4000 {
		t.Fatalf("data peer close=%d err=%v", status, closeErr)
	}
}

func TestDailyByteQuotaStopsForwardingBeforeLimitIsExceeded(t *testing.T) {
	cfg := DefaultConfig()
	cfg.RouteDailyBytes = 32 * 1024
	s := testServer(t, cfg)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	seed := make([]byte, 32)
	private := ed25519.NewKeyFromSeed(seed)
	ctrl, route := register(t, ctx, s, seed)
	defer ctrl.Close(websocket.StatusNormalClosure, "")
	client, _ := clientOpen(t, ctx, s, route)
	defer client.Close(websocket.StatusNormalClosure, "")
	open := readJSON(t, ctx, ctrl)
	sidText, _ := stringField(open, "sid")
	sid, _ := decodeB64(sidText, 16)
	data, challenge := dialWithHello(t, ctx, s)
	defer data.Close(websocket.StatusNormalClosure, "")
	pub := private.Public().(ed25519.PublicKey)
	transcript, _ := AcceptTranscript(challenge, pub, sid)
	accept := message("host_accept", map[string]any{"v": 1, "pub": encodeB64(pub), "sid": sidText, "sig": encodeB64(ed25519.Sign(private, transcript))})
	if err := data.Write(ctx, websocket.MessageText, accept); err != nil {
		t.Fatal(err)
	}
	_ = readJSON(t, ctx, data)
	_ = readJSON(t, ctx, client)
	if err := client.Write(ctx, websocket.MessageBinary, make([]byte, 64*1024)); err != nil {
		t.Fatal(err)
	}
	typ, reader, err := data.Reader(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if typ != websocket.MessageBinary {
		t.Fatalf("forwarded message type=%v, want binary", typ)
	}
	forwarded, err := io.ReadAll(reader)
	if err != nil {
		t.Fatal(err)
	}
	if got := len(forwarded); got != int(cfg.RouteDailyBytes) {
		t.Fatalf("forwarded bytes=%d, want exactly quota %d", got, cfg.RouteDailyBytes)
	}
	if status := websocket.CloseStatus(readErr(ctx, data)); int(status) != 4004 {
		t.Fatalf("host close status=%d, want 4004", status)
	}
}

func TestHubCloseClosesControlAndDataConnections(t *testing.T) {
	cfg := DefaultConfig()
	hub, server := testServerWithLogger(t, cfg, log.New(io.Discard, "", 0))
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	seed := make([]byte, 32)
	private := ed25519.NewKeyFromSeed(seed)
	ctrl, route := register(t, ctx, server, seed)
	client, _ := clientOpen(t, ctx, server, route)
	open := readJSON(t, ctx, ctrl)
	sidText, _ := stringField(open, "sid")
	sid, _ := decodeB64(sidText, 16)
	data, challenge := dialWithHello(t, ctx, server)
	pub := private.Public().(ed25519.PublicKey)
	transcript, _ := AcceptTranscript(challenge, pub, sid)
	accept := message("host_accept", map[string]any{"v": 1, "pub": encodeB64(pub), "sid": sidText, "sig": encodeB64(ed25519.Sign(private, transcript))})
	if err := data.Write(ctx, websocket.MessageText, accept); err != nil {
		t.Fatal(err)
	}
	_ = readJSON(t, ctx, data)
	_ = readJSON(t, ctx, client)
	pendingClient, _ := clientOpen(t, ctx, server, route)
	_ = readJSON(t, ctx, ctrl)

	closed := make(chan struct{})
	go func() { hub.Close(); close(closed) }()
	for name, conn := range map[string]*websocket.Conn{"control": ctrl, "client": client, "pending client": pendingClient, "data": data} {
		if status := websocket.CloseStatus(readErr(ctx, conn)); status != websocket.StatusGoingAway {
			t.Errorf("%s close status=%d, want %d", name, status, websocket.StatusGoingAway)
		}
	}
	select {
	case <-closed:
	case <-ctx.Done():
		t.Fatal("Hub.Close did not complete")
	}
}

func TestFuzzParseControlSeeds(t *testing.T) {
	for _, raw := range []string{`{"t":"ping"}`, `[]`, `{"t":"a","t":"b"}`, `null`, `{"nested":{"x":1,"x":2}}`} {
		_, _ = ParseControl([]byte(raw))
	}
}

func FuzzParseControl(f *testing.F) {
	for _, raw := range []string{`{"t":"ping"}`, `[]`, `{"t":"a","t":"b"}`, `null`, `{"v":1}`} {
		f.Add([]byte(raw))
	}
	f.Fuzz(func(t *testing.T, raw []byte) { _, _ = ParseControl(raw) })
}

func TestLogsOmitRouteSecrets(t *testing.T) {
	var output bytes.Buffer
	cfg := DefaultConfig()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_, server := testServerWithLogger(t, cfg, log.New(&output, "", 0))
	seed := bytes.Repeat([]byte{0x42}, 32)
	private := ed25519.NewKeyFromSeed(seed)
	pub := private.Public().(ed25519.PublicKey)
	ctrl, route := register(t, ctx, server, seed)
	defer ctrl.Close(websocket.StatusNormalClosure, "")
	client, _ := dialWithHello(t, ctx, server)
	defer client.Close(websocket.StatusNormalClosure, "")
	keyID := bytes.Repeat([]byte{0x21}, 16)
	key := bytes.Repeat([]byte{0x51}, 32)
	nonce := bytes.Repeat([]byte{0x32}, 16)
	stamp := uint64(time.Now().Unix())
	transcript, err := ClientTranscript(route[:], 1, keyID, stamp, nonce)
	if err != nil {
		t.Fatal(err)
	}
	mac, _ := ClientMAC(key, transcript)
	open := message("client_open", map[string]any{
		"v": 1, "route": encodeB64(route[:]), "kind": "device", "key": encodeB64(keyID),
		"ts": stamp, "nonce": encodeB64(nonce), "mac": encodeB64(mac),
	})
	if err := client.Write(ctx, websocket.MessageText, open); err != nil {
		t.Fatal(err)
	}
	request := readJSON(t, ctx, ctrl)
	sid, _ := stringField(request, "sid")
	if err := ctrl.Write(ctx, websocket.MessageText, message("reject", map[string]any{"sid": sid, "code": "UNKNOWN_KEY"})); err != nil {
		t.Fatal(err)
	}
	if code, _ := stringField(readJSON(t, ctx, client), "code"); code != "UNKNOWN_KEY" {
		t.Fatalf("client rejection code=%q", code)
	}
	if closeCode := websocket.CloseStatus(readErr(ctx, client)); closeCode != 4007 {
		t.Fatalf("client close=%d", closeCode)
	}
	logText := output.String()
	for _, secret := range []string{
		encodeB64(route[:]), encodeB64(pub), encodeB64(seed), encodeB64(keyID),
		encodeB64(key), encodeB64(nonce), encodeB64(mac), "127.0.0.1", string(open),
	} {
		if strings.Contains(logText, secret) {
			t.Fatalf("log contains secret material: %s", secret)
		}
	}
	if !strings.Contains(logText, "event=host_register") || !strings.Contains(logText, "event=client_open") {
		t.Fatalf("full control flow was not logged: %q", logText)
	}
}

func TestParseControlRejectsOversizeAndNestedDuplicate(t *testing.T) {
	if _, err := ParseControl([]byte(`{"x":{"a":1,"a":2}}`)); err == nil {
		t.Fatal("nested duplicate accepted")
	}
	if _, err := ParseControl([]byte(fmt.Sprintf(`{"x":"%s"}`, strings.Repeat("a", MaxControlBytes)))); err == nil {
		t.Fatal("oversize control accepted")
	}
}

func TestLogEventSanitizesUntrustedCodes(t *testing.T) {
	var output bytes.Buffer
	h := NewHub(DefaultConfig(), log.New(&output, "", 0))
	h.logEvent("client_open", "BAD\x1b[31m\nforged", nil, 0)
	if got := output.String(); strings.ContainsAny(got, "\r\x1b") || strings.Count(got, "\n") != 1 || !strings.Contains(got, "code=OTHER") {
		t.Fatalf("untrusted log code was not sanitized: %q", got)
	}
}

func TestOpenRateLimiterHonorsBurst(t *testing.T) {
	lim := newLimiter()
	now := time.Unix(100, 0)
	for i := 0; i < 20; i++ {
		if !lim.allow("client", 60, time.Minute, 20, now) {
			t.Fatalf("burst rejected at %d", i)
		}
	}
	if lim.allow("client", 60, time.Minute, 20, now) {
		t.Fatal("request beyond burst was allowed")
	}
	if !lim.allow("client", 60, time.Minute, 20, now.Add(time.Second)) {
		t.Fatal("token was not replenished")
	}
}

func TestConnectionAccountingUsesConfiguredHostLimitAndReclaimsIPs(t *testing.T) {
	cfg := DefaultConfig()
	cfg.IPMaxConns = 16
	cfg.IPMaxHostConns = 64
	h := NewHub(cfg, nil)
	for i := 0; i < cfg.IPMaxHostConns; i++ {
		if !h.reserveHost("192.0.2.1") {
			t.Fatalf("host connection %d unexpectedly rejected", i+1)
		}
	}
	if h.reserveHost("192.0.2.1") {
		t.Fatal("host connection above configured limit accepted")
	}
	for i := 0; i < cfg.IPMaxHostConns; i++ {
		h.releaseHost("192.0.2.1")
	}
	if len(h.hosts) != 0 {
		t.Fatalf("empty host IP entry retained: %#v", h.hosts)
	}
	for i := 0; i < cfg.IPMaxConns; i++ {
		if !h.promoteClient("192.0.2.2") {
			t.Fatalf("client connection %d unexpectedly rejected", i+1)
		}
	}
	if h.promoteClient("192.0.2.2") {
		t.Fatal("client connection above configured limit accepted")
	}
	for i := 0; i < cfg.IPMaxConns; i++ {
		h.releaseClient("192.0.2.2")
	}
	if len(h.clients) != 0 {
		t.Fatalf("empty client IP entry retained: %#v", h.clients)
	}
}

func TestLimiterPrunesInactiveIPWindows(t *testing.T) {
	lim := newLimiter()
	start := time.Now()
	if !lim.allow("192.0.2.1", 10, time.Minute, 1, start) {
		t.Fatal("initial request rejected")
	}
	if !lim.allow("192.0.2.2", 10, time.Minute, 1, start.Add(11*time.Minute)) {
		t.Fatal("request after cleanup rejected")
	}
	if len(lim.windows) != 1 {
		t.Fatalf("inactive limiter windows were not reclaimed: %#v", lim.windows)
	}
}

func TestDailyByteReservationsAreAtomicAndOfflineRoutesAreSweptSafely(t *testing.T) {
	now := time.Date(2026, 9, 29, 12, 0, 0, 0, time.UTC)
	cfg := DefaultConfig()
	cfg.RouteDailyBytes = 50
	cfg.Now = func() time.Time { return now }
	h := NewHub(cfg, nil)
	var id [16]byte
	id[0] = 1
	h.routes[id] = &route{}
	var accepted atomic.Int64
	var wg sync.WaitGroup
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if ok, _ := h.reserveBytes(id, 1); ok {
				accepted.Add(1)
			}
		}()
	}
	wg.Wait()
	if got := accepted.Load(); got != 50 {
		t.Fatalf("accepted reservations=%d, want 50", got)
	}
	if got := h.routes[id].bytesToday; got != 50 {
		t.Fatalf("reserved bytes=%d, want 50", got)
	}

	var oldID, activeID [16]byte
	oldID[0], activeID[0] = 2, 3
	h.routes[oldID] = &route{bytesDay: "2026-09-28", bytesToday: 40}
	h.routes[activeID] = &route{active: 1}
	h.mu.Lock()
	h.sweepOfflineRoutesLocked(now)
	h.mu.Unlock()
	if _, ok := h.routes[oldID]; ok {
		t.Fatal("prior-day offline route was retained")
	}
	if _, ok := h.routes[activeID]; !ok {
		t.Fatal("active route was swept")
	}
	if _, ok := h.routes[id]; !ok {
		t.Fatal("current-day quota record was swept")
	}
}
