# Runbook: the image signer on the maintainer's Mac

ADR-040. The cosign private key lives only in the maintainer's Keychain. A launchd agent on the Mac signs
the image digests a deploy run built; no GitHub runner can sign.

## What it does

Every 30 seconds `deploy/signer/bms_signer.py` lists the latest `deploy.yml` runs of this repository
(push to `main`). For a run whose three `Build and push api|web|proxy` jobs succeeded on the runner
`bms-deploy-laptop`, it reads each job's own log for the one `bms-image-digest image=... digest=... revision=...`
line and signs `image@digest` with the Keychain key (`cosign sign --tlog-upload=false`). Before signing it
checks that the commit is on `main`, the revision is the run's commit, the image labels name that commit on
both platforms, and the public half of the Keychain key equals `deploy/cosign.pub` on main. The commit must also have come through a merged pull request (a direct push to `main` is refused; set
`BMS_SIGNER_ALLOW_DIRECT=1` in the agent's environment only on purpose). Anything else is
refused and logged once. A signed run attempt is remembered in `~/bms-ops/signer/state.json` (a re-run is a
new attempt and is signed again). The `staging-pointer`
job then sees the signatures (it verifies with `deploy/cosign.pub`) and moves `staging`.

## Install or update

```sh
brew install crane gh               # once
gh auth status                      # a token with write:packages
deploy/signer/install.sh            # downloads the pinned cosign v2.4.1, copies the script, loads the launchd agent
```

Do not use a newer cosign: v3 writes signatures the staging puller (cosign v2.4.1) cannot read, and it would
mark every release failed for good. `install.sh` installs v2.4.1 into `~/bms-ops/signer/bin` with a pinned hash
and the signer refuses to run with any other version. Re-run `install.sh` after the script changes (the agent
runs the copy, not the repository).

## Check it

- `tail -f ~/bms-ops/signer/signer.log`
- `python3 ~/bms-ops/signer/bms_signer.py --once --dry-run` says what it would sign and signs nothing.
- `launchctl print gui/$(id -u)/com.rindzo.bms-signer | head`
- Verify a digest by hand: `cosign verify --key deploy/cosign.pub --insecure-ignore-tlog=true ghcr.io/rincoltech-solutions-ltd/bms-platform-api@sha256:<digest>`

## When the pointer job times out

The pointer job waits 25 minutes for the signatures and then fails, leaving `staging` where it was. Causes,
in order: the Mac is off or asleep, the agent is not loaded, the Keychain is locked, `gh` is logged out, or
the signer refused the run (the log says why, for example "build ran on ... refused"). Fix the cause, then
re-run the failed `staging-pointer` job (or the whole run) from the Actions page; nothing needs rebuilding.

## Rotate the key

1. `~/bms-ops/signer/bin/cosign generate-key-pair` on the Mac (v2.4.1); store `cosign.key` (base64) and the password in the Keychain items
   `bms-cosign-key` and `bms-cosign-password`; delete the local files.
2. Commit the new `cosign.pub` as `deploy/cosign.pub`, and install it as `/opt/bms/cosign.pub` on the staging
   host (docs/runbooks/provision-host.md section 8.4). Do both before the next deploy: the host marks a release
   failed when the signature does not match the key it holds.
3. Re-run `install.sh` is not needed; the signer reads the Keychain at every pass.

## Remove

`launchctl bootout gui/$(id -u)/com.rindzo.bms-signer; rm ~/Library/LaunchAgents/com.rindzo.bms-signer.plist`
