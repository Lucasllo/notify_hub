---
phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o
plan: 10
subsystem: api
tags: [publish-confirm, mongodb, logback, problem-detail, gap-closure, tdd]

# Dependency graph
requires:
  - phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o
    provides: "03-05: NotificationRepublisher como serviço único de publish/republish; 03-08: varredura de PENDING sem confirm (UnconfirmedPublishSweeper, markUnconfirmedAsPublishFailed, PUBLISH_UNCONFIRMED)"
provides:
  - "NotificationRepublisher.wasClaimedByWorker(NotificationStatus): evidência de claim (não nulo, fora de PENDING/PUBLISH_FAILED)"
  - "publishOrMarkFailed relê o documento quando o update condicional PENDING → PUBLISH_FAILED casa 0; sem evidência de claim é falha (503 na criação, FAILED no republish)"
  - "publishOrMarkFailed com log ERROR (id, código, classe da exceção) e relançamento em DataAccessException"
  - "Detail do 503 publish-failed sem afirmar o status gravado"
  - "Detail do 503 republish-interrupted verdadeiro também para o documento que ficou PENDING (cita a varredura)"
affects: [03-09, verificacao-fase-03, fase-04-demo]

# Actuals (#2632) — chars/4 over the realized diff
actuals:
  tokens: 7862
  tasks: 2
  commits: 4
plan_head_before: f422de9729d4f53eff4de808e5c7ed49ae86b2df

tech-stack:
  added: []
  patterns:
    - "Update condicional que casa 0 nunca é prova sozinho: relê o documento e decide por um predicado derivado da tabela de status (wasClaimedByWorker)"
    - "Captura de log em teste unitário com ListAppender do logback preso ao logger da classe (attach no @BeforeEach, detach no @AfterEach), sem depender do layout"
    - "DataAccessException num ponto de escrita crítica: ERROR só com id, código fechado e getClass().getSimpleName(), depois relança a mesma exceção"

key-files:
  created: []
  modified:
    - notify-api/src/main/java/dev/notifyhub/api/application/NotificationRepublisher.java
    - notify-api/src/main/java/dev/notifyhub/api/application/PublishFailedException.java
    - notify-api/src/main/java/dev/notifyhub/api/application/RepublishInterruptedException.java
    - notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java
    - notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java
    - notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java
    - notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java

key-decisions:
  - "03-10: update PENDING → PUBLISH_FAILED que casa 0 é relido; só status com evidência de claim (wasClaimedByWorker: não nulo, nem PENDING, nem PUBLISH_FAILED) segue como publicado. PUBLISH_FAILED, PENDING ou documento ausente viram 503 (criação) ou FAILED (republish)"
  - "03-10: DataAccessException na marcação ou na releitura gera ERROR com id, código e classe da exceção e é relançada (criação continua 500 genérico); o órfão PENDING sai pela varredura do 03-08"
  - "03-10: details dos 503 não afirmam status que o documento pode não ter: publish-failed diz só que o broker não confirmou; republish-interrupted cita a varredura de publicações sem confirm"

patterns-established:
  - "Evidência de claim do worker centralizada em NotificationRepublisher.wasClaimedByWorker, para o 03-09 reutilizar no replay"

requirements-completed: [API-03, ADM-03]

