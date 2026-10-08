# Third-party notices

Cetus plugin (`dsh-cetus`) is an independent unofficial community project. It is not affiliated with, authorized by, or endorsed by DeepSeek. DeepSeek Harness names and related marks belong to their respective owners.

This repository is MIT. The Android client lives in `apps/android/`; its third-party notices are in [`apps/android/THIRD_PARTY_NOTICES.md`](apps/android/THIRD_PARTY_NOTICES.md).

## DeepSeek Harness

- License: MIT
- Copyright: Copyright (c) 2026 DeepSeek
- Upstream: https://github.com/deepseek-ai

## Plugin npm dependencies

| Package | Version | License | Upstream |
|---|---|---|---|
| @deepseek-ai/schemastery | 3.18.1 | MIT | https://www.npmjs.com/package/@deepseek-ai/schemastery |
| qrcode | 1.5.4 | MIT | https://github.com/soldair/node-qrcode |
| selfsigned | 5.5.0 | MIT | https://github.com/jfromaniello/selfsigned |
| ws | 8.22.0 (^8) | MIT | https://github.com/websockets/ws |
| @deepseek-ai/cordis (peer) | ^4.0.1 | MIT | https://www.npmjs.com/package/@deepseek-ai/cordis |

## dsh-plugin-mobile-gateway notice

The plugin's session-control routes (`src/mobile-session-control.js`: queued
prompts, goal edit / pause / resume / clear, scheduled tasks) and the file
SHA-256 response header follow the Harness RPC payload shapes used by
dsh-plugin-mobile-gateway (https://github.com/Clarklevis1995/dsh-plugin-mobile-gateway).
Licensed under the MIT License:

```
MIT License

Copyright (c) 2026 Clarklevis1995

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
