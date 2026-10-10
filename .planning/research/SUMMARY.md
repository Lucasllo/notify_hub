# Project Research Summary

**Project:** Notify Hub
**Domain:** Multichannel notification service (simulated email/SMS/webhook) on RabbitMQ TTL+DLX retry/DLQ, MongoDB, Resilience4j, Java 21 virtual threads, Prometheus/Grafana. Backend portfolio piece, about 1 week.
**Researched:** 2026-10-09
**Confidence:** MEDIUM-HIGH (versions HIGH; RabbitMQ 4.x x-death and VT pinning behaviour MEDIUM)

## Executive Summary

Notify Hub is an async "accept, then deliver" service. Experts build it with the database as the source of truth and the broker as a carrier of attempt tokens. The API inserts a PENDING document, publishes with correlated publisher confirms plus mandatory returns, and replies 202. The worker claims each message with a single conditional `findAndModify`, renders the template, and calls a simulated adapter wrapped in a per-channel circuit breaker and semaphore bulkhead. Every outcome is written in one atomic update (`$set` status plus `$push` attempt). Retries use fixed-TTL delay queues per level (TTL encoded in the queue name), and a per-channel DLQ is the final parking lot. The reviewer cares about delivery reliability they can see: the lifecycle, the topology in the Rabbit UI, the attempt history, safe replay, and deterministic demos. Workflows, preferences and template tooling are anti-features here.

Recommendation: Spring Boot **3.5.16** (the last 3.x OSS release; the user requires 3.x; add a README note on the 4.x migration). Let the BOM manage Spring AMQP 3.2.12, Mongo driver 5.5.2, Micrometer, Testcontainers 1.21.4 and JMustache 1.16. Use Resilience4j **2.4.0** with `resilience4j-spring-boot3`, applied programmatically through `Decorators`. Use `Jackson2JsonMessageConverter` (the Jackson 2 / Spring AMQP 3.x name, not the Boot 4 Jackson 3 variants). Use **k6 2.3.0** in a compose profile for both the 1000-notification demo and the VT vs platform benchmark. Pin images: `rabbitmq:4.3.6-management`, `mongo:8.0.32`, `prom/prometheus:v3.13.4`, `grafana/grafana:12.4.12` (not 13).

The main risks are silent loss and silent misbehaviour, which would break the Core Value:
- the default listener requeue causes hot loops;
- unroutable publishes still get acked;
- acking before the retry publish is confirmed loses the message;
- check-then-act idempotency lets duplicates through;
- the idempotency guard can swallow reprocessed messages;
- Resilience4j defaults mean the breaker never opens in the demo;
- an unfair VT benchmark discredits the README.

The mitigations are concrete and listed below. Each needs a matching test. Deterministic failure injection ("magic recipients") is what keeps the retry, DLQ and reprocess integration test from being flaky.

## Key Findings

### Recommended Stack

Boot 3.5.16 on Java 21 (Temurin) with a Maven 3.9 wrapper and multi-module build (`notify-common` is a plain jar with no repackage). Sync Spring Data MongoDB, not reactive, because blocking on virtual threads is the point. Multi-stage Dockerfiles, so `docker compose up --build` works without a local JDK.

**Core technologies:**
- **Spring Boot 3.5.16 / Spring AMQP 3.2.12:** confirms, returns, `Declarables` topology, and VT-aware listener containers. Do not override BOM versions.
- **Spring Data MongoDB 4.5.13 + driver 5.5.2:** `MongoTemplate.findAndModify` for transitions. Use Mongo 8.0, not 9.0, which this driver has not been validated against.
- **Resilience4j 2.4.0 (`resilience4j-spring-boot3`):** one CB and one **semaphore** Bulkhead per channel. `ThreadPoolBulkhead` defeats virtual threads. Add `spring-boot-starter-aop` only if you use the annotations.
- **JMustache 1.16:** plain `Mustache.compiler()` with no `defaultValue`/`nullValue`, so a missing variable throws and is treated as permanent. Do not use `spring-boot-starter-mustache`.
- **Testcontainers 1.21.4 + `@ServiceConnection` + Awaitility:** 1.21.4 fixes the Docker Engine 29 handshake on Windows. Use the same image tags as compose.
- **Micrometer + Prometheus 3.13 LTS + Grafana 12.4 + `rabbitmq_prometheus` (:15692):** the worker needs `starter-web` on port 8081 so Prometheus can scrape it.
- **k6 2.3.0 (container, `profiles: [bench]`):** use `shared-iterations` for the demo and `constant-arrival-rate` (open model) for the benchmark.
- **springdoc 2.8.17 (optional):** only the 2.8.x line works with Boot 3.5.

