---
name: shelly-operator
description: Operate the Shelly app (dev.shelly.terminal) on this phone — read or change Shelly's own MCP server settings, restart the article-delivery loop in a terminal tab, and run Shelly's in-app update flow. Load for any task whose target app is Shelly itself.
---

# Operating Shelly (dev.shelly.terminal)

You are operating **Shelly**, an AI terminal / agent app, on the user's phone.
Work slowly and verify each screen before acting. If a screen does not match this
skill, STOP and report what you actually see (see Reporting).

## GUARDRAILS — hard rules, no exceptions

1. NEVER tap any approval dialog for a command or file operation. This includes the
   dialog titled 「Shelly MCPサーバー: 承認が必要です」 (buttons 承認 / 拒否) and any
   dialog offering 許可 / 常に許可 / 許可しない. If one appears: STOP, do not touch
   it, and report that an approval dialog is showing.
2. NEVER approve a ClosePaw hand-off or "External Goal" confirmation from inside a
   task. Consent for your runs belongs to the user on the phone, before the run.
   If such a prompt appears mid-task: STOP and report.
3. NEVER tap 「MCPトークンを再発行」 (Regenerate MCP Token), and never tap
   「MCPトークンをコピー」 (Copy MCP Token). The MCP token must not change and must
   not be copied anywhere.
4. Change only the setting named in the goal. Read everything else; touch nothing else.
5. Never type into a terminal except exactly as Procedure 2 says. Never run other
   commands, and never use Shelly to start another ClosePaw task.

## UI MAP

- Shelly opens to a workspace: a top bar, a sidebar (☰ menu icon, top-left), up to
  4 panes in the center (Terminal / AI / Browser / Markdown), and a bottom context bar.
- Top bar, right side icons (left to right): cloud-download = **Updates** screen;
  magnifier = **command palette**; history (clock/arrow) = recent logs;
  gear = **quick Settings panel** (a dropdown, NOT the full settings).
- Terminal sessions are named shelly-1 … shelly-4. A terminal pane has a key bar
  above the keyboard with buttons: **Ctrl+C**, Tab, ↑, ↓, Paste, Alt, Enter
  (compact labels may show ^C). Use these buttons instead of trying to send key
  chords. Tap a terminal pane to focus it before typing.
- The sidebar's **MY AGENTS** section lists the user's agents. You should not need
  it for these procedures.

## WHERE SHELLY'S OWN MCP SETTINGS LIVE — read carefully

Shelly's own MCP *server* settings are ONLY in the **shelly config** screen:

1. Tap the magnifier (command palette) in the top bar.
2. Tap the action labelled **Settings**. A modal titled **shelly config** opens:
   one long scrollable list of sections.
3. Scroll to the section **エージェント** (Agents). The MCP rows are there, in
   this order (Japanese label / English label):
   - MCPサーバー / MCP Server — the server on/off switch
   - MCPトークンをコピー / Copy MCP Token — button コピー / Copy — DO NOT TAP
   - MCPトークンを再発行 / Regenerate MCP Token — button 再発行 / Regenerate — DO NOT TAP
   - MCP: ターミナル出力の読み取りを許可 / MCP: Allow reading terminal output — switch
   - MCP: 診断情報（ログ・設定）の読み取りを許可 / MCP: Allow diagnostics (logs, settings) — switch
   - MCP: 実行/書き込みを許可（危険） / MCP: Allow exec/write (dangerous) — switch
4. Close the modal with the × at its top-right when done.

TRAP: the gear (quick Settings) panel has a section 連携 (Integrations) with a row
labelled **MCP Servers**. That screen manages *outbound* MCP servers Shelly connects
to as a client (Start/Stop buttons, client config). It is NOT Shelly's own server
and shows nothing about it. Never use it to judge or change Shelly's MCP server state.
The same warning applies to the "MCP Servers" title inside that screen.

## PROCEDURE 1 — Report Shelly's MCP server state (read-only)

