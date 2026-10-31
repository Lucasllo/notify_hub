---
phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o
plan: 02
subsystem: api
tags: [spring-amqp, basicGet, publisher-confirms, mongodb, findAndModify, handler-interceptor, problem-details, micrometer]

requires:
  - phase: 02-falhas-retry-com-backoff-e-resili-ncia
    provides: DLQ por canal com cópia confirmada pelo worker, tabela de transições de NotificationStatus, DeliveryMetrics com as 42 séries do D-15
  - phase: 03-01
    provides: harness de IT (notify-it) com notify.admin.api-key no profile it
provides:
  - POST /admin/dlq/{canal}/reprocess?max=N com X-API-Key (200 com contagens, 400, 401, 503 com motivo fechado)
  - DlqReprocessService (basicGet com ack manual, orçamento min(max, prontas), reprocessed/skipped/discarded, nack com requeue na primeira falha)
  - NotificationResetService.resetForReprocess (reset atômico derivado da tabela, fence status + retryCount, evento REPROCESSED)
  - NotificationPublisher.copyToDlqTail e CorrelationData com UUID em toda publicação da API
  - AttemptOutcome.REPROCESSED; DeliveryMetrics com conjunto explícito dos 4 resultados do adapter
  - ApiExceptionHandler.invalidRequest (400 de parâmetro único) e handleTypeMismatch
  - AdminProperties (notify.admin.api-key / max-default / max-limit) e ENV NOTIFY_ADMIN_API_KEY
affects: [03-03, 03-04, 03-05, 03-06, 03-07]

actuals:
  tokens: 19894
  tasks: 3
  commits: 6
plan_head_before: 1c53d7bae5cab41f22a797f577b8c79ff1d60cdf

tech-stack:
  added: []
  patterns:
    - "Laço basicGet dentro de rabbitTemplate.execute com contagens num objeto mutável fora do lambda; exceção inesperada guardada e relançada depois do execute"
    - "Cada entrega termina em exatamente um basicAck ou basicNack(tag, false, true), com nack no finally por entrega"
    - "Resultado do reset como interface selada + ramo padrão instanceof (sem switch exaustivo), para resultados novos caírem em skipped"
    - "HandlerInterceptor em /admin/** com SHA-256 + MessageDigest.isEqual; ErrorResponseException em preHandle vira Problem Details pelo ApiExceptionHandler"
    - "Testes de fluxo com stub do execute que converte exceções do callback via RabbitExceptionTranslator, como o RabbitTemplate real"

key-files:
  created:
    - notify-api/src/main/java/dev/notifyhub/api/admin/AdminProperties.java
    - notify-api/src/main/java/dev/notifyhub/api/admin/AdminWebConfiguration.java
    - notify-api/src/main/java/dev/notifyhub/api/admin/AdminController.java
    - notify-api/src/main/java/dev/notifyhub/api/admin/DlqReprocessService.java
    - notify-api/src/main/java/dev/notifyhub/api/admin/ReprocessInterruptedException.java
    - notify-api/src/main/java/dev/notifyhub/api/application/NotificationResetService.java
    - notify-api/src/test/java/dev/notifyhub/api/admin/DlqReprocessFlowTest.java
    - notify-api/src/test/java/dev/notifyhub/api/admin/AdminControllerTest.java
    - notify-api/src/test/java/dev/notifyhub/api/admin/MongoBson.java
  modified:
    - notify-common/src/main/java/dev/notifyhub/common/domain/AttemptOutcome.java
    - notify-worker/src/main/java/dev/notifyhub/worker/metrics/DeliveryMetrics.java
    - notify-api/src/main/java/dev/notifyhub/api/messaging/NotificationPublisher.java
    - notify-api/src/main/java/dev/notifyhub/api/web/ProblemTypes.java
    - notify-api/src/main/java/dev/notifyhub/api/web/ApiExceptionHandler.java
    - notify-api/src/main/resources/application.yml

