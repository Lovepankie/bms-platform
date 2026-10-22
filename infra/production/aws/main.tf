# The production VM on AWS EC2 (ADR-063). One of two equivalent roots: ../hetzner is the other. The owner picks
# one provider and applies only that directory. Nothing is applied until production day.
#
# Same host as on Hetzner: one VM in the default VPC, a security group with SSH from the admin addresses only and
# 80, 443 and 443/udp from anywhere, a fixed public address, the shared cloud-init and the shared Cloudflare
# module. IPv4 only (the default VPC has no IPv6), so no AAAA records.

# Ubuntu 24.04 LTS, the current image Canonical publishes through the public SSM parameter it documents.
data "aws_ssm_parameter" "ubuntu" {
  name = "/aws/service/canonical/ubuntu/server/24.04/stable/current/${var.architecture == "arm64" ? "arm64" : "amd64"}/hvm/ebs-gp3/ami-id"
}

data "aws_vpc" "default" {
  default = true
}

module "cloud_init" {
  source       = "../modules/cloud-init"
  ops_ssh_keys = var.ssh_public_keys
}

locals {
  admin_ipv4 = [for c in var.admin_cidrs : c if !strcontains(c, ":")]
}

resource "aws_security_group" "prod" {
  name        = var.name
  description = "BMS production: SSH from the team only, web from anywhere"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "SSH from the team's addresses only"
    protocol    = "tcp"
    from_port   = 22
    to_port     = 22
    cidr_blocks = local.admin_ipv4
  }
  ingress {
    description = "HTTP (redirect to HTTPS)"
    protocol    = "tcp"
    from_port   = 80
    to_port     = 80
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    description = "HTTPS"
    protocol    = "tcp"
    from_port   = 443
    to_port     = 443
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    description = "HTTP/3"
    protocol    = "udp"
    from_port   = 443
    to_port     = 443
    cidr_blocks = ["0.0.0.0/0"]
  }

  # Outbound is open: the puller, Docker, the DNS-01 challenge and the R2 backups all need it.
  egress {
    protocol    = "-1"
    from_port   = 0
    to_port     = 0
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "prod" {
  ami                    = nonsensitive(data.aws_ssm_parameter.ubuntu.value)
  instance_type          = var.instance_type
  vpc_security_group_ids = [aws_security_group.prod.id]
  user_data              = module.cloud_init.user_data

  # No EC2 key pair: cloud-init installs the team's keys for ops only, and sshd refuses everyone else.

  # A mistyped destroy must not be able to delete the customers' database.
  disable_api_termination = true

  # IMDSv2 only, one hop: a container on the host cannot reach the metadata service.
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  root_block_device {
    volume_size           = var.disk_gb
    volume_type           = "gp3"
    encrypted             = true
    delete_on_termination = false
  }

  tags = {
    Name = var.name
  }

  lifecycle {
    # cloud-init runs once, at first boot; a newer AMI or edited user data must not replace the server.
    ignore_changes = [ami, user_data]
  }
}

resource "aws_eip" "prod" {
  domain   = "vpc"
  instance = aws_instance.prod.id

  tags = {
    Name = var.name
  }
}

module "cloudflare" {
  source = "../modules/cloudflare"

  account_id              = var.cloudflare_account_id
  name                    = var.name
  environment             = "production"
  domain                  = var.domain
  tenant_slugs            = var.tenant_slugs
  host_ipv4               = aws_eip.prod.public_ip
  host_ipv6               = ""
  host_ipv6_network       = ""
  r2_location             = var.r2_location
  backup_bucket_name      = var.backup_bucket_name
  documents_bucket_name   = var.documents_bucket_name
  daily_retention_days    = var.daily_retention_days
  monthly_retention_days  = var.monthly_retention_days
  restrict_tokens_to_host = var.restrict_tokens_to_host
}
