# Shizuku server won't stay up — diagnosis (2026-11-23)

Scope: read-only-first diagnosis (Tier L). Nothing was killed/stopped/restarted.
One controlled start attempt was made (server was down), per protocol.
Evidence file left on device: `/sdcard/Download/shizuku_attempt.log`.

## Verdict (ranked)

1. **ROOT CAUSE — the installed Shizuku APK's native libraries are ZIP-compressed
   (DEFLATED), so the server's `System.load("…/base.apk!/lib/arm64-v8a/librish.so")`
   fails deterministically.** Loading a `.so` from inside an APK via the `!/`
   path requires the entry to be STORED uncompressed. In the installed
   `base.apk`, `lib/arm64-v8a/librish.so` (and every other `.so`) is
   `compress_type=8 (DEFLATED)` and unaligned (verified by pulling the APK and
   inspecting the zip directory). Every server start therefore dies in the
   `ShizukuService` constructor with `UnsatisfiedLinkError` before any logging
   is initialized — which is why the death is "silent" in the normal logcat
   (the only trace is in the crash buffer). This is a property of the installed
   APK file, not of the shell session, the starter script, or the OS.
2. **Trigger — the Shizuku app update on 2026-11-20 20:35** replaced the APK
   (new codePath, version 13.6.0.r1086.2650830c, installed via
   `com.google.android.packageinstaller` originating from Chrome, i.e. a
   sideloaded APK). A server process started from the previous APK keeps its
   already-loaded libraries and is unaffected by an update; that is consistent
   with the old server (PID 19337) running long-term and every start *after*
   its death failing. (19337's exact start time/route is no longer recoverable
   from on-device evidence; the kernel booted 2026-11-22 ~18:02 JST per
   `/proc/stat` btime, so the archaeology cannot be fully closed. The failure
   signature of the *current* APK is, however, exact and reproduced.)
3. **Secondary, intermittent — the starter's `pm path` lookup can fail.**
   My controlled attempt died one step earlier than the other attempts:
   the starter exited 7 with `fatal: can't get path of manager`, although
   `pm path moe.shizuku.privileged.api` run manually from the same shell
   (also via `sh -c`, also with a scrubbed environment) returns the path
   reliably. The starter reads it via `popen`; the failure appears flaky
   (device load average was ~5 at the time). This is *not* the root cause:
   three other attempts in the crash buffer got past the lookup and still
   died on cause 1.
4. **Rejected — (a) shell-session lifetime:** the starter daemonizes, switches
   to init's mount namespace, and switches cgroup (all present in the binary);
   no attempt died that way — the server died in its constructor, <1 s after
   exec, or never launched. **(b) Android 17 / app_process restriction:** the
   OS (Android 17, SDK 37) let app_process exec, load the APK's dex, and run
   `ShizukuService.main`; `pm` works. No evidence ties the failure to an OS
   behavior change. Page size is 4096 in this boot (16K-capable device), so
   16K alignment is not implicated either.

## Evidence

### Device / OS facts
- Model SM-F956Q (Galaxy Z Fold6), Android 17 (SDK 37),
  build `CP2A.260605.016`, security patch 2026-09-05,
  fingerprint `samsung/q6qjpnw/q6q:17/CP2A.260605.016/F956QOPU1EZJD:user/release-keys`.
- Kernel boot (btime 1795338307) ≈ 2026-11-22 18:02 JST; uptime 10:54 at 04:59.

### Shizuku app facts
- `moe.shizuku.privileged.api` versionName `13.6.0.r1086.2650830c`,
  versionCode 1086, targetSdk 36, firstInstall 2025-10-15,
  **lastUpdateTime 2026-11-20 20:35:03**,
  codePath `/data/app/~~DXwryn_lGnCqUZYS_pSXRg==/moe.shizuku.privileged.api-TI6qnCf4-y9UA-9sW-JnvA==`,
  installer `com.google.android.packageinstaller`, originating `com.android.chrome`.
- Native libs *are* extracted at install to `…/lib/arm64/` (librish.so, 21512 B,
  present) — but the server does not load from there; the starter passes
  `-Dshizuku.library.path=<apk>!/lib/arm64-v8a` and the server `System.load`s
  the `!/` path.
- Pulled `base.apk` zip inspection: **all** `.so` entries (arm64-v8a,
  armeabi-v7a, x86, x86_64) are `compress_type=8 (DEFLATED)`;
  `lib/arm64-v8a/librish.so`: size 21512, compressed 8654, data offset 899493
  (not 4096-aligned). A `!/` dlopen of a deflated entry cannot succeed.