key-decisions:
  - "Primeira falha registrada vence: se o canal cair depois de um nack de publish, o 503 mantém o motivo da publicação (Tally.stop só grava o primeiro motivo)"
  - "Corpo da DLQ que desserializa para null (JSON 'null') conta como 'sem notificationId' e é preservado no fim da DLQ"
  - "Os records de ResetResult ficam aninhados no NotificationResetService (NotificationResetService.Reset etc.), não dentro da interface"
  - "RED com esqueletos sem comportamento (que compilam) para os testes falharem por asserção, e não por compilação"

patterns-established:
  - "400 de parâmetro sempre por ApiExceptionHandler.invalidRequest(List<FieldProblem>), com os erros juntos num único Problem Details (base para 03-04 e 03-06)"
  - "Motivo de falha de infraestrutura em enum fechado (FailureReason.of(PublishResult) lê só o Outcome, nunca o detail)"

requirements-completed: [ADM-01, ADM-02, ADM-04]

coverage:
  - id: D1
    description: "POST /admin/dlq/{canal}/reprocess move uma notificação DEAD_LETTERED ou FAILED da DLQ para a fila principal: basicGet sem autoAck → findAndModify → republish confirmado (messageId = id, notify-retry-count 0, CorrelationData UUID) → basicAck; 200 com {requested, reprocessed, skipped, discarded}"
    requirement: ADM-01
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/admin/DlqReprocessFlowTest.java#deadLetteredGoesBackToTheMainQueueResetBeforePublishAckAfter"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/admin/DlqReprocessFlowTest.java#failedAlsoResetsBecauseTheTableAllowsFailedToPending"
        status: pass
    human_judgment: false
  - id: D2
    description: "Orçamento min(max, prontas no início), DLQ vazia sem abrir canal, skipped copiado para o fim (bytes originais, sem x-death) e nunca relido; discarded para SENT/inexistente; corpo inválido preservado sem consultar o Mongo"
    requirement: ADM-01
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/admin/DlqReprocessFlowTest.java (budget*, emptyDlq*, statusTheTableCannotReset*, lostFence*, missingDocument*, sentDocument*, *Body*)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Parada na primeira falha: nack/return/timeout de publish ou cópia, Mongo fora, DLQ inexistente e broker fora viram 503 reprocess-interrupted com contagens parciais e motivo fechado; a mensagem volta à DLQ com basicNack(tag, false, true); nunca reject nem requeue false; exceção inesperada leva nack e chega intacta"
    requirement: ADM-01
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/admin/DlqReprocessFlowTest.java (publishNack*, returned*, confirmThatNeverArrives*, copyNack*, mongoDown*, missingDlq*, brokerDown*, channelThatFails*, unexpectedException*; @AfterEach dlqNeverRejectsNorDropsADelivery)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Reset grava PENDING e retryCount 0, remove leaseUntil/claimedAt, faz $push com $slice -50 de um REPROCESSED (number 0, retryLevel anterior, sem errorCode/failureClass), com fence _id + status + retryCount lidos e sem tocar em attemptCount"
    requirement: ADM-02
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/admin/DlqReprocessFlowTest.java#resetIsFencedByTheReadStatusAndRetryCountAndNeverTouchesAttemptCount"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/admin/DlqReprocessFlowTest.java#resetPushesOneReprocessedEventKeepingTheLastFiftyEntries"
        status: pass
    human_judgment: false
  - id: D5
    description: "DeliveryMetrics continua com as 42 séries do D-15 depois de AttemptOutcome.REPROCESSED (nenhuma série outcome=reprocessed)"
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/metrics/DeliveryMetricsTest.java#onlyTheFortyTwoClosedSetSeriesExist"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/metrics/DeliveryMetricsTest.java#durationTimersArePreRegisteredWithTheFourAdapterOutcomes"
        status: pass
    human_judgment: false
  - id: D6
    description: "/admin/** exige X-API-Key exata (SHA-256 + MessageDigest.isEqual, sem trim nem caixa), 401 unauthorized antes do binding e fail closed com chave vazia; canal só email/sms/webhook e max de 1 a 1000 (padrão 100), 400 validation-error inclusive para abc/1.5/estouro de int"
    requirement: ADM-04
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/admin/AdminControllerTest.java (26 casos)"
        status: pass
    human_judgment: false

