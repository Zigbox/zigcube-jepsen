# One Jepsen cluster on GCP, created and destroyed by .github/workflows/gcp.yml.
#
# A VPC of its own with one subnet, Cloud NAT for egress (apt, Maven), no
# external IPs. SSH reaches the VMs only from IAP's range (the CI runner
# talks to the control VM through an IAP tunnel, as the CI service account,
# with OS Login) and from inside the subnet (the control VM drives the
# nodes as root, as Jepsen expects). See docs/decisions/2026-11-17-gcp-opentofu.md.

locals {
  prefix = "zj-${var.run_id}"

  # Networks, subnets, routers and firewall rules take no labels: the sweeper
  # finds them by the zj- name prefix and their creation time.
  labels = {
    purpose      = "jepsen"
    run_id       = var.run_id
    created_unix = var.created_unix
  }

  control_ip = cidrhost(var.subnet_cidr, 10)
  nodes      = { for i in range(1, var.node_count + 1) : "n${i}" => cidrhost(var.subnet_cidr, 10 + i) }

  # Every VM gets these names in /etc/hosts: Jepsen and the harness name the
  # nodes n1..n5, and the nodes resolve each other (cn/ip) the same way.
  hosts = join("\n", concat(
    ["${local.control_ip} control"],
    [for name, ip in local.nodes : "${ip} ${name}"],
  ))

  common_metadata = {
    enable-oslogin         = "TRUE"
    block-project-ssh-keys = "TRUE"
    serial-port-enable     = "FALSE"
    startup-script         = file("${path.module}/scripts/startup.sh")
    zj-hosts               = local.hosts
    zj-subnet              = var.subnet_cidr
  }
}

data "google_compute_image" "ubuntu" {
  project = "ubuntu-os-cloud"
  family  = "ubuntu-2404-lts-amd64"
}

resource "google_compute_network" "net" {
  name                    = "${local.prefix}-net"
  description             = "purpose=jepsen run_id=${var.run_id} created_unix=${var.created_unix}"
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "subnet" {
  name                     = "${local.prefix}-subnet"
  region                   = var.region
  network                  = google_compute_network.net.id
  ip_cidr_range            = var.subnet_cidr
  private_ip_google_access = true
}

resource "google_compute_router" "router" {
  name    = "${local.prefix}-router"
  region  = var.region
  network = google_compute_network.net.id
}

resource "google_compute_router_nat" "nat" {
  name                               = "${local.prefix}-nat"
  router                             = google_compute_router.router.name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"
}

resource "google_compute_firewall" "iap_ssh" {
  name          = "${local.prefix}-iap-ssh"
  description   = "SSH from Identity-Aware Proxy's TCP forwarding range only."
  network       = google_compute_network.net.id
  direction     = "INGRESS"
  source_ranges = ["35.235.240.0/20"]

  allow {
    protocol = "tcp"
    ports    = ["22"]
  }
}

resource "google_compute_firewall" "internal" {
  name          = "${local.prefix}-internal"
  description   = "Everything between the VMs of the run's subnet: Jepsen's SSH, Postgres, zcube-server, the hold proxy."
  network       = google_compute_network.net.id
  direction     = "INGRESS"
  source_ranges = [var.subnet_cidr]

  allow {
    protocol = "tcp"
  }
  allow {
    protocol = "udp"
  }
  allow {
    protocol = "icmp"
  }
}

# The VMs. No service account (so no token on the metadata server), no
# external IP, OS Login on, shielded boot. GCE deletes them after
# max_run_hours even if neither CI's destroy nor the sweeper runs.

resource "google_compute_instance" "control" {
  name                      = "${local.prefix}-control"
  description               = "Jepsen control: lein, the test, the clients."
  machine_type              = var.control_machine_type
  zone                      = var.zone
  labels                    = merge(local.labels, { role = "control" })
  allow_stopping_for_update = true

  boot_disk {
    auto_delete = true
    initialize_params {
      image  = data.google_compute_image.ubuntu.self_link
      size   = var.disk_gb
      type   = "pd-balanced"
      labels = local.labels
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.subnet.id
    network_ip = local.control_ip
  }

  metadata = merge(local.common_metadata, { zj-role = "control" })

  scheduling {
    provisioning_model          = "STANDARD"
    automatic_restart           = false
    on_host_maintenance         = "MIGRATE"
    instance_termination_action = "DELETE"
    max_run_duration {
      seconds = var.max_run_hours * 3600
    }
  }

  shielded_instance_config {
    enable_secure_boot          = true
    enable_vtpm                 = true
    enable_integrity_monitoring = true
  }

  # NAT first: the startup script installs packages.
  depends_on = [google_compute_router_nat.nat, google_compute_firewall.internal]
}

resource "google_compute_instance" "node" {
  for_each = local.nodes

  name                      = "${local.prefix}-${each.key}"
  description               = "Jepsen node ${each.key}."
  machine_type              = var.node_machine_type
  zone                      = var.zone
  labels                    = merge(local.labels, { role = "node" })
  allow_stopping_for_update = true

  boot_disk {
    auto_delete = true
    initialize_params {
      image  = data.google_compute_image.ubuntu.self_link
      size   = var.disk_gb
      type   = "pd-balanced"
      labels = local.labels
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.subnet.id
    network_ip = each.value
  }

  metadata = merge(local.common_metadata, {
    zj-role     = "node"
    zj-root-key = var.root_ssh_public_key
  })

  scheduling {
    provisioning_model          = "STANDARD"
    automatic_restart           = false
    on_host_maintenance         = "MIGRATE"
    instance_termination_action = "DELETE"
    max_run_duration {
      seconds = var.max_run_hours * 3600
    }
  }

  shielded_instance_config {
    enable_secure_boot          = true
    enable_vtpm                 = true
    enable_integrity_monitoring = true
  }

  depends_on = [google_compute_router_nat.nat, google_compute_firewall.internal]

  lifecycle {
    precondition {
      condition     = can(regex("^ssh-", var.root_ssh_public_key))
      error_message = "root_ssh_public_key must be an OpenSSH public key."
    }
  }
}
