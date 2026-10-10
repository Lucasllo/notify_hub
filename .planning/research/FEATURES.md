# Feature Research

**Domain:** Multichannel notification service (email / SMS / webhook, all simulated) on RabbitMQ + MongoDB. Backend portfolio project, about one week of work.
**Researched:** 2026-10-09
**Confidence:** HIGH for feature categorisation and scope (it follows PROJECT.md constraints and well-established product patterns). MEDIUM for specific product behaviour cited from Novu, Knock, and Stripe (official docs, retrieved via web search and not exhaustively verified).

---

## How to read this document

The "users" of this project are not end customers. They are **reviewers**: hiring managers, tech leads, and other engineers who clone the repo, run `docker compose up`, run the load script, and read the code. That changes what counts as table stakes:

- **Table stakes** = a reviewer who knows messaging systems will notice if this is missing and conclude the author doesn't understand the domain. Real products (Novu, Knock, Twilio, SendGrid, Courier) all have these in some form.
- **Differentiators** = cheap features that make the demo memorable or show senior-level judgement (determinism, operability, honest trade-offs). They must line up with the Core Value: *an accepted notification is never silently lost*.
- **Anti-features** = things real notification products have that would eat the one-week budget without showing anything new about messaging, NoSQL, resilience, or Java 21.

---

## Feature Landscape

### Table Stakes (reviewers expect these)

#### A. API surface (`notify-api`)

| # | Feature | Why Expected | Complexity | Notes |
|---|---------|--------------|------------|-------|
| A1 | `POST /notifications` returns **202 Accepted** with `id`, `status`, and a `Location` header | Every async notification API (Novu trigger, Knock workflow trigger, Twilio Messages) acknowledges acceptance, not delivery. Returning 200/201 as if the message were "sent" signals a sync mindset. | LOW | Body: `{channel, recipient, templateId, variables}`. Response: `{id, status: "PENDING", createdAt}`. If publish confirm fails, still return 202, but with `status: PUBLISH_FAILED`, or 503. **Decide explicitly** (see Dependency Notes). |
| A2 | Structural request validation with **RFC 9457 Problem Details** errors | Reviewers will POST garbage first. Spring Boot 3 has `ProblemDetail` built in, so there's no reason to return stack traces or ad-hoc error JSON. | LOW | Bean Validation: `channel` is an enum (EMAIL/SMS/WEBHOOK), `recipient` is not blank, `templateId` is not blank, `variables` is a map with a size cap (e.g. ≤50 keys, ≤4 KB of values). 400 for malformed JSON/enum, 422 for semantic violations. |
| A3 | `GET /notifications/{id}` with embedded attempt history | Knock/Novu expose message status plus delivery attempts per message. This is the main "proof" endpoint in the demo. | LOW | 404 as Problem Detail. Return `status`, `attemptCount`, `retryLevel`, `attempts[]`, `lastError`, timestamps (`createdAt`, `updatedAt`, `sentAt`/`failedAt`). |
| A4 | `GET /notifications?status=&channel=&page=&size=` paginated | Already in PROJECT.md. Operators need to find failed messages. | LOW | Spring Data `Pageable`, default sort `createdAt DESC`, cap `size` at 100. A compound index `{status:1, channel:1, createdAt:-1}` backs it (justify in README). Return a stable page envelope (`content`, `page`, `size`, `totalElements`), not raw Spring `Page` JSON, which changes between Boot versions. |
| A5 | **Idempotency-Key header on `POST /notifications`** | Stripe, Knock, and Novu all support this for create calls (Stripe and Knock: keys up to 255 chars, 24-hour replay window; Novu rejects reused keys with a different body). A notification service without client-side dedup sends duplicate SMS whenever a client retries on a timeout. **PROJECT.md only covers worker-side idempotency. This is a gap and should become a requirement.** | LOW–MEDIUM | Store `idempotencyKey` on the notification document with a **unique sparse index** (or partial index on existence). Same key + same payload: return the original notification (200 or 202 replay, add `Idempotent-Replayed: true`). Same key + different payload: 422 (or 409), detected by storing a SHA-256 hash of the canonical request. Handle the race with `DuplicateKeyException`: catch, re-read, replay. A TTL is optional (skip it; keys live as long as the document). |
| A6 | Worker-side idempotency (redelivery never double-sends) | Already in PROJECT.md. RabbitMQ is at-least-once, and a reviewer *will* ask "what happens on redelivery?" | MEDIUM | Note: "unique index on `notification_id`" alone does not prevent double sends, because `_id` is already unique. The real mechanism is an **atomic state-transition claim**: `findAndModify({_id, status ∈ {PENDING, RETRYING}} → PROCESSING)`. If it matches nothing and the status is already SENT/FAILED/DEAD_LETTERED, ack and skip. Document it as "at-least-once delivery + idempotent consumer". Never claim exactly-once. |
| A7 | Health/readiness endpoints (Actuator) | `docker compose` healthchecks and `depends_on: condition: service_healthy` need them. Reviewers expect `/actuator/health`. | LOW | Include Rabbit and Mongo health indicators. Expose `/actuator/prometheus`. |

