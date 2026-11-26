# ClosePaw handover fact sheet — 2026-11-26

Development ownership transfers from Hayato (VM line) to the owner's PC
Claude Code sessions. This sheet is the fixed reference for the receiving
seat: current state with evidence, the full remaining queue with
acceptance conditions, rig discipline, and gate discipline. Working
documents named below live in Hayato's workspace
(`~/workspace/closepaw-d4d5/` on the VM) and can be requested via the
bridge inbox (repo `RYOITABASHI/shelly-hayato-bridge`, Issue #4).

## 1. Current state

Trusted line: `shelly-trusted-caller`. Head: merge commit `39219b77`
(PR #40). Latest release: **build 166** (tag `shelly-v0.1.0-166`,
target = 39219b77, APK 22,126,571 bytes, SHA-256
`fdb4f7e1f8ea55fb3ea960a95c538c9f6164317c643d871cbeaff9ecbaf34894`).
Build 166 is **released but NOT installed**; the device runs build 162.

### Brain deletion — DONE (PR #39, merge `0e9a3763`, build 162)

Per the Fable verdict of 2026-11-25 (deprecate `run_goal`, unify driving
on Shelly sessions):

- `run_goal` remains in tools/list and refuses with the dedicated error
  `[run_goal_retired] run_goal is retired. Use start_session to drive
  ClosePaw.` — verified on-device on build 162 (no handle/VD/session).
- The internal brain was deleted, not parked: planner LLM clients, model
  catalog (`llm_models.json`), OpenRouter path, third-party Codex client,
  `AgentModelResolver`, `McpIntake` incl. the interim fail-fast guard.
- The Codex OAuth credential saved 2026-11-25 is wiped on first launch
  of build 162 (D-4; code + `RetiredCredentialMigrationTest`).
- `ShellyGoalReceiver` / `SHELLY_RUN_GOAL` removed entirely (Shelly
  re-points its driver to MCP session primitives; current Shelly falls
  back to activity launch, so removal degrades gracefully).
- The draft-save routine (`ClosePawDrafts`, result codes 17/18) is a
  deterministic routine, NOT brain — untouched, behavior-identical.
- Build 162 installed 2026-11-26: after the update ClosePaw recovered
  with **zero intervention** (resident, MCP server, accessibility,
  Shizuku, VD all ready). Session regression on 162 PASSED in an
  unlocked window (Settings row tap ok; Clock tabs タイマー/アラーム/
  世界時計 ok; stale-index tap refused with `element_changed`).

### Full-suite obligation — DISCHARGED

Unfiltered `./gradlew :app:testDebugUnitTest` on Linux runners
(disposable branches, since deleted):

- Post-deletion `0e9a3763`: run 45719971706 — **2134 tests, 1 failed,
  1 skipped**.
- Pre-deletion baseline `503850bc`: run 45723467007 — **2919 tests,
  2 failed, 1 skipped** (the same TaskRowMappingTest failure, plus one
  test of the since-deleted brain).
- Windows run (2134 tests, 7 failed): 6 of the 7 pass on Linux
  (AgentSkillCatalogTest x1, MemoryStoreTest x5 — environment
  artifacts). Conclusion: **the deletion introduced zero regressions.**

### Codex model probe — CLOSED with a definitive record

Diagnostic probe shipped in build 153 (PR #38). Device run completed
2026-11-26 09:04 JST on build 153 (VD session, taps only):

- Settings UI result, verbatim: 「利用可能な Codex モデルはありません」
- logcat tag `CodexModelProbe`: `No entitled Codex models`

Auth itself worked (bearer accepted, refresh worked); the account is
entitled to **zero** Codex models for this client identity
(`originator: pi`). All catalog wire slugs (gpt-5.2 / 5.4 / 5.5) had
already returned HTTP 400 individually. The slug search is closed;
there is no internal-brain revival path, and per the owner there is no
OpenRouter top-up and no paid-API fallback.

### tap_at / swipe — IMPLEMENTED, device proof owed (PR #40)

Builds 165 (branch) / 166 (merge). New MCP session tools `tap_at`
(runHandle, x, y) and `swipe` (fromX, fromY, toX, toY, durationMs
50–2000), routed through `VirtualDisplayInputInjector` (helper-primary,
per-call incumbent fallback, `TaskHelperInputPolicy` flag), success
only on confirmed DOWN+UP acks, VD-scoped.

- Gate: first round BLOCKING (swipe endpoints unchecked; the VD
  executor's clamping could report success for a substituted gesture)
  → fix (session-layer bounds validation against
  `getDisplayInfo()` dimensions, `invalid_params` refusal before
  capture/dispatch) → scoped re-gate **OK**.
- Host evidence: shell rig PASS=37 FAIL=0; standalone JVM rigs
  299 + 323 tests green incl. the new admission/route tests.
- Remaining: install build 166 and run the T-2 device proof (queue #1).

### Foundation already shipped (builds 85–146)

Typed ops (`secure_setting`, `read_logcat`, `capture_screen`,
`get_ui_tree`), VD lifecycle increments, helper-primary task reads and
writes with per-call fallback, Inc.4 input promotion (helper route
default on), round-14 auto-recovery (`McpRecoveryService` sticky
resident + `RunRecoveryStore` persistence + terminal close as
FAILED/`process_lost`), Chrome surfacing verification, and the
`element_changed` root-cause fix (enriched vs raw text guard;
device-verified on build 143/144; the guard-mismatch diagnostic
instrumentation remains in-tree as a tripwire).

### Shizuku — root cause known, dependency still present

The official Shizuku APK v13.6.0 ships its native libraries DEFLATED;
the server's `System.load(...base.apk!/lib/...)` fails, so the server
cannot be shell-launched from the stock APK. Working routes today:
manager launch, or Hayato's adb-shell route with the library path
pointed at installer-extracted libs (documented in the VM workspace:
`shizuku-diagnosis-20261123.md`, `stage2-scoping.md`). After a server
restart, ClosePaw recovery is: **force-stop first, THEN rebind**
accessibility — rebind alone does not respawn it. The durable fix is
stage 2 (remove the dependency); Inc.5 (cutover + VD decoupling) is
scoped but **not started**.

### Device state at handover

Build 162 live; MCP server ON (port 8768); resident/accessibility/
Shizuku ready at last check; permission pre-grants
WRITE_SECURE_SETTINGS and READ_LOGS granted 2026-11-25 and verified
(`read_logcat` returns real lines). MCP token lives in
EncryptedSharedPreferences (`mcp_server_secure`) — **never press
Regenerate** in Settings; it kills existing rigs instantly.

## 2. Remaining queue (with acceptance conditions)

1. **Build 166 install + T-2 device proof.** Acceptance (brief
   `impl-brief-session-input-tools.md`, fixed): tools/list = 19 tools
   incl. `tap_at`/`swipe`; in a VD session, `swipe` scrolls a Settings
   list (before/after observe differ in the expected direction) and
   `tap_at` on a visible row's coordinates opens that row's page;
   injector logs show `route=helper outcome=CONFIRMED` for TAP=1 and
   SWIPE=3; out-of-bounds swipe refused with `invalid_params`; stale
   handle refused with the standard session error. A proof driver
   exists in the VM workspace (`proof_t2.py`). Note: the VM-side
   window hook that was armed for this was **removed at handover** to
   prevent double runs — the receiving seat owns device verification.
2. **Shizuku heal v2** (bridge to Inc.5): ClosePaw-side recovery when
   the Shizuku server dies or the binder is lost, beyond today's
   force-stop + rebind manual sequence. Scope: `stage2-scoping.md`.
3. **CI filter swaps + micro-fix bundle.** (a) In
   `.github/workflows/build-shelly-variant.yml` (still pending; needs
   a route with Workflows permission): swap the test-filter entries
   `MainActivityIntentApplierSecurityTest` →
   `RetiredCredentialMigrationTest`,
   `llm.EffectiveMaxCompletionTokensTest` → `app.RunRecoveryStoreTest`,
   `session.SessionCoordinatorTest` →
   `session.SessionServicesCleanupTest`, plus the stale
   "External Goal dialog" release-notes wording (the trusted-caller
   mechanism itself is LIVE — `ShellyTrustedCaller`, cert-pinned;
   only the wording is stale). (b) Stale callback schema text in
   `McpHandler.kt` (~673/675: "No result callback is sent in this
   increment"). (c) BOOT_COMPLETED log labeling for resident starts
   after force-stop. (d) Recovery-sweep audit logging (sweep /
   `process_lost` closures emit no logcat lines). (e) Cosmetic
   references: `ShellySaveDraftReceiver.kt:23` KDoc,
   `ShellyTrustedCaller.kt:61` comment, `doc/main/app/mcp_intake.md:33`.
4. **VD empty-root fixture** (settlement leftover; designed in
   `settlement-items-plan.md`, not yet run).
5. **`self_update` field exercise** (opportunistic). Note from its
   schema (verified 2026-11-26): it reinstalls the CURRENT build's
   release APK — a repair op, not an upgrade path.
6. **Leftover device batch**: callback-receipt live capture during a
   recovery sweep; Inc.2 C1 in-start recovery path device exercise
   (code + JVM only so far); real-reboot drill (needs an owner
   window). Drill stay-awake procedure: the seat sets
   `stay_on_while_plugged_in 7` for the drill only and restores the
   original value (currently 0), verifying the restore before
   releasing the device lease.
7. **vd_locked track** (design brief `design-brief-vd-locked.md`,
   acceptance V1–V6 fixed there). Measured problem: while Dreaming or
   keyguard-showing, a lock layer covers every display incl. new VDs,
   and `observe` returns it with status ok. Open items: D1 detection
   signals (keyguard + a verified Dreaming signal), D5 avoidance
   measurements (M-a pre-created VD across lock, M-b VD flag variants,
   M-c input-lands-but-observation-blocked split), then D2 admission
   refusal `vd_locked`, D3 mid-session behavior, D4 status field.
   Includes two measured sub-findings: (i) a session VD can be
   `isSleeping=true` / `visibleRequested=false` and never render even
   with the device awake + unlocked (displays 95/96, 2026-11-25,
   uncharacterized); (ii) the IME is shared across displays, so VD
   input focus can surface the soft keyboard on display 0 — close VD
   sessions promptly after input work. No keyguard dismissal or
   lock-bypass attempts, ever.
8. **TaskRowMappingTest fix** (pre-existing, NOT a deletion
   regression): `matchesIncumbentReflectiveMappingForAllFixtureClasses`
   NPEs at `TaskRowMappingTest.kt:29` on Linux and Windows alike,
   identically before and after PR #39 (evidence: runs 45719971706 /
   45723467007 above); every file in its causal chain is byte-identical
   across the deletion, and the class was never in the CI filter.
   Acceptance: the class passes in an unfiltered Linux suite without
   weakening the mapping contract it asserts.
9. **Low priority / opportunistic**: Settings two-pane search field
   merged into one accessibility element (investigation); S3
   decision-path observation (needs an MCP driver for the draft-save
   decision path); ディスプレイ Settings row tap (structurally
   identical to passing rows; Settings resumes sub-pages).

## 3. Rig discipline (device work)

- **VD only.** Display 0 is a read-only observation target: never
  input, launch, front, displace, or kill anything there.
- **overlay_display_devices is FORBIDDEN** (owner incident
  2026-11-26: a leftover setting projected a VD window onto the
  owner's screen). At the end of every device task, verify
  `settings get global overlay_display_devices` is `none`; if set,
  clear it on the spot and report.
- **IME**: never switch unsanctioned. If a task switches it (VD
  terminal text goes via ADB Keyboard), restore Gboard immediately
  after and verify `settings get secure default_input_method` before
  finishing. Minimize switching itself.
- **Windows**: VD content renders only while the device is awake AND
  unlocked (except vd_locked-track measurement work). While Dozing /
  keyguard-showing, VD sessions observe only the lock layer. The adb
  route is unreachable while the device sleeps (measured repeatedly);
  MCP/Tailscale typed routes are the always-on path.
- **adb route (VM rig)**: Tailscale proxy tunnel
  (`adb_tunnel.py <local 13489> ryoz-fold6 <wireless port>`); the
  wireless port rotates — read `closepaw_status`
  `adbChannel.discoveredPort` first (NSD values can be stale). Tier
  discipline via `device_lock.sh`: L read-only parallel, M serialized
  installs/captures, H exclusive VD/input with preflight and
  guaranteed cleanup. Filter on-device; never stream unbounded
  `dumpsys`/`logcat` through the tunnel.
- **Ports**: ClosePaw MCP = 8768, Shelly MCP = 8767. Do not confuse
  their tokens (cross-use returns 401).
- **Sessions**: `finish_run` promptly; crashed drivers leave zombie
  sessions that self-release via Hot Idle (~5 min). `get_run_result`
  takes `runHandle`, not `runId`.
- **Recovery**: never foreground-launch ClosePaw to recover it.
  Shizuku dead → check `pidof shizuku_server` first.

## 4. Gate discipline (all development)

- Fix the acceptance list / fact sheet BEFORE implementation. Gates
  check only the current batch's diff plus conformance to that fixed
  list. If the list itself is wrong, record a LIST DEFECT (or a dated
  clarification in the brief), fix the list first, then run a scoped
  re-gate.
- A **Codex review gate with OK and no blocking findings is required
  before any push**. The final review is the lead's own: read the
  actual diff against the fact sheet; the gate is an input, not the
  verdict.
- Model choice is delegated to the executing agent; pass observed
  tool constraints as information, not prescriptions.
- Build batching: accumulate to a sensible milestone; do not build
  per small change.
- Verify every release **by tag**: tag ref and release
  `target_commitish` must equal the intended commit; record the APK
  SHA-256. Report "build N is out" the moment CI completes —
  separately from verification.
- GitHub route: avoid the connector for reads (approval cards);
  public reads via the REST API; writes via fine-grained PAT
  helpers. The Hayato ops token has **no Workflows permission** —
  workflow-file changes go through the owner's own git credentials
  (this seat).
- Owner involvement: the owner only instructs. No routine approval
  taps, installs, or screen-reading are handed to him; manual items
  are flagged prominently when truly unavoidable.

## 5. Key working documents (VM workspace `~/workspace/closepaw-d4d5/`)

`impl-brief-brain-deletion.md` + `impl-report-brain-deletion.md`
(incl. the full-suite evidence and the known-failure record),
`deprecation-inventory-p1p2.md`, `design-brief-codex-model-probe.md`,
`impl-brief-session-input-tools.md` + `impl-report-session-input-tools.md`
+ `gate-session-input-tools.log`, `design-brief-vd-locked.md`,
`stage2-scoping.md`, `q5-decision-material.md`,
`auto-recovery-audit-20261124.md`,
`seat-findings-dispositions-20261124.md`,
`shizuku-diagnosis-20261123.md`, `settlement-items-plan.md`,
proof drivers `proof_full.py` / `proof_t2.py` / `mcp_client_persist.py`.
