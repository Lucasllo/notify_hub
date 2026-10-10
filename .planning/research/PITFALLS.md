# Pitfalls Research

**Domain:** Multichannel notification service (RabbitMQ TTL+DLX retry/DLQ, MongoDB, Spring Boot 3.x, Resilience4j, Java 21 virtual threads)
**Researched:** 2026-10-09
**Confidence:** MEDIUM-HIGH overall. Spring AMQP, RabbitMQ DLX and Resilience4j defaults were checked against official docs. Virtual-thread pinning in specific libraries and RabbitMQ 4.x `x-death` republish behavior come from GitHub issues and community sources, so they are MEDIUM.

> Phase names below are topical, because the roadmap does not exist yet:
> **P-Foundation** (Maven multi-module, compose infra, topology in `notify-common`), **P-API** (POST/GET, publish with confirms), **P-Worker** (consume, render, adapters, idempotency, retry/DLQ routing), **P-Resilience** (Resilience4j CB/bulkhead, failure classification), **P-Reprocess** (admin DLQ endpoint), **P-Observability** (Micrometer/Prometheus/Grafana), **P-Testing** (Testcontainers IT), **P-Benchmark/Demo** (VT vs platform benchmark, load script, README).

---

## Critical Pitfalls

### Pitfall 1: Spring AMQP default requeue gives an infinite hot redelivery loop

**What goes wrong:**
The listener throws an exception and the message is redelivered immediately, forever, at full CPU. The delay queues are never used, logs flood, and the "retry with backoff" story falls apart in the demo.

**Why it happens:**
With retries disabled (the Boot default), `defaultRequeueRejected` defaults to `true`. Spring Boot docs: *"By default, if retries are disabled and the listener throws an exception, the delivery is retried indefinitely."* Developers assume an exception means "dead-letter it".

**How to avoid:**
- Set `spring.rabbitmq.listener.simple.default-requeue-rejected=false` on the worker. Any unexpected exception then goes to the main queue's DLX instead of looping.
- Do not rely on exceptions for flow control. The listener should decide the outcome explicitly: success → ack; transient → publish to the next delay level, then ack; permanent or exhausted → route to the DLQ. For "send to DLQ now", throw `AmqpRejectAndDontRequeueException` only when the main queue's `x-dead-letter-exchange` really points at the DLQ.
- Keep `spring.rabbitmq.listener.simple.retry.enabled=false` (see Pitfall 2).

**Warning signs:**
The `redelivered=true` rate climbs in the management UI, the same `notification_id` shows up in logs hundreds of times a second, delay queues stay empty while failures happen, and CPU is pegged with no throughput.

**Phase to address:** P-Worker (first listener). Add a regression test in P-Testing: a poison message must end in the DLQ within N seconds and be delivered fewer than K times.

---

### Pitfall 2: Turning on Spring's stateless retry interceptor on top of broker-side retry

**What goes wrong:**
Someone enables `spring.rabbitmq.listener.simple.retry.enabled=true` "for resilience". Each delivery is then retried in memory (default 3 attempts, with `Thread.sleep` backoff that blocks the consumer). After that, `RejectAndDontRequeueRecoverer` rejects the message, and it goes straight to the main queue's DLX. That either skips the delay levels or multiplies attempts (3 in-memory × 3 levels = 9+ sends). The attempt history in Mongo no longer matches what the broker shows.

**Why it happens:**
Tutorials pair `retry.enabled` with DLQs. The two retry mechanisms look complementary, but they compete.

**How to avoid:**
Use one retry mechanism, the broker TTL+DLX levels the project requires. Write down in a config comment and in the README that in-memory retry is off on purpose. If a recoverer is ever needed, use a custom `MessageRecoverer` that publishes to the delay exchange. Never use the default one.

**Warning signs:**
Attempts recorded in Mongo do not match the x-death or attempt header, the gap between attempts is about 1s instead of 5s/30s/2m, and consumer threads sit in `Thread.sleep` in thread dumps.

**Phase to address:** P-Worker.

---

### Pitfall 3: Topic-exchange binding overlap and dead-letter routing-key rewrites misroute retries

**What goes wrong:**
- A retry message published with a key like `notification.email.retry.1` also matches the main queue binding `notification.email.#`. It is delivered to the main queue immediately and to the delay queue as well, which means duplicates and no backoff.
- The delay queue has no `x-dead-letter-routing-key`, so the expired message is dead-lettered with its original key (`...retry.1`). That either routes back into the same delay queue (a loop) or matches nothing and is **silently dropped**. Classic-queue dead-lettering has no `mandatory` flag and no internal confirms.
- Without a dead-letter routing key, the original key, including CC/BCC keys, is reused. When `x-dead-letter-routing-key` is set, it replaces the key (RabbitMQ docs).

**Why it happens:**
One topic exchange carries main, retry and DLQ traffic, and wildcard bindings are written broadly.

**How to avoid:**
- Use **separate exchanges per concern**: `notify.events` (topic, API → channel queues), `notify.retry` (direct, worker → `notify.<ch>.retry.<level>`), and `notify.dlq` (direct, → `notify.<ch>.dlq`).
- Each delay queue declares `x-message-ttl=<level>`, `x-dead-letter-exchange=notify.events` (or a dedicated direct "requeue" exchange), and **an explicit** `x-dead-letter-routing-key=<channel main key>`.
- The main queue declares `x-dead-letter-exchange=notify.dlq` and `x-dead-letter-routing-key=<ch>`, so reject(requeue=false) means DLQ.
- Delay queues must have **no consumers**.
- Keep every name and key as constants in `notify-common`. Add a topology unit test that checks each binding pattern matches only the intended key.

**Warning signs:**
Main-queue deliveries exceed published + retried counts, messages vanish (the Mongo status stays RETRYING forever), or a delay queue shows sustained throughput with depth that never drops.

**Phase to address:** P-Foundation (topology design), verified in P-Testing.

---

### Pitfall 4: Counting attempts from `x-death` (and resetting it) is fragile across RabbitMQ versions

