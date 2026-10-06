// Package hpke implements the DLPUSH/1 token-sealing encryption context.
//
// The algorithm combination is fixed by docs/rfc/0002-push-gateway.md §5.3:
// RFC 9180 base mode with DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 /
// ChaCha20-Poly1305, info UTF-8 "dlpush/1 token", and associated data
// "dlpush/1 token|" + kid (domain-separated from content encryption).
package hpke

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"

	"github.com/cloudflare/circl/hpke"
	"github.com/cloudflare/circl/kem"
)

// Suite is the fixed DLPUSH/1 HPKE suite.
var Suite = hpke.NewSuite(hpke.KEM_X25519_HKDF_SHA256, hpke.KDF_HKDF_SHA256, hpke.AEAD_ChaCha20Poly1305)

// Info is the HPKE setup context: UTF-8 "dlpush/1 token".
const Info = "dlpush/1 token"

// AADPrefix is the domain separator for token sealing: UTF-8 "dlpush/1 token|".
// It MUST NOT be shared with content encryption, whose prefix is
// "dlpush/1 content|" (see internal/content).
const AADPrefix = "dlpush/1 token|"

// AAD builds the token-seal associated data for a key id.
func AAD(kid string) []byte { return []byte(AADPrefix + kid) }

// Sealed is the on-wire representation of an HPKE-encapsulated token.
// It is a JSON object, never a serialized string (RFC 0002 §5.3).
type Sealed struct {
	V   int    `json:"v"`
	Kid string `json:"kid"`
	Enc string `json:"enc"` // base64url, 32-byte encapsulated key
	Ct  string `json:"ct"`  // base64url, HPKE ciphertext
}

// EncBytes decodes the base64url encapsulated key.
func (s *Sealed) EncBytes() ([]byte, error) {
	b, err := base64.RawURLEncoding.DecodeString(s.Enc)
	if err != nil {
		return nil, fmt.Errorf("hpke: decode enc: %w", err)
	}
	if len(b) != 32 {
		return nil, fmt.Errorf("hpke: enc length %d, want 32", len(b))
	}
	return b, nil
}

// CtBytes decodes the base64url ciphertext.
func (s *Sealed) CtBytes() ([]byte, error) {
	b, err := base64.RawURLEncoding.DecodeString(s.Ct)
	if err != nil {
		return nil, fmt.Errorf("hpke: decode ct: %w", err)
	}
	if len(b) < 16 {
		return nil, fmt.Errorf("hpke: ct length %d too short for ChaCha20-Poly1305", len(b))
	}
	return b, nil
}

// Hash is the rate-limit key: sha256 of the Sealed JSON object.
func (s *Sealed) Hash() [32]byte {
	b, _ := json.Marshal(s)
	return sha256.Sum256(b)
}

// SealFor seals pt for pkR and records the kid in the returned Sealed
// object. The ephemeral key is random. Shared vectors use SealDeterministic.
func SealFor(kid string, pkR kem.PublicKey, pt []byte) (*Sealed, error) {
	return seal(kid, pkR, pt, []byte(Info), AAD(kid), nil)
}

// SealDeterministic seals pt with a fixed 32-byte encapsulation seed (RFC 9180
// ikmE). Tests use it to reproduce published enc/ct; production callers must
// use SealFor so the ephemeral key is fresh.
func SealDeterministic(kid string, pkR kem.PublicKey, pt, seed []byte) (*Sealed, error) {
	if len(seed) != X25519Scheme().EncapsulationSeedSize() {
		return nil, fmt.Errorf("hpke: encapsulation seed length %d, want %d", len(seed), X25519Scheme().EncapsulationSeedSize())
	}
	return seal(kid, pkR, pt, []byte(Info), AAD(kid), bytes.NewReader(seed))
}

func seal(kid string, pkR kem.PublicKey, pt, info, aad []byte, rnd io.Reader) (*Sealed, error) {
	snd, err := Suite.NewSender(pkR, info)
	if err != nil {
		return nil, fmt.Errorf("hpke: sender: %w", err)
	}
	enc, sealer, err := snd.Setup(rnd)
	if err != nil {
		return nil, fmt.Errorf("hpke: setup: %w", err)
	}
	ct, err := sealer.Seal(pt, aad)
	if err != nil {
		return nil, err
	}
	return &Sealed{
		V:   1,
		Kid: kid,
		Enc: base64.RawURLEncoding.EncodeToString(enc),
		Ct:  base64.RawURLEncoding.EncodeToString(ct),
	}, nil
}

// Gateway is a named HPKE private key that can open sealed tokens.
type Gateway struct {
	kid string
	sk  kem.PrivateKey
}

// NewGateway returns a gateway key with the given id and private key.
func NewGateway(kid string, sk kem.PrivateKey) (*Gateway, error) {
	if kid == "" {
		return nil, fmt.Errorf("hpke: empty kid")
	}
	if sk == nil {
		return nil, fmt.Errorf("hpke: nil private key")
	}
	return &Gateway{kid: kid, sk: sk}, nil
}

// Kid returns the gateway key id.
func (g *Gateway) Kid() string { return g.kid }

// PublicKey returns the raw 32-byte X25519 public key, for GET /v1/keys.
func (g *Gateway) PublicKey() ([]byte, error) {
	type pub interface{ Public() kem.PublicKey }
	p, ok := g.sk.(pub)
	if !ok {
		return nil, fmt.Errorf("hpke: key %q: private key does not expose public key", g.kid)
	}
	return RawPublic(p.Public())
}

// Open recovers the plaintext of a token sealed for this gateway.
func (g *Gateway) Open(s *Sealed) ([]byte, error) {
	if s.V != 1 {
		return nil, fmt.Errorf("hpke: unknown sealed version %d", s.V)
	}
	if s.Kid != g.kid {
		return nil, fmt.Errorf("hpke: kid %q does not match gateway key %q", s.Kid, g.kid)
	}
	enc, err := s.EncBytes()
	if err != nil {
		return nil, err
	}
	ct, err := s.CtBytes()
	if err != nil {
		return nil, err
	}
	return open(g.sk, enc, ct, []byte(Info), AAD(g.kid))
}

// OpenContext opens enc/ct with caller-supplied info and aad. Production Open
// always uses Info and AAD(kid). Tests use this to prove that a content-prefix
// aad, a mutated aad, or the wrong info fails.
func OpenContext(sk kem.PrivateKey, enc, ct, info, aad []byte) ([]byte, error) {
	if sk == nil {
		return nil, fmt.Errorf("hpke: nil private key")
	}
	return open(sk, enc, ct, info, aad)
}

func open(sk kem.PrivateKey, enc, ct, info, aad []byte) ([]byte, error) {
	rcv, err := Suite.NewReceiver(sk, info)
	if err != nil {
		return nil, fmt.Errorf("hpke: receiver: %w", err)
	}
	np, err := rcv.Setup(enc)
	if err != nil {
		return nil, fmt.Errorf("hpke: setup: %w", err)
	}
	pt, err := np.Open(ct, aad)
	if err != nil {
		return nil, fmt.Errorf("hpke: open: %w", err)
	}
	return pt, nil
}