coverage:
  - id: D1
    description: "POST com publish sem confirm, update condicional que casa 0 e documento já PUBLISH_FAILED responde 503 com Retry-After 5, type publish-failed e detail que começa com 'O broker não confirmou a publicação da notificação', contém o id e PUBLISH_NACK e não contém o texto do nack (tracer de ponta a ponta na fatia web)"
    requirement: "API-03"
    verification:
      - kind: integration
        ref: "notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java#unconfirmedPublishWhoseDocumentIsAlreadyPublishFailedAnswers503WithATrueDetail"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java#timeoutWhoseConditionalUpdateMatchesNothingAndTheDocumentIsPublishFailedIs503"
        status: pass
    human_judgment: false
  - id: D2
    description: "Update que casa 0 com releitura PROCESSING continua 202 na criação e REPUBLISHED no republish; releitura PUBLISH_FAILED ou documento ausente é FAILED com o código do publish"
    requirement: "ADM-03"
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java#timeoutAfterWorkerAlreadyClaimedStillAnswers202"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java#zeroMatchWhileMarkingPublishFailedWithTheWorkerClaimIsRepublished"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java#zeroMatchWhileMarkingPublishFailedWithTheDocumentAlreadyPublishFailedIsAFailure"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java#zeroMatchWhileMarkingPublishFailedWithTheDocumentMissingIsAFailure"
        status: pass
    human_judgment: false
  - id: D3
    description: "Publish NACK seguido de DataAccessResourceFailureException no update PENDING → PUBLISH_FAILED, na criação e no republish: a própria exceção é propagada e um único evento ERROR contém o id e PUBLISH_NACK, sem 'conexão caiu' e sem o destinatário"
    requirement: "API-03"
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java#nackThenMongoDownWhileMarkingPublishFailedLogsTheIdAndPropagates"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java#republishWithNackThenMongoDownWhileMarkingPublishFailedLogsTheIdAndPropagates"
        status: pass
    human_judgment: false
  - id: D4
    description: "Lote do republish-failed com NACK + Mongo fora termina em 503 republish-interrupted com reason MONGO_UNAVAILABLE, republished 0 e detail que cita a varredura e não contém a mensagem da exceção; type, title e propriedades inalterados"
    requirement: "ADM-03"
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java#batchWithNackThenMongoDownWhileMarkingPublishFailedIs503WithATrueDetail"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java#interruptedProblemCarriesPartialCountsAndTheClosedReason"
        status: pass
    human_judgment: false
  - id: D5
    description: "Sem regressão contra RabbitMQ e MongoDB reais: 503 com marcação que casa 1 (detail novo), replay D-08 e varredura do 03-08; suíte unitária da API inteira verde"
    verification:
      - kind: integration
        ref: "./mvnw -q -pl notify-it -am verify -Dit.test=UnroutablePublishIT,IdempotencyIT,UnconfirmedPublishRecoveryIT"
        status: pass
      - kind: unit
        ref: "./mvnw -q -pl notify-common install && ./mvnw -q -pl notify-api test"
        status: pass
    human_judgment: false

duration: 16min
completed: 2026-11-01
status: complete
---

# Phase 3 Plan 10: Rastro e resposta verdadeira do publish sem confirm Summary

**`publishOrMarkFailed` relê o documento quando o update PENDING → PUBLISH_FAILED casa 0 e só segue como publicado com evidência de claim do worker (`wasClaimedByWorker`). Com MongoDB fora, registra um ERROR com o id e o código e relança. Os details dos 503 (`publish-failed` e `republish-interrupted`) deixam de afirmar um status que o documento pode não ter.**

## Performance

- **Duration:** 16 min
- **Started:** 2026-11-01T00:10:37Z
- **Completed:** 2026-11-01T00:26:42Z
- **Tasks:** 2 (1 tracer + 1 auto, ambos TDD)
- **Files modified:** 7 (4 de produção, 3 de teste)

## Accomplishments

- **Sem 202 sem prova (T-03-35).** Depois do 03-08, um update condicional que casa 0 podia significar que a varredura (ou outro republish) já tinha marcado PUBLISH_FAILED, e a API ainda respondia 202. Agora o documento é relido. PROCESSING ou qualquer status posterior ao claim segue como publicado. PUBLISH_FAILED, PENDING ou documento ausente viram 503 com `Retry-After` na criação e FAILED no republish. Provado de ponta a ponta na fatia web (HTTP → controller → serviço → republisher → publisher reais).
- **Órfão rastreável (T-03-33).** NACK seguido de `DataAccessException` na marcação ou na releitura gera um ERROR com o id, o código do publish e a classe da exceção, e a mesma exceção é relançada. O documento que ficou PENDING sem mensagem é recuperado pela varredura do 03-08. Na criação continua o 500 genérico. No lote, 503 `republish-interrupted` com MONGO_UNAVAILABLE e as contagens acumuladas.
- **503 verdadeiros (T-03-34, D-03, D-04).** O `publish-failed` diz só "O broker não confirmou a publicação da notificação <id> (<código>). Tente novamente mais tarde." O `republish-interrupted` diz que as não republicadas continuam PUBLISH_FAILED e que uma que tenha ficado PENDING sem confirm volta pela varredura. Type, title, propriedades e `Retry-After` não mudaram, e nenhum log ou detail carrega mensagem de exceção, texto do broker ou destinatário.

