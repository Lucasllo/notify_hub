# Architecture Research

**Domain:** Multichannel notification service (async messaging, retry/DLQ, NoSQL persistence) - Notify Hub
**Researched:** 2026-10-09
**Confidence:** MEDIUM overall. The design rules are standard RabbitMQ/Spring AMQP practice and were checked against the official rabbitmq.com and docs.spring.io pages. Version-sensitive facts (x-death in RabbitMQ 4.x, virtual-thread listener behaviour) come from release notes and issue trackers. The GSD seam rates these LOW because they were retrieved by WebSearch/WebFetch, so the phase plans need to check them with a test.

---

## Standard Architecture

### System Overview

```
                    HTTP (JSON)                                   Prometheus scrape
  client / k6 ─────────────────────┐                     ┌──────────────────────────────┐
                                   v                     │                              │
┌──────────────────────────────────────────────────┐     │   ┌────────────────────────┐  │
│ notify-api  (Spring MVC, Tomcat)                 │     │   │ notify-worker          │  │
│                                                  │     │   │ (listener containers,  │  │
│  NotificationController   AdminDlqController     │     │   │  virtual threads)      │  │
│        │                    │ (X-API-Key filter) │     │   │                        │  │
│        v                    v                    │     │   │  ChannelListener x3    │  │
│  NotificationService   DlqReprocessService       │     │   │        │               │  │
│        │      │             │       │            │     │   │        v               │  │
│        │      └──> NotificationPublisher <──┘    │     │   │  DeliveryProcessor     │  │
│        │           (confirms + returns)          │     │   │   ├ NotificationState  │  │
│        v                    │                    │     │   │   │  (findAndModify)   │  │
│  NotificationRepository     │                    │     │   │   ├ TemplateRenderer   │  │
└────────┼────────────────────┼────────────────────┘     │   │   ├ ChannelGateway     │  │
         │                    │ AMQP publish             │   │   │  (CB + Bulkhead)   │  │
         │                    v                          │   │   │   └ SimulatedAdapter│ │
         │   ┌──────────────────────────────────────┐    │   │   ├ FailureClassifier  │  │
         │   │ RabbitMQ                             │    │   │   ├ RetryRouter ───────┼──┼─> notify.retry
         │   │ notify.exchange (topic)              │────┼──>│   └ DeliveryMetrics    │  │
         │   │  ├ notify.email   ─┐                 │ consume │                        │  │
         │   │  ├ notify.sms      ├─ main queues    │    │   └───────────┬────────────┘  │
         │   │  └ notify.webhook ─┘ (DLX→notify.dlx)│    │               │               │
         │   │ notify.retry (direct)                │    │               │               │
         │   │  └ notify.<ch>.retry.<ttl> x3 levels │    │               │               │
         │   │     (TTL, DLX→notify.exchange)       │    │               │               │
         │   │ notify.dlx (direct)                  │    │               │               │
         │   │  └ notify.<ch>.dlq                   │    │               │               │
         │   └──────────────────────────────────────┘    │               │               │
         v                                               │               v               │
   ┌───────────────────────────────────────────────────────────────────────────┐        │
   │ MongoDB  db=notify                                                        │        │
   │   notifications (embedded attempts[])     templates (embedded variants)   │        │
   └───────────────────────────────────────────────────────────────────────────┘        │
                                                                                         │
   Prometheus  <── /actuator/prometheus (api, worker) + rabbitmq_prometheus :15692 ──────┘
   Grafana     <── provisioned datasource + dashboard JSON
```

**Direction of dependencies:** the API and the worker never call each other. They share two things: the AMQP topology/message contract and the MongoDB `notifications` collection, both defined in `notify-common`.

### Component Responsibilities

| Component | Module | Owns | Talks to | Typical implementation |
|-----------|--------|------|----------|------------------------|
| `Channel`, `NotificationStatus`, `RetryLevel`, header names | common | Shared vocabulary | everyone | Java enums and constants |
| `NotificationMessage` | common | AMQP payload contract | API (produce), worker (consume) | Java `record` + `Jackson2JsonMessageConverter` |
| `NotifyTopology` | common | All exchanges, queues and bindings, with their arguments | RabbitAdmin in both apps | `@Bean Declarables` built in a loop over `Channel` x `RetryLevel` |
| `NotificationDocument`, `TemplateDocument`, `MongoIndexInitializer` | common | Collection schema + indexes | API, worker | Spring Data `@Document`; indexes created explicitly via `IndexOperations` |
| `NotificationController` | api | `POST/GET /notifications` | NotificationService, query service | `@RestController`, Bean Validation |
| `NotificationService` | api | Create flow: persist PENDING, then publish | Repository, NotificationPublisher | Plain service |
| `NotificationPublisher` | api | Confirmed publish (ack/nack/returned/timeout) | RabbitTemplate | `CorrelationData` future with a bounded `get(timeout)` |
| `NotificationQueryService` | api | Paginated list + detail | MongoTemplate | `Query` + `Pageable`, uses the compound index |
| `AdminDlqController` + `ApiKeyFilter` | api | `POST /admin/dlq/{channel}/reprocess?max=N` | DlqReprocessService | `OncePerRequestFilter` on `/admin/**`, constant-time compare |
| `DlqReprocessService` | api | Drain the DLQ in a bounded loop, reset retry state, republish | RabbitTemplate (`execute` + `basicGet`), MongoTemplate | Manual ack per message |
| `ChannelListener` (x3) | worker | One listener container per channel queue | DeliveryProcessor | `@RabbitListener(queues=..., concurrency=...)` per channel |
| `DeliveryProcessor` | worker | Runs one delivery attempt end to end | everything below | Plain orchestrator; the only place that decides the outcome |
| `NotificationStateService` | worker | Idempotent state transitions (claim, success, retry, fail) | MongoTemplate | `findAndModify` with a conditional filter |
| `TemplateRenderer` | worker | Template lookup + Mustache render + variable validation | TemplateRepository | JMustache, compiled-template cache keyed by `(id, version)`; pure enough to unit test |
| `TemplateSeeder` | worker | Idempotent upsert of seed templates at startup | MongoTemplate | `ApplicationRunner`, upsert by `_id` |
| `ChannelGateway` | worker | Resilience decoration per channel | CB/Bulkhead registries, adapters | Resilience4j `Decorators`, one CB and one Bulkhead instance per channel name |
| `EmailAdapter`/`SmsAdapter`/`WebhookAdapter` | worker | Simulated sending (latency, random failures, magic recipients) | nothing | Implement `ChannelAdapter`; config under `notify.simulation.<channel>.*` |
| `FailureClassifier` | worker | Exception mapped to `TRANSIENT` or `PERMANENT` | - | Pure function over an exception hierarchy |
| `RetryRouter` | worker | Picks the next retry level, or reports retries exhausted; republishes with confirm | RabbitTemplate | Header-based attempt counter |
| `DeliveryMetrics` | worker | Counters and timers per channel | MeterRegistry | Micrometer; called only by DeliveryProcessor |

