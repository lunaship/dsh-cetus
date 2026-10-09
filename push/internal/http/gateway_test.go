package http

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/lunaship/dsh-links/push/internal/apns"
	"github.com/lunaship/dsh-links/push/internal/hpke"
	"github.com/lunaship/dsh-links/push/internal/limit"
	"github.com/sideshow/apns2/token"
)

func TestPushRateLimit(t *testing.T) {
	gw, sealed := testGateway(t, scriptedSender{status: 200})
	gw.Limiter = limit.New(1, 10, func() time.Time { return time.Unix(1_700_000_000, 0) })
	h := gw.Handle()

	rec := postPush(t, h, sealed)
	if rec.Code != 200 {
		t.Fatalf("first status %d body %s", rec.Code, rec.Body.String())
	}
	rec = postPush(t, h, sealed)
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("limited status %d body %s", rec.Code, rec.Body.String())
	}
	if strings.Contains(rec.Body.String(), sealed.Ct) || strings.Contains(rec.Body.String(), "apnsToken") {
		t.Fatalf("429 body echoed a secret: %s", rec.Body.String())
	}
}

func TestPushGonePassthrough(t *testing.T) {
	for _, sc := range []scriptedSender{
		{status: 410, reason: "Unregistered"},
		{status: 400, reason: "BadDeviceToken"},
	} {
		t.Run(sc.reason, func(t *testing.T) {
			gw, sealed := testGateway(t, sc)
			rec := postPush(t, gw.Handle(), sealed)
			if rec.Code != http.StatusGone {
				t.Fatalf("status %d body %s", rec.Code, rec.Body.String())
			}
			var body PushResponse
			if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
				t.Fatal(err)
			}
			if body.Error != "bad_device_token" || body.Ok {
				t.Fatalf("body %+v", body)
			}
		})
	}
}

func TestPushLogsOmitSecrets(t *testing.T) {
	var buf bytes.Buffer
	log.SetOutput(&buf)
	t.Cleanup(func() { log.SetOutput(io.Discard) })

	tokenHex := strings.Repeat("cd", 32)
	contentCt := base64.StdEncoding.EncodeToString([]byte("ciphertext-not-a-token"))
	gw, sealed := testGateway(t, scriptedSender{status: 200})
	rec := postPush(t, gw.Handle(), sealed)
	if rec.Code != 200 {
		t.Fatalf("status %d body %s", rec.Code, rec.Body.String())
	}
	logs := buf.String()
	for _, secret := range []string{sealed.Enc, sealed.Ct, contentCt, tokenHex, "127.0.0.1", "apnsToken"} {
		if strings.Contains(logs, secret) {
			t.Fatalf("log contains %q:\n%s", secret, logs)
		}
	}
	if !strings.Contains(logs, "status=200") || !strings.Contains(logs, "kind=alert") {
		t.Fatalf("log missing allowed fields:\n%s", logs)
	}
}

func TestSealedStringAndUnknownKid(t *testing.T) {
	gw, sealed := testGateway(t, scriptedSender{status: 200})
	h := gw.Handle()
	contentCt := base64.StdEncoding.EncodeToString([]byte("plugin-ciphertext"))

	body := []byte(`{"kid":"` + sealed.Kid + `","sealed":"not-an-object","kind":"alert","ct":"` + contentCt + `","collapseId":"c","priority":"high","expiresIn":60}`)
	rec := postRaw(t, h, body)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("string sealed status %d body %s", rec.Code, rec.Body.String())
	}

	// Unknown kid is rejected before Open. The gateway must not try another key.
	unknown := PushRequest{
		Kid:        "other-kid",
		Sealed:     &hpke.Sealed{V: sealed.V, Kid: "other-kid", Enc: sealed.Enc, Ct: sealed.Ct},
		Kind:       "alert",
		Ct:         contentCt,
		CollapseID: "c",
		Priority:   "high",
		ExpiresIn:  60,
	}
	raw, err := json.Marshal(unknown)
	if err != nil {
		t.Fatal(err)
	}
	rec = postRaw(t, h, raw)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("unknown kid status %d body %s", rec.Code, rec.Body.String())
	}
	var resp PushResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	if resp.Error != "unknown_kid" {
		t.Fatalf("error %q, want unknown_kid", resp.Error)
	}
}

