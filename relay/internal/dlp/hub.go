package dlp

import (
	"context"
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

const MaxDataMessage = 256 * 1024

type route struct {
	ctrl        *websocket.Conn
	active      int
	pending     int
	bytesToday  int64
	bytesDay    string
	connections int
}

type pendingStream struct {
	route    [16]byte
	client   *websocket.Conn
	created  time.Time
	accepted chan *websocket.Conn
	result   chan agentRejection
	done     chan struct{}
}

type agentRejection struct {
	code    string
	hostNow int64
}

type Hub struct {
	mu              sync.Mutex
	cfg             Config
	routes          map[[16]byte]*route
	pending         map[[16]byte]*pendingStream
	clients         map[string]int
	hosts           map[string]int
	openLimiter     *limiter
	registerLimiter *limiter
	logger          *log.Logger
	closed          bool
}

func NewHub(cfg Config, logger *log.Logger) *Hub {
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	if logger == nil {
		logger = log.New(io.Discard, "", 0)
	}
	return &Hub{cfg: cfg, routes: map[[16]byte]*route{}, pending: map[[16]byte]*pendingStream{}, clients: map[string]int{}, hosts: map[string]int{}, openLimiter: newLimiter(), registerLimiter: newLimiter(), logger: logger}
}

func (h *Hub) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, "ok")
	})
	mux.HandleFunc("/ws", h.serveHTTP)
	return mux
}

func (h *Hub) Close() {
	h.mu.Lock()
	h.closed = true
	connections := make([]*websocket.Conn, 0)
	for _, r := range h.routes {
		if r.ctrl != nil {
			connections = append(connections, r.ctrl)
		}
	}
	for _, p := range h.pending {
		connections = append(connections, p.client)
	}
	h.mu.Unlock()
	for _, c := range connections {
		_ = c.Close(websocket.StatusGoingAway, "")
	}
}

func (h *Hub) serveHTTP(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/ws" {
		http.NotFound(w, r)
		return
	}
	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{CompressionMode: websocket.CompressionDisabled, InsecureSkipVerify: true})
	if err != nil {
		return
	}
	c.SetReadLimit(MaxControlBytes)
	ip := h.clientIP(r)
	clientReserved := false
	defer func() {
		if clientReserved {
			h.releaseClient(ip)
		}
	}()
	// coder/websocket hijacks the HTTP connection; the request context can be
	// canceled as soon as Accept returns, so WebSocket lifetime has its own ctx.
	ctx := context.Background()
	challenge := make([]byte, 32)
	if _, err := rand.Read(challenge); err != nil {
		return
	}
	if err := h.writeJSON(c, message("hello", map[string]any{"v": 1, "ch": encodeB64(challenge), "now": h.cfg.Now().Unix()})); err != nil {
		return
	}
	if !h.reserveUnknown(ip) {
		h.fail(c, "SERVER_BUSY", 4005)
		return
	}
	clientReserved = true
	firstCtx, cancel := context.WithTimeout(ctx, h.cfg.FirstMessageTimeout)
	kind, raw, err := readMessage(firstCtx, c, MaxControlBytes)
	cancel()
	if err != nil || kind != websocket.MessageText {
		h.fail(c, "PROTOCOL_ERROR", 4000)
		return
	}
	frame, err := ParseControl(raw)
	if err != nil {
		h.fail(c, "PROTOCOL_ERROR", 4000)
		return
	}
	t, err := stringField(frame, "t")
	if err != nil {
		h.fail(c, "PROTOCOL_ERROR", 4000)
		return
	}
	switch t {
	case "host_register":
		h.releaseClient(ip)
		clientReserved = false
		h.serveRegister(ctx, c, ip, challenge, frame)
	case "host_accept":
		h.releaseClient(ip)
		clientReserved = false
		h.serveHostData(ctx, c, ip, challenge, frame)
	case "client_open":
		h.serveClient(ctx, c, ip, frame)
	default:
		h.releaseClient(ip)
		clientReserved = false
		h.fail(c, "PROTOCOL_ERROR", 4000)
	}
}

