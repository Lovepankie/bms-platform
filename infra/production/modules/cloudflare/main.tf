# Everything production keeps in the existing Cloudflare account, whatever provider runs the VM (ADR-063):
# the DNS records of the BMS hosts, the R2 backup bucket with the lock and lifecycle rules of ADR-058, the R2
# documents bucket, and the three tokens the host uses. Nothing here is applied until production day.
#
# The backup token is the point of this file. ADR-058 decision 5 and the launch audit (#416, item M12): a host
# token that can delete every backup is the costliest single risk. R2 has no put-only permission, so the
# guarantee is built from three parts that all live here and nowhere on the host:
#   1. lock rules: no object under production/daily/ (30 days) or production/monthly/ (365 days) can be deleted
#      or overwritten by anyone before its time, whatever token they hold;
#   2. lifecycle rules: the bucket, not the host, removes objects a day after their lock ends, so backup.sh runs
#      with BACKUP_RETENTION_ON_HOST=false and deletes nothing;
#   3. the host token: object read and write on this one bucket only (it cannot change the lock or lifecycle
#      rules, touch other buckets, or anything else in the account), limited to the VM's own addresses if Cloudflare
#      enforces the IP condition on R2's S3 requests (runbook step 10 checks; the lock holds either way).
# The host's token cannot change the rules. A Cloudflare account administrator can, and so can the token Terraform runs
# with (it may create tokens, so it is as strong as an administrator): both stay few, on the maintainer's side, behind
# two-step sign-in, never on the host (ADR-058 decision 5).

locals {
  hostnames = concat(["bms", "bms-callbacks"], [for slug in var.tenant_slugs : "${slug}-bms"])

  day = 86400

  # The R2 resource names a token policy uses for one bucket.
  backup_bucket_resource    = "com.cloudflare.edge.r2.bucket.${var.account_id}_default_${cloudflare_r2_bucket.backups.name}"
  documents_bucket_resource = "com.cloudflare.edge.r2.bucket.${var.account_id}_default_${cloudflare_r2_bucket.documents.name}"

  # With restrict_tokens_to_host, each token carries the VM's addresses as an IP condition.
  host_cidrs = compact([
    "${var.host_ipv4}/32",
    var.host_ipv6_network,
  ])
}

data "cloudflare_zone" "this" {
  filter = {
    name    = var.domain
    account = { id = var.account_id }
  }
}

# ---------------------------------------------------------------------------------------------------------------
# DNS. DNS only (not proxied): Caddy terminates TLS itself and obtains the wildcard certificate with the DNS-01
# challenge. Never the apex and never a wildcard of the company zone (docs/runbooks/provision-host.md section 3).

resource "cloudflare_dns_record" "a" {
  for_each = toset(local.hostnames)
  zone_id  = data.cloudflare_zone.this.zone_id
  name     = "${each.value}.${var.domain}"
  type     = "A"
  content  = var.host_ipv4
  proxied  = false
  ttl      = 300
  comment  = "bms production (terraform, infra/production)"
}

resource "cloudflare_dns_record" "aaaa" {
  for_each = var.host_ipv6 == "" ? toset([]) : toset(local.hostnames)
  zone_id  = data.cloudflare_zone.this.zone_id
  name     = "${each.value}.${var.domain}"
  type     = "AAAA"
  content  = var.host_ipv6
  proxied  = false
  ttl      = 300
  comment  = "bms production (terraform, infra/production)"
}

# ---------------------------------------------------------------------------------------------------------------
# R2 buckets. The location hint is where the data is kept: part of the data protection decision (NFR-DP-07), so it
# has no default.

resource "cloudflare_r2_bucket" "backups" {
  account_id = var.account_id
  name       = var.backup_bucket_name
  location   = var.r2_location

  lifecycle {
    prevent_destroy = true
  }
}

resource "cloudflare_r2_bucket" "documents" {
  account_id = var.account_id
  name       = var.documents_bucket_name
  location   = var.r2_location

  lifecycle {
    prevent_destroy = true
  }
}

# ADR-058 decision 5. <environment>/drill/ stays unlocked: the restore drill overwrites its result there.
resource "cloudflare_r2_bucket_lock" "backups" {
  account_id  = var.account_id
  bucket_name = cloudflare_r2_bucket.backups.name

  rules = [
    {
      id        = "daily-30d"
      enabled   = true
      prefix    = "${var.environment}/daily/"
      condition = { type = "Age", max_age_seconds = var.daily_retention_days * local.day }
    },
    {
      id        = "monthly-365d"
      enabled   = true
      prefix    = "${var.environment}/monthly/"
      condition = { type = "Age", max_age_seconds = var.monthly_retention_days * local.day }
    },
  ]

  lifecycle {
    prevent_destroy = true
  }
}

