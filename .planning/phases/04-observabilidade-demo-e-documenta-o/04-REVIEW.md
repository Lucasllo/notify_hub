---
phase: 04-observabilidade-demo-e-documenta-o
reviewed: 2026-11-02T00:20:19Z
depth: standard
files_reviewed: 33
files_reviewed_list:
  - .dockerignore
  - docker-compose.bench.yml
  - docker-compose.yml
  - docs/arquitetura.md
  - docs/benchmark.md
  - docs/demo.md
  - docs/modelagem-mongo.md
  - docs/trade-offs.md
  - k6/bench-backlog.js
  - k6/demo.js
  - notify-api/Dockerfile
  - notify-api/pom.xml
  - notify-api/src/main/java/dev/notifyhub/api/application/NotificationRepublisher.java
  - notify-api/src/main/java/dev/notifyhub/api/metrics/PublishMetrics.java
  - notify-api/src/main/resources/application.yml
  - notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java
  - notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java
  - notify-api/src/test/java/dev/notifyhub/api/application/UnconfirmedPublishSweeperTest.java
  - notify-api/src/test/java/dev/notifyhub/api/metrics/PublishMetricsTest.java
  - notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java
  - notify-api/src/test/java/dev/notifyhub/api/web/NotificationValidationTest.java
  - notify-worker/Dockerfile
  - observability/grafana/dashboards/notify-hub.json
  - observability/grafana/provisioning/dashboards/notify-hub.yaml
  - observability/grafana/provisioning/datasources/prometheus.yaml
  - observability/prometheus/prometheus.yml
  - README.md
  - scripts/bench/drain-stats.js
  - scripts/bench/run-bench.sh
  - scripts/explain-indices.js
  - scripts/smoke-fase1.sh
  - scripts/smoke-fase2.sh
  - scripts/smoke-fase4.sh
findings:
  critical: 0
  warning: 4
  info: 11
  total: 15
status: issues_found
---

# Phase 4: Code Review Report

**Reviewed:** 2026-11-02T00:20:19Z
**Depth:** standard
**Files Reviewed:** 33
**Status:** issues_found

## Summary

I reviewed the Phase 4 changes: the new publish-outcome counter (`PublishMetrics`) and how `NotificationRepublisher` uses it, actuator and Prometheus exposure, the Dockerfiles and compose stack (demo and bench override), Prometheus and Grafana provisioning, the dashboard JSON, the k6 scripts, the benchmark harness, the smoke scripts and the pt-BR docs.

To check the dashboard and docs, I compared them with the code they depend on:
- the worker's `DeliveryMetrics` meter names and tags (`notify_delivery_*`, with the histogram on `notify.delivery.duration`);
- queue naming in `Channel` and the `queue_coarse_metrics` family;
- the transitions in `NotificationStatus`;
- the index definitions in `MongoIndexInitializer`;
- the env-var bindings in the worker's `application.yml`.

Every PromQL selector matches a meter or label the apps actually emit. The state diagram and index tables in the docs match the code.

I found no blockers. The main problems:
1. The new `publish_failed/unconfirmed` series also counts notifications whose publish the broker did confirm, so the API's publish metrics double-count.
2. The README and demo walkthrough link screenshots that do not exist in the repository.
3. The k6 demo summary can declare a round drained too early when an earlier interrupted round leaves non-terminal documents behind.
4. The PowerShell instructions in the demo produce a broken JSON body on Windows PowerShell 5.1.

## Warnings

### WR-01: `publish_failed/unconfirmed` counts publishes the broker confirmed, so the publish counter double-counts

**File:** `notify-api/src/main/java/dev/notifyhub/api/application/NotificationRepublisher.java:316-330` (also `notify-api/src/main/java/dev/notifyhub/api/metrics/PublishMetrics.java:24-25`, `observability/grafana/dashboards/notify-hub.json:472`)

**Issue:** `markUnconfirmedAsPublishFailed` selects every document with `status = PENDING`, `claimedAt = null` and `updatedAt < cutoff`, then calls `metrics.unconfirmed(marked)`. Nothing records whether the broker acked the publish. A notification that `publishOrMarkFailed` already counted as `confirmed/none` but that no worker has claimed for longer than `after` (2 min by default) is caught by the sweep and counted again, as `publish_failed/unconfirmed`. This happens when the worker is stopped, restarting, or behind on a backlog.

So the counter ends up with two outcomes for one publish, and the "failure" one is wrong. The Javadoc ("PENDING sem confirm levado a PUBLISH_FAILED pela varredura") and the dashboard both present this series as an unconfirmed publish, which misleads anyone reading the panel during a worker outage. `docker-compose.bench.yml:23-25` raises the sweep cutoff to 1h for exactly this reason, which shows the false positive is a known real case.