### Server state at diagnosis time
- `ps -A | grep -i shizuku` → nothing. Server down; manager app not running.
- ClosePaw (build 99, updated 2026-11-22 23:17) status: adbChannel paired +
  connected, lastSuccessfulPort 37987, `virtualDisplayReady: true` (that flag
  tracks the embedded-adb readiness probe, not the Shizuku binder).

### Crash buffer (`logcat -d -b crash`) — three identical server deaths
- 11-23 04:41:13 pid 22532, 04:55:12 pid 27528, 04:59:01 pid 30002 — each:
  ```
  FATAL EXCEPTION: main
  java.lang.UnsatisfiedLinkError: dlopen failed: library
  "/data/app/~~DXwryn…==/moe.shizuku.privileged.api-…==/base.apk!/lib/arm64-v8a/librish.so" not found
      at rikka.shizuku.xh.I(SourceFile:30)
      at rikka.shizuku.an.<init>(SourceFile:15)
      at rikka.shizuku.server.ShizukuService.<init>(SourceFile:1)
      at rikka.shizuku.server.ShizukuService.main(SourceFile:20)
  ```
  (followed by "Couldn't report crash … Bad file descriptor" noise.)
- No new tombstone for these (Java crash, not native); newest tombstones are
  from 2026-11-21.

### The starter (step 2)
- `/sdcard/Android/data/moe.shizuku.privileged.api/start.sh` (mtime
  2026-06-26 22:41; the app has not regenerated it since, despite the update)
  copies `…/moe.shizuku.privileged.api/starter` → `/data/local/tmp/shizuku_starter`
  (chmod 700, chown 2000) and execs it with `"$1"`.
- Starter binary strings show the flow: `popen("pm path moe.shizuku.privileged.api")`
  → kill old `shizuku_server` (scan /proc comm) → "starting server..." →
  daemon(), setns to init mount ns, cgroup switch → exec
  `/system/bin/app_process -Djava.class.path=<apk> -Dshizuku.library.path=<apk>!/lib/arm64-v8a
  --nice-name=shizuku_server rikka.shizuku.server.ShizukuService`.
  Supports `--apk=` override for the path lookup only.

### The one controlled start attempt (2026-11-23 04:59:53 JST)
```
info: start.sh begin
info: attempt to copy starter from /storage/emulated/0/Android/data/moe.shizuku.privileged.api/starter to /data/local/tmp/shizuku_starter
info: exec /data/local/tmp/shizuku_starter
info: starter begin
info: killing old process...
fatal: can't get path of manager
info: shizuku_starter exit with non-zero value 7
```
- start.sh itself exited **0** (it echoes the starter's failure but does not
  propagate the exit code) — see the heal implication below.
- `ps` after the attempt: no shizuku_server (it never launched in this
  attempt). Session-lifetime test (step 4) is therefore moot; in the attempts
  that did launch, the process died in its constructor, not with the session.

## Implication for ClosePaw's auto-heal (stage 1, build 97+)

- Heal path (src-r7 `McpMaintenance.checkShizuku`): through the embedded adb
  channel (shell uid 2000) it probes server identity
  (`ShizukuHealPlan.IDENTITY_PROBE_COMMAND`), then execs
  `ShizukuHealPlan.buildHealCommand(identity, "sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh")`
  (kill-9 of the exact pid+startTicks if a stale server is present, else just
  the start command), then polls the binder for 10 s.
- **The route's premise does not survive this verdict.** No shell-route start
  can succeed while the installed APK's libs are deflated: the starter either
  flakes at `pm path` or launches a server that dies in its constructor.
  Additionally, heal's success check is weakened by start.sh masking the
  starter's exit code (start.sh exits 0 when the starter exits 7), so heal
  logs "start.sh exited successfully" and only fails later at the binder poll
  ("Shizuku did not come up after start.sh") — matching the field behavior.
- Recovery requires an installed Shizuku APK whose native libs are STORED in
  the APK (verify the zip layout before installing — the currently installed
  APK came via Chrome/package-installer and is deflated; do not assume a
  re-download has the right layout). Whether the manager app's own start
  button can succeed is doubtful on this evidence — it drives the same
  starter and the same `!/` library path — and should be treated as
  unverified until an attempt is checked against the crash buffer.
- If heal is kept, it should at minimum treat the starter's `fatal:` output /
  the crash-buffer signature as a terminal, non-retryable condition instead
  of burning its 4-attempts/hour budget on a start that cannot succeed.
