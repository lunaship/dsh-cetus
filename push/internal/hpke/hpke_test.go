package hpke

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/cloudflare/circl/hpke"
	"github.com/cloudflare/circl/kem"
)

func TestRFC9180A2Base(t *testing.T) {
	var vec struct {
		Info       string `json:"info"`
		IkmE       string `json:"ikmE"`
		SkRm       string `json:"skRm"`
		PkRm       string `json:"pkRm"`
		Enc        string `json:"enc"`
		Encryption struct {
			Aad string `json:"aad"`
			Ct  string `json:"ct"`
			Pt  string `json:"pt"`
		} `json:"encryption"`
	}
	mustJSON(t, "hpke/rfc9180-a2-base.json", &vec)

	scheme := X25519Scheme()
	pk := mustPublic(t, scheme, vec.PkRm)
	sk := mustPrivate(t, scheme, vec.SkRm)
	info := mustHex(t, vec.Info)
	seed := mustHex(t, vec.IkmE)
	aad := mustHex(t, vec.Encryption.Aad)
	pt := mustHex(t, vec.Encryption.Pt)
	wantEnc := mustHex(t, vec.Enc)
	wantCt := mustHex(t, vec.Encryption.Ct)

	suite := hpke.NewSuite(hpke.KEM_X25519_HKDF_SHA256, hpke.KDF_HKDF_SHA256, hpke.AEAD_ChaCha20Poly1305)
	sender, err := suite.NewSender(pk, info)
	if err != nil {
		t.Fatalf("sender: %v", err)
	}
	enc, sealer, err := sender.Setup(bytes.NewReader(seed))
	if err != nil {
		t.Fatalf("setup: %v", err)
	}
	if !bytes.Equal(enc, wantEnc) {
		t.Fatalf("enc\n got %x\nwant %x", enc, wantEnc)
	}
	ct, err := sealer.Seal(pt, aad)
	if err != nil {
		t.Fatalf("seal: %v", err)
	}
	if !bytes.Equal(ct, wantCt) {
		t.Fatalf("ct\n got %x\nwant %x", ct, wantCt)
	}

	got, err := OpenContext(sk, wantEnc, wantCt, info, aad)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	if !bytes.Equal(got, pt) {
		t.Fatalf("plaintext\n got %x\nwant %x", got, pt)
	}
}

func TestDLPushV1SealOpen(t *testing.T) {
	var file struct {
		Info  string `json:"info_utf8"`
		Cases []struct {
			ID        string `json:"id"`
			Kid       string `json:"kid"`
			IkmE      string `json:"ikmE"`
			SkRm      string `json:"skRm"`
			PkRm      string `json:"pkRm"`
			AAD       string `json:"aad_utf8"`
			Plaintext string `json:"plaintext_utf8"`
			Sealed    Sealed `json:"sealed"`
		} `json:"cases"`
	}
	mustJSON(t, "hpke/dlpush-v1-seal-open.json", &file)
	if file.Info != Info {
		t.Fatalf("info %q, want %q", file.Info, Info)
	}
	if len(file.Cases) == 0 {
		t.Fatal("no cases")
	}
	for _, c := range file.Cases {
		t.Run(c.ID, func(t *testing.T) {
			if string(AAD(c.Kid)) != c.AAD {
				t.Fatalf("aad %q, want %q", AAD(c.Kid), c.AAD)
			}
			pk := mustPublic(t, X25519Scheme(), c.PkRm)
			sk := mustPrivate(t, X25519Scheme(), c.SkRm)
			got, err := SealDeterministic(c.Kid, pk, []byte(c.Plaintext), mustHex(t, c.IkmE))
			if err != nil {
				t.Fatalf("seal: %v", err)
			}
			if got.V != c.Sealed.V || got.Kid != c.Sealed.Kid || got.Enc != c.Sealed.Enc || got.Ct != c.Sealed.Ct {
				t.Fatalf("sealed\n got %+v\nwant %+v", got, c.Sealed)
			}
			gw, err := NewGateway(c.Kid, sk)
			if err != nil {
				t.Fatalf("gateway: %v", err)
			}
			pt, err := gw.Open(&c.Sealed)
			if err != nil {
				t.Fatalf("open: %v", err)
			}
			if string(pt) != c.Plaintext {
				t.Fatalf("plaintext %q, want %q", pt, c.Plaintext)
			}
		})
	}
}