---

## Recommended Project Structure

```
notify-hub/
├── pom.xml                                # parent: Boot BOM, Java 21, plugin mgmt, modules
├── docker-compose.yml                     # rabbitmq (management + prometheus plugins), mongo, api, worker, prometheus, grafana
├── infra/
│   ├── prometheus/prometheus.yml          # scrape api, worker, rabbitmq:15692
│   └── grafana/provisioning/{datasources,dashboards}/  # + notify-hub.json
├── scripts/load-test.js (k6) | load.sh    # 1000 notifications + mix of failing recipients
├── notify-common/src/main/java/.../common/
│   ├── domain/          Channel, NotificationStatus, FailureType, AttemptOutcome
│   ├── messaging/       NotificationMessage, NotifyHeaders, NotifyTopology, RetryLevel, TopologyProperties
│   └── persistence/     NotificationDocument, AttemptEntry, TemplateDocument, MongoIndexInitializer
├── notify-api/src/main/java/.../api/
│   ├── web/             NotificationController, AdminDlqController, ApiKeyFilter, ApiExceptionHandler, dto/
│   ├── application/     NotificationService, NotificationQueryService, DlqReprocessService
│   └── messaging/       NotificationPublisher, PublishResult
└── notify-worker/src/main/java/.../worker/
    ├── listener/        EmailListener, SmsListener, WebhookListener (thin: extract headers, delegate)
    ├── processing/      DeliveryProcessor, NotificationStateService, ClaimResult
    ├── template/        TemplateRenderer, TemplateRepository, TemplateSeeder, seed/*.json
    ├── channel/         ChannelAdapter, ChannelGateway, simulated/{Email,Sms,Webhook}Adapter, SimulationProperties
    ├── failure/         FailureClassifier, TransientDeliveryException, PermanentDeliveryException (+ subtypes)
    ├── retry/           RetryRouter
    └── metrics/         DeliveryMetrics
```

### Structure Rationale

- **notify-common is a "shared kernel", not a utility bag.** It holds exactly what both apps must agree on byte-for-byte: queue names and arguments, header names, the message record, enums, and the Mongo document schema plus indexes. If the API and the worker declared queues with different arguments, RabbitMQ would refuse the second declaration with `406 PRECONDITION_FAILED`. One shared `Declarables` bean rules that out. Keep Spring Boot auto-configuration out of common. It only exposes `@Configuration` classes that each app imports explicitly.
- **Thin listeners, fat processor.** Listener methods only read `NotificationMessage` plus the `notify-retry-count` header and the `redelivered` flag, then call `DeliveryProcessor.process(...)`. That way the processor can be tested without a broker.
- **One package per decision.** Failure classification, retry routing, state transitions and metrics are separate. Each answers one question, so the PITFALLS and test phases can target each one directly.

---

## Architectural Patterns

### Pattern 1: Topic exchange + per-channel queues + fixed-TTL delay queues per level + per-channel DLQ

**What:** Three exchanges, five queues per channel (15 queues in total), all declared in code.

| Exchange | Type | Purpose |
|----------|------|---------|
| `notify.exchange` | topic | Main entry. Routing key `notification.<channel>` (`notification.email`, ...) |
| `notify.retry` | direct | Worker republishes failed messages here with routing key `<channel>.retry.<level>` |
| `notify.dlx` | direct | Dead-letter target of the main queues. Routing key `<channel>.dlq` |

| Queue (per channel) | Arguments | Bound to |
|---------------------|-----------|----------|
| `notify.<ch>` | `x-dead-letter-exchange=notify.dlx`, `x-dead-letter-routing-key=<ch>.dlq` | `notify.exchange` / `notification.<ch>` |
| `notify.<ch>.retry.5s` | `x-message-ttl=5000`, `x-dead-letter-exchange=notify.exchange`, `x-dead-letter-routing-key=notification.<ch>` | `notify.retry` / `<ch>.retry.1` |
| `notify.<ch>.retry.30s` | same, ttl 30000 | `notify.retry` / `<ch>.retry.2` |
| `notify.<ch>.retry.2m` | same, ttl 120000 | `notify.retry` / `<ch>.retry.3` |
| `notify.<ch>.dlq` | none (no TTL, no DLX) | `notify.dlx` / `<ch>.dlq` |

**Why this shape:**
- A **queue-level** TTL (`x-message-ttl`) is the same for every message in the queue, so the head always expires first and there is no head-of-line blocking. Per-message TTL in a shared queue has that problem. This is the reason for the key decision in PROJECT.md.
- **Per-channel retry queues** (rather than three shared ones) keep the RabbitMQ panel readable during the demo: when the SMS breaker opens you see `notify.sms.retry.*` fill up while email stays empty. It also keeps blast radius and purges per channel. 9 delay queues cost nothing.
- **Main queue DLX → DLQ** makes the broker the safety net. Any rejected message (`AmqpRejectAndDontRequeueException`, or an unexpected exception when `default-requeue-rejected=false`) ends up in the channel DLQ, never in a hot requeue loop and never dropped. Moving to the DLQ is therefore an atomic broker operation, with no publish-then-ack window.
- **Topic** for the main exchange (instead of direct) lets you later add an audit/analytics queue bound to `notification.#` without touching producers. If you add such a binding, retried messages will dead-letter back through `notify.exchange` and reach it again. Bind it to a narrower key or accept that.
- **Put the TTL in the queue name** (`retry.5s`). Queue arguments are immutable. Changing a TTL under the same name causes a `406 PRECONDITION_FAILED` at startup, while a new TTL under a new name just creates a new queue. Make the levels configurable (`notify.retry.levels=5s,30s,2m`) so the Testcontainers test can use `200ms,400ms,800ms`. The full production ladder takes 2.6 minutes per message to exhaust.
- Name the delay ladder honestly: 5s → 30s → 2m is a roughly geometric ladder (x6, x4). If the README says "exponential", consider 5s → 25s → 2m5s (x5) or document it as "fixed-level escalating backoff".

