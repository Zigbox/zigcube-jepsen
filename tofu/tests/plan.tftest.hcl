# Offline: `tofu test` plans against a mocked Google provider, no
# credentials. Checks what the configuration decides on its own: names,
# addresses, labels, no external IP, no service account, OS Login.

mock_provider "google" {
  mock_data "google_compute_image" {
    defaults = {
      name      = "ubuntu-2404-noble-amd64-v20261110"
      self_link = "https://www.googleapis.com/compute/v1/projects/ubuntu-os-cloud/global/images/ubuntu-2404-noble-amd64-v20261110"
    }
  }
}

variables {
  project_id          = "example-project"
  region              = "us-central1"
  zone                = "us-central1-a"
  run_id              = "12345678901"
  created_unix        = "1794787200"
  root_ssh_public_key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample zj-test"
}

run "cluster" {
  command = plan

  assert {
    condition     = length(google_compute_instance.node) == 5
    error_message = "five nodes"
  }
  assert {
    condition     = google_compute_instance.node["n1"].network_interface[0].network_ip == "10.78.0.11" && google_compute_instance.node["n5"].network_interface[0].network_ip == "10.78.0.15"
    error_message = "nK is host .1K of the subnet"
  }
  assert {
    condition     = google_compute_instance.control.network_interface[0].network_ip == "10.78.0.10"
    error_message = "control is host .10"
  }
  assert {
    condition     = alltrue([for n in values(google_compute_instance.node) : length(n.network_interface[0].access_config) == 0]) && length(google_compute_instance.control.network_interface[0].access_config) == 0
    error_message = "no external IPs"
  }
  assert {
    condition     = alltrue([for n in values(google_compute_instance.node) : length(n.service_account) == 0]) && length(google_compute_instance.control.service_account) == 0
    error_message = "no service account on any VM"
  }
  assert {
    condition     = alltrue([for n in values(google_compute_instance.node) : n.metadata["enable-oslogin"] == "TRUE" && n.labels["purpose"] == "jepsen" && n.labels["run_id"] == "12345678901" && n.labels["created_unix"] == "1794787200"])
    error_message = "OS Login and the sweeper's labels on every node"
  }
  assert {
    condition     = google_compute_instance.control.metadata["zj-hosts"] == "10.78.0.10 control\n10.78.0.11 n1\n10.78.0.12 n2\n10.78.0.13 n3\n10.78.0.14 n4\n10.78.0.15 n5"
    error_message = "hosts lines"
  }
  assert {
    condition     = google_compute_firewall.iap_ssh.source_ranges == toset(["35.235.240.0/20"])
    error_message = "SSH from IAP only"
  }
  assert {
    condition     = google_compute_network.net.name == "zj-12345678901-net" && google_compute_instance.node["n3"].name == "zj-12345678901-n3"
    error_message = "names carry the zj-<run_id> prefix the sweeper looks for"
  }
  assert {
    condition     = google_compute_instance.node["n1"].scheduling[0].max_run_duration[0].seconds == 18000
    error_message = "GCE deletes the VMs after max_run_hours"
  }
  assert {
    condition     = google_compute_instance.control.scheduling[0].instance_termination_action == "DELETE" && google_compute_instance.control.scheduling[0].max_run_duration[0].seconds == 18000 && alltrue([for n in values(google_compute_instance.node) : n.scheduling[0].instance_termination_action == "DELETE"])
    error_message = "every VM, control included, is deleted (not stopped) at max_run_duration"
  }
  assert {
    condition     = google_compute_firewall.internal.source_ranges == toset(["10.78.0.0/24"])
    error_message = "internal traffic from the subnet only"
  }
  assert {
    condition     = google_compute_instance.control.metadata["block-project-ssh-keys"] == "TRUE" && alltrue([for n in values(google_compute_instance.node) : n.metadata["block-project-ssh-keys"] == "TRUE" && n.metadata["zj-subnet"] == "10.78.0.0/24"])
    error_message = "project-wide SSH keys blocked on every VM; nodes know the subnet (root SSH only from it)"
  }
}

run "bad_key" {
  command = plan
  variables {
    root_ssh_public_key = ""
  }
  expect_failures = [google_compute_instance.node]
}
