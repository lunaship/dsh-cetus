FROM golang:1.25-bookworm AS build

WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/dlp-relay ./cmd/dlp-relay

# 运行镜像不带 shell、包管理器与任何工具；Relay 无状态，不需要数据卷
FROM scratch
COPY --from=build /out/dlp-relay /usr/local/bin/dlp-relay
USER 10001:10001
ENV DLP_LISTEN=0.0.0.0:8411
EXPOSE 8411
ENTRYPOINT ["/usr/local/bin/dlp-relay"]