RabbitMQ 4.3 traps: no `x-queue-mode: lazy` or CQv1 args, no transient non-exclusive queues, and no `global-qos`.

### Expected Features

**Must have (table stakes):**
- `POST /notifications` returns 202 plus `Location`. Errors use RFC 9457 Problem Details. `GET /{id}` includes embedded `attempts[]`. The paginated list is backed by ESR compound indexes.
- Explicit status state machine (PENDING, PROCESSING, SENT, RETRYING, FAILED, DEAD_LETTERED/exhausted, PUBLISH_FAILED) with an allowed-transitions table in common. Transitions are enforced with conditional updates.
- Worker idempotency through an atomic claim, plus **stuck-PROCESSING recovery** (lease or `redelivered` flag). Without recovery, "never lost silently" has a hole.
- Seeded Mustache templates with per-channel variants and `requiredVariables`, strict rendering, per-channel recipient validation, and renderer unit tests.
- Delay-level retry, a transient vs permanent classifier, and per-channel DLQ, with permanent failures also parked in the DLQ. Admin reprocess requires `X-API-Key` (constant-time compare), caps `max`, and validates the channel enum.
- Reprocess resets the counters but **keeps** `attempts[]` and appends a REPROCESSED event.
- Per-channel counters and timers with bounded tags, pre-registered at startup. The Grafana dashboard is provisioned. Queue depths come from the broker plugin.
- Compose with healthchecks, the 1000-notification k6 script, the Testcontainers retry, DLQ and reprocess IT, the VT benchmark, and a pt-BR README.
- **Deterministic failure injection** (magic recipients such as always-transient, always-permanent, flaky-N). This is the highest-ROI item and is required for a non-flaky IT.

**Should have (differentiators):**
- `Idempotency-Key` on POST (unique partial index plus payload hash). FEATURES rates it P1; ARCHITECTURE treats it as optional. See Gaps.
- An admin republish for PUBLISH_FAILED, which closes the no-outbox gap.
- `GET /notifications/stats` (aggregation), Swagger UI, correlation id in MDC and headers, a Mongo-backed DLQ peek, and a graceful-shutdown "kill the worker" demo.

**Defer (v2+):** outbox, real providers, scheduling, preferences, fan-out, digests, rate limiting, multi-tenancy, full auth, OTel tracing, DELIVERED receipts, per-message jitter, a batch endpoint, and priority queues.

### Architecture Approach

The API and the worker never call each other. They share only the AMQP contract and the Mongo schema, both defined in `notify-common` (enums, `NotificationMessage` record, header names, one `Declarables` topology bean, document classes, and an explicit `MongoIndexInitializer`, because `auto-index-creation` is off by default).

Topology: `notify.exchange` (topic, `notification.<ch>`), `notify.retry` (direct), `notify.dlx` (direct). Each channel gets 5 queues: main (DLX to DLQ), `retry.<ttl>` x3 (queue TTL, with DLX and an **explicit** routing key back to main), and `dlq`. Using separate exchanges avoids binding overlap.

The message payload is thin (id, channel, templateCode). The document is fat and holds recipient, variables and attempts, which keeps PII out of the broker. The attempt counter is an app header (`notify-retry-count`) plus `retryCount` in Mongo. **Never use x-death**: RabbitMQ 4.x does not interpret it on republish.

