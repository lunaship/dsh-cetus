package dlp

import (
	"crypto/ed25519"
	"encoding/hex"
	"encoding/json"
	"os"
	"testing"
)

type vectorFile struct {
	Inputs struct {
		HostKeySeed   string `json:"hostKeySeed"`
		Challenge     string `json:"ch"`
		SID           string `json:"sid"`
		KeySeed       string `json:"keySeed"`
		RelayHandle   string `json:"relayHandle"`
		BootstrapSeed string `json:"bootstrapSeed"`
		Timestamp     uint64 `json:"ts"`
		Nonce         string `json:"nonce"`
	} `json:"inputs"`
	Outputs map[string]json.RawMessage `json:"outputs"`
}

func vectorBytes(t *testing.T, value string) []byte {
	t.Helper()
	b, err := hex.DecodeString(value)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func vectorOutput(t *testing.T, outputs map[string]json.RawMessage, name string) string {
	t.Helper()
	var value string
	if err := json.Unmarshal(outputs[name], &value); err != nil {
		t.Fatalf("decode vector output %s: %v", name, err)
	}
	return value
}

func TestDLP1Vectors(t *testing.T) {
	raw, err := os.ReadFile("../../../testdata/dlp1/vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var vectors vectorFile
	if err := json.Unmarshal(raw, &vectors); err != nil {
		t.Fatal(err)
	}
	i, o := vectors.Inputs, vectors.Outputs
	seed := vectorBytes(t, i.HostKeySeed)
	private := ed25519.NewKeyFromSeed(seed)
	pub := private.Public().(ed25519.PublicKey)
	if got := hex.EncodeToString(pub); got != vectorOutput(t, o, "hostPub") {
		t.Fatalf("hostPub mismatch: %s", got)
	}
	route, err := RouteID(pub)
	if err != nil {
		t.Fatal(err)
	}
	if got := hex.EncodeToString(route[:]); got != vectorOutput(t, o, "routeId") {
		t.Fatalf("routeId mismatch: %s", got)
	}
	register, err := RegisterTranscript(vectorBytes(t, i.Challenge), pub)
	if err != nil {
		t.Fatal(err)
	}
	if got := hex.EncodeToString(register); got != vectorOutput(t, o, "T_register") {
		t.Fatalf("T_register mismatch: %s", got)
	}
	if got := hex.EncodeToString(ed25519.Sign(private, register)); got != vectorOutput(t, o, "sig_register") {
		t.Fatalf("sig_register mismatch: %s", got)
	}
	accept, err := AcceptTranscript(vectorBytes(t, i.Challenge), pub, vectorBytes(t, i.SID))
	if err != nil {
		t.Fatal(err)
	}
	if got := hex.EncodeToString(accept); got != vectorOutput(t, o, "T_accept") {
		t.Fatalf("T_accept mismatch: %s", got)
	}
	if got := hex.EncodeToString(ed25519.Sign(private, accept)); got != vectorOutput(t, o, "sig_accept") {
		t.Fatalf("sig_accept mismatch: %s", got)
	}

	relayHandle := vectorBytes(t, i.RelayHandle)
	deviceKey, err := DeviceRelayKey(vectorBytes(t, i.KeySeed), relayHandle)
	if err != nil {
		t.Fatal(err)
	}
	if got := hex.EncodeToString(deviceKey); got != vectorOutput(t, o, "deviceRelayKey") {
		t.Fatalf("deviceRelayKey mismatch: %s", got)
	}
	deviceTranscript, err := ClientTranscript(route[:], 1, relayHandle, i.Timestamp, vectorBytes(t, i.Nonce))
	if err != nil {
		t.Fatal(err)
	}
	if got := hex.EncodeToString(deviceTranscript); got != vectorOutput(t, o, "T_client_device") {
		t.Fatalf("T_client_device mismatch: %s", got)
	}
	deviceMAC, err := ClientMAC(deviceKey, deviceTranscript)
	if err != nil {
		t.Fatal(err)
	}
	if got := hex.EncodeToString(deviceMAC); got != vectorOutput(t, o, "mac_device") {
		t.Fatalf("mac_device mismatch: %s", got)
	}

	bootstrapID, err := HKDF(vectorBytes(t, i.BootstrapSeed), route[:], BootstrapIDInfo, 16)
	if err != nil {
		t.Fatal(err)
	}
	bootstrapKey, err := HKDF(vectorBytes(t, i.BootstrapSeed), route[:], BootstrapKeyInfo, 32)
	if err != nil {
		t.Fatal(err)
	}
	if got := hex.EncodeToString(bootstrapID); got != vectorOutput(t, o, "bootstrapId") {
		t.Fatalf("bootstrapId mismatch: %s", got)
	}
	if got := hex.EncodeToString(bootstrapKey); got != vectorOutput(t, o, "bootstrapKey") {
		t.Fatalf("bootstrapKey mismatch: %s", got)
	}
	bootstrapTranscript, err := ClientTranscript(route[:], 2, bootstrapID, i.Timestamp, vectorBytes(t, i.Nonce))
	if err != nil {
		t.Fatal(err)
	}
	if got := hex.EncodeToString(bootstrapTranscript); got != vectorOutput(t, o, "T_client_bootstrap") {
		t.Fatalf("T_client_bootstrap mismatch: %s", got)
	}
	bootstrapMAC, err := ClientMAC(bootstrapKey, bootstrapTranscript)
	if err != nil {
		t.Fatal(err)
	}
	if got := hex.EncodeToString(bootstrapMAC); got != vectorOutput(t, o, "mac_bootstrap") {
		t.Fatalf("mac_bootstrap mismatch: %s", got)
	}
}
