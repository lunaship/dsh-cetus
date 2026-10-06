package content

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestDLPushContentSealOpen(t *testing.T) {
	var file struct {
		Prefix string `json:"aad_prefix_utf8"`
		Cases  []struct {
			ID        string `json:"id"`
			DeviceID  string `json:"deviceId"`
			Key       string `json:"key"`
			Nonce     string `json:"nonce"`
			AAD       string `json:"aad_utf8"`
			Plaintext string `json:"plaintext_utf8"`
			Ct        string `json:"ct"`
		} `json:"cases"`
	}
	mustJSON(t, "content/dlpush-v1-seal-open.json", &file)
	if file.Prefix != AADPrefix {
		t.Fatalf("prefix %q, want %q", file.Prefix, AADPrefix)
	}
	for _, c := range file.Cases {
		t.Run(c.ID, func(t *testing.T) {
			if string(AAD(c.DeviceID)) != c.AAD {
				t.Fatalf("aad %q, want %q", AAD(c.DeviceID), c.AAD)
			}
			key := mustHex(t, c.Key)
			nonce := mustHex(t, c.Nonce)
			got, err := SealWithNonce(key, nonce, []byte(c.Plaintext), []byte(c.AAD))
			if err != nil {
				t.Fatal(err)
			}
			wire := base64.StdEncoding.EncodeToString(got)
			if wire != c.Ct {
				t.Fatalf("ct\n got %s\nwant %s", wire, c.Ct)
			}
			if !bytes.Equal(got[:len(nonce)], nonce) {
				t.Fatal("wire does not start with nonce")
			}
			pt, err := OpenStd(key, []byte(c.Ct), []byte(c.AAD))
			if err != nil {
				t.Fatal(err)
			}
			if string(pt) != c.Plaintext {
				t.Fatalf("plaintext %q", pt)
			}
		})
	}
}

func TestDLPushContentNegative(t *testing.T) {
	var pos struct {
		Cases []struct {
			DeviceID  string `json:"deviceId"`
			Key       string `json:"key"`
			Plaintext string `json:"plaintext_utf8"`
			Ct        string `json:"ct"`
		} `json:"cases"`
	}
	mustJSON(t, "content/dlpush-v1-seal-open.json", &pos)
	base := pos.Cases[0]
	var neg struct {
		WrongPrefix string `json:"wrong_aad_prefix_utf8"`
		MaxAge      int    `json:"max_age_seconds"`
		Cases       []struct {
			ID     string `json:"id"`
			Mutate string `json:"mutate"`
			AAD    string `json:"aad_utf8"`
			TS     int64  `json:"ts"`
			Now    int64  `json:"now"`
			MaxAge int    `json:"max_age_seconds"`
			Expect string `json:"expect"`
		} `json:"cases"`
	}
	mustJSON(t, "content/dlpush-v1-negative.json", &neg)
	if neg.WrongPrefix != "dlpush/1 token|" {
		t.Fatalf("wrong prefix %q", neg.WrongPrefix)
	}
	key := mustHex(t, base.Key)
	raw, err := base64.StdEncoding.DecodeString(base.Ct)
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range neg.Cases {
		t.Run(c.ID, func(t *testing.T) {
			switch c.Mutate {
			case "tag":
				bad := append([]byte(nil), raw...)
				bad[len(bad)-1] ^= 0x01
				if _, err := Open(key, bad, AAD(base.DeviceID)); err == nil {
					t.Fatal("mutated tag opened")
				}
			case "ciphertext":
				bad := append([]byte(nil), raw...)
				bad[12] ^= 0x01
				if _, err := Open(key, bad, AAD(base.DeviceID)); err == nil {
					t.Fatal("mutated ciphertext opened")
				}
			case "wrong_prefix":
				if c.Expect != "open_failed" {
					t.Fatalf("expect %q", c.Expect)
				}
				if c.AAD != neg.WrongPrefix+base.DeviceID {
					t.Fatalf("aad %q", c.AAD)
				}
				if _, err := Open(key, raw, []byte(c.AAD)); err == nil {
					t.Fatal("token-prefix aad opened content")
				}
			case "ts":
				if c.Expect != "generic_copy" {
					t.Fatalf("expect %q", c.Expect)
				}
				if c.Now-c.TS <= int64(c.MaxAge) {
					t.Fatalf("vector is not expired: now %d ts %d max %d", c.Now, c.TS, c.MaxAge)
				}
				var msg struct {
					TS int64 `json:"ts"`
				}
				if err := json.Unmarshal([]byte(base.Plaintext), &msg); err != nil {
					t.Fatal(err)
				}
				if msg.TS != c.TS {
					t.Fatalf("plaintext ts %d, vector %d", msg.TS, c.TS)
				}
			default:
				t.Fatalf("unknown mutate %q", c.Mutate)
			}
		})
	}
}

func mustJSON(t *testing.T, rel string, dest any) {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("..", "..", "..", "testdata", "push", rel))
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(b, dest); err != nil {
		t.Fatal(err)
	}
}

func mustHex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}
