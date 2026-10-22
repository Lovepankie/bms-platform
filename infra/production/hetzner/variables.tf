# Nothing that costs money or decides where data lives has a default: location, server_type, r2_location and
# the Cloudflare account are the owner's decisions (docs/runbooks/terraform-production.md, "Decide first").

variable "location" {
  description = "Hetzner location (fsn1, nbg1, hel1, ash, hil, sin). No default: the processing region is a data protection decision (Uganda PDPO, NFR-DP-07) recorded in an ADR before production data arrives."
  type        = string
}

variable "server_type" {
  description = "Hetzner server type. No default: the documented size is 2 vCPU, 4 GB RAM, 40 GB disk (chapter 9 section 9.4), which Hetzner sells as cx23 in some locations; check availability in the chosen location."
  type        = string
}

variable "cloudflare_account_id" {
  description = "The existing Cloudflare account that holds the zone. Set it as TF_VAR_cloudflare_account_id, never in a committed file. Sensitive, so plan output masks it; still never paste plan output anywhere public."
  type        = string
  sensitive   = true
}

variable "r2_location" {
  description = "R2 location hint for the backup and documents buckets (weur, eeur, apac, enam, wnam, oc). No default: where backups and documents are kept is part of the same data protection decision."
  type        = string
  validation {
    condition     = contains(["weur", "eeur", "apac", "enam", "wnam", "oc"], var.r2_location)
    error_message = "Use one of the R2 location hints: weur, eeur, apac, enam, wnam, oc."
  }
}

variable "ssh_public_keys" {
  description = "Public keys of the people allowed to sign in as ops. sshd refuses root and passwords (cloud-init)."
  type        = list(string)
  validation {
    condition     = length(var.ssh_public_keys) > 0
    error_message = "Give at least one SSH public key."
  }
}

variable "admin_cidrs" {
  description = "Addresses allowed to reach SSH (port 22), for example [\"203.0.113.7/32\"]. Nothing else is opened for SSH."
  type        = list(string)
  validation {
    condition = length(var.admin_cidrs) > 0 && alltrue([
      for c in var.admin_cidrs : can(cidrhost(c, 0)) && (
        strcontains(c, ":") ? tonumber(split("/", c)[1]) >= 48 : tonumber(split("/", c)[1]) >= 16
      )
    ])
    error_message = "Give at least one address in CIDR form, an IPv4 prefix of /16 or longer and an IPv6 prefix of /48 or longer; never the whole internet."
  }
}

variable "name" {
  description = "Name of the server, its firewall, labels and the Cloudflare tokens."
  type        = string
  default     = "bms-prod"
}

variable "image" {
  description = "Operating system image. The 24.04 LTS on purpose: cloud-init installs Docker from Docker's own apt repository, which may not carry packages for a brand-new Ubuntu release yet."
  type        = string
  default     = "ubuntu-24.04"
}

variable "backups" {
  description = "Hetzner's own daily server backups (about a fifth of the server price). No default: they are unencrypted images of the whole disk, the database and /opt/bms/.env included, kept by Hetzner outside the encrypted and locked backups of ADR-058, so they are part of the data protection decision (runbook step 1)."
  type        = bool
}

variable "enable_ipv6" {
  description = "Give the server a public IPv6 address and publish AAAA records."
  type        = bool
  default     = true
}

variable "domain" {
  description = "The DNS zone of the platform and tenant hosts, in the existing Cloudflare account."
  type        = string
  default     = "rincoltech.com"
}

variable "tenant_slugs" {
  description = "One DNS record <slug>-bms.<domain> per production tenant. Add a slug when a tenant goes live."
  type        = list(string)
  default     = []
}

variable "backup_bucket_name" {
  description = "R2 bucket for the encrypted database backups (R2_BACKUP_BUCKET)."
  type        = string
  default     = "bms-production-backups"
}

variable "documents_bucket_name" {
  description = "R2 bucket for the API's documents (R2_DOCUMENTS_BUCKET)."
  type        = string
  default     = "bms-production-documents"
}

variable "daily_retention_days" {
  description = "Lock window of daily backups (ADR-058). prevent_destroy does not stop an in-place change of the lock rules, so a shorter window is refused here."
  type        = number
  default     = 30
  validation {
    condition     = var.daily_retention_days >= 30
    error_message = "Daily backups stay locked for at least 30 days (ADR-058, NFR-BAK-03)."
  }
}

variable "monthly_retention_days" {
  description = "Lock window of monthly backups (ADR-058). prevent_destroy does not stop an in-place change of the lock rules, so a shorter window is refused here."
  type        = number
  default     = 365
  validation {
    condition     = var.monthly_retention_days >= 365
    error_message = "Monthly backups stay locked for at least 365 days (ADR-058, NFR-BAK-03)."
  }
}

variable "restrict_tokens_to_host" {
  description = "Make the host's Cloudflare tokens usable only from the server's addresses."
  type        = bool
  default     = true
}
