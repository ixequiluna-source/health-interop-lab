# Claims leave the platform through SQS toward the clearinghouse.
#
# FIFO rather than a standard queue, for one reason: a standard queue delivers at least once,
# and "at least once" for a healthcare claim means the same encounter can be billed twice.
# Duplicate billing does not bounce — it adjudicates, and the recovery is a recoupment and,
# in the United States, potential False Claims Act exposure. Content-based deduplication plus
# the deterministic MessageDeduplicationId the C# service sends closes the common retry path.

resource "aws_sqs_queue" "claims_dlq" {
  name       = "interop-${var.environment}-claims-dlq.fifo"
  fifo_queue = true

  # A dead letter must outlive a long weekend plus the investigation that follows it.
  message_retention_seconds = 1209600 # 14 days, the maximum

  kms_master_key_id                 = aws_kms_key.claims.id
  kms_data_key_reuse_period_seconds = 300
}

resource "aws_sqs_queue" "claims" {
  name                        = "interop-${var.environment}-claims.fifo"
  fifo_queue                  = true
  content_based_deduplication = false

  # Deduplication is explicit: the producer sends a MessageDeduplicationId derived from the
  # claim id. Content-based deduplication would hash the whole body, so a regenerated claim
  # that differs only in its control number would be treated as new — which is exactly the
  # duplicate this queue exists to prevent.
  deduplication_scope   = "messageGroup"
  fifo_throughput_limit = "perMessageGroupId"

  # Long enough for the consumer to build, validate and transmit an interchange, and short
  # enough that a crashed consumer's messages return promptly.
  visibility_timeout_seconds = 300
  message_retention_seconds  = 345600 # 4 days
  receive_wait_time_seconds  = 20     # long polling; short polling burns requests on empty receives

  kms_master_key_id                 = aws_kms_key.claims.id
  kms_data_key_reuse_period_seconds = 300

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.claims_dlq.arn
    # Three attempts distinguishes a transient clearinghouse failure from a claim that will
    # never succeed. Without a redrive policy a poison message blocks its message group
    # forever, and because this is a FIFO queue that stalls every claim for that patient.
    maxReceiveCount = 3
  })
}

resource "aws_kms_key" "claims" {
  description             = "Encrypts claims in transit through SQS (${var.environment})"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "claims" {
  name          = "alias/interop-${var.environment}-claims"
  target_key_id = aws_kms_key.claims.key_id
}

# Deny any unencrypted access to the queue.
#
# SQS is encrypted at rest by the KMS key above, but that says nothing about the transport.
# This policy refuses requests that did not arrive over TLS, so a misconfigured client fails
# loudly instead of sending claims in the clear.
resource "aws_sqs_queue_policy" "claims_tls_only" {
  queue_url = aws_sqs_queue.claims.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyNonTLS"
        Effect    = "Deny"
        Principal = "*"
        Action    = "sqs:*"
        Resource  = aws_sqs_queue.claims.arn
        Condition = {
          Bool = { "aws:SecureTransport" = "false" }
        }
      },
    ]
  })
}

# The Kafka topic the Java ingest writes and the Kotlin mapper reads.
#
# Modelled as a Pub/Sub topic here because the platform runs its own Kafka in-cluster for the
# clinical feed and uses Pub/Sub only for cross-service notification; the retention setting
# is the part that matters and is stated explicitly rather than left at the default.
resource "google_pubsub_topic" "admissions" {
  name = "${local.name_prefix}-admissions"

  message_retention_duration = "604800s" # 7 days

  message_storage_policy {
    # Pins message storage to the chosen region. Data residency for PHI is a contractual and
    # regulatory commitment, and the default policy allows storage anywhere.
    allowed_persistence_regions = [var.region]
  }

  schema_settings {
    schema   = google_pubsub_schema.admission_event.id
    encoding = "JSON"
  }
}

# A schema on the topic is a runtime contract: a producer that changes the event shape is
# rejected at publish time rather than discovered by a consumer failing in production.
resource "google_pubsub_schema" "admission_event" {
  name = "${local.name_prefix}-admission-event"
  type = "AVRO"

  definition = jsonencode({
    type      = "record"
    name      = "AdmissionEvent"
    namespace = "ai.firmus.interop"
    fields = [
      { name = "schemaVersion", type = "string" },
      { name = "eventId", type = "string" },
      { name = "messageControlId", type = ["null", "string"], default = null },
      { name = "messageType", type = ["null", "string"], default = null },
      { name = "sendingApplication", type = ["null", "string"], default = null },
      { name = "sendingFacility", type = ["null", "string"], default = null },
      { name = "recordedAt", type = "string" },
    ]
  })

  lifecycle {
    # Pub/Sub schemas are immutable; a change forces replacement, which would detach the
    # topic. Revisions are added as new schema resources instead.
    prevent_destroy = true
  }
}

resource "google_pubsub_topic" "admissions_dead_letter" {
  name                       = "${local.name_prefix}-admissions-dlq"
  message_retention_duration = "604800s"

  message_storage_policy {
    allowed_persistence_regions = [var.region]
  }
}
