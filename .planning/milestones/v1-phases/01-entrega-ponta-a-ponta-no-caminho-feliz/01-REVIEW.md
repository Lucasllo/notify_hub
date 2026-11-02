---
phase: 01-entrega-ponta-a-ponta-no-caminho-feliz
reviewed: 2026-10-12T19:56:35Z
depth: standard
files_reviewed: 62
files_reviewed_list:
  - docker-compose.yml
  - pom.xml
  - notify-api/pom.xml
  - notify-common/pom.xml
  - notify-worker/pom.xml
  - notify-api/src/main/java/dev/notifyhub/api/NotifyApiApplication.java
  - notify-api/src/main/java/dev/notifyhub/api/application/NotificationNotFoundException.java
  - notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java
  - notify-api/src/main/java/dev/notifyhub/api/application/PublishFailedException.java
  - notify-api/src/main/java/dev/notifyhub/api/config/ApiConfiguration.java
  - notify-api/src/main/java/dev/notifyhub/api/messaging/NotificationPublisher.java
  - notify-api/src/main/java/dev/notifyhub/api/messaging/PublishProperties.java
  - notify-api/src/main/java/dev/notifyhub/api/messaging/PublishResult.java
  - notify-api/src/main/java/dev/notifyhub/api/web/ApiExceptionHandler.java
  - notify-api/src/main/java/dev/notifyhub/api/web/NotificationController.java
  - notify-api/src/main/java/dev/notifyhub/api/web/ProblemTypes.java
  - notify-api/src/main/java/dev/notifyhub/api/web/dto/CreateNotificationRequest.java
  - notify-api/src/main/java/dev/notifyhub/api/web/dto/CreateNotificationResponse.java
  - notify-api/src/main/java/dev/notifyhub/api/web/dto/NotificationView.java
  - notify-api/src/main/java/dev/notifyhub/api/web/validation/CreateNotificationRequestValidator.java
  - notify-api/src/main/resources/application-demo.yml
  - notify-api/src/main/resources/application.yml
  - notify-common/src/main/java/dev/notifyhub/common/NotifyCommonConfiguration.java
  - notify-common/src/main/java/dev/notifyhub/common/domain/AttemptOutcome.java
  - notify-common/src/main/java/dev/notifyhub/common/domain/Channel.java
  - notify-common/src/main/java/dev/notifyhub/common/domain/FailureClass.java
  - notify-common/src/main/java/dev/notifyhub/common/domain/NotificationStatus.java
  - notify-common/src/main/java/dev/notifyhub/common/domain/RecipientRules.java
  - notify-common/src/main/java/dev/notifyhub/common/messaging/NotificationMessage.java
  - notify-common/src/main/java/dev/notifyhub/common/messaging/NotifyHeaders.java
  - notify-common/src/main/java/dev/notifyhub/common/messaging/NotifyTopology.java
  - notify-common/src/main/java/dev/notifyhub/common/messaging/TopologyProperties.java
  - notify-common/src/main/java/dev/notifyhub/common/messaging/TtlLabel.java
  - notify-common/src/main/java/dev/notifyhub/common/persistence/AttemptEntry.java
  - notify-common/src/main/java/dev/notifyhub/common/persistence/Collections.java
  - notify-common/src/main/java/dev/notifyhub/common/persistence/LastError.java
  - notify-common/src/main/java/dev/notifyhub/common/persistence/MongoIndexInitializer.java
  - notify-common/src/main/java/dev/notifyhub/common/persistence/NotificationDocument.java
  - notify-common/src/main/java/dev/notifyhub/common/persistence/RenderedContent.java
  - notify-worker/src/main/java/dev/notifyhub/worker/NotifyWorkerApplication.java
  - notify-worker/src/main/java/dev/notifyhub/worker/channel/ChannelAdapter.java
  - notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulatedChannelAdapter.java
  - notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulatedDeliveryException.java
  - notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulationConfiguration.java
  - notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulationProperties.java
  - notify-worker/src/main/java/dev/notifyhub/worker/config/WorkerConfiguration.java
  - notify-worker/src/main/java/dev/notifyhub/worker/listener/ChannelListeners.java
  - notify-worker/src/main/java/dev/notifyhub/worker/listener/RetryHeaders.java
  - notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java
  - notify-worker/src/main/java/dev/notifyhub/worker/processing/NotificationStateService.java
  - notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateCatalog.java
  - notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateDocument.java
  - notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateNotFoundException.java
  - notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateRenderer.java
  - notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateSeeder.java
  - notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateVariant.java
  - notify-worker/src/main/resources/application-demo.yml
  - notify-worker/src/main/resources/application.yml
  - notify-worker/src/main/resources/seed/templates/order-shipped.json
  - notify-worker/src/main/resources/seed/templates/password-reset.json
  - notify-worker/src/main/resources/seed/templates/welcome.json
  - scripts/smoke-fase1.sh
