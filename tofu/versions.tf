terraform {
  required_version = ">= 1.8.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "= 8.11.0"
    }
  }

  # Partial configuration: CI passes the bucket (repo variable
  # TF_STATE_BUCKET) and a per-run prefix, runs/<github run id>, with
  # -backend-config. `tofu init -backend=false` for offline checks.
  backend "gcs" {}
}

provider "google" {
  project = var.project_id
  region  = var.region
  zone    = var.zone
}
