FROM node:22-alpine AS builder
WORKDIR /web
RUN npm install -g pnpm@11.19.0
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

FROM nginx:stable-alpine
ENV JAVA_API_TARGET=http://host.docker.internal:8080
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=builder /web/dist /usr/share/nginx/html
EXPOSE 80