findings:
  critical: 0
  warning: 6
  info: 9
  total: 15
status: issues_found
---

# Phase 1: Code Review Report

**Reviewed:** 2026-10-12T19:56:35Z
**Depth:** standard
**Files Reviewed:** 62
**Status:** issues_found

## Summary

I reviewed the whole Phase 1 skeleton: the POMs, compose, the shared contract in `notify-common`, the API (create/get, publisher confirms, Problem Details), the worker (per-channel listeners, findAndModify claim, rendering, simulated adapter, seed) and the smoke script.

The core mechanics are sound:
- The topology arguments live in one place.
- Only durable queues are declared, with no CQv1 or lazy arguments.
- Listener factories are left to Boot, so the virtual-thread executor is kept.
- `default-requeue-rejected: false` is set and in-memory retry is off.
- Claims and results are single conditional updates.
- The seeder avoids the upsert-with-version-filter trap.
- The returned message is checked before `isAck()`.

I found no blockers. The six warnings are mostly at the API boundary:
- The enum binding accepts numeric channels. I confirmed this against Jackson 2.19.2.
- The `createdAt` in the 202 response does not match what GET returns.
- On a confirm timeout, the API returns 503 even though the message may still be delivered.
- Internal exception text leaks through `lastError.message`.
- Infrastructure failures fall outside the Problem Details contract and can leave an orphaned PENDING document.
- The worker trusts the Mongo document's channel without checking it against the queue it consumed from.

The two known deferred items are listed as Info only (IN-01, IN-02), as requested.

## Narrative Findings (AI reviewer)

## Warnings

### WR-01: `channel` accepts enum ordinals ("channel": 1 becomes SMS) and bypasses the API-06 "canal inválido → 400" contract

**File:** `notify-api/src/main/java/dev/notifyhub/api/web/dto/CreateNotificationRequest.java:16-17` (binding), `notify-api/src/main/resources/application.yml:4-26` (no Jackson override)
**Issue:** D-01 fixes the public channel as an uppercase string (`EMAIL | SMS | WEBHOOK`). Jackson's default enum deserializer still maps integer ordinals and quoted numeric strings to constants. Boot's `ObjectMapper` does not enable `FAIL_ON_NUMBERS_FOR_ENUMS`. I confirmed this with jshell against jackson-databind 2.19.2 and the compiled `Channel` class: `1` deserializes to `SMS` and `"2"` to `WEBHOOK`.

So `{"channel":1,"recipient":"+5511999998888",...}` returns 202 and is delivered over SMS, when it should be a 400. The per-channel recipient check does not catch this, because it validates against the coerced channel. The API also becomes coupled to the declaration order of the enum: inserting a constant (for example a future `PUSH` before `SMS`) would silently reroute existing clients.
**Fix:** In `notify-api/src/main/resources/application.yml`:
```yaml
spring:
  jackson:
    deserialization:
      fail-on-numbers-for-enums: true
```
With this flag both `1` and `"2"` throw `InvalidFormatException` with target type `Channel` (verified). `ApiExceptionHandler.invalidChannel` then already returns the "valores aceitos" 400. Add a WebMvcTest case for `"channel":1`.

### WR-02: The `createdAt` in the 202 body does not match the `createdAt` from GET (nanosecond vs millisecond precision)

**File:** `notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java:49,58,63`
**Issue:** `Instant now = Instant.now()` carries micro/nanosecond precision on JDK 21 (Windows and Linux). The same value goes into the 202 body (`CreateNotificationResponse`), the AMQP message (`NotificationMessage.createdAt`) and the Mongo document. BSON dates only store milliseconds. As a result, the 202 returns something like `2026-10-12T19:00:00.123456700Z` while `GET /notifications/{id}` returns `2026-10-12T19:00:00.123Z` for the same notification.

Clients that correlate the two, and the Phase 3 list ordering and pagination cursors, will see two different creation timestamps.
**Fix:**
```java
Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
```
Apply the same change to `failedAt` (line 72) and to the worker's `Instant.now()` calls in `NotificationStateService`, so that every persisted and echoed instant uses the precision Mongo stores.

