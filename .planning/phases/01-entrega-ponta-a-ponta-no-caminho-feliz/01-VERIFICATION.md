---
phase: 01-entrega-ponta-a-ponta-no-caminho-feliz
verified: 2026-10-12T23:06:00Z
status: gaps_found
score: 4/5 roadmap success criteria verified (18/19 requirements satisfied; SC3/API-06 partially failed)
covered_files:
  - .planning/REQUIREMENTS.md
  - .planning/phases/01-entrega-ponta-a-ponta-no-caminho-feliz/01-01-PLAN.md
  - .planning/phases/01-entrega-ponta-a-ponta-no-caminho-feliz/01-01-SUMMARY.md
  - .planning/phases/01-entrega-ponta-a-ponta-no-caminho-feliz/01-02-PLAN.md
  - .planning/phases/01-entrega-ponta-a-ponta-no-caminho-feliz/01-02-SUMMARY.md
  - .planning/phases/01-entrega-ponta-a-ponta-no-caminho-feliz/01-03-PLAN.md
  - .planning/phases/01-entrega-ponta-a-ponta-no-caminho-feliz/01-03-SUMMARY.md
  - .planning/phases/01-entrega-ponta-a-ponta-no-caminho-feliz/01-04-PLAN.md
  - .planning/phases/01-entrega-ponta-a-ponta-no-caminho-feliz/01-04-SUMMARY.md
  - .planning/phases/01-entrega-ponta-a-ponta-no-caminho-feliz/01-05-PLAN.md
  - .planning/phases/01-entrega-ponta-a-ponta-no-caminho-feliz/01-05-SUMMARY.md
  - .planning/phases/01-entrega-ponta-a-ponta-no-caminho-feliz/01-06-PLAN.md
  - .planning/phases/01-entrega-ponta-a-ponta-no-caminho-feliz/01-06-SUMMARY.md
  - docker-compose.yml
  - notify-api/pom.xml
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
  - notify-common/pom.xml
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
  - notify-worker/pom.xml
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
  - pom.xml
  - scripts/smoke-fase1.sh
covered_digest: "v1:sha256:3ab97fec6b55a083fbc32767cb3a75136330afd6705f79d18cbe5ef6f98c0c18"
behavior_unverified: 0
overrides_applied: 0
gaps:
  - truth: "SC3 / API-06: Payload inválido (canal desconhecido, campo ausente, destinatário fora do formato do canal) recebe 400 com Problem Details"
    status: partial
    reason: >-
      O campo `channel` aceita ordinais do enum. Verificado ao vivo contra a API empacotada:
      `{"channel":1,...}` → 202 (vira SMS) e `{"channel":"2",...}` → 202 (vira WEBHOOK), enquanto
      `{"channel":"email",...}` → 400. O contrato público (D-01) é a string EMAIL|SMS|WEBHOOK; um valor
      numérico não é um canal válido e deveria ser 400. Além de violar API-06, o roteamento passa a
      depender da ordem de declaração do enum (inserir uma constante redireciona clientes em silêncio).
      Mesmo achado que o WR-01 do 01-REVIEW.md.
    artifacts:
      - path: "notify-api/src/main/resources/application.yml"
        issue: "sem `spring.jackson.deserialization.fail-on-numbers-for-enums: true`"
      - path: "notify-api/src/main/java/dev/notifyhub/api/web/dto/CreateNotificationRequest.java"
        issue: "`Channel channel` vinculado pelo desserializador padrão do Jackson, que aceita ordinais"
      - path: "notify-api/src/test/java/dev/notifyhub/api/web/NotificationValidationTest.java"
        issue: "nenhum caso para canal numérico (`1` e `\"2\"`)"
    missing:
      - "Habilitar `spring.jackson.deserialization.fail-on-numbers-for-enums: true` no application.yml da API (o `ApiExceptionHandler.invalidChannel` já transforma o `InvalidFormatException` em 400 com errors[].field=channel)"
      - "Teste WebMvc: `\"channel\":1` e `\"channel\":\"2\"` → 400 application/problem+json, errors[].field = channel, nenhum insert no Mongo"
      - "Opcional: caso equivalente no scripts/smoke-fase1.sh"
