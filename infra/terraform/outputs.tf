output "cluster_name" {
  description = "GKE cluster name."
  value       = google_container_cluster.main.name
}

output "cluster_endpoint" {
  description = "GKE control plane endpoint."
  value       = google_container_cluster.main.endpoint
  # Marked sensitive so it does not land in CI logs, which are usually retained longer and
  # read more widely than the infrastructure they describe.
  sensitive = true
}

output "artifact_registry" {
  description = "Docker repository that holds the service images."
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.images.repository_id}"
}

output "workload_service_account" {
  description = "Google service account bound to Kubernetes workloads via Workload Identity."
  value       = google_service_account.workload.email
}

output "claims_queue_url" {
  description = "FIFO queue the claims service publishes to."
  value       = aws_sqs_queue.claims.url
}

output "claims_dlq_url" {
  description = "Dead letter queue for claims that failed three delivery attempts."
  value       = aws_sqs_queue.claims_dlq.url
}

output "admissions_topic" {
  description = "Pub/Sub topic carrying canonical admission events."
  value       = google_pubsub_topic.admissions.id
}

output "audit_bucket" {
  description = "Bucket holding retained audit logs."
  value       = google_storage_bucket.audit_logs.name
}