**Major components:**
1. **NotificationPublisher (api):** per-send `CorrelationData` future with a bounded timeout. Success means ack **and** `getReturned()==null`. On failure, a *conditional* update sets PENDING to PUBLISH_FAILED.
2. **DeliveryProcessor (worker):** the single place where outcomes are decided. The claim predicate is fenced by `retryCount`. Transient with levels left: publish the retry token (confirmed), then set RETRYING, then ack. Permanent or exhausted: set FAILED, then throw `AmqpRejectAndDontRequeueException` so the message goes to the DLQ.
3. **ChannelGateway:** `Decorators` with the CB outside and the Bulkhead inside. CB `recordExceptions` covers transient errors only; it ignores permanent errors and `BulkheadFullException`.
4. **FailureClassifier:** exhaustive mapping over a closed exception hierarchy. `CallNotPermitted` and `BulkheadFull` are transient. `MustacheException` is permanent. A Mongo timeout is transient. Anything unknown is rethrown, so it ends in the DLQ.
5. **DlqReprocessService (api):** `rabbitTemplate.execute` with `basicGet(autoAck=false)`, then Mongo reset, then confirmed republish, then `basicAck`. It stops at the first failure. Do **not** use `RabbitTemplate.receive()`, which auto-acks.
6. **Per-channel `@RabbitListener` containers:** prefetch about 10, `default-requeue-rejected=false`, listener retry disabled. Custom factories must go through the Boot configurer, or the VT executor is lost without warning.

Ack mode is reconciled as follows: AUTO ack with the confirm wait *inside* the listener is correct, because AUTO acks only after the method returns normally. Use that unless discuss-phase prefers MANUAL.

### Critical Pitfalls

1. **Hot requeue loop and competing in-memory retry.** Set `default-requeue-rejected=false` and keep `listener.simple.retry.enabled=false`. Add an IT that sends a poison message and checks it reaches the DLQ after fewer than K deliveries.
2. **Confirms that do not verify routing, or ack before the retry publish.** Use correlated confirms, returns and mandatory, and check `getReturned()`. In the worker, always publish, wait for the confirm, then ack. Add an IT that publishes to an unbound key and expects PUBLISH_FAILED.
3. **Check-then-act idempotency and `save()` lost updates.** Use only conditional `findAndModify` with `$set` and `$push` together. Add an IT that delivers the same id concurrently and expects exactly one send.
4. **Reprocess swallowed by the idempotency guard, or lost via auto-ack.** Reset Mongo *before* republishing, and use manual-ack `basicGet`. Add an IT covering FAILED, then reprocess, then SENT.
5. **Resilience4j defaults.** The default CB (`minimumNumberOfCalls` 100, 60s open) never opens in the demo, and the default bulkhead (25 calls, 0 wait) causes retry storms. Tune the CB (window 20, min 10, open time no longer than the first delay level) and size the bulkhead deliberately so that it does fire in the demo.
6. **Queue-argument 406 PRECONDITION_FAILED.** Put the TTL in the queue name, define the arguments in one place in common, and document `docker compose down -v`.
7. **Unfair VT benchmark.** Change one variable only, keep concurrency, Mongo pool, bulkhead and channel cache identical, use blocking `Thread.sleep` latency outside any lock, set the failure rate to 0, and use an open-model load. Sweep the concurrency level, warm up, run at least 3 times, and record JFR `jdk.VirtualThreadPinned` events. Report the amqp-client pinning on JDK 21 honestly.

## Implications for Roadmap

Coarse granularity: 5 phases. The build order follows the dependency chain in ARCHITECTURE.

### Phase 1: Foundation + API Ingest/Query
**Rationale:** Both apps import the shared contract and topology. Fixing queue arguments later costs 406 errors and queue deletion. The API comes first so messages piling up in `notify.<ch>` prove the contract.
**Delivers:** parent POM and wrapper; `notify-common` (enums, transition table, message record, headers, `Declarables`, documents, index initializer, configurable retry levels); compose with rabbit (management + prometheus plugins, dedicated user) and mongo, with healthchecks, pinned tags and `.gitattributes`; POST with confirms, returns and conditional PUBLISH_FAILED; GET by id; paginated list with ESR indexes; Problem Details; actuator.
**Addresses:** A1-A4, A7, B1, (A5 if adopted).
**Avoids:** Pitfalls 3, 6, 7, 17, 18, 19, 21, 25, 27.