func (h *Hub) reserveUnknown(ip string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.clients[ip]+h.hosts[ip] >= h.cfg.IPMaxConns {
		return false
	}
	h.clients[ip]++
	return true
}

func (h *Hub) releaseClient(ip string) {
	h.mu.Lock()
	if h.clients[ip] > 0 {
		h.clients[ip]--
	}
	h.mu.Unlock()
}
func (h *Hub) reserveHost(ip string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.clients[ip]+h.hosts[ip] >= 256 {
		return false
	}
	h.hosts[ip]++
	return true
}
func (h *Hub) releaseHost(ip string) {
	h.mu.Lock()
	if h.hosts[ip] > 0 {
		h.hosts[ip]--
	}
	h.mu.Unlock()
}

func (h *Hub) serveRegister(ctx context.Context, c *websocket.Conn, ip string, challenge []byte, frame map[string]json.RawMessage) {
	if !h.reserveHost(ip) {
		h.fail(c, "SERVER_BUSY", 4005)
		return
	}
	defer h.releaseHost(ip)
	if !h.registerLimiter.allow(ip, 10, time.Minute, 10, h.cfg.Now()) {
		h.fail(c, "RATE_LIMITED", 4004)
		return
	}
	version, err := intField(frame, "v")
	if err != nil {
		h.fail(c, "PROTOCOL_ERROR", 4000)
		return
	}
	if version != int64(Version) {
		h.fail(c, "UNSUPPORTED_VERSION", 4001)
		return
	}
	pubText, e1 := stringField(frame, "pub")
	sigText, e2 := stringField(frame, "sig")
	pub, e3 := decodeB64(pubText, ed25519.PublicKeySize)
	sig, e4 := decodeB64(sigText, ed25519.SignatureSize)
	if e1 != nil || e2 != nil || e3 != nil || e4 != nil {
		h.fail(c, "PROTOCOL_ERROR", 4000)
		return
	}
	transcript, _ := RegisterTranscript(challenge, pub)
	if !ed25519.Verify(ed25519.PublicKey(pub), transcript, sig) {
		h.fail(c, "AUTH_FAILED", 4002)
		return
	}
	routeID, _ := RouteID(pub)
	h.mu.Lock()
	if h.closed {
		h.mu.Unlock()
		h.fail(c, "SERVER_BUSY", 4005)
		return
	}
	old := h.routes[routeID]
	if old == nil {
		old = &route{}
		h.routes[routeID] = old
	}
	previous := old.ctrl
	old.ctrl = c
	old.connections++
	h.mu.Unlock()
	if previous != nil && previous != c {
		go previous.Close(websocket.StatusCode(4010), "REPLACED")
	}
	if err := h.writeJSON(c, message("registered", map[string]any{"route": encodeB64(routeID[:]), "ping": 20})); err != nil {
		return
	}
	pingStop := make(chan struct{})
	defer close(pingStop)
	go h.pingConnection(c, pingStop)
	for {
		readCtx, cancel := context.WithTimeout(ctx, 60*time.Second)
		kind, raw, err := readMessage(readCtx, c, MaxControlBytes)
		cancel()
		if err != nil {
			break
		}
		if kind != websocket.MessageText {
			h.fail(c, "PROTOCOL_ERROR", 4000)
			break
		}
		msg, err := ParseControl(raw)
		if err != nil {
			h.fail(c, "PROTOCOL_ERROR", 4000)
			break
		}
		t, err := stringField(msg, "t")
		if err != nil {
			h.fail(c, "PROTOCOL_ERROR", 4000)
			break
		}
		switch t {
		case "ping":
			if h.writeJSON(c, message("pong", map[string]any{})) != nil {
				return
			}
		case "reject":
			sidText, e1 := stringField(msg, "sid")
			code, e2 := stringField(msg, "code")
			sidRaw, e3 := decodeB64(sidText, 16)
			if e1 != nil || e2 != nil || e3 != nil {
				h.fail(c, "PROTOCOL_ERROR", 4000)
				break
			}
			var sid [16]byte
			copy(sid[:], sidRaw)
			h.rejectPending(sid, code, rawFieldInt64(msg, "hostNow"))
		default:
			h.fail(c, "PROTOCOL_ERROR", 4000)
		}
	}
	h.mu.Lock()
	if current := h.routes[routeID]; current != nil && current.ctrl == c {
		current.ctrl = nil
	}
	h.mu.Unlock()
}