# One day after the lock, so the lifecycle never races it (docs/runbooks/restore-from-backup.md section 7).
resource "cloudflare_r2_bucket_lifecycle" "backups" {
  account_id  = var.account_id
  bucket_name = cloudflare_r2_bucket.backups.name

  rules = [
    {
      id                        = "daily-expire"
      enabled                   = true
      conditions                = { prefix = "${var.environment}/daily/" }
      delete_objects_transition = { condition = { type = "Age", max_age = (var.daily_retention_days + 1) * local.day } }
    },
    {
      id                        = "monthly-expire"
      enabled                   = true
      conditions                = { prefix = "${var.environment}/monthly/" }
      delete_objects_transition = { condition = { type = "Age", max_age = (var.monthly_retention_days + 1) * local.day } }
    },
    {
      id                                 = "abort-stale-uploads"
      enabled                            = true
      conditions                         = { prefix = "" }
      abort_multipart_uploads_transition = { condition = { type = "Age", max_age = 7 * local.day } }
    },
  ]
}

# ---------------------------------------------------------------------------------------------------------------
# The host's tokens. Account-owned (they do not belong to a person and survive one leaving). Each value is in
# Terraform state, which is why the state lives in its own private bucket (runbook, "State").

# The whole list, filtered here by exact name: the API's own name filter needs URL encoding, which the provider may
# or may not add, and an empty result would only show up on production day.
data "cloudflare_account_api_token_permission_groups_list" "all" {
  account_id = var.account_id
}

locals {
  r2_item_write_id = one([for g in data.cloudflare_account_api_token_permission_groups_list.all.result : g.id if g.name == "Workers R2 Storage Bucket Item Write"])
  dns_write_id     = one([for g in data.cloudflare_account_api_token_permission_groups_list.all.result : g.id if g.name == "DNS Write"])
  zone_read_id     = one([for g in data.cloudflare_account_api_token_permission_groups_list.all.result : g.id if g.name == "Zone Read"])
}

# backup.sh, monitor.sh and restore-drill.sh: objects in the backup bucket only. It cannot change the lock or
# lifecycle rules (that needs an admin permission), and the lock refuses its deletes and overwrites under
# daily/ and monthly/.
resource "cloudflare_account_token" "host_backups" {
  account_id = var.account_id
  name       = "${var.name}-host-backups"

  policies = [{
    effect            = "allow"
    permission_groups = [{ id = local.r2_item_write_id }]
    resources         = jsonencode({ (local.backup_bucket_resource) = "*" })
  }]

  condition = var.restrict_tokens_to_host ? { request_ip = { in = local.host_cidrs } } : null
}

# The API's documents (chapter 12 section 12.5): objects in the documents bucket only.
resource "cloudflare_account_token" "host_documents" {
  account_id = var.account_id
  name       = "${var.name}-host-documents"

  policies = [{
    effect            = "allow"
    permission_groups = [{ id = local.r2_item_write_id }]
    resources         = jsonencode({ (local.documents_bucket_resource) = "*" })
  }]

  condition = var.restrict_tokens_to_host ? { request_ip = { in = local.host_cidrs } } : null
}

# Caddy's DNS-01 challenge for the wildcard certificate: DNS records of the whole company zone, plus reading the zone
# itself, which the Cloudflare DNS module of Caddy needs to find the zone. NOT narrow: a host compromise could change MX
# or the mail signing records. Accepted for launch (ADR-063 decision 4); the fix is a separate validation domain with
# Caddy's override_domain (runbook, "Decide first").
resource "cloudflare_account_token" "host_dns" {
  account_id = var.account_id
  name       = "${var.name}-host-dns01"

  policies = [{
    effect            = "allow"
    permission_groups = [{ id = local.dns_write_id }, { id = local.zone_read_id }]
    resources         = jsonencode({ "com.cloudflare.api.account.zone.${data.cloudflare_zone.this.zone_id}" = "*" })
  }]

  condition = var.restrict_tokens_to_host ? { request_ip = { in = local.host_cidrs } } : null
}
