# syntax=docker/dockerfile:1
FROM node:22-alpine AS build
WORKDIR /build
# Lockfile first so the dependency layer survives source-only changes.
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
# SOC 2 CC6.1: the server runs unprivileged end to end. nginx.conf relocates the pid file
# and every temp path under /tmp and logs to stdout/stderr, so no root-owned directory has
# to be chowned open just to let the worker start.
RUN addgroup -S web && adduser -S -G web -H -s /sbin/nologin web \
    && rm -rf /usr/share/nginx/html/*

COPY nginx.conf /etc/nginx/nginx.conf
COPY --from=build /build/dist/console-angular/browser /usr/share/nginx/html

USER web
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
CMD ["nginx", "-g", "daemon off;"]