func (h *Hub) pingConnection(c *websocket.Conn, stop <-chan struct{}) {
	ticker := time.NewTicker(25 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			err := c.Ping(ctx)
			cancel()
			if err != nil {
				_ = c.CloseNow()
				return
			}
		case <-stop:
			return
		}
	}
}

func (h *Hub) serveClient(ctx context.Context, c *websocket.Conn, ip string, frame map[string]json.RawMessage) {
	version, err := intField(frame, "v")
	if err != nil {
		h.fail(c, "PROTOCOL_ERROR", 4000)
		return
	}
	if version != int64(Version) {
		h.fail(c, "UNSUPPORTED_VERSION", 4001)
		return
	}
	routeText, e1 := stringField(frame, "route")
	kind, e2 := stringField(frame, "kind")
	keyText, e3 := stringField(frame, "key")
	ts, e4 := intField(frame, "ts")
	nonceText, e5 := stringField(frame, "nonce")
	macText, e6 := stringField(frame, "mac")
	routeRaw, e7 := decodeB64(routeText, 16)
	_, e8 := decodeB64(keyText, 16)
	_, e9 := decodeB64(nonceText, 16)
	_, e10 := decodeB64(macText, 32)
	if e1 != nil || e2 != nil || e3 != nil || e4 != nil || e5 != nil || e6 != nil || e7 != nil || e8 != nil || e9 != nil || e10 != nil || (kind != "device" && kind != "bootstrap") || ts < 0 {
		h.fail(c, "PROTOCOL_ERROR", 4000)
		return
	}
	var routeID [16]byte
	copy(routeID[:], routeRaw)
	if !h.openLimiter.allow(ip, h.cfg.IPOpenPerMinute, time.Minute, 20, h.cfg.Now()) {
		h.fail(c, "RATE_LIMITED", 4004)
		return
	}
	h.mu.Lock()
	online := h.routes[routeID] != nil && h.routes[routeID].ctrl != nil
	globalAtLimit := h.activeStreamsLocked() >= h.cfg.MaxStreams
	h.mu.Unlock()
	if !online {
		h.fail(c, "ROUTE_OFFLINE", 4003)
		return
	}
	if globalAtLimit {
		h.fail(c, "SERVER_BUSY", 4005)
		return
	}
	sidBytes := make([]byte, 16)
	if _, err := rand.Read(sidBytes); err != nil {
		h.fail(c, "SERVER_BUSY", 4005)
		return
	}
	var sid [16]byte
	copy(sid[:], sidBytes)
	p := &pendingStream{route: routeID, client: c, created: h.cfg.Now(), accepted: make(chan *websocket.Conn, 1), result: make(chan agentRejection, 1), done: make(chan struct{})}
	h.mu.Lock()
	r := h.routes[routeID]
	if r == nil || r.ctrl == nil {
		h.mu.Unlock()
		h.fail(c, "ROUTE_OFFLINE", 4003)
		return
	}
	if r.active+r.pending >= h.cfg.RouteMaxStreams || h.activeStreamsLocked() >= h.cfg.MaxStreams {
		h.mu.Unlock()
		h.fail(c, "SERVER_BUSY", 4005)
		return
	}
	r.pending++
	h.pending[sid] = p
	ctrl := r.ctrl
	h.mu.Unlock()
	defer h.removePending(sid)
	req := map[string]any{"v": version, "route": routeText, "kind": kind, "key": keyText, "ts": ts, "nonce": nonceText, "mac": macText}
	if err := h.writeJSON(ctrl, message("open", map[string]any{"sid": encodeB64(sid[:]), "req": req})); err != nil {
		h.fail(c, "OPEN_TIMEOUT", 4006)
		return
	}
	timer := time.NewTimer(h.cfg.OpenTimeout)
	defer timer.Stop()
	select {
	case data := <-p.accepted:
		h.mu.Lock()
		if rr := h.routes[routeID]; rr != nil {
			if rr.pending > 0 {
				rr.pending--
			}
			rr.active++
		}
		delete(h.pending, sid)
		h.mu.Unlock()
		_ = h.writeJSON(c, message("ready", map[string]any{}))
		_ = h.writeJSON(data, message("ready", map[string]any{}))
		h.bridge(ctx, sid, routeID, c, data, p.done)
		close(p.done)
	case rejection := <-p.result:
		if rejection.code == "" {
			rejection.code = "SERVER_BUSY"
		}
		fields := map[string]any{"code": rejection.code}
		if rejection.code == "CLOCK_SKEW" && rejection.hostNow > 0 {
			fields["hostNow"] = rejection.hostNow
		}
		h.failFields(c, fields, rejection.code, 4007)
	case <-timer.C:
		h.fail(c, "OPEN_TIMEOUT", 4006)
	case <-ctx.Done():
	}
}