human_verification:
  - test: "Abrir http://localhost:15672 (notify/notify) com API e worker no ar e conferir Exchanges e Queues"
    expected: "notify.exchange (topic), notify.retry e notify.dlx (direct); por canal a fila principal com DLX, retry.5s/25s/125s com TTL e a DLQ; notify.email.dlq com as mensagens rejeitadas pelo smoke"
    why_human: "O SC1 fala em 'visíveis no painel'. A API de gerenciamento (mesma fonte do painel) foi conferida automaticamente; a renderização visual da UI não."
  - test: "Com API no ar, abrir no navegador GET http://localhost:8080/notifications/{id} de uma notificação EMAIL entregue"
    expected: "JSON com status SENT, rendered.subject/body em pt-BR, attempts[0] com startedAt, durationMs e thread VirtualThread"
    why_human: "Item adiado do plano 01-06; o mesmo conteúdo foi conferido por curl no smoke, falta só a leitura humana do JSON."
---

# Phase 1: Entrega ponta a ponta no caminho feliz — Verification Report

**Phase Goal:** Um cliente cria uma notificação via `POST /notifications` e o worker a entrega pelo adapter simulado até SENT, sobre um contrato compartilhado e uma topologia RabbitMQ completa (filas principais, de delay e DLQs) já declarados e visíveis no painel.
**Verified:** 2026-10-12T23:06:00Z
**Status:** gaps_found
**Re-verification:** No — initial verification

The happy path, the topology, idempotency, DLQ parking, demo-profile TTLs and virtual threads all work. I checked them by running the build and both smoke variants myself, not by trusting the SUMMARYs. One input-validation gap stops a clean pass: a numeric `channel` is accepted with 202 instead of 400 (API-06 / SC3). The fix is one line plus a test.

Note on MVP mode: ROADMAP marks this phase `mode: mvp`, but the goal is not in "As a …, I want to …, so that …." form. I did not refuse to verify. The User Flow Coverage table below uses the client's flow (create → follow Location → SENT) as the outcome clause.

## User Flow Coverage

| Step | Expected | Evidence | Status |
|------|----------|----------|--------|
| Client POSTs a notification | 202 + `Location` + PENDING | smoke: EMAIL/SMS/WEBHOOK → 202, `Location /notifications/{id}`, PENDING | VERIFIED |
| Doc persisted before publish | PENDING insert, then publish with confirm | `NotificationService.create`: `insert` → `publisher.publish` (correlated confirm + mandatory) | VERIFIED |
| Worker consumes per channel | One listener container per channel | `ChannelListeners` (listener-email/sms/webhook); smoke: notify.email has 10 consumers | VERIFIED |
| Worker renders + sends (simulated) | Seed template rendered, adapter called | smoke: `rendered` in pt-BR, WEBHOOK envelope event/notificationId/data | VERIFIED |
| Client follows Location and sees SENT | SENT with 1 attempt (timestamp, durationMs) | smoke: all 3 channels SENT, attempt 1 on VirtualThread | VERIFIED |

## Goal Achievement

