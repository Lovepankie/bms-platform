# Nothing that costs money or decides where data lives has a default: region, instance_type, r2_location and
# the Cloudflare account are the owner's decisions (docs/runbooks/terraform-production.md, "Decide first").

variable "region" {
  description = "AWS region (for example af-south-1, eu-west-1, us-east-1). No default: the processing region is a data protection decision (Uganda PDPO, NFR-DP-07) recorded in an ADR before production data arrives. A region that is not enabled by default (af-south-1) must be opted in first."
  type        = string
}

variable "instance_type" {
  description = "EC2 instance type. No default: the documented size is 2 vCPU and 4 GB RAM (chapter 9 section 9.4), for example t3.medium or t4g.medium (arm64 images are built too)."
  type        = string
}

variable "architecture" {
  description = "CPU architecture of the instance type: x86_64 or arm64. The BMS images are built for both."
  type        = string
  default     = "x86_64"
  validation {
    condition     = contains(["x86_64", "arm64"], var.architecture)
    error_message = "Use x86_64 or arm64."
  }
}

variable "disk_gb" {
  description = "Root disk size in GB (gp3, encrypted)."
  type        = number
  default     = 40
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
  description = "IPv4 addresses allowed to reach SSH (port 22), for example [\"203.0.113.7/32\"]. Nothing else is opened for SSH. IPv6 is refused (the default VPC has none)."
  type        = list(string)
  validation {
    condition = length(var.admin_cidrs) > 0 && alltrue([
      for c in var.admin_cidrs : can(cidrhost(c, 0)) && (
        !strcontains(c, ":") && tonumber(split("/", c)[1]) >= 16
      )
    ])
    error_message = "Give at least one IPv4 address in CIDR form with a prefix of /16 or longer; never the whole internet. IPv6 entries are refused on AWS: the default VPC has no IPv6, so they would silently do nothing."
  }
}

variable "name" {
  description = "Name of the server, its firewall, labels and the Cloudflare tokens."
  type        = string
  default     = "bms-prod"
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
