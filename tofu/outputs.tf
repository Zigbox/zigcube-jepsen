output "control_name" {
  description = "The control VM, for gcloud compute ssh --tunnel-through-iap."
  value       = google_compute_instance.control.name
}

output "zone" {
  value = var.zone
}

output "control_ip" {
  value = local.control_ip
}

output "node_ips" {
  value = local.nodes
}

output "image" {
  description = "The Ubuntu 24.04 image the family resolved to for this run."
  value       = data.google_compute_image.ubuntu.name
}
