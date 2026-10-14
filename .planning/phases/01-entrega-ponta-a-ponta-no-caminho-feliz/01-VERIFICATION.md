---
phase: 01-entrega-ponta-a-ponta-no-caminho-feliz
verified: 2026-10-13T00:51:07Z
status: passed
score: 5/5 roadmap success criteria verified (19/19 requirements satisfied)
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
  - .planning/phases/01-entrega-ponta-a-ponta-no-caminho-feliz/01-07-PLAN.md
  - .planning/phases/01-entrega-ponta-a-ponta-no-caminho-feliz/01-07-SUMMARY.md
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
  - notify-api/src/test/java/dev/notifyhub/api/web/NotificationValidationTest.java
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

covered_digest: "v1:sha256:0d36295d0373654f52f52ee3ee61d8e816ddbc8cde6d9cce8fa8c48e725df6b3"
behavior_unverified: 0
overrides_applied: 0
re_verification:
  previous_status: gaps_found
  previous_score: 4/5
  gaps_closed:
    - "SC3 / API-06: Payload inválido (canal desconhecido, campo ausente, destinatário fora do formato do canal) recebe 400 com Problem Details. Canal numérico (1 e \"2\") agora responde 400 problem+json com errors[].field = channel e nada é gravado no Mongo"
  gaps_remaining: []
  regressions: []
advisory: []
human_verification:

  - test: "Abrir http://localhost:15672 (notify/notify) com API e worker no ar e conferir Exchanges e Queues"
    expected: "notify.exchange (topic), notify.retry e notify.dlx (direct); por canal a fila principal com DLX, retry.5s/25s/125s com TTL e a DLQ; notify.email.dlq com as mensagens rejeitadas pelo smoke"
    why_human: "O SC1 fala em 'visíveis no painel'. A API de gerenciamento (mesma fonte do painel) foi conferida automaticamente pelo smoke; a renderização visual da UI não."
  - test: "Com API no ar, abrir no navegador GET http://localhost:8080/notifications/{id} de uma notificação EMAIL entregue (por exemplo e37a6216-7353-4c1c-9c70-e04f2078d380, desta execução)"
    expected: "JSON com status SENT, rendered.subject/body em pt-BR, attempts[0] com startedAt, durationMs e thread VirtualThread"
    why_human: "Item adiado do plano 01-06. O mesmo conteúdo foi conferido por curl no smoke; falta só a leitura humana do JSON."
---

# Phase 1: Entrega ponta a ponta no caminho feliz — Verification Report

**Phase Goal:** Um cliente cria uma notificação via `POST /notifications` e o worker a entrega pelo adapter simulado até SENT, sobre um contrato compartilhado e uma topologia RabbitMQ completa (filas principais, de delay e DLQs) já declarados e visíveis no painel.
**Verified:** 2026-10-13T00:51:07Z
**Status:** human_needed
**Re-verification:** Yes, after gap closure (plan 01-07)

The only gap from the previous report (SC3 / API-06, numeric `channel` accepted with 202) is closed. I confirmed it against the packaged jar, not just from the SUMMARY: running `bash scripts/smoke-fase1.sh --sem-build` myself, `"channel":1` and `"channel":"2"` each got 400 `application/problem+json` with `errors[].field = channel`, and the Mongo `notifications` count did not change across the 400 block (32 → 32). The other four success criteria showed no regression in the same run or in the demo-profile run.

All automated checks pass. The status is `human_needed` only because the two visual UAT items from plan 01-06 are still open: the management UI and reading the JSON in a browser.

## User Flow Coverage

| Step | Expected | Evidence (this run) | Status |
|------|----------|---------------------|--------|
| Client POSTs a notification | 202 + `Location` + PENDING | smoke: EMAIL/SMS/WEBHOOK → 202, `Location /notifications/{id}`, PENDING | VERIFIED |
| Doc persisted before publish | PENDING insert, then publish with confirm | Code unchanged since the initial verification; the 503 path still produces PUBLISH_FAILED (smoke) | VERIFIED |
| Worker consumes per channel | One listener container per channel | smoke: `notify.email` has 10 consumers; all 3 channels reached SENT | VERIFIED |
| Worker renders + sends (simulated) | Seed template rendered, adapter called | smoke: rendered content present, WEBHOOK envelope event/notificationId/data | VERIFIED |
| Client follows Location and sees SENT | SENT with 1 attempt (timestamp, durationMs) | smoke: e37a6216 (EMAIL), 288415d8 (SMS), 1e08a37e (WEBHOOK) are SENT, attempt 1 on a VirtualThread | VERIFIED |

## Goal Achievement

