# ADR-097: The Cloudflare tunnel runs over HTTP/2, not QUIC

## Status: Accepted

## Context
On 27 Oct 2026 the in-app feedback watcher logged four timeouts in two hours. Staging then answered `/version` in 0.6 to 12 seconds through the tunnel, while the app answered the same request on the Pi itself in about 0.17 seconds. At the same time the cloudflared connector logged "failed to accept QUIC stream: timeout: no recent network activity". The Pi's internet link looked healthy (0% packet loss, about 20 ms to 1.1.1.1). cloudflared uses QUIC over UDP by default, and UDP is often throttled or dropped on mobile and home uplinks like the staging Pi's.

## Decision
The cloudflared service in `deploy/compose.pi-staging.yml` (the only compose file that runs a tunnel) sets `TUNNEL_TRANSPORT_PROTOCOL` to `http2`, so the tunnel runs over TCP. Setting `CLOUDFLARE_TUNNEL_PROTOCOL` in the host's `.env` overrides it: `quic` goes back to the old behaviour, `auto` lets cloudflared choose.

## Consequences
- Better: a steady connection on uplinks that handle UDP badly. A shop owner on staging no longer waits seconds for each screen.
- Worse: HTTP/2 over TCP suffers head-of-line blocking on a lossy link, which QUIC avoids. On a clean link the difference is not noticeable for this app.
- Watch: time `/version` through the tunnel after the deploy. If it is still slow, the uplink itself is the cause, not the protocol.
