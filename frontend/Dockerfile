FROM node:20-bookworm-slim AS build

WORKDIR /workspace

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

ARG PUBLIC_URL=/spot/zooma/
ARG REACT_APP_APIURL=https://wwwdev.ebi.ac.uk/spot/zooma/
ARG ZOOMA_MINIFY=true

ENV PUBLIC_URL=${PUBLIC_URL}
ENV REACT_APP_APIURL=${REACT_APP_APIURL}
ENV ZOOMA_MINIFY=${ZOOMA_MINIFY}

RUN node build.mjs

FROM caddy:2-alpine

ARG PUBLIC_URL=/spot/zooma/
ENV PUBLIC_URL=${PUBLIC_URL}
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
