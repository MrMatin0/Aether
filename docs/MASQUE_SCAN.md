# MASQUE: "it does not scan"

*`fix/masque-scan`, 2026-09-24. App 1.5.0, engine 2.1.0.*

Users reported that MASQUE never connects and "does not scan". There was no
single bug. Five problems stacked on the same attempt, each one eating a budget
the next one needed. This page records what they were, what changed, and what
was left alone on purpose.

## One attempt, before the fix

The default hand-picked MASQUE profile (Precise, IPv4, quick reconnect, noize
firewall) runs the engine as
`--masque --balanced -4 --quick-reconnect --noize firewall` with
`AETHER_MASQUE_HTTP2=0`:

| Step | Where | Worst case |
|---|---|---|
| Re-verify remembered gateways, one at a time (at most 8) | engine: `lib.rs` `run_masque`, `lastconn::RECENT_CAP` | 8 × 5 s = 40 s |
| Fetch an ECHConfigList, when `--ech auto` was sent (the anti-DPI pass always sent it) | engine: `lib.rs` `resolve_ech` → `dns.rs` | ~18 s |
| Balanced sweep: 16 probes in flight, 6 s each, until 6 gateways answer | engine: `prober.rs` | 120 s |
| **The app kills the attempt** | app: `ConnectionPlanner`, `FIRST_PASS_MAX_MS` | **75 s** |
| Retry forced onto HTTP/2 + fragment + ECH over TCP 443 | app | fails wherever only QUIC gets through |

The engine never gives up by itself: an empty sweep is logged ("no usable
MASQUE gateway found ... rescanning shortly") and a new one starts two seconds
later. The app's budget is the only thing that ends an attempt, and it was
shorter than one sweep.

## What was wrong, and what changed

### 1. The app cut the scan off halfway (app)

`ConnectionPlanner.manualProtocol` capped the first attempt of a hand-picked
MASQUE or MIM protocol at 75 s, whatever the scan mode:

| Scan mode | Engine sweep (MASQUE) | First attempt, before | After |
|---|---|---|---|
| Turbo | 45 s | 60 s | 105 s |
| Precise | 120 s | **75 s** | 235 s |
| Verified | 60 s | 75 s | 165 s |
| Ultra | 180 s | **75 s** | 385 s |

**Fix:** the cap is gone (`VpnTunables.FIRST_PASS_MAX_MS` removed). Every
attempt gets the profile's full `connectTimeoutMs()`. The HTTP/2 pass still
follows when the first attempt fails, on the same budget. The price is a
longer wait on a network where nothing works at all; the alternative was never
letting the scan finish on one where it does.

### 2. Nobody budgeted for the remembered-gateway ring (app)

Engine 2.1.0 remembers the last eight working gateways and, with
`--quick-reconnect` (the app's default), re-verifies them one by one before it
scans. On a network that has just blocked them, that is 40 s before the sweep
begins. Smart Auto runs every rung on Turbo with a 60 s budget, so a MASQUE
rung could time out before its sweep had really started.

**Fix:** MASQUE and MIM budgets include
`ConnectionProfile.MASQUE_QUICK_RECONNECT_ALLOWANCE_MS` (45 s) when quick
reconnect is on (the "After" column above). WireGuard, Gool and pinned peers
are unchanged.

### 3. ECH was sent to an endpoint that does not accept it (app)

Upstream's own `resolve_ech()` says it: "warp masque endpoint does not accept
ECH". With `--ech auto` the engine still fetched an ECHConfigList (up to
~18 s of DNS on a filtered network, before scanning) and injected it into the
HTTP/3 tunnel handshake. The scan probes never carry ECH, so an edge that
passed the scan was then dialled with a handshake the scan had never tested.
Over HTTP/2 the value is ignored, so there it was only the delay. The anti-DPI
pass and three Smart Auto MASQUE rungs asked for ECH on every attempt.

**Fix:** `ConnectionProfile.sendsEch` is false for MASQUE and MIM, so `--ech`
never reaches a MASQUE engine, even with the ECH switch on. The anti-DPI pass
and the Smart Auto rungs stop asking for it. WireGuard and Gool are unchanged.

### 4. The sweep waited out its whole deadline (engine, `prober.rs`)

The quiet window (`quiet_after_first`: 20 s balanced, 8 s verified, 15 s
ironclad) only started once the target number of gateways had answered (6, 4,
3). On a filtered network one to five answering gateways is the common case,
and then the engine sat out the full deadline before using any of them.

**Fix:** the window starts at the first gateway and restarts with each new one
until the target, which is what its name and the "no new gateways recently"
log line already described. From the target on nothing changes. Turbo (first
answer wins) and thorough (no target) behave exactly as before.

### 5. The DNS-over-HTTPS ranges took 1 in 7 probes (engine, `prober.rs`)

`MASQUE_CIDRS_V4` lists `162.159.36.0/24` and `162.159.46.0/24` last because
they never serve MASQUE (upstream's own test says so), but the round-robin
sweep still gave them 2 of every 14 probes from the first round.

**Fix:** they are queued after every other candidate. Nothing is dropped.

Both engine changes live in `prober.rs`, which is already in `PATCHED_FILES`,
so `scripts/sync-core.sh` three-way merges them onto the next core. The
upstream baseline under `native/aether/.upstream-baseline/` is untouched, and
`MODIFICATIONS.md` records the patch.

## Left alone on purpose

- **Manual ranges are still not honoured.** The app sets `AETHER_SCAN_CIDRS`,
  `AETHER_MASQUE_CIDRS` and `AETHER_WG_CIDRS`, but `prober/scan.rs`, which read
  them, was deleted by the 2.0.0 sync and neither upstream prober reads them.
  Restoring that is its own change: it would also make Smart Auto's MASQUE
  rungs scan only the edges its WireGuard-style probe measured, and one of
  those, `8.6.112.0/24`, is not a MASQUE range at all.
- **`quic.rs` `verify_masque`** starts its clock before the 500 ms version
  bait, so measured RTTs can be inflated by up to half a second. That hits
  every candidate alike, so the ranking is unchanged; fixing it means adding
  `quic.rs` to `PATCHED_FILES`.
- **The ring walk itself** (sequential, 5 s per gateway) is upstream behaviour
  in `lib.rs`; the app budgets for it instead of patching it.

## Checking it

- Unit tests: `ConnectionPlannerTest`, `StrategyLadderTest`, `SmartAutoTest`
  and `MasqueScanBudgetTest` run in CI (`:app:testReleaseUnitTest`). The new
  `prober.rs` tests (sweep order, quiet window) run with the engine's
  `cargo test`, which CI does not run.
- On a device, with the core log level on Info: connect with MASQUE on Precise.
  The engine log should show `candidate ok`, then `reached target` or
  `no new gateways recently, finalizing selection` well before the deadline,
  and `ECH disabled (warp masque endpoint does not accept ECH)` instead of an
  ECHConfigList fetch.
- Which ranges answer from a given network, from the engine crate:
  `cargo test report_which_masque_ranges_answer_on_this_network -- --ignored --nocapture`.
