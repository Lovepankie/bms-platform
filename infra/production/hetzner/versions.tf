terraform {
  required_version = ">= 1.6"

  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.70"
    }
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.28"
    }
  }
}

# Credentials come from the environment, never from a file in this repository
# (docs/runbooks/terraform-production.md, "Secrets"):
#   HCLOUD_TOKEN           read and write on the Rincol Tech Hetzner Cloud project only
#   CLOUDFLARE_API_TOKEN   the Terraform token of the runbook (R2 admin, account tokens, DNS on the one zone)
provider "hcloud" {}
provider "cloudflare" {}