duration: 24min
completed: 2026-10-31
status: complete
---

# Phase 3 Plan 02: Reprocessamento seguro da DLQ (ADM-01, ADM-02, ADM-04) Summary

**`POST /admin/dlq/{canal}/reprocess?max=N` protegido por `X-API-Key` (SHA-256 + `MessageDigest.isEqual`, fail closed). O endpoint drena a DLQ com `basicGet` e ack manual: reset atômico no Mongo com evento REPROCESSED, depois republish confirmado, depois ack. Para na primeira falha com nack com requeue e 503 de motivo fechado, e preserva no fim da DLQ tudo o que não pode voltar à fila principal.**

## Performance

- **Duração:** 24 min
- **Início:** 2026-10-31T12:57:45Z
- **Fim:** 2026-10-31T13:22:35Z
- **Tarefas:** 3 (6 commits: RED + GREEN em cada uma)
- **Arquivos alterados:** 15 (9 criados, 6 alterados)

## Accomplishments

- **`DlqReprocessService`:** antes do laço lê `getQueueInfo` (DLQ inexistente → 503 DLQ_UNAVAILABLE; broker fora → BROKER_UNAVAILABLE) e calcula o orçamento `min(max, prontas)`. O laço `basicGet(dlq, false)` roda num único canal dentro do `execute`, e cada entrega termina em exatamente um `basicAck` ou `basicNack(tag, false, true)`, com nack no `finally`. A classificação é:
  - `Reset` → republish confirmado → `reprocessed`;
  - `Missing`/`Sent` → `discarded`;
  - qualquer outro resultado, ou corpo ilegível, sem `notificationId` ou de outro canal → cópia confirmada para o fim da DLQ → `skipped`.
- **`NotificationResetService`:** um `findAndModify` cercado por `_id` + status lido + `retryCount` lido. Só age quando `canTransitionTo(PENDING)`. Grava PENDING e `retryCount` 0, remove `leaseUntil`/`claimedAt` e faz `$push` com `$slice` -50 de um REPROCESSED. `attemptCount` não é tocado.
- **`NotificationPublisher`:** passa a usar `CorrelationData` com UUID em toda publicação (Armadilha 7). A espera do confirm virou um método compartilhado, e o novo `copyToDlqTail` publica os bytes originais com só `messageId`, content-type, `x-failure-class` e `notify-retry-count`.
- **`AdminWebConfiguration.ApiKeyInterceptor` em `/admin/**`:** devolve 401 `unauthorized` antes do binding (canal inválido sem chave também dá 401). A `ErrorResponseException` lançada em `preHandle` virou Problem Details com `instance`, o que confirma a A1 do RESEARCH.
- **`AdminController` e `ApiExceptionHandler`:** o controller valida canal e `max` num único 400 via `ApiExceptionHandler.invalidRequest`. O `handleTypeMismatch` transforma `max=abc`/`1.5`/`99999999999` em 400 `validation-error` com campo `max`.
- **Enum e métricas:** `AttemptOutcome.REPROCESSED` entrou no enum, e o `DeliveryMetrics` passou a registrar o timer sobre um `EnumSet` explícito dos 4 resultados do adapter. As 42 séries continuam.

## Task Commits

