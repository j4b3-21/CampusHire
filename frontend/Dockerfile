# CampusHire web image (Next.js standalone output; requires `output: "standalone"` in next.config.ts).
#   docker build -t campushire-frontend:local frontend
FROM node:22-alpine AS deps

WORKDIR /app

COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund


FROM node:22-alpine AS build

WORKDIR /app

# NEXT_PUBLIC_* are inlined into the browser bundle and rewrites to API_INTERNAL_URL are resolved by
# `next build`, so they are build arguments; change them and rebuild.
ARG API_INTERNAL_URL=http://backend:8080
ARG NEXT_PUBLIC_KEYCLOAK_URL=http://localhost:8180
ARG NEXT_PUBLIC_KEYCLOAK_REALM=campushire
ARG NEXT_PUBLIC_KEYCLOAK_CLIENT_ID=campushire-web
ENV API_INTERNAL_URL=$API_INTERNAL_URL \
    NEXT_PUBLIC_KEYCLOAK_URL=$NEXT_PUBLIC_KEYCLOAK_URL \
    NEXT_PUBLIC_KEYCLOAK_REALM=$NEXT_PUBLIC_KEYCLOAK_REALM \
    NEXT_PUBLIC_KEYCLOAK_CLIENT_ID=$NEXT_PUBLIC_KEYCLOAK_CLIENT_ID \
    NEXT_TELEMETRY_DISABLED=1

COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build


FROM node:22-alpine AS runtime

RUN addgroup -S -g 10001 campushire \
    && adduser -S -D -H -u 10001 -G campushire -s /sbin/nologin campushire

WORKDIR /app

ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    HOSTNAME=0.0.0.0 \
    PORT=3000

COPY --from=build --chown=10001:10001 /app/public ./public
COPY --from=build --chown=10001:10001 /app/.next/standalone ./
COPY --from=build --chown=10001:10001 /app/.next/static ./.next/static

USER 10001:10001

EXPOSE 3000

CMD ["node", "server.js"]