func (h *Hub) activeStreamsLocked() int {
	total := 0
	for _, r := range h.routes {
		total += r.active + r.pending
	}
	return total
}

func (h *Hub) recordBytes(routeID [16]byte, count int64) bool {
	if h.cfg.RouteDailyBytes == 0 {
		return true
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	r := h.routes[routeID]
	if r == nil {
		return false
	}
	day := h.cfg.Now().UTC().Format("2006-01-02")
	if r.bytesDay != day {
		r.bytesDay, r.bytesToday = day, 0
	}
	if r.bytesToday+count > h.cfg.RouteDailyBytes {
		return false
	}
	r.bytesToday += count
	return true
}

func (h *Hub) serveHostData(ctx context.Context, c *websocket.Conn, ip string, challenge []byte, frame map[string]json.RawMessage) {
	if !h.reserveHost(ip) {
		h.fail(c, "SERVER_BUSY", 4005)
		return
	}
	defer h.releaseHost(ip)
	version, err := intField(frame, "v")
	if err != nil || version != 1 {
		h.fail(c, "PROTOCOL_ERROR", 4000)
		return
	}
	pubText, e1 := stringField(frame, "pub")
	sidText, e2 := stringField(frame, "sid")
	sigText, e3 := stringField(frame, "sig")
	pub, e4 := decodeB64(pubText, 32)
	sidRaw, e5 := decodeB64(sidText, 16)
	sig, e6 := decodeB64(sigText, 64)
	if e1 != nil || e2 != nil || e3 != nil || e4 != nil || e5 != nil || e6 != nil {
		h.fail(c, "PROTOCOL_ERROR", 4000)
		return
	}
	var sid [16]byte
	copy(sid[:], sidRaw)
	transcript, _ := AcceptTranscript(challenge, pub, sidRaw)
	if !ed25519.Verify(ed25519.PublicKey(pub), transcript, sig) {
		h.fail(c, "AUTH_FAILED", 4002)
		return
	}
	routeID, _ := RouteID(pub)
	h.mu.Lock()
	p := h.pending[sid]
	valid := p != nil && p.route == routeID
	h.mu.Unlock()
	if !valid {
		h.fail(c, "PROTOCOL_ERROR", 4000)
		return
	}
	select {
	case p.accepted <- c:
	default:
		h.fail(c, "PROTOCOL_ERROR", 4000)
	}
	select {
	case <-ctx.Done():
	case <-p.result:
	case <-p.done:
	}
}

func (h *Hub) rejectPending(sid [16]byte, code string, hostNow int64) {
	if len(code) > 32 || strings.ContainsAny(code, " \t\r\n") {
		code = "PROTOCOL_ERROR"
	}
	h.mu.Lock()
	p := h.pending[sid]
	h.mu.Unlock()
	if p == nil {
		return
	}
	_ = hostNow // forwarded as a single error frame by the waiting client handler.
	select {
	case p.result <- agentRejection{code: code, hostNow: hostNow}:
	default:
	}
}

func (h *Hub) removePending(sid [16]byte) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if p := h.pending[sid]; p != nil {
		delete(h.pending, sid)
		if r := h.routes[p.route]; r != nil && r.pending > 0 {
			r.pending--
		}
	}
}