**What goes wrong:**
- With **fixed per-level delay queues**, the worker has to choose the level. That means it **publishes** a new message to `retry.<level>` rather than rejecting, so the broker writes no `x-death` entry for the main queue on that hop. Code that reads `x-death[0].count` gets the wrong number or `null`.
- `x-death` is compressed by `{queue, reason}`, and the most recent event comes first. `count` is per pair, not a global attempt count.
- From 3.13.0 the broker stopped interpreting client-set `x-death` on republish, so counts stayed at 1. A 3.13.3 patch was 3.13-only, and maintainers said `x-death` was "never meant to be modified by applications". Sources say RabbitMQ 4.x does not interpret client-republished `x-death` (MEDIUM confidence).
- "Reset attempts" on reprocess cannot be done by editing `x-death`.

**Why it happens:**
Old blog posts (from before 3.13) show `x-death` count as the standard retry counter.

**How to avoid:**
- The application owns the attempt counter. Carry an app header (for example `x-notify-attempt`) **and** store `attempts` in the Mongo document. Mongo is the source of truth. The header is a fast path or cross-check.
- Pick the level from the attempt number: `attempt 1 → 5s`, `2 → 30s`, `3 → 2m`, `>max → DLQ`.
- Reprocess sets `attempts=0` in Mongo and republishes with `x-notify-attempt=0`. Do not copy `x-death` headers onto the republished message.
- Treat `x-death` as diagnostic information only. It is fine to show it in the GET history or logs.

**Warning signs:**
The attempt count is stuck at 1, messages never reach the DLQ, or messages reach the DLQ after one failure. Behavior changes when the RabbitMQ image tag changes.

**Phase to address:** P-Worker (retry routing), P-Reprocess (reset semantics).

---

### Pitfall 5: Retry hop is not atomic (publish-to-delay + ack), so loss or duplicates follow

**What goes wrong:**
The worker acks the original message and then publishes to the delay queue. If the process crashes in between, or the publish is unroutable or nacked, the notification is lost while Mongo says RETRYING. That breaks the core value ("never lost silently"). The reverse order (publish, then ack) is safe but can duplicate if the process crashes before the ack.

**Why it happens:**
Publisher confirms are configured only in the API, and the worker's retry publish is treated as fire-and-forget.

**How to avoid:**
- In the worker, **publish to the delay/DLQ exchange with confirms + mandatory, wait for the confirm (with a timeout), then ack the original**. If the confirm fails, nack with requeue=true (or throw), so the broker keeps the message.
- Accept the resulting at-least-once duplicates and let idempotency (Pitfall 8) absorb them.
- Simplest correct implementation: `AcknowledgeMode.MANUAL` for the worker listener, so the ack happens only after the confirm. AUTO mode with a confirm wait inside the listener also works, because AUTO acks only after the listener returns normally.

**Warning signs:**
The Mongo RETRYING count grows while all delay queues are empty, or the "retries" metric is higher than the delay-queue publish rate.

**Phase to address:** P-Worker.

---

### Pitfall 6: Publisher confirms "configured" but not actually verifying delivery

**What goes wrong:**
- `spring.rabbitmq.publisher-confirm-type` defaults to `none`. Without it, `CorrelationData.getFuture()` never completes, or there are no confirms at all.
- **An unroutable message is still acked.** The broker confirms once it decides the message routes nowhere. If `mandatory`/returns are not enabled, the API marks PENDING as "published" while the message has been discarded. This happens, for example, when the worker hasn't declared the queues yet or a routing key has a typo.
- Calling `setConfirmCallback` on the shared auto-configured `RabbitTemplate` in more than one place fails ("only one ConfirmCallback"), or ties confirm handling to global state.
- Waiting on `future.get()` with no timeout can hang a request thread.
- A late confirm or timeout race: the API times out and marks `PUBLISH_FAILED`, the message was actually delivered, and the worker already set `SENT`. The API's unconditional update then overwrites `SENT`.

**How to avoid:**
- `spring.rabbitmq.publisher-confirm-type=correlated`, `spring.rabbitmq.publisher-returns=true`, `spring.rabbitmq.template.mandatory=true`.
- Use a unique `CorrelationData(notificationId)` per send, then `future.get(timeout)`. Success means `ack && correlation.getReturned() == null`. Anything else is `PUBLISH_FAILED`.
- Make the status updates **conditional**: `updateOne({_id, status: PENDING}, {$set: {status: PUBLISH_FAILED}})`. Never overwrite a status the worker has advanced.
- Both API and worker declare the full topology (shared `notify-common` `@Configuration`), so the API never publishes into a void at startup.
- Messages persistent (the Spring AMQP default delivery mode is PERSISTENT; leave it) and queues durable.

**Warning signs:**
Mongo has PENDING documents that are never processed while there are no PUBLISH_FAILED documents, `basic.return` appears in RabbitMQ logs, and the confirm callback never fires in tests.

