---
name: app-shelly
description: App-specific guidance for Shelly.
metadata:
  package: dev.shelly.terminal
---

- CRITICAL: never tap approval dialogs — the one titled 「Shelly MCPサーバー: 承認が必要です」 or any offering 許可 / 常に許可 / 許可しない. Stop and report instead.
- Shelly's own MCP server settings are NOT under the gear panel's 連携 → "MCP Servers" row; that screen manages outbound MCP client servers (Start/Stop), a different feature.
- Shelly's own MCP server switches live in the "shelly config" modal: command palette (magnifier icon) → "Settings" → scroll to section エージェント.
- In that section, never tap 「MCPトークンを再発行」 or 「MCPトークンをコピー」.
- Toggling any MCP switch restarts Shelly's MCP server; a brief restart is normal.
- Main layout: top bar + left sidebar (☰) + up to 4 panes + bottom bar. Top-bar icons: cloud-download = Updates, magnifier = command palette, history = recent logs, gear = quick Settings panel.
- Terminal sessions are named shelly-1…shelly-4. A terminal pane's key bar (above the keyboard) has real Ctrl+C, Tab, ↑, ↓, Paste, Alt, Enter buttons — use them, not key chords.
- Updates screen (cloud-download icon) shows 「Shelly は最新です」 when current, or 「更新があります」 with current vs available version lines.
- If a dialog mentions 署名鍵 / バックアップ / アンインストール (signing-key migration), stop and report — never automate it.
