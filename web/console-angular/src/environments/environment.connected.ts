import { HttpPatientGateway } from '../app/data/http-patient-gateway';
import type { AppEnvironment } from '../app/core/environment';
export const environment: AppEnvironment = {
  label: 'local · HTTP → gRPC · synthetic data',
  gateway: 'http', gatewayBaseUrl: '', pipelinePollMs: 30_000,
};
export const gatewayImplementation = HttpPatientGateway;