### Observable Truths (Roadmap Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | `docker compose up` brings up healthy RabbitMQ 4.3 + Mongo 8.0; `./mvnw verify` builds all 3 modules; on start the broker has notify.exchange/retry/dlx and, per channel, main queue + 3 delay queues with TTL in the name (5s/25s/125s) + DLQ; Mongo indexes exist | ✓ VERIFIED | `./mvnw -q verify` EXIT=0, 182 tests, 0 failures/errors/skips (24 surefire reports). Compose is pinned to `rabbitmq:4.3.6-management` and `mongo:8.0.32` with healthchecks; both report healthy. Management API: `notify.email` has `x-dead-letter-exchange=notify.dlx` and `x-dead-letter-routing-key=email.dlq`; each `retry.*` queue has `x-message-ttl` 5000/25000/125000 and dead-letters to `notify.exchange`/`notification.email`. Indexes: `status_channel_createdAt`, `channel_createdAt`, `createdAt`. Plugins enabled: rabbitmq_management + rabbitmq_prometheus (:15692/metrics → 200). |
| 2 | POST → 202 + Location; following Location, PENDING → SENT, rendered from the seed template, one attempt with timestamp and duration | ✓ VERIFIED | Smoke (default and demo): all 3 channels go 202 → SENT, `rendered` is set, `attempts[0]` has timestamp, durationMs and a VirtualThread. Code: `NotificationStateService.markSent` is a single `updateFirst` ($set + $unset + $inc + $push $each/$slice -50) behind a PROCESSING/retryCount fence. |
| 3 | Invalid payload (unknown channel, missing field, recipient wrong for the channel) → 400 Problem Details; unconfirmed/unroutable publish → 503 Problem Detail + Retry-After and PUBLISH_FAILED | ✗ FAILED (partial) | 503 part VERIFIED: with the binding removed, POST → 503, `Retry-After: 5`, `publish-failed`, and GET shows PUBLISH_FAILED. Named-string 400s VERIFIED (PUSH, missing recipient, non-E.164, dotted key; no Mongo writes). **FAILED:** live probe of the packaged API gave `"channel":1` → **202** (SMS) and `"channel":"2"` → **202** (WEBHOOK), while `"channel":"email"` → 400. Jackson's default enum binding accepts ordinals and there is no `fail-on-numbers-for-enums`. |
| 4 | A duplicate message produces no second send; a message the worker rejects goes to the channel DLQ, not back to the queue in a loop | ✓ VERIFIED | Smoke: a duplicate published through the management API is discarded (log `duplicada/obsoleta`) and attempts stays at 1. An unknown templateCode puts exactly one more message in `notify.email.dlq` and leaves `notify.email` empty. Code: conditional `findAndModify` claim; `default-requeue-rejected: false`; in-memory retry disabled. |
| 5 | `demo` profile → short-TTL delay queues (1s/5s/25s) with no code change; worker processes on virtual threads | ✓ VERIFIED | `smoke-fase1.sh --sem-build --perfil-demo` EXIT=0: `retry.1s/5s/25s` exist for all channels (TTL 1000 confirmed via management API) and the happy path still reaches SENT on a VirtualThread. `spring.threads.virtual.enabled: ${VIRTUAL_THREADS:true}`; there is no custom listener factory, so Boot's VT executor is kept. |

**Score:** 4/5 roadmap truths verified (0 present-but-behavior-unverified)

Plan-level must_haves (01-01..01-06) were checked against the code and the smoke run. All hold except the API-06 channel-coercion case above. The `verification: backstop` truths (Maven re-run safety, concurrent topology declaration, concurrent claim atomicity) are partly backed by observed behavior: the smoke restarts both apps on an existing topology with no PRECONDITION_FAILED, and a duplicate claim is rejected. Full concurrency proof is outside what one verification run can show. None of them is a roadmap success criterion.

### Prohibitions

