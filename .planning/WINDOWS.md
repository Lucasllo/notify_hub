---
schema_version: 1
open_count: 2
waived_count: 0
fixed_count: 1
total_count: 3
last_updated: 2026-11-02T00:10:09.525Z
---

# Broken Windows Ledger

> Cross-phase defect register. With `workflow.windows_enforce` enabled, `/gsd-ship` blocks while `open_count > 0`.
> Waive with `gsd-tools windows waive <id> "<reason>"` (reason required).
> Mark fixed with `gsd-tools windows fixed <id>`.

| id | phase | kind | file | line | description | status | reason | recorded_at | resolved_at |
|----|-------|------|------|------|-------------|--------|--------|-------------|-------------|
| 1 | 02 | unrun-verify | notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java |  | 02-08 Task 2 human-check: derrubar o worker entre o confirm do token h+1 e o ack e confirmar descarte da original reentregue (SingleNotificationStore prova só a regra do fence) | fixed |  | 2026-10-20T23:36:49.943Z | 2026-10-30T00:59:36.890Z |
| 2 | 04 | stub | docs/img/dashboard-k6.png |  | Screenshot docs/img/dashboard-k6.png ainda não capturada; README/docs/demo.md já linkam (checkpoint humano do fim da fase) | open |  | 2026-11-02T00:10:08.771Z |  |
| 3 | 04 | stub | docs/img/rabbitmq-filas.png |  | Screenshot docs/img/rabbitmq-filas.png ainda não capturada; README/docs/demo.md já linkam (checkpoint humano do fim da fase) | open |  | 2026-11-02T00:10:09.525Z |  |

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
  },
  {
    "id": 2,
    "kind": "stub",
    "phase": "04",
    "file": "docs/img/dashboard-k6.png",
    "line": null,
    "description": "Screenshot docs/img/dashboard-k6.png ainda não capturada; README/docs/demo.md já linkam (checkpoint humano do fim da fase)",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-11-02T00:10:08.771Z",
    "resolved_at": null,
    "milestone": null
  },
  {
    "id": 3,
    "kind": "stub",
    "phase": "04",
    "file": "docs/img/rabbitmq-filas.png",
    "line": null,
    "description": "Screenshot docs/img/rabbitmq-filas.png ainda não capturada; README/docs/demo.md já linkam (checkpoint humano do fim da fase)",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-11-02T00:10:09.525Z",
    "resolved_at": null,
    "milestone": null
  }
]
````