**Fix:** Either record the ack and exclude it from the sweep, or rename the series so it stops claiming the publish was unconfirmed. For example, persist the confirm and exclude it:
```java
// publishOrMarkFailed, after result.ok():
mongoTemplate.updateFirst(query(where(Fields.ID).is(doc.id()).and(Fields.STATUS).is(NotificationStatus.PENDING)),
        new Update().set(Fields.PUBLISH_CONFIRMED_AT, Instant.now()), NotificationDocument.class);
// markUnconfirmedAsPublishFailed filter:
.and(Fields.PUBLISH_CONFIRMED_AT).is(null)
```
At minimum, rename the reason (for example `unclaimed_timeout`) and state in `PublishMetrics`, `docs/arquitetura.md` and the panel description that the series overlaps with `confirmed/none`.

### WR-02: README and demo walkthrough link screenshots that are not in the repository

**File:** `README.md:81`, `docs/demo.md:72`, `docs/demo.md:86`

**Issue:** The docs reference `docs/img/dashboard-k6.png` and `docs/img/rabbitmq-filas.png`, but `docs/img/` only contains `.gitkeep` (checked with `git ls-files docs/img`). The README is the project's front page, and these render as broken images on GitHub.

**Fix:** Capture and commit both PNGs (Grafana dashboard during `docker compose --profile bench run --rm k6`, and the RabbitMQ Queues tab), or remove the image lines until they exist.

### WR-03: k6 demo summary can report "drained" too early when an earlier round left non-terminal documents

**File:** `k6/demo.js:123-127`, `k6/demo.js:142-150`

**Issue:** `nonTerminalOfRound` computes `Math.max(0, s.byStatus[k] - base.byStatus[k])` from the global `/notifications/stats`. Suppose a previous k6 run was interrupted (Ctrl+C) with 5 notifications still RETRYING. Those 5 finish during the new round while 5 of the new round are still RETRYING. The delta is then `5 - 5 = 0`, and two polls in a row satisfy `zeroStreak >= 2`. The summary then:
- prints `PENDING=0 PROCESSING=0 RETRYING=0` while work from this round is still in flight;
- counts the old round's terminal outcomes as this round's.

`scripts/smoke-fase4.sh:183` greps exactly that line, so the smoke can pass on an undrained round. The clamp only protects against leftovers that never resolve; it hides leftovers that do resolve.

**Fix:** Track this round's own documents instead of global deltas. Record the `id` of every 202 response (pass them to `teardown` through `setup` data or a `SharedArray`, or log them) and poll their statuses. Alternatively, add a `createdAfter` filter to `/notifications/stats` and pass the timestamp taken in `setup()`. As a cheap guard, fail when any delta is negative, because that means the baseline was not stable:
```js
for (const k of NON_TERMINAL) {
  const d = s.byStatus[k] - base.byStatus[k];
  if (d < 0) throw new Error(`linha de base instável: ${k} caiu ${-d} (rodada anterior ainda drenando)`);
}
```

### WR-04: PowerShell instructions in the demo produce invalid JSON on Windows PowerShell 5.1

**File:** `docs/demo.md:5`, `docs/demo.md:104-106`

**Issue:** The guide tells PowerShell users to call `curl.exe`, and the examples pass the body as `-d '{"channel":"EMAIL",...}'`. Windows PowerShell 5.1, the default on Windows 10/11, strips embedded double quotes when it passes arguments to native executables. `curl.exe` then receives `{channel:EMAIL,...}` and the API answers 400. The project author and the stated audience use Windows, so this breaks step 6 of the walkthrough for them.

**Fix:** State that the JSON examples need Git Bash or PowerShell 7.3+. Alternatively, give a PowerShell-native variant:
```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/notifications -ContentType 'application/json' `
  -Body '{"channel":"EMAIL","recipient":"always-transient@fail.test","templateCode":"welcome","variables":{"name":"Ana"}}'
