# Runbook: the deploy runner (Jerome's laptop)

ADR-040 and its amendment of 2026-10-18. `bms-deploy-laptop` is a GitHub Actions runner in the WSL Ubuntu of Jerome's laptop,
under its own user `bmsdeploy` (uid 1006) beside the other runner users (`ghrunner` to `ghrunner5`, which it never touches),
in the organisation runner group `bms-deploy`: visible to this repository only and restricted to `deploy.yml` at
`refs/heads/main`. It runs the build, pointer and promote jobs of `deploy.yml`. It holds no signing key and no deploy secret;
the Mac signs (docs/runbooks/deploy-signer.md).

It moved here from the staging Pi (`bms-deploy-pi`) on 2026-10-18 because the Pi hard-reset three times during or right after
deploy work (7 resets in 42 hours, with no shutdown logs) and each reset takes the NFE edge down with it. The Pi variant
(`provision.sh`) is retired; `provision-laptop.sh` derives the laptop variant from the same files.

## Install (once, or after any change to deploy/runner/)

Once, on the maintainer's machine, create the runner group (it exists: id 5; re-create it only if it was deleted) and check it:

```sh
O=RincolTech-Solutions-ltd
RID=$(gh api repos/$O/bms-platform --jq .id)
gh api -X POST orgs/$O/actions/runner-groups -f name=bms-deploy -f visibility=selected -F "selected_repository_ids[]=$RID" -F restricted_to_workflows=true -f "selected_workflows[]=$O/bms-platform/.github/workflows/deploy.yml@refs/heads/main"
gh api orgs/$O/actions/runner-groups --jq '.runner_groups[]|select(.name=="bms-deploy")|{visibility,restricted_to_workflows,selected_workflows}'
```

Then the files and the registration token. The laptop's SSH lands in Windows (cmd.exe), so stream the files into WSL on stdin and
send a small script instead of quoting a command line:

```sh
tar czf - -C deploy/runner . | ssh HP-PC@<zerotier ip> 'wsl -d Ubuntu-24.04 -u root -e bash -c "rm -rf /tmp/bmsrun && mkdir -m 700 /tmp/bmsrun && tar xzf - -C /tmp/bmsrun && chmod +x /tmp/bmsrun/*.sh"'
TOK=$(gh api -X POST orgs/RincolTech-Solutions-ltd/actions/runners/registration-token --jq .token)
printf 'cd /tmp/bmsrun\nRUNNER_TOKEN=%s ./provision-laptop.sh\n' "$TOK" | ssh HP-PC@<zerotier ip> 'wsl -d Ubuntu-24.04 -u root -e bash -s'
```

`provision-laptop.sh` is idempotent. It creates the user (no sudo, no docker group), the nftables wall (its DNS rule is taken from the
host's resolver at every boot, as the other users' walls do), a 5 GB and 400% CPU cap, rootless Docker, the root-owned job hooks
and the locked x64 runner install. It touches no other user. The runner shows as `bms-deploy-laptop`, label `bms-deploy`, in the
runner group `bms-deploy`. Installed on 2026-10-18; tested from the runner user: the public internet answers; the WSL gateway, the
LAN, ZeroTier, loopback sshd and all IPv6 do not; rootless Docker 29.9.1 pulls through the wall; the other five runners stayed active.

## Check it

```sh
gh api orgs/RincolTech-Solutions-ltd/actions/runners --jq '.runners[]|select(.name=="bms-deploy-laptop")|"\(.name) \(.status) busy=\(.busy)"'
ssh nfetestpi1@<host> 'systemctl is-active bmsdeploy-actions-runner bmsdeploy-netwall; sudo nft list table inet bmsdeploy_wall | head -3; journalctl -t bms-deploy-hook -n 10 --no-pager'
```

The hook log lines show the wipe at the start and end of every job. Between jobs the user's rootless Docker
holds no image, container or volume: `sudo -u bmsdeploy env XDG_RUNTIME_DIR=/run/user/1002 DOCKER_HOST=unix:///run/user/1002/docker.sock docker system df`.

## Prove the group restriction (after every change to the group)

Push a throwaway branch with a workflow `on: push` and `runs-on: [self-hosted, bms-deploy]` that only echoes.
It must stay queued and never start on `bms-deploy-laptop`; delete the branch afterwards. If it starts, the group
setting failed open: stop the runner and fix the group before any deploy.

## Operate

- Installed on 2026-10-18 (uid 1002, runner 2.338.0, group id 5). Wall tested from the runner user: the public internet
  answers; the router (192.168.1.1), the host's sshd and OpenEMS ports, ZeroTier and all IPv6 do not. Rootless Docker
  26.1.5 pulls images through the wall.
- A deploy build is cold every time (the hooks wipe all caches) and runs one image at a time. Expect it to be
  slower than the hosted build; record the real duration here after the first runs: _not measured yet_.
- One job runs at a time. A queued deploy waits for the running one.
- Disk: the wipe keeps the rootless store small; if `/home/bmsdeploy` grows, run `docker system prune -af
  --volumes` as that user and look at why a hook did not run (`journalctl -t bms-deploy-hook`).
- Runner version: auto-update is off. To update, set `RUNNER_VERSION` and re-run `provision.sh` after
  `deprovision.sh` (the registration is kept only if you do not remove it).
- If the wall blocks a registry or mirror a build needs, add it to the allowed side by editing
  `bmsdeploy-wall.nft` in this repository, not on the host, and re-run `provision.sh`.

## Do not re-run provision.sh during a deploy

Re-running it restarts the wall (`Requires=` also restarts the user manager and the runner) when a file changed. Check
`gh api orgs/RincolTech-Solutions-ltd/actions/runners --jq '.runners[]|select(.name=="bms-deploy-laptop").busy'` is false first.

## Remove

```sh
gh api -X POST orgs/RincolTech-Solutions-ltd/actions/runners/remove-token --jq .token
sudo REMOVE_TOKEN=<the token> /tmp/bms-deploy-runner/deprovision.sh
```

Reverting the workflow change brings the hosted jobs back, which work only while a budget exists.