### Observable Truths (Roadmap Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Infra healthy; `./mvnw verify` builds all 3 modules; broker topology (3 exchanges; per channel main queue + 3 delay queues with TTL in the name + DLQ); Mongo indexes | ✓ VERIFIED (regression) | smoke this run: rabbitmq and mongo healthy; `OK: exchanges notify.exchange, notify.retry e notify.dlx`; `OK: por canal (email, sms, webhook): fila principal, DLQ e retry.5s/25s/125s`; indexes `_id_,status_channel_createdAt,channel_createdAt,createdAt`; 3 templates seeded. The orchestrator's `./mvnw -q verify` passed with 184 tests. I re-ran only the affected class, `NotificationValidationTest`: 22 tests, 0 failures/errors. |
| 2 | POST → 202 + Location; PENDING → SENT from the seed template, one attempt with timestamp and duration | ✓ VERIFIED (regression) | smoke this run: all 3 channels 202 → SENT, rendered, attempt 1 on a VirtualThread. After the binding was restored, a fresh EMAIL (23ec171d) also reached SENT. |
| 3 | Invalid payload → 400 Problem Details; unconfirmed/unroutable publish → 503 + Retry-After and PUBLISH_FAILED | ✓ VERIFIED (gap closed) | **Live, packaged jar (smoke this run):** `canal numérico 1 (ordinal de SMS) → 400 problem+json, errors[].field = channel` and `canal numérico em string 2 (ordinal de WEBHOOK) → 400 problem+json, errors[].field = channel`. Both payloads are otherwise valid for the channel the ordinal would select, so without the fix they would have returned 202. `espera_400` asserts status = 400, Content-Type contains `application/problem+json`, and `"field":"channel"` appears in the body. PUSH, missing recipient, non-E.164 SMS and dotted variable key also → 400. `nenhum 400 gravou no Mongo (notifications continua com 32 documentos)`. The 503 path still works: binding removed → 503, `Retry-After: 5`, `publish-failed`, and GET shows PUBLISH_FAILED. **Unit:** `numericChannelIs400WithOnlyChannelErrorAndNoWrite[1]` and `[2]` pass and assert one error {channel, "valores aceitos: EMAIL, SMS, WEBHOOK"} plus `verifyNoInteractions(mongoTemplate, rabbitTemplate)`, with a broker mock that acks (so a regression shows up as 202). **Code:** `spring.jackson.deserialization.fail-on-numbers-for-enums: true` is in `notify-api/src/main/resources/application.yml` and is also present in `BOOT-INF/classes/application.yml` inside the packaged jar. No custom `ObjectMapper` bean overrides Boot's (the only use is injection into the AMQP converter), and `application-demo.yml` does not override it. |
| 4 | A duplicate produces no second send; a rejected message goes to the channel DLQ, not back in a loop | ✓ VERIFIED (regression) | smoke this run: the duplicate of e37a6216 was discarded (log `duplicada/obsoleta`) and attempts stayed at 1. Unknown templateCode → `notify.email.dlq` got exactly one more message (4) and `notify.email` is empty. |
| 5 | `demo` profile → short-TTL delay queues (1s/5s/25s) with no code change; worker on virtual threads | ✓ VERIFIED (regression) | `smoke-fase1.sh --sem-build --perfil-demo` this run: EXIT=0; `retry.1s/5s/25s` on all 3 channels; EMAIL 64affc31 → SENT on a VirtualThread. |

**Score:** 5/5 roadmap truths verified (0 present-but-behavior-unverified)

### Plan 01-07 must_haves

| Truth | Status | Evidence |
|-------|--------|----------|
| `"channel":1` with a valid SMS payload → 400 validation-error, one error on channel, no Mongo/Rabbit call | ✓ VERIFIED | unit `[1]` passes (I ran it); smoke line OK |
| `"channel":"2"` with a valid WEBHOOK payload → same 400, no write or publish | ✓ VERIFIED | unit `[2]` passes; smoke line OK |
| EMAIL/SMS/WEBHOOK by name still 202; magic recipients do not regress | ✓ VERIFIED | smoke happy path for all 3 channels; `magicRecipientsAreAccepted` is in the 22/22 green class |
| Packaged jar answers 400 field=channel for both forms; notifications count unchanged | ✓ VERIFIED | smoke this run (count 32 → 32) |

### Required Artifacts (01-07)

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `notify-api/src/main/resources/application.yml` | `fail-on-numbers-for-enums: true` | ✓ VERIFIED | under the single root `spring:` block, at `spring.jackson.deserialization`; present in the packaged jar |
| `notify-api/src/test/java/dev/notifyhub/api/web/NotificationValidationTest.java` | `numericChannelIs400WithOnlyChannelErrorAndNoWrite` | ✓ VERIFIED | 2 parameterized rows, both pass |
| `scripts/smoke-fase1.sh` | `espera_400 "canal numérico ...` ×2 before `contagem_depois` | ✓ VERIFIED | executed; both OK |

