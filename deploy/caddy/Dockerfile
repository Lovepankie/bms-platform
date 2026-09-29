# syntax=docker/dockerfile:1.7
# Edge proxy image: Caddy plus the Cloudflare DNS module. A wildcard certificate for
# *.<zone> can only be issued through the ACME DNS-01 challenge, and the stock Caddy image
# has no DNS provider modules, so this image adds exactly one (docs/sdd/09-infrastructure-design.md
# section 9.5). Built and tagged with the api and web images, so a release pins all three.

#
# Multi-arch (ADR-018): Go cross-compiles, so xcaddy runs on the build machine's own platform and
# targets TARGETOS/TARGETARCH; the runtime stage only copies files. Two configurations ship in the
# image: Caddyfile (the production VM edge, public TLS) and Caddyfile.tunnel (the internal origin
# behind cloudflared on the ARM64 staging host, plain HTTP), chosen by the compose file's command.

FROM --platform=$BUILDPLATFORM caddy:2.11.4-builder-alpine AS build
ARG TARGETOS
ARG TARGETARCH
RUN GOOS=$TARGETOS GOARCH=$TARGETARCH xcaddy build --output /out/caddy \
        --with github.com/caddy-dns/cloudflare@v0.2.4

FROM caddy:2.11.4-alpine
COPY --from=build /out/caddy /usr/bin/caddy
COPY Caddyfile Caddyfile.tunnel /etc/caddy/
