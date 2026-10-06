package hpke

import (
	"errors"

	"github.com/cloudflare/circl/hpke"
	"github.com/cloudflare/circl/kem"
)

// errUnsupportedKey is returned when a key type does not expose raw bytes.
var errUnsupportedKey = errors.New("hpke: key does not expose raw bytes")

// Scheme returns the underlying KEM scheme (for keypair generation in tests).
func Scheme() kem.Scheme { return X25519Scheme() }

// X25519Scheme is the DHKEM(X25519, HKDF-SHA256) KEM scheme.
func X25519Scheme() kem.Scheme { return hpke.KEM_X25519_HKDF_SHA256.Scheme() }

// WrapX25519 wraps a raw 32-byte X25519 private key into a circl
// kem.PrivateKey suitable for the HPKE suite. Used by cmd/dlpush to load
// key files from disk.
func WrapX25519(raw []byte) (kem.PrivateKey, error) {
	return X25519Scheme().UnmarshalBinaryPrivateKey(raw)
}

// PublicFromRaw wraps a raw 32-byte X25519 public key into a kem.PublicKey
// (for generating shared test vectors with a fixed gateway key).
func PublicFromRaw(raw []byte) (kem.PublicKey, error) {
	return X25519Scheme().UnmarshalBinaryPublicKey(raw)
}

// RawPublic extracts the 32-byte raw form of a kem.PublicKey (xKEMPubKey).
func RawPublic(pk kem.PublicKey) ([]byte, error) {
	type mb interface{ MarshalBinary() ([]byte, error) }
	if m, ok := pk.(mb); ok {
		return m.MarshalBinary()
	}
	return nil, errUnsupportedKey
}

// RawPrivate extracts the 32-byte raw form of a kem.PrivateKey (xKEMPrivKey).
func RawPrivate(sk kem.PrivateKey) ([]byte, error) {
	type mb interface{ MarshalBinary() ([]byte, error) }
	if m, ok := sk.(mb); ok {
		return m.MarshalBinary()
	}
	return nil, errUnsupportedKey
}