1. **Task 1: Tracer, uma DEAD_LETTERED volta à fila principal com parada segura na primeira falha**
   - RED `a4a075a` (test): `DlqReprocessFlowTest` (12 cenários) contra esqueletos sem comportamento. 11/12 falharam por asserção (o de DLQ vazia passava por coincidência no esqueleto).
   - GREEN `777f673` (feat). Gate do tracer: execução interativa, `end-of-phase`, `<verify>` só automático. O verify rodou de novo, passou, e a expansão seguiu sem checkpoint.
2. **Task 2: REPROCESSED sem apagar o histórico, discarded e motivo fechado em falha de infraestrutura**
   - RED `c45fbee` (test): 10/12 cenários novos falharam por asserção. No worker, `DeliveryMetricsTest` falhou com 15 timers e 45 séries.
   - GREEN `6f604bb` (feat).
3. **Task 3: X-API-Key em tempo constante (fail closed) e 400 para canal ou `max` inválidos**
   - RED `35ae292` (test): 20/26 casos do `AdminControllerTest` falharam (401 e 400 saíam 200 ou estouravam).
   - GREEN `0f87222` (feat).

**Plan metadata:** commit `docs(03-02)` logo após este SUMMARY.

## Files Created/Modified

- `notify-api/.../admin/DlqReprocessService.java`: laço do reprocessamento, `Report`, `FailureReason`
- `notify-api/.../admin/ReprocessInterruptedException.java`: 503 `reprocess-interrupted` com contagens e `reason`
- `notify-api/.../admin/AdminController.java`: endpoint com validação de canal e `max`
- `notify-api/.../admin/AdminProperties.java`: `notify.admin.*`, com construtor que valida 1 ≤ max-default ≤ max-limit
- `notify-api/.../admin/AdminWebConfiguration.java`: registro das propriedades e `ApiKeyInterceptor`
- `notify-api/.../application/NotificationResetService.java`: reset atômico e `ResetResult`
- `notify-api/.../messaging/NotificationPublisher.java`: UUID na correlação, `awaitConfirm`, `copyToDlqTail`
- `notify-api/.../web/ProblemTypes.java`: `REPROCESS_INTERRUPTED`, `UNAUTHORIZED`
- `notify-api/.../web/ApiExceptionHandler.java`: `invalidRequest`, `handleTypeMismatch`
- `notify-api/src/main/resources/application.yml`: bloco `notify.admin`
- `notify-common/.../domain/AttemptOutcome.java`: `REPROCESSED`
- `notify-worker/.../metrics/DeliveryMetrics.java`: `ADAPTER_OUTCOMES = EnumSet.of(...)`
- Testes: `DlqReprocessFlowTest` (24), `AdminControllerTest` (26), `MongoBson` (helper)

## Decisions Made

- **Primeiro motivo vence.** `Tally.stop` só grava o primeiro motivo de parada. Se o canal cair depois de um nack de publish, o 503 informa `PUBLISH_NACK`, que é a falha que interrompeu o lote.
- **Corpo `null` conta como "sem notificationId".** Um JSON `null` desserializa para `null` e cai nessa regra, então é preservado no fim da DLQ.
- **Records de resultado na classe.** Os records de `ResetResult` ficam aninhados no `NotificationResetService`, e não dentro da interface, para a leitura ficar mais curta (`NotificationResetService.Reset`).
- **RED que compila.** Em cada tarefa, o RED usa esqueletos que compilam, para a falha vir de asserção e não de compilação, como pede o `tdd.md`. O checador `tdd-red-evidence` lê saída TAP de Node e não se aplica a Surefire. As evidências estão nos commits RED acima.

## Deviations from Plan

### Ajustes de execução

