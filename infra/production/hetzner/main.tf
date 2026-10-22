# The production VM on Hetzner Cloud (ADR-063). One of two equivalent roots: ../aws is the other. The owner
# picks one provider and applies only that directory. Nothing is applied until production day.

locals {
  labels = {
    project     = "bms-platform"
    environment = "production"
    managed_by  = "terraform"
  }
}

module "cloud_init" {
  source       = "../modules/cloud-init"
  ops_ssh_keys = var.ssh_public_keys
}

resource "hcloud_ssh_key" "admin" {
  for_each   = { for index, key in var.ssh_public_keys : tostring(index) => key }
  name       = "${var.name}-${each.key}"
  public_key = each.value
  labels     = local.labels
}

# Inbound: SSH from the admin addresses only; 80 and 443 (tcp) and 443 (udp, HTTP/3) from anywhere; nothing
# else. Outbound is open (the puller, Docker, the DNS-01 challenge and the R2 backups all need it).
resource "hcloud_firewall" "prod" {
  name   = var.name
  labels = local.labels

  rule {
    direction   = "in"
    protocol    = "tcp"
    port        = "22"
    source_ips  = var.admin_cidrs
    description = "SSH from the team's addresses only"
  }
  rule {
    direction   = "in"
    protocol    = "tcp"
    port        = "80"
    source_ips  = ["0.0.0.0/0", "::/0"]
    description = "HTTP (redirect to HTTPS)"
  }
  rule {
    direction   = "in"
    protocol    = "tcp"
    port        = "443"
    source_ips  = ["0.0.0.0/0", "::/0"]
    description = "HTTPS"
  }
  rule {
    direction   = "in"
    protocol    = "udp"
    port        = "443"
    source_ips  = ["0.0.0.0/0", "::/0"]
    description = "HTTP/3"
  }
}

resource "hcloud_server" "prod" {
  name         = var.name
  server_type  = var.server_type
  image        = var.image
  location     = var.location
  ssh_keys     = [for key in hcloud_ssh_key.admin : key.id]
  firewall_ids = [hcloud_firewall.prod.id]
  backups      = var.backups
  labels       = local.labels
  user_data    = module.cloud_init.user_data

  # A mistyped destroy or an image rebuild must not be able to delete the customers' database.
  delete_protection  = true
  rebuild_protection = true

  public_net {
    ipv4_enabled = true
    ipv6_enabled = var.enable_ipv6
  }

  lifecycle {
    # cloud-init runs once, at first boot; editing it later must not replace the server.
    ignore_changes = [user_data, ssh_keys, image]
  }
}

module "cloudflare" {
  source = "../modules/cloudflare"

  account_id              = var.cloudflare_account_id
  name                    = var.name
  environment             = "production"
  domain                  = var.domain
  tenant_slugs            = var.tenant_slugs
  host_ipv4               = hcloud_server.prod.ipv4_address
  host_ipv6               = var.enable_ipv6 ? hcloud_server.prod.ipv6_address : ""
  host_ipv6_network       = var.enable_ipv6 ? hcloud_server.prod.ipv6_network : ""
  r2_location             = var.r2_location
  backup_bucket_name      = var.backup_bucket_name
  documents_bucket_name   = var.documents_bucket_name
  daily_retention_days    = var.daily_retention_days
  monthly_retention_days  = var.monthly_retention_days
  restrict_tokens_to_host = var.restrict_tokens_to_host
}
