// Package content implements the DLPUSH/1 end-to-end notification content
// encryption (RFC 0002 §5.4).
//
// AES-256-GCM with a random 12-byte nonce and associated data
// "dlpush/1 content|" + deviceId. The AAD prefix is a separate constant from
// the token-seal prefix in internal/hpke; the two must never be shared, and
// negative tests assert that the wrong prefix fails decryption.
package content

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"fmt"
)

// AADPrefix is the domain separator for content encryption: UTF-8
// "dlpush/1 content|". It must not be shared with the token-seal prefix
// "dlpush/1 token|" (hpke.AADPrefix).
const AADPrefix = "dlpush/1 content|"

// AAD builds the content-encryption associated data for a device id.
func AAD(deviceID string) []byte { return []byte(AADPrefix + deviceID) }

// Seal encrypts plaintext under key K with a random 12-byte nonce and the
// given AAD. The returned byte slice is nonce || ciphertext || tag.
func Seal(key, plaintext, aad []byte) ([]byte, error) {
	if len(key) != 32 {
		return nil, fmt.Errorf("content: key length %d, want 32", len(key))
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("content: aes: %w", err)
	}
	g, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("content: gcm: %w", err)
	}
	nonce := make([]byte, g.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return nil, fmt.Errorf("content: nonce: %w", err)
	}
	return sealWithNonce(g, nonce, plaintext, aad), nil
}

// SealWithNonce encrypts with a caller-supplied 12-byte nonce. Shared test
// vectors use it so iOS and the plugin can Open a fixed ciphertext. Production
// callers must use Seal, which draws a fresh nonce.
func SealWithNonce(key, nonce, plaintext, aad []byte) ([]byte, error) {
	if len(key) != 32 {
		return nil, fmt.Errorf("content: key length %d, want 32", len(key))
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("content: aes: %w", err)
	}
	g, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("content: gcm: %w", err)
	}
	if len(nonce) != g.NonceSize() {
		return nil, fmt.Errorf("content: nonce length %d, want %d", len(nonce), g.NonceSize())
	}
	return sealWithNonce(g, nonce, plaintext, aad), nil
}

func sealWithNonce(g cipher.AEAD, nonce, plaintext, aad []byte) []byte {
	// g.Seal returns ciphertext||tag. The wire layout is nonce || ciphertext || tag.
	out := make([]byte, 0, len(nonce)+len(plaintext)+g.Overhead())
	out = append(out, nonce...)
	return g.Seal(out, nonce, plaintext, aad)
}

// Open decrypts nonce||ciphertext||tag under key K with AAD.
func Open(key, data, aad []byte) ([]byte, error) {
	if len(key) != 32 {
		return nil, fmt.Errorf("content: key length %d, want 32", len(key))
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("content: aes: %w", err)
	}
	g, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("content: gcm: %w", err)
	}
	ns := g.NonceSize()
	if len(data) < ns {
		return nil, fmt.Errorf("content: data too short")
	}
	return g.Open(nil, data[:ns], data[ns:], aad)
}

// SealStd is Seal with standard base64 of the result (no newlines).
func SealStd(key, plaintext, aad []byte) (string, error) {
	ct, err := Seal(key, plaintext, aad)
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(ct), nil
}

// OpenStd inverts SealStd.
func OpenStd(key, b64, aad []byte) ([]byte, error) {
	raw, err := base64.StdEncoding.DecodeString(string(b64))
	if err != nil {
		return nil, fmt.Errorf("content: decode: %w", err)
	}
	return Open(key, raw, aad)
}
