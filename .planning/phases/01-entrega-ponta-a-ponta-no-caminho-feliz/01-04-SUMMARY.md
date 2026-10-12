---
phase: 01-entrega-ponta-a-ponta-no-caminho-feliz
plan: 04
subsystem: api
tags: [spring-mvc, problem-details, rfc-9457, bean-validation, publisher-confirms, correlation-data, spring-amqp, spring-data-mongodb, webmvctest, mockito]

requires:
  - phase: 01-01
    provides: "Channel (routingKey), NotificationStatus, RecipientRules (isValid/describe/mask), NotificationMessage, NotifyHeaders"
  - phase: 01-02
    provides: "NotifyCommonConfiguration (Declarables, MessageConverter, MongoIndexInitializer), NotifyTopology.MAIN_EXCHANGE, NotificationDocument (+ Fields, newPending), LastError, AttemptEntry"
provides:
  - "POST /notifications: insert PENDING → publish com confirm correlacionado + mandatory → 202 + Location /notifications/{uuid} + {id, status, createdAt}"
  - "Publish não confirmado (nack, devolução, timeout, broker fora) → update condicional PENDING→PUBLISH_FAILED + 503 problem+json com Retry-After; worker já reivindicou → 202"
  - "GET /notifications/{id} com a representação completa de D-03; id inexistente → 404 problem+json"
  - "400 problem+json com errors[] {field, message} antes de qualquer gravação (constraints + CreateNotificationRequestValidator por canal e de variables)"
  - "ApiConfiguration: @Import(NotifyCommonConfiguration), runners createMongoIndexes (@Order 1) e declareTopology (@Order 2)"
  - "Propriedades notify.publish.confirm-timeout / retry-after (5s); application-demo.yml com notify.topology.retry-levels 1s,5s,25s"
affects: [01-05, 01-06, 02, 03, 04]

actuals:
  tokens: 14155
  tasks: 3
  commits: 6
plan_head_before: d1bedcd301c7f047d38bc549a406699321b46d76

tech-stack:
  added: []
  patterns:
    - "Configuração da app fora da classe @SpringBootApplication: @WebMvcTest não carrega o contrato inteiro"
    - "Publisher: CorrelationData com o id da notificação; getReturned() conferido antes de isAck()"
    - "A API só grava PENDING (insert) e PUBLISH_FAILED (update condicional a PENDING); nada depois de um ack"
    - "Erros HTTP como ErrorResponseException + ApiExceptionHandler (ResponseEntityExceptionHandler), instance = path quando nulo"
    - "Validação em duas camadas que caem no mesmo 400: constraints no record + Validator registrado por @InitBinder"

key-files:
  created:
    - notify-api/src/main/java/dev/notifyhub/api/config/ApiConfiguration.java
    - notify-api/src/main/resources/application.yml
    - notify-api/src/main/resources/application-demo.yml
    - notify-api/src/main/java/dev/notifyhub/api/web/NotificationController.java
    - notify-api/src/main/java/dev/notifyhub/api/web/dto/CreateNotificationRequest.java
    - notify-api/src/main/java/dev/notifyhub/api/web/dto/CreateNotificationResponse.java
    - notify-api/src/main/java/dev/notifyhub/api/web/dto/NotificationView.java
    - notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java
    - notify-api/src/main/java/dev/notifyhub/api/application/PublishFailedException.java
    - notify-api/src/main/java/dev/notifyhub/api/application/NotificationNotFoundException.java
    - notify-api/src/main/java/dev/notifyhub/api/messaging/NotificationPublisher.java
    - notify-api/src/main/java/dev/notifyhub/api/messaging/PublishResult.java
    - notify-api/src/main/java/dev/notifyhub/api/messaging/PublishProperties.java
    - notify-api/src/main/java/dev/notifyhub/api/web/ProblemTypes.java
    - notify-api/src/main/java/dev/notifyhub/api/web/ApiExceptionHandler.java
    - notify-api/src/main/java/dev/notifyhub/api/web/validation/CreateNotificationRequestValidator.java
    - notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java
    - notify-api/src/test/java/dev/notifyhub/api/messaging/NotificationPublisherTest.java
    - notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java
    - notify-api/src/test/java/dev/notifyhub/api/web/NotificationValidationTest.java
  modified: []

