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
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func testServer(t *testing.T, cfg Config) *httptest.Server {
	t.Helper()
	h := NewHub(cfg, log.New(io.Discard, "", 0))
	s := httptest.NewServer(h.Handler())
	t.Cleanup(func() { h.Close(); s.Close() })
	return s
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

	var before, after runtime.MemStats
	runtime.GC()
	runtime.ReadMemStats(&before)
	lastProgress := written.Load()
	lastChange := time.Now()
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
	if written.Load() == 0 {
		t.Fatal("Agent wrote no data before backpressure")
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
	h := NewHub(DefaultConfig(), log.New(&output, "", 0))
	seed := bytes.Repeat([]byte{0x42}, 32)
	private := ed25519.NewKeyFromSeed(seed)
	pub := private.Public().(ed25519.PublicKey)
	route, err := RouteID(pub)
	if err != nil {
		t.Fatal(err)
	}
	h.logEvent("register", "ok", route[:])
	logText := output.String()
	for _, secret := range []string{encodeB64(route[:]), encodeB64(pub), encodeB64(seed)} {
		if strings.Contains(logText, secret) {
			t.Fatalf("log contains secret material: %s", secret)
		}
	}
	if logText == "" {
		t.Fatal("expected event log")
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
