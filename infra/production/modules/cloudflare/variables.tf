variable "account_id" {
  description = "The existing Cloudflare account that holds the zone and R2. Passed in, never written in a file of this repository."
  type        = string
  sensitive   = true
}

variable "name" {
  description = "Prefix of the token names."
  type        = string
}

variable "environment" {
  description = "BMS_ENVIRONMENT of the host: the prefix backup.sh writes under (<environment>/daily/, <environment>/monthly/)."
  type        = string
}

variable "domain" {
  description = "The DNS zone of the platform and tenant hosts."
  type        = string
}

variable "tenant_slugs" {
  description = "One record <slug>-bms.<domain> per production tenant."
  type        = list(string)
}

variable "host_ipv4" {
  description = "Public IPv4 address of the VM."
  type        = string
}

variable "host_ipv6" {
  description = "Public IPv6 address of the VM, or empty for no AAAA records."
  type        = string
}

variable "host_ipv6_network" {
  description = "The VM's IPv6 network in CIDR form (for the token address condition), or empty."
  type        = string
}

variable "r2_location" {
  description = "R2 location hint for both buckets."
  type        = string
}

variable "backup_bucket_name" {
  description = "Name of the backup bucket."
  type        = string
}

variable "documents_bucket_name" {
  description = "Name of the documents bucket."
  type        = string
}

variable "daily_retention_days" {
  description = "Lock window of daily backups (ADR-058: 30)."
  type        = number
}

variable "monthly_retention_days" {
  description = "Lock window of monthly backups (ADR-058: 365)."
  type        = number
}

variable "restrict_tokens_to_host" {
  description = "Make the host's tokens usable only from the VM's addresses."
  type        = bool
}
