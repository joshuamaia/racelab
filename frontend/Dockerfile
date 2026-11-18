# syntax=docker/dockerfile:1
# RaceLab frontend — build context: ./frontend (+ named context "certs" = ./infrastructure/certs)

# ---- build ----------------------------------------------------------------
FROM node:24-alpine AS build
COPY --from=certs . /tmp/certs/
RUN cat /tmp/certs/*.crt > /tmp/extra-ca.pem 2>/dev/null || true
ENV NODE_EXTRA_CA_CERTS=/tmp/extra-ca.pem
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build -- --configuration production

# ---- runtime ----------------------------------------------------------------
FROM nginx:1.27-alpine
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1/index.html || exit 1
CMD ["nginx", "-g", "daemon off;"]