### Phase 2: Worker Happy Path + Templates
**Rationale:** The claim and SENT transitions are the base that the retry and fail paths extend. The renderer is independently unit-testable.
**Delivers:** per-channel listeners (VT enabled, prefetch about 10, no requeue); full claim predicate including stuck-PROCESSING lease; TemplateSeeder (idempotent upsert); strict TemplateRenderer plus unit tests; simulated adapters (blocking latency, random failure rate, magic recipients); SENT transition with `$push` attempt and `$slice`; skeleton for metric hooks.
**Addresses:** A6, X10, C1, D1-D4, X1.
**Avoids:** Pitfalls 1, 2, 8, 11, 12, 15, 20.

### Phase 3: Failure Handling, Retry/DLQ, Resilience
**Rationale:** This is the core of the project. Classification, routing and resilience are intertwined. The classifier needs CB and bulkhead exceptions, and resilience tuning only makes sense alongside the delay ladder.
**Delivers:** exception hierarchy and table-tested classifier; RetryRouter (header counter, fresh `MessageProperties`, no x-death or `expiration` copied, confirm then ack); FAILED to DLQ via reject; ChannelGateway with demo-tuned CB and semaphore bulkhead per channel.
**Addresses:** E1, E2, B2, B3, Resilience4j requirement.
**Avoids:** Pitfalls 4, 5, 13, 16, 22.

### Phase 4: DLQ Reprocess + Integration Tests
**Rationale:** Reprocess needs a populated DLQ (Phase 3) and reuses the API publisher (Phase 1). The required IT covers Phases 3 and 4 together.
**Delivers:** `POST /admin/dlq/{ch}/reprocess?max=N` with the API-key filter; reset that keeps history; PUBLISH_FAILED republish (X3); Testcontainers ITs with short TTLs and Awaitility: retry, then DLQ, then reprocess, then SENT; poison message; unroutable publish leading to PUBLISH_FAILED; concurrent duplicate; index existence. Failsafe `*IT` keeps `mvn test` Docker-free.
**Addresses:** E3, E4, C2, X3, G3.
**Avoids:** Pitfalls 9, 10, 26.

### Phase 5: Observability, Demo, Benchmark, README
**Rationale:** Dashboards and benchmark numbers are only meaningful once behaviour is stable.
**Delivers:** pre-registered bounded metrics; Prometheus scrape config (service names); Grafana provisioning with a fixed datasource uid; panels for throughput, outcomes, retries by level, DLQ depth, p95 latency and CB state; k6 demo of 1000 notifications that prints a summary (optionally via `/stats`); fair VT vs platform benchmark with raw data and JFR; Dockerfiles for api and worker so one command brings everything up; pt-BR README with Mermaid diagrams, failure walkthrough, index justification with `explain()`, trade-offs (no outbox, no jitter, Boot 3.5 EOL, driver CVEs not reachable here).
**Addresses:** F1-F5, G1, G2, G4, X7, X8, plus P2 items as time allows.
**Avoids:** Pitfalls 14, 23, 24.

### Phase Ordering Rationale
- Contract and topology come before both apps. The API comes before the worker, the happy path before the failure paths, and the failure paths before reprocess and the IT. Visualisation and measurement come last.
- Configurable TTL levels and magic recipients must exist from Phases 1 and 2, because the Phase 4 IT depends on them.
- Metric hooks go in at the outcome points during Phases 2 and 3. Phase 5 only wires up dashboards.

### Research Flags

Need `/gsd-plan-phase --research-phase`:
- **Phase 3:** CB and bulkhead sizing against concurrency and the delay ladder; whether `CallNotPermitted` should use up a level. Verify the RabbitMQ 4.3 x-death and DLX behaviour with a test.
- **Phase 5 (benchmark):** needs a short design spike (concurrency sweep, metrics, k6 executor, JFR capture) before coding.

