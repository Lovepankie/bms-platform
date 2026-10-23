# ADR-078: Deploys keep the previous builds' hashed web files for 14 days

## Status

Proposed (issue #525, 23 Oct 2026). Builds on ADR-006 (build once, `deploy.sh` switches the named services), ADR-009 (one
PWA, vite-plugin-pwa with `registerType: autoUpdate`), ADR-018 (the staging host, signed digests, rollback) and ADR-040 (build on
the deploy runner, sign on the maintainer's Mac). Changes no signing, verification or pointer rule. Renumbered at merge if needed
(pending ADR-077 is claimed by an open pull request).

## Context

After each staging deploy, phones running the previous app copy failed with "Importing a module script failed". The web image
(`frontend/Dockerfile`) ships only its own build: `/srv/index.html`, `/srv/sw.js`, the hashed `/srv/assets/*` and the hashed
workbox runtime `/srv/workbox-<hash>.js`. A page that is already open, or a service worker that has not yet taken the new build,
still runs the previous `index.html` and its scripts, and loads screens lazily by their hashed names. Those names are gone from the
new container, so `frontend/Caddyfile` answers 404 (it must never answer them with `index.html`, chapter 9 section 9.6) and the
lazy import throws. The worker updates in the background, but until it does, every screen not yet loaded is broken.

The standard answer for an offline-capable single-page app is to keep the hashed files of the last few builds served next to the
new ones: hashed names never clash, so old and new can sit side by side, and `index.html` and the service worker revalidate on
every load so the update is found quickly. They already do: everything outside the hashed class answers `no-cache`.

## Decision

1. **A named volume `web_assets`**, in both `deploy/compose.yml` (production VM) and `deploy/compose.pi-staging.yml`, mounted
   read-only into `web` at `/srv/previous` (`nocopy`), with the same layout as `/srv`: `assets/<name>` and `workbox-<hash>.js`.
   No new long-running container; the web container stays read-only on staging and keeps its memory limit.
2. **`deploy.sh` tops it up before every switch.** `switch_to` first runs `keep_outgoing_assets`: it reads the local image ID
   of the running `web` container (`.Image`, not the reference string it was created with) and runs that image once as the profile
   service `keep-assets` with `--pull never`, so only the image its own deploy verified can run, never a pull of a tag that moved.
   It copies every file under `/srv/assets` (subfolders included) and `/srv/workbox-*.js` into the volume. A name already there is
   not copied again, only its date refreshed; a new file is written under a `.part` name and renamed, so a half-copied file is
   never served, and a failed copy fails the run. The same step runs before a rollback's switch, so the files of a release that went
   live briefly and failed the gate are kept too. `keep-assets` runs with no network, as root only to write the root-owned volume,
   with no capability, `no-new-privileges`, a read-only root and 32 MB (and the `bms.slice` cgroup on staging). The step has a time
   limit, `BMS_KEEP_ASSETS_TIMEOUT` (default 60 seconds) before a deploy's switch and 20 seconds before a rollback's; past it the
   run is killed and its container removed. A failure or a timeout is logged as a warning and never stops a deploy or a rollback:
   the cost is a reload on a phone with the old copy, the behaviour before this decision.
3. **Pruned by age, 14 days.** The same run first deletes every kept file whose date is more than `BMS_KEEP_ASSETS_DAYS` (default
   14, a whole number; any other value fails the run with a warning) times 24 hours old, and only then copies, so the copy never
   raises the peak use of the disk. The date is the last time the file was in a build being replaced, so a file is kept for 14 days after it left the live build,
   however long ago it first appeared. Time, not a count of builds, because staging deploys several times a day and a phone may
   stay closed for a week; builds share most chunks (an unchanged chunk keeps its hash), so the volume grows by the changed files
   of each deploy only. The pruning age is the lever if the SD card runs short.
4. **Caddy falls back to the volume for hashed paths only.** In the `@hashed` handle (`/assets/*`, `/workbox-*.js`) the Caddyfile
   tries `{path}` and then `/previous{path}`; a name in neither still answers 404, uncached. The immutable year of caching stays,
   since a hashed name always means the same bytes. `index.html`, `sw.js`, `registerSW.js` and every other non-hashed path keep
   `Cache-Control: no-cache`. `/previous` itself answers 404, so the volume is reachable only through that fallback. Without the
   volume (local development) the fallback finds nothing and nothing changes.

## Consequences

Better: a phone on the previous app copy keeps loading its screens after a deploy while the new worker installs; no one has to clear
site data; a rollback keeps both builds' files. The signed-image chain is untouched: `keep-assets` runs an image that was verified
when it went live, the new images are verified exactly as before, and the volume holds only files from verified images.

Worse: one more step in each switch (a second or two, at most the time limit) and one more volume to know about. The bound on its
size is time, not bytes; each run prints the volume's size in the deploy log. The volume is not in the backups, and
does not need to be: losing it costs reloads, not data. A phone that stays closed longer than 14 days after its build left, or that
skipped several builds, can still meet a 404 on a lazy import and then needs one reload; the worker update repairs it.

Rejected: keeping old files inside each image (each build copying the previous image's assets) makes images grow without bound and
the build depend on the previous image; a CDN or object store for assets is more infrastructure than the problem needs; copying from
the new image as well would duplicate what `/srv` already serves.

Tested by `deploy/tests/keep-assets-test.sh` in CI: three small web builds through `deploy.sh`'s own `switch_to` against both compose
files, checking that a hash of the first build (a nested one too) answers 200 after the second deploy, `index.html` and `sw.js` answer
`no-cache`, an unknown hash and `/previous/...` answer 404, a file past the keep period is pruned, a switch back keeps the rolled-back
build's files, a `keep-assets` that fails or hangs still lets the switch happen (the hang cut off, no container left) and the volume
is read-only inside `web`. The check on a real host is
in `docs/runbooks/deploy.md`.
