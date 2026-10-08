#!/usr/bin/env bash
#
# sync-core.sh - keep the vendored Aether engine (core) in sync with the
# official upstream repository, automatically, on every CI build.
#
# WHY THIS EXISTS (1.2.2)
# -----------------------
# Until 1.2.1 the vendored core under native/aether was bumped by hand. That
# meant the app could silently ship an engine months behind upstream, and
# nobody noticed until a protocol change broke connectivity in the field.
# From 1.2.2 the BUILD owns the core version:
#
#   1. Query the official core repo (CluvexStudio/Aether) for its latest
#      release/tag.
#   2. Compare it with native/aether/CORE_VERSION (currently vendored).
#   3. If upstream is NEWER, fetch that exact tag and MERGE this app's own
#      engine patches onto the new sources (see PATCHED_FILES).
#   4. Record the upgrade in README.md / README.fa.md at the core-sync
#      anchors, so documentation can never drift from what was built.
#
# HOW THE APP PATCHES SURVIVE AN UPGRADE (root fix, 1.2.2)
# --------------------------------------------------------
# The first implementation simply copied this repo's whole prober.rs /
# wg_prober.rs over the new upstream files. That is wrong and it broke the
# build the moment upstream touched those files: our stale copies were written
# against the OLD engine API, so core 1.4.0 failed to compile with
#
#   error[E0063]: missing fields `expected_pins` and `pin_endpoint`
#                 in initializer of `H2TunnelConfig`   (prober.rs)
#   error[E0061]: this function takes 8 arguments but 7 were supplied
#                 (wireguard::verify_endpoint_keep_session, wg_prober.rs)
#
# A whole-file copy can never be correct, because it silently reverts every
# upstream change inside that file. So we now treat our changes as what they
# actually are: a PATCH on top of a known upstream baseline.
#
#   ours   = native/aether/<file>                     (upstream_old + our patch)
#   base   = pristine upstream <file> at CORE_VERSION (the baseline)
#   theirs = pristine upstream <file> at the new tag
#
# and we run a real three-way merge (git merge-file). Upstream API changes and
# our additive changes then combine correctly, exactly like a rebase would.
#
# The baseline is cached in native/aether/.upstream-baseline/ so later runs
# need no extra clone. On the very first upgrade the baseline is reconstructed
# by also cloning the currently vendored tag.
#
# If the merge conflicts we deliberately keep the PURE UPSTREAM file (which is
# guaranteed to compile) instead of forcing our stale copy, and we shout about
# it in the log and in the changelog. A degraded feature is recoverable; a red
# build on every future run is not. Since core 2.3.0 the conflicted merge is
# also kept, markers and all, under native/aether/.core-conflicts/, so the
# person resolving it starts from the merge rather than from scratch.
#
# Finally, the previous core is snapshotted to native/.core-prev so the CI
# "Build engine" step can roll back and retry if the new core does not build
# for any reason at all. An automatic engine upgrade must never be able to
# break a release.
#
# NOTHING OUTSIDE PATCHED_FILES IS LOST SILENTLY (drift guard, core 2.1.0)
# -------------------------------------------------------------------------
# Only PATCHED_FILES are merged back; the rest of aether/ is replaced
# wholesale. That is right for upstream's own code and silently destructive
# for anything else: the 2.0.0 sync (af58736, 2026-09-14) deleted the app's
# engine work that lived outside the list - the prober/, masque_h2/,
# wireguard/ and masque/ modules among it - and nothing in the log said so.
# Before it touches the tree, the script now compares the vendored core with
# pristine upstream at the vendored version and stops, naming every file it
# would lose, unless CORE_SYNC_ALLOW_DRIFT=1 says to discard them on purpose.
#
# THE LISTS CAUGHT UP WITH THE ENGINE WORK (core 2.3.0 review, 2026-10-05)
# ------------------------------------------------------------------------
# Between the 2.1.0 sync (2026-09-24) and the 2.3.0 release the app carried
# engine work in eleven files that were NOT in PATCHED_FILES (see
# MODIFICATIONS.md, "Engine patches"), plus a module upstream does not ship
# (wg_experiments.rs). The drift guard would have refused the 2.3.0 upgrade,
# correctly, and CORE_SYNC_ALLOW_DRIFT=1 would have deleted all of it. Those
# files are listed now, the new module is APP_OWNED_FILES, and the quiche
# workspace manifest, which pins the boring version quiche builds with and
# which nothing used to move, is UPSTREAM_EXTRA_FILES (core 2.3.0 moved boring
# from 4.22 to 5.2 on both sides; two boring-sys copies cannot link together).
#
# The script is deliberately conservative: any failure to reach GitHub leaves
# the vendored core untouched and exits 0, so a network hiccup can never break
# a release build. It only ever moves FORWARD (never downgrades). The one
# deliberate failure is the drift guard: it exits 1, because a red run gets
# looked at and silently deleted work does not.
#
# Usage:
#   scripts/sync-core.sh                   # sync to latest upstream release
#   CORE_TARGET=1.4 scripts/sync-core.sh   # pin a specific version
#   CORE_SYNC=off scripts/sync-core.sh     # disable (use vendored core)
#   CORE_SYNC_ALLOW_DRIFT=1 scripts/sync-core.sh
#                                          # upgrade even though it discards
#                                          # local changes outside PATCHED_FILES
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

