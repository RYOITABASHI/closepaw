# MCP intake (v1)

ClosePaw can expose its own token-gated MCP endpoint so an MCP client (Claude Code, Codex, Muse, …)
can ask it to operate the phone directly, without going through Shelly. Off by default.

## Endpoint

- Streamable HTTP, `POST http://<phone-ip>:8768/mcp`, JSON responses (no SSE; `GET` → 405).
- Protocol `2025-06-18` (`2025-03-26` is echoed if requested): `initialize` returns an
  `Mcp-Session-Id`; every later request must carry it. `DELETE` with the header ends the session.
- Every request needs `Authorization: Bearer <token>` (constant-time check). A present `Origin`
  header that is not loopback is refused with 403 (DNS-rebinding guard).
- Bound to `0.0.0.0` only while **Settings → Agent Behavior → MCP → ClosePaw MCP server** is ON.

The design mirrors Shelly's MCP server (`scripts/shelly-mcp-server.js`, legacy era): same check
order (Origin → path → bearer → method), status codes and JSON-RPC error codes.

## Tools (typed surface only)

| Tool | Args | Result |
|---|---|---|
| `run_goal` | `{goal}` | `{runHandle}` |
| `get_run_result` | `{runHandle, waitMs?}` (≤ 20000) | `{runHandle, status, summary}` |
| `stop_run` | `{runHandle}` | `{runHandle, stopRequested, status}` |
| `closepaw_status` | `{}` | `{version, serverOn, virtualDisplayReady, accessibilityReady, activeRuns}` |

`status`: `starting | running | completed | failed | cancelled`. There are no tap / swipe /
text / shell tools: `run_goal` is the only way to make ClosePaw act.

## How a run joins the existing machinery

`run_goal` → `AgentServiceMcpBackend` (`app/McpIntake.kt`) runs the same probes and
`ShellyBackgroundRun.precheck` as `ShellyGoalReceiver` (main-display fallback never allowed), then
calls `AgentService.startShellyRun(goal, VIRTUAL_DISPLAY, correlation = null, onTerminal)`. So the
run gets the same virtual-display execution, invisible overlay, IME policy, task
restore/deferral and notification. No Shelly correlation is passed, so nothing is sent to Shelly.

`onTerminal` is fired by `AgentService` from the same report-once paths that send the Shelly
result (`reportShellyResult`, `reportShellyStartFailure`, `reportShellyStartCancelled`), so STOP-ALL
(capsule Stop), the notification's Stop, `STOP_AGENT`, replacement by a user task and service
destroy all end an MCP run as `cancelled`/`failed`. `stop_run` uses `AgentService.stopIntakeRun`
→ `stopShellyRun` (or cancels a start that is still being set up).

## Token

32 random bytes (hex), stored in its own `EncryptedSharedPreferences` file (same pattern as
`AuthStore`). Regenerating replaces it immediately and drops all MCP sessions.

### Shell-only credential provider

`McpCredentialProvider` (authority `ai.closepaw.mcpcredential`, path `/token`, column `token`)
returns the current bearer token to the adb shell (app id 2000) and root only, so an operator
agent with an adb route can recover a lost credential file with no owner action:

    adb shell content query --uri content://ai.closepaw.mcpcredential/token

- Any other caller uid: `SecurityException`; the token store is not touched.
- MCP disabled, or no token yet: zero rows. The provider never creates or regenerates a token,
  so reading it never invalidates a running client.
- Other paths: zero rows. insert / update / delete: refused.
- Audit: `ClosePawMcpAudit` logs `mcp credential uid=<uid> outcome=served|denied|empty(<reason>)`.
  The token value is never logged.
- Client: `src/closepaw/closepaw_mcp.py` in the shelly-hayato-bridge repository.

## Audit

Logcat tag `ClosePawMcpAudit`, one line per tool call:
`mcp tool=<name> handle=<runHandle|-> outcome=<…>`. Never the token, never the goal text.