key-decisions:
  - "PublishResult: a fábrica de sucesso é acked() (par de nacked()); o nome ok() fica só para o boolean de instância, porque Java não aceita os dois com a mesma assinatura"
  - "Validador por canal instanciado pelo controller (não é bean): @WebMvcTest não escaneia Validator, e ele não tem estado"
  - "Mais de 50 variáveis gera um único erro, sem detalhar chave a chave, para limitar o tamanho da resposta a um payload abusivo"
  - "O update PUBLISH_FAILED usa um Instant novo (momento da falha, depois da espera do confirm) em lastError.at e updatedAt"
  - "Detail do 503 cita só o id e o errorCode; o motivo do nack/devolução fica no lastError do documento, nunca no corpo HTTP"

patterns-established:
  - "ProblemTypes guarda os type URNs (validation-error, publish-failed, not-found); novos erros HTTP devem estender ErrorResponseException"
  - "errors[] é uma lista de ApiExceptionHandler.FieldProblem(field, message); field nulo para erros que não são de campo"
  - "Testes de fatia da API: @WebMvcTest + @Import(serviço, publisher) + @EnableConfigurationProperties + @MockitoBean MongoTemplate/RabbitTemplate cujo convertAndSend completa o CorrelationData"

requirements-completed: [API-01, API-02, API-04, API-06, FND-04, MSG-05]

coverage:
  - id: D1
    description: "POST válido → 202 com Location /notifications/{uuid} e corpo {id, status PENDING, createdAt}; insert PENDING antes do publish em notify.exchange/notification.<canal> com messageId = id e notify-retry-count = 0"
    requirement: API-01
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java#postReturns202WithLocationAndMinimalBody"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java#insertsPendingBeforePublishingWithMessageIdAndRetryHeader"
        status: pass
    human_judgment: false
  - id: D2
    description: "Publish não confirmado (nack, devolução, timeout, broker fora) vira PUBLISH_FAILED por update condicional a PENDING e 503 problem+json com Retry-After 5; modifiedCount 0 (worker já reivindicou) responde 202; ack nunca grava status"
    requirement: API-02
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/messaging/NotificationPublisherTest.java"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java#nackedPublishAnswers503ProblemWithRetryAfter"
        status: pass
    human_judgment: false
  - id: D3
    description: "GET /notifications/{id} devolve a representação completa de D-03; id inexistente → 404 problem+json not-found"
    requirement: API-04
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java#getReturnsFullRepresentation"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/web/NotificationValidationTest.java#unknownIdIs404Problem"
        status: pass
    human_judgment: false
  - id: D4
    description: "Entrada inválida (13 casos + JSON malformado) → 400 problem+json validation-error com errors[] por campo e instance, sem tocar Mongo nem Rabbit; destinatários mágicos aceitos"
    requirement: API-06
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/web/NotificationValidationTest.java"
        status: pass
    human_judgment: false
  - id: D5
    description: "Ao subir, a API cria os índices e declara a topologia; com o profile demo usa os níveis 1s,5s,25s"
    requirement: FND-04
    verification:
      - kind: manual_procedural
        ref: "java -jar notify-api.jar --spring.profiles.active=demo contra docker compose: 15 filas notify.* com .1s/.5s/.25s em rabbitmqctl list_queues; getIndexes() = _id_, status_channel_createdAt, channel_createdAt, createdAt"
        status: pass
    human_judgment: true
    rationale: "Depende de broker e Mongo reais; conferido no smoke manual deste plano, mas a prova formal em runtime (compose completo, painel) é do plano 01-06"

duration: 11min
completed: 2026-10-12
status: complete
---

# Phase 1 Plan 04: API de notificações (criação, consulta, PUBLISH_FAILED e Problem Details) Summary

**`notify-api` completa da Fase 1: `POST /notifications` grava PENDING, publica com confirm correlacionado + mandatory e responde 202 + Location; publish não confirmado vira PUBLISH_FAILED por update condicional e 503 problem+json com `Retry-After`; `GET` devolve a visão de D-03; entrada inválida vira 400 com `errors[]` por campo antes de qualquer gravação**