**Example:**
```java
@Configuration
public class NotifyTopology {
  @Bean
  Declarables notifyDeclarables(TopologyProperties props) {
    List<Declarable> d = new ArrayList<>();
    TopicExchange main = ExchangeBuilder.topicExchange(MAIN_EXCHANGE).durable(true).build();
    DirectExchange retry = ExchangeBuilder.directExchange(RETRY_EXCHANGE).durable(true).build();
    DirectExchange dlx   = ExchangeBuilder.directExchange(DLX_EXCHANGE).durable(true).build();
    d.addAll(List.of(main, retry, dlx));
    for (Channel ch : Channel.values()) {
      Queue q = QueueBuilder.durable(ch.queue())
          .deadLetterExchange(DLX_EXCHANGE).deadLetterRoutingKey(ch.dlqKey()).build();
      Queue dlq = QueueBuilder.durable(ch.dlq()).build();
      d.addAll(List.of(q, dlq,
          BindingBuilder.bind(q).to(main).with(ch.routingKey()),
          BindingBuilder.bind(dlq).to(dlx).with(ch.dlqKey())));
      for (RetryLevel lvl : props.levels()) {             // ordinal 1..n, ttl
        Queue delay = QueueBuilder.durable(ch.retryQueue(lvl))  // notify.email.retry.5s
            .ttl((int) lvl.ttl().toMillis())
            .deadLetterExchange(MAIN_EXCHANGE).deadLetterRoutingKey(ch.routingKey()).build();
        d.add(delay);
        d.add(BindingBuilder.bind(delay).to(retry).with(ch.retryKey(lvl)));   // email.retry.1
      }
    }
    return new Declarables(d);
  }
}
```
`RabbitAdmin` (auto-configured by Boot) declares these when the first connection opens and again after every reconnect. Both apps importing the same bean is idempotent.

**Queue type:** use **classic durable** queues for the single-node demo. Quorum queues are the production default for RabbitMQ 4.x. If you switch, note that since 4.0 quorum queues have a default `delivery-limit` of 20, after which messages are dead-lettered with reason `delivery_limit`. Keep the queue type a single property in `TopologyProperties`.

**Trade-offs:** More queues than the plugin approach, but everything is native, visible and testable. Dead-lettering from the delay queue back to `notify.exchange` is broker-internal and at-most-once. If the DLX routing key does not match any binding (a typo), **the broker drops the message silently**. The integration test must exercise every level.

### Pattern 2: Header-based attempt counter + republish to the level queue (not x-death)

**What:** The worker owns the attempt counter in a custom header `notify-retry-count` (0 on first publish). On a transient failure it publishes a **new** message to `notify.retry` with routing key `<ch>.retry.<n+1>` and header `n+1`, waits for the publisher confirm, then lets the original be acked.

**Why not x-death:**
1. In this topology the main queue → delay queue hop is a client publish, not a dead-letter. Each level is a different queue, so every `x-death` entry has `count=1` and cannot express "attempt number".
2. RabbitMQ 4.x **no longer interprets client-supplied `x-death`** on (re)publish (4.0.1 release notes). Counts that used to accumulate across republishes now reset. The RabbitMQ notes and Spring AMQP issue #2688 both recommend an application-owned header instead.
3. The main queue has one DLX, so a single reject cannot choose among three levels. Reject-based routing is used only for the terminal DLQ hop.

**Rules:**
- Build fresh `MessageProperties` on every republish. Copy only business headers (`notify-retry-count`, `notify-channel`, `notify-notification-id`) and the `messageId`. **Strip `x-death` and `x-first-death-*`/`x-last-death-*`.** That keeps headers small and avoids RabbitMQ 3.x cycle-detection surprises.
- `messageId = notificationId` on every publish (API, retry and reprocess), so logs and the panel correlate.
- The decision is a pure function: `next = current + 1; next <= levels.size() ? RETRY(level[next]) : EXHAUSTED`.

```java
public RetryDecision decide(int current) {
  int next = current + 1;
  return next <= levels.size() ? RetryDecision.retry(levels.get(next - 1), next)
                               : RetryDecision.exhausted();
}
```

### Pattern 3: Listener error semantics (AUTO ack + explicit exception types)

**What:** Use `acknowledge-mode: auto` and `default-requeue-rejected: false`. Keep Spring's stateful/stateless listener retry (`spring.rabbitmq.listener.simple.retry.enabled`) **disabled**: in-memory retries block the consumer thread and bypass the delay queues.

| Listener outcome | Container action | Broker result |
|------------------|------------------|---------------|
| Returns normally (sent, retry republished with confirm, or stale duplicate dropped) | ack | removed |
| `AmqpRejectAndDontRequeueException` (permanent failure or retries exhausted, after Mongo is set to FAILED) | nack, requeue=false | dead-lettered to `notify.<ch>.dlq` |
| Any unexpected exception (bug, Mongo down) | nack, requeue=false (because of `default-requeue-rejected=false`) | dead-lettered to DLQ, the safety net |
| `ImmediateRequeueAmqpException` (retry republish not confirmed, broker hiccup) | nack, requeue=true | redelivered with `redelivered=true` |

**Per-channel listener containers** (`@RabbitListener(queues = "notify.email", concurrency = "${notify.channels.email.concurrency}")`, one per channel). Do **not** use a single `@RabbitListener(queues = {email, sms, webhook})`. With the SimpleMessageListenerContainer each consumer thread serves all of its queues, so a slow SMS adapter would stall email. Set `prefetch` low (about 10, not the default 250), because unacked messages that sit behind slow adapters inflate redelivery storms.

### Pattern 4: Idempotent state machine with conditional `findAndModify`

**What:** The MongoDB document is the source of truth. A RabbitMQ message is a "token" that asks for one attempt. Every state transition is a single conditional `findAndModify`. If the filter doesn't match, the token is stale or a duplicate and is acked without sending.

**States:** `PENDING → PROCESSING → SENT` (terminal) | `→ RETRYING → PROCESSING ...` | `→ FAILED` (terminal while the message is in the DLQ). Plus `PUBLISH_FAILED` (API could not confirm the publish). The reprocess flow moves `FAILED → PENDING` (resets `retryCount` to 0, increments `reprocessCount`).

