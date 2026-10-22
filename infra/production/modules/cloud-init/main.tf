# The first-boot configuration of the production VM, the same for every provider (ADR-063). It renders
# cloud-init.yaml.tftpl with the unit files the repository ships in deploy/systemd, so the host gets exactly
# the units that were reviewed: the puller (ADR-018), the host checks and the weekly restore drill (ADR-058).
# No secret goes in here: user data is readable from the provider's metadata service and console.

variable "ops_ssh_keys" {
  description = "Public keys of the people who sign in as ops."
  type        = list(string)
}

locals {
  units = "${path.module}/../../../../deploy/systemd"
}

output "user_data" {
  description = "The cloud-config document."
  value = templatefile("${path.module}/cloud-init.yaml.tftpl", {
    ops_ssh_keys    = var.ops_ssh_keys
    pull_service    = file("${local.units}/bms-pull.service")
    pull_timer      = file("${local.units}/bms-pull.timer")
    monitor_service = file("${local.units}/bms-monitor.service")
    monitor_timer   = file("${local.units}/bms-monitor.timer")
    drill_service   = file("${local.units}/bms-restore-drill.service")
    drill_timer     = file("${local.units}/bms-restore-drill.timer")
  })
}