## Task Commits

1. **Task 1 (tracer): update que casa 0 relido, 503 do POST sem afirmar o status gravado**
   - RED: `87619c7` (test). O tracer `unconfirmedPublishWhoseDocumentIsAlreadyPublishFailedAnswers503WithATrueDetail` falhava com "Status expected:<503> but was:<202>", e o `timeoutWhoseConditionalUpdateMatchesNothingAndTheDocumentIsPublishFailedIs503` com "Expecting code to raise a throwable". 32 testes, 2 falhas, exatamente as duas esperadas.
   - GREEN: `d8e94b5` (feat). `wasClaimedByWorker`, releitura em `publishOrMarkFailed` (INFO com claim, WARN sem claim), detail novo do `PublishFailedException` e Javadoc.
2. **Task 2: ERROR com o id em Mongo fora e detail do lote verdadeiro**
   - RED: `96416af` (test). Os dois `…MongoDown…LogsTheIdAndPropagates` falhavam em "Expected size: 1 but was: 0" (nenhum ERROR), e o `batchWithNack…` e o `interruptedProblem…` falhavam porque o detail não citava a varredura. Os três `zeroMatch…` já passavam pela releitura da Task 1, como o plano previa. 27 testes, 4 falhas.
   - GREEN: `7043aaf` (feat). `try`/`catch (DataAccessException)` com ERROR e relançamento, detail e Javadoc do `RepublishInterruptedException`, Javadoc de `publishOrMarkFailed` e `republishFailed`.

Não houve REFACTOR: nenhuma limpeza foi necessária depois dos GREEN.

## Files Created/Modified

- `notify-api/.../application/NotificationRepublisher.java`: `wasClaimedByWorker`, releitura no update que casa 0, ERROR e relançamento em `DataAccessException`, Javadoc da classe, de `publishOrMarkFailed` e de `republishFailed`.
- `notify-api/.../application/PublishFailedException.java`: detail que não afirma o status gravado e Javadoc (PUBLISH_FAILED ou PENDING até a varredura).
- `notify-api/.../application/RepublishInterruptedException.java`: detail e Javadoc que citam a varredura de publicações sem confirm.
- `notify-api/.../application/NotificationService.java`: só o comentário do ramo sem confirm, alinhado à releitura (ver Deviations).
- `notify-api/src/test/.../web/NotificationApiFlowTest.java`: tracer da fatia web.
- `notify-api/src/test/.../application/NotificationServiceTest.java`: releitura PUBLISH_FAILED (503) e PROCESSING (202), detail do NACK, NACK + Mongo fora com `ListAppender`.
- `notify-api/src/test/.../application/NotificationRepublisherTest.java`: NACK + Mongo fora no republish e no lote, os três `zeroMatch…`, detail do lote com "varredura" e `ListAppender`.

## Decisions Made