**Uniqueness:** `_id` is the notification id (UUID string generated by the API, also used as AMQP `messageId`). `_id` is MongoDB's built-in unique index, so it is the unique index the requirement asks for. The README should say so explicitly. The **conditional claim** is what actually blocks duplicate sends. Optional extra: an `idempotencyKey` field with a unique **partial** index, if you want API-level dedupe of client POST retries (out of scope by default).

**Claim predicate** (header `h = notify-retry-count`):
```
_id == id
AND status NOT IN [SENT, FAILED]
AND ( retryCount <  h                                           // a later-step token wins
   OR (retryCount == h AND status IN [PENDING, RETRYING, PUBLISH_FAILED])
   OR (retryCount == h AND status == PROCESSING
       AND (redelivered == true OR leaseUntil < now)) )         // previous holder died
→ $set { status: PROCESSING, retryCount: h, leaseUntil: now+30s, updatedAt: now }
```
```java
Query q = query(where("_id").is(id).and("status").nin(SENT, FAILED)
    .orOperator(
        where("retryCount").lt(h),
        where("retryCount").is(h).and("status").in(PENDING, RETRYING, PUBLISH_FAILED),
        redelivered ? where("retryCount").is(h).and("status").is(PROCESSING)
                    : where("retryCount").is(h).and("status").is(PROCESSING).and("leaseUntil").lt(now)));
Update u = new Update().set("status", PROCESSING).set("retryCount", h)
    .set("leaseUntil", now.plus(lease)).set("updatedAt", now);
NotificationDocument doc = mongo.findAndModify(q, u, options().returnNew(true), NotificationDocument.class);
if (doc == null) { metrics.duplicateSkipped(channel); return; }   // ack, do not send
```
`PUBLISH_FAILED` is claimable on purpose. It means "publish not confirmed" (for example a confirm timeout), not "definitely not routed". If the message did arrive, delivering it lets the record correct itself.

**Ordering rules (dual-write without an outbox):**

| Outcome | Order | Crash-window consequence |
|---------|-------|--------------------------|
| Success | send → `findAndModify(PROCESSING→SENT, push attempt)` → ack | Crash after send: redelivery, possible **duplicate send** (inherent at-least-once at the adapter boundary; document it) |
| Transient, retries left | **publish retry token (confirmed)** → `findAndModify(PROCESSING→RETRYING, retryCount=h+1, push attempt)` → ack | Crash after publish: two tokens. The later token wins via `retryCount < h`, the stale one is skipped. Nothing is lost. |
| Permanent / exhausted | `findAndModify(→FAILED, push attempt, lastError)` → throw `AmqpRejectAndDontRequeueException` | Crash before reject: redelivered, re-claimed, re-classified, same result |

Publishing the next token **before** writing the state that fences out the current token is what keeps "never lost silently" true. Writing state first and then failing to publish would leave a `RETRYING` document with no token anywhere.

### Pattern 5: Publisher confirms in the API (and in the worker's retry publish)

**What:** `spring.rabbitmq.publisher-confirm-type=correlated`, `spring.rabbitmq.publisher-returns=true`, `spring.rabbitmq.template.mandatory=true`. Each send carries its own `CorrelationData(notificationId)`. Wait on its `CompletableFuture<Confirm>` with a bounded timeout (for example 5s). Use the per-send future, not a template-wide `ConfirmCallback` (only one is allowed per template).

```java
public PublishResult publish(NotificationMessage msg) {
  CorrelationData cd = new CorrelationData(msg.notificationId());
  rabbit.convertAndSend(MAIN_EXCHANGE, msg.channel().routingKey(), msg, m -> {
    m.getMessageProperties().setMessageId(msg.notificationId());
    m.getMessageProperties().setHeader(NotifyHeaders.RETRY_COUNT, 0);
    m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    return m;
  }, cd);
  try {
    CorrelationData.Confirm c = cd.getFuture().get(confirmTimeout.toMillis(), MILLISECONDS);
    if (cd.getReturned() != null) return PublishResult.unroutable(cd.getReturned().getReplyText());
    return c.isAck() ? PublishResult.confirmed() : PublishResult.nacked(c.getReason());
  } catch (TimeoutException e) { return PublishResult.timeout(); }
}
```
**Critical detail:** an unroutable message with `mandatory=true` is **returned AND then acked**. `ack=true` alone does not prove delivery, so always check `getReturned()` (it is populated before the future completes).

**API create flow:** validate → `insert` PENDING (`_id=UUID`) → publish → on anything but a confirmed, routed publish, run a **conditional** update `{_id, status: PENDING} → PUBLISH_FAILED`. It has to be conditional because on a timeout the worker may already have claimed the document, and an unconditional update would overwrite `PROCESSING`/`SENT`. Response: `202 Accepted` + `Location` + `{id, status}` on confirm. On publish failure, `503` with the id and `PUBLISH_FAILED` so the client knows. Confirm this response contract in discuss-phase.

**API status after confirm:** do **not** write a `QUEUED` state after the ack. That would race the worker. `PENDING` means "accepted, awaiting worker".

### Pattern 6: DLQ reprocess (bounded basicGet loop with manual ack)

**What:** `POST /admin/dlq/{channel}/reprocess?max=N` drains up to N messages from `notify.<ch>.dlq` and puts them back in the main flow with retries reset.

**Do not use `RabbitTemplate.receive(queue)` here.** On a non-transacted channel it does `basicGet` with **autoAck**, so the message leaves the DLQ before you republish and is lost if the publish fails. Instead hold one channel through `rabbitTemplate.execute(ChannelCallback)`:

```java
return rabbit.execute(channel -> {
  int moved = 0, skipped = 0;
  for (int i = 0; i < max; i++) {
    GetResponse r = channel.basicGet(ch.dlq(), false);             // manual ack (ch = Channel enum)
    if (r == null) break;                                          // DLQ empty
    long tag = r.getEnvelope().getDeliveryTag();
    try {
      String id = r.getProps().getMessageId();
      ResetResult reset = state.resetForReprocess(id);             // FAILED|PROCESSING|PUBLISH_FAILED -> PENDING, retryCount=0,
                                                                   // reprocessCount++, push REPROCESSED event
      if (reset == ALREADY_SENT) { channel.basicAck(tag, false); skipped++; continue; }
      PublishResult p = publisher.publish(rebuild(r, /*retryCount*/0)); // fresh props, no x-death
      if (!p.ok()) { state.revertToFailed(id); channel.basicNack(tag, false, true); break; }
      channel.basicAck(tag, false); moved++;
    } catch (Exception e) { channel.basicNack(tag, false, true); break; }
  }
  return new ReprocessReport(moved, skipped);
});
```
- **Stop at the first failure.** A nack with requeue puts the message back at the head, so continuing would `basicGet` the same message again.
- Always ack or nack every fetched tag. Unacked `basicGet` deliveries on a cached channel stay invisible until that channel closes.
- Cap `max` (for example ≤ 1000) and validate the `{channel}` path variable against the enum.
- "Reset attempts" resets `retryCount` (the fencing counter) and the header. It does **not** delete the `attempts[]` history. Append a `REPROCESSED` event so `GET /notifications/{id}` tells the full story.
- Publishing goes through the same `NotificationPublisher` (confirms), and the message is rebuilt from the DLQ payload. The payload is thin (see Data Flow), so the Mongo document stays the source of truth.

