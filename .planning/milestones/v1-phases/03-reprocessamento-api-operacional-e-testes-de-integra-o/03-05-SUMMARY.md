---
phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o
plan: 05
subsystem: api
tags: [spring-amqp, publisher-confirms, mandatory, mongodb, updateFirst, idempotency-key, problem-details, testcontainers]

requires:
  - phase: 03-01
    provides: harness notify-it (EndToEndSupport com post/adminPost/get/mongo/awaitStatus e o AmqpAdmin da API)
  - phase: 03-02
    provides: AdminController com validação de max, ApiKeyInterceptor em /admin/**, NotificationPublisher com CorrelationData UUID
  - phase: 03-04
    provides: NotificationService.create(request, key) com replay pelo índice idempotencyKey_unique (ponto de extensão replayOrRethrow)
provides:
  - "NotificationRepublisher: serviço único de publish/republish (publishOrMarkFailed, republish com fence PUBLISH_FAILED -> PENDING, lote republishFailed, constante MONGO_UNAVAILABLE)"
  - "POST /admin/notifications/republish-failed?max=N (200 {requested, republished}, 400, 401, 503 republish-interrupted)"
  - "RepublishInterruptedException (503) e ProblemTypes.REPUBLISH_INTERRUPTED"
  - "Replay de Idempotency-Key de uma notificação PUBLISH_FAILED republica (D-08): 202 PENDING ou 503 com Retry-After"
  - "lastError.message de PUBLISH_FAILED com texto fixo 'Publicação não confirmada pelo broker (<código>)', sem o reply text do broker"
  - "UnroutablePublishIT com 4 testes contra RabbitMQ e MongoDB reais (TST-03 não roteável, ADM-03, D-08 e lote parcial)"
affects: [03-06, 03-07, fase-4-readme]

actuals:
  tokens: 12493
  tasks: 2
  commits: 4
plan_head_before: 2353190c73ed92e7aa05602baf5c7763e7efb125

tech-stack:
  added: []
  patterns:
    - "Republish com fence: updateFirst {_id, status: PUBLISH_FAILED} -> $set PENDING antes do publish; modificado 0 não publica"
    - "Lote administrativo que para na primeira falha com ErrorResponseException 503 de contagens parciais e motivo fechado (mesmo molde do ReprocessInterruptedException)"
    - "Binding removido pelo AmqpAdmin da API e restaurado com declareBinding num finally (Armadilha 14)"

key-files:
  created:
    - notify-api/src/main/java/dev/notifyhub/api/application/NotificationRepublisher.java
    - notify-api/src/main/java/dev/notifyhub/api/application/RepublishInterruptedException.java
    - notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java
    - notify-it/src/test/java/dev/notifyhub/it/UnroutablePublishIT.java
  modified:
    - notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java
    - notify-api/src/main/java/dev/notifyhub/api/admin/AdminController.java
    - notify-api/src/main/java/dev/notifyhub/api/web/ProblemTypes.java
    - notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java
    - notify-api/src/test/java/dev/notifyhub/api/admin/AdminControllerTest.java
    - notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java
    - notify-api/src/test/java/dev/notifyhub/api/web/NotificationValidationTest.java

key-decisions:
  - "03-05: publish da criação, replay de PUBLISH_FAILED e republish-failed passam todos pelo NotificationRepublisher; lastError de PUBLISH_FAILED grava 'Publicação não confirmada pelo broker (<código>)', nunca PublishResult.detail()"
  - "03-05: no lote, fence perdido (outro caminho já tirou de PUBLISH_FAILED) não conta nem interrompe; publish sem confirm lança 503 com errorCode() e DataAccessException na busca ou num update lança com MONGO_UNAVAILABLE"
  - "03-05: replay de PUBLISH_FAILED com fence perdido relê o documento por findById e devolve o status atual com Idempotent-Replayed; se o documento sumiu, cai no status lido antes"

patterns-established:
  - "Validação de max dos endpoints admin num método privado comum (resolveMax) que acumula FieldProblem"
  - "Cenários de broker sem rota no IT: removeBinding no try, declareBinding no finally, asserções HTTP pós-restauração fora do try"

requirements-completed: [ADM-03, API-03, TST-03]

coverage:
  - id: D1
    description: "Sem o binding notify.exchange -> notify.email, POST /notifications responde 503 com Retry-After 5 e publish-failed; o documento fica PUBLISH_FAILED com lastError.code PUBLISH_RETURNED e mensagem fixa, sem NO_ROUTE no corpo nem no GET"
    requirement: TST-03
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/UnroutablePublishIT.java#unroutablePublishBecomesPublishFailedAndIsRepublishedByTheOperator"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java#nackMarksPublishFailedConditionallyAndThrows"
        status: pass
    human_judgment: false
  - id: D2
    description: "POST /admin/notifications/republish-failed com X-API-Key republica as PUBLISH_FAILED (createdAt asc, _id asc, limit max, padrão 100), responde 200 {requested, republished} e cada uma chega a SENT; sem chave ou chave errada 401 sem mexer em documento; max 0/1001/abc 400 com campo max"
    requirement: ADM-03
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/UnroutablePublishIT.java#unroutablePublishBecomesPublishFailedAndIsRepublishedByTheOperator"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/admin/AdminControllerTest.java (republishFailed*, 8 casos)"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java#batchSearchesPublishFailedOldestFirstAndStopsAtTheFirstFailureWith503Partial"
        status: pass
    human_judgment: false
  - id: D3
    description: "Republish faz PUBLISH_FAILED -> PENDING num updateFirst condicional {_id, status: PUBLISH_FAILED} antes do publish; modificado 0 não publica nem conta (T-03-22); publish devolvido volta a PUBLISH_FAILED com lastError fixo"
    requirement: ADM-03
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java (republishFencesPublishFailedToPendingThenPublishes, lostFenceDoesNotPublish, returnedPublishMarksPublishFailedAgainWithTheFixedMessage, batchCountsOnlyRepublishedAndSkipsLostFences)"
        status: pass
    human_judgment: false
  - id: D4
    description: "O lote para na primeira falha com 503 republish-interrupted, requested/republished parciais e reason fechado (PUBLISH_RETURNED no broker real; MONGO_UNAVAILABLE na busca ou num update), e as não processadas continuam PUBLISH_FAILED; com o binding de volta, a próxima chamada republica as 2 até SENT"
    requirement: ADM-03
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/UnroutablePublishIT.java#republishBatchStopsAtTheFirstFailureWithPartialCounts"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java (mongoDownOnTheSearchIs503WithMongoUnavailable, mongoDownOnAFenceUpdateIs503WithTheAccumulatedCounts, interruptedProblemCarriesPartialCountsAndTheClosedReason)"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/admin/AdminControllerTest.java#interruptedRepublishIsAServiceUnavailableProblemWithPartialCounts"
        status: pass
    human_judgment: false
  - id: D5
    description: "Replay com a mesma Idempotency-Key e o mesmo payload de uma PUBLISH_FAILED republica pelo mesmo serviço: com rota, 202 Idempotent-Replayed PENDING e SENT com 1 documento; sem rota, 503 com Retry-After e o documento continua PUBLISH_FAILED; fence perdido devolve o status atual"
    requirement: API-03
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/UnroutablePublishIT.java#replayOfAPublishFailedNotificationRepublishes"
        status: pass
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/UnroutablePublishIT.java#replayThatFailsAgainAnswers503WithRetryAfter"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java (replayOfPublishFailed*, 3 casos)"
        status: pass
    human_judgment: false
  - id: D6
    description: "A criação continua igual para o cliente: confirm positivo -> 202 PENDING sem escrita depois do publish; timeout com o worker já tendo reivindicado (update casa 0) -> 202; regressão da idempotência intacta"
    requirement: API-03
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java (confirmedPublishNeverUpdatesStatus, timeoutAfterWorkerAlreadyClaimedStillAnswers202)"
        status: pass
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/IdempotencyIT.java (5 testes)"
        status: pass
      - kind: other
        ref: "./mvnw -q verify (exit 0; 561 unitários e 24 ITs)"
        status: pass
    human_judgment: false

duration: 20min
completed: 2026-10-31
status: complete
---

# Phase 3 Plan 05: Republicação de PUBLISH_FAILED (ADM-03, D-08, TST-03) Summary

**PUBLISH_FAILED ganhou saída. O operador republica em lote com `POST /admin/notifications/republish-failed`, e o cliente que repete o POST com a mesma `Idempotency-Key` dispara a mesma republicação. Os dois caminhos passam pelo novo `NotificationRepublisher`, que faz PUBLISH_FAILED → PENDING num update condicional antes do publish confirmado. O lote para na primeira falha com 503 de contagens parciais. O `lastError` deixou de gravar o reply text do broker. O `UnroutablePublishIT` prova o publish não roteável e as duas republicações contra o RabbitMQ real.**

## Performance

- **Duração:** 20 min
- **Início:** 2026-10-31T14:31:53Z
- **Fim:** 2026-10-31T14:51:36Z
- **Tarefas:** 2 (4 commits: RED + GREEN em cada uma)
- **Arquivos alterados:** 11 (4 criados, 7 alterados)

## Accomplishments

- **`NotificationRepublisher` (serviço único, Padrão 4b):**
  - `publishOrMarkFailed(doc)` é o publish da criação, extraído do `NotificationService`. Sem confirm, faz PENDING → PUBLISH_FAILED condicional. Se o update casa 0, o worker já reivindicou a notificação e o resultado é vazio (Armadilha 4).
  - O `lastError` grava `"Publicação não confirmada pelo broker (<código>)"`, nunca `PublishResult.detail()` (T-02-01, T-03-21).
  - `republish(doc)` faz `updateFirst` com `{_id, status: PUBLISH_FAILED}` → `$set` PENDING + `updatedAt` e só então publica. Se o update casa 0, devolve `NOT_PUBLISH_FAILED` sem publicar (T-03-22). Os outros desfechos são `REPUBLISHED` e `FAILED` (com o `PublishResult`).
  - `republishFailed(max)` busca as PUBLISH_FAILED por `createdAt` asc + `_id` asc com `limit(max)` e conta só as REPUBLISHED; fence perdido não conta nem interrompe. Publish sem confirm lança `RepublishInterruptedException` com `errorCode()`. Uma `DataAccessException` na busca ou num update lança com `MONGO_UNAVAILABLE`. As duas levam as contagens acumuladas.
- **`POST /admin/notifications/republish-failed?max=N`:** usa o mesmo interceptor `X-API-Key` (401) e a mesma validação de `max` do reprocess, agora num `resolveMax` comum (1..1000, padrão 100). Responde 200 `{requested, republished}` ou 503 `urn:notify-hub:problem:republish-interrupted`.
- **Replay de PUBLISH_FAILED (D-08):**
  - Com o mesmo hash, o `NotificationService` chama `republisher.republish(existente)`.
  - REPUBLISHED → 202 PENDING com `Idempotent-Replayed: true`.
  - FAILED → `PublishFailedException` (503 com `Retry-After`).
  - Fence perdido → relê o documento e devolve o status atual.
  - Os outros status seguem o replay simples do 03-04.
- **`UnroutablePublishIT` (4 testes, broker real):** cobre não roteável → PUBLISH_FAILED sem `NO_ROUTE`, 401 sem chave, republish até SENT, replay que republica, replay que falha de novo e lote parcial. O binding sempre volta no `finally`.

## Task Commits

1. **Task 1: Tracer, publish não roteável vira PUBLISH_FAILED e o operador republica até SENT**
   - RED `7e9c408` (test): falharam por asserção a mensagem fixa no `NotificationServiceTest` (saiu `"x"`), os 5 casos de chave/max do `AdminControllerTest` (404, endpoint ausente) e o IT em `lastError.message` (saiu `NO_ROUTE`). Os 2 casos de 401 já passavam pelo interceptor.
   - GREEN `668b738` (feat). Gate do tracer: execução interativa, `end-of-phase`, `<verify>` só automático. Os dois comandos do verify rodaram de novo no código commitado (exit 0, nenhuma linha de falha), e a expansão seguiu sem checkpoint.
2. **Task 2: replay de PUBLISH_FAILED republica (D-08) e o lote para com 503 parcial**
   - RED `a9b6609` (test): 8 testes unitários falharam por asserção. Os 4 do lote/503 no `NotificationRepublisherTest` falharam por falta de exceção, pela `DataAccessResourceFailureException` crua ou pelo `type` `about:blank`; também falharam os 3 replays do `NotificationServiceTest` e o 503 do `AdminControllerTest`. Os 3 ITs novos falharam com 202/PUBLISH_FAILED/200 no lugar de 503/PENDING/503.
   - GREEN `7a5d56a` (feat).

**Plan metadata:** commit `docs(03-05)` logo após este SUMMARY.

## Files Created/Modified

- `notify-api/.../application/NotificationRepublisher.java` (novo): publish, republish com fence, lote, `RepublishOutcome`, `RepublishReport`, `MONGO_UNAVAILABLE`
- `notify-api/.../application/RepublishInterruptedException.java` (novo): 503 com `requested`, `republished` e `reason`
- `notify-api/.../application/NotificationService.java`: construtor `(MongoTemplate, NotificationRepublisher, PublishProperties)`, criação pelo republisher, `replayPublishFailed`, `markPublishFailed` local removido
- `notify-api/.../admin/AdminController.java`: endpoint `republish-failed`, `resolveMax` comum, Javadoc com ADM-03
- `notify-api/.../web/ProblemTypes.java`: `REPUBLISH_INTERRUPTED`
- Testes: `NotificationRepublisherTest` (8, novo), `NotificationServiceTest` (12), `AdminControllerTest` (34), `NotificationApiFlowTest`/`NotificationValidationTest` (`@Import` com o republisher), `UnroutablePublishIT` (4, novo)

## Decisions Made

- **Uma só transição para os três caminhos.** Criação, replay e admin compartilham o mesmo `markPublishFailed` privado, então o `lastError` fixo vale para todos de uma vez. Nenhum smoke nem consumidor lia a mensagem antiga, e o `code` não mudou.
- **Fence perdido no replay relê o documento.** O `findById` devolve o status atual (por exemplo PROCESSING, se o worker reivindicou). Se o documento tiver sumido, a resposta usa o status lido antes. Nos dois casos sai com `Idempotent-Replayed: true` e sem publicar.
- **O log do 503 por Mongo cita só a classe da exceção** e o 503 só o motivo fechado, nunca a mensagem da exceção (o teste confere que o `detail` não contém o texto dela).

## Deviations from Plan

### Ajustes de execução

**1. [Processo] Commits em `main`**
- **Found during:** início da execução
- **Issue:** o protocolo do executor recusa commits na branch padrão, mas o projeto usa `branching_strategy: none` e o orquestrador mandou seguir em `main`.
- **Fix:** commits normais em `main`, com hooks e sem `--no-verify`. O pin de raiz (#4254) rodou antes da primeira escrita e antes de cada commit.
- **Commits:** todos os 4

**2. [Processo] Testes de caracterização verdes nos RED**
- **Found during:** Task 1 (RED) e Task 2 (RED)
- **Issue:** os casos de 401 do `republish-failed` passam desde o RED, porque o interceptor de `/admin/**` vale também para caminho sem handler. Os 4 testes de `republish` do `NotificationRepublisherTest` (Task 2) cobrem comportamento que a Task 1 já entregou.
- **Fix:** ficaram como caracterização no mesmo commit RED, que também tem testes falhando por asserção para o comportamento novo de cada tarefa. A divisão está nas mensagens dos commits RED.
- **Committed in:** `7e9c408`, `a9b6609`

**3. [Teste extra] Dois casos além do `<behavior>`**
- **Found during:** Task 2 (RED)
- **Issue:** o plano manda tratar `DataAccessException` "na busca ou num `updateFirst`", mas o `<behavior>` só testa a busca. Também não havia teste para fence perdido no meio do lote.
- **Fix:** `mongoDownOnAFenceUpdateIs503WithTheAccumulatedCounts` e `batchCountsOnlyRepublishedAndSkipsLostFences`, mais `max=abc` e chave errada no `AdminControllerTest` (truth do ADM-04).
- **Committed in:** `a9b6609`, `7e9c408`

---

**Total deviations:** 3 (2 de processo, 1 de cobertura extra de teste)
**Impact on plan:** nenhum no escopo. O código de produção seguiu o plano.

## Issues Encountered

- `-DskipTests` também pula o Failsafe. No RED do IT da Task 2, a primeira leitura pegou um relatório velho. Rodei de novo com `-Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false` para pular só os unitários.
- Os avisos de LF → CRLF do Git aparecem em todo commit (Windows) e são inofensivos.
- Observação, fora do escopo: se o Mongo cair entre um publish sem confirm e o `markPublishFailed`, o documento fica PENDING sem mensagem no broker. O caminho da criação já tinha esse comportamento antes deste plano. O republish herda o mesmo caso: o lote responde 503 MONGO_UNAVAILABLE, e o documento fica fora do alcance do `republish-failed`, que só busca PUBLISH_FAILED.

## TDD Gate Compliance

Os 2 gates RED (`test(03-05)`: `7e9c408`, `a9b6609`) vêm antes dos 2 gates GREEN (`feat(03-05)`: `668b738`, `7a5d56a`), em ordem. Não houve REFACTOR. O RED usa esqueletos que compilam: na Task 1, o `NotificationRepublisher` com o publish antigo movido sem mudança e republish/lote sem comportamento; na Task 2, `RepublishInterruptedException` sem corpo. Assim a falha vem de asserção, não de compilação. O checador `tdd-red-evidence` lê saída TAP de Node e não se aplica ao Surefire/Failsafe. A evidência do RED está nas mensagens dos commits RED.

## Known Stubs

Nenhum. Os esqueletos do RED foram substituídos nos commits GREEN. O grep por `TODO`, `FIXME`, `Esqueleto`, `não implementado` e `placeholder` em `notify-api/src/main` e no IT não acha nada.

## Threat Flags

Nenhuma superfície nova fora do `<threat_model>`. O endpoint novo fica sob o interceptor `/admin/**` do 03-02. Cada `mitigate` tem teste:
- T-03-21: mensagem fixa (grep negativo de `result.detail()`); o IT confere que `NO_ROUTE` não aparece no 503, no GET nem no 503 do lote.
- T-03-22: `lostFenceDoesNotPublish` e `batchCountsOnlyRepublishedAndSkipsLostFences`.
- T-03-23: 401 no `AdminControllerTest` e no IT, com o documento ainda PUBLISH_FAILED.
- T-03-24: `max` 0/1001/abc → 400, teto `notify.admin.max-limit`, parada na primeira falha.

## User Setup Required

Nenhum.

## Next Phase Readiness

- O 03-06 (listagem e stats) não toca nos arquivos deste plano. O lote do `republish-failed` busca `status` + sort `createdAt`, então o índice `status_createdAt` do 03-06 também serve a essa query.
- O 03-07 (smoke/demo) pode chamar `POST /admin/notifications/republish-failed` com a chave da demo depois de restaurar o binding, como o `UnroutablePublishIT`.
- A Fase 4 (README) pode citar o `UnroutablePublishIT` como prova do "aceita = nunca perdida" no caso de publish sem rota.

## Self-Check: PASSED

- Os 4 arquivos criados existem em disco.
- Os commits `7e9c408`, `668b738`, `a9b6609` e `7a5d56a` estão no histórico (4 = `git rev-list --count 2353190..HEAD`).
- Todos os critérios de aceite das 2 tarefas deram PASS (grep e comandos), assim como os `key_links` do plano.
- Verificação do plano:
  - `./mvnw -q -pl notify-common install && ./mvnw -q -pl notify-api test`: verde (125).
  - `./mvnw -q -pl notify-it -am verify -Dit.test=UnroutablePublishIT,IdempotencyIT`: verde (4 + 5).
  - `./mvnw -q verify`: exit 0, 0 linhas `[ERROR]`, 561 unitários (80 common, 125 api, 356 worker) e 24 ITs (`DeliveryGuaranteesIT` 3, `HarnessSmokeIT` 2, `IdempotencyIT` 5, `ReprocessEdgeCasesIT` 7, `ReprocessFlowIT` 3, `UnroutablePublishIT` 4).

---
*Phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o*
*Completed: 2026-10-31*
