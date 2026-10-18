#!/usr/bin/env python3
"""Signs the images a deploy run built, with the cosign key that lives only in the maintainer's Keychain.

ADR-040. This runs on the maintainer's Mac under launchd. It is NOT a GitHub runner and no runner ever
holds the key. Every pass it asks GitHub for the latest deploy runs and signs the three image digests of a
run only when ALL of these hold:

  * the run is this repository's `.github/workflows/deploy.yml`, event `push`, branch `main` (the runner
    group that serves `bms-deploy-pi` also admits nothing else);
  * its head commit is on main (an ancestor of, or equal to, main);
  * the three `Build and push <image>` jobs finished with success, ran on the dedicated runner
    (`bms-deploy-pi`) and at that commit;
  * each job's own LOG holds exactly one `bms-image-digest image=... digest=... revision=...` line, and the
    revision is the run's head commit. A log cannot be edited after the fact and no other run can write to it;
    annotations are not used because a check run can be updated through the API;
  * the image behind that digest carries the label `org.opencontainers.image.revision` equal to the commit,
    on both the arm64 and the amd64 manifest;
  * the commit came through a merged pull request (GitHub cannot forbid a direct push to main on the free plan);
  * the public half of the Keychain key equals `deploy/cosign.pub` on main (a wrong key would make the
    staging host mark every release failed for good).

It signs `image@digest`, never a tag. The tools are pinned: cosign v2.4.1 is what the staging puller and the
pointer job verify with, and a newer cosign writes a signature format they cannot read.

Secrets are read at signing time and passed to cosign through the environment only: the key and password
come from the Keychain (items bms-cosign-key, base64 of the PEM, and bms-cosign-password); the registry
login is the `gh` token, written to a private temporary Docker config that is removed afterwards. Nothing
secret is printed or logged.

Usage:
  bms_signer.py --once            one pass, then exit
  bms_signer.py --loop 30         pass every 30 seconds (launchd KeepAlive)
  bms_signer.py --once --dry-run  say what would be signed, sign nothing
"""
from __future__ import annotations

import argparse
import base64
import calendar
import fcntl
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

REPO = "RincolTech-Solutions-ltd/bms-platform"
WORKFLOW_PATH = ".github/workflows/deploy.yml"
RUNNER_NAME = "bms-deploy-pi"
IMAGE_PREFIX = "ghcr.io/rincoltech-solutions-ltd/bms-platform"
IMAGES = ("api", "web", "proxy")
MAX_RUN_AGE_SECONDS = 48 * 3600
STATE_DIR = Path(os.environ.get("BMS_SIGNER_STATE", str(Path.home() / "bms-ops" / "signer")))
COSIGN = os.environ.get("BMS_COSIGN", str(STATE_DIR / "bin" / "cosign"))
COSIGN_VERSION = "v2.4.1"
KEY_ITEM = "bms-cosign-key"
PASSWORD_ITEM = "bms-cosign-password"
ALLOW_DIRECT = os.environ.get("BMS_SIGNER_ALLOW_DIRECT") == "1"   # sign a commit that did not come through a merged PR
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
SHA_RE = re.compile(r"^[0-9a-f]{40}$")
LOG_LINE_RE = re.compile(r"bms-image-digest image=(\S+) digest=(sha256:[0-9a-f]{64}) revision=([0-9a-f]{40})")


def log(message: str) -> None:
    print(time.strftime("%Y-%m-%dT%H:%M:%S%z"), message, flush=True)


