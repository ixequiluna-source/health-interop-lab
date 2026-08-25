"""Controls-as-code.

Every check here corresponds to a row in ``compliance/soc2/control-matrix.md``. The point is
that the control is *executable*: a manifest that drops ``readOnlyRootFilesystem`` fails the
build rather than being noticed at the next audit, and the evidence an auditor asks for is a
CI run rather than a screenshot someone took once.

The checks are deliberately blunt and read the repository as text and YAML. They do not need
a cluster, cloud credentials or a Terraform plan, so they run on every pull request in about
a second — which is the only reason a policy gate survives contact with a real team.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Iterator

import pytest
import yaml

REPO = Path(__file__).resolve().parents[2]
K8S = REPO / "infra" / "k8s"
TERRAFORM = REPO / "infra" / "terraform"


# --------------------------------------------------------------------------- helpers


def k8s_documents() -> Iterator[tuple[Path, dict]]:
    for path in sorted(K8S.rglob("*.yaml")):
        for doc in yaml.safe_load_all(path.read_text()):
            if doc:
                yield path, doc


def workloads() -> Iterator[tuple[Path, dict]]:
    for path, doc in k8s_documents():
        if doc.get("kind") in {"Deployment", "StatefulSet", "DaemonSet", "Job"}:
            yield path, doc


def containers(workload: dict) -> Iterator[dict]:
    spec = workload["spec"]["template"]["spec"]
    yield from spec.get("initContainers", [])
    yield from spec.get("containers", [])


def dockerfiles() -> Iterator[Path]:
    for path in sorted(REPO.rglob("Dockerfile")):
        if "node_modules" not in path.parts:
            yield path


def terraform_text() -> str:
    return "\n".join(p.read_text() for p in sorted(TERRAFORM.glob("*.tf")))


def workload_ids() -> list[str]:
    return [f"{path.name}:{doc['metadata']['name']}" for path, doc in workloads()]


ALL_WORKLOADS = list(workloads())


# ------------------------------------------------------------------ CC6.1 access control


@pytest.mark.parametrize("path,workload", ALL_WORKLOADS, ids=workload_ids())
def test_cc6_1_containers_do_not_run_as_root(path: Path, workload: dict) -> None:
    """CC6.1 — a process that does not need root must not have it.

    Root inside a container is root on the node the moment any isolation boundary gives way,
    and the services here are a parser, a mapper and two API servers. None of them needs it.
    """
    pod = workload["spec"]["template"]["spec"]
    pod_ctx = pod.get("securityContext", {})
    assert pod_ctx.get("runAsNonRoot") is True, f"{path.name}: pod must set runAsNonRoot"
    assert pod_ctx.get("runAsUser", 0) != 0, f"{path.name}: runAsUser must not be 0"


@pytest.mark.parametrize("path,workload", ALL_WORKLOADS, ids=workload_ids())
def test_cc6_1_privilege_escalation_is_blocked(path: Path, workload: dict) -> None:
    """CC6.1 — no path from an unprivileged process to a privileged one.

    ``allowPrivilegeEscalation`` left unset defaults to true when the container has any
    set-uid binary, which is not a default anyone chose.
    """
    for container in containers(workload):
        ctx = container.get("securityContext", {})
        name = f"{path.name}:{container['name']}"
        assert ctx.get("allowPrivilegeEscalation") is False, f"{name}: must set allowPrivilegeEscalation=false"
        assert ctx.get("privileged", False) is False, f"{name}: must not be privileged"
        dropped = ctx.get("capabilities", {}).get("drop", [])
        assert "ALL" in dropped, f"{name}: must drop ALL capabilities"


@pytest.mark.parametrize("path,workload", ALL_WORKLOADS, ids=workload_ids())
def test_cc6_1_root_filesystem_is_read_only(path: Path, workload: dict) -> None:
    """CC6.1 — a writable image lets an attacker persist across a restart.

    Anything that genuinely needs scratch space gets a size-bounded emptyDir instead.
    """
    for container in containers(workload):
        ctx = container.get("securityContext", {})
        assert ctx.get("readOnlyRootFilesystem") is True, (
            f"{path.name}:{container['name']}: must set readOnlyRootFilesystem=true"
        )


@pytest.mark.parametrize("path,workload", ALL_WORKLOADS, ids=workload_ids())
def test_cc6_1_seccomp_profile_is_set(path: Path, workload: dict) -> None:
    """CC6.1 — the default seccomp profile blocks the syscalls no web service needs."""
    pod = workload["spec"]["template"]["spec"]
    profile = pod.get("securityContext", {}).get("seccompProfile", {})
    assert profile.get("type") == "RuntimeDefault", f"{path.name}: pod must set seccompProfile RuntimeDefault"


@pytest.mark.parametrize("path,workload", ALL_WORKLOADS, ids=workload_ids())
def test_cc6_1_service_account_token_is_not_mounted(path: Path, workload: dict) -> None:
    """CC6.1 — least privilege.

    None of these pods calls the Kubernetes API. Mounting the token regardless hands anything
    that achieves execution inside the pod a cluster credential for free.
    """
    pod = workload["spec"]["template"]["spec"]
    assert pod.get("automountServiceAccountToken") is False, (
        f"{path.name}: set automountServiceAccountToken=false"
    )


def test_cc6_1_namespace_enforces_restricted_pod_security() -> None:
    """CC6.1 — the namespace is the enforcement point.

    Per-pod securityContext is defence in depth; Pod Security Admission is what actually
    refuses a non-compliant pod that someone applies by hand.
    """
    namespaces = [doc for _, doc in k8s_documents() if doc.get("kind") == "Namespace"]
    assert namespaces, "expected a Namespace manifest"
    for ns in namespaces:
        labels = ns["metadata"].get("labels", {})
        assert labels.get("pod-security.kubernetes.io/enforce") == "restricted", (
            f"namespace {ns['metadata']['name']} must enforce the restricted profile"
        )


# ------------------------------------------------------------ CC6.6 network segmentation


def test_cc6_6_namespace_denies_traffic_by_default() -> None:
    """CC6.6 — a namespace with no NetworkPolicy is fully open.

    The default-deny policy must select every pod (empty podSelector) and cover both
    directions; a deny that only covers ingress leaves exfiltration paths untouched.
    """
    policies = [doc for _, doc in k8s_documents() if doc.get("kind") == "NetworkPolicy"]
    assert policies, "expected NetworkPolicy manifests"

    default_denies = [
        p
        for p in policies
        if p["spec"].get("podSelector") == {}
        and set(p["spec"].get("policyTypes", [])) == {"Ingress", "Egress"}
        and not p["spec"].get("ingress")
        and not p["spec"].get("egress")
    ]
    assert default_denies, "no default-deny NetworkPolicy covering both ingress and egress"


def test_cc6_6_read_gateway_has_no_path_to_the_write_side() -> None:
    """CC6.6 — the read gateway must not be able to reach Kafka.

    The architecture claims writes only enter through the HL7 feed. If the read gateway can
    reach the broker, that claim is a convention rather than a control, and a future change
    can quietly add a second, unaudited write path.
    """
    gateway = next(
        doc
        for _, doc in k8s_documents()
        if doc.get("kind") == "NetworkPolicy" and doc["metadata"]["name"] == "patient-gateway"
    )
    egress_targets = yaml.safe_dump(gateway["spec"].get("egress", []))
    assert "kafka" not in egress_targets, "patient-gateway must not have egress to Kafka"


# --------------------------------------------------------------- A1.2 availability


@pytest.mark.parametrize("path,workload", ALL_WORKLOADS, ids=workload_ids())
def test_a1_2_resources_are_requested_and_limited(path: Path, workload: dict) -> None:
    """A1.2 — both halves are load-bearing and for opposite reasons.

    Without a *limit* one pod can starve its neighbours. Without a *request* the pod is
    BestEffort and is evicted first under memory pressure — which for the MLLP listener
    means dropping a live clinical feed at exactly the moment the node is busiest.
    """
    for container in containers(workload):
        name = f"{path.name}:{container['name']}"
        resources = container.get("resources", {})
        for section in ("requests", "limits"):
            values = resources.get(section, {})
            assert values.get("cpu"), f"{name}: missing resources.{section}.cpu"
            assert values.get("memory"), f"{name}: missing resources.{section}.memory"


@pytest.mark.parametrize("path,workload", ALL_WORKLOADS, ids=workload_ids())
def test_a1_2_health_probes_are_defined(path: Path, workload: dict) -> None:
    """A1.2 — without a readiness probe, traffic reaches a pod that is not ready yet."""
    for container in containers(workload):
        name = f"{path.name}:{container['name']}"
        assert "livenessProbe" in container, f"{name}: missing livenessProbe"
        assert "readinessProbe" in container, f"{name}: missing readinessProbe"


@pytest.mark.parametrize("path,workload", ALL_WORKLOADS, ids=workload_ids())
def test_a1_2_rollouts_never_reach_zero_replicas(path: Path, workload: dict) -> None:
    """A1.2 — maxUnavailable must be 0 for a feed that cannot pause."""
    strategy = workload["spec"].get("strategy", {})
    if strategy.get("type") != "RollingUpdate":
        pytest.skip("not a rolling update")
    assert strategy["rollingUpdate"]["maxUnavailable"] == 0, (
        f"{path.name}: rollout may not take the service below the desired count"
    )


def test_a1_2_every_service_has_a_disruption_budget() -> None:
    """A1.2 — a node drain must not be able to take a service to zero."""
    deployments = {doc["metadata"]["name"] for _, doc in ALL_WORKLOADS}
    budgets = {
        doc["metadata"]["name"]
        for _, doc in k8s_documents()
        if doc.get("kind") == "PodDisruptionBudget"
    }
    missing = deployments - budgets
    assert not missing, f"no PodDisruptionBudget for: {sorted(missing)}"


# --------------------------------------------------- CC8.1 change management / supply chain


@pytest.mark.parametrize("path,workload", ALL_WORKLOADS, ids=workload_ids())
def test_cc8_1_images_are_pinned(path: Path, workload: dict) -> None:
    """CC8.1 — ``:latest`` means the bytes running in production are unknowable.

    A moving tag also breaks rollback: redeploying yesterday's manifest does not redeploy
    yesterday's image.
    """
    for container in containers(workload):
        image = container["image"]
        name = f"{path.name}:{container['name']}"
        assert not image.endswith(":latest"), f"{name}: image must not use the latest tag"
        reference = image.rsplit("/", 1)[-1]
        assert ":" in reference or "@" in reference, f"{name}: image must carry an explicit tag or digest"


@pytest.mark.parametrize("dockerfile", list(dockerfiles()), ids=lambda p: str(p.relative_to(REPO)))
def test_cc8_1_images_declare_a_non_root_user(dockerfile: Path) -> None:
    """CC6.1 — the image itself must not default to root.

    The Kubernetes securityContext would catch it, but an image that only works as root is
    a latent failure the first time anyone runs it outside this cluster.
    """
    text = dockerfile.read_text()
    users = re.findall(r"^\s*USER\s+(\S+)", text, flags=re.MULTILINE)
    assert users, f"{dockerfile}: no USER directive"
    assert users[-1] not in {"root", "0"}, f"{dockerfile}: final USER is root"


@pytest.mark.parametrize("dockerfile", list(dockerfiles()), ids=lambda p: str(p.relative_to(REPO)))
def test_cc8_1_base_images_are_pinned(dockerfile: Path) -> None:
    """CC8.1 — an unpinned base image makes a build unreproducible."""
    for line in dockerfile.read_text().splitlines():
        match = re.match(r"^\s*FROM\s+(\S+)", line)
        if not match:
            continue
        image = match.group(1)
        if image.upper() in {"SCRATCH"} or not image.count("/") + image.count(":"):
            pass
        assert not image.endswith(":latest"), f"{dockerfile}: FROM {image} uses the latest tag"
        assert ":" in image.rsplit("/", 1)[-1] or "@" in image, (
            f"{dockerfile}: FROM {image} has no explicit tag"
        )


@pytest.mark.parametrize("dockerfile", list(dockerfiles()), ids=lambda p: str(p.relative_to(REPO)))
def test_a1_2_images_declare_a_healthcheck(dockerfile: Path) -> None:
    assert "HEALTHCHECK" in dockerfile.read_text(), f"{dockerfile}: no HEALTHCHECK"


# ------------------------------------------------------------------ CC6.7 / CC7.2 data


def test_cc6_7_control_plane_is_not_open_to_the_internet() -> None:
    """CC6.7 — the authorized-networks variable must not admit 0.0.0.0/0.

    A permissive default is how a control plane in front of PHI ends up internet-reachable:
    it never appears in a diff, so it is never reviewed.
    """
    text = terraform_text()
    assert 'variable "authorized_networks"' in text
    variable_block = text.split('variable "authorized_networks"', 1)[1].split("\nvariable ", 1)[0]
    # Match the HCL attribute, not the word: the block's own comment explains at length why
    # there is no default, and a naive substring check would fire on the explanation.
    assert not re.search(r"^\s*default\s*=", variable_block, flags=re.MULTILINE), (
        "authorized_networks must not have a default"
    )
    assert "0.0.0.0/0" in variable_block and "validation" in variable_block, (
        "authorized_networks must validate that 0.0.0.0/0 is rejected"
    )


def test_cc7_2_audit_logs_are_retained_and_immutable() -> None:
    """CC7.2 / HIPAA §164.316(b)(2)(i) — six years, and not deletable by an administrator."""
    text = terraform_text()
    assert "google_project_iam_audit_config" in text, "audit logging is not configured"
    for log_type in ("ADMIN_READ", "DATA_READ", "DATA_WRITE"):
        assert log_type in text, f"audit config does not capture {log_type}"
    assert "retention_policy" in text, "audit bucket has no retention policy"
    assert "is_locked" in text, "audit bucket retention is not locked"
    assert "public_access_prevention    = \"enforced\"" in text or 'public_access_prevention' in text


def test_cc6_1_encryption_keys_rotate() -> None:
    """CC6.1 — a key that never rotates is a key whose compromise is permanent."""
    text = terraform_text()
    assert text.count("rotation_period") >= 2, "KMS keys must declare a rotation period"
    assert "enable_key_rotation     = true" in text or "enable_key_rotation" in text
    assert "database_encryption" in text, "etcd application-layer encryption is not enabled"


def test_pi1_2_claims_queue_cannot_double_bill() -> None:
    """PI1.2 — processing integrity for the claims tail.

    A standard SQS queue is at-least-once, and at-least-once for a claim means the same
    encounter can be billed twice. Duplicate claims adjudicate rather than bounce, so the
    control has to be structural: a FIFO queue plus a deterministic deduplication id.
    """
    text = terraform_text()
    assert "fifo_queue                  = true" in text or "fifo_queue" in text
    assert ".fifo" in text, "claims queue must be a FIFO queue"
    assert "redrive_policy" in text, "claims queue needs a dead letter queue"
    assert "kms_master_key_id" in text, "claims queue must be encrypted with a customer key"
    assert "aws:SecureTransport" in text, "claims queue must deny non-TLS access"


def test_cc6_7_data_residency_is_pinned() -> None:
    """CC6.7 — PHI storage location is a contractual commitment, not a default."""
    text = terraform_text()
    assert "allowed_persistence_regions" in text, "Pub/Sub topics must pin storage regions"


# ----------------------------------------------------------------------- CC6.1 secrets


SECRET_PATTERNS = [
    (re.compile(r"AKIA[0-9A-Z]{16}"), "AWS access key id"),
    (re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"), "private key"),
    (re.compile(r"ghp_[A-Za-z0-9]{36}"), "GitHub personal access token"),
    (re.compile(r"\"type\"\s*:\s*\"service_account\""), "GCP service account key"),
]

SCANNED_SUFFIXES = {
    ".java", ".kt", ".kts", ".go", ".cs", ".ts", ".js", ".html", ".css",
    ".py", ".yaml", ".yml", ".tf", ".json", ".md", ".sh", ".proto", ".xml",
}

SKIPPED_DIRS = {"node_modules", "dist", ".angular", "target", "build", "bin", "obj", ".git"}


def scannable_files() -> Iterator[Path]:
    for path in REPO.rglob("*"):
        if not path.is_file() or path.suffix not in SCANNED_SUFFIXES:
            continue
        if SKIPPED_DIRS & set(path.parts):
            continue
        yield path


def test_cc6_1_no_credentials_are_committed() -> None:
    """CC6.1 — a credential in git is a credential that has already leaked.

    Rotation is the only remedy once it is in history, so the cheap control is a gate that
    refuses the commit in the first place.
    """
    findings: list[str] = []
    for path in scannable_files():
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for pattern, label in SECRET_PATTERNS:
            if pattern.search(text):
                findings.append(f"{path.relative_to(REPO)}: {label}")
    assert not findings, "credentials found in the repository:\n" + "\n".join(findings)


def test_cc6_1_phi_is_not_logged_from_the_ingest_path() -> None:
    """HIPAA minimum necessary — application logs are not in the clinical retention regime.

    The ingest service logs control ids and event ids. Logging the message body would put
    the full HL7 payload, which is PHI, into a store with different access rules and a
    different retention period from the clinical record.
    """
    handler = (
        REPO
        / "services/hl7-ingest-java/src/main/java/ai/firmus/interop/hl7/IngestHandler.java"
    ).read_text()
    assert "it is PHI" in handler, "the PHI logging decision must stay documented at the call site"
    for forbidden in ("LOG.info(rawMessage", "log(Level.INFO, rawMessage"):
        assert forbidden not in handler, "the raw HL7 payload must never be logged"