#### B. Status lifecycle

| # | Feature | Why Expected | Complexity | Notes |
|---|---------|--------------|------------|-------|
| B1 | Explicit status state machine: `PENDING → PROCESSING → SENT`, with `RETRYING`, `FAILED`, `PUBLISH_FAILED`, `DEAD_LETTERED` | Every provider exposes a lifecycle (Twilio: queued/sending/sent/failed/delivered/undelivered; Knock: queued/sent/delivered/delivery_attempted/undelivered/not_sent/bounced). Without one, nothing is observable. | MEDIUM | Model it as an enum with an **allowed-transitions table** in `notify-common`, unit-tested. Enforce it with conditional updates (status precondition in the Mongo query), so a late redelivery can't overwrite `SENT` with `RETRYING`. |
| B2 | Clear terminal-state semantics | Reviewers will check whether `FAILED` and `DEAD_LETTERED` mean different things. | LOW | **Recommended semantics:** `FAILED` = permanent failure (invalid recipient, missing template, missing variable), never retried, attempts = 1. `DEAD_LETTERED` = transient failures exhausted all retry levels. `PUBLISH_FAILED` = the API could not get a broker confirm, so the message never reached the queue. `SENT` = the adapter accepted it (simulated, so there's no `DELIVERED`. Say so in the README). |
| B3 | Permanent failures are also parked in the per-channel DLQ (tagged), not just marked `FAILED` | Core Value: "never lost silently". PROJECT.md says permanent failures go "direto para FAILED/DLQ". Recommend **both**: status `FAILED` plus a DLQ message with header `x-failure-class: PERMANENT`. An operator can fix the root cause (e.g. seed the missing template) and reprocess. | LOW | Reprocess then works the same way for both classes. The status tells you the cause; the DLQ is the single parking lot. |

#### C. Attempt history

| # | Feature | Why Expected | Complexity | Notes |
|---|---------|--------------|------------|-------|
| C1 | Embedded `attempts[]` array per notification | Knock's `message.delivery_attempted` event carries attempt info, total attempts, and whether it will retry. Delivery attempt logs are standard in every provider dashboard. This is the NoSQL modelling showcase (embedding vs referencing). | LOW | Each entry: `attemptNumber`, `cycle` (reprocess generation), `startedAt`, `finishedAt`, `durationMs`, `outcome` (SUCCESS / TRANSIENT_FAILURE / PERMANENT_FAILURE), `errorCode`, `errorMessage` (truncated, e.g. 500 chars), `retryLevel` scheduled next (5s/30s/2m), `workerThread` (shows virtual-thread names in the demo). Append with `$push`, atomically in the same update as the status transition. |
| C2 | Reprocessing **resets the attempt counter but preserves history** | PROJECT.md says reprocess "zera as tentativas". If that wipes `attempts[]`, the audit trail is gone and "never lost silently" breaks. | LOW | Reset `attemptCount`/`retryLevel` and increment `cycle`. Keep `attempts[]` and add a `reprocessedAt[]` (or a history event). Bound the array: with 3 retry levels the max is ~4 entries per cycle, so it can't grow without limit unless someone reprocesses endlessly. Mention `$slice` as a guard. |

#### D. Template rendering

| # | Feature | Why Expected | Complexity | Notes |
|---|---------|--------------|------------|-------|
| D1 | Mustache templates stored in MongoDB, seeded at startup, with per-channel fields | Templates plus variables are the core abstraction of Novu/Knock/Courier. Already in PROJECT.md. | LOW | `templates` doc: `_id` (e.g. `order-shipped`), `channel`, `subject` (email only), `body`, `requiredVariables: [..]`, `version`. Seed idempotently (upsert) so restarts don't duplicate or fail. |
| D2 | **Strict variable validation** (a missing variable is a permanent failure) | A rendered SMS that says "Hello , your code is " is the classic notification bug. Reviewers look for it. | LOW | Two layers: (1) explicit check of the `requiredVariables` declared on the template against the request's `variables` keys, giving a clear error listing the missing names; (2) JMustache's default strict mode, which throws when a variable can't be resolved. **Do not** set `defaultValue("")`, and **do not use `spring-boot-starter-mustache`'s auto-configured compiler** in the worker. Its environment collector can resolve `{{...}}` from Spring properties. Build a plain `Mustache.compiler()`. Cache compiled templates (Caffeine or a `ConcurrentHashMap` keyed by `id:version`). |
| D3 | Per-channel recipient validation (permanent failure) | "Invalid recipient" is a named permanent-failure class in PROJECT.md. | LOW | EMAIL: basic address regex. SMS: E.164 (`^\+[1-9]\d{7,14}$`). WEBHOOK: absolute `http(s)` URL. Run it in the worker, with the adapter as the classifier, so the permanent path is demonstrable. |
| D4 | Unit tests for the renderer | Explicitly required. Cheap, and the first file a reviewer opens. | LOW | Cases: happy path, missing variable, extra variables ignored, HTML escaping (`{{x}}` vs `{{{x}}}`) for email, missing template. |

#### E. Retry / DLQ / admin operations

| # | Feature | Why Expected | Complexity | Notes |
|---|---------|--------------|------------|-------|
| E1 | Exponential backoff via per-level delay queues (TTL + DLX) | Core of the project. Knock retries "up to 8 times over 30 minutes with exponential back-off with jitter", so backoff is industry standard. | MEDIUM | Visible in the RabbitMQ management UI: `notify.email.retry.5s`, `.30s`, `.2m`, `notify.email.dlq`. Retry level travels in a message header (`x-retry-level`), with the DB as the source of truth. Note in the README that fixed-TTL levels can't add per-message jitter, a known trade-off for avoiding head-of-line blocking. |
| E2 | Transient vs permanent failure classification | Already in PROJECT.md. It is what separates a thoughtful retry design from "retry everything 3 times". | MEDIUM | A sealed exception hierarchy in `notify-common` (`TransientDeliveryException`, `PermanentDeliveryException`). Map Resilience4j `CallNotPermittedException` (circuit open) and `BulkheadFullException` to transient. |
| E3 | `POST /admin/dlq/{channel}/reprocess?max=N`, protected by `X-API-Key` | Already in PROJECT.md. Operators need a manual replay path. | MEDIUM | Return a summary: `{channel, requested: N, moved: k, skipped: [...]}`. Pull with `basicGet` in a loop up to `max`, update the DB (reset counters, set status PENDING, increment cycle), republish to the main exchange **with confirms**, and only then ack the DLQ message. Never ack before the republish is confirmed. Validate `channel` against the enum to prevent queue-name injection. Constant-time API key comparison. |
| E4 | Admin endpoints return 401 without the key and 403 with a wrong one, as Problem Details | Basic security hygiene reviewers check. | LOW | A simple `OncePerRequestFilter` on `/admin/**`. Key from an env var with a demo default in compose. |

#### F. Observability

| # | Feature | Why Expected | Complexity | Notes |
|---|---------|--------------|------------|-------|
| F1 | Per-channel Micrometer counters: sent, failed, retried, dead-lettered | Already in PROJECT.md. | LOW | Suggested names: `notify_notifications_published_total{channel}`, `notify_notifications_publish_failed_total{channel}`, `notify_deliveries_total{channel,outcome}`, `notify_retries_total{channel,level}`, `notify_dead_lettered_total{channel}`, `notify_dlq_reprocessed_total{channel}`. **Never tag by recipient, notificationId, or error message** (cardinality explosion). Tag by `errorCode` only from a bounded enum. |
| F2 | Latency timers with histograms | "How fast?" is the second question after "does it work?". The VT benchmark also needs it. | LOW | `notify_adapter_duration_seconds{channel}` (simulated provider latency) and `notify_end_to_end_seconds{channel}` (createdAt to SENT). Enable `publishPercentileHistogram` so Grafana can show p95/p99. |
| F3 | Provisioned Grafana dashboard plus Prometheus in compose | Already in PROJECT.md. This is the visual payoff of the demo. | MEDIUM | Panels: throughput by channel, outcomes stacked, retries by level, DLQ size, p95 latency, circuit breaker state. Provision via files (`grafana/provisioning/...`) so nobody has to click anything. |
| F4 | Queue-depth visibility | You can't show backpressure or DLQ growth without queue metrics. | LOW | Enable the `rabbitmq_prometheus` plugin (ships with RabbitMQ 3.8+) and scrape it. No custom code needed. |
| F5 | Resilience4j metrics (circuit breaker state, bulkhead available calls) | Showing the breaker open under load is the most convincing resilience moment of the demo. | LOW | `resilience4j-micrometer` binds these automatically with the Spring Boot 3 starter. |

#### G. Demo, test, and benchmark

| # | Feature | Why Expected | Complexity | Notes |
|---|---------|--------------|------------|-------|
| G1 | One-command environment: `docker compose up` (API, worker, Rabbit, Mongo, Prometheus, Grafana) | Hard constraint in PROJECT.md. A reviewer won't install anything by hand. | MEDIUM | Healthchecks plus `depends_on: service_healthy`. Pre-built multi-stage Dockerfiles, so Maven doesn't need to be installed on the host. |
| G2 | Load script that fires 1000 notifications and prints a status summary | Already in PROJECT.md. | LOW | Mixed channels, a configurable share of intentionally bad requests. At the end, poll until no PENDING/PROCESSING/RETRYING remain (with a timeout) and print counts per status/channel plus the Grafana URL. Pick one tool: **k6** if it also does the benchmark, otherwise a bash+curl or small Java script. Prefer a k6 container in compose (`profiles: [demo]`) so the host needs nothing. |
| G3 | Testcontainers integration test: retry → DLQ → reprocess | Explicitly required. The single strongest correctness proof. | HIGH | Requires **deterministic failure injection** (see X1). Random failure rates make this test flaky. Override TTLs to milliseconds in the test profile. Use Awaitility for async assertions. |
| G4 | Virtual threads vs platform threads benchmark, results in README | Explicitly required. | MEDIUM | VTs only win when work **blocks**. Make the simulated adapter latency a real blocking sleep (e.g. 200–500 ms), keep concurrency identical, and switch only `spring.threads.virtual.enabled` (plus the listener container's task executor). Report throughput, p95, and thread count, with the hardware stated. Be honest if the difference is small at low concurrency. |

---

### Differentiators (cheap ways to stand out)

These all line up with the Core Value (never lost silently, observable, reproducible). Ranked by value-to-cost.

| # | Feature | Value Proposition | Complexity | Notes |
|---|---------|-------------------|------------|-------|
| X1 | **Deterministic failure injection ("magic recipients")** | Makes the demo and the integration tests reproducible. Without it, retry → DLQ can only be shown statistically. Real providers do this too: Twilio test credentials use magic numbers. | LOW | E.g. `fail-transient@demo.test` always times out, `fail-permanent@demo.test` is always an invalid recipient, `flaky-2@demo.test` fails twice then succeeds (keyed by attempt number). Combines with the configurable random failure rate (PROJECT.md) for the load test. **Highest-ROI item in this list.** |
| X2 | `GET /notifications/stats` (counts by status × channel via Mongo aggregation) | Lets the demo script print a summary table without Grafana. Also shows aggregation pipelines (a NoSQL skill beyond CRUD). | LOW | Uses the `{status, channel}` index prefix. One `$group` stage. |
| X3 | Admin republish for `PUBLISH_FAILED`: `POST /admin/notifications/{id}/republish` (or `POST /admin/publish-failed/republish?max=N`) | Closes the gap the no-outbox decision leaves open. Without it, `PUBLISH_FAILED` is a dead end, which contradicts "never lost silently". Makes the trade-off look deliberate. | LOW | Reuses the same publish-with-confirm code. The README can say: "we chose confirms + manual recovery instead of an outbox; here is the recovery path." |
| X4 | DLQ peek: `GET /admin/dlq/{channel}?limit=N` (read without consuming) | Operators inspect before replaying. Pairs naturally with reprocess. | LOW–MEDIUM | Simplest route: query Mongo for `status ∈ {FAILED, DEAD_LETTERED}` and `channel`, which is the source of truth. That avoids a fiddly AMQP peek (basicGet + nack/requeue reorders messages). Also report queue depth from `RabbitAdmin.getQueueInfo`. |
| X5 | Correlation/trace ID propagated API → AMQP headers → worker logs | One `grep` follows a notification end to end. Shows production thinking. | LOW | Put the `notificationId` and `X-Request-Id` in MDC, and in message headers on publish and re-publish. Structured JSON logs (Spring Boot 3.4+ has built-in structured logging) are a bonus. Full OpenTelemetry tracing is optional (see anti-features). |
| X6 | OpenAPI / Swagger UI (springdoc) | A reviewer can try the API from the browser in 10 seconds. | LOW | Document the Idempotency-Key header, the 202 semantics, and the error schema. |
| X7 | Architecture + topology diagram and a "failure walkthrough" in the README (pt-BR) | Reviewers read before running. A sequence diagram for "transient failure → 5s → 30s → 2m → DLQ → reprocess → SENT" communicates mastery faster than code. | LOW | Mermaid renders natively on GitHub. Include screenshots of the Rabbit topology and the Grafana dashboard. |
| X8 | Documented trade-offs section ("O que ficou de fora e por quê") | Turns scope limits (no outbox, no jitter, simulated delivery, no DELIVERED status) into evidence of judgement. | LOW | Directly reuses PROJECT.md's Out of Scope list. |
| X9 | Graceful shutdown: in-flight messages finish or get requeued | Restarting the worker mid-load without losing or duplicating messages is a great live demo ("kill the worker, nothing is lost"). | LOW | `server.shutdown=graceful` plus the listener container's shutdown timeout. Manual ack mode, so un-acked messages are redelivered and caught by the idempotent claim (A6). |
| X10 | Recovering stuck `PROCESSING` | If the worker dies after the claim but before the ack, redelivery finds the status `PROCESSING` and a naive claim skips it, so the message is lost silently. | MEDIUM | Allow the claim if `status = PROCESSING AND claimedAt < now - leaseTimeout`, or include `PROCESSING` in claimable states on redelivery (`redelivered` flag). **Arguably table stakes for correctness**. Flag it for the worker phase. |

---

### Anti-Features (deliberately NOT built)

| Feature | Why Requested | Why Problematic (here) | Alternative |
|---------|---------------|------------------------|-------------|
| Real provider integrations (SMTP, SES, Twilio, SendGrid) | "Make it real" | Needs credentials, makes the demo non-reproducible, and moves the focus from messaging to vendor SDKs. Already out of scope. | Simulated adapters behind a `ChannelSender` port. Mention in the README that a real adapter is a drop-in. Optional: a webhook adapter that POSTs to a local mock container. Only worth it if time remains. |
| UI / admin dashboard / in-app inbox | Visual appeal | A frontend project inside a backend portfolio, and a week isn't enough. | Grafana + RabbitMQ Management UI + Swagger UI **are** the UI. |
| Scheduled / delayed sends (`sendAt`) | Every SaaS has it | Needs a scheduler or delayed-message plugin, and it gets confused with retry delay queues. Doubles the time-related test surface. | Out of scope. Mention as a future evolution. |
| User/subscriber management, preferences, opt-out, quiet hours | Core of Novu/Knock | That is a different product (preference center). Needs a user model, per-channel preference resolution, and so on. Shows nothing about resilience. | The request carries an explicit `recipient`. No subscriber concept. |
| Multi-step workflows, digest/batching, fan-out to multiple channels per request | Novu/Knock headline features | Workflow engines are a project of their own. Fan-out complicates the status model (one request, N statuses). | One request = one notification = one channel. Clients send N requests for N channels. |
| Template CRUD API, versioning UI, i18n/locales, layouts/partials | "Complete" template management | Already out of scope. Validation and caching logic multiplies. | Seeded templates with a `version` field. Changing a template means editing the seed file. |
| Delivery receipts / status webhooks back to the client (`DELIVERED`, `BOUNCED`, `OPENED`) | Real providers do it | Nothing real exists to deliver to, so it would be fake on top of fake. Callbacks also need their own retry/DLQ system (recursion of scope). | `SENT` is the final success state. The client polls `GET /notifications/{id}`. Document it. |
| Outbox pattern / CDC / change streams for publishing | "Correct" dual-write | Already out of scope by decision. Adds a poller or Debezium. | Publisher confirms + `PUBLISH_FAILED` + admin republish (X3) + an explicit README section. |
| `rabbitmq_delayed_message_exchange` plugin | Simpler per-message delay | Already out of scope. Not native, and it hides the topology the demo wants to show. | TTL + DLX per level. |
| Full auth (JWT, OAuth2, users/roles), multi-tenancy, per-tenant API keys | "Production-ready" | Large surface, no messaging value. Tenancy touches every query and index. | A single API key on `/admin/**`. Public endpoints stay open, documented as a demo decision. |
| Rate limiting / quotas per client or recipient | Real providers throttle | Needs a distributed counter (Redis) or a per-instance approximation. Muddies the benchmark. | Bulkhead per channel (already planned) as the backpressure mechanism. Mention rate limiting as an evolution. |
| Priority queues / per-message priority | "Urgent OTP vs marketing" | `x-max-priority` interacts badly with the retry topology and isn't covered by the benchmark. | Separate queues per channel already isolate traffic classes. |
| Bulk endpoint (`POST /notifications/batch`) | Faster load test | Partial-failure semantics (207 Multi-Status, per-item idempotency) add a surprising amount of design. | The load script sends individual requests concurrently, which also exercises the API more realistically. |
| Kafka / multiple broker support / broker abstraction layer | "Pluggable" | Over-engineering. The point is to show RabbitMQ mastery specifically. | Spring AMQP directly. Topology constants in `notify-common`. |
| Full distributed tracing stack (Tempo/Jaeger + OTel collector) | Observability completeness | Two more containers and cross-module context propagation to debug inside a one-week budget. | Correlation ID in MDC and headers (X5). Mention Micrometer Tracing as an easy next step. |
| PII encryption, retention/TTL purge, GDPR erasure endpoints | Compliance realism | Real concerns, but none of them shows the targeted skills. | One README line: "dados fictícios. Em produção: criptografia e retenção". |
| Per-message jitter / dynamic backoff computation | "Better" backoff | Conflicts with the fixed-level TTL design (per-message TTL in a shared queue leads to head-of-line blocking, which is exactly what the design avoids). | Fixed levels. Document the trade-off. |

---

## Feature Dependencies

```
notify-common: Channel enum, NotificationStatus + transition table, event DTO, topology constants, failure exception hierarchy
    └──required by──> everything below

Mongo model (notifications + templates, indexes)
    ├──required by──> A1 POST (persist PENDING) ──> A5 Idempotency-Key (unique sparse index)
    ├──required by──> A3 GET by id ──> C1 attempts[] (embedded)
    ├──required by──> A4 list ──> X2 stats aggregation
    └──required by──> D1 template seed ──> D2 strict rendering ──> D4 renderer unit tests

RabbitMQ topology (exchange, per-channel queues, delay levels, DLQs)
    ├──required by──> A1 publish with confirms ──> PUBLISH_FAILED ──> X3 republish
    └──required by──> worker consumer
                          ├──requires──> A6 atomic claim (idempotent consumer) ──> X10 stuck-PROCESSING recovery
                          ├──requires──> E2 failure classification
                          │                   ├──requires──> D2/D3 (permanent sources)
                          │                   └──requires──> Resilience4j CB + bulkhead (transient sources)
                          ├──requires──> E1 delay-level routing ──> B3 DLQ parking (+ DEAD_LETTERED)
                          └──requires──> C1 attempt append (same atomic update as status change)

E1 + B3 ──required by──> E3 DLQ reprocess ──requires──> C2 counter reset preserving history
E3 ──enhanced by──> X4 DLQ peek
X1 deterministic failure injection ──required by──> G3 Testcontainers retry→DLQ→reprocess test (non-flaky)
X1 ──enhances──> G2 demo script (predictable final counts)
F1/F2 metrics ──required by──> F3 Grafana dashboard ──required by──> G2 demo "show the metrics"
F2 adapter latency timer + blocking simulated latency ──required by──> G4 VT benchmark
A7 health ──required by──> G1 compose healthchecks

CONFLICTS:
  per-message jitter  ──conflicts──> fixed-TTL delay queues (head-of-line blocking)
  API-side template/variable validation (fail-fast 422) ──conflicts──> demonstrating the worker's permanent-failure path
  wiping attempts[] on reprocess ──conflicts──> Core Value (audit trail lost)
  spring-boot-starter-mustache auto-config ──conflicts──> strict variable validation (environment collector)
```

### Dependency Notes

- **`notify-common` first.** The status enum and transition table, the channel enum, the event DTO, and the topology names are shared contracts. Getting them wrong forces changes in both modules.
- **A5 (API idempotency) needs the Mongo model and index before the POST handler is final.** It's cheap if designed in from day one and awkward to retrofit (it changes the POST flow to insert, catch duplicate, then replay).
- **Semantic validation location (decision needed).** Validating template existence and required variables in the API (422 fail-fast) is better product behaviour, but then the worker's "template missing / variable missing → permanent failure" path, which PROJECT.md lists explicitly, can't be triggered by the demo. **Recommendation:** the API validates structure only (A2), and the worker does semantic validation (D2/D3) and classifies it as permanent. Fail-fast API validation can come in v1.x behind a flag.
- **Publish-failure response (decision needed).** On a negative or timed-out confirm, return either `202 + status PUBLISH_FAILED` (honest: the record exists and is recoverable through X3) or `503` (the client retries, and with A5 the retry is safe). **Recommendation:** 503 with Problem Detail + `Retry-After`, keep the doc as `PUBLISH_FAILED`, and let an Idempotency-Key retry re-attempt the publish for that existing doc. This makes A5 and X3 reinforce each other.
- **X1 (deterministic failures) is a hard prerequisite for G3.** Build it alongside the simulated adapters, not as an afterthought.
- **C1 attempt append and the status transition must be one atomic Mongo update.** Otherwise a crash between them leaves history and status inconsistent.
- **E3 reprocess ordering:** DB update → republish with confirm → ack the DLQ message. Any other order can lose or duplicate.
- **G4 depends on adapter latency being blocking.** If the simulated latency is non-blocking (reactive delay or scheduled executor), VTs show no advantage and the benchmark is meaningless.

---

## MVP Definition

### Launch With (v1, the one-week deliverable)

- [ ] `notify-common` contracts: Channel, NotificationStatus + transition table, event DTO, topology constants, failure hierarchy. *Everything depends on these.*
- [ ] A1–A4, A7: POST (202) / GET by id / paginated list / health, with Problem Details. *Core API surface.*
- [ ] **A5 Idempotency-Key on POST.** *A gap in PROJECT.md. Cheap and expected. Add it as a requirement.*
- [ ] B1–B3 status lifecycle with enforced transitions and clear terminal semantics.
- [ ] C1–C2 embedded attempt history, preserved across reprocess.
- [ ] D1–D4 seeded Mustache templates, strict variables, recipient validation, renderer unit tests.
- [ ] A6 + X10 idempotent claim including stuck-PROCESSING recovery. *Without X10, "never lost silently" has a hole.*
- [ ] E1–E4 delay-level retry, failure classification, DLQ reprocess with API key.
- [ ] Resilience4j CB + bulkhead per channel (PROJECT.md).
- [ ] **X1 deterministic failure injection.** *Prerequisite for a non-flaky G3 and a predictable demo.*
- [ ] F1–F5 metrics, provisioned Grafana, rabbitmq_prometheus.
- [ ] G1–G4 compose, 1000-notification script, Testcontainers test, VT benchmark.
- [ ] X3 PUBLISH_FAILED republish. *Closes the no-outbox gap. About 1 hour of work.*
- [ ] X7 + X8 README (pt-BR) with diagrams, failure walkthrough, trade-offs.

### Add After Validation (v1.x, if time remains in the week)

- [ ] X2 `/notifications/stats`. *Add when writing the demo script's summary output.*
- [ ] X6 springdoc OpenAPI. *Add once the API shape is stable.*
- [ ] X5 correlation ID in MDC and headers, structured logs.
- [ ] X4 DLQ peek endpoint (Mongo-backed).
- [ ] X9 graceful-shutdown "kill the worker" demo step.
- [ ] API-side fail-fast template validation behind a flag.

### Future Consideration (v2+, mention only in the README "Evoluções" section)

- [ ] Transactional outbox. *Removes PUBLISH_FAILED as a manual-recovery state.*
- [ ] Real provider adapters / local mock SMTP (MailHog/Mailpit) / webhook receiver container.
- [ ] Scheduling (`sendAt`), user preferences, fan-out, digests.
- [ ] Rate limiting, multi-tenancy, full auth.
- [ ] OpenTelemetry tracing.
- [ ] Delivery receipts / `DELIVERED` state via provider callbacks.

---

## Feature Prioritization Matrix

"Value" here means value to a reviewer evaluating backend competence.

| Feature | Reviewer Value | Implementation Cost | Priority |
|---------|----------------|---------------------|----------|
| Status state machine + enforced transitions (B1–B3) | HIGH | MEDIUM | P1 |
| Delay-level retry + DLQ + classification (E1, E2) | HIGH | MEDIUM | P1 |
| Idempotent consumer claim (A6) + stuck PROCESSING recovery (X10) | HIGH | MEDIUM | P1 |
| Embedded attempt history (C1, C2) | HIGH | LOW | P1 |
| DLQ reprocess admin (E3, E4) | HIGH | MEDIUM | P1 |
| Testcontainers retry→DLQ→reprocess (G3) | HIGH | HIGH | P1 |
| Deterministic failure injection (X1) | HIGH | LOW | P1 |
| Idempotency-Key on POST (A5) | HIGH | LOW–MEDIUM | P1 |
| POST/GET/list + Problem Details (A1–A4) | MEDIUM | LOW | P1 |
| Strict template rendering + tests (D1–D4) | MEDIUM | LOW | P1 |
| Metrics + Grafana + rabbit queue metrics (F1–F5) | HIGH | MEDIUM | P1 |
| Compose + load script (G1, G2) | HIGH | MEDIUM | P1 |
| VT vs platform benchmark (G4) | MEDIUM | MEDIUM | P1 (required) |
| PUBLISH_FAILED republish (X3) | MEDIUM | LOW | P1 |
| README diagrams + trade-offs (X7, X8) | HIGH | LOW | P1 |
| Stats aggregation endpoint (X2) | MEDIUM | LOW | P2 |
| OpenAPI / Swagger UI (X6) | MEDIUM | LOW | P2 |
| Correlation ID propagation (X5) | MEDIUM | LOW | P2 |
| DLQ peek (X4) | LOW–MEDIUM | LOW–MEDIUM | P2 |
| Graceful shutdown demo (X9) | MEDIUM | LOW | P2 |
| Outbox, scheduling, preferences, real providers, UI | LOW (for this goal) | HIGH | P3 / never |

**Priority key:**
- P1: Must have for the one-week deliverable
- P2: Should have, add when possible
- P3: Future consideration, README mention only

---

## Competitor Feature Analysis

| Feature | Novu | Knock | Twilio / SendGrid | Notify Hub (our approach) |
|---------|------|-------|-------------------|---------------------------|
| Create-call idempotency | `Idempotency-Key` header. Replay returns the cached response, and a reused key with a different body is rejected. `transactionId` is *not* a dedup key. | `Idempotency-Key` on workflow trigger, ≤255 chars, 24 h, caches only 2xx | n/a (Stripe-style keys are the industry reference: ≤255 chars, ≥24 h, param mismatch → error) | `Idempotency-Key` on POST, unique sparse index, payload hash, mismatch → 422 |
| Status model | Notification entity tracks jobs, messages, and per-channel delivery status | queued, sent, delivered, delivery_attempted, undelivered, not_sent, bounced | queued, sending, sent, failed, delivered, undelivered | PENDING, PROCESSING, SENT, RETRYING, FAILED, DEAD_LETTERED, PUBLISH_FAILED (no DELIVERED: simulated) |
| Retries | Failed step retried without re-running earlier steps | Up to 8 retries over 30 min, exponential backoff with jitter | Provider-internal | 3 fixed levels (5s/30s/2m) via TTL+DLX, transient only. No jitter (documented) |
| Attempt visibility | Activity feed / execution log | `delivery_attempted` event with attempt count and will-retry flag | Event webhooks / logs | Embedded `attempts[]` per notification, exposed in GET |
| Permanent failure handling | Step marked failed | `message.undelivered` = permanent, not retried | `failed`/`undelivered` with error codes | `FAILED` immediately + parked in DLQ tagged PERMANENT |
| Manual replay | Limited / dashboard | Limited | Re-send via API | `POST /admin/dlq/{channel}/reprocess?max=N` + PUBLISH_FAILED republish |
| Templates | Full editor, versions, layouts, i18n | Full editor, Liquid | Dynamic templates (Handlebars) | Seeded Mustache in Mongo, `requiredVariables`, strict render |
| Preferences / subscribers / workflows / digests | Core features | Core features | Partial | **Deliberately excluded** |
| Test mode | Dev environment | Dev environment | Magic test numbers/credentials | Magic recipients for deterministic failures (X1) |

**Takeaway:** the SaaS products compete on workflows, preferences, and template tooling, which are all anti-features here. Notify Hub should compete on **visible delivery reliability**: lifecycle rigour, retry topology you can see in the Rabbit UI, attempt history, safe replay, and determinism. That is exactly what the Core Value asks for.

---

## Sources

- Novu: Idempotency (API reference): https://docs.novu.co/api-reference/idempotency. MEDIUM.
- Novu: Notification event concept: https://docs.novu.co/platform/concepts/notification-event. MEDIUM.
- Novu: How Novu works: https://docs.novu.co/platform/how-novu-works. MEDIUM.
- Novu `transactionId` vs `Idempotency-Key` (community PR): https://github.com/Practitionist/familiarise_web/pull/2059. LOW.
- Knock: API reference overview (idempotency, 255 chars, 24 h, 2xx-only cache): https://docs.knock.app/api-reference/overview. MEDIUM.
- Knock: Delivering notifications (retries: 8 over 30 min, exponential backoff + jitter): https://docs.knock.app/send-notifications/delivering-notifications. MEDIUM.
- Knock: Webhook event types (`message.delivery_attempted`, `message.undelivered`): https://docs.knock.app/developer-tools/outbound-webhooks/event-types. MEDIUM.
- Stripe: Idempotent requests: https://docs.stripe.com/api/idempotent_requests and https://stripe.com/docs/idempotency. MEDIUM (official; 409 status not specified by Stripe).
- Stripe: API v2 overview (30-day idempotency window in v2): https://docs.stripe.com/api-v2-overview. MEDIUM.
- JMustache README / Compiler API (strict-by-default missing variables, `defaultValue`, `nullValue`, `strictSections`): https://github.com/samskivert/jmustache, http://samskivert.github.io/jmustache/apidocs/com/samskivert/mustache/Mustache.Compiler.html. MEDIUM.
- Twilio message status values (queued/sending/sent/failed/delivered/undelivered): training knowledge, LOW, not re-verified in this session.
- Spring Boot `spring-boot-starter-mustache` environment collector behaviour: training knowledge, LOW. Verify during the template phase.
- PROJECT.md (Notify Hub), primary scope authority. HIGH.

---
*Feature research for: multichannel notification service (simulated delivery) on RabbitMQ + MongoDB*
*Researched: 2026-10-09*