def run(cmd: list[str], env: dict[str, str] | None = None, timeout: int = 120) -> tuple[int, str, str]:
    merged = dict(os.environ)
    if env:
        merged.update(env)
    try:
        proc = subprocess.run(cmd, env=merged, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return 124, "", "timeout"
    return proc.returncode, proc.stdout, proc.stderr


def gh_api(path: str) -> object | None:
    code, out, err = run(["gh", "api", path])
    if code != 0:
        log(f"gh api {path.split('?')[0]} failed: {err.strip()[:160]}")
        return None
    try:
        return json.loads(out)
    except json.JSONDecodeError:
        return None


def keychain(item: str) -> str | None:
    code, out, _ = run(["security", "find-generic-password", "-s", item, "-w"])
    return out.strip() if code == 0 and out.strip() else None


def load_key() -> tuple[str, str] | None:
    raw, password = keychain(KEY_ITEM), keychain(PASSWORD_ITEM)
    if not raw or not password:
        log("Keychain items for the cosign key are missing or locked")
        return None
    try:
        pem = base64.b64decode(raw).decode()
    except Exception:
        log("the Keychain key is not base64 of a PEM")
        return None
    if "BEGIN ENCRYPTED SIGSTORE PRIVATE KEY" not in pem:
        log("the Keychain key is not a cosign private key")
        return None
    return pem, password


def cosign_ok() -> bool:
    code, out, _ = run([COSIGN, "version"])
    if code != 0 or COSIGN_VERSION not in out:
        log(f"cosign {COSIGN_VERSION} is required at {COSIGN} (deploy/signer/install.sh installs it); refusing to sign")
        return False
    return True


def main_sha() -> str | None:
    data = gh_api(f"repos/{REPO}/git/ref/heads/main")
    sha = (data or {}).get("object", {}).get("sha") if isinstance(data, dict) else None
    return sha if isinstance(sha, str) and SHA_RE.match(sha) else None


def is_on_main(sha: str, head: str) -> bool:
    data = gh_api(f"repos/{REPO}/compare/{sha}...{head}")
    return isinstance(data, dict) and data.get("status") in ("ahead", "identical")


def from_merged_pr(sha: str) -> bool:
    """The commit reached main through a merged pull request, not a direct push (the repository is on the free
    plan, so GitHub cannot forbid a direct push to main; the signer refuses to sign one)."""
    data = gh_api(f"repos/{REPO}/commits/{sha}/pulls")
    return isinstance(data, list) and any(p.get("merged_at") and (p.get("base") or {}).get("ref") == "main" for p in data)


def main_public_key(head: str) -> str | None:
    data = gh_api(f"repos/{REPO}/contents/deploy/cosign.pub?ref={head}")
    if not isinstance(data, dict) or "content" not in data:
        return None
    try:
        return base64.b64decode(data["content"]).decode()
    except Exception:
        return None


def squash(text: str) -> str:
    return re.sub(r"\s+", "", text)


def digest_from_log(job_id: int, image: str, sha: str) -> str | None:
    code, out, err = run(["gh", "api", f"repos/{REPO}/actions/jobs/{job_id}/logs"], timeout=180)
    if code != 0:
        log(f"job {job_id}: could not read the log: {err.strip()[:120]}")
        return None
    found = LOG_LINE_RE.findall(out)
    if len(found) != 1:
        return None
    found_image, digest, revision = found[0]
    if found_image != image or revision != sha:
        return None
    return digest


def build_digests(run_info: dict, refuse) -> dict[str, str] | None:
    """The three digests the build jobs of this run recorded, or None while anything is missing or wrong."""
    sha = run_info["head_sha"]
    jobs = gh_api(f"repos/{REPO}/actions/runs/{run_info['id']}/jobs?per_page=100&filter=latest")
    if not isinstance(jobs, dict):
        return None
    by_name = {job["name"]: job for job in jobs.get("jobs", [])}
    digests: dict[str, str] = {}
    for image in IMAGES:
        job = by_name.get(f"Build and push {image}")
        if not job or job.get("status") != "completed" or job.get("conclusion") != "success":
            return None
        if job.get("runner_name") != RUNNER_NAME or job.get("head_sha") != sha:
            refuse(f"{image} build ran on {job.get('runner_name')!r} at {str(job.get('head_sha'))[:7]}")
            return None
        digest = digest_from_log(job["id"], image, sha)
        if digest is None:
            refuse(f"the log of the {image} build does not hold exactly one matching digest line")
            return None
        digests[image] = digest
    return digests


def label_revision(ref: str, platform: str, docker_config: str) -> str | None:
    code, out, _ = run(["crane", "config", "--platform", platform, ref], env={"DOCKER_CONFIG": docker_config})
    if code != 0:
        return None
    try:
        return (json.loads(out).get("config") or {}).get("Labels", {}).get("org.opencontainers.image.revision")
    except json.JSONDecodeError:
        return None


def signed_already(ref: str, pub_file: str, docker_config: str) -> bool:
    code, _, _ = run(
        [COSIGN, "verify", "--key", pub_file, "--insecure-ignore-tlog=true", ref],
        env={"DOCKER_CONFIG": docker_config},
    )
    return code == 0


def process(run_info: dict, key: tuple[str, str], state: dict, dry: bool, head: str) -> None:
    run_id, sha = run_info["id"], run_info["head_sha"]
    marker = f"{run_id}:{run_info.get('run_attempt', 1)}"
    if marker in state["done"]:
        return

    def refuse(reason: str) -> None:
        if state["refused"].get(marker) != reason:
            log(f"run {marker}: {reason}: refused")
            state["refused"][marker] = reason

    if run_info.get("path") != WORKFLOW_PATH or run_info.get("event") != "push" or run_info.get("head_branch") != "main":
        return
    if (run_info.get("repository") or {}).get("full_name") != REPO or not SHA_RE.match(sha):
        return
    digests = build_digests(run_info, refuse)
    if digests is None:
        return
    if not is_on_main(sha, head):
        refuse(f"commit {sha[:7]} is not on main")
        return
    if not ALLOW_DIRECT and not from_merged_pr(sha):
        refuse(f"commit {sha[:7]} did not come through a merged pull request (BMS_SIGNER_ALLOW_DIRECT=1 overrides)")
        return
    pem, password = key
    user_code, user_out, _ = run(["gh", "api", "user", "--jq", ".login"])
    tok_code, token, _ = run(["gh", "auth", "token"])
    if user_code != 0 or tok_code != 0:
        log("no gh login for the registry")
        return
    workdir = tempfile.mkdtemp(prefix="bms-signer-")
    os.chmod(workdir, 0o700)
    try:
        docker_config = os.path.join(workdir, "docker")
        os.makedirs(docker_config, mode=0o700)
        auth = base64.b64encode(f"{user_out.strip()}:{token.strip()}".encode()).decode()
        Path(docker_config, "config.json").write_text(json.dumps({"auths": {"ghcr.io": {"auth": auth}}}))
        os.chmod(Path(docker_config, "config.json"), 0o600)
        env = {"COSIGN_KEY": pem, "COSIGN_PASSWORD": password, "DOCKER_CONFIG": docker_config}
        code, pub, _ = run([COSIGN, "public-key", "--key", "env://COSIGN_KEY"], env=env)
        if code != 0:
            log("cosign could not read the key")
            return
        published = main_public_key(head)
        if published is None or squash(pub) != squash(published):
            refuse("the Keychain key does not match deploy/cosign.pub on main (rotation not finished?)")
            return
        pub_file = os.path.join(workdir, "cosign.pub")
        Path(pub_file).write_text(pub)
        refs = {image: f"{IMAGE_PREFIX}-{image}@{digest}" for image, digest in digests.items()}
        for image, ref in refs.items():
            for platform in ("linux/arm64", "linux/amd64"):
                if label_revision(ref, platform, docker_config) != sha:
                    refuse(f"{image} {platform} image labels do not name {sha[:7]}")
                    return
        for image, ref in refs.items():
            if signed_already(ref, pub_file, docker_config):
                continue
            if dry:
                log(f"DRY RUN: would sign {ref}")
                continue
            code, _, err = run([COSIGN, "sign", "--key", "env://COSIGN_KEY", "--tlog-upload=false", "--yes", ref], env=env, timeout=300)
            if code != 0:
                log(f"run {marker}: signing {image} failed: {err.strip()[-200:]}")
                return
            log(f"run {marker}: signed {image} {digests[image][:19]} for {sha[:7]}")
        if not dry:
            state["done"][marker] = {"sha": sha, "at": int(time.time()), "digests": digests}
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


def one_pass(dry: bool) -> None:
    state_file = STATE_DIR / "state.json"
    state: dict = {"done": {}, "refused": {}}
    if state_file.exists():
        try:
            state.update(json.loads(state_file.read_text()))
        except json.JSONDecodeError:
            pass
    listing = gh_api(f"repos/{REPO}/actions/workflows/deploy.yml/runs?event=push&branch=main&per_page=10")
    if not isinstance(listing, dict):
        return
    if not cosign_ok():
        return
    key = load_key()
    if key is None:
        return
    head = main_sha()
    if head is None:
        return
    now = time.time()
    for run_info in listing.get("workflow_runs", []):
        if run_info.get("conclusion") in ("failure", "cancelled", "skipped"):
            continue
        created = run_info.get("created_at", "")
        try:
            age = now - calendar.timegm(time.strptime(created, "%Y-%m-%dT%H:%M:%SZ"))
        except ValueError:
            continue
        if age > MAX_RUN_AGE_SECONDS:
            continue
        try:
            process(run_info, key, state, dry, head)
        except Exception as exc:  # one bad run must not stop the others
            log(f"run {run_info.get('id')}: {type(exc).__name__}: {str(exc)[:160]}")
    if not dry:
        state["done"] = dict(list(state["done"].items())[-50:])
        state["refused"] = dict(list(state["refused"].items())[-50:])
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        tmp = state_file.with_suffix(".tmp")
        tmp.write_text(json.dumps(state))
        tmp.replace(state_file)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--loop", type=int, metavar="SECONDS")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if not args.once and not args.loop:
        parser.error("give --once or --loop SECONDS")
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    lock = open(STATE_DIR / "signer.lock", "w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        log("another signer is running")
        return 1
    while True:
        try:
            one_pass(args.dry_run)
        except Exception as exc:  # a bad pass must not stop the loop
            log(f"pass failed: {type(exc).__name__}: {str(exc)[:160]}")
        if args.once:
            return 0
        time.sleep(args.loop)


if __name__ == "__main__":
    sys.exit(main())