### Pattern 7: Resilience4j per channel (CircuitBreaker outside, SemaphoreBulkhead inside)

**What:** One CB instance and one Bulkhead instance per channel name (`email`, `sms`, `webhook`), configured in YAML through `resilience4j-spring-boot3` and applied **programmatically** with `Decorators` in `ChannelGateway`. Programmatic decoration makes the per-channel selection explicit and unit-testable, and avoids AOP proxy surprises.

```java
public void send(Channel ch, RenderedMessage m) {
  CircuitBreaker cb = cbRegistry.circuitBreaker(ch.id());
  Bulkhead bh = bhRegistry.bulkhead(ch.id());
  Decorators.ofRunnable(() -> adapters.get(ch).send(m))
      .withBulkhead(bh)          // inner: concurrency limit
      .withCircuitBreaker(cb)    // outer: open CB fails fast before taking a permit
      .run();
}
```
```yaml
resilience4j:
  circuitbreaker:
    configs.default:
      slidingWindowType: COUNT_BASED
      slidingWindowSize: 20
      failureRateThreshold: 50
      waitDurationInOpenState: 10s
      permittedNumberOfCallsInHalfOpenState: 3
      recordExceptions: [com.notifyhub.worker.failure.TransientDeliveryException]
      ignoreExceptions:
        - com.notifyhub.worker.failure.PermanentDeliveryException   # bad recipient must not open the breaker
        - io.github.resilience4j.bulkhead.BulkheadFullException      # saturation is not a downstream failure
    instances: { email: {baseConfig: default}, sms: {baseConfig: default}, webhook: {baseConfig: default} }
  bulkhead:
    configs.default: { maxConcurrentCalls: 10, maxWaitDuration: 50ms }
    instances: { email: {baseConfig: default}, sms: {baseConfig: default}, webhook: {baseConfig: default} }
```
- Use the **semaphore** Bulkhead, not `ThreadPoolBulkhead`. The semaphore version runs on the caller's (virtual) thread. The thread-pool version moves work back onto a platform pool and loses MDC.
- **The bulkhead only ever triggers if listener concurrency for that channel exceeds `maxConcurrentCalls`.** Pick the demo values so it does (for example concurrency 20, bulkhead 10, slow simulated latency), otherwise the "bulkhead full → retry" path is dead code.
- Skip `TimeLimiter`, which needs async/`CompletableFuture` calls. The simulated adapter raises `SimulatedTimeoutException extends TransientDeliveryException` after its configured latency.
- CB, Bulkhead and Retry metrics are exported automatically by `resilience4j-micrometer` (`resilience4j_circuitbreaker_state{name="sms"}`) and are ready for the Grafana dashboard.

### Pattern 8: Failure classification as a closed exception hierarchy

```
DeliveryException (abstract)
├── TransientDeliveryException      → retry level / DLQ when exhausted
│   ├── SimulatedTimeoutException
│   └── SimulatedServerErrorException (5xx)
└── PermanentDeliveryException      → FAILED + DLQ immediately, no attempts spent
    ├── InvalidRecipientException
    ├── TemplateNotFoundException   (missing template or missing channel variant)
    └── MissingTemplateVariableException
```
`FailureClassifier.classify(Throwable)`:
- `CallNotPermittedException` (CB open) → TRANSIENT
- `BulkheadFullException` → TRANSIENT
- `TransientDeliveryException` → TRANSIENT
- `PermanentDeliveryException` → PERMANENT
- `MustacheException` → PERMANENT (wrap it as `MissingTemplateVariable`)
- anything else → **rethrow**, so it lands in the DLQ via the safety net.

Make the classifier exhaustive and unit-test it. Use `failureType` + `errorCode` (`INVALID_RECIPIENT`, `TEMPLATE_NOT_FOUND`, `MISSING_VARIABLE`, `TIMEOUT`, `SERVER_ERROR`, `CIRCUIT_OPEN`, `BULKHEAD_FULL`, `RETRIES_EXHAUSTED`) as the stored attempt fields and as the metric `reason` tag.

**Deterministic failure triggers** (needed for the integration test and the demo, on top of the random failure rate): recipients matching configurable patterns force outcomes, for example `*@invalid.test` → permanent and `always-fail-*` → transient on every attempt. A test that depends on random failure rates is flaky.

---

## Data Flow

### Request Flow: create

```
POST /notifications {channel, recipient, templateCode, variables}
  → NotificationController (Bean Validation: channel enum, non-blank fields, variables map)
  → NotificationService
      1. insert NotificationDocument{_id=UUID, status=PENDING, retryCount=0, attempts=[]}      [Mongo]
      2. NotificationPublisher.publish(NotificationMessage) to notify.exchange / notification.<ch>
         header notify-retry-count=0, messageId=_id, persistent                              [RabbitMQ]
      3. wait for confirm (≤5s) + check returned
         ok   → 202 {id, status: PENDING}
         fail → conditional update PENDING→PUBLISH_FAILED; metric publish_failed; 503 {id, status}
```

### Delivery Flow: worker

