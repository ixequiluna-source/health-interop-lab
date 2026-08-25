variable "project_id" {
  description = "GCP project that owns the cluster and the data plane."
  type        = string
}

variable "region" {
  description = "GCP region. Data residency is a HIPAA/SOC 2 concern, not a latency preference."
  type        = string
  default     = "us-central1"
}

variable "aws_region" {
  description = "AWS region for the claims queues. Clearinghouses are AWS-side in this design."
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Deployment environment; part of every resource name and label."
  type        = string

  validation {
    # Free-form environment names produce resources nobody can attribute during an audit,
    # and a typo silently creates a parallel set of infrastructure that outlives the plan.
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be one of: dev, staging, prod."
  }
}

variable "gke_node_count" {
  description = "Nodes per zone in the primary pool."
  type        = number
  default     = 2

  validation {
    condition     = var.gke_node_count >= 1 && var.gke_node_count <= 20
    error_message = "gke_node_count must be between 1 and 20."
  }
}

variable "gke_machine_type" {
  description = "Machine type for the primary node pool."
  type        = string
  default     = "e2-standard-4"
}

variable "authorized_networks" {
  description = <<-EOT
    CIDR blocks permitted to reach the GKE control plane.

    Deliberately has no default. A default of 0.0.0.0/0 is the single most common way a
    cluster holding PHI ends up with a control plane exposed to the internet, and a default
    is exactly the kind of decision that survives review unnoticed.
  EOT
  type = list(object({
    cidr_block   = string
    display_name = string
  }))

  validation {
    condition     = !contains([for n in var.authorized_networks : n.cidr_block], "0.0.0.0/0")
    error_message = "0.0.0.0/0 is not an acceptable authorized network for a PHI control plane."
  }
}

variable "phi_retention_days" {
  description = "Retention for audit logs. HIPAA §164.316(b)(2)(i) requires six years."
  type        = number
  default     = 2192 # 6 years

  validation {
    condition     = var.phi_retention_days >= 2192
    error_message = "HIPAA requires documentation retention of at least six years (2192 days)."
  }
}

variable "labels" {
  description = "Extra labels merged into every resource."
  type        = map(string)
  default     = {}
}
