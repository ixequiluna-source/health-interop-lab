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

The production build switches to HttpPatientGateway. It requires an HTTP/Connect-compatible adapter with the expected query and operations endpoints; the Go gRPC listener alone does not satisfy this contract. Do not serve the production bundle as if it were the standalone synthetic demo.

Tests live beside their components, stores and adapters. The test gateway can control responses to exercise stale-request handling and pagination.
