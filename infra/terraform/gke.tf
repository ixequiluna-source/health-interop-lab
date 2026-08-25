locals {
  name_prefix = "interop-${var.environment}"
}

# Own VPC rather than the default network: the default has permissive firewall rules and
# auto-created subnets in every region, which is the opposite of a bounded PHI environment.
resource "google_compute_network" "main" {
  name                    = "${local.name_prefix}-vpc"
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "nodes" {
  name          = "${local.name_prefix}-nodes"
  ip_cidr_range = "10.10.0.0/20"
  region        = var.region
  network       = google_compute_network.main.id

  # Flow logs are an audit control (SOC 2 CC7.2): without them there is no record of who
  # talked to what inside the cluster, which is the first question asked after an incident.
  log_config {
    aggregation_interval = "INTERVAL_5_SEC"
    flow_sampling        = 0.5
    metadata             = "INCLUDE_ALL_METADATA"
  }

  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = "10.20.0.0/16"
  }
  secondary_ip_range {
    range_name    = "services"
    ip_cidr_range = "10.30.0.0/20"
  }

  private_ip_google_access = true
}

# Private nodes have no external addresses, so egress needs an explicit, auditable path.
resource "google_compute_router" "main" {
  name    = "${local.name_prefix}-router"
  region  = var.region
  network = google_compute_network.main.id
}

resource "google_compute_router_nat" "main" {
  name                               = "${local.name_prefix}-nat"
  router                             = google_compute_router.main.name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"

  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }
}

# Workload Identity replaces long-lived service-account keys. A JSON key mounted into a pod
# is a credential that never expires, is copied into laptops and CI, and cannot be revoked
# without breaking everything that shares it.
resource "google_service_account" "workload" {
  account_id   = "${local.name_prefix}-workload"
  display_name = "Interop platform workloads (${var.environment})"
}

resource "google_container_cluster" "main" {
  name     = "${local.name_prefix}-gke"
  location = var.region

  # The default pool is removed immediately; the managed pool below carries real settings.
  remove_default_node_pool = true
  initial_node_count       = 1

  network    = google_compute_network.main.id
  subnetwork = google_compute_subnetwork.nodes.id

  # Deleting a cluster that holds PHI must be a deliberate, separate act.
  deletion_protection = var.environment == "prod"

  release_channel {
    channel = "REGULAR"
  }

  workload_identity_config {
    workload_pool = "${var.project_id}.svc.id.goog"
  }

  private_cluster_config {
    enable_private_nodes    = true
    enable_private_endpoint = false
    master_ipv4_cidr_block  = "172.16.0.0/28"
  }

  master_authorized_networks_config {
    dynamic "cidr_blocks" {
      for_each = var.authorized_networks
      content {
        cidr_block   = cidr_blocks.value.cidr_block
        display_name = cidr_blocks.value.display_name
      }
    }
  }

  ip_allocation_policy {
    cluster_secondary_range_name  = "pods"
    services_secondary_range_name = "services"
  }

  # Application-layer secret encryption: without it, Kubernetes Secrets are base64 in etcd,
  # which is encoding, not encryption, and is not a control an auditor accepts.
  database_encryption {
    state    = "ENCRYPTED"
    key_name = google_kms_crypto_key.etcd.id
  }

  # NetworkPolicy must be enabled at the cluster for the policies in infra/k8s to be
  # enforced. Applying NetworkPolicy objects to a cluster without an enforcer is the classic
  # false sense of segmentation: the YAML exists, nothing reads it.
  network_policy {
    enabled  = true
    provider = "CALICO"
  }

  addons_config {
    network_policy_config {
      disabled = false
    }
  }

  logging_config {
    enable_components = ["SYSTEM_COMPONENTS", "WORKLOADS", "APISERVER"]
  }

  monitoring_config {
    enable_components = ["SYSTEM_COMPONENTS"]
    managed_prometheus {
      enabled = true
    }
  }

  binary_authorization {
    evaluation_mode = "PROJECT_SINGLETON_POLICY_ENFORCE"
  }
}

resource "google_container_node_pool" "primary" {
  name       = "${local.name_prefix}-primary"
  location   = var.region
  cluster    = google_container_cluster.main.name
  node_count = var.gke_node_count

  management {
    auto_repair  = true
    auto_upgrade = true
  }

  upgrade_settings {
    max_surge       = 1
    max_unavailable = 0
  }

  node_config {
    machine_type = var.gke_machine_type
    disk_size_gb = 100
    disk_type    = "pd-balanced"

    # Least privilege for the node itself; pods get their own identity via Workload Identity.
    service_account = google_service_account.workload.email
    oauth_scopes    = ["https://www.googleapis.com/auth/cloud-platform"]

    shielded_instance_config {
      enable_secure_boot          = true
      enable_integrity_monitoring = true
    }

    workload_metadata_config {
      mode = "GKE_METADATA"
    }

    # Legacy metadata endpoints let any pod read node credentials.
    metadata = {
      disable-legacy-endpoints = "true"
    }

    labels = {
      environment = var.environment
      data-class  = "phi"
    }
  }
}

resource "google_kms_key_ring" "main" {
  name     = "${local.name_prefix}-keyring"
  location = var.region
}

resource "google_kms_crypto_key" "etcd" {
  name     = "${local.name_prefix}-etcd"
  key_ring = google_kms_key_ring.main.id

  # Rotation is a stated SOC 2 control; annual is the common baseline for envelope keys.
  rotation_period = "7776000s" # 90 days

  lifecycle {
    prevent_destroy = true
  }
}

resource "google_artifact_registry_repository" "images" {
  location      = var.region
  repository_id = "${local.name_prefix}-images"
  format        = "DOCKER"
  description   = "Service images for the interoperability platform"

  docker_config {
    # Tags cannot be moved once pushed, so the digest behind a deployed tag cannot change
    # underneath a running cluster. This is what makes an image tag an audit artifact.
    immutable_tags = true
  }
}
