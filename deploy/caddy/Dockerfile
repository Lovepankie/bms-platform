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
#
# The build context is deploy/ (docker build -f deploy/caddy/Dockerfile deploy). The image also
# carries that release's host files under /usr/share/bms-deploy, from which the staging puller
# installs them (ADR-018): the repository is private, and the host holds no GitHub credential.

FROM --platform=$BUILDPLATFORM caddy:2.11.4-builder-alpine AS build
ARG TARGETOS
ARG TARGETARCH
RUN GOOS=$TARGETOS GOARCH=$TARGETARCH xcaddy build --output /out/caddy \
        --with github.com/caddy-dns/cloudflare@v0.2.4

FROM caddy:2.11.4-alpine
COPY --from=build /out/caddy /usr/bin/caddy
COPY caddy/Caddyfile caddy/Caddyfile.tunnel /etc/caddy/
COPY compose.pi-staging.yml deploy.sh backup.sh pull-staging.sh /usr/share/bms-deploy/
COPY postgres /usr/share/bms-deploy/postgres
COPY sql /usr/share/bms-deploy/sql
COPY systemd /usr/share/bms-deploy/systemd