1. Open Shelly with open_app (package dev.shelly.terminal).
2. Open **shelly config** via the command palette → **Settings** (see above).
3. Scroll to エージェント and find the six MCP rows.
4. Read the switch state (on/off) of these four rows: MCPサーバー;
   MCP: ターミナル出力の読み取りを許可; MCP: 診断情報（ログ・設定）の読み取りを許可;
   MCP: 実行/書き込みを許可（危険）. Do not tap any switch or button.
5. Close the modal (×). Report using the Reporting format, quoting the labels
   exactly as shown on screen.

## PROCEDURE 2 — Restart the article-delivery loop

The delivery loop runs inside one of Shelly's terminal tabs as:
`node ~/.shelly/agents/.env.article-delivery.js loop`
Its tab shows periodic delivery/status output lines instead of an idle prompt.

1. Open Shelly. Visit each terminal pane (top-bar pane switcher) and find the one
   whose visible output is the delivery loop's repeating status lines.
   - If NO pane shows the loop and every terminal sits at an idle shell prompt,
     the loop is not running: use the first terminal pane and say so in your report.
2. Tap that terminal pane to focus it.
3. Tap **Ctrl+C** in the key bar. Wait for the shell prompt to return. (If output
   keeps scrolling, tap Ctrl+C once more. If the terminal asks a yes/no question,
   STOP and report the question text.)
4. Type exactly this line, then tap **Enter** in the key bar:
   node ~/.shelly/agents/.env.article-delivery.js loop
5. Verify on screen: new loop output lines appear and keep the tab busy (the shell
   prompt does NOT come back). If an error message or an immediate prompt appears
   instead, STOP and report the visible error text word for word.
6. Report: which tab, whether the loop had been running, the last visible output
   lines after restart.

## PROCEDURE 3 — Change one named Settings switch

1. Open **shelly config** (command palette → Settings) and scroll to the section
   named in the goal (for MCP rows: エージェント).
2. Find the row by its exact label (Japanese or English, as quoted in the goal).
3. Read its current state. If it already matches the target state in the goal,
   do not tap; report "already <state>".
4. Otherwise tap the row's switch once. Note: changing any MCP row restarts
   Shelly's MCP server — a brief restart is expected, not an error.
5. Re-read the switch and report the new state, quoting the label exactly.

## PROCEDURE 4 — Run Shelly's in-app update

1. Open Shelly. Open the Updates screen: tap the cloud-download icon in the top
   bar. (Equivalent: gear → section 更新 (UPDATES) → row 更新を確認 / Check for updates.)
2. Read the status:
   - 「Shelly は最新です」 / "Shelly is up to date": report the current version
     (「Shelly 現在 v…」) and STOP — nothing to install.
   - 「更新があります」 / "Update available" with 「Shelly 利用可能 v…」 newer than
     「Shelly 現在 v…」: continue, but only if the goal explicitly says to install
     the update. If the goal only asked to check, report and STOP.
3. Tap the install/update action for the **Shelly release build** (not "Dev builds",
   not Codex / Claude Code / CLI updates on the same screen).
4. Android's system package-installer confirmation will appear. This is the one
   confirmation you may accept, and only because the goal explicitly requested
   this update: tap Install (インストール). Any OTHER dialog — especially a
   signing-key / backup / uninstall message (署名鍵, バックアップ, アンインストール)
   — means STOP and report; that migration flow must never be automated.
5. After installation Shelly restarts. Re-open Shelly, return to the Updates
   screen, and verify it now shows the new version as current / up to date.
6. Report the version before and after, exactly as shown.

## REPORTING — end every task with this block, in this order

TASK: <the goal, shortened to one line>
FOUND: <initial state you verified on screen, with labels quoted exactly>
ACTIONS: <each tap/action you took, in order; "none" if read-only>
FINAL STATE: <the state you verified at the end, labels quoted exactly>
UNEXPECTED: <anything that differed from this skill, verbatim screen text where
possible; "none" if nothing>

Rules for reporting: quote on-screen text exactly (Japanese as shown). Never claim
a state you did not see on screen. If you stopped early, say where and why in
UNEXPECTED.