| Plan | Prohibition | Tier | Disposition |
|------|-------------|------|-------------|
| 01-01 | No PII in AMQP body/headers; recipient masked in logs | test | Enforced: `NotificationMessageJsonTest.serializesExactlyTheFourTokenFields` asserts exactly {notificationId, channel, templateCode, createdAt}. The only header is `notify-retry-count`. Logs go through `RecipientRules.mask` (seen in the smoke logs as `a***@exemplo.com`). |
| 01-04 | No 202 for an unconfirmed publish the worker has not claimed | test | Enforced: `NotificationServiceTest.nackMarksPublishFailedConditionallyAndThrows` and `NotificationPublisherTest`, plus the live smoke (binding removed → 503). Caveat in WR-03 below (TIMEOUT may still be delivered). |
| 01-03 | No real delivery (no SMTP/SMS/HTTP) | judgment | Non-authoritative LLM verdict: holds. `SimulatedChannelAdapter` only sleeps and draws a random number, and the worker sources contain no `java.net`/HttpClient/RestTemplate/WebClient/mail/Socket usage. Human review recommended. |

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `pom.xml` + module POMs | Boot 3.5.16 parent, Java 21, 3 modules | ✓ VERIFIED | api and worker depend on notify-common |
| `mvnw`, `.mvn/wrapper/maven-wrapper.properties` | Maven 3.9.16 wrapper | ✓ VERIFIED | used for the verify run |
| `docker-compose.yml` | pinned images, healthchecks, 127.0.0.1 ports | ✓ VERIFIED | |
| `Channel`, `NotificationStatus`, `NotificationMessage`, `RecipientRules`, `NotifyHeaders` | shared contract | ✓ VERIFIED | used by api and worker |
| `NotifyTopology` / `TopologyProperties` / `NotifyCommonConfiguration` | single `Declarables` bean, configurable levels | ✓ VERIFIED | wired through `@Import` in `ApiConfiguration` and `WorkerConfiguration` |
| `MongoIndexInitializer` | explicit named indexes | ✓ VERIFIED | called by an `@Order(1)` runner in both apps |
| API: controller, service, publisher, exception handler, validator | 202/GET/400/404/503 | ⚠️ PARTIAL | everything works except the numeric-channel binding (gap) |
| Worker: listeners, `DeliveryProcessor`, `NotificationStateService`, `WorkerConfiguration` | claim → render → send → SENT | ✓ VERIFIED | listeners start only after indexes and seed |
| Templates: seed JSONs, `TemplateSeeder`, `TemplateRenderer` | 3 templates × 3 variants, idempotent seed | ✓ VERIFIED | smoke: 3 templates; renderer test passes |
| `SimulatedChannelAdapter` + `SimulationConfiguration` | one adapter per channel, latency/failure configurable | ✓ VERIFIED | |
| `scripts/smoke-fase1.sh` | end-to-end proof | ✓ VERIFIED | EXIT=0 in both profiles |

### Key Link Verification

| From | To | Via | Status |
|------|----|-----|--------|
| ApiConfiguration / WorkerConfiguration | NotifyCommonConfiguration | `@Import` | WIRED |
| NotifyCommonConfiguration | NotifyTopology | `NotifyTopology.declarables(props)` | WIRED |
| NotifyTopology | Channel | `ch.queue()/dlq()/retryQueue()/routingKey()` | WIRED |
| NotificationService | NotificationPublisher | `publisher.publish` after insert | WIRED |
| NotificationPublisher | NotifyTopology | `convertAndSend(NotifyTopology.MAIN_EXCHANGE, …, cd)` | WIRED |
| NotificationController | CreateNotificationRequestValidator | `@InitBinder addValidators` | WIRED |
| ChannelListeners | DeliveryProcessor | `processor.process(Channel.X, …)` | WIRED |
| DeliveryProcessor | TemplateRenderer / ChannelAdapter | `renderer.render` → `adapters.get(..).send` | WIRED |
| WorkerConfiguration | TemplateSeeder | `@Order(2)` runner `seed()` before `registry.start()` | WIRED |

### Data-Flow Trace (Level 4)

| Artifact | Data | Source | Real data | Status |
|----------|------|--------|-----------|--------|
| `GET /notifications/{id}` | NotificationView | `mongoTemplate.findById` | yes (smoke reads SENT + attempts) | ✓ FLOWING |
| Worker render | template + variables | `TemplateCatalog` (Mongo `templates`) + notification doc | yes | ✓ FLOWING |