## Performance

- **Duration:** 11 min
- **Started:** 2026-10-12T19:05:50Z
- **Completed:** 2026-10-12T19:16:26Z
- **Tasks:** 3
- **Files modified:** 20

## Accomplishments
- Criação ponta a ponta: o documento PENDING é inserido antes do publish. A mensagem vai para `notify.exchange` com routing key `notification.<canal>`, `messageId` = id e header `notify-retry-count` = 0, e a API espera o confirm por até `notify.publish.confirm-timeout` (5s). O 202 traz `Location: /notifications/{uuid}` e `{id, status, createdAt}`.
- Promessa "aceita = nunca perdida em silêncio": nack, devolução (`mandatory`), timeout e broker fora gravam PUBLISH_FAILED com `lastError` por um update condicional a `status = PENDING` e respondem 503 com `type` `urn:notify-hub:problem:publish-failed` e `Retry-After: 5`. Se o update casa 0 documentos (o worker já reivindicou), a API responde 202 (Armadilha 4). Depois de um ack a API não grava nada.
- Problem Details (RFC 9457) em toda a API via `ApiExceptionHandler`. O 400 traz `errors[] {field, message}` das constraints e do `CreateNotificationRequestValidator` (destinatário por canal com `RecipientRules`, até 50 variáveis, regex de chave, valores escalares, string até 1000). Canal desconhecido lista os valores aceitos, e JSON malformado não expõe mensagem interna. O 404 tem tipo `not-found`.
- `ApiConfiguration` importa o contrato comum e roda os runners de índices e topologia. O smoke manual contra o compose confirmou contexto completo, confirm real, 15 filas com os níveis do demo, 3 índices nomeados e o destinatário mascarado no log (`a***@exemplo.com`).
- 32 testes de fatia sem Docker: flow 4, publisher 5, serviço 3, validação 20.

## Task Commits

1. **Task 1: Criar e acompanhar ponta a ponta (tracer)**: `8280754` (test, RED) e `07ee30e` (feat, GREEN)
2. **Task 2: Publish não confirmado → PUBLISH_FAILED condicional + 503**: `2444e9e` (test, RED) e `54d7f8b` (feat, GREEN)
3. **Task 3: 400 por campo, 404 e profile demo**: `2f1b754` (test, RED) e `f17a12e` (feat, GREEN)

**Plan metadata:** commit do SUMMARY (docs)

## Files Created/Modified
- `notify-api/.../config/ApiConfiguration.java`: `@Import(NotifyCommonConfiguration)`, `PublishProperties` e os runners de startup
- `notify-api/src/main/resources/application.yml`: porta 8080, VT, Mongo/Rabbit por env, confirms correlacionados, returns, mandatory, cache de 50 canais, actuator `health,info`, `notify.publish.*`
- `notify-api/src/main/resources/application-demo.yml`: `notify.topology.retry-levels: 1s,5s,25s`
- `notify-api/.../web/NotificationController.java`: POST/GET e `@InitBinder` com o validador
- `notify-api/.../web/dto/*`: `CreateNotificationRequest` (D-01, constraints em pt-BR), `CreateNotificationResponse` (D-02), `NotificationView` + `AttemptView` (D-03)
- `notify-api/.../application/NotificationService.java`: `create` (insert → publish → PUBLISH_FAILED condicional), `find` e `get`
- `notify-api/.../application/PublishFailedException.java`, `NotificationNotFoundException.java`: `ErrorResponseException` com 503 + Retry-After e 404
- `notify-api/.../messaging/NotificationPublisher.java`, `PublishResult.java`, `PublishProperties.java`: publish com confirm e o resultado tipado
- `notify-api/.../web/ProblemTypes.java`, `ApiExceptionHandler.java`: URNs dos tipos e o handler de Problem Details
- `notify-api/.../web/validation/CreateNotificationRequestValidator.java`: regras por canal e de `variables`
- 4 classes de teste (32 testes)