CORE_REPO="${AETHER_REPO:-CluvexStudio/Aether}"
CORE_DIR="native/aether"
VERSION_FILE="$CORE_DIR/CORE_VERSION"
BASELINE_DIR="$CORE_DIR/.upstream-baseline"
CONFLICT_DIR="$CORE_DIR/.core-conflicts"
PREV_DIR="native/.core-prev"
STATE_FILE="native/.core-sync-state"
README_EN="README.md"
README_FA="README.fa.md"

# Assembled from parts on purpose so the endpoints stay easy to override.
# CORE_API_BASE / CORE_GIT_BASE exist so the whole upgrade path can be
# exercised offline against a local repository (see scripts/test-core-sync.sh).
GH_HOST="github.com"
SCHEME="https"
GH_API="${CORE_API_BASE:-${SCHEME}://api.${GH_HOST}}"
GH_WEB="${CORE_GIT_BASE:-${SCHEME}://${GH_HOST}}"

# The baseline this app was engineered against; also the floor we never go below.
# 1.2.3: raised to 1.5.0 (the release that fixed the mislabelled 1.4/1.3.0 vendor
# and rebased the app's engine patches onto the real upstream baseline).
BASELINE="1.5.0"

# App-specific patches carried on top of the upstream engine. These are MERGED
# (three-way) onto the new upstream sources, never blind-copied over them.
#
#   Cargo.toml    -> arc-swap (wg_experiments), smoltcp socket-tcp-cubic/-reno
#   account.rs    -> keep the registration before enrolling, cut-on-the-wire
#                    detection, no short connect timeout on a proxied path,
#                    ECH-first api calls with --ech, refusals kept final
#   api.rs        -> follows account.rs / apifront.rs
#   apifront.rs   -> ECH route first (its key from dns.rs), Android trust
#                    store, early stop on a real API answer. Upstream 2.3.0
#                    DELETES this file (its https.rs and --enroll-address
#                    replace the camouflaged route), so the sync drops it and
#                    keeps a copy under .core-conflicts/.
#   cli.rs        -> --precise / --ultra accepted as aliases of --balanced /
#                    --ironclad (the 1.4.6 scan-mode names), plus parser tests.
#   dns.rs        -> the core's one ECH key: AETHER_ECH_DOMAIN / AETHER_ECH_DNS
#                    (upstream 2.3.0's own variables), cached once per process
#                    for the api and the tunnel. Upstream 2.3.0 rewrites dns.rs
#                    around the same variables, so a conflict here resolves to
#                    upstream's file.
#   masque_h2.rs  -> backpressure instead of drops, SpoofingStream, custom SNI
#   netstack.rs   -> TCP congestion control, no tx drops, event-driven waits
#   prober.rs     -> quiet window from the first gateway, DoH ranges last,
#                    failure tally (and, historically, AETHER_SCAN_CIDRS)
#   quic.rs       -> sendmmsg batching, lossless inbound, custom SNI
#   socks.rs      -> DNS cache, parallel resolvers, non-blocking UDP associate
#   sysprofile.rs -> netstack TCP windows sized for the link
#   tls.rs        -> CUBIC unless AETHER_QUIC_CC asks otherwise, ECH helpers
#   wg_prober.rs  -> custom_wg_cidrs_v4() (the 1.2.2 location picker)
#   wireguard.rs  -> drain boringtun, no lock across await, socket buffers,
#                    wg_experiments wiring
#
# cli.rs WAS MISSING FROM THIS LIST through core 2.0.0, and everything from
# Cargo.toml down to wireguard.rs above except prober.rs and wg_prober.rs was
# missing through core 2.1.0. A change under aether/ that is NOT listed here
# (or in APP_OWNED_FILES) is not merged, it is deleted by the next upgrade. The
# drift guard below refuses to upgrade until such a change is either listed or
# explicitly given up (CORE_SYNC_ALLOW_DRIFT=1).
PATCHED_FILES=(
  "aether/Cargo.toml"
  "aether/src/account.rs"
  "aether/src/api.rs"
  "aether/src/apifront.rs"
  "aether/src/cli.rs"
  "aether/src/dns.rs"
  "aether/src/masque_h2.rs"
  "aether/src/netstack.rs"
  "aether/src/prober.rs"
  "aether/src/quic.rs"
  "aether/src/socks.rs"
  "aether/src/sysprofile.rs"
  "aether/src/tls.rs"
  "aether/src/wg_prober.rs"
  "aether/src/wireguard.rs"
)

