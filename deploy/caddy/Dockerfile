# syntax=docker/dockerfile:1.7
# Edge proxy image: Caddy plus the Cloudflare DNS module. A wildcard certificate for
# *.<base domain> can only be issued through the ACME DNS-01 challenge, and the stock Caddy image
# has no DNS provider modules, so this image adds exactly one (docs/sdd/09-infrastructure-design.md
# section 9.5). Built and tagged with the api and web images, so a release pins all three.

FROM caddy:2.11.4-builder-alpine AS build
RUN xcaddy build --with github.com/caddy-dns/cloudflare@v0.2.4

FROM caddy:2.11.4-alpine
COPY --from=build /usr/bin/caddy /usr/bin/caddy
COPY Caddyfile /etc/caddy/Caddyfile