The artifacts from plans 01-01..01-06 had a regression check through the smoke. Git shows no changes to production code under `notify-common` or `notify-worker` since the previous verification. In `notify-api`, `application.yml` is the only production file that changed.

### Key Link Verification

| From | To | Via | Status |
|------|----|-----|--------|
| application.yml (`fail-on-numbers-for-enums`) | ApiExceptionHandler.invalidChannel | Boot ObjectMapper → `InvalidFormatException(targetType=Channel)` → `FieldProblem("channel", ACCEPTED_CHANNELS)` | WIRED (proved live: field=channel in the response body) |
| NotificationValidationTest | application.yml | `@WebMvcTest` loads application.yml + JacksonAutoConfiguration | WIRED (the test was RED with 202 before the flag and is GREEN after, per commits 82f45fb → 39f513f; it is green now) |
| smoke-fase1.sh | packaged notify-api jar | `espera_400` against `$API_URL/notifications` before the count comparison | WIRED |
| Previous links (01-01..01-06) | — | — | WIRED (regression via smoke) |

### Data-Flow Trace (Level 4)

| Artifact | Data | Source | Real data | Status |
|----------|------|--------|-----------|--------|
| `GET /notifications/{id}` | NotificationView | `mongoTemplate.findById` | yes (smoke reads SENT + attempts) | ✓ FLOWING |
| Worker render | template + variables | `TemplateCatalog` (Mongo `templates`) + notification doc | yes | ✓ FLOWING |

### Behavioral Spot-Checks / Probe Execution

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Numeric-channel WebMvc slice | `./mvnw -q -pl notify-api -am test -Dtest=NotificationValidationTest` | EXIT=0; 22 tests, 0 fail/err/skip; `[1]` and `[2]` present | ✓ PASS |
| End-to-end, default profile | `bash scripts/smoke-fase1.sh --sem-build` | EXIT=0, `SMOKE FASE 1: OK`; includes both numeric-channel 400 lines and "nenhum 400 gravou no Mongo" | ✓ PASS |
| End-to-end, demo profile | `bash scripts/smoke-fase1.sh --sem-build --perfil-demo` | EXIT=0, retry.1s/5s/25s, EMAIL SENT on a VirtualThread | ✓ PASS |
| Jar freshness | `unzip -p notify-api-*.jar BOOT-INF/classes/application.yml` | contains `fail-on-numbers-for-enums: true`; the api jar was built 21:42, after fix commit 39f513f (21:41); the worker jar (16:32) is newer than the last worker/common commit (16:31) | ✓ PASS |
| Full build | `./mvnw -q verify` (orchestrator run, not repeated) | EXIT=0, 184 tests | ✓ PASS (reported) |

Both smoke runs shut down their API and worker JVMs. Only `java.exe` pid 6408 (the VS Code language server) is still running.

### Requirements Coverage

| Req | Source Plan | Status | Evidence |
|-----|-------------|--------|----------|
| FND-01 | 01-01, 01-06 | ✓ SATISFIED | multi-module build via wrapper (184 tests green) |
| FND-02 | 01-01, 01-02 | ✓ SATISFIED | shared contract unchanged |
| FND-03 | 01-01 | ✓ SATISFIED | pinned images, healthy, plugins |
| FND-04 | 01-02, 01-04, 01-05 | ✓ SATISFIED | indexes present (smoke) |
| MSG-01 | 01-02 | ✓ SATISFIED | 3 exchanges (smoke) |
| MSG-02 | 01-02 | ✓ SATISFIED | main queues and bindings (smoke) |
| MSG-03 | 01-02 | ✓ SATISFIED | retry.5s/25s/125s per channel (smoke) |
| MSG-04 | 01-02 | ✓ SATISFIED | reject → DLQ, no loop (smoke) |
| MSG-05 | 01-02, 01-04, 01-05 | ✓ SATISFIED | demo 1s/5s/25s (demo smoke) |
| API-01 | 01-04 | ✓ SATISFIED | 202 + Location |
| API-02 | 01-04 | ✓ SATISFIED | 503 + Retry-After + PUBLISH_FAILED (WR-03 caveat stands) |
| API-04 | 01-04 | ✓ SATISFIED | GET with status/attempts |
| API-06 | 01-04, 01-07 | ✓ SATISFIED | **gap closed**: numeric/ordinal channel → 400, no write (live and unit) |
| WRK-01 | 01-05 | ✓ SATISFIED | 3 containers, no requeue loop |
| WRK-02 | 01-05 | ✓ SATISFIED | duplicate discarded (smoke) |
| WRK-04 | 01-05 | ✓ SATISFIED (Phase 1 scope: SENT) | single atomic update |
| WRK-05 | 01-05 | ✓ SATISFIED | VirtualThread in attempts and logs |
| TPL-01 | 01-03, 01-05 | ✓ SATISFIED | 3 seeded templates |
| DLV-01 | 01-03 | ✓ SATISFIED | simulated adapters per channel |