### WR-03: A confirm TIMEOUT answers 503 "PUBLISH_FAILED, tente novamente", but the message may still be delivered, so a client retry duplicates the notification

**File:** `notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java:59-60,80-83`, `notify-api/src/main/java/dev/notifyhub/api/messaging/NotificationPublisher.java:49-53`, `notify-api/src/main/java/dev/notifyhub/api/application/PublishFailedException.java:31-32`, `notify-common/src/main/java/dev/notifyhub/common/domain/NotificationStatus.java:29`
**Issue:** A confirm timeout does not mean the broker lost the message. A late ack or a slow-but-successful route is common under load, and that is exactly the k6 scenario. The sequence is:
1. The API marks the document PUBLISH_FAILED and returns 503 with `Retry-After: 5`. The detail text tells the client to try again.
2. `claimableForDelivery()` deliberately includes PUBLISH_FAILED, so the worker later claims the original and sends it (SENT).
3. The client, following the 503, POSTs again and gets a new id.

The recipient receives the notification twice, and the client was told the first one failed. The race guard on lines 80-85 only helps when the worker claims *before* the API's update. It does not help when the message is still sitting in the queue during the 5s wait. Idempotency-Key (API-03) is a Phase 3 item, so in this phase nothing mitigates the duplicate.
**Fix:** Distinguish an *uncertain* outcome from a *definite* failure:
- For `TIMEOUT` (and channel-closed NACKs), either return 202 with the document left PENDING and let a Phase 2/3 sweeper resolve it, or return 503 with a different problem type/detail (for example `publish-unconfirmed`) that says the notification may still be delivered and should be checked at `Location` before retrying.
- Reserve the "registrada como PUBLISH_FAILED, tente novamente" wording for `RETURNED` and `BROKER_UNAVAILABLE`, where non-delivery is certain.
- At minimum, record this explicitly as a known duplicate window until API-03 lands.

### WR-04: Internal exception text is persisted in `lastError.message` and exposed by `GET /notifications/{id}`

**File:** `notify-api/src/main/java/dev/notifyhub/api/messaging/NotificationPublisher.java:40,56`, `notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java:77`, `notify-api/src/main/java/dev/notifyhub/api/web/dto/NotificationView.java:25`
**Issue:** `PublishResult.brokerUnavailable(e.getMessage())` and `nacked(cause.getMessage())` store raw `AmqpException`/`ExecutionException` messages, for example `java.net.ConnectException: Connection refused: getsockopt` or channel-close reasons with broker internals. `markPublishFailed` writes them to `lastError.message`, and `NotificationView` returns `lastError` verbatim.

`PublishFailedException`'s Javadoc says the body "cita só o id e o código do erro, nunca a mensagem interna", and `ApiExceptionHandler` says "Nenhuma mensagem interna de exceção ... vai no corpo". The GET path breaks that policy one request later, by disclosing infrastructure details to any caller who has the id.
**Fix:** Store a fixed, client-safe pt-BR message per outcome and log the raw text server-side:
```java
public String publicMessage() {
    return switch (outcome) {
        case ACK -> null;
        case NACK -> "o broker recusou a publicação";
        case RETURNED -> "mensagem sem rota no broker";
        case TIMEOUT -> "o broker não confirmou a publicação a tempo";
        case BROKER_UNAVAILABLE -> "broker indisponível";
    };
}
// NotificationService: new LastError(result.errorCode(), result.publicMessage(), null, failedAt)
// and log.warn("... detalhe={}", result.detail())
```

### WR-05: Infrastructure failures fall outside the Problem Details contract, and a failure in `markPublishFailed` leaves an orphaned PENDING document

