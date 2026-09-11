FROM node:20-bookworm-slim AS build

WORKDIR /workspace

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

ARG PUBLIC_URL=/spot/zooma/
# The API is served under the same path prefix on every host (www, wwwdev),
# so a relative URL lets one image serve both; the old absolute default made
# a prod deployment of this image call the dev API. Only the fetch prefix
# uses it. Override at build time for a non-standard layout.
ARG REACT_APP_APIURL=/spot/zooma/
ARG ZOOMA_MINIFY=true

ENV PUBLIC_URL=${PUBLIC_URL}
ENV REACT_APP_APIURL=${REACT_APP_APIURL}
ENV ZOOMA_MINIFY=${ZOOMA_MINIFY}

RUN node build.mjs

FROM caddy:2-alpine

ARG PUBLIC_URL=/spot/zooma/
ENV PUBLIC_URL=${PUBLIC_URL}
# PUBLIC_URL without its trailing slash, for the redirect in the Caddyfile.
# Kept in step with PUBLIC_URL by hand; Caddyfile placeholders can't strip it.
ARG PUBLIC_PATH=/spot/zooma
ENV PUBLIC_PATH=${PUBLIC_PATH}
# The upstream image gives the caddy binary cap_net_bind_service so it can
# bind :80/:443. We listen on 8080 and run with allowPrivilegeEscalation
# disabled, under which the kernel refuses to exec a binary that carries
# file capabilities ("operation not permitted"). Strip it.
RUN apk add --no-cache libcap && setcap -r /usr/bin/caddy && apk del libcap

RUN addgroup -S -g 1279 zooma \
    && adduser -S -D -H -u 5534 -G zooma zooma \
    && chown -R 5534:1279 /data /config

COPY --chown=5534:1279 Caddyfile /etc/caddy/Caddyfile
COPY --chown=5534:1279 --from=build /workspace/dist /srv

USER 5534:1279

EXPOSE 8080
