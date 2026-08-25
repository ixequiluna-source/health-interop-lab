# SOC 2 control matrix

Every row names a control, the artifact that implements it, and the automated check that
fails the build when it regresses. The checks live in
[`tools/policy/test_policies.py`](../../tools/policy/test_policies.py) and run on every push.

**What this is.** A demonstration that security and availability controls can be expressed as
code and enforced by CI rather than asserted in a document. Sixty-eight checks currently run
in under two seconds.

**What this is not.** A SOC 2 report. A real attestation covers a service organization's
operating effectiveness over a period, assessed by an independent auditor, and most of its
scope is organizational — access reviews, vendor management, onboarding and offboarding,
incident response drills — none of which a repository can evidence. Nothing here should be
read as a claim of certification.

---

## Security — common criteria

| Control | Criterion | Implementation | Automated check |
|---|---|---|---|
| Workloads do not run as root | CC6.1 | `runAsNonRoot: true`, `runAsUser: 10001` in every pod spec | `test_cc6_1_containers_do_not_run_as_root` |
| No privilege escalation path | CC6.1 | `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]` | `test_cc6_1_privilege_escalation_is_blocked` |
| Immutable container filesystem | CC6.1 | `readOnlyRootFilesystem: true` + bounded `emptyDir` for scratch | `test_cc6_1_root_filesystem_is_read_only` |
| Syscall surface reduced | CC6.1 | `seccompProfile: RuntimeDefault` | `test_cc6_1_seccomp_profile_is_set` |
| Least-privilege identity | CC6.1 | `automountServiceAccountToken: false`; Workload Identity instead of key files | `test_cc6_1_service_account_token_is_not_mounted` |
| Enforcement at the namespace | CC6.1 | Pod Security Admission `enforce: restricted` | `test_cc6_1_namespace_enforces_restricted_pod_security` |
| Images do not default to root | CC6.1 | `USER` in every Dockerfile | `test_cc8_1_images_declare_a_non_root_user` |
| Encryption keys rotate | CC6.1 | KMS `rotation_period`, `enable_key_rotation`, etcd `database_encryption` | `test_cc6_1_encryption_keys_rotate` |
| No credentials in source | CC6.1 | Pattern scan across the tree | `test_cc6_1_no_credentials_are_committed` |
| PHI is not written to application logs | CC6.1 / HIPAA minimum necessary | Ingest logs control ids only; decision documented at the call site | `test_cc6_1_phi_is_not_logged_from_the_ingest_path` |
| Default-deny network posture | CC6.6 | `default-deny-all` NetworkPolicy + Calico enabled on the cluster | `test_cc6_6_namespace_denies_traffic_by_default` |
| Read path cannot become a write path | CC6.6 | `patient-gateway` NetworkPolicy has no egress to Kafka | `test_cc6_6_read_gateway_has_no_path_to_the_write_side` |
| Control plane is not internet-facing | CC6.7 | `authorized_networks` has no default and rejects `0.0.0.0/0` | `test_cc6_7_control_plane_is_not_open_to_the_internet` |
| Data residency is pinned | CC6.7 | `allowed_persistence_regions` on every Pub/Sub topic | `test_cc6_7_data_residency_is_pinned` |
| Audit logs retained and immutable | CC7.2 / HIPAA §164.316(b)(2)(i) | Admin/data read/write audit config; six-year locked bucket retention | `test_cc7_2_audit_logs_are_retained_and_immutable` |
| Deployed bytes are knowable | CC8.1 | No `:latest`; immutable Artifact Registry tags; digest pinning in the prod overlay | `test_cc8_1_images_are_pinned`, `test_cc8_1_base_images_are_pinned` |

## Availability

| Control | Criterion | Implementation | Automated check |
|---|---|---|---|
| Resource requests and limits | A1.2 | Both set on every container | `test_a1_2_resources_are_requested_and_limited` |
| Health is observable | A1.2 | Liveness, readiness and startup probes; `HEALTHCHECK` in every image | `test_a1_2_health_probes_are_defined`, `test_a1_2_images_declare_a_healthcheck` |
| Rollouts never reach zero | A1.2 | `maxUnavailable: 0` | `test_a1_2_rollouts_never_reach_zero_replicas` |
| Voluntary disruption is bounded | A1.2 | A PodDisruptionBudget per service | `test_a1_2_every_service_has_a_disruption_budget` |

## Processing integrity

| Control | Criterion | Implementation | Automated check |
|---|---|---|---|
| A claim cannot be billed twice | PI1.2 | FIFO queue, deterministic `MessageDeduplicationId`, dead letter after 3 attempts, TLS-only policy | `test_pi1_2_claims_queue_cannot_double_bill` |
| An admission cannot be lost or duplicated | PI1.2 | Kafka `acks=all` + idempotent producer, asserted in `KafkaEventSinkConfigTest` | Java unit tests |
| An unacknowledged message is retried | PI1.2 | ACK withheld on publish failure; connection dropped | `IngestHandlerTest.publishFailureIsRetryable` |

---

## Why some of these are worth arguing about

**`requests` and `limits` are not the same control.** A container with no limit can starve its
neighbours. A container with no request gets BestEffort QoS and is the first thing evicted
under node memory pressure — which for the MLLP listener means dropping a live clinical feed
at the moment the node is busiest. Checking only one of them looks like diligence and covers
half the failure.

**NetworkPolicy without an enforcer is inert.** Applying these policies to a cluster with no
CNI enforcement produces YAML that reads exactly like segmentation and does nothing. That is
why `network_policy { enabled = true }` sits in the Terraform and is not optional.

**Redaction belongs in the collector, not only in the services.** Every service here is
written not to put patient identity in a span attribute. The collector deletes those keys
anyway, because the next span attribute will be added by someone who has not read this file.

**Hashing an identifier is not de-identification.** A hashed MRN is a stable per-patient key,
so anyone who can correlate it against a second dataset re-identifies the patient. The
collector deletes rather than hashes.

**Six years is not a default.** GCS buckets keep objects forever unless told otherwise, and
"forever" is not the same control as a locked retention policy: an administrator can delete
from the former. Audit logs an administrator can quietly remove are not evidence.