```
or escape the quotes for curl.exe on 5.1 (`-d '{\"channel\":\"EMAIL\",...}'`).

## Info

### IN-01: Dashboard description says `confirmed/claimed` comes from the sweep

**File:** `observability/grafana/dashboards/notify-hub.json:472`

**Issue:** The description reads "reason claimed quando veio da varredura". In the code, `confirmed/claimed` comes from `publishOrMarkFailed` when the re-read shows the worker already claimed the notification (`NotificationRepublisher.java:196-200`). The sweep only produces `publish_failed/unconfirmed`.

**Fix:** "reason claimed quando o confirm não chegou mas a releitura mostrou o claim do worker".

### IN-02: "Aceitas" counts idempotent replays, and the publish panel does not match "todo POST aceito"

**File:** `observability/grafana/dashboards/notify-hub.json:45`, `:77`; `docs/demo.md:70`

**Issue:** A replay with the same `Idempotency-Key` also returns 202. It may publish nothing (notification already claimed) or publish a second time (republish of a PENDING notification). So "Aceitas" overstates new notifications. The statement in `demo.md` that every accepted POST shows up as `confirmed` is false for replays and for the excluded DLQ reprocess path. The k6 demo sends no replays, so the effect is limited to manual traffic.

**Fix:** Say in the panel description that replays are included. In `demo.md`, write "todo POST novo aceito".

### IN-03: "Taxa de sucesso" shows NaN before the first terminal outcome

**File:** `observability/grafana/dashboards/notify-hub.json:143`

**Issue:** The series are pre-registered at 0, so on a fresh stack the expression evaluates `0/0 = NaN`. The stat panel shows "NaN", which contradicts the README's claim that the dashboard never shows "No data" (`README.md:90`).

**Fix:** Wrap the denominator, for example `... / clamp_min(sum(increase(...[$__range])), 1)`, or set a `noValue`/value mapping for NaN.

### IN-04: The application.yml comment says the port listens only on loopback, but `java -jar` binds 0.0.0.0

**File:** `notify-api/src/main/resources/application.yml:45-48`

**Issue:** `server.address` is not set, so the jar runs started by `smoke-fase1.sh` and `smoke-fase2.sh` listen on every interface. That exposes the unauthenticated `/actuator/prometheus` (and the rest of the API) on the LAN. Only the compose port mappings are bound to `127.0.0.1`.

**Fix:** Correct the comment. Optionally, have the smoke scripts pass `--server.address=127.0.0.1`.

### IN-05: README offers `admin/admin` "para editar", but the provisioned dashboard cannot be saved

**File:** `README.md:74`, `docs/demo.md:25`

**Issue:** `observability/grafana/provisioning/dashboards/notify-hub.yaml` sets `allowUiUpdates: false`, and the dashboard JSON sets `"editable": false`. An admin can only "Save as" a copy.

**Fix:** "admin/admin para salvar uma cópia editável".

### IN-06: `docs/arquitetura.md` leaves out the `unknown_failure` outcome of the duration timer

**File:** `docs/arquitetura.md:144`

**Issue:** `DeliveryMetrics` registers `success|transient_failure|permanent_failure|unknown_failure`, but the table lists only the first three.

**Fix:** Add `unknown_failure` to the tag list.

### IN-07: Three different statements of the calibration target

**File:** `scripts/bench/run-bench.sh:69-70`, `scripts/bench/run-bench.sh:252`, `docs/benchmark.md:128`

**Issue:**
- The header comment cites "~480 msg/s ... janela medida de mais de 60s", but the measured rates were 508–929 msg/s with windows of 32–59 s.
- The calibration code targets about 30 s.
- `benchmark.md` says "passar de 20s".

**Fix:** Align the three on the value the code actually computes, and update the stale msg/s figure.

### IN-08: `drain-stats.js` overstates throughput by one completion when `warmup=0`

**File:** `scripts/bench/drain-stats.js:257-258`

**Issue:** With `W=0`, the window starts at `sent[0]` but `medidas = total`. Only `total - 1` completions happen after the window opens. With `W>0` the fencepost is correct. `run-bench.sh` never passes 0, but the script accepts it.

**Fix:** `const medidas = W > 0 ? total - W : total - 1;`

### IN-09: Silent exits under `set -e` in run-bench

**File:** `scripts/bench/run-bench.sh:163`, `:291`, `:303-304`

**Issue:**
- `"${COMPOSE[@]}" stop worker >/dev/null 2>&1` has no `||` handler. If it fails, the script exits with no message after a long run.
- On line 303, when the first `jfr summary` attempt fails, its error text is captured into `JFR_SUMMARY` before the fallback output is appended. This is harmless for the awk parse, but the logged summary is confusing.

**Fix:** Add `|| falhou "..."` to the stop calls. Capture each attempt separately, for example `JFR_SUMMARY=$(...) || JFR_SUMMARY=$(...)`.

### IN-10: `-DskipTests` still compiles tests and resolves test dependencies inside the image build

**File:** `notify-api/Dockerfile:23-24`, `notify-worker/Dockerfile:64-65`

**Issue:** The image build compiles test sources and downloads Testcontainers and the other test-scope dependencies. This makes the first `docker compose up --build` slower, and a test-only compile error breaks the demo image.

**Fix:** Use `-Dmaven.test.skip=true`, if no module consumes a test-jar.

### IN-11: Running smoke-fase1 in the default profile adds retry queues to the demo broker volume

**File:** `scripts/smoke-fase1.sh:210-220`

**Issue:** Since Phase 4, the smoke stops the compose `api`/`worker` and reuses the same `rabbitmq-data` volume. Without `--perfil-demo`, the local jars declare `retry.125s` for each channel next to the demo ladder. Afterwards the demo shows 18 queues instead of the 15 that `docs/demo.md:76` and `docs/arquitetura.md:75` describe, and the "Filas de delay" panel shows the extra queues.

**Fix:** Mention `docker compose down -v` (or `--perfil-demo`) in the smoke header and in demo.md troubleshooting, or run smoke 1 in a separate compose project (`-p notify-smoke`).

---

_Reviewed: 2026-11-02T00:20:19Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
