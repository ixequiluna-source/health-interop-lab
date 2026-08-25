# Audit logging and retention.
#
# HIPAA §164.312(b) requires mechanisms that record and examine activity in systems holding
# ePHI, and §164.316(b)(2)(i) requires the documentation be retained six years. SOC 2 CC7.2
# asks the same question a different way. Both are satisfied by configuration, not by intent,
# so the retention and immutability settings live here where they are reviewable.

resource "google_project_iam_audit_config" "all_services" {
  project = var.project_id
  service = "allServices"

  audit_log_config {
    log_type = "ADMIN_READ"
  }
  audit_log_config {
    log_type = "DATA_READ"
  }
  audit_log_config {
    log_type = "DATA_WRITE"
  }
}

resource "google_storage_bucket" "audit_logs" {
  name     = "${var.project_id}-${local.name_prefix}-audit"
  location = var.region

  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"

  # A bucket lock makes retained logs immutable for the retention period: not even the
  # project owner can delete them early. Audit logs an administrator can quietly remove are
  # not evidence, which is the whole point of retaining them.
  retention_policy {
    retention_period = var.phi_retention_days * 24 * 60 * 60
    is_locked        = var.environment == "prod"
  }

  versioning {
    enabled = true
  }

  encryption {
    default_kms_key_name = google_kms_crypto_key.audit.id
  }

  lifecycle_rule {
    condition {
      age = 90
    }
    action {
      type          = "SetStorageClass"
      storage_class = "NEARLINE"
    }
  }

  lifecycle_rule {
    condition {
      age = 365
    }
    action {
      type          = "SetStorageClass"
      storage_class = "COLDLINE"
    }
  }
}

resource "google_kms_crypto_key" "audit" {
  name            = "${local.name_prefix}-audit"
  key_ring        = google_kms_key_ring.main.id
  rotation_period = "7776000s"

  lifecycle {
    prevent_destroy = true
  }
}

resource "google_logging_project_sink" "audit" {
  name        = "${local.name_prefix}-audit-sink"
  destination = "storage.googleapis.com/${google_storage_bucket.audit_logs.name}"

  filter = <<-EOT
    logName:"cloudaudit.googleapis.com"
    OR resource.type="k8s_cluster"
    OR resource.type="gce_subnetwork"
  EOT

  unique_writer_identity = true
}

resource "google_storage_bucket_iam_member" "audit_sink_writer" {
  bucket = google_storage_bucket.audit_logs.name
  role   = "roles/storage.objectCreator"
  member = google_logging_project_sink.audit.writer_identity
}
