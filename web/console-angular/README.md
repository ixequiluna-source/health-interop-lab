# Interop console

An Angular interface for exploring patient search, patient details and pipeline state.

[Project overview](../../README.md) · [Architecture boundaries](../../docs/ARCHITECTURE.md)

## Local synthetic demo

Requires Node.js 22 and npm. From this directory:

```sh
npm ci
npm start
```

Open http://localhost:4200. Development uses the in-memory gateway and synthetic data. Search for a patient, open a detail view and inspect the pipeline page. Pipeline values in this mode are fixtures.

## Checks

```sh
npm test -- --watch=false
npm run build
```

The production build switches to HttpPatientGateway. It requires an HTTP/Connect-compatible adapter with the expected query and operations endpoints; the Go gRPC listener alone does not satisfy this contract. The repository includes this JSON subset in `services/patient-gateway-go/cmd/console-edge`. Use `npm run start:connected` with the gateway and edge running; see [connected demo instructions](../../docs/CONNECTED-DEMO.md). The default development demo remains in-memory.

Tests live beside their components, stores and adapters. The test gateway can control responses to exercise stale-request handling and pagination.
