# syntax=docker/dockerfile:1.7
# BMS Platform web image (ADR-009): the static PWA bundle served by Caddy on :8080. TLS, tenant
# host routing and the /api proxy are the edge proxy's job (deploy/caddy), not this container's.

FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM caddy:2.11-alpine AS runtime
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /src/dist /srv
# Caddy's official image runs as root by default; this one does not need to.
RUN chown -R 10001:10001 /srv /config /data
USER 10001:10001
EXPOSE 8080