### Behavioral Spot-Checks / Probe Execution

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Build and unit tests | `./mvnw -q verify` | EXIT=0; 182 tests, 0 fail/err/skip | ✓ PASS |
| End-to-end, default profile | `bash scripts/smoke-fase1.sh --sem-build` | EXIT=0, all `OK:` lines (topology, indexes, seed, 3×SENT on VT, 4×400, 503/PUBLISH_FAILED, duplicate, DLQ without loop) | ✓ PASS |
| End-to-end, demo profile | `bash scripts/smoke-fase1.sh --sem-build --perfil-demo` | EXIT=0, retry.1s/5s/25s present, happy path SENT | ✓ PASS |
| Broker plugins | `rabbitmq-plugins list -e -m`; `curl :15692/metrics` | management + prometheus enabled; 200 | ✓ PASS |
| Numeric channel rejected | `curl POST {"channel":1,...}` / `{"channel":"2",...}` against the packaged API | 202 / 202 | ✗ FAIL |

### Requirements Coverage

| Req | Source Plan | Status | Evidence |
|-----|-------------|--------|----------|
| FND-01 | 01-01, 01-06 | ✓ SATISFIED | multi-module build through the wrapper, Boot 3.5.16, Java 21 |
| FND-02 | 01-01, 01-02 | ✓ SATISFIED | Channel/Status/transitions/message/headers/topology constants in notify-common |
| FND-03 | 01-01 | ✓ SATISFIED | pinned images, healthchecks, management + prometheus plugin enabled |
| FND-04 | 01-02, 01-04, 01-05 | ✓ SATISFIED | explicit `createIndex` runners; indexes present |
| MSG-01 | 01-02 | ✓ SATISFIED | single `Declarables` bean, 3 exchanges |
| MSG-02 | 01-02 | ✓ SATISFIED | `notify.<ch>` bound by `notification.<ch>` |
| MSG-03 | 01-02 | ✓ SATISFIED | 5s/25s/125s queues, TTL in the name, DLX back to the main queue with an explicit key |
| MSG-04 | 01-02 | ✓ SATISFIED | main queue DLX → `notify.<ch>.dlq`; reject parks the message (smoke) |
| MSG-05 | 01-02, 01-04, 01-05 | ✓ SATISFIED | demo 1s/5s/25s verified live; ms labels supported |
| API-01 | 01-04 | ✓ SATISFIED | 202 + Location; insert before publish |
| API-02 | 01-04 | ✓ SATISFIED | correlated confirms + mandatory; conditional PUBLISH_FAILED; 503 + Retry-After (see WR-03 caveat) |
| API-04 | 01-04 | ✓ SATISFIED | GET with status, attempts, lastError; 404 Problem Detail |
| API-06 | 01-04 | ✗ BLOCKED (partial) | numeric/ordinal channel accepted with 202 |
| WRK-01 | 01-05 | ✓ SATISFIED | 3 containers, requeue-rejected false, retry off, prefetch 10 |
| WRK-02 | 01-05 | ✓ SATISFIED | conditional findAndModify claim; duplicate discarded (smoke) |
| WRK-04 | 01-05 | ✓ SATISFIED (Phase 1 scope: SENT) | single atomic update; the other outcomes come in Phase 2 by design |
| WRK-05 | 01-05 | ✓ SATISFIED | VT enabled, attempts on VirtualThread, configurable concurrency |
| TPL-01 | 01-03, 01-05 | ✓ SATISFIED | `templates` collection, 3 docs, idempotent versioned seed |
| DLV-01 | 01-03 | ✓ SATISFIED | one simulated adapter per channel, configurable latency and failure rate |

