terraform {
  required_version = ">= 1.9.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # State holds resource identifiers, IAM bindings and occasionally generated secrets, so it
  # is treated as sensitive: remote, versioned, encrypted, and locked so two applies cannot
  # interleave. Backend configuration is supplied with `-backend-config` per environment
  # rather than hard-coded, so one repo cannot accidentally write prod state from a dev run.
  backend "gcs" {}
}

provider "google" {
  project = var.project_id
  region  = var.region

  default_labels = merge(
    {
      managed-by  = "terraform"
      environment = var.environment
      # Marks every resource that may touch protected health information, so the label is
      # queryable during an audit instead of being reconstructed from memory.
      data-class = "phi"
    },
    var.labels,
  )
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = merge(
      {
        ManagedBy   = "terraform"
        Environment = var.environment
        DataClass   = "phi"
      },
      var.labels,
    )
  }
}