```
notify.<ch>  ──deliver──>  ChannelListener(msg, header h, redelivered)
  → DeliveryProcessor
      1. claim: findAndModify (predicate above)          null → ack, metric duplicate_skipped
      2. TemplateRenderer.render(templateCode, channel, variables)   (cached compiled template)
      3. ChannelGateway.send(ch, rendered)               CB(Bulkhead(SimulatedAdapter))
      4a. success   → findAndModify PROCESSING→SENT, $push attempt(SUCCESS)   → metric sent  → ack
      4b. transient → RetryRouter.decide(h)
            RETRY(level n+1): publish notify.retry/<ch>.retry.<n+1> (confirmed) header h+1
                              → findAndModify →RETRYING, retryCount=h+1, $push attempt(TRANSIENT)
                              → metric retried{level} → ack
            EXHAUSTED:        findAndModify →FAILED, $push attempt, lastError=RETRIES_EXHAUSTED
                              → metric failed{reason=exhausted} → throw AmqpRejectAndDontRequeue → DLQ
      4c. permanent → findAndModify →FAILED, $push attempt(PERMANENT) → metric failed{reason}
                      → throw AmqpRejectAndDontRequeue → DLQ

notify.<ch>.retry.<ttl> ──(TTL expires, broker dead-letters)──> notify.exchange / notification.<ch> ──> notify.<ch>
```

### Reprocess Flow

```
POST /admin/dlq/{ch}/reprocess?max=N  (X-API-Key)
  → ApiKeyFilter → AdminDlqController → DlqReprocessService
  loop ≤ N: basicGet(dlq, autoAck=false)
           → Mongo reset (→PENDING, retryCount=0, reprocessCount++, push REPROCESSED)
           → publish notify.exchange/notification.<ch> header 0 (confirmed)
           → basicAck   (failure: revert to FAILED, basicNack requeue, stop)
  → 200 {moved, skipped, remainingApprox}
```

### Query Flow

```
GET /notifications/{id}                → findById → full document incl. attempts[]
GET /notifications?status=&channel=&page=&size=
   → Criteria on present filters, sort createdAt desc, _id desc
   → served by compound indexes (below); page size capped (≤100)
```

### Key Data Flows

1. **Message payload is thin, the document is fat.** `NotificationMessage{notificationId, channel, templateCode, createdAt}` carries just enough to read in the panel. The worker always loads recipient and variables from Mongo, because it must read the document to claim anyway. This keeps a single source of truth, keeps PII out of the broker and DLQ, and makes reprocessing trivial.
2. **State only moves forward through findAndModify.** No read-modify-write with `save()` anywhere in the worker. `save()` overwrites the whole document and loses concurrent `$push` updates to `attempts`.
3. **Metrics follow outcomes, not attempts in flight.** Counters are incremented by `DeliveryProcessor` only after the corresponding Mongo transition succeeds, so Grafana and `GET /notifications` agree.

---

## MongoDB Document Design

### `notifications`

```json
{
  "_id": "0192f1c2-...-uuid",
  "channel": "EMAIL",
  "recipient": "ana@example.com",
  "templateCode": "order-shipped",
  "variables": { "name": "Ana", "orderId": "123" },
  "status": "RETRYING",
  "retryCount": 1,
  "reprocessCount": 0,
  "leaseUntil": "2026-10-09T12:00:30Z",
  "lastError": { "type": "TRANSIENT", "code": "TIMEOUT", "message": "simulated timeout after 2000ms" },
  "attempts": [
    { "number": 1, "retryLevel": 0, "startedAt": "...", "durationMs": 2003,
      "outcome": "TRANSIENT_FAILURE", "errorCode": "TIMEOUT", "errorMessage": "...", "workerId": "worker-1" }
  ],
  "createdAt": "2026-10-09T12:00:00Z",
  "updatedAt": "2026-10-09T12:00:07Z",
  "sentAt": null
}
```
- **Embed attempts.** They are always read with the parent (`GET /{id}`), never queried on their own, and naturally bounded: `levels+1` per cycle times the reprocess cycles. Add a guard anyway: `$push: {attempts: {$each: [a], $slice: -50}}`.
- `_id` as a UUID string from the API (not ObjectId), so the id exists before insert and can go into the AMQP `messageId`. UUIDv7 is a nice touch (time-ordered, better B-tree locality) but optional.
- Store enums as strings. Use `Instant` for timestamps.

### `templates`

```json
{
  "_id": "order-shipped",
  "version": 1,
  "requiredVariables": ["name", "orderId"],
  "variants": {
    "EMAIL":   { "subject": "Pedido {{orderId}} enviado", "body": "Olá {{name}}, ..." },
    "SMS":     { "body": "{{name}}, pedido {{orderId}} saiu para entrega" },
    "WEBHOOK": { "body": "{\"event\":\"order.shipped\",\"orderId\":\"{{orderId}}\"}" }
  },
  "updatedAt": "..."
}
```
- One document per template concept, with per-channel variants **embedded**. This shows off NoSQL modeling (one read, no join) and makes "missing channel variant" a clean `TemplateNotFound` permanent failure. Lookup is by `_id`, which is unique by definition, so no extra index is needed.
- Validate `requiredVariables` **before** rendering, which gives a precise `MISSING_VARIABLE` error naming the variable. JMustache throws on unresolved variables by default anyway. Do not configure `defaultValue`/`nullValue`, because that silently renders blanks.
- Seeder: the worker upserts seed JSON at startup (`ApplicationRunner`, upsert by `_id`, overwrite only if the seed `version` is higher). This works the same under docker compose and Testcontainers. Do not use a Mongo `docker-entrypoint-initdb.d` script, because it doesn't run in Testcontainers or locally.
- For webhook bodies, Mustache HTML-escaping (`{{x}}`) will mangle JSON. Configure the compiler with `escapeHTML(false)` for WEBHOOK/SMS variants, or have the template author use `{{{x}}}`. Per-variant compiler config is the cleaner option.

### Indexes (justify in README, following the ESR rule: Equality → Sort → Range)

| Index | Serves | Why |
|-------|--------|-----|
| `_id` (implicit, unique) | `GET /{id}`, worker claim, idempotency | Built in. Every `findAndModify` filters by `_id` first, so the claim is a single-document indexed operation |
| `{status: 1, channel: 1, createdAt: -1}` | `?status=X&channel=Y` and `?status=X` (prefix), sorted newest first | Equality fields first, then the sort field, so no in-memory SORT stage. `status` leads because ops queries filter by status alone more often (all FAILED) than by channel alone |
| `{channel: 1, createdAt: -1}` | `?channel=Y` alone | The first index can't serve a channel-only filter because `status` is the prefix |
| `{createdAt: -1}` | unfiltered list | Avoids a collection scan + sort on the default listing |

