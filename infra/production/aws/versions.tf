terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.28"
    }
  }
}

# Credentials come from the environment, never from a file in this repository
# (docs/runbooks/terraform-production.md, "Secrets"):
#   AWS_PROFILE            a named profile of the Rincol Tech AWS account with rights on EC2 in the region
#   CLOUDFLARE_API_TOKEN   the Terraform token of the runbook (R2 admin, account tokens, DNS on the one zone)
provider "aws" {
  region = var.region

  default_tags {
    tags = {
      project     = "bms-platform"
      environment = "production"
      managed_by  = "terraform"
    }
  }
}

provider "cloudflare" {}
