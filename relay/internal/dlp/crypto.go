package dlp

import (
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"errors"
)

const (
	Version          = byte(1)
	RoutePrefix      = "DLP1 route\x00"
	RegisterPrefix   = "DLP1 host_register\x00"
	AcceptPrefix     = "DLP1 host_accept\x00"
	ClientPrefix     = "DLP1 client_open\x00"
	DeviceKeyPrefix  = "DLP1 device key\x00"
	BootstrapIDInfo  = "DLP1 bootstrap id"
	BootstrapKeyInfo = "DLP1 bootstrap key"
)

func RouteID(hostPub []byte) ([16]byte, error) {
	var route [16]byte
	if len(hostPub) != ed25519.PublicKeySize {
		return route, errors.New("host public key must be 32 bytes")
	}
	h := sha256.New()
	h.Write([]byte(RoutePrefix))
	h.Write(hostPub)
	copy(route[:], h.Sum(nil)[:len(route)])
	return route, nil
}

func RegisterTranscript(challenge, hostPub []byte) ([]byte, error) {
	if len(challenge) != 32 || len(hostPub) != ed25519.PublicKeySize {
		return nil, errors.New("invalid register transcript field length")
	}
	return concat([]byte(RegisterPrefix), []byte{Version}, challenge, hostPub), nil
}

func AcceptTranscript(challenge, hostPub, sid []byte) ([]byte, error) {
	if len(challenge) != 32 || len(hostPub) != ed25519.PublicKeySize || len(sid) != 16 {
		return nil, errors.New("invalid accept transcript field length")
	}
	return concat([]byte(AcceptPrefix), []byte{Version}, challenge, hostPub, sid), nil
}

func ClientTranscript(route []byte, kind byte, key []byte, ts uint64, nonce []byte) ([]byte, error) {
	if len(route) != 16 || len(key) != 16 || len(nonce) != 16 || (kind != 1 && kind != 2) {
		return nil, errors.New("invalid client transcript field")
	}
	var stamp [8]byte
	binary.BigEndian.PutUint64(stamp[:], ts)
	return concat([]byte(ClientPrefix), []byte{Version}, route, []byte{kind}, key, stamp[:], nonce), nil
}

func DeviceRelayKey(keySeed, relayHandle []byte) ([]byte, error) {
	if len(keySeed) != 32 || len(relayHandle) != 16 {
		return nil, errors.New("invalid device key field length")
	}
	return hmacSHA256(keySeed, []byte(DeviceKeyPrefix), relayHandle), nil
}

func ClientMAC(key, transcript []byte) ([]byte, error) {
	if len(key) != 32 {
		return nil, errors.New("client key must be 32 bytes")
	}
	return hmacSHA256(key, transcript), nil
}

func HKDF(ikm, salt []byte, info string, length int) ([]byte, error) {
	if length < 1 || length > 255*sha256.Size {
		return nil, errors.New("invalid HKDF output length")
	}
	extract := hmac.New(sha256.New, salt)
	extract.Write(ikm)
	prk := extract.Sum(nil)
	result := make([]byte, 0, length)
	previous := []byte{}
	for counter := byte(1); len(result) < length; counter++ {
		expand := hmac.New(sha256.New, prk)
		expand.Write(previous)
		expand.Write([]byte(info))
		expand.Write([]byte{counter})
		previous = expand.Sum(nil)
		result = append(result, previous...)
	}
	return result[:length], nil
}

func VerifySignature(pub, transcript, signature []byte) bool {
	return len(pub) == ed25519.PublicKeySize && ed25519.Verify(ed25519.PublicKey(pub), transcript, signature)
}

func hmacSHA256(key []byte, parts ...[]byte) []byte {
	h := hmac.New(sha256.New, key)
	for _, part := range parts {
		h.Write(part)
	}
	return h.Sum(nil)
}

func concat(parts ...[]byte) []byte {
	length := 0
	for _, part := range parts {
		length += len(part)
	}
	out := make([]byte, 0, length)
	for _, part := range parts {
		out = append(out, part...)
	}
	return out
}