No orphaned requirements: all 19 phase-1 IDs in REQUIREMENTS.md appear in at least one plan's `requirements:`.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| (all phase files) | — | TBD/FIXME/XXX | — | none found |
| notify-api/.../CreateNotificationRequest.java + application.yml | 16-17 | lenient enum binding (WR-01) | 🛑 Blocker | API-06/SC3 gap above |
| notify-api/.../NotificationService.java | 49 | `Instant.now()` nanos vs Mongo millis (WR-02) | ⚠️ Warning | createdAt in the 202 differs from GET |
| notify-api/.../NotificationService.java / NotificationPublisher.java | 59-83 / 49-53 | TIMEOUT treated as a definite failure (WR-03) | ⚠️ Warning | possible duplicate if the client retries after a 503 that was really delivered; mitigated later by API-03 |
| notify-api/.../NotificationPublisher.java | 40,56 | raw exception text in lastError (WR-04) | ⚠️ Warning | infrastructure details leak via GET |
| notify-api/.../ApiExceptionHandler.java | 22-28 | Mongo/AMQP infra errors not mapped to Problem Details; orphan PENDING on a double failure (WR-05) | ⚠️ Warning | outside the SC3 wording; affects the core value under a dependency outage |
| notify-worker/.../NotificationStateService.java | 63-65 | claim not filtered by channel (WR-06) | ⚠️ Warning | cross-channel delivery is possible with a hand-crafted message |
| notify-worker/.../DeliveryProcessor.java | 93-114 | doc left PROCESSING after a post-claim failure | ℹ️ Info | known; Phase 2 (WRK-03, DLV-03) |
| notify-worker application.yml | — | MustacheAutoConfiguration active (deferred-items.md) | ℹ️ Info | cosmetic warning in the log |

### Deferred Items

None of the gaps match a later phase. The PROCESSING-after-failure behavior (IN-01) is Phase 2 scope (WRK-03, DLV-03) by roadmap design and is not counted as a Phase 1 gap.

### Human Verification Required

These two items do not change the status (gaps_found takes precedence). They remain the end-of-phase UAT items from plan 01-06:

#### 1. RabbitMQ management UI

**Test:** Open http://localhost:15672 (notify/notify) with API and worker running.
**Expected:** The 3 exchanges are listed. Per channel: the main queue (DLX args), retry.5s/25s/125s (TTL args) and the DLQ. `notify.email.dlq` holds the rejected messages.
**Why human:** This is a visual check. The same data was confirmed automatically through `/api/queues` and `/api/exchanges`.

#### 2. Browser GET of an EMAIL notification

**Test:** With the API running, open `/notifications/{id}` for a SENT EMAIL notification (for example 58c60c80-ac8d-4956-8cb5-86fc4ab8d6a6 from this run).
**Expected:** SENT, pt-BR subject/body, `attempts[0]` with startedAt, durationMs and a VirtualThread name.
**Why human:** Deferred readability check. The content was already confirmed by curl in the smoke.

### Gaps Summary

There is one gap, and it comes from a single root cause. The API's Jackson binding accepts enum ordinals for `channel`, so `1` and `"2"` are taken as SMS and WEBHOOK and get a 202. API-06 and SC3 promise a 400 Problem Detail for an invalid channel. The fix is the WR-01 fix:
- set `spring.jackson.deserialization.fail-on-numbers-for-enums: true` in `notify-api/src/main/resources/application.yml`;
- add a WebMvc test for both forms.

The existing `ApiExceptionHandler.invalidChannel` already produces the right 400 body. Everything else in the phase goal was shown to work end to end by my own runs of the build and both smoke variants.

Side effect of this verification: the numeric-channel probe created two valid PENDING notifications (83679994-d0e4-44f6-92c8-047f5363583c on SMS, 10ba7ccf-8b30-4541-bc1f-45fa77a3bee7 on WEBHOOK). Their messages are waiting in `notify.sms` and `notify.webhook` and will go to SENT the next time the worker starts. No API or worker processes were left running.

---

_Verified: 2026-10-12T23:06:00Z_
_Verifier: Claude (gsd-verifier)_
