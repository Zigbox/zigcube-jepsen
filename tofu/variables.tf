variable "project_id" {
  description = "GCP project (repo variable GCP_PROJECT_ID)."
  type        = string
}

variable "region" {
  description = "Region of the subnet, router and NAT (repo variable GCP_REGION)."
  type        = string
  default     = "us-central1"
}

variable "zone" {
  description = "Zone of every VM (repo variable GCP_ZONE)."
  type        = string
}

variable "run_id" {
  description = "The GitHub run id. Names every resource (zj-<run_id>-...) and labels the VMs."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-z][0-9a-z-]{0,20}$", var.run_id))
    error_message = "run_id: lowercase letters, digits and hyphens, at most 21 characters."
  }
}

variable "created_unix" {
  description = "When the run started (seconds since the epoch), a label for the sweeper and for people."
  type        = string
  default     = "0"

  validation {
    condition     = can(regex("^[0-9]+$", var.created_unix))
    error_message = "created_unix: digits only."
  }
}

variable "root_ssh_public_key" {
  description = <<-EOT
    The public half of a throwaway key the CI run generates. The nodes put it
    in root's authorized_keys and accept it only from inside the subnet, so
    only the control VM (which gets the private half over IAP) can use it.
    Jepsen drives the nodes as root over SSH. Not a secret.
  EOT
  type        = string
  default     = ""
}

variable "node_count" {
  description = "Jepsen nodes n1..nN. The harness wants 5 (2 Postgres, 1 server, 2 agents)."
  type        = number
  default     = 5

  validation {
    condition     = var.node_count >= 1 && var.node_count <= 9
    error_message = "node_count: 1 to 9."
  }
}

variable "node_machine_type" {
  description = "Machine type of the nodes. 5 x e2-standard-2 + 1 x e2-standard-2 control = 12 vCPU, the project's global CPU quota (CPUS_ALL_REGIONS)."
  type        = string
  default     = "e2-standard-2"
}

variable "control_machine_type" {
  description = "Machine type of the control VM (lein, Jepsen, the clients)."
  type        = string
  default     = "e2-standard-2"
}

variable "disk_gb" {
  description = "Boot disk size of every VM."
  type        = number
  default     = 20
}

variable "subnet_cidr" {
  description = "The run's subnet. The control VM is host .10, node nK is host .1K."
  type        = string
  default     = "10.78.0.0/24"
}

variable "max_run_hours" {
  description = "GCE deletes each VM after this long whatever CI does (a backstop under the sweeper)."
  type        = number
  default     = 5
}