**File:** `notify-api/src/main/java/dev/notifyhub/api/web/ApiExceptionHandler.java:22-28`, `notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java:52-53,73-79`
**Issue:**
1. `ApiExceptionHandler` only extends `ResponseEntityExceptionHandler`, which covers Spring MVC exceptions and `ErrorResponseException`. A `DataAccessException`/`MongoTimeoutException` from `insert`, `updateFirst` or `findById` (Mongo down, by default after a 30s server-selection wait) falls through to Boot's `BasicErrorController`. That returns `application/json` with `{timestamp,status,error,path}` and status 500, not `application/problem+json`, even though the class Javadoc says "Problem Details (RFC 9457) para toda a API". An outage of a dependency is a 503 condition, not a 500.
2. If the publish fails and then `markPublishFailed`'s `updateFirst` throws (Mongo flapping), the document stays **PENDING** with no message in any queue. No component in the roadmap reclaims PENDING documents: WRK-03 only covers PROCESSING, and the Phase 3 admin republish targets PUBLISH_FAILED. GET shows "PENDING" forever. That undercuts the core value ("nunca se perde em silêncio").
**Fix:** Add a fallback handler that maps `DataAccessResourceFailureException`/`MongoException` and `AmqpException` to 503 Problem Details (`urn:notify-hub:problem:dependency-unavailable`, plus `Retry-After`), and a generic `Exception` handler that returns a 500 Problem Detail with no message. For the orphan, catch the failure around `markPublishFailed` and log the id at ERROR with a stable marker so it can be found. Also add "PENDING older than N minutes" to the Phase 2/3 sweeper scope (or treat stale PENDING like PUBLISH_FAILED in the admin republish).

### WR-06: The worker trusts `doc.channel()` without checking it against the listener channel, so one queue can drive delivery on another channel

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java:87-90,93,101,105`, `notify-worker/src/main/java/dev/notifyhub/worker/processing/NotificationStateService.java:63-65`
**Issue:** `process` rejects a message whose *payload* `channel` differs from the listener's queue (lines 87-90). It then claims by id only, and renders and sends using `doc.channel()` (lines 101, 105). A message whose payload says `EMAIL` but whose `notificationId` points to an SMS document will:
- be claimed (the claim filter has no channel predicate);
- be rendered with the SMS variant;
- be sent through the SMS adapter, on a thread of the EMAIL container.

That breaks the per-channel isolation WRK-01 is built around. In Phase 2 it would also charge the wrong channel's circuit breaker and bulkhead. This can be reached through any publish with a mismatched pair: the management UI (which the smoke script itself uses), a bug in the Phase 3 republish, or a hand-built message. The existing guard gives a false sense of coverage.
**Fix:** Make the claim conditional on the channel too, so a mismatch becomes "duplicada/obsoleta" (or reject it explicitly):
```java
public Optional<NotificationDocument> claim(String id, Channel channel, int retryCount) {
    Query claimable = query(where(Fields.ID).is(id)
            .and(Fields.CHANNEL).is(channel)
            .and(Fields.STATUS).in(NotificationStatus.claimableForDelivery())
            .and(Fields.RETRY_COUNT).lte(retryCount));
    ...
}
```
Call it with `listenerChannel` from `DeliveryProcessor`.

## Info

### IN-01: Known gap (deferred to Phase 2): failures after the claim leave the document in PROCESSING, and unknown templates are accepted with 202

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java:93-114`
**Issue:** This item is listed for completeness and is not a new defect. The claim happens before `catalog.require` and `renderer.render`. Any later exception (unknown `templateCode`, missing variable, adapter failure, or a Mongo error inside `markSent`) DLQs the message and leaves the document PROCESSING. In the `markSent` case the notification was actually "sent", so a later DLQ reprocess would deliver it twice. Related: when the claim itself fails because Mongo is down, the message is DLQ'd while the document stays PENDING. The API never checks `templateCode` or `requiredVariables`, so `"templateCode":"nao-existe"` gets a 202 (the smoke script relies on this). `requiredVariables` is loaded but not read anywhere yet.
**Fix:** Covered by DLV-03/DLV-06/TPL-02/WRK-03 in Phase 2. Make sure the classifier treats a `markSent` persistence failure after a successful send as "do not resend" rather than as a transient retry.

### IN-02: Known deferred item: `MustacheAutoConfiguration` is activated by jmustache on the classpath

**File:** `notify-worker/src/main/resources/application.yml:4-6`
**Issue:** This is already tracked in `deferred-items.md`. The worker logs `Cannot find template location: classpath:/templates/` and registers an MVC Mustache view resolver it never uses.
**Fix:** `spring.autoconfigure.exclude: org.springframework.boot.autoconfigure.mustache.MustacheAutoConfiguration`.

