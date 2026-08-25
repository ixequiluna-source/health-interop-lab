MODULE := github.com/ixequiluna-source/health-interop-lab/services/patient-gateway-go
PROTO_DIR := ../../proto

.PHONY: proto
proto:
	# --module strips the module prefix from the go_package option, so the generated files
	# land at gen/interop/v1/ inside this module rather than under a mirrored directory
	# tree named after the full import path.
	protoc \
		-I $(PROTO_DIR) \
		--go_out=. --go_opt=module=$(MODULE) \
		--go-grpc_out=. --go-grpc_opt=module=$(MODULE) \
		$(PROTO_DIR)/interop/v1/patient.proto

.PHONY: tidy
tidy:
	go mod tidy

.PHONY: build
build: proto tidy
	go build ./...

.PHONY: test
test:
	go test -race ./...

.PHONY: vet
vet:
	go vet ./...

.PHONY: clean
clean:
	rm -rf gen