Standard patterns (skip research): Phase 1 (Boot multi-module, confirms, indexes), Phase 2 (listeners, findAndModify, JMustache), Phase 4 (fully specified in ARCHITECTURE Pattern 6).

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | Checked against Maven Central, the Boot 3.5.16 BOM and source, Docker Hub and endoflife.date. Grafana 13 breakage is MEDIUM (issue reports). |
| Features | HIGH | Scope driven by PROJECT.md. Competitor details MEDIUM. |
| Architecture | MEDIUM-HIGH | Standard Spring AMQP and RabbitMQ patterns. x-death on 4.x and VT listener specifics need an IT to confirm. |
| Pitfalls | MEDIUM-HIGH | Official docs for defaults. Pinning and x-death from issue trackers. |

**Overall confidence:** MEDIUM-HIGH

### Gaps to Address

- **"Exponential" delay ladder:** 5s/30s/2m is x6 then x4, which is escalating rather than strictly exponential. Decide between a factor-consistent ladder (for example 5s/25s/125s, x5) and keeping 5s/30s/2m and calling it "fixed-level escalating backoff" in the README. PROJECT.md says "backoff exponencial", so the README wording has to match whichever is chosen. Also consider a shorter demo profile (for example 2s/5s/10s) so reviewers do not wait 2.6 minutes per message.
- **JMustache HTML-escaping on webhook JSON (and SMS):** `{{x}}` HTML-escapes values, which corrupts JSON bodies (`"` becomes `&quot;`). Recommended fix: a per-variant compiler with `escapeHTML(false)` for WEBHOOK and SMS, escaping kept for EMAIL. Plain escape-off still allows JSON injection through a variable containing `"`, so either JSON-escape values for webhook or document the limitation. Cover all of this in the renderer unit tests. Also confirm the strict missing-variable default in a test.
- **Idempotency-Key on POST:** FEATURES says P1 and calls it a PROJECT.md gap; ARCHITECTURE says optional. Decide during requirements. Recommendation: include it (low cost; it pairs with the 503 PUBLISH_FAILED retry).
- **Publish-failure HTTP contract:** 503 plus Problem Detail plus `Retry-After` with status PUBLISH_FAILED (recommended), or 202 with status PUBLISH_FAILED. Confirm in discuss-phase.
- **Semantic validation location:** keep template and variable checks in the worker so the permanent-failure path can be demonstrated. The API checks structure, plus optionally recipient format. If the API rejects bad recipients, magic recipients must still pass API validation.
- **Status naming:** FAILED vs DEAD_LETTERED. FEATURES proposes two terminal states; ARCHITECTURE uses FAILED plus `lastError=RETRIES_EXHAUSTED`. Pick one in requirements. Recommendation: keep both statuses for clarity in the demo.
- **Exchange naming:** ARCHITECTURE uses `notify.exchange` and `notify.dlx`; PITFALLS uses `notify.events` and `notify.dlq`. Use ARCHITECTURE's names.
- **Benchmark target:** worker drain time, API arrival rate, or both. Decide in the Phase 5 spike.

## Sources

### Primary (HIGH confidence)
- Maven Central metadata; `spring-boot-dependencies-3.5.16.pom`; Spring Boot source at tag v3.5.16 (`RabbitAnnotationDrivenConfiguration`, `RabbitProperties`)
- Spring Boot 3.5 reference (virtual threads, AMQP); Spring AMQP template and CorrelationData docs
- RabbitMQ docs: DLX, confirms, quorum queues; v4.3.0 release notes
- Resilience4j docs (bulkhead and CB defaults); v2.4.0 release notes; k6 v2.0.0 release notes; endoflife.date; Docker Hub tag API
- PROJECT.md (scope authority)

### Secondary (MEDIUM confidence)
- rabbitmq-server #11331 and Spring AMQP #2688 (x-death on republish); rabbitmq-java-client #1276 (VT pinning)
- Grafana issues #123607 and #122663 (v13 provisioning); Testcontainers #11235 (Docker 29)
- Novu, Knock and Stripe idempotency and retry docs; JMustache README; MongoDB driver 4.11 VT notes; OSV CVE entries

### Tertiary (LOW confidence)
- `spring-boot-starter-mustache` environment collector behaviour; Twilio status values; osseva.io RabbitMQ 4.0 checklist

---
*Research completed: 2026-10-09*
*Ready for roadmap: yes*