**1. [Processo] Commits em `main`**
- **Found during:** início da execução
- **Issue:** o protocolo do executor recusa commits na branch padrão, mas o projeto usa `branching_strategy: none` e o orquestrador mandou seguir em `main`.
- **Fix:** commits normais em `main`, com hooks e sem `--no-verify`. O pin de raiz (#4254) rodou antes da primeira escrita e antes de cada commit.
- **Commits:** todos os 6

**2. [Rule 3 - Blocking] Helper de teste `MongoBson` no notify-api**
- **Found during:** Task 2 (RED)
- **Issue:** o `$push` com `$each`/`$slice` só vira um documento legível depois do `UpdateMapper`. O `Update` cru guarda um objeto `Modifiers` interno.
- **Fix:** cópia reduzida do `MongoBson` dos testes do worker em `notify-api/src/test/java/dev/notifyhub/api/admin/MongoBson.java`. É um arquivo só de teste, fora da lista `files_modified`.
- **Committed in:** `c45fbee`

**3. [Rule 2 - Missing Critical] Stub do `execute` converte exceções como o `RabbitTemplate` real**
- **Found during:** Task 2 (RED)
- **Issue:** com o stub que só rodava o callback, uma exceção inesperada chegava crua a quem chamou, e o cenário "exceção inesperada chega intacta" passaria sem implementação nenhuma.
- **Fix:** o `@BeforeEach` agora envolve o callback com `RabbitExceptionTranslator.convertRabbitAccessException`, o mesmo comportamento do `execute` real.
- **Committed in:** `c45fbee`

---

**Total deviations:** 3 (1 de processo, 1 bloqueio de teste, 1 fidelidade do teste)
**Impact on plan:** nenhum no escopo. O código de produção seguiu o plano.

## Issues Encountered

- O `./mvnw -q -pl notify-common install` sem `-DskipTests` imprime os WARN esperados dos testes do common (contexto que falha de propósito). Não é erro.
- Os warnings de LF → CRLF do Git aparecem em todo commit (Windows). São inofensivos.

## TDD Gate Compliance

Os 3 gates RED (`test(03-02)`) vêm antes dos 3 gates GREEN (`feat(03-02)`), em ordem. Não houve commit de REFACTOR, porque não foi necessário.

## Known Stubs

Nenhum. Os esqueletos do RED foram substituídos nos commits GREEN, e o grep por `Esqueleto`/`não implementado`/TODO/FIXME no código de produção não acha nada.

## Threat Flags

Nenhuma superfície nova fora do `<threat_model>`. O endpoint admin, a leitura da DLQ e o reset no Mongo estão cobertos por T-03-04 a T-03-11, e todos os `mitigate` têm teste.

## User Setup Required

Nenhum. Sem `NOTIFY_ADMIN_API_KEY`, os endpoints admin recusam tudo (fail closed). A demo ganha uma chave no 03-07, e o profile `it` já usa `it-admin-key`.

## Next Phase Readiness

- O 03-03 pode acrescentar as transições PENDING/RETRYING/PROCESSING → PENDING (CR-01) e o resultado `LeaseActive`. O ramo padrão do `DlqReprocessService` já trata um resultado novo como `skipped`, sem mudança no serviço.
- O `invalidRequest(List<FieldProblem>)` está pronto para os 400 de parâmetro dos planos 03-04 e 03-06.
- Os ITs de reprocessamento podem usar o `adminPost` do `EndToEndSupport` com a chave `it-admin-key`.

## Self-Check: PASSED

- Os 9 arquivos criados existem em disco.
- Os commits `a4a075a`, `777f673`, `c45fbee`, `6f604bb`, `35ae292` e `0f87222` estão no histórico.
- Todos os critérios de aceite das 3 tarefas deram PASS (grep e comandos).
- Verificação do plano:
  - `./mvnw -q -pl notify-api test -Dtest=DlqReprocessFlowTest`: verde (24/24).
  - `./mvnw -q -pl notify-worker test -Dtest=DeliveryMetricsTest,DeliveryFlowTest`: verde (48/48).
  - `./mvnw -q -pl notify-api test`: verde (84/84).
  - `./mvnw -q test`: exit 0, sem `[ERROR]`.
  - Extra: `HarnessSmokeIT` (Testcontainers) 2/2 com o contexto real da API.

---
*Phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o*
*Completed: 2026-10-31*