func TestFakeAPNsGone(t *testing.T) {
	fake := http.NewServeMux()
	var seenToken string
	fake.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		seenToken = strings.TrimPrefix(r.URL.Path, "/3/")
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(410)
		_, _ = w.Write([]byte(`{"reason":"Unregistered"}`))
	})
	srv := httptest.NewServer(fake)
	t.Cleanup(srv.Close)

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	sender := apns.NewTokenSender(&token.Token{AuthKey: key, KeyID: "KEYID", TeamID: "TEAMID"}, "TEAMID", "dev.deeplinks.ios", strings.TrimPrefix(srv.URL, "http://"))
	sender.SetTestClient(srv.Client(), "http")
	gw, sealed := testGateway(t, nil)
	gw.Sender = sender
	rec := postPush(t, gw.Handle(), sealed)
	if rec.Code != http.StatusGone {
		t.Fatalf("status %d body %s", rec.Code, rec.Body.String())
	}
	if seenToken != strings.Repeat("cd", 32) {
		t.Fatalf("fake saw token %q", seenToken)
	}
}

func TestFakeAPNsPassesContentCiphertext(t *testing.T) {
	contentCt := base64.StdEncoding.EncodeToString([]byte("plugin-content-ciphertext"))
	var seenBody []byte
	var seenType, seenPriority, seenCollapse string
	fake := http.NewServeMux()
	fake.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		var err error
		seenBody, err = io.ReadAll(r.Body)
		if err != nil {
			http.Error(w, "read", http.StatusInternalServerError)
			return
		}
		seenType = r.Header.Get("apns-push-type")
		seenPriority = r.Header.Get("apns-priority")
		seenCollapse = r.Header.Get("apns-collapse-id")
		w.WriteHeader(http.StatusOK)
	})
	srv := httptest.NewServer(fake)
	t.Cleanup(srv.Close)

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	sender := apns.NewTokenSender(&token.Token{AuthKey: key, KeyID: "KEYID", TeamID: "TEAMID"}, "TEAMID", "dev.deeplinks.ios", strings.TrimPrefix(srv.URL, "http://"))
	sender.SetTestClient(srv.Client(), "http")
	gw, sealed := testGateway(t, sender)

	body, err := json.Marshal(PushRequest{
		Kid:        sealed.Kid,
		Sealed:     sealed,
		Kind:       "alert",
		Ct:         contentCt,
		CollapseID: "device-session-approval",
		Priority:   "high",
		ExpiresIn:  60,
	})
	if err != nil {
		t.Fatal(err)
	}
	rec := postRaw(t, gw.Handle(), body)
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d body %s", rec.Code, rec.Body.String())
	}
	var payload struct {
		E string `json:"e"`
		K string `json:"k"`
	}
	if err := json.Unmarshal(seenBody, &payload); err != nil {
		t.Fatalf("apns body %s: %v", seenBody, err)
	}
	if payload.E != contentCt {
		t.Fatalf("APNs e = %q, want content ct %q", payload.E, contentCt)
	}
	if payload.K != sealed.Kid || seenType != "alert" || seenPriority != "10" || seenCollapse != "device-session-approval" {
		t.Fatalf("headers/payload kid=%s type=%s priority=%s collapse=%s", payload.K, seenType, seenPriority, seenCollapse)
	}
}

func FuzzPushBody(f *testing.F) {
	gw, sealed := testGateway(f, scriptedSender{status: 200})
	h := gw.Handle()
	good, err := json.Marshal(PushRequest{
		Kid: sealed.Kid, Sealed: sealed, Kind: "alert",
		Ct: "AQID", CollapseID: "c", Priority: "high", ExpiresIn: 60,
	})
	if err != nil {
		f.Fatal(err)
	}
	f.Add(good)
	f.Add([]byte(`{"kid":"test-kid","sealed":"string"}`))
	f.Add([]byte(`{"kid":"nope","sealed":{"v":1,"kid":"nope","enc":"aa","ct":"bb"}}`))
	f.Add([]byte("not json"))
	f.Add([]byte(nil))
	f.Fuzz(func(t *testing.T, body []byte) {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodPost, "/v1/push", bytes.NewReader(body))
		h.ServeHTTP(rec, req)
		switch rec.Code {
		case http.StatusOK, http.StatusBadRequest, http.StatusTooManyRequests, http.StatusGone, http.StatusBadGateway, http.StatusMethodNotAllowed:
		default:
			t.Fatalf("unexpected status %d", rec.Code)
		}
	})
}

func testGateway(t testing.TB, sender apns.Sender) (*Gateway, *hpke.Sealed) {
	t.Helper()
	scheme := hpke.X25519Scheme()
	pk, sk, err := scheme.GenerateKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	kid := "test-kid"
	key, err := hpke.NewGateway(kid, sk)
	if err != nil {
		t.Fatal(err)
	}
	plain := []byte(`{"apnsToken":"` + strings.Repeat("cd", 32) + `","env":"sandbox","bundleId":"dev.deeplinks.ios"}`)
	sealed, err := hpke.SealFor(kid, pk, plain)
	if err != nil {
		t.Fatal(err)
	}
	if sender == nil {
		sender = scriptedSender{status: 200}
	}
	return &Gateway{
		Keys:    map[string]*hpke.Gateway{kid: key},
		Sender:  sender,
		Version: "test",
	}, sealed
}

