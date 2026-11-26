# ClosePaw VD Stage 2 — Scoping: migrating the virtual display off the Shizuku binder

Status: scoping investigation only (no code changed). Written 2026-11-22 against the build-87
baseline tree (`src-r3-orig/`, current for this subsystem — rounds 3/4 did not touch
`platform/virtualdisplay/`) and the Phase 0 spike tree (`src79/`, tag `shelly-v0.1.0-79`, the
only tree that contains the spike; it was never merged to the trusted line).

## 0. The two privileged routes, in one paragraph each

**Today (Shizuku):** the app process holds no privilege. `ShizukuClient` wraps the framework's
own AIDL stubs around `ShizukuBinderWrapper`, so every call transacts as the shell user through
the Shizuku server process. Proxies are resolved lazily by `ShizukuServiceProxyProvider` from
`SystemServiceHelper.getSystemService(name)` for five services: `display`, `input`,
`activity_task`, `window`, `package`. All reflection is on framework stub classes, so transaction
IDs track the device's framework version.

**Phase 0 helper (proven on device, spike only):** a standalone dex
(`vdhelper/src/helper/java/ai/closepaw/spike/vd/VdResidentMain.java`) is staged to
`/data/local/tmp/closepaw-vd-<random>/` through the embedded ADB channel (`AdbShellChannel`) and
launched with `app_process` **as uid 2000 itself**. Because the helper process *is* the shell,
it needs no binder wrapper: `FrameworkServices` does `ServiceManager.getService("display")` +
reflective `IDisplayManager$Stub.asInterface` directly. The helper registers its command Binder
back into the app through a ContentProvider rendezvous (`IActivityManager
.getContentProviderExternal` + `IContentProvider.call` with a shell `AttributionSource`,
one-time challenge + per-session secret, peer uid/pid checks in `VdProtocol`/`VdWire`). The app
passes its ImageReader `Surface` to the helper inside a Bundle over that Binder; the helper
creates the VD, the app confirms the first frame, and helper death provably releases the display
(the spike's helper-only-kill test). Wire vocabulary today is exactly three commands:
`CREATE`, `FRAME` (ack), `CLOSE` — plus `ERROR`. Nothing else has been built on this channel.

Dependency trade to keep in view: Shizuku needs *its server alive* (stage 1, now in progress,
auto-starts it from the same ADB channel). The helper needs *wireless debugging on + the
embedded ADB channel connected + the helper process alive*, and the spike caps helper lifetime
with a 600 s watchdog and requires API 33+. A stage-2 world removes the Shizuku-server
dependency for VD but makes VD availability ride on the ADB channel's health instead.

## 1. Consumed binder capabilities → purpose → helper reachability → risk

Reachability grades: **proven** = exercised on device by the Phase 0 spike; **plausible** =
same code pattern as a proven call (shell-uid process, `ServiceManager` + reflective stub),
not yet exercised; **unknown** = no precedent in either tree, or a semantic (not transport)
question.

| # | Capability (service · method) | Production consumers & purpose | Helper reachability | Risk |
|---|---|---|---|---|
| 1 | `display` · `IDisplayManager.createVirtualDisplay` (API 33+ `VirtualDisplayConfig` w/ `setDisplayImePolicy`, or legacy 31/32 signatures) + package-name/UID retry dance | `VirtualDisplayPlatform.start()` — create the VD (flags 0xE49 + optional API 34+ focus-isolation flags) | **Proven** — `FrameworkServices.Display.create` does exactly this, incl. the config-builder IME policy and the same flag set (`VdProtocol.REQUIRED_FLAGS` = 0xE49, focus flags on 34+). Legacy 31/32 signatures: **unknown** (spike is API 33+ only) | Low on the target device (API 34+). Product risk if API 31/32 support must be kept |
| 2 | `display` · `releaseVirtualDisplay(callback)` | `VirtualDisplayPlatform.stop()` / start rollback / Broken cleanup | **Proven** — helper `CLOSE` → `Display.release()`; helper death also releases (framework-side, via the app-lease death link) | Low |
| 3 | `display` · `setVirtualDisplaySurface(callback, surface)` | `VirtualDisplaySurfaceController` — hybrid surface model: ImageReader ⇄ viewer `SurfaceView` (live preview) and back | **Plausible** — the helper owns the callback token (the thing the app-side transport must cache), and a `Surface` already crosses the wire once (at CREATE). Needs a new wire command carrying the replacement Surface | Medium — unproven path, and PixelCopy/live-preview timing is user-visible; a botched switch blanks the viewer |
| 4 | `window` · `IWindowManager.setDisplayImePolicy(displayId, policy)` | `VirtualDisplayPlatform.reconcileImePolicy` — HIDE normally, FALLBACK while the viewer is open (takeover typing) | **Plausible, code-present** — the helper already contains this exact call as the fallback when the config builder lacks `setDisplayImePolicy`; on an API 33+ device the builder path likely took precedence, so the standalone call from the helper may never have executed | Low–medium — fail-open in production (per-action a11y IME suppression is the second layer), so a failure degrades, not breaks |
| 5 | `input` · `IInputManager.injectInputEvent(event, WAIT_FOR_FINISH)` with `InputEvent.setDisplayId` targeting | `VirtualDisplayInputInjector` — tap / long-press / swipe / key events / viewer touch forwarding; the primary input path for all coordinate actions | **Plausible** — identical stub pattern via `ServiceManager.getService("input")` from a shell-uid process. Two unproven sub-parts: (a) `setDisplayId` reflection *inside* app_process (the app-side code needs HiddenApiBypass + a round-trip self-test, and falls back to shell when it fails); (b) MotionEvent construction/Parceling — events should be built in-helper from primitives (action, x, y, downTime, eventTime), not parceled from the app | **High** — this is the hot path of every agent action; the 2026-03-06 transport experiment (`doc/main/infra/tool/click_transport_experiment.md`) recorded Shizuku `injectInputEvent` returning success with no effect on one hard surface (Files RecyclerView, emulator, flagged by its own authors as possibly a setup artifact). Any new input path needs a device A/B against the incumbent before it can be trusted |
| 6 | Shell process spawn (`Shizuku.newProcess`) — `input -d <vd> tap/swipe/keyevent/text`, `am start --display`, `am stack …` | Fallbacks everywhere: input when `setDisplayId` fails (`VirtualDisplayInputInjector`, `VirtualDisplayViewerTouchHandler`), ASCII text (`VirtualDisplayTextInjector`), Chrome launch (`VirtualDisplayAppController.startChrome` via `executeQuietShellCommand`), app launch primary path (`am start -n … --display`) | **Proven as a capability, different vehicle** — the app already has `AdbShellChannel.exec` (arbitrary shell as uid 2000; the spike's proofs ran their whole shell workload through it), and the helper process could equally spawn/exec. No Shizuku needed for this row at all | Low for correctness; medium for latency (per-command channel round-trip vs local spawn) on the input fallback path |
| 7 | `activity_task` · `getAllRootTaskInfosOnDisplay(displayId)` (typed: taskId, top/base activity, packages, visible, windowing mode) | The task-observation surface — §2 below. Consumers: `MainDisplayTaskGuard/Restorer`, `VirtualDisplayAppController`, `AgentService.isChromeInFront`, VD stop cleanup | **Plausible** via helper (same stub pattern; returns structured rows over the wire) — **not attempted by the spike**, which instead tested shell-text observation from the app side and graded it partial (§2). Via shell dumps: **proven partial** (see §2 grades) | **High** — the semantic core of stage 2; see §2 |
| 8 | `activity_task` · `removeTask(taskId)` | VD stop cleanup (`removeRootTasksOnDisplay`), save-draft/Chrome task removal (`VirtualDisplayAppController.removeTask`) | **Plausible** via helper (typed Boolean result — strictly better than the spike's `am stack remove`, whose CLI discards the Boolean; Proof 3 could only assert postconditions) | Low–medium |
| 9 | `activity_task` · `moveRootTaskToDisplay(taskId, displayId)` | `MainDisplayTaskRestorer` (displaced display-0 tasks back/forth), `VirtualDisplayAppController.moveTaskHere/moveTaskToMainDisplay` | **Plausible** via helper | Medium — these moves are the user-visible "did my app come back" behaviour of Shelly runs; errors strand user tasks on a dying VD (mitigated today by keep-on-release handover) |
| 10 | `activity_task` · `startActivityFromRecents(taskId, options)` | Restorer refront path (bring the user's task back to front with Recents semantics, no-animation options) | **Plausible** via helper | Medium |
| 11 | `activity_task` · `moveTaskToFront(taskId)` (5-arg shape, fallback to #10) | `VirtualDisplayAppController`, `ChromeDraftBrowser` (save-draft routine) | **Plausible** via helper | Low–medium |
| 12 | `activity_task` · `startActivityAsUser(…12 args, USER_CURRENT)` as shell | Restorer refront fallback (`startActivityAsShell`) — binder form of `am start` without a process spawn | **Plausible** via helper — arguably more natural there: the helper *is* the shell uid the call impersonates today | Medium — signature is found by shape-matching (12 params, Intent 4th); must be re-verified per framework version wherever it runs |
| 13 | `activity_task` · `getAllRootTaskInfosOnDisplay` filtered by package (rootTaskIdsOnDisplay) | `VirtualDisplayAppController.chromeTaskIds` — which Chrome tasks live on the VD (draft/browser flows) | Same as #7 | Same as #7 |
| 14 | Shizuku runtime itself: `pingBinder`, `checkSelfPermission`, permission request, binder-death listener, `HiddenApiBypass` | `PlatformFactory` gating, `VirtualDisplayPlatform.hasRequiredPermissions`, the arbiter's Broken transition on binder death, settings UI status, precheck probes (rounds 3 already moved the *probes* to the ADB channel) | **Replaced, not ported** — helper liveness = wire Binder death (already the spike's model: app-lease death kills the helper; helper death detected via `linkToDeath`/session state) + ADB channel state. The VdLifecycleArbiter's Broken semantics map onto helper-death | Medium — every "is VD available" decision in the app currently funnels through Shizuku availability/permission; each call site must be re-pointed, and a missed one silently falls back to the accessibility platform (wrong-surface runs) |
| 15 | `package` · `IPackageManager.getPackageInstaller()` | `SelfUpdate` fallback install route — **not VD**, listed so the boundary is explicit: stage 2 (VD) does not remove this Shizuku use, nor the CDP bridge's `bindUserService` (`browser/cdp/shizuku/`) | Out of scope | — (whole-app Shizuku removal is a larger programme than stage 2) |

## 2. The task-observation equivalence question, stated precisely

### What production actually observes, and how

Two different observation systems exist today, and conflating them is what makes this look
harder (and easier) than it is:

**(a) The agent loop's per-turn perception does NOT use binder task data at all.**
`AgentTurnRunner`/`TurnExecutionPhaseRunner` call `platform.captureScreen()` +
`getCurrentPackageName()` per turn. On the VD platform both are **accessibility**:
`VirtualDisplayWindowAccessor` takes `AccessibilityService.getWindowsOnAllDisplays()`,
filters to the VD, picks the highest-layer `TYPE_APPLICATION` window; its root gives the
a11y tree and the current package. Screenshots come from the VD's own ImageReader /
PixelCopy. **This whole surface survives a stage-2 migration untouched** — it never
transits Shizuku. Focus, likewise, is handled by creation flags (OWN_FOCUS /
STEAL_TOP_FOCUS_DISABLED) plus a11y window layering, not by task queries.

**(b) Typed task inventory is consumed at run boundaries and around launches — event-driven,
never per-turn polling.** Call sites of `rootTasksOnDisplay(displayId)` /
`rootTasksWithPackagesOnDisplay(displayId)`:

- `MainDisplayTaskRestorer.aroundLaunch` — snapshot displays 0 **and** VD before and after
  every `launchApp` during a Shelly background run (4 queries per launch), so the guard can
  tell "the launch displaced the user's display-0 task onto the VD" from "new task".
- `MainDisplayTaskRestorer.beforeAgentAction` — one display-0 read before an agent action,
  **only while the guard has an undecided displacement** (conditional, not per-action by
  default); `onMainDisplayForeground` — a conditional display-0 read after an a11y
  foreground event on display 0.
- Run start (`reconcileBeforeRun`), run end (`restore`, deferred restore, handover
  preparation), and platform `stop()` — reconcile/restore planning and the pre-release
  task cleanup.
- `VirtualDisplayAppController.chromeTaskIds / mainDisplayTasks` and
  `AgentService.isChromeInFront` (front task on display 0: first root with
  `visible != false`, then "is Chrome's package in its package set").

**Fields actually consumed** (from `DisplayTask` / `RootTask` construction in
`ShizukuActivityTaskTransport`): ordered root-task list, **top-first** (WindowManager
traversal order); `taskId`; `packageName` = top activity's package, else base activity's;
the full package set (base/top/real/orig activity + base intent) for the RootTask variant;
`visible` (nullable, the root's own field); `pinned` (windowing mode == PINNED, roots with
a pinned task are excluded from movable candidates).

**Failure semantics are part of the contract:** every query returns `null` on any failure,
and `null` means *unknown → fail closed*: `reconcileBeforeRun` returning FAILED **blocks
the run from starting**; restore planning aborts and tasks stay "owed" for the next
reconcile; cleanup skips removal. A wrong-but-non-null answer is strictly worse than null
everywhere — it moves or destroys the wrong user task, or strands one.

### What the Phase 0 spike proved about re-sourcing this from shell text (Proof 2)

Proof 2 deliberately tested the *no-binder* route: observation from the app over
`AdbShellChannel` by merging two text dumps — `am stack list` plus a device-side
awk-filtered `dumpsys activity activities` (`VdTaskEvidence.kt`). Its own evidence grades
(`VdTaskObservation.comparison`):

- `rootTaskId`, `topFirstOrdering`, `pinned`, `nullOnFailure` (induced bad command →
  null): **reproduced**.
- `topOrBasePackage`, `packageFilteredRootIds`, `nullableVisibility`: **approximated** —
  package attribution falls back to the bottom History entry when the stack row lacks a
  top activity; package-filtered ids union several hints (top, base, intent, leaf alias)
  where the typed API has exact fields; visibility is a *child-row* proxy for the root's
  field, and empty roots yield null.
- `typedContractParityProven`: **false**, by the spike's own record — *"No typed Binder
  comparison was performed."* Proof 2 as constructed could only ever show shell-vs-shell
  self-consistency, never equivalence to the typed contract production consumes.

And its pass bar is brittle by construction: `mergeTasks` returns an **empty (null)
observation unless the two sequential dumps reconcile exactly** — identical root-id
sequences per display, child ids exactly equal to leaf ids, visibility agreement. Any task
migrating between the two polls (normal during launches — precisely when the restorer
queries) voids the observation. Latency compounds this: each observation is two serialized
channel execs (the spike allows 6 s each; a full `dumpsys activity activities` must be
written to a file on-device and filtered there — streaming it is both slow and, per the
2026-11-22 freeze lesson, dangerous), versus one binder transaction.

### The equivalence question, precisely

> Can a non-Shizuku source produce, for an arbitrary display, a **single-snapshot**,
> top-first-ordered root-task list with (taskId, top-else-base package, full package set,
> nullable visible, pinned) that is **never silently wrong** (any doubt → null), at the
> call frequency of §2(b) — around every launch and at run boundaries — without the
> two-poll race?

Candidate answers:

1. **Helper-side typed query (the natural stage-2 design).** The helper process calls
   `IActivityTaskManager.getAllRootTaskInfosOnDisplay` itself — the *same API, same
   identity* as today's Shizuku path — and returns structured rows over the wire. This is
   equivalence-preserving *by construction*: identical fields, identical ordering,
   single snapshot, null-on-any-exception. It is **plausible, not proven**: the spike never
   sent a task query over the wire (its command set is CREATE/FRAME/CLOSE). Cost: new wire
   commands + a DTO, and observation now depends on helper liveness — acceptable, since
   without the helper there is no VD to observe in a stage-2 world (display-0-only queries
   by non-VD consumers, e.g. `AgentService.isChromeInFront`, are the exception and need a
   stated answer: helper-on-demand, or keep that one query on another route).
2. **Shell-text observation (spike route).** Proven partial: identity/ordering/pinned/
   failure-nulling reproduce; package attribution and visibility are approximations, and
   exact-reconciliation nulls will fire under launch churn. Adopting it means *changing
   production semantics*, not migrating them: the guard would make displacement decisions
   on approximated packages, and reconcile would fail closed more often (runs refused at
   start). That is a product decision, not an engineering equivalence.
3. **Accessibility windows.** Already carries the loop's needs (§2a) and can answer
   "which package's window is in front on display D" — but it has no task ids, no
   inventory of windowless tasks, no pinned state, and no stable task identity across
   window churn. It cannot substitute for (b) at all; it already complements it.

**Conclusion:** the equivalence question has a clean answer only via route 1. Stage 2
should therefore be framed as "move the typed calls into the helper", with route 2 kept
as the documented fallback if route 1 fails on device.

## 3. Proposed increments

Each increment is independently shippable behind the existing platform selection (the
helper path already knows how to fall back: `PlatformFactory` falls back to the
accessibility platform today; a stage-2 platform can fall back to the Shizuku platform
while both exist).

**Increment 1 — prove the riskiest assumption: typed task observation over the helper
wire.** Extend the spike (or a minimal production-shadow variant) with one read-only wire
command: `QUERY_ROOT_TASKS(displayId)` executed in the helper via
`IActivityTaskManager.getAllRootTaskInfosOnDisplay`, returning (taskId, topPackage,
basePackage, packages, visible, pinned) rows. On device, run it side-by-side with the
incumbent `ShizukuClient.rootTasksOnDisplay` / `rootTasksWithPackagesOnDisplay` for
displays 0 and a live VD, across a scripted launch/displace/restore sequence, and diff
the outputs field-by-field. This is small (one command, no production behaviour change,
observation-only), and it directly retires the §2 risk: if the helper cannot reproduce
the typed contract, stage 2 stops here and route 2 (shell text) gets its own decision.
*Verify:* field-level diff report over N sequences incl. a pinned task, an empty root,
and a mid-sequence task migration; null-behaviour induced by killing the helper
mid-query.

**Increment 2 — VD lifecycle on the helper (create/release/surface/IME policy).**
Productionise the spike's session (lift the 600 s watchdog into a session-length
lifetime, API 33+ gate made explicit) behind a `HelperVirtualDisplayPlatform` selected
only when the Shizuku platform is unavailable or by a debug flag; port
`setVirtualDisplaySurface` (row 3) and `setDisplayImePolicy` (row 4). Parity target:
the existing VD test matrix + viewer live-preview. *Verify:* existing VD instrumentation
scenarios pass on the helper platform; surface switch round-trip ImageReader ⇄
SurfaceView without a blank viewer.

**Increment 3 — task management writes on the helper.** Port rows 8–12
(remove/move/refront/start-as-shell) and switch `MainDisplayTaskRestorer`,
`VirtualDisplayAppController`, and stop-cleanup to the helper transport; keep the
Shizuku transport compiled in as the fallback for one release. *Verify:* the Shelly
background-run displacement suite (launch displaces → run ends → user's task returns /
deferred restore / handover) green on the helper transport; `am stack remove`-class
postcondition-only checks replaced by typed Boolean assertions.

**Increment 4 — input on the helper.** Port row 5: in-helper MotionEvent construction
+ `setDisplayId` + `IInputManager.injectInputEvent`, with the incumbent's own discipline
(first-use round-trip self-test; shell fallback retained, re-sourced to
`AdbShellChannel`). Gate on a device A/B against the Shizuku injector across the
transport-experiment surfaces (incl. Files RecyclerView) before it can become the
primary path. *Verify:* A/B matrix ≥ incumbent on every surface; no false-success
injections (event accepted, no UI effect) beyond the incumbent's rate.

**Increment 5 — cutover and Shizuku decoupling for VD.** Re-point availability gating
(row 14: factory, `hasRequiredPermissions`, settings/status surfaces still naming
Shizuku for VD), make the helper platform the default VD backend, and delete the
Shizuku VD transports. Out of scope even here: the CDP user-service bridge and the
self-update IPackageManager fallback (row 15) — whole-app Shizuku removal is a
separate programme.

## 4. Open questions (device experiment or owner decision)

1. **Helper-side typed task query** — does `getAllRootTaskInfosOnDisplay` from the
   app_process helper return byte-equivalent `RootTaskInfo` data (incl. `visible` and
   configuration windowing mode) to the Shizuku-wrapped call? (Increment 1 answers
   this; it is *the* gating experiment.)
2. **`setDisplayId` inside app_process** — does the reflection work under the helper's
   hidden-API enforcement profile (no HiddenApiBypass library loaded there today), and
   does `injectInputEvent` from the helper avoid the false-success seen in the 2026-03
   emulator experiment on the real Fold?
3. **API floor** — the spike requires API 33+; production VD creation supports 31/32
   legacy signatures. Is dropping VD (not the app) on API 31/32 acceptable, or must
   the helper grow the legacy signatures?
4. **Helper lifetime & residency** — production sessions run far beyond the spike's
   600 s watchdog; a resident helper is a new always-on process on the owner's phone
   (memory, battery, and "what is this process" optics). Session-scoped (spawn per VD
   session, as the spike does) vs resident across sessions is an owner-visible choice.
5. **Display-0-only task queries without a VD session** (`AgentService.isChromeInFront`,
   save-draft gathers) — spawn the helper on demand, keep those on shell-text
   observation (accepting §2 approximations for those consumers only), or keep a
   minimal Shizuku dependency for them? Needs a decision before Increment 3.
6. **ADB-channel dependence** — stage 2 makes VD availability depend on wireless
   debugging + NSD port discovery staying healthy for the whole session (today a
   mid-session ADB drop doesn't kill a Shizuku VD; a mid-session helper death does
   kill the stage-2 VD — the arbiter's Broken path covers it, but the *rate* matters).
   Is that trade accepted for background runs, given the degraded-mode design already
   treats ADB loss on mobile data as expected?
7. **IME policy from the helper** — the helper's `setDisplayImePolicy` fallback code
   path may never have executed (builder path preferred). Needs one forced execution
   on device before Increment 2 relies on it.

## Sources

- Production: `app/src/main/kotlin/ai/closepaw/platform/PlatformFactory.kt`;
  `platform/virtualdisplay/` — `ShizukuClient.kt`, `ShizukuServiceProxyProvider.kt`,
  `ShizukuDisplayTransport.kt`, `ShizukuActivityTaskTransport.kt`,
  `ShizukuInputTransport.kt`, `ShizukuWindowTransport.kt`, `ShizukuShellExecutor.kt`,
  `ShizukuActivityLauncher.kt`, `ShizukuRuntimeGateway.kt`, `VirtualDisplayPlatform.kt`,
  `VirtualDisplayWindowAccessor.kt`, `VirtualDisplayAppController.kt`,
  `MainDisplayTaskGuard.kt`, `MainDisplayTaskRestorer.kt`; `app/AgentService.kt`
  (taskReader / isChromeInFront); `adb/AdbShellChannel.kt`.
- Spike (tag-79 tree only): `vdhelper/src/helper/java/ai/closepaw/spike/vd/
  VdResidentMain.java`, `FrameworkServices.java`; `vdhelper/src/main/java/ai/closepaw/
  spike/vd/VdProtocol.java`, `VdWire.java`; `app/src/main/kotlin/ai/closepaw/spike/vd/
  ResidentProcess.kt`, `VdHelperSession.kt`, `VdPhase0Proofs.kt`, `VdTaskEvidence.kt`,
  `VdRecoveryQueries.kt`.
- Docs: `doc/main/infra/virtual_display.md` (VD architecture, lifecycle arbiter, input
  fallback table), `doc/main/infra/platform.md` (platform selection/fallback),
  `doc/main/infra/tool/click_transport_experiment.md` (2026-03-06 transport A/B,
  emulator caveats), `doc/main/app/mcp_intake.md` (status surface naming `shizukuReady`
  — renamed in round 3 after this tree).

---

## Premise record (2026-11-22, confirmed for the Shelly README fact-check)

Two layers must not be conflated:

- **Readiness gate (start/no-start decision): rewired off Shizuku in PR #14, shipped in build 94.**
  ShellyGoalReceiver / ShellySaveDraftReceiver / McpIntake now decide via
  `probeVirtualDisplayReady` -> `VirtualDisplayReadiness.isReady(paired, connected, hasKnownPort)`
  over the embedded adb channel's state (prefs/files/in-memory only, fail-closed).
  The gate does NOT consult ShizukuClient. Status field renamed `shizukuReady` -> `virtualDisplayReady`.
- **VD execution itself (creation + input, VirtualDisplayPlatform and below): still Shizuku-binder
  dependent in builds 94 / 97 / 99.** Stage 2 (migration to the in-house helper) is NOT done.
  Build 97 (round 5) added only the auto-heal: McpMaintenance restarts Shizuku via the canonical
  start.sh path when its binder dies. That is recovery of the dependency, not removal of it.
  Round 6 (build 99) changed only the receiver's logging (goal fingerprint); probe wiring
  unchanged since round 3.

Accurate one-liner for external docs: "The start gate no longer checks Shizuku, but VD
execution still requires the Shizuku server (with automatic recovery since build 97)."