## Decisions Made
- A fábrica de sucesso de `PublishResult` se chama `acked()`. Ver o desvio 1.
- O validador é instanciado pelo controller, não registrado como bean. O `@WebMvcTest` não escaneia `Validator`, e o validador não tem estado.
- Com mais de 50 variáveis sai um único erro, sem detalhar chave a chave (T-01-16). A chave citada na mensagem é truncada em 64 caracteres.
- O 503 cita só o id e o `errorCode`. O motivo do nack ou da devolução fica no `lastError` do documento (T-01-18).
- O timestamp do PUBLISH_FAILED é o momento da falha, depois da espera do confirm, e não o `createdAt`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fábrica `PublishResult.ok()` conflitava com o `boolean ok()`**
- **Found during:** Task 1 (GREEN)
- **Issue:** O plano pede a fábrica estática `ok()` e o método de instância `boolean ok()` no mesmo record. O Java recusa os dois (`method ok() is already defined`), porque ambos não têm parâmetros.
- **Fix:** A fábrica virou `acked()` (par de `nacked()`). O `boolean ok()` ficou, porque o ARCHITECTURE.md usa `p.ok()` no worker. O RESEARCH Padrão 4 cita `PublishResult.ok()` como fábrica; quem copiar esse trecho deve usar `acked()`.
- **Files modified:** notify-api/src/main/java/dev/notifyhub/api/messaging/PublishResult.java, NotificationPublisher.java
- **Verification:** `NotificationApiFlowTest` verde (3/3 na Task 1); `NotificationPublisherTest` verde na Task 2
- **Committed in:** 07ee30e

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Só o nome de uma fábrica. O comportamento e os critérios de aceitação são os do plano.

Nota de processo TDD:
- Task 1: o RED foi falha de compilação por falta das classes, a mesma convenção dos planos 01-01 a 01-03.
- Task 2: o RED foi falha de compilação por falta de `PublishFailedException`. O `NotificationPublisherTest` passaria antes do GREEN porque o plano põe o publisher na Task 1; ele trava o contrato de cada desfecho.
- Task 3: o RED foi por asserção. 7 falhas mostraram `about:blank` sem `errors[]` e 404 sem corpo, e 10 erros mostraram payload inválido aceito e gravado no Mongo. Os 3 casos de destinatário mágico já passavam e ficam como guarda de regressão.
- Em todas as tarefas o commit `test(01-04)` vem antes do `feat(01-04)`.

## Issues Encountered
- `./mvnw -q` não imprime a contagem de testes. A execução foi conferida nos relatórios do surefire (`tests`/`failures`/`errors`), o que descarta "No tests matching pattern".
- O smoke manual deixou uma mensagem em `notify.email` e o documento PENDING correspondente no volume do Mongo, porque ainda não há worker. Quando o worker do plano 01-05 subir, ele consome essa notificação. Os containers foram parados (`docker compose stop`), sem `down -v`.
- O helper `git.base-branch --is-protected` classifica `main` como protegida. O orquestrador declarou o commit em `main` como esperado (branching_strategy none, execução sequencial), como nos planos anteriores.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- O worker (01-05) consome `NotificationMessage` com `messageId` = id e header `notify-retry-count` = 0, e precisa reivindicar a partir de PENDING ou PUBLISH_FAILED (`claimableForDelivery`). O update da API casa só PENDING, então um claim do worker nunca é sobrescrito.
- O worker precisa usar os mesmos `notify.topology.retry-levels` no profile `demo` (1s,5s,25s).
- Runtime formal (compose completo, POST → SENT, 503 removendo binding, 400 com curl): plano 01-06.

---
*Phase: 01-entrega-ponta-a-ponta-no-caminho-feliz*
*Completed: 2026-10-12*

## Self-Check: PASSED
- Os 20 arquivos de key-files.created existem no disco
- Os commits 8280754, 07ee30e, 2444e9e, 54d7f8b, 2f1b754 e f17a12e estão no histórico
- `./mvnw -q -pl notify-api test` verde (32 testes, 0 falhas, 0 erros)
- Todos os critérios de aceitação das Tasks 1, 2 e 3 passaram (grep/teste)