func TestDLPushV1Negative(t *testing.T) {
	var pos struct {
		Cases []struct {
			Kid       string `json:"kid"`
			SkRm      string `json:"skRm"`
			Plaintext string `json:"plaintext_utf8"`
			Sealed    Sealed `json:"sealed"`
		} `json:"cases"`
	}
	mustJSON(t, "hpke/dlpush-v1-seal-open.json", &pos)
	base := pos.Cases[0]
	var neg struct {
		WrongPrefix string `json:"wrong_aad_prefix_utf8"`
		Cases       []struct {
			ID     string `json:"id"`
			Mutate string `json:"mutate"`
			AAD    string `json:"aad_utf8"`
			Expect string `json:"expect"`
		} `json:"cases"`
	}
	mustJSON(t, "hpke/dlpush-v1-negative.json", &neg)
	if neg.WrongPrefix != "dlpush/1 content|" {
		t.Fatalf("wrong prefix %q", neg.WrongPrefix)
	}
	sk := mustPrivate(t, X25519Scheme(), base.SkRm)
	gw, err := NewGateway(base.Kid, sk)
	if err != nil {
		t.Fatal(err)
	}
	enc, err := base.Sealed.EncBytes()
	if err != nil {
		t.Fatal(err)
	}
	ct, err := base.Sealed.CtBytes()
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range neg.Cases {
		t.Run(c.ID, func(t *testing.T) {
			if c.Expect != "open_failed" {
				t.Fatalf("expect %q", c.Expect)
			}
			switch c.Mutate {
			case "enc":
				bad := append([]byte(nil), enc...)
				bad[0] ^= 0x01
				sealed := base.Sealed
				sealed.Enc = base64.RawURLEncoding.EncodeToString(bad)
				if _, err := gw.Open(&sealed); err == nil {
					t.Fatal("mutated enc opened")
				}
			case "ct":
				bad := append([]byte(nil), ct...)
				bad[len(bad)-1] ^= 0x01
				sealed := base.Sealed
				sealed.Ct = base64.RawURLEncoding.EncodeToString(bad)
				if _, err := gw.Open(&sealed); err == nil {
					t.Fatal("mutated ct opened")
				}
			case "aad":
				aad := append(AAD(base.Kid), 'x')
				if _, err := OpenContext(sk, enc, ct, []byte(Info), aad); err == nil {
					t.Fatal("mutated aad opened")
				}
			case "kid":
				sealed := base.Sealed
				sealed.Kid = base.Kid + "-other"
				if _, err := gw.Open(&sealed); err == nil {
					t.Fatal("mutated kid opened")
				}
			case "wrong_prefix":
				if c.AAD != neg.WrongPrefix+base.Kid {
					t.Fatalf("aad %q", c.AAD)
				}
				if _, err := OpenContext(sk, enc, ct, []byte(Info), []byte(c.AAD)); err == nil {
					t.Fatal("content-prefix aad opened a token seal")
				}
			default:
				t.Fatalf("unknown mutate %q", c.Mutate)
			}
		})
	}
}

func mustJSON(t *testing.T, rel string, dest any) {
	t.Helper()
	b, err := os.ReadFile(testdata(rel))
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(b, dest); err != nil {
		t.Fatal(err)
	}
}

func testdata(rel string) string {
	return filepath.Join("..", "..", "..", "testdata", "push", rel)
}

func mustHex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func mustPublic(t *testing.T, scheme kem.Scheme, hexKey string) kem.PublicKey {
	t.Helper()
	pk, err := scheme.UnmarshalBinaryPublicKey(mustHex(t, hexKey))
	if err != nil {
		t.Fatal(err)
	}
	return pk
}

func mustPrivate(t *testing.T, scheme kem.Scheme, hexKey string) kem.PrivateKey {
	t.Helper()
	sk, err := scheme.UnmarshalBinaryPrivateKey(mustHex(t, hexKey))
	if err != nil {
		t.Fatal(err)
	}
	return sk
}
