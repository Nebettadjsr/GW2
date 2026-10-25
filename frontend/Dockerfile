# syntax=docker/dockerfile:1
# Static Vue frontend runtime image (STORY-INFRA-007, ADR-011). Build from the repository root or
# from frontend/: docker build -t gw2-frontend frontend
# Node only builds; the runtime stage is the maintained unprivileged Nginx image plus dist/ and one
# config template. Tags float with upstream patch releases; pass --build-arg to pin a digest.
ARG NODE_IMAGE=node:22-alpine
ARG NGINX_IMAGE=nginxinc/nginx-unprivileged:stable-alpine

FROM ${NODE_IMAGE} AS build
WORKDIR /src
# Dependencies first so a source-only change reuses the installed node_modules layer.
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html tsconfig.json vite.config.ts ./
COPY public/ public/
COPY src/ src/
# `npm run build` is the existing reproducible type check followed by `vite build`.
RUN npm run build

FROM ${NGINX_IMAGE}
# Rendered to /etc/nginx/conf.d/default.conf at start by the image's envsubst entrypoint, which
# substitutes only variables present in the environment (nginx's own $variables are untouched).
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist/ /usr/share/nginx/html/
# Internal backend that /api/ is forwarded to; override at run time (host name on the shared network).
ENV GW2_BACKEND_ORIGIN=http://backend:8080
# Unprivileged (uid 101) listener; the pid file and temp paths of this image are already writable.
EXPOSE 8080
