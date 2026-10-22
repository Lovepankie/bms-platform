output "hostnames" {
  description = "The DNS names published for the VM."
  value       = [for host in local.hostnames : "${host}.${var.domain}"]
}

output "zone_id" {
  description = "The zone the records are in."
  value       = data.cloudflare_zone.this.zone_id
}

output "r2_endpoint" {
  description = "The S3 endpoint of the account's R2 (R2_ENDPOINT). Sensitive: it carries the account id."
  sensitive   = true
  value       = "https://${var.account_id}.r2.cloudflarestorage.com"
}

output "backup_bucket" {
  description = "R2_BACKUP_BUCKET."
  value       = cloudflare_r2_bucket.backups.name
}

output "documents_bucket" {
  description = "R2_DOCUMENTS_BUCKET."
  value       = cloudflare_r2_bucket.documents.name
}

# R2's S3 credentials of an API token: the access key id is the token id, the secret is the SHA-256 of the
# token value (Cloudflare R2 documentation, "Authentication").
output "host_env" {
  description = "The lines of /opt/bms/.env that Terraform knows. Secret: read it with terraform output -raw host_env on the maintainer's Mac only."
  sensitive   = true
  value       = <<-EOT
    R2_ENDPOINT=https://${var.account_id}.r2.cloudflarestorage.com
    R2_BACKUP_BUCKET=${cloudflare_r2_bucket.backups.name}
    R2_ACCESS_KEY_ID=${cloudflare_account_token.host_backups.id}
    R2_SECRET_ACCESS_KEY=${sha256(cloudflare_account_token.host_backups.value)}
    BACKUP_RETENTION_ON_HOST=false
    R2_DOCUMENTS_BUCKET=${cloudflare_r2_bucket.documents.name}
    R2_DOCUMENTS_ACCESS_KEY_ID=${cloudflare_account_token.host_documents.id}
    R2_DOCUMENTS_SECRET_ACCESS_KEY=${sha256(cloudflare_account_token.host_documents.value)}
    CLOUDFLARE_API_TOKEN=${cloudflare_account_token.host_dns.value}
  EOT
}