**Phase to address:** P-API (and the worker's publish path in P-Worker). Add an IT that publishes to a deliberately unbound key and expects PUBLISH_FAILED.

---

### Pitfall 7: Queue argument mismatch, `PRECONDITION_FAILED - inequivalent arg 'x-message-ttl'`

**What goes wrong:**
TTL levels are changed (5s to 10s), or a DLX is added to an existing queue. On the next start, `RabbitAdmin` redeclares, the broker closes the channel with 406 PRECONDITION_FAILED, and the declaration fails. Depending on config, the app logs errors and its listeners keep failing, or startup fails. The same thing happens if the API and the worker declare the same queue with different args, or if the test profile uses short TTLs against a reused container or volume.

**Why it happens:**
Queue arguments are immutable once declared. Developers iterate on TTLs with a persistent compose volume.

**How to avoid:**
- **Encode the TTL in the queue name**: `notify.email.retry.5s`, `notify.email.retry.30s`, `notify.email.retry.2m`. A TTL change then creates a new queue instead of a conflict, and it reads well in the management UI.
- One source of truth for args in `notify-common`. Both modules use the same `Declarables` bean.
- Pass TTL as an integer through `QueueBuilder.durable(name).ttl(int)`. A string value causes a type-mismatch precondition error.
- Use `docker compose down -v` while iterating, and document it in the README troubleshooting section.
- Keep `RabbitAdmin.ignoreDeclarationExceptions=false`, so a mismatch fails loudly instead of degrading quietly.

**Warning signs:**
`inequivalent arg` in logs, `Channel shutdown: channel error; protocol method: #method<channel.close>(reply-code=406` messages, and listeners repeatedly restarting.

**Phase to address:** P-Foundation.

---

### Pitfall 8: Idempotency via check-then-act instead of an atomic claim

**What goes wrong:**
The worker does `findById` → `if status != SENT` → `send()` → `save(SENT)`. Two concurrent deliveries of the same `notification_id` can both pass the check. Duplicates come from redelivery after a consumer crash or shutdown, from the publish-then-ack window in Pitfall 5, or from a reprocess while the original is still in a delay queue. Both deliveries send, which is a duplicate "send". With virtual threads and high consumer concurrency, the race window is reached much more often.
Also, `repository.save(entity)` replaces the whole document. Two writers race and one `attempts` entry is lost (lost update).

**Why it happens:**
"Unique index on notification_id" is misread. The notification **is** the `_id`, so a unique index on it alone prevents nothing during processing.

**How to avoid:**
- **Atomic claim with findAndModify**: `findAndModify({_id: id, status: {$in: [PENDING, RETRYING]}, attempts: expectedAttempt}, {$set: {status: PROCESSING, claimedAt: now, claimToken: uuid}})`. If it returns null, someone else owns the message or it is terminal, so ack and skip.
- Or use an explicit **deliveries/claims collection with a unique compound index** `{notificationId: 1, attempt: 1}`. Insert first. On `DuplicateKeyException` (Spring's translation of E11000), skip. That is the "unique index" the PROJECT.md asks for, applied to the right key.
- Write status and history with **one atomic update** (`$set` + `$push` on `attempts`). Never read-modify-`save()`.
- Recover stale `PROCESSING` claims. If a worker dies after the claim and the message is redelivered, allow re-claim when `claimedAt` is older than a timeout.
- Be honest in the README: delivery is at-least-once. A crash between "adapter sent" and "status written" can still re-send. Real providers need a provider-side idempotency key, and the simulated adapter can log the key to show this.

**Warning signs:**
The adapter call count is higher than the notification count at 0% failure rate, and `attempts` arrays contain duplicate attempt numbers.

**Phase to address:** P-Worker. Add a concurrency IT in P-Testing that publishes the same id twice at the same time and checks exactly one send.

---

### Pitfall 9: Idempotency guard silently swallows DLQ reprocessing

**What goes wrong:**
Reprocess republishes a FAILED notification. The worker's idempotency check sees `status=FAILED` (terminal), acks and skips it. The reprocess endpoint reports success, nothing happens, and the demo of "retry → DLQ → reprocess" fails.

**How to avoid:**
Reprocess must **first** do an atomic Mongo update, `FAILED → PENDING` (or `REPROCESSING`) with `attempts=0` and a history event `REPROCESSED`, and **then** republish. The claim filter accepts that status. If the republish fails, roll the status back or mark it.

**Warning signs:**
Reprocess returns `moved: N`, but there are no new attempts in GET /notifications/{id}.

**Phase to address:** P-Reprocess, with an IT covering the full retry → DLQ → reprocess → SENT path.

---

### Pitfall 10: DLQ reprocess loses or re-loops messages

**What goes wrong:**
- `rabbitTemplate.receive(dlq)` acks on receipt. If the republish then fails, the message is gone.
- Looping `while (n < max)` while the same messages fail fast back into the DLQ means the endpoint reprocesses the same message several times in one call.
- Messages that reached the DLQ via `MessageConversionException` (bad JSON) have no Mongo document. Reprocessing them sends them straight back to the DLQ.

**How to avoid:**
- Use `rabbitTemplate.execute(channel -> ...)`: `basicGet(dlq, autoAck=false)` → Mongo reset → publish with confirms → `basicAck`. On failure, `basicNack(requeue=true)`.
- Bound the batch to `min(max, messageCount at start)`, read via a passive declare, and skip ids already handled in this batch.
- Skip and report messages with no matching Mongo document, rather than looping on them.

**Phase to address:** P-Reprocess.

---

### Pitfall 11: Virtual threads that buy nothing (fixed listener concurrency) or hide new bottlenecks

**What goes wrong:**
- `spring.threads.virtual.enabled=true` makes Boot auto-configure a virtual-thread executor for the RabbitMQ listener (Boot 3.2 release notes). But `SimpleMessageListenerContainer` still runs **exactly `concurrentConsumers` threads**. With concurrency=4, VT and platform threads behave the same. The benchmark shows "no difference" and the README claim falls flat.
- Raising concurrency to thousands runs into other limits: one channel per consumer (RabbitMQ's default `channel_max` is 2047 per connection), prefetch × consumers of unacked messages, the Mongo driver pool (`maxPoolSize` default 100; extra threads queue for up to `maxWaitTime`, default 2 min), and Resilience4j bulkhead limits (Pitfall 13).
- `CachingConnectionFactory` channel cache (default 25). Many concurrent virtual-thread publishers (API requests, worker retry publishes) churn channels open and closed.

**How to avoid:**
- Decide and document the concurrency model: `concurrency=N-M` sized explicitly (for example 50-100 per channel queue), `prefetch` small (1-10) so work spreads across consumers, and the bulkhead and Mongo pool sized to match.
- Raise `spring.rabbitmq.cache.channel.size` (for example 100+) for publish-heavy paths.
- Write in the README that virtual threads remove the thread-per-blocking-call **cost**. They do not remove downstream limits.

**Warning signs:**
Thread dumps show only N listener threads, throughput is flat as consumers increase, `MongoWaitQueueTimeoutException` or slow pool checkout appears, and RabbitMQ shows channel churn.

**Phase to address:** P-Worker (container config), P-Benchmark (documented limits).

---

### Pitfall 12: Java 21 `synchronized` pinning and ThreadLocal assumptions

**What goes wrong:**
- On **Java 21**, a virtual thread that blocks inside `synchronized` (or `Object.wait()`) pins its carrier. With few carriers (default = number of CPU cores), a few pinned threads stall everything. Known sources: the RabbitMQ Java client's frame-writing path goes through `DataOutputStream`/`synchronized`, and issue #1276 is still open. Older logging appenders, your own `synchronized` around the simulated adapter's `Thread.sleep`, and `synchronized` caches are other sources. JEP 491 fixes this only in **JDK 24+**.
- ThreadLocal-based context (MDC trace ids, request-scoped caches) does not cross the hand-off to another executor (ThreadPoolBulkhead, TimeLimiter, `@Async`). Large ThreadLocal caches per virtual thread multiply memory, because there are no reused threads.
- Pooling virtual threads (`Executors.newFixedThreadPool` with a VT factory) is an anti-pattern. Use semaphores to limit concurrency.

**How to avoid:**
- No `synchronized` around blocking calls in project code. Use `ReentrantLock`, or better, no locks at all (the adapters are stateless).
- Simulate latency with `Thread.sleep(Duration)` **outside** any monitor.
- Detect pinning: run the benchmark and ITs with `-Djdk.tracePinnedThreads=short` (JDK 21-23) and/or record JFR `jdk.VirtualThreadPinned`. Report the findings in the README. Short pins inside the AMQP client are acceptable and worth documenting.
- Option to note in the README: build with `--release 21` but run on JDK 25 LTS, where synchronized pinning is gone. Keep Java 21 as the stated baseline per the constraint.
- Use Micrometer context propagation or a `TaskDecorator` if MDC must survive executor hops.

**Warning signs:**
Throughput collapses at concurrency ≈ CPU cores, pinned-thread stack traces appear, and trace ids are missing in worker logs.

**Phase to address:** P-Worker (adapter implementation), P-Benchmark (pinning detection).

---

### Pitfall 13: Resilience4j defaults that sabotage the retry design

**What goes wrong:**
- **Bulkhead type:** `ThreadPoolBulkhead` runs calls on its own platform `ThreadPoolExecutor` (default size = number of CPUs, queue 100). That throws away virtual threads and breaks ThreadLocal context. Use the **semaphore** `Bulkhead`.
- **Semaphore bulkhead defaults:** `maxConcurrentCalls=25`, `maxWaitDuration=0`. With more than 25 concurrent consumers, every extra call fails **immediately** with `BulkheadFullException`. That is classified as transient, so a flood of messages goes into the 5s delay queue. The result is a self-inflicted retry storm and inflated failure metrics.
- **Circuit breaker defaults:** `minimumNumberOfCalls=100`, `slidingWindowSize=100`, `failureRateThreshold=50`, `waitDurationInOpenState=60s`. With a demo failure rate of 10-30%, the breaker **never opens**, so the demo shows nothing. When it does open, 60s open plus retry levels of 5s and 30s means messages burn levels 1 and 2 against an open breaker and reach the DLQ early.
- **recordExceptions empty = every exception counts.** Permanent failures (invalid recipient, missing template variable) open the breaker for healthy traffic.

**How to avoid:**
- Semaphore bulkhead per channel adapter with `maxConcurrentCalls` ≥ listener concurrency for that channel (or deliberately lower, with `maxWaitDuration` of about 100-500ms), and a written explanation of the sizing.
- Circuit breaker per adapter with demo-tuned values: `slidingWindowType=COUNT_BASED`, `slidingWindowSize=20`, `minimumNumberOfCalls=10`, `waitDurationInOpenState` ≤ first delay level (for example 5-10s), `automaticTransitionFromOpenToHalfOpenEnabled=true`.
- `ignoreExceptions` = permanent failure types; `recordExceptions` = simulated timeout/5xx types.
- Decide whether `CallNotPermittedException` uses up an attempt. Recommendation: it counts, but the README explains it. Alternatively, CB-open failures get the longest delay level.
- Map `CallNotPermittedException` and `BulkheadFullException` explicitly as **transient** in the classifier.
- Resilience4j 3.x requires Java 21 and can switch its internal schedulers to virtual threads. 2.x with `resilience4j-spring-boot3` is fine for Boot 3. Pick one in STACK.md.

**Warning signs:**
The CB state metric is always CLOSED, the bulkhead-rejected count is above zero at low load, permanent failures move CB failure rate, and delay-queue depth spikes as soon as load starts.

**Phase to address:** P-Resilience.

---

### Pitfall 14: Unfair or misleading virtual-vs-platform benchmark

**What goes wrong:**
The benchmark compares different concurrency limits (Tomcat's 200-thread default vs unbounded virtual threads), uses CPU-bound simulated work (busy loops, where VT give no gain), skips warm-up, uses closed-model load (k6 constant VUs, which suffers coordinated omission), measures only the API's 202 latency (the work is async), or keeps random failures and retries on, so the 5s-2m delays dominate. Results look either "VT 10x faster!" or "no difference". A reviewer with expertise can see the flaw either way.

**How to avoid (fair design):**
1. **One variable.** Same JAR and same compose file. Toggle only `SPRING_THREADS_VIRTUAL_ENABLED=true|false`. Same JVM flags (`-Xms=-Xmx`), same container `cpus`/`mem_limit`, same machine, nothing else running.
2. **Same concurrency limits at every layer.** Same `concurrency`, `prefetch`, bulkhead `maxConcurrentCalls`, Mongo `maxPoolSize`, channel cache. Then **sweep offered concurrency** (for example 50 / 200 / 1000 / 5000 in-flight) and plot throughput, p50/p95/p99, live thread count, and RSS/heap. Platform threads hit their wall (thread count, memory, context switching) where VT do not. That curve is the honest result.
3. **Blocking I/O, not CPU.** The adapter does `Thread.sleep(latency)` (for example 50-200ms, fixed or seeded distribution) outside any lock. Failure rate = 0 and CB/bulkhead sized to avoid rejections during benchmark runs.
4. **Warm-up.** Discard the first 30-60s or a warm-up run. Run at least 3 repetitions and report the median.
5. **Open-model load.** k6 `constant-arrival-rate` / `ramping-arrival-rate`, or pre-load N messages into the queue and measure drain time for the worker.
6. **Measure end-to-end.** `sentAt - createdAt` from Mongo, or a Micrometer timer in the worker, alongside API latency. Pin the k6 image version and run k6 in compose so reviewers need nothing installed.
7. **Optional isolated micro-benchmark** (`newVirtualThreadPerTaskExecutor` vs `newFixedThreadPool(N)` running 10k sleeping tasks). It separates the thread-model effect from RabbitMQ/Mongo effects.
8. Publish hardware, JDK build, versions, raw numbers and the script in the README. State the limitations (single host, simulated I/O, Docker Desktop on Windows/WSL2 overhead).

**Warning signs:**
Identical results at every concurrency level (a bottleneck elsewhere), one run per mode, a VT win on CPU-bound work, or latency numbers that ignore queueing time.

**Phase to address:** P-Benchmark/Demo, which needs its own short design spike before coding.

---

## Moderate Pitfalls

### Pitfall 15: Ack mode and prefetch misconfiguration
- `AcknowledgeMode.NONE` (autoAck) means messages are lost when the worker crashes. Never use it here.
- `MANUAL` without `basicAck`/`basicNack` on **every** path (including exceptions) leaves messages unacked. Once `prefetch` messages are outstanding, the consumer stalls. The messages only come back when the channel closes.
- Spring's default prefetch (250) with slow adapters means one consumer hoards messages while others idle. On shutdown or rebalance, large batches are redelivered at once. Use a small prefetch (1-10) for slow, blocking handlers.
- `shutdownTimeout` (default 5s) is shorter than the slowest simulated send. In-flight messages are redelivered on every compose restart, which tests idempotency for you whether or not you planned it.
**Phase:** P-Worker.

### Pitfall 16: Per-message TTL reintroduces head-of-line blocking
Expiry/dead-lettering of per-message TTL (`expiration` property) only happens at the **head** of the queue. A 2m message blocks a 5s message behind it. The project avoids this with per-queue `x-message-ttl` per level. **Never also set `expiration`** on retry messages (some copy-paste examples do, and reprocess code may copy the original `MessageProperties`). Clear `expiration` when republishing.
**Phase:** P-Worker / P-Reprocess.

### Pitfall 17: Dead-letter cycle detection and quorum-queue delivery limits
- RabbitMQ drops a message that cycles through dead-lettering **with no rejection in the cycle**, for example if someone adds a TTL to the main queue so that both hops are `expired`. The design must keep a reject or an explicit publish on the main → delay hop.
- If quorum queues are chosen: since RabbitMQ 4.0 the default `delivery-limit` is **20**, after which messages are dead-lettered (or dropped if there is no DLX). Quorum queues also support at-least-once dead-lettering (`x-dead-letter-strategy=at-least-once`, which requires `overflow=reject-publish`), unlike classic queues, where dead-lettering has no internal confirms. Recommendation for a single-node demo: classic durable queues, with the DLX loss window documented. Quorum queues are mentioned as the production evolution.
**Phase:** P-Foundation.

### Pitfall 18: Spring Data MongoDB never creates your indexes
Since Spring Data MongoDB 3.0, **`auto-index-creation` defaults to false**. `@Indexed(unique = true)` and `@CompoundIndex` are silently ignored, so the idempotency unique index does not exist and duplicate inserts succeed. Create indexes explicitly at startup (`MongoTemplate.indexOps(...).ensureIndex(...)` in an `ApplicationRunner`, or `spring.data.mongodb.auto-index-creation=true`). **Assert their existence in an IT** with `indexOps().getIndexInfo()`.
**Phase:** P-Foundation / P-API.

### Pitfall 19: Compound index violates ESR, and skip-based pagination
- Query: `status=? & channel=?` sorted by `createdAt desc`. ESR (Equality → Sort → Range) means `{status: 1, channel: 1, createdAt: -1, _id: -1}`. Putting `createdAt` first, or a range before the sort, causes in-memory SORT stages (32MB-100MB sort memory limit errors at scale).
- Optional filters: when only `channel` is given, the index above cannot use its prefix. Either add `{channel: 1, createdAt: -1}` or always route queries through supported shapes. Justify each index in the README with `explain("executionStats")` output (`IXSCAN`, `totalDocsExamined ≈ nReturned`, no `SORT` stage).
- Spring Data `Page<T>` runs a `countDocuments` per request, and `skip` is O(offset). Prefer `Slice` or **keyset pagination** (`createdAt < lastCreatedAt OR (createdAt == lastCreatedAt AND _id < lastId)`), with `_id` as tie-breaker, and cap page size (for example ≤100).
- Do not index low-value fields separately (`{status:1}` alone). Write amplification buys nothing.
**Phase:** P-API.

### Pitfall 20: Unbounded embedded attempts array
The attempts history is bounded by max attempts, but reprocess cycles reset `attempts` and keep pushing history. Use `$push` with `$slice: -50` (or a cap), and keep a separate `attemptCount` field so the history length is not the counter.
**Phase:** P-Worker.

### Pitfall 21: Message conversion and type headers between modules
- The default `SimpleMessageConverter` uses Java serialization or byte[], which is insecure and opaque in the management UI. Configure `Jackson2JsonMessageConverter` (Spring AMQP 3.x; renamed/variant in 4.x) **on both** the API template and the worker container factory.
- `__TypeId__` header class names must resolve in the worker. Put event classes in `notify-common` and configure trusted packages.
- `MessageConversionException` is fatal in `ConditionalRejectingErrorHandler`. It is rejected without requeue (into the DLQ) and never touches Mongo. Log it with a dedicated metric.
**Phase:** P-Foundation / P-Worker.

### Pitfall 22: Failure misclassification (permanent errors retried, transient errors failed)
- JMustache by default renders missing variables as empty, or throws, depending on `defaultValue`/`strictSections`. Configure `Mustache.compiler().strictSections(true)` and **no** `defaultValue`/`nullValue`, so a missing variable throws `MustacheException`. Classify that as **permanent**.
- A template not found in Mongo is permanent. A Mongo timeout during template lookup is **transient**. Don't lump every `DataAccessException` together.
- Validate recipient format in the **API** (400) as well as in the worker, so obviously invalid requests never hit the queue.
**Phase:** P-Worker / P-Resilience. Unit-test the classifier with a table of exceptions.

---

## Minor Pitfalls

### Pitfall 23: Micrometer tag cardinality and naming
- Tags must be bounded: `channel` (3), `outcome` (sent/failed/retried/dlq), `reason` (enum of failure categories), `level` (retry level). **Never** use `notificationId`, recipient, template variables, exception messages, or raw HTTP paths with ids as tags.
- Counters appear in `/actuator/prometheus` only after the first increment. Grafana panels show "No data" at demo start. **Pre-register** counters for every channel × outcome at startup.
- Prometheus naming: `notify.sent` is exposed as `notify_sent_total`. Dashboards must use `rate(notify_sent_total[1m])`, not raw counter values. Restarts reset counters, and `rate()` handles that.
- Avoid enabling `publishPercentileHistogram` on high-tag timers without a reason, because series multiply.
**Phase:** P-Observability.

### Pitfall 24: Grafana/Prometheus provisioning in compose
- Prometheus scrape targets must use compose service names (`notify-api:8080`, `notify-worker:8081`), not `localhost`. Expose `management.endpoints.web.exposure.include=health,prometheus`.
- The dashboard JSON references a datasource **uid**. Give the provisioned datasource a fixed `uid` and use it in the dashboard, or panels show "datasource not found" on a fresh machine.
- Add the RabbitMQ Prometheus plugin endpoint (`rabbitmq_prometheus`, port 15692) to show queue depths per retry level. It is a strong visual for the demo.
**Phase:** P-Observability.

### Pitfall 25: Docker Compose startup ordering and healthchecks
- `depends_on` without `condition: service_healthy` only orders container **start**, not readiness. The apps race the broker, and the template seed fails on first boot.
- Healthchecks: RabbitMQ `rabbitmq-diagnostics -q check_port_connectivity` (or `-q ping`, which is weaker) with `start_period: 20s`. Mongo `mongosh --quiet --eval "db.adminCommand('ping').ok"`. Apps: actuator `/actuator/health`. Check the base image actually contains `curl`/`wget`, or use a tiny alternative. The load script service waits on `notify-api: service_healthy`.
- The template seed must be idempotent (upsert by template name). Otherwise the second `docker compose up` with a persisted volume fails with duplicate key errors.
- **Windows-specific (the author's platform):** CRLF line endings in `.sh` scripts mounted into Linux containers fail with `/bin/sh^M: bad interpreter`. Add `.gitattributes` (`*.sh text eol=lf`). Run the load script inside a container (`grafana/k6`, pinned tag), so reviewers need neither bash nor k6.
- Host port conflicts (5672, 15672, 27017, 3000, 9090) on the reviewer's machine. Document them, or expose only the ports needed.
- Pin image tags (`rabbitmq:4.x-management`, `mongo:8.x`). `latest` breaks reproducibility (Pitfall 4 shows how behavior shifts between RabbitMQ versions).
**Phase:** P-Foundation (infra skeleton), finalized in P-Benchmark/Demo.

### Pitfall 26: Testcontainers flakiness with TTL-based retries
- Production TTLs (5s/30s/2m) make the retry → DLQ IT take more than 2.5 minutes and time out. **Make TTLs configurable** (`notify.retry.levels=200ms,400ms,800ms` in the test profile). Combined with TTL-in-queue-name (Pitfall 7), no precondition conflicts.
- Never `Thread.sleep` in tests. Use **Awaitility** (`await().atMost(10, SECONDS).pollInterval(100, MILLISECONDS).untilAsserted(...)`) against **Mongo state** (status FAILED, attempts=4) and the DLQ message count.
- Random adapter failure makes assertions nondeterministic. Inject a scripted test adapter ("fail transiently N times, then succeed", or "always fail permanently") via a `@TestConfiguration`, or seed the RNG and set the failure rate to 1.0/0.0 per test.
- Shared static containers (faster) leak state between tests. Use unique ids per test and purge queues in `@BeforeEach`, or assert only on the test's own ids.
- Use `@ServiceConnection` (Boot 3.1+) for `RabbitMQContainer`/`MongoDBContainer` and pin image tags to match compose.
- Docker Desktop must be running on Windows. Testcontainers + WSL2 sometimes needs `TESTCONTAINERS_RYUK_DISABLED` workarounds. Document how to run, and keep ITs in a separate Maven profile/Failsafe so `mvn test` stays fast.
- The circuit breaker state leaks across tests (singleton registry). Reset breakers in `@BeforeEach`, or set a high `minimumNumberOfCalls` in the test profile except in CB-specific tests.
**Phase:** P-Testing (but configurable TTLs must exist from P-Worker on).

### Pitfall 27: Spring Boot 3.x line is past OSS support
Spring Boot 3.5's open-source support ended 2026-06-30 (vendor sources, MEDIUM). The project constraint says 3.x, so pin the **latest 3.5.x patch** and add a one-line note in the README ("Boot 3.5 por restrição do escopo; migração para 4.x é evolução"). Don't grab an older 3.2-3.4, which lacks fixes.
**Phase:** P-Foundation (STACK decision).

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| No outbox (save PENDING + publish with confirms) | Fits 1-week scope | Crash between save and publish leaves PENDING forever | Acceptable **if** README documents it and a "stuck PENDING" query/metric exists |
| Classic queues instead of quorum | Simple single-node demo | DLX hop has no confirms; no replication | Acceptable for demo; document quorum + at-least-once DLX as evolution |
| `x-death` as attempt counter | No extra header | Breaks across RabbitMQ versions; can't reset | Never; use app header + Mongo |
| `repository.save()` for status updates | Less code | Lost updates under concurrency | Never in worker; use atomic `$set`/`$push` |
| `Page<T>` with skip | Built into Spring Data | count query per request, O(offset) | Acceptable for admin listing if page size capped and documented; keyset preferred |
| `auto-index-creation=true` | One-liner | Index builds at app start, hidden in annotations | Acceptable for demo if an IT asserts indexes exist |
| Hardcoded prod TTLs | Fast to write | IT takes minutes / flaky | Never; make levels configurable from day one |
| API key compared with `equals` and stored in compose | Simple | Timing side-channel, key in repo | Acceptable for demo with `MessageDigest.isEqual` and env var |

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| Spring AMQP listener | Default requeue on exception → hot loop | `default-requeue-rejected=false`, explicit outcome routing |
| Spring AMQP template | Confirms on, returns off → unroutable acked as success | `correlated` + `publisher-returns` + `mandatory`, check `getReturned()` |
| RabbitMQ DLX | Missing `x-dead-letter-routing-key` on delay queues | Explicit DLX + routing key per delay queue; separate exchanges |
| RabbitMQ declare | Changing args on existing queue → 406 | TTL in queue name, `down -v` while iterating |
| MongoDB / Spring Data | Relying on `@Indexed` | Explicit `ensureIndex` + IT assertion |
| MongoDB driver | Pool 100 vs 1000s of virtual threads | Size pool and consumers together; monitor checkout wait |
| Resilience4j | ThreadPoolBulkhead with VT; default CB thresholds | Semaphore bulkhead; demo-tuned CB; ignore permanent exceptions |
| JMustache | Missing variable renders as empty | Strict compiler, missing var → permanent failure |
| Grafana | Dashboard references non-fixed datasource uid | Fixed uid in provisioning |
| Testcontainers | Prod TTLs, Thread.sleep, random failures | Short configurable TTLs, Awaitility, scripted adapters |

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| Prefetch 250 with slow blocking handler | Uneven consumers, big redelivery bursts | prefetch 1-10 | Any latency ≥ 50ms |
| Bulkhead 25 / wait 0 with more consumers | BulkheadFullException storm into retry queues | Size bulkhead ≥ consumers or add maxWaitDuration | Consumers > 25 per adapter |
| Mongo pool 100 under VT | Requests wait on pool checkout; latency cliff | Align pool, consumers, bulkhead | > 100 concurrent DB ops |
| Channel cache 25 with VT publishers | Channel open/close churn, broker CPU | Raise `spring.rabbitmq.cache.channel.size` | > 25 concurrent publishes |
| `synchronized` around blocking on Java 21 | Throughput ≈ #cores × (1/latency) | ReentrantLock / no lock; JFR pinning check | Concurrency > CPU cores |
| skip pagination + count | Slow GET /notifications | Keyset + Slice | 10^5+ docs |
| Per-message TTL in shared queue | Short delays wait behind long ones | Per-queue TTL per level (as planned) | Mixed delays in one queue |

## Security Mistakes

| Mistake | Risk | Prevention |
|---------|------|------------|
| Admin API key in source / compose committed with real value | Key leak | Env var with demo default, documented; constant-time compare (`MessageDigest.isEqual`) |
| Unbounded `max` on reprocess | DoS / message storm | Cap `max` (for example ≤ 1000), validate input |
| Java serialization message converter | Deserialization gadget attacks | Jackson JSON converter, trusted packages restricted to `notify-common` |
| Logging full recipient / variables | PII in logs (good habit to show in portfolio) | Mask recipient in logs; never as metric tags |
| Exposing all actuator endpoints | env/heapdump leak | Expose only `health,prometheus` (and `info`) |
| RabbitMQ `guest/guest` used from other containers | `guest` only allowed from localhost → connection refused, people "fix" by loosening security | Define a dedicated user via `RABBITMQ_DEFAULT_USER/PASS` env |

## UX Pitfalls (API consumer / reviewer experience)

| Pitfall | User Impact | Better Approach |
|---------|-------------|-----------------|
| `POST /notifications` returns 200 with body as if sent | Implies sync delivery | `202 Accepted` + `Location` header + id |
| GET shows status only | Reviewer can't see retry story | Return attempts with timestamps, level, error category |
| Demo needs manual steps | Reviewer gives up | `docker compose up` + one containerized script; README in pt-BR with expected screenshots |
| Retry delays too long for demo | Reviewer waits minutes to see DLQ | Demo profile with shorter levels (for example 2s/5s/10s), prod-like values documented |

## "Looks Done But Isn't" Checklist

- [ ] **Retry flow:** Often missing explicit `x-dead-letter-routing-key` on delay queues. Verify a message traverses 5s → 30s → 2m → DLQ in the management UI and in the Mongo history.
- [ ] **Publisher confirms:** Often missing returns/mandatory. Verify publishing to an unbound key yields PUBLISH_FAILED.
- [ ] **Worker retry publish:** Often fire-and-forget. Verify confirm-then-ack ordering in code review.
- [ ] **Idempotency:** Often a check-then-act. Verify the concurrent duplicate IT sends exactly once.
- [ ] **Unique index:** Often never created. Verify `getIndexInfo()` in the IT.
- [ ] **Reprocess:** Often swallowed by the idempotency guard. Verify FAILED → reprocess → SENT end-to-end.
- [ ] **Circuit breaker:** Often never opens with defaults. Verify the CB state gauge flips during a demo run with a raised failure rate.
- [ ] **Permanent failures:** Often retried. Verify a missing template variable goes straight to FAILED/DLQ with attempts=1.
- [ ] **Virtual threads:** Often enabled but irrelevant. Verify thread dump/JFR shows VT listener threads and no long pins.
- [ ] **Benchmark:** Often single-run, CPU-bound, closed-model. Verify it follows the Pitfall 14 checklist, raw data is committed, and the method is in the README.
- [ ] **Metrics:** Often missing until first event. Verify the Grafana dashboard renders zeros immediately after `compose up`.
- [ ] **Compose:** Often works only on the author's machine. Verify a fresh clone + `docker compose up` on a clean Docker (no volumes) and on Windows line endings.

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| Queue arg mismatch (406) | LOW | `docker compose down -v`, or rename queue with TTL suffix; delete old queue via management UI |
| Hot redelivery loop | LOW | Set `default-requeue-rejected=false`, purge queue, add regression IT |
| Lost messages in DLX hop / ack-before-publish | MEDIUM | Reorder to confirm-then-ack; add "stuck RETRYING/PENDING" query + metric to detect leftovers |
| Missing indexes / duplicates already written | MEDIUM | Dedupe collection, create unique index (fails while duplicates exist), add startup assertion |
| x-death-based counter | MEDIUM | Introduce app header + Mongo `attemptCount`; migrate in-flight messages by draining queues |
| Benchmark discredited | MEDIUM | Re-run with fair method; replace README numbers; keep raw data |
| Pinning discovered late | LOW-MEDIUM | Replace `synchronized` in project code; document library pins; optional JDK 25 runtime |

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| 3 Binding overlap / DL routing key | P-Foundation | Topology unit test + IT traversal of levels |
| 7 Arg mismatch PRECONDITION_FAILED | P-Foundation | Restart with changed TTL in dev without crash (TTL in name) |
| 17 Cycle detection / quorum limits | P-Foundation | Design note in ARCHITECTURE/README |
| 18 Indexes not created | P-Foundation / P-API | IT asserts `getIndexInfo()` |
| 21 Converter / type headers | P-Foundation | IT round-trip API → worker |
| 25 Compose healthchecks, CRLF, pinned tags | P-Foundation → P-Demo | Fresh-clone `compose up` on Windows |
| 27 Boot 3.5 OSS EOL | P-Foundation | STACK.md + README note |
| 6 Publisher confirms + returns | P-API | IT: unbound key → PUBLISH_FAILED; conditional update |
| 19 ESR index / keyset pagination | P-API | `explain()` shows IXSCAN, no SORT; output in README |
| 1 Default requeue loop | P-Worker | IT: poison message delivered < K times, ends in DLQ |
| 2 Stateless retry interceptor | P-Worker | Config review; attempt timing matches levels |
| 4 x-death counting | P-Worker | IT asserts attempt numbers 1..4 in Mongo |
| 5 Retry hop atomicity | P-Worker | Code review: confirm-then-ack; stuck-RETRYING metric = 0 |
| 8 Idempotency race | P-Worker | Concurrent duplicate IT: exactly one send |
| 11 VT concurrency model | P-Worker | Thread dump at load; documented sizing |
| 12 Pinning / ThreadLocal | P-Worker / P-Benchmark | JFR `jdk.VirtualThreadPinned` report |
| 15 Ack mode / prefetch | P-Worker | Restart under load: no stuck unacked, duplicates absorbed |
| 16 Per-message TTL HOL | P-Worker / P-Reprocess | Assert no `expiration` property on retry messages |
| 20 Unbounded history | P-Worker | `$slice` in update |
| 22 Failure classification / strict Mustache | P-Worker / P-Resilience | Classifier table-driven unit test; renderer unit test for missing var |
| 13 Resilience4j defaults | P-Resilience | CB opens in demo; no bulkhead rejections at nominal load |
| 9 Reprocess vs idempotency | P-Reprocess | IT: FAILED → reprocess → SENT |
| 10 Reprocess loss/re-loop | P-Reprocess | IT: republish failure keeps message in DLQ |
| 23 Metric cardinality / pre-registration | P-Observability | `/actuator/prometheus` shows bounded series at startup |
| 24 Grafana provisioning | P-Observability | Fresh volume → dashboard renders |
| 26 Testcontainers TTL flakiness | P-Testing (configurable TTLs from P-Worker) | IT suite < 2 min, 10 consecutive green runs |
| 14 Unfair benchmark | P-Benchmark | Method checklist satisfied; ≥3 runs; raw data committed |

## Sources

- RabbitMQ Dead Letter Exchanges docs (x-death semantics, `{queue, reason}` compression, cycle detection, routing-key behavior, quorum at-least-once DLX): https://www.rabbitmq.com/docs/dlx (HIGH, official)
- RabbitMQ Quorum Queues docs and 4.0 blog (default delivery-limit 20; 4.3 delivery-count change): https://www.rabbitmq.com/docs/quorum-queues, https://www.rabbitmq.com/blog/2024/08/28/quorum-queues-in-4.0 (HIGH)
- rabbitmq-server issue #11331 (x-death count not incremented on republish from 3.13; 3.13.3-only fix; maintainers: x-death not meant for apps): https://github.com/rabbitmq/rabbitmq-server/issues/11331 (MEDIUM)
- RabbitMQ 4.0 breaking-changes checklist (x-death not interpreted on client republish in 4.x): https://www.osseva.io/blog/rabbitmq-4-0-breaking-changes (LOW-MEDIUM, vendor blog; consistent with #11331)
- Spring Boot AMQP reference (retries disabled by default; indefinite redelivery; `defaultRequeueRejected`; `RejectAndDontRequeueRecoverer`): https://docs.spring.io/spring-boot/reference/messaging/amqp.html (HIGH)
- Spring Boot 3.2 release notes (virtual-thread executor auto-configured for RabbitMQ listener): https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.2-Release-Notes (HIGH)
- Spring AMQP template docs / CorrelationData API (returns before confirms, mandatory, unique correlation id): https://docs.spring.io/spring-amqp/reference/amqp/template.html (HIGH)
- RabbitMQ confirms docs (unroutable messages are acked): https://www.rabbitmq.com/docs/confirms (HIGH)
- rabbitmq-java-client issue #1276 (pinning with VT via synchronized java.io streams; open): https://github.com/rabbitmq/rabbitmq-java-client/issues/1276 (MEDIUM)
- Resilience4j Bulkhead docs (semaphore 25/0; thread-pool defaults): https://resilience4j.readme.io/docs/bulkhead (HIGH)
- Resilience4j CircuitBreaker docs (defaults 50%/100/100/60s; CallNotPermittedException): https://resilience4j.readme.io/docs/circuitbreaker (HIGH)
- Resilience4j README (3.x requires Java 21, VT schedulers option): https://github.com/resilience4j/resilience4j (MEDIUM)
- MongoDB Java driver 4.11 release notes (VT support, reduced pinning): https://www.mongodb.com/docs/drivers/java/sync/current/reference/release-notes/ (HIGH)
- JEP 491 (synchronized without pinning, JDK 24): https://openjdk.org/jeps/491 (HIGH, from training knowledge; widely cited)
- Spring Boot 3.5 OSS end of support 2026-06-30: https://www.osseva.io/eol/spring-boot-3-5 (MEDIUM, vendor sources agree)
- Training knowledge (not re-verified this session; MEDIUM): Spring Data MongoDB `auto-index-creation=false` since 3.0; MongoDB ESR guideline; RabbitMQ `channel_max` 2047; Spring AMQP default prefetch 250; CachingConnectionFactory channel cache 25; Mongo driver `maxPoolSize` 100; JMustache strictness options; k6 arrival-rate executors and coordinated omission.

---
*Pitfalls research for: multichannel notification service on RabbitMQ + MongoDB with Spring Boot 3.x, Resilience4j and Java 21 virtual threads*
*Researched: 2026-10-09*
