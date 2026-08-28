# Production image: a multi-stage build produces a minified static `dist/`, which nginx then
# serves on port 80. Vite's VITE_* env vars are compiled INTO the JS bundle at build time (not
# read at container startup), so the backend URL must be supplied as a build ARG -- a runtime
# `environment:` entry on the final container would silently have no effect.
FROM node:20-alpine AS build

WORKDIR /app

# Install dependencies first so this layer is cached unless package*.json change.
COPY package.json package-lock.json* ./
RUN npm ci

COPY . .

ARG VITE_API_BASE_URL=http://localhost:8080/api
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

FROM nginx:alpine

COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

HEALTHCHECK --interval=10s --timeout=5s --start-period=10s --retries=6 \
    CMD wget -q --spider http://localhost/ || exit 1