- Do **not** create single-field `status` and `channel` indexes and hope for index intersection. The planner rarely picks it and it never removes the sort.
- Add `_id` as a sort tie-breaker (`createdAt desc, _id desc`) for stable pages. Because the sort is not covered by the indexes above, consider adding `_id: -1` as a trailing key on each compound index. That is cheap and keeps paging deterministic.
- `Page<T>` triggers a `countDocuments` with the same filter. That is fine at demo scale and uses the same index. Mention keyset pagination (`createdAt < lastSeen`) as the scalable alternative in the README.
- Optional: `{status: 1, leaseUntil: 1}` for a future "stuck PROCESSING" sweeper (out of scope), and a TTL index on `createdAt` for retention.
- **Create indexes explicitly.** Spring Data MongoDB has had `auto-index-creation` **off by default** since 3.0, so `@CompoundIndex` annotations alone do nothing. Use a `MongoIndexInitializer` in common (`mongoTemplate.indexOps(...).ensureIndex(...)`, idempotent) run by both apps at startup, or set `spring.data.mongodb.auto-index-creation=true` deliberately and say so.

---

## Where Metrics Are Recorded

| Metric (Micrometer name → Prometheus) | Tags | Recorded in | When |
|---|---|---|---|
| `notify.notifications.accepted` → `_total` | channel | API `NotificationService` | after the confirmed publish |
| `notify.notifications.publish.failed` | channel, reason (nack/unroutable/timeout) | API `NotificationService` | after PUBLISH_FAILED is written |
| `notify.dlq.reprocessed` | channel, result (moved/skipped/failed) | API `DlqReprocessService` | per message |
| `notify.notifications.sent` | channel | worker `DeliveryProcessor` | after the SENT transition succeeds |
| `notify.notifications.retried` | channel, level (1/2/3), reason | worker `DeliveryProcessor` | after the retry publish is confirmed and RETRYING is written |
| `notify.notifications.failed` | channel, type (permanent/exhausted), reason | worker `DeliveryProcessor` | after the FAILED transition, before reject |
| `notify.notifications.duplicate.skipped` | channel | worker `DeliveryProcessor` | claim returned null |
| `notify.delivery.duration` (Timer, with percentiles histogram) | channel, outcome | worker `ChannelGateway` | around the decorated adapter call |
| `resilience4j_circuitbreaker_*`, `resilience4j_bulkhead_*` | name=channel | auto (resilience4j-micrometer) | - |
| Queue depths, publish/deliver rates | queue | **RabbitMQ `rabbitmq_prometheus` plugin (:15692)**, scraped directly | - |
| JVM / virtual threads / HTTP | - | Spring Boot Actuator | - |

Rules: put metric names and tag keys as constants in `DeliveryMetrics` (worker) and `ApiMetrics` (api). **Never tag with `notificationId`, recipient or templateCode-with-variables** (cardinality explosion). Queue depth comes from the broker plugin, so don't poll the management API from the apps. Adapters don't touch metrics, the gateway times calls, and the processor counts outcomes.

---

## Suggested Build Order (dependencies)

```
[1] Foundation: parent POM + notify-common + docker compose (rabbit+mongo)
        │   topology Declarables visible in panel; enums, message record, headers, documents, index initializer
        v
[2] API ingest + query ──────────────────────────┐
        │   POST (insert PENDING + confirmed publish + PUBLISH_FAILED), GET by id, GET list + indexes
        v                                         │ (messages now pile up in notify.<ch>; observable)
[3] Worker happy path                             │
        │   per-channel listeners, claim (findAndModify), TemplateRenderer (+unit test), seeder,
        │   simulated adapters (latency, magic recipients), SENT transition, DeliveryMetrics skeleton
        v                                         │
[4] Failure handling + resilience                 │
        │   FailureClassifier, RetryRouter + level republish w/ confirms, FAILED→DLQ via reject,
        │   full claim predicate (stale/redelivered), Resilience4j CB + Bulkhead per channel
        v                                         v
[5] DLQ reprocess (API admin) + Testcontainers integration test: retry → DLQ → reprocess
        │   (needs [2] publisher + [4] DLQ population; short TTL levels via properties)
        v
[6] Observability + demo: Prometheus/Grafana provisioning, rabbitmq_prometheus, load script (1000),
    virtual vs platform thread benchmark, README pt-BR (index justification, limitations, outbox note)
```

**Ordering rationale:**
- Topology and the shared contract come first because both apps import them. Getting queue arguments wrong later means deleting queues (`406 PRECONDITION_FAILED`).
- The API comes before the worker: once the API publishes, the messages sitting in `notify.<ch>` prove the contract, and the worker then has real input.
- The happy path comes before failures: the claim and SENT transitions are the base that the retry/fail transitions extend, and the template renderer unit test is independent.
- Reprocess depends on a populated DLQ ([4]) and reuses the API publisher ([2]). The integration test naturally covers [4] and [5] together, so build them back to back. With coarse granularity they can be merged into one phase.
- Metrics hooks are cheap to add inside [3]/[4] at the outcome points. The dashboards and benchmark wait until the end, when the behaviour is stable.

---

## Scaling Considerations

| Scale | Architecture adjustments |
|-------|--------------------------|
| Demo (1k notifications, 1 worker) | As designed. Classic queues, single Mongo, offset pagination |
| ~1M/day, N workers | Run multiple worker replicas; the claim predicate already handles competing consumers. Tune per-channel concurrency/prefetch, move to quorum queues (mind `delivery-limit`), keyset pagination, TTL index for retention |
| Larger | Transactional outbox in the API (removes the dual-write window), a stuck-PROCESSING sweeper, partitioned channel queues, sharding `notifications` by `_id` hash |

**First bottleneck:** the simulated adapter latency bounded by consumer concurrency × bulkhead permits. This is the knob the virtual-thread benchmark exercises. **Second:** Mongo write amplification from `$push` on every attempt (fine at this scale).

---

## Anti-Patterns

### Anti-Pattern 1: Counting retries with `x-death`
**What people do:** Read `x-death[0].count` to decide the next level.
**Why it's wrong:** In this topology every count is 1 (each level is a different queue, and main→delay is a publish), and RabbitMQ 4.x ignores client-supplied x-death on republish.
**Do this instead:** Use the `notify-retry-count` header owned by the worker, and strip x-death on republish.

### Anti-Pattern 2: `RabbitTemplate.receive()` for DLQ reprocess
**What people do:** `while ((m = template.receive(dlq)) != null) template.send(...)`.
**Why it's wrong:** Non-transacted `receive` is `basicGet(autoAck=true)`, so the message is gone before the republish is confirmed.
**Do this instead:** `execute(channel -> basicGet(q, false) ... basicAck/basicNack)`.

