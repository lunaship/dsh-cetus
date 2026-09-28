#!/usr/bin/env python3
"""Run with: python3 testdata/dlp1/gen_vectors.py > testdata/dlp1/vectors.json"""

import base64
import hashlib
import hmac
import json

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


def b64u(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def hkdf(ikm, salt, info, length):
    # RFC 5869, SHA-256.
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    out = b""
    previous = b""
    counter = 1
    while len(out) < length:
        previous = hmac.new(prk, previous + info + bytes([counter]), hashlib.sha256).digest()
        out += previous
        counter += 1
    return out[:length]


def main():
    host_seed = bytes(range(32))
    challenge = bytes([0xA5] * 32)
    sid = bytes(range(16, 32))
    key_seed = bytes([0x5A] * 32)
    relay_handle = bytes(range(32, 48))
    bootstrap_seed = bytes(range(48, 64))
    timestamp = 1790000000
    nonce = bytes([0x3C] * 16)

    private_key = Ed25519PrivateKey.from_private_bytes(host_seed)
    host_pub = private_key.public_key().public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw,
    )
    route_id = hashlib.sha256(b"DLP1 route\0" + host_pub).digest()[:16]
    t_register = b"DLP1 host_register\0" + b"\x01" + challenge + host_pub
    t_accept = b"DLP1 host_accept\0" + b"\x01" + challenge + host_pub + sid
    t_device = b"DLP1 client_open\0" + b"\x01" + route_id + b"\x01" + relay_handle + timestamp.to_bytes(8, "big") + nonce
    t_bootstrap = b"DLP1 client_open\0" + b"\x01" + route_id + b"\x02" + hkdf(bootstrap_seed, route_id, b"DLP1 bootstrap id", 16) + timestamp.to_bytes(8, "big") + nonce
    t_devkey = b"DLP1 device key\0" + relay_handle
    device_key = hmac.new(key_seed, t_devkey, hashlib.sha256).digest()
    bootstrap_id = hkdf(bootstrap_seed, route_id, b"DLP1 bootstrap id", 16)
    bootstrap_key = hkdf(bootstrap_seed, route_id, b"DLP1 bootstrap key", 32)

    outputs = {
        "hostPub": host_pub.hex(),
        "routeId": route_id.hex(),
        "T_register": t_register.hex(),
        "sig_register": private_key.sign(t_register).hex(),
        "T_accept": t_accept.hex(),
        "sig_accept": private_key.sign(t_accept).hex(),
        "deviceRelayKey": device_key.hex(),
        "T_client_device": t_device.hex(),
        "mac_device": hmac.new(device_key, t_device, hashlib.sha256).hexdigest(),
        "bootstrapId": bootstrap_id.hex(),
        "bootstrapKey": bootstrap_key.hex(),
        "T_client_bootstrap": t_bootstrap.hex(),
        "mac_bootstrap": hmac.new(bootstrap_key, t_bootstrap, hashlib.sha256).hexdigest(),
        "b64u": {"routeId": b64u(route_id), "relayHandle": b64u(relay_handle)},
    }
    print(json.dumps({
        "version": 1,
        "inputs": {
            "hostKeySeed": host_seed.hex(),
            "ch": challenge.hex(),
            "sid": sid.hex(),
            "keySeed": key_seed.hex(),
            "relayHandle": relay_handle.hex(),
            "bootstrapSeed": bootstrap_seed.hex(),
            "ts": timestamp,
            "nonce": nonce.hex(),
        },
        "outputs": outputs,
    }, indent=2))


if __name__ == "__main__":
    main()