func postPush(t *testing.T, h http.Handler, sealed *hpke.Sealed) *httptest.ResponseRecorder {
	t.Helper()
	body, err := json.Marshal(PushRequest{
		Kid:        sealed.Kid,
		Sealed:     sealed,
		Kind:       "alert",
		Ct:         base64.StdEncoding.EncodeToString([]byte("ciphertext-not-a-token")),
		CollapseID: "collapse",
		Priority:   "high",
		ExpiresIn:  60,
	})
	if err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest(http.MethodPost, "/v1/push", bytes.NewReader(body))
	req.RemoteAddr = "127.0.0.1:1234"
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func postRaw(t testing.TB, h http.Handler, body []byte) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/v1/push", bytes.NewReader(body))
	req.RemoteAddr = "127.0.0.1:1234"
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

type scriptedSender struct {
	status int
	reason string
}

func (s scriptedSender) Send(ctx context.Context, req apns.Request) (apns.Result, error) {
	res := apns.ResultFor(s.status, s.reason)
	if res.Gone {
		return res, apns.ErrBadDeviceToken
	}
	return res, nil
}

// RFC 0002 §5.6 把「payload 顶层只有 aps / e / k」定为**硬性**约束：
// 多一个字段就多一份锁屏可见面（deviceId / sessionId / 标题原文 / 工具名
// 都会因此暴露给 APNs 与通知中心）。
//
// 之前没有任何测试盯着这条 —— 谁顺手加个字段都不会被发现。这里按
// "允许清单" 断言，而不是检查黑名单字段：新增字段必须显式改这个测试，
// 从而强制过一次 RFC §5.6 的复查。
func TestAlertPayloadTopLevelIsExactlyApsEK(t *testing.T) {
	contentCt := base64.StdEncoding.EncodeToString([]byte("plugin-content-ciphertext"))
	var seen map[string]json.RawMessage
	fake := http.NewServeMux()
	fake.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		raw, err := io.ReadAll(r.Body)
		if err != nil {
			http.Error(w, "read", http.StatusInternalServerError)
			return
		}
		if err := json.Unmarshal(raw, &seen); err != nil {
			http.Error(w, "decode", http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusOK)
	})
	srv := httptest.NewServer(fake)
	t.Cleanup(srv.Close)

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	sender := apns.NewTokenSender(
		&token.Token{AuthKey: key, KeyID: "KEYID", TeamID: "TEAMID"}, "TEAMID",
		"dev.deeplinks.ios", strings.TrimPrefix(srv.URL, "http://"))
	sender.SetTestClient(srv.Client(), "http")
	gw, sealed := testGateway(t, sender)

	body, err := json.Marshal(PushRequest{
		Kid: sealed.Kid, Sealed: sealed, Kind: "alert", Ct: contentCt, ExpiresIn: 60,
	})
	if err != nil {
		t.Fatal(err)
	}
	if rec := postRaw(t, gw.Handle(), body); rec.Code != http.StatusOK {
		t.Fatalf("status %d body %s", rec.Code, rec.Body.String())
	}

	allowed := map[string]bool{"aps": true, "e": true, "k": true}
	for field := range seen {
		if !allowed[field] {
			t.Errorf(
				"payload 顶层出现额外字段 %q —— RFC 0002 §5.6 只允许 aps/e/k；"+
					"新增字段会扩宽锁屏可见面，必须显式更新本条测试与 RFC", field)
		}
	}
	for field := range allowed {
		if _, ok := seen[field]; !ok {
			t.Errorf("payload 顶层缺少必需字段 %q", field)
		}
	}

	// 顺带钉住 §5.6 的另外两条：aps 必须带 mutable-content（否则 NSE 不会运行），
	// 且 alert 文本是通用文案，不得包含业务内容。
	var aps struct {
		Alert struct {
			Title string `json:"title"`
			Body  string `json:"body"`
		} `json:"alert"`
		MutableContent int `json:"mutable-content"`
	}
	if err := json.Unmarshal(seen["aps"], &aps); err != nil {
		t.Fatal(err)
	}
	if aps.MutableContent != 1 {
		t.Errorf("aps.mutable-content = %d, want 1（NSE 需要它才会被拉起）", aps.MutableContent)
	}
	if aps.Alert.Title != "cetus" || aps.Alert.Body != "有新的任务动态" {
		t.Errorf("锁屏回退文案应为通用文案，实际 title=%q body=%q", aps.Alert.Title, aps.Alert.Body)
	}
}
