# syntax=docker/dockerfile:1
FROM golang:1.24-alpine AS build
RUN apk add --no-cache protobuf-dev make git
WORKDIR /build

COPY services/patient-gateway-go/go.mod ./
COPY proto ../../proto
COPY services/patient-gateway-go/ ./

RUN go install google.golang.org/protobuf/cmd/protoc-gen-go@v1.36.5 \
    && go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.5.1 \
    && make proto \
    && go mod tidy
# Static binary with no debug symbols: nothing in the runtime image to link against, and a
# smaller attack surface than shipping a toolchain-linked binary.
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/patient-gateway .

FROM gcr.io/distroless/static-debian12:nonroot
# Distroless has no shell and no package manager, so a process that achieves execution has
# nothing to pivot with. `nonroot` runs as uid 65532.
COPY --from=build /out/patient-gateway /patient-gateway
USER 65532:65532
EXPOSE 9090 8080
# distroless has no wget or curl; the binary answers its own health check.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD ["/patient-gateway", "-healthcheck"]
ENTRYPOINT ["/patient-gateway"]
