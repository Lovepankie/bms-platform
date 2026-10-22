output "ipv4" {
  description = "Public IPv4 address of the server (ssh ops@<ipv4>)."
  value       = hcloud_server.prod.ipv4_address
}

output "ipv6" {
  description = "Public IPv6 address of the server (empty when IPv6 is off)."
  value       = var.enable_ipv6 ? hcloud_server.prod.ipv6_address : ""
}

output "hostnames" {
  description = "The DNS names this configuration publishes."
  value       = module.cloudflare.hostnames
}

output "backup_bucket" {
  description = "R2_BACKUP_BUCKET."
  value       = module.cloudflare.backup_bucket
}

output "documents_bucket" {
  description = "R2_DOCUMENTS_BUCKET."
  value       = module.cloudflare.documents_bucket
}

output "host_env" {
  description = "The /opt/bms/.env lines Terraform knows (R2, the documents bucket, the DNS-01 token). Secret: terraform output -raw host_env, on the maintainer's Mac only."
  sensitive   = true
  value       = module.cloudflare.host_env
}

output "next_steps" {
  description = "What is still done by hand after the server exists."
  value       = "Follow docs/runbooks/terraform-production.md from step 7: .env, cosign.pub, the read:packages login, the first files and timers, the first backup, the lock test and the first restore drill."
}
