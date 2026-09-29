# syntax=docker/dockerfile:1.7
# BMS Platform web image (ADR-009): the static PWA bundle served by Caddy on :8080. TLS, tenant
# host routing and the /api proxy are the edge proxy's job (deploy/caddy), not this container's.

#
# Multi-arch (ADR-018): npm and Vite run on the build machine's own platform ($BUILDPLATFORM), since
# the bundle is the same for every architecture. The runtime stage has no RUN step: ownership is
# set while copying, so nothing executes under emulation.

FROM --platform=$BUILDPLATFORM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build && mkdir -p /empty

FROM caddy:2.11-alpine AS runtime
COPY Caddyfile /etc/caddy/Caddyfile
# Caddy's official image runs as root by default; this one does not need to.
COPY --chown=10001:10001 --from=build /src/dist /srv
COPY --chown=10001:10001 --from=build /empty /config
COPY --chown=10001:10001 --from=build /empty /data
USER 10001:10001
EXPOSE 8080