# Files under aether/ that exist only in this repository. There is nothing
# upstream to merge them onto, so they are carried across an upgrade verbatim.
# If upstream ever ships a file of the same name, ours is kept and the run is
# flagged for review.
APP_OWNED_FILES=(
  "aether/src/wg_experiments.rs"
)

# Upstream files OUTSIDE aether/ that the engine build depends on and that
# must move with the core. The vendored quiche sources are identical to
# upstream's; only the workspace manifest, which pins boring, changes between
# releases. Replaced from the new tag when upstream ships them.
UPSTREAM_EXTRA_FILES=(
  "quiche/Cargo.toml"
)

log() { printf '[core-sync] %s\n' "$*"; }
warn() { printf '[core-sync] %s\n' "$*" >&2; }

# GitHub Actions annotations, so problems are visible in the run summary and
# not just buried in the log.
notice_gh() { [[ -n "${GITHUB_ACTIONS:-}" ]] && printf '::warning::%s\n' "$*" || true; }
error_gh() { [[ -n "${GITHUB_ACTIONS:-}" ]] && printf '::error title=Core sync::%s\n' "$*" || true; }

rm -f "$STATE_FILE"

if [[ "${CORE_SYNC:-on}" == "off" ]]; then
  log "CORE_SYNC=off - keeping the vendored core untouched."
  exit 0
fi

# ---------------------------------------------------------------- current
current="$BASELINE"
if [[ -f "$VERSION_FILE" ]]; then
  current="$(tr -d '[:space:]' < "$VERSION_FILE")"
fi
log "Vendored core version: ${current}"

# ---------------------------------------------------------------- upstream
api() {
  local url="$1"
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    curl -fsSL -H "Authorization: Bearer ${GITHUB_TOKEN}" \
      -H "Accept: application/vnd.github+json" "$url" 2>/dev/null || true
  else
    curl -fsSL -H "Accept: application/vnd.github+json" "$url" 2>/dev/null || true
  fi
}