func (h *Hub) bridge(ctx context.Context, sid, routeID [16]byte, client, host *websocket.Conn, done chan struct{}) {
	host.SetReadLimit(MaxDataMessage + 1)
	client.SetReadLimit(MaxDataMessage + 1)
	bridgeCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	deadline := h.cfg.Now().Add(h.cfg.MaxLifetime)
	activity := make(chan struct{}, 1)
	var once sync.Once
	closing := make(chan struct{})
	closed := make(chan struct{})
	finish := func(code int, reason string) {
		once.Do(func() {
			close(closing)
			go func() {
				defer close(closed)
				results := make(chan struct{}, 2)
				go func() { _ = client.Close(websocket.StatusCode(code), reason); results <- struct{}{} }()
				go func() { _ = host.Close(websocket.StatusCode(code), reason); results <- struct{}{} }()
				<-results
				<-results
				cancel()
			}()
		})
	}
	results := make(chan error, 2)
	copyDirection := func(src, dst *websocket.Conn) {
		for {
			typ, reader, err := src.Reader(bridgeCtx)
			if err != nil {
				results <- err
				return
			}
			if typ != websocket.MessageBinary {
				finish(4000, "PROTOCOL_ERROR")
				results <- errors.New("non-binary data frame")
				return
			}
			writeCtx, writeCancel := context.WithTimeout(bridgeCtx, h.cfg.WriteTimeout)
			writer, err := dst.Writer(writeCtx, websocket.MessageBinary)
			if err == nil {
				written, copyErr := io.CopyN(writer, reader, MaxDataMessage+1)
				closeErr := writer.Close()
				if written > MaxDataMessage {
					err = errors.New("data frame too large")
					finish(4000, "PROTOCOL_ERROR")
				} else if copyErr != nil && copyErr != io.EOF {
					err = copyErr
				} else {
					err = closeErr
				}
				if err == nil && !h.recordBytes(routeID, written) {
					err = errors.New("route daily byte limit exceeded")
					finish(4004, "RATE_LIMITED")
				}
			}
			writeCancel()
			if err != nil {
				results <- err
				return
			}
			select {
			case activity <- struct{}{}:
			default:
			}
		}
	}
	go copyDirection(client, host)
	go copyDirection(host, client)
	idle := time.NewTimer(h.cfg.IdleTimeout)
	defer idle.Stop()
	life := time.NewTimer(time.Until(deadline))
	defer life.Stop()
	ping := time.NewTicker(25 * time.Second)
	defer ping.Stop()
	active := true
	for active {
		select {
		case <-results:
			finish(1000, "")
			active = false
		case <-activity:
			if !idle.Stop() {
				select {
				case <-idle.C:
				default:
				}
			}
			idle.Reset(h.cfg.IdleTimeout)
		case <-idle.C:
			finish(4008, "IDLE_TIMEOUT")
			active = false
		case <-life.C:
			finish(4009, "LIFETIME_EXCEEDED")
			active = false
		case <-ping.C:
			pingCtx, pingCancel := context.WithTimeout(bridgeCtx, 10*time.Second)
			pingErr := make(chan error, 2)
			go func() { pingErr <- client.Ping(pingCtx) }()
			go func() { pingErr <- host.Ping(pingCtx) }()
			errA, errB := <-pingErr, <-pingErr
			pingCancel()
			if errA != nil || errB != nil {
				finish(1001, "")
				active = false
			}
		case <-bridgeCtx.Done():
			active = false
		}
	}
	select {
	case <-closing:
		select {
		case <-closed:
		case <-time.After(6 * time.Second):
		}
	default:
	}
	h.mu.Lock()
	if r := h.routes[routeID]; r != nil && r.active > 0 {
		r.active--
	}
	h.mu.Unlock()
	_ = sid
	_ = done
}

