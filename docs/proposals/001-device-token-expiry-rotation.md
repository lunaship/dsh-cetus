# Proposal: Device token expiry and rotation

## Problem

Paired devices receive a long-lived `x-dsh-link-token` (192-bit random number). Currently:

- Tokens never expire.
- The only way to invalidate a token is manual revocation from the host panel.
- A lost or stolen device can keep accessing the host indefinitely until manually revoked.

## Proposed solution

### Step 1: Idle expiry (plugin-only, backwards-compatible)

- Add a new plugin config: `deviceIdleExpireDays` (default `90`, `0` = never expire).
- During auth, if `now - lastSeenAt > idleExpire`, treat the token as revoked and return `401`.
- Log: `dsh-cetus: device expired device=<8>`.
- Host panel shows each device's "last seen" time and "will expire in X days".
- Tests: fake clock covers "just not expired", "just expired", and "configured as 0".

### Step 2: Token rotation (plugin + app, requires version negotiation)

- New endpoint: `POST /mobile/token/rotate` — accepts old token, returns new token.
- Old token retains a short grace period (e.g. 5 minutes) to handle lost responses, then invalidates.
- App rotates token on cold start (or every 7 days). New token is encrypted with `TokenCrypto` and written to `HostStore` **only after** successful write confirmation.
- Optionally add `POST /mobile/token/confirm` or use "first use of new token" as confirmation.
- Older apps that don't call the rotate endpoint continue to work, subject only to Step 1 idle expiry.
- Update `docs/COMPATIBILITY.md` version matrix.

## Open questions

1. Should idle expiry be enforced server-side, or is client-side `lastSeenAt` sufficient?
2. What is the right default for `deviceIdleExpireDays`? 90 days? 30 days?
3. Should the app proactively warn the user before token expiry, or only fail at auth time?
4. For Step 2, should the rotation be transparent to the user, or require explicit confirmation?
5. How does this interact with the DLP/1 remote relay path? Does the relay need changes?

## Migration path

- Step 1 is fully backwards-compatible: old clients still work, new clients get idle expiry.
- Step 2 requires both plugin and app updates. During the transition period, old clients fall back to Step 1 constraints.

## Related

- S6 in `dsh-links-安全整改方案.md`
