package dlp

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"strings"
	"unicode/utf8"
)

const MaxControlBytes = 4 * 1024

var errDuplicateKey = errors.New("duplicate JSON key")

func ParseControl(raw []byte) (map[string]json.RawMessage, error) {
	if len(raw) == 0 || len(raw) > MaxControlBytes || !utf8.Valid(raw) {
		return nil, errors.New("invalid control frame length or UTF-8")
	}
	if err := rejectDuplicateKeys(raw); err != nil {
		return nil, err
	}
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	var value map[string]json.RawMessage
	if err := dec.Decode(&value); err != nil || value == nil {
		return nil, errors.New("control frame must be a JSON object")
	}
	if dec.Decode(new(any)) != io.EOF {
		return nil, errors.New("trailing control data")
	}
	return value, nil
}

func rejectDuplicateKeys(raw []byte) error {
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	if err := walkJSONValue(dec); err != nil {
		return err
	}
	if _, err := dec.Token(); err != io.EOF {
		return errors.New("trailing JSON value")
	}
	return nil
}

func walkJSONValue(dec *json.Decoder) error {
	token, err := dec.Token()
	if err != nil {
		return err
	}
	delimiter, ok := token.(json.Delim)
	if !ok {
		return nil
	}
	switch delimiter {
	case '{':
		seen := map[string]struct{}{}
		for dec.More() {
			keyToken, err := dec.Token()
			if err != nil {
				return err
			}
			key, ok := keyToken.(string)
			if !ok {
				return errors.New("invalid JSON object key")
			}
			if _, exists := seen[key]; exists {
				return errDuplicateKey
			}
			seen[key] = struct{}{}
			if err := walkJSONValue(dec); err != nil {
				return err
			}
		}
		_, err = dec.Token()
		return err
	case '[':
		for dec.More() {
			if err := walkJSONValue(dec); err != nil {
				return err
			}
		}
		_, err = dec.Token()
		return err
	default:
		return errors.New("unexpected JSON delimiter")
	}
}

func stringField(raw map[string]json.RawMessage, name string) (string, error) {
	value, ok := raw[name]
	if !ok {
		return "", errors.New("missing field")
	}
	var result string
	if err := json.Unmarshal(value, &result); err != nil {
		return "", errors.New("invalid string field")
	}
	return result, nil
}

func intField(raw map[string]json.RawMessage, name string) (int64, error) {
	value, ok := raw[name]
	if !ok {
		return 0, errors.New("missing field")
	}
	var result json.Number
	if err := json.Unmarshal(value, &result); err != nil {
		return 0, errors.New("invalid integer field")
	}
	n, err := result.Int64()
	if err != nil || n > (1<<53)-1 || n < -((1<<53)-1) || json.Number(result.String()) != result {
		return 0, errors.New("invalid integer field")
	}
	return n, nil
}

func decodeB64(value string, size int) ([]byte, error) {
	if value == "" || strings.ContainsAny(value, "=+/") {
		return nil, errors.New("invalid base64url")
	}
	b, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil || len(b) != size || base64.RawURLEncoding.EncodeToString(b) != value {
		return nil, errors.New("invalid base64url length")
	}
	return b, nil
}

func encodeB64(value []byte) string { return base64.RawURLEncoding.EncodeToString(value) }

func message(kind string, fields map[string]any) []byte {
	value := make(map[string]any, len(fields)+1)
	value["t"] = kind
	for key, field := range fields {
		value[key] = field
	}
	raw, _ := json.Marshal(value)
	return raw
}