func (h *Hub) writeJSON(c *websocket.Conn, raw []byte) error {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	return c.Write(ctx, websocket.MessageText, raw)
}

func (h *Hub) fail(c *websocket.Conn, code string, closeCode int) {
	h.failFields(c, map[string]any{"code": code}, code, closeCode)
}

func (h *Hub) failFields(c *websocket.Conn, fields map[string]any, reason string, closeCode int) {
	_ = h.writeJSON(c, message("error", fields))
	_ = c.Close(websocket.StatusCode(closeCode), reason)
}

func readMessage(ctx context.Context, c *websocket.Conn, limit int64) (websocket.MessageType, []byte, error) {
	typ, reader, err := c.Reader(ctx)
	if err != nil {
		return 0, nil, err
	}
	data, err := io.ReadAll(io.LimitReader(reader, limit+1))
	if err != nil {
		return typ, nil, err
	}
	if int64(len(data)) > limit {
		return typ, nil, errors.New("message too large")
	}
	return typ, data, nil
}

func rawFieldInt64(raw map[string]json.RawMessage, key string) int64 {
	value, _ := intField(raw, key)
	return value
}

func (h *Hub) clientIP(r *http.Request) string {
	peer, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		peer = r.RemoteAddr
	}
	addr, err := netip.ParseAddr(strings.Trim(peer, "[]"))
	if err != nil {
		return "unknown"
	}
	trusted := false
	for _, prefix := range h.cfg.TrustedProxies {
		if prefix.Contains(addr) {
			trusted = true
			break
		}
	}
	if !trusted {
		return addr.String()
	}
	parts := strings.Split(r.Header.Get("X-Forwarded-For"), ",")
	for i := len(parts) - 1; i >= 0; i-- {
		candidate, parseErr := netip.ParseAddr(strings.TrimSpace(parts[i]))
		if parseErr != nil {
			continue
		}
		isTrusted := false
		for _, prefix := range h.cfg.TrustedProxies {
			if prefix.Contains(candidate) {
				isTrusted = true
				break
			}
		}
		if !isTrusted {
			return candidate.String()
		}
	}
	return addr.String()
}

func routeLogID(dailyKey []byte, routeID []byte) string {
	h := hmac.New(sha256.New, dailyKey)
	h.Write(routeID)
	return hex.EncodeToString(h.Sum(nil)[:8])
}

func (h *Hub) logEvent(event, code string, routeID []byte) {
	day := h.cfg.Now().UTC().Format("2006-01-02")
	dailyKey := sha256.Sum256([]byte("DLP1 daily log\x00" + day))
	h.logger.Printf("event=%s code=%s route=%s", event, code, routeLogID(dailyKey[:], routeID))
}

func (h *Hub) String() string {
	h.mu.Lock()
	defer h.mu.Unlock()
	return fmt.Sprintf("routes=%d streams=%d", len(h.routes), len(h.pending))
}
func rawRouteString(route [16]byte) string { return strconv.Itoa(int(route[0])) }