### IN-03: The WEBHOOK body and EMAIL subject are rendered unescaped (TPL-03 deferred)

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateRenderer.java:30,50,52`
**Issue:** This is intended for Phase 1, per CONTEXT. A client value such as `name = "Ana\",\"admin\":true,\"x\":\""` produces a syntactically valid webhook envelope with injected fields. Values with CR/LF go into the e-mail subject unchanged, which matters once a real adapter exists. The API validator only checks length and type, not content.
**Fix:** In TPL-03, use a JSON-escaping `Escaper` for WEBHOOK and strip or reject CR/LF in values destined for the subject.

### IN-04: Seed templates are only compiled at first use, so a broken seed fails at runtime instead of at startup

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateSeeder.java:118-125`, `notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateRenderer.java:56-58`
**Issue:** `read` checks only `id` and a non-empty `variants`. A variant with a null `body` (NPE inside `compiler.compile(null)`) or a Mustache syntax error is found only when the first message for that template arrives. That message is DLQ'd and its document is stranded in PROCESSING (IN-01), and the same happens to every later message for that template.
**Fix:** In `read`, require a non-null `body` for each variant (and a `subject` for EMAIL), and call `Mustache.compiler().compile(...)` once per variant so the context fails to start on a bad seed.

### IN-05: `PublishProperties` is not validated, and `Retry-After` truncates to whole seconds

**File:** `notify-api/src/main/java/dev/notifyhub/api/messaging/PublishProperties.java:14-16`, `notify-api/src/main/java/dev/notifyhub/api/application/PublishFailedException.java:24`
**Issue:**
- `confirm-timeout: 0` (or a negative value) makes every publish time out immediately, so every POST returns 503.
- `retry-after: 500ms` produces `Retry-After: 0`, and a negative value produces an invalid header.
- `TopologyProperties` validates its input in the compact constructor, but this record does not.
**Fix:** Add a compact constructor that rejects non-positive values and requires `retryAfter >= 1s`, or round up with `Math.max(1, (retryAfter.toMillis() + 999) / 1000)`.

### IN-06: `ApiExceptionHandler.handleExceptionInternal` re-implements what Spring MVC already does

**File:** `notify-api/src/main/java/dev/notifyhub/api/web/ApiExceptionHandler.java:59-68`
**Issue:** In Spring 6.x, `HttpEntityMethodProcessor` already sets `ProblemDetail.instance` from the request URI when it is null, so the override is dead weight. `PublishFailedException.getResult()` (line 41) is also never called.
**Fix:** Remove the override, or keep it with a comment explaining why. Remove `getResult()`/`result` until something needs them.

### IN-07: The `Number` branch of `RetryHeaders.parse` is unreachable from the listeners

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/listener/RetryHeaders.java:28-32`, `notify-worker/src/main/java/dev/notifyhub/worker/listener/ChannelListeners.java:41,49,57`
**Issue:** All three listeners bind the header as `String`, so `parse` never receives a `Number`. A numeric header that Spring converts to text, such as a `Double` 1.0 becoming `"1.0"`, is rejected by `Long.parseLong`, whereas the `Number` branch would have accepted it. The tests exercise a path that production never takes.
**Fix:** Either change the signature to `parse(String)` and drop the branch, or document that the `Number` path exists only for the Phase 2 republisher's direct use.

### IN-08: The class name `dev.notifyhub.common.persistence.Collections` shadows `java.util.Collections`

**File:** `notify-common/src/main/java/dev/notifyhub/common/persistence/Collections.java:4`
**Issue:** Any class in the `persistence` package that needs `java.util.Collections` must fully qualify it, and IDE auto-import can pick the wrong one. `NotificationStatus` already imports `java.util.Collections` in a sibling package, so the clash is one refactor away.
**Fix:** Rename the class to `MongoCollections` or `CollectionNames`.

### IN-09: Demo credentials and an unauthenticated Mongo are hardcoded; the smoke script has brittle and destructive steps

**File:** `docker-compose.yml:10-11,25-30`, `notify-api/src/main/resources/application.yml:16-17`, `notify-worker/src/main/resources/application.yml:17-18`, `scripts/smoke-fase1.sh:290,401-403`
**Issue:**
- `notify/notify` is committed as the RabbitMQ credential and Mongo runs without auth. The ports are bound to `127.0.0.1`, so this is acceptable for the demo, but the README's limitations section should say so.
- The smoke script asserts `templates == 3`, which breaks as soon as Phase 3 admin adds a template.
- The script deletes a live binding on whatever broker is running.
**Fix:**
- Document the demo-only credentials.
- Make the template check `>= 3` and assert the three seed ids.
- Keep the trap-based binding restore. It is already present and correct.

---

_Reviewed: 2026-10-12T19:56:35Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