No requirements are orphaned. Bookkeeping note: `.planning/REQUIREMENTS.md` still shows API-06 as `[ ]` / "Gaps Found". The orchestrator should flip it to Complete. That edit will change `covered_digest`, because REQUIREMENTS.md is a covered file.

### Prohibitions

Unchanged from the initial verification. Plan 01-07 adds none.

- 01-01 (test tier, no PII in AMQP): enforced by `NotificationMessageJsonTest`.
- 01-04 (test tier, no 202 for an unconfirmed publish): enforced by tests and by the live 503 in this run.
- 01-03 (judgment tier, no real delivery): non-authoritative LLM verdict "holds" (no network or mail APIs in the worker). Human review is recommended.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| 01-07 modified files | — | TBD/FIXME/XXX | — | none found |
| notify-api/.../NotificationService.java | 49 | `Instant.now()` nanos vs Mongo millis (WR-02) | ⚠️ Warning | createdAt in the 202 differs from GET |
| notify-api/.../NotificationService.java / NotificationPublisher.java | — | TIMEOUT treated as a definite failure (WR-03) | ⚠️ Warning | possible duplicate after a 503; mitigated later by API-03 |
| notify-api/.../NotificationPublisher.java | — | raw exception text in lastError (WR-04) | ⚠️ Warning | infrastructure details visible via GET |
| notify-api/.../ApiExceptionHandler.java | — | infrastructure errors not mapped; orphan PENDING on a double failure (WR-05) | ⚠️ Warning | outside the SC3 wording |
| notify-worker/.../NotificationStateService.java | — | claim not filtered by channel (WR-06) | ⚠️ Warning | needs a hand-crafted message |
| notify-worker/.../DeliveryProcessor.java | — | doc left PROCESSING after a post-claim failure | ℹ️ Info | Phase 2 scope (WRK-03, DLV-03); the smoke prints it as the known gap |

WR-01 (the previous blocker) is resolved. WR-02..WR-06 are the known non-blocking warnings from 01-REVIEW.md and are unchanged.

### Deferred Items

None. The PROCESSING-after-failure behavior is Phase 2 scope by roadmap design and is not counted as a Phase 1 gap.

### Human Verification Required

#### 1. RabbitMQ management UI

**Test:** Open http://localhost:15672 (notify/notify) with API and worker running.
**Expected:** The 3 exchanges are listed. Per channel: the main queue (DLX args), retry.5s/25s/125s (TTL args) and the DLQ. `notify.email.dlq` holds the rejected messages. The demo-profile run also left retry.1s queues in the broker.
**Why human:** This is a visual check. The same data was confirmed automatically through the management API in this run.

#### 2. Browser GET of an EMAIL notification

**Test:** With the API running, open `/notifications/e37a6216-7353-4c1c-9c70-e04f2078d380`, a SENT EMAIL from this run.
**Expected:** SENT, pt-BR subject/body, `attempts[0]` with startedAt, durationMs and a VirtualThread name.
**Why human:** Deferred readability check from plan 01-06. The content was already confirmed by curl in the smoke.

### Gaps Summary

There are no gaps left. The single previous gap (SC3 / API-06) is closed by `spring.jackson.deserialization.fail-on-numbers-for-enums: true`. Both the WebMvc slice and a live run against the packaged jar prove it, with nothing persisted for rejected payloads. The new smoke cases did not regress anything: the happy path on all 3 channels, the 503/PUBLISH_FAILED path, duplicate suppression, DLQ parking without a loop and the demo-profile TTLs all passed.

Side effects of this verification: the smoke created new notifications. These are e37a6216, 288415d8, 1e08a37e and 23ec171d (SENT), 73796133 (PUBLISH_FAILED) and 0040ca44 (PROCESSING, now in `notify.email.dlq`), plus 64affc31 (SENT, demo run). RabbitMQ and Mongo are still up. No API or worker JVMs are left running.

---

_Verified: 2026-10-13T00:51:07Z_
_Verifier: Claude (gsd-verifier)_