- O ERROR também cobre a falha na releitura (`findById`), não só no `updateFirst`. As duas acontecem no mesmo trecho de rastro e têm o mesmo desfecho (o documento pode ter ficado PENDING sem mensagem). Por isso o texto diz "ao marcar PUBLISH_FAILED ou reler o documento" e "pode ter ficado PENDING", sem afirmar o status.
- Documento ausente na releitura é tratado como falha, como o plano assumiu (o lado conservador).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Comentário do `NotificationService.create` desatualizado**
- **Found during:** Task 1 (GREEN)
- **Issue:** o comentário do ramo sem confirm dizia que o update que casa 0 significa que o worker já reivindicou. Depois da releitura isso ficou falso e contradizia a proibição do plano ("não afirmar um status que o documento pode não ter").
- **Fix:** o comentário agora cita a releitura e `NotificationRepublisher.wasClaimedByWorker`. Nenhuma linha de código mudou.
- **Files modified:** notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java (fora de `files_modified` do plano)
- **Verification:** suíte unitária da API verde (164 testes)
- **Committed in:** d8e94b5

### Ajustes de processo (sem mudança de escopo)

**2. [Rule 3 - Blocking] Evidência RED em formato TAP**
- **Found during:** Tasks 1 e 2 (RED)
- **Issue:** `gsd-tools check tdd-red-evidence` lê saída TAP, e os testes rodam no Surefire (JUnit 5).
- **Fix:** igual ao 03-08. O resultado real do Surefire (contagens e nomes dos testes que falharam) foi reescrito em TAP no registro, com uma linha dizendo que é tradução. Veredito `RED_EVIDENCE_OK` para os 2 alvos da Task 1 e os 4 da Task 2.
- **Files modified:** nenhum do projeto (registros no scratchpad da sessão)

**3. Commits na branch `main`**
- O orquestrador mandou executar em modo sequencial na árvore principal (`git.branching_strategy: none`), como nos planos anteriores. Os commits foram para `main`, com hooks normais.

---

**Total deviations:** 2 auto-fixed (1 bug de comentário, 1 de processo), mais 1 nota de branch
**Impact on plan:** nenhuma mudança de escopo ou de comportamento além do planejado.

## TDD Gate Compliance

- Task 1: RED `87619c7` → GREEN `d8e94b5`.
- Task 2: RED `96416af` → GREEN `7043aaf`.
- Tracer feedback gate: execução interativa, `human_verify_mode` end-of-phase e verify só `<automated>`. Os dois comandos de verify da Task 1 (unitários e `UnroutablePublishIT`) passaram sobre o código do commit GREEN, então a expansão seguiu sem checkpoint.

## Verification

- Task 1: `./mvnw -q -pl notify-common install && ./mvnw -q -pl notify-api test -Dtest=NotificationApiFlowTest,NotificationServiceTest,NotificationRepublisherTest` saiu com 0. `./mvnw -q -pl notify-it -am verify -Dit.test=UnroutablePublishIT` saiu com 0 (4 testes, 0 falhas/erros).
- Task 2 e plano: `./mvnw -q -pl notify-common install && ./mvnw -q -pl notify-api test` saiu com 0 (164 testes, 0 falhas). `./mvnw -q -pl notify-it -am verify -Dit.test=UnroutablePublishIT,IdempotencyIT,UnconfirmedPublishRecoveryIT` saiu com 0 (4 + 5 + 1 testes, 0 falhas/erros).
- Todos os greps de `acceptance_criteria` passaram, inclusive zero `e.getMessage()` fora de comentários no `NotificationRepublisher`.

## Issues Encountered

None.

## Known Stubs

Nenhum.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Pronto para o **03-09** (onda 3): `NotificationRepublisher.wasClaimedByWorker` está público e estático para servir de evidência de claim no replay.
- A janela registrada no 03-08 (publish travado além do corte com update casando 0 tratado como claim) está fechada.
- As duas proibições do plano (API-03 e ADM-03) continuam `unresolved`/sinalizadas, sem probe de check, como o plano previa.

---
*Phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o*
*Completed: 2026-11-01*

## Self-Check: PASSED

- Arquivos alterados presentes: NotificationRepublisher, PublishFailedException, RepublishInterruptedException, NotificationService, NotificationApiFlowTest, NotificationServiceTest, NotificationRepublisherTest
- Commits presentes: 87619c7, d8e94b5, 96416af, 7043aaf