### Anti-Pattern 3: Treating a confirm `ack` as "delivered"
**What people do:** Check only `confirm.isAck()`.
**Why it's wrong:** Unroutable + `mandatory` gives a return and **then** an ack.
**Do this instead:** Check `getReturned()` too. Treat timeouts as "unknown" (PUBLISH_FAILED but claimable).

### Anti-Pattern 4: Spring listener retry / requeue-on-exception
**What people do:** Enable `spring.rabbitmq.listener.simple.retry` or leave `default-requeue-rejected=true`.
**Why it's wrong:** In-memory retries block the consumer and bypass the delay queues. Requeue-on-exception creates a hot poison-message loop.
**Do this instead:** Disable listener retry, set `default-requeue-rejected=false`, and use explicit exception types.

### Anti-Pattern 5: Repository `save()` for state transitions
**What people do:** `doc = repo.findById(id); doc.setStatus(SENT); repo.save(doc)`.
**Why it's wrong:** Last-writer-wins clobbers concurrent transitions and `$push` updates. It is also not idempotent.
**Do this instead:** Conditional `findAndModify` / `updateFirst` with a status/retryCount filter.

### Anti-Pattern 6: One listener for all channel queues
**What people do:** `@RabbitListener(queues = {"notify.email","notify.sms","notify.webhook"})`.
**Why it's wrong:** Consumer threads are shared, so one slow or broken channel delays the others and the per-channel isolation story falls apart.
**Do this instead:** One container per channel, each with its own concurrency property.

### Anti-Pattern 7: Circuit breaker counting permanent failures
**What people do:** Use the default CB config, which records every exception.
**Why it's wrong:** A batch of invalid recipients opens the breaker and blocks valid traffic. Bulkhead saturation also opens it.
**Do this instead:** `recordExceptions` = transient only. `ignoreExceptions` = permanent + `BulkheadFullException`.

---

## Integration Points

### External Services

| Service | Integration pattern | Notes |
|---------|---------------------|-------|
| RabbitMQ (4.x, `management` + `prometheus` plugins) | Spring AMQP, `CachingConnectionFactory`, correlated confirms + returns | Enable plugins in the compose image/command. The panel must show the 15 queues |
| MongoDB (single node) | Spring Data MongoDB, `MongoTemplate` for conditional updates | No multi-document transactions needed (no replica set required) |
| Prometheus / Grafana | Scrape `/actuator/prometheus` + `rabbitmq:15692` | Provision datasource + dashboard JSON via files, with no manual steps |

### Internal Boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| api → worker | AMQP only (`NotificationMessage` + headers) | Never HTTP. The contract lives in notify-common |
| api ↔ worker | Shared Mongo `notifications` collection | Both write it. The API writes PENDING/PUBLISH_FAILED and the reprocess reset, always **conditionally**. The worker owns every other transition |
| worker internals | DeliveryProcessor → (State, Renderer, Gateway, Classifier, Router, Metrics) | The processor is the only component that sequences side effects |
| Gateway → Adapter | In-process interface `ChannelAdapter` | Adapters throw only `DeliveryException` subtypes |

### Virtual threads note (worker)
`spring.threads.virtual.enabled=true` (Boot 3.2+) auto-configures the Rabbit listener container factories with a virtual-thread executor. One historical issue: with SimpleMessageListenerContainer, a concurrency above the logical CPU count failed to start (Boot #38563, root cause `synchronized(consumersMonitor)` in Spring AMQP, tracked as #2481/#2568). Phase 3 should check startup at the chosen concurrency on the selected Spring AMQP version, and fall back to `DirectMessageListenerContainer` (`consumersPerQueue`) if it hangs. Keep the code path free of `synchronized` around blocking I/O (Java 21 pins carrier threads; this was fixed only in JDK 24 by JEP 491). The benchmark design (what exactly differs between virtual and platform threads when concurrency is bounded by consumers) needs its own phase research. A naive setup may show no difference.

---

## Sources

Seam tier: LOW, retrieved via WebSearch/WebFetch. Most items are official vendor documentation or release notes; the design patterns cross-check against each other.
- RabbitMQ Dead Letter Exchanges (x-death, routing key, cycle detection, TTL dead-lettering): https://www.rabbitmq.com/docs/dlx
- RabbitMQ 4.0.1 release notes (x-death no longer interpreted on client publish): https://github.com/rabbitmq/rabbitmq-server/releases/tag/v4.0.1
- Spring AMQP issue #2688 (x-death documentation / Spring Cloud Stream pattern breaks on 4.0): https://github.com/spring-projects/spring-amqp/issues/2688
- RabbitMQ Quorum Queues (delivery-limit default 20 in 4.0): https://www.rabbitmq.com/docs/quorum-queues , https://www.rabbitmq.com/blog/2024/08/28/quorum-queues-in-4.0
- Spring AMQP CorrelationData API (`getFuture`, `getReturned`): https://docs.spring.io/spring-amqp/api/org/springframework/amqp/rabbit/connection/CorrelationData.html
- Spring AMQP Template / sending messages (single ConfirmCallback, returns before confirm): https://docs.spring.io/spring-amqp/reference/amqp/template.html
- Spring AMQP polling consumer: https://docs.spring.io/spring-amqp/reference/amqp/receiving-messages/polling-consumer.html
- Spring AMQP choosing a container (SMLC vs DMLC): https://docs.spring.io/spring-amqp/reference/amqp/receiving-messages/choose-container.html
- Spring Boot 3.2 release notes (virtual threads, Rabbit listener executor #36387): https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.2-Release-Notes
- Spring Boot #38563 / Spring AMQP #2481 (virtual threads + SMLC startup): https://github.com/spring-projects/spring-boot/issues/38563 , https://github.com/spring-projects/spring-amqp/issues/2481
- Resilience4j getting started (aspect order, bulkhead types): https://resilience4j.readme.io/docs/getting-started-3 , https://resilience4j.readme.io/docs/bulkhead
- Background knowledge (not re-fetched this session; verify during planning): Spring Data MongoDB `auto-index-creation` default false since 3.0; MongoDB ESR indexing guideline; JMustache strict missing-variable behaviour and `escapeHTML`; non-transacted `RabbitTemplate.receive` uses autoAck `basicGet`.

---
*Architecture research for: RabbitMQ + MongoDB multichannel notification service (Notify Hub)*
*Researched: 2026-10-09*