# Minimal JSON scrape: avoids a jq dependency on the runner.
latest_raw="$(api "${GH_API}/repos/${CORE_REPO}/releases/latest" |
  sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"
if [[ -z "$latest_raw" ]]; then
  latest_raw="$(api "${GH_API}/repos/${CORE_REPO}/tags" |
    sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"
fi

target="${CORE_TARGET:-${latest_raw:-}}"
if [[ -z "$target" ]]; then
  warn "Could not reach the core repo - keeping vendored core ${current}. Build continues."
  exit 0
fi

# Normalise "v1.4" / "1.4.0" style tags for comparison.
target_v="${target#v}"
current_v="${current#v}"
log "Latest upstream core: ${target_v}"

# Is $1 strictly newer than $2?
newer_than() {
  [[ "$1" != "$2" ]] &&
    [[ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n1)" == "$1" ]]
}

if ! newer_than "$target_v" "$current_v"; then
  log "Vendored core ${current_v} is already current (upstream ${target_v}). Nothing to do."
  exit 0
fi

log "Upgrading core ${current_v} -> ${target_v}"

# ---------------------------------------------------------------- fetch
staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT

# Clone tag $1 into $2. Tags are written both with and without a leading "v"
# in the wild, so try the other spelling before giving up.
clone_tag() {
  local tag="$1" dest="$2" alt
  git clone --depth 1 --branch "$tag" "${GH_WEB}/${CORE_REPO}.git" "$dest" >/dev/null 2>&1 && return 0
  alt="v${tag#v}"
  [[ "$tag" == v* ]] && alt="${tag#v}"
  rm -rf "$dest"
  git clone --depth 1 --branch "$alt" "${GH_WEB}/${CORE_REPO}.git" "$dest" >/dev/null 2>&1
}

if ! clone_tag "$target" "$staging/new"; then
  warn "Could not fetch core tag ${target} - keeping ${current_v}. Build continues."
  exit 0
fi

if [[ ! -d "$staging/new/aether" ]]; then
  warn "Unexpected upstream layout (no aether/ dir) - aborting upgrade, keeping ${current_v}."
  exit 0
fi

# ------------------------------------------------------------ drift guard
# Everything under aether/ is about to be replaced by the new upstream tree,
# and only PATCHED_FILES are merged back. So any OTHER difference between the
# vendored tree and pristine upstream at the vendored version is work this run
# would delete without a word (see the header). Compare first:
#
#   changed  an upstream file edited here. Add it to PATCHED_FILES and it is
#            three-way merged like the others.
#   added    a file upstream does not ship. Add it to APP_OWNED_FILES and it
#            is carried over verbatim, or keep it out of native/aether/aether.
#   removed  an upstream file deleted here.
#
# The vendored side is what git would commit (tracked files, plus untracked
# ones that are not ignored), so a local cargo target/ is not drift, but a new
# module nobody has committed yet is.
allow_drift() { [[ "${CORE_SYNC_ALLOW_DRIFT:-0}" == "1" ]]; }

is_patched() {
  local p
  for p in "${PATCHED_FILES[@]}"; do
    [[ "$1" == "$p" ]] && return 0
  done
  return 1
}

is_app_owned() {
  local p
  for p in "${APP_OWNED_FILES[@]}"; do
    [[ "$1" == "$p" ]] && return 0
  done
  return 1
}

# NUL-separated paths under aether/, relative to the directory holding aether/.
upstream_files() { git -C "$1" ls-files -z -- aether; }
vendored_files() {
  local f
  if git -C "$REPO_ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    git -C "$REPO_ROOT" ls-files -z --cached --others --exclude-standard -- "$CORE_DIR/aether" |
      while IFS= read -r -d '' f; do printf '%s\0' "${f#"$CORE_DIR"/}"; done
  else
    (cd "$CORE_DIR" && find aether ! -type d -print0)
  fi
}

if ! clone_tag "$current_v" "$staging/base" || [[ ! -d "$staging/base/aether" ]]; then
  rm -rf "$staging/base"
  if ! allow_drift; then
    warn "Could not fetch upstream ${current_v}, so the vendored core cannot be checked for local changes - keeping ${current_v}."
    warn "CORE_SYNC_ALLOW_DRIFT=1 upgrades without the check."
    notice_gh "Core sync skipped: upstream ${current_v} could not be fetched to check the vendored core for local changes."
    exit 0
  fi
  warn "Could not fetch upstream ${current_v}; CORE_SYNC_ALLOW_DRIFT=1, so upgrading without the drift check."
else
  drift=()
  while IFS= read -r -d '' rel; do
    is_patched "$rel" && continue
    is_app_owned "$rel" && continue
    if [[ ! -e "$CORE_DIR/$rel" ]]; then
      drift+=("removed  $rel")
    elif [[ ! -e "$staging/base/$rel" ]]; then
      drift+=("added    $rel")
    elif ! cmp -s "$staging/base/$rel" "$CORE_DIR/$rel"; then
      drift+=("changed  $rel")
    fi
  done < <({ upstream_files "$staging/base"; vendored_files; } | sort -zu)

  if (( ${#drift[@]} == 0 )); then
    log "Drift guard: outside PATCHED_FILES the vendored core is exactly upstream ${current_v}."
  elif allow_drift; then
    warn "CORE_SYNC_ALLOW_DRIFT=1: discarding ${#drift[@]} local change(s) outside PATCHED_FILES:"
    printf '[core-sync]   %s\n' "${drift[@]}" >&2
    notice_gh "Core sync discarded ${#drift[@]} vendored engine change(s) outside PATCHED_FILES (CORE_SYNC_ALLOW_DRIFT=1); they are still in git history."
  else
    warn "Refusing to upgrade ${current_v} -> ${target_v}: it would silently delete ${#drift[@]} local change(s) outside PATCHED_FILES:"
    printf '[core-sync]   %s\n' "${drift[@]}" >&2
    warn "changed: add the file to PATCHED_FILES so it is three-way merged."
    warn "added: add the file to APP_OWNED_FILES so it is carried over, or keep it out of ${CORE_DIR}/aether."
    warn "removed: restore the file from upstream ${current_v}."
    warn "Or discard them on purpose with CORE_SYNC_ALLOW_DRIFT=1. Nothing was changed."
    error_gh "Refusing to upgrade the core: ${#drift[@]} vendored engine change(s) outside PATCHED_FILES would be lost. The log names them."
    exit 1
  fi
fi

# ------------------------------------------------------- baseline for merge
# The pristine upstream copy of each patched file AT THE CURRENTLY VENDORED
# VERSION. Without it a three-way merge is impossible and we would be back to
# the broken "blind copy" behaviour.
have_baseline=1
for rel in "${PATCHED_FILES[@]}"; do
  [[ -f "$BASELINE_DIR/$rel" ]] || have_baseline=0
done

if (( have_baseline == 0 )); then
  log "No complete cached baseline for ${current_v} - reconstructing it from upstream."
  # The drift guard has normally cloned this tag already; reuse it.
  if [[ -d "$staging/base/aether" ]] ||
     { clone_tag "$current_v" "$staging/base" && [[ -d "$staging/base/aether" ]]; }; then
    mkdir -p "$BASELINE_DIR"
    for rel in "${PATCHED_FILES[@]}"; do
      if [[ -f "$staging/base/$rel" ]]; then
        mkdir -p "$BASELINE_DIR/$(dirname "$rel")"
        cp "$staging/base/$rel" "$BASELINE_DIR/$rel"
      fi
    done
    have_baseline=1
    log "Baseline for ${current_v} reconstructed."
  else
    warn "Could not fetch the baseline tag ${current_v}; patches will be re-applied without a merge base."
  fi
fi

# ------------------------------------------------------------- snapshot
# Keep the whole previous core so the CI build step can roll back and retry if
# the new core turns out not to build. This directory is git-ignored.
rm -rf "$PREV_DIR"
mkdir -p "$(dirname "$PREV_DIR")"
cp -R "$CORE_DIR" "$PREV_DIR"

# ---------------------------------------------------------------- preserve
backup="$staging/ours"
mkdir -p "$backup"
for rel in "${PATCHED_FILES[@]}" "${APP_OWNED_FILES[@]}"; do
  if [[ -f "$CORE_DIR/$rel" ]]; then
    mkdir -p "$backup/$(dirname "$rel")"
    cp "$CORE_DIR/$rel" "$backup/$rel"
  fi
done

# ---------------------------------------------------------------- apply
# Replace the upstream-owned sources only. Anything this repo added on its own
# (build scripts, vendored quiche, CORE_VERSION, the baseline cache) stays.
rm -rf "$CORE_DIR/aether"
cp -R "$staging/new/aether" "$CORE_DIR/aether"

# Conflict copies from an earlier run describe an older merge; start clean.
rm -rf "$CONFLICT_DIR"

# keep_for_review <rel> <file> <suffix>: leave a copy beside the tree so the
# person resolving a dropped patch does not have to dig it out of git history.
keep_for_review() {
  mkdir -p "$CONFLICT_DIR/$(dirname "$1")"
  cp "$2" "$CONFLICT_DIR/$1.$3"
}

# App-owned files go back in verbatim.
shadowed=()
for rel in "${APP_OWNED_FILES[@]}"; do
  [[ -f "$backup/$rel" ]] || continue
  if [[ -f "$CORE_DIR/$rel" ]] && ! cmp -s "$backup/$rel" "$CORE_DIR/$rel"; then
    warn "Upstream ${target_v} now ships ${rel} too; keeping this repo's copy, upstream's is under ${CONFLICT_DIR}/."
    keep_for_review "$rel" "$CORE_DIR/$rel" "upstream"
    shadowed+=("$rel")
  fi
  mkdir -p "$CORE_DIR/$(dirname "$rel")"
  cp "$backup/$rel" "$CORE_DIR/$rel"
done

# Re-apply this app's patches by MERGING them onto the new upstream files.
merged=()
unchanged=()
dropped=()
for rel in "${PATCHED_FILES[@]}"; do
  ours="$backup/$rel"
  theirs="$CORE_DIR/$rel"
  base="$BASELINE_DIR/$rel"

  # Nothing of ours to carry over.
  [[ -f "$ours" ]] || continue

  # Ours is pristine upstream (the file is listed but carries no patch right
  # now): the new upstream file is already the right answer.
  if [[ -f "$base" ]] && cmp -s "$base" "$ours"; then
    if [[ -f "$theirs" ]]; then
      unchanged+=("$rel")
    fi
    continue
  fi

  # Upstream removed the file entirely.
  if [[ ! -f "$theirs" ]]; then
    warn "Upstream ${target_v} no longer ships ${rel}; the app patch for it was dropped (copy kept under ${CONFLICT_DIR}/)."
    keep_for_review "$rel" "$ours" "orphan"
    dropped+=("$rel")
    continue
  fi

  # Upstream did not touch this file: our version already contains upstream's
  # content plus our patch, so keep ours verbatim (fast path, no merge needed).
  if [[ -f "$base" ]] && cmp -s "$base" "$theirs"; then
    cp "$ours" "$theirs"
    unchanged+=("$rel")
    continue
  fi

  if [[ ! -f "$base" ]]; then
    # No merge base available. Blind-copying is exactly the bug we are fixing,
    # so prefer the file that is guaranteed to compile: upstream's.
    warn "No merge base for ${rel}; keeping the pure upstream file (app patch NOT applied)."
    keep_for_review "$rel" "$ours" "orphan"
    dropped+=("$rel")
    continue
  fi

  # Real three-way merge: ours (upstream_old + patch) x base x theirs (new).
  work="$staging/merge_$(basename "$rel")"
  cp "$ours" "$work"
  set +e
  git merge-file -q \
    -L "app patch (${current_v})" -L "upstream ${current_v}" -L "upstream ${target_v}" \
    "$work" "$base" "$theirs"
  rc=$?
  set -e

  if (( rc == 0 )); then
    cp "$work" "$theirs"
    merged+=("$rel")
    log "Merged app patch into ${rel} cleanly."
  else
    # rc > 0 = conflicts, rc = 255 = merge error. Either way the result is not
    # trustworthy; keep pure upstream so the engine still compiles, and keep
    # the conflicted merge beside it for whoever re-applies the patch.
    warn "Could not merge the app patch into ${rel} (upstream rewrote it)."
    warn "Keeping the pure upstream file so the build stays green; the conflicted merge is ${CONFLICT_DIR}/${rel}.merge."
    keep_for_review "$rel" "$work" "merge"
    dropped+=("$rel")
  fi
done

# Upstream files outside aether/ that move with the core.
synced_extra=()
for rel in "${UPSTREAM_EXTRA_FILES[@]}"; do
  [[ -f "$staging/new/$rel" ]] || continue
  if [[ ! -f "$CORE_DIR/$rel" ]] || ! cmp -s "$staging/new/$rel" "$CORE_DIR/$rel"; then
    mkdir -p "$CORE_DIR/$(dirname "$rel")"
    cp "$staging/new/$rel" "$CORE_DIR/$rel"
    synced_extra+=("$rel")
    log "Synced ${rel} from upstream ${target_v}."
  fi
done

if [[ -d "$CONFLICT_DIR" ]]; then
  cat > "$CONFLICT_DIR/README.txt" <<EOF
Left by scripts/sync-core.sh while upgrading the core ${current_v} -> ${target_v}.

  *.merge     the three-way merge of an app patch that conflicted. The file in
              the tree is pure upstream ${target_v}; re-apply the patch from here.
  *.orphan    an app patch whose upstream file is gone (or had no merge base).
  *.upstream  upstream's copy of a file this repo owns (APP_OWNED_FILES).

Nothing here is compiled. Delete the directory once every entry is resolved.
EOF
fi

# The new upstream files become the baseline for the NEXT upgrade.
mkdir -p "$BASELINE_DIR"
for rel in "${PATCHED_FILES[@]}"; do
  if [[ -f "$staging/new/$rel" ]]; then
    mkdir -p "$BASELINE_DIR/$(dirname "$rel")"
    cp "$staging/new/$rel" "$BASELINE_DIR/$rel"
  else
    rm -f "$BASELINE_DIR/$rel"
  fi
done
cat > "$BASELINE_DIR/README.txt" <<EOF
Pristine upstream copies of the files this app patches, at core ${target_v}.

Do not edit. scripts/sync-core.sh uses them as the merge base so the app's
engine patches can be rebased onto a new core instead of overwriting it.
EOF

if (( ${#dropped[@]} > 0 )); then
  warn "App engine patch(es) NOT applied on ${target_v}: ${dropped[*]}"
  notice_gh "Core upgraded to ${target_v} but the app patch for ${dropped[*]} could not be rebased. The conflicted merges are under ${CONFLICT_DIR}/ and must be re-applied by hand."
fi

printf '%s\n' "$target_v" > "$VERSION_FILE"
log "Core upgraded to ${target_v}."
(( ${#merged[@]} > 0 ))       && log "  three-way merged: ${merged[*]}"
(( ${#unchanged[@]} > 0 ))    && log "  carried over unchanged: ${unchanged[*]}"
(( ${#synced_extra[@]} > 0 )) && log "  synced outside aether/: ${synced_extra[*]}"
(( ${#shadowed[@]} > 0 ))     && log "  app-owned, now also upstream: ${shadowed[*]}"
(( ${#dropped[@]} > 0 ))      && log "  needs manual review: ${dropped[*]}"

# State for the CI rollback/commit steps.
{
  echo "CORE_PREV_VERSION=${current_v}"
  echo "CORE_NEW_VERSION=${target_v}"
  echo "CORE_UPGRADED=1"
  echo "CORE_DROPPED=${dropped[*]:-}"
} > "$STATE_FILE"

# --------------------------------------------------- new core capabilities
# If the new core advertises capabilities the UI does not expose yet, say so
# loudly in the build log AND in the changelog, so no engine feature can ship
# without a matching UI decision.
#
# --psiphon* and --tor* are deliberately NOT listed: the app runs its own
# Psiphon and Tor cores (docs/CORE_V2.md "One Tor", docs/CORE_V2_1.md "One
# Psiphon"), so the engine's copies are never going to be wired in.
NEW_CAPS=""
if [[ -d "$CORE_DIR/aether/src" ]]; then
  for cap in "--ech" "--noize" "--fragment" "--ironclad" "--dual" "--masque" "--gool" \
             "--verified" "--exit-loc" "--stats" "--gool-classic" "--tls-verify" \
             "--register" "--enroll-address" "--disable-grease" "--tls-ciphers" \
             "--tls-groups"; do
    if grep -rqF -- "$cap" "$CORE_DIR/aether/src" 2>/dev/null; then
      if ! grep -rqF -- "$cap" "app/src/main/java" 2>/dev/null; then
        NEW_CAPS+="${cap} "
      fi
    fi
  done
fi
if [[ -n "$NEW_CAPS" ]]; then
  warn "Core ${target_v} exposes options not wired into the UI yet: ${NEW_CAPS}"
  notice_gh "Core ${target_v} exposes engine options not yet wired into the UI: ${NEW_CAPS}"
fi

# ---------------------------------------------------------------- document
# MANDATORY: every core upgrade documents itself in the current changelog
# (the core-sync:en / core-sync:fa anchors, which live in the 1.2.3 section).
#
# NOTE the "--" before the pattern: every changelog line starts with "- ", and
# without it grep parses the line as a bundle of options and dies with
# "grep: invalid option".
document() {
  local file="$1" anchor="$2" line="$3"
  [[ -f "$file" ]] || return 0
  grep -qF -- "$line" "$file" && return 0
  grep -qF -- "$anchor" "$file" || return 0
  awk -v anchor="$anchor" -v line="$line" '
    { print }
    index($0, anchor) && !inserted { print line; inserted = 1 }
  ' "$file" > "$file.tmp" && mv "$file.tmp" "$file"
}

EN_LINE="- **Engine (core) upgraded to v${target_v}** automatically by the CI core-sync step (previous: v${current_v}). The app's engine patches were rebased onto the new sources with a three-way merge."
FA_LINE="- **ارتقای هسته (Core) به نسخهٔ ${target_v}** به‌صورت خودکار توسط مرحلهٔ core-sync در CI (نسخهٔ قبلی: ${current_v})؛ پچ‌های اختصاصی برنامه با ادغام سه‌طرفه روی سورس جدید بازاعمال شدند."

if (( ${#dropped[@]} > 0 )); then
  EN_LINE+=" Needs manual review: ${dropped[*]}."
  FA_LINE+=" نیازمند بازبینی دستی: ${dropped[*]}."
fi

if [[ -n "$NEW_CAPS" ]]; then
  EN_LINE+=" New engine options detected and pending UI review: ${NEW_CAPS}"
  FA_LINE+=" گزینه‌های تازهٔ هسته که نیازمند بررسی در UI هستند: ${NEW_CAPS}"
fi

document "$README_EN" "<!-- core-sync:en -->" "$EN_LINE"
document "$README_FA" "<!-- core-sync:fa -->" "$FA_LINE"

log "README changelog updated for core ${target_v}."
