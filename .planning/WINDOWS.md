---
schema_version: 1
open_count: 0
waived_count: 0
fixed_count: 1
total_count: 1
last_updated: 2026-10-30T00:59:36.890Z
---

# Broken Windows Ledger

> Cross-phase defect register. With `workflow.windows_enforce` enabled, `/gsd-ship` blocks while `open_count > 0`.
> Waive with `gsd-tools windows waive <id> "<reason>"` (reason required).
> Mark fixed with `gsd-tools windows fixed <id>`.

| id | phase | kind | file | line | description | status | reason | recorded_at | resolved_at |
|----|-------|------|------|------|-------------|--------|--------|-------------|-------------|
| 1 | 02 | unrun-verify | notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java |  | 02-08 Task 2 human-check: derrubar o worker entre o confirm do token h+1 e o ack e confirmar descarte da original reentregue (SingleNotificationStore prova só a regra do fence) | fixed |  | 2026-10-20T23:36:49.943Z | 2026-10-30T00:59:36.890Z |

````json
[
  {
    "id": 1,
    "kind": "unrun-verify",
    "phase": "02",
    "file": "notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java",
    "line": null,
    "description": "02-08 Task 2 human-check: derrubar o worker entre o confirm do token h+1 e o ack e confirmar descarte da original reentregue (SingleNotificationStore prova só a regra do fence)",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-20T23:36:49.943Z",
    "resolved_at": "2026-10-30T00:59:36.890Z",
    "milestone": null
  }
]
````
