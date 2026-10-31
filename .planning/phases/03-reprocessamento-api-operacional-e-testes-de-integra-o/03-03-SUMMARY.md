---
phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o
plan: 03
subsystem: testing
tags: [testcontainers, failsafe, awaitility, rabbitmq, mongodb, dlq, reprocess, micrometer, virtual-threads]

requires:
  - phase: 03-01
    provides: harness notify-it (EndToEndSupport, profile it com escada 200ms/500ms/1s e chave admin)
  - phase: 03-02
    provides: POST /admin/dlq/{canal}/reprocess, DlqReprocessService (ramo padrão → skipped), NotificationResetService com fence status + retryCount
  - phase: 02-falhas-retry-com-backoff-e-resili-ncia
    provides: escada TTL+DLX, cópia na DLQ com x-failure-class, destinatários mágicos, DeliveryMetrics, CR-01 em deferred-items.md
provides:
  - ReprocessFlowIT (TST-02) com o ciclo retry → DLQ → reprocess → SENT pelo HTTP real, a segunda chamada sem efeito e as recusas admin que não movem a DLQ
  - ReprocessEdgeCasesIT com CR-01, lease vigente, discarded, corpo inválido, orçamento e chamadas concorrentes
  - DeliveryGuaranteesIT (TST-03) com venenosa sem loop, permanente sem retry e duplicata concorrente com exatamente um envio
  - transições D-01 (PENDING/RETRYING/PROCESSING → PENDING) na tabela de NotificationStatus
  - NotificationResetService.LeaseActive e o fence de PROCESSING com $or de leaseUntil vencido ou nulo
  - CR-01 da Fase 2 resolvido
affects: [03-04, 03-05, 03-06, 03-07, fase-4-readme]

actuals:
  tokens: 13074
  tasks: 3
  commits: 5
plan_head_before: 4cd422dd2884e929e7716533db1569fa2f6114e3

tech-stack:
  added: []
  patterns:
    - "IT de borda monta o estado direto: documento inserido no Mongo + token posto na DLQ pelo RabbitTemplate da API em notify.dlx/email.dlq"
    - "Prova de 'exatamente um envio' pelo delta do timer notify.delivery.duration{outcome=success} no MeterRegistry do worker, estável com Awaitility during"
    - "Status guardado por lease derivado da tabela (reclaimableWhenStuck), nunca PROCESSING escrito à mão no reset"

key-files:
  created:
    - notify-it/src/test/java/dev/notifyhub/it/ReprocessFlowIT.java
    - notify-it/src/test/java/dev/notifyhub/it/ReprocessEdgeCasesIT.java
    - notify-it/src/test/java/dev/notifyhub/it/DeliveryGuaranteesIT.java
  modified:
    - notify-common/src/main/java/dev/notifyhub/common/domain/NotificationStatus.java
    - notify-common/src/test/java/dev/notifyhub/common/domain/NotificationStatusTest.java
    - notify-api/src/main/java/dev/notifyhub/api/application/NotificationResetService.java
    - notify-api/src/main/java/dev/notifyhub/api/admin/DlqReprocessService.java
    - notify-api/src/test/java/dev/notifyhub/api/admin/DlqReprocessFlowTest.java
    - .planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/deferred-items.md

key-decisions:
  - "03-03: o status guardado por lease no reset vem de NotificationStatus.reclaimableWhenStuck(), não de PROCESSING escrito à mão; lease vigente → LeaseActive sem escrita, lease vencido ou nulo → fence com $or leaseUntil < agora / leaseUntil nulo (o mesmo agora da classificação)"
  - "03-03: NotResettable continua na interface selada, embora hoje só SENT não possa ir a PENDING (e SENT tem resultado próprio); fica como rede de segurança derivada da tabela"
  - "03-03: o IT de duplicata usa registry.get(...) em vez de find(...): série ausente falha o teste em vez de virar NPE"

patterns-established:
  - "ITs de reprocessamento esperam unackedCount com atMost 15s e poll de 500ms (estatística do painel agregada a cada ~5s)"
  - "Chamadas concorrentes em ITs: Executors.newVirtualThreadPerTaskExecutor + CountDownLatch, asserção sobre a soma das contagens"

requirements-completed: [TST-02, TST-03, ADM-01]

coverage:
  - id: D1
    description: "./mvnw verify prova com broker e banco reais: fail-until-reprocess termina DEAD_LETTERED com 4 TRANSIENT_FAILURE e 1 mensagem na DLQ; o reprocess responde {10, 1, 0, 0}; a notificação chega a SENT com retryCount 0, attempts = 4 falhas + REPROCESSED (number 0, cita DEAD_LETTERED) + SUCCESS (number 5), attemptCount 5 e DLQ com 0 prontas e 0 sem ack"
    requirement: TST-02
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/ReprocessFlowIT.java#retryLadderEndsInTheDlqAndReprocessDeliversSent"
        status: pass
    human_judgment: false
  - id: D2
    description: "Uma segunda chamada com a DLQ vazia responde {10, 0, 0, 0} e a notificação continua SENT com exatamente um REPROCESSED"
    requirement: TST-02
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/ReprocessFlowIT.java#secondReprocessCallIsANoOp"
        status: pass
    human_judgment: false
  - id: D3
    description: "Sem chave, com chave errada, canal push e max 0/1001/abc: 401/400 em application/problem+json com o campo certo, e a DLQ continua com 1 pronta, 0 sem ack e o documento DEAD_LETTERED com as mesmas 4 tentativas"
    requirement: TST-02
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/ReprocessFlowIT.java#rejectedAdminCallsNeverMoveTheDlq"
        status: pass
    human_judgment: false
  - id: D4
    description: "Tabela D-01: PENDING/RETRYING/PROCESSING → PENDING, sourcesOf(PENDING) com 6 status; claimableForDelivery() e reclaimableWhenStuck() inalterados e 356 testes do worker sem regressão"
    requirement: ADM-01
    verification:
      - kind: unit
        ref: "notify-common/src/test/java/dev/notifyhub/common/domain/NotificationStatusTest.java (14 casos)"
        status: pass
      - kind: unit
        ref: "./mvnw -q -pl notify-worker test (356 testes)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Reset: RETRYING e PENDING com fence {_id, status, retryCount}; PROCESSING com lease vencido ou nulo com $or leaseUntil < agora / nulo; lease vigente sem findAndModify, cópia no fim e skipped; fence perdido continua skipped"
    requirement: ADM-01
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/admin/DlqReprocessFlowTest.java (28 casos)"
        status: pass
    human_judgment: false
  - id: D6
    description: "CR-01 com Mongo e RabbitMQ reais: PENDING, RETRYING, PROCESSING com lease vencido e com lease nulo → {10, 4, 0, 0} e os 4 até SENT com 1 REPROCESSED citando o status anterior; lease vigente → skipped e, depois de vencer, SENT; SENT + sem documento → discarded 2; corpo inválido → skipped e preservado; max=100 com 1 skipped não relê; max=1 com 3 deixa 2 prontas"
    requirement: ADM-01
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/ReprocessEdgeCasesIT.java (6 testes de borda)"
        status: pass
    human_judgment: false
  - id: D7
    description: "Duas chamadas concorrentes de reprocess com 6 DEAD_LETTERED: 200 nas duas, soma reprocessed 6, skipped e discarded 0, cada notificação SENT com exatamente 1 REPROCESSED e DLQ com 0 prontas e 0 sem ack"
    requirement: ADM-01
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/ReprocessEdgeCasesIT.java#concurrentReprocessCallsNeverReprocessTheSameMessageTwice"
        status: pass
    human_judgment: false
  - id: D8
    description: "Venenosa: corpo não JSON chega uma vez à DLQ, a fila principal fica vazia, as contagens ficam estáveis por 1s e x-death[0] tem count 1, reason rejected e queue notify.email"
    requirement: TST-03
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/DeliveryGuaranteesIT.java#poisonMessageGoesToTheDlqOnceWithoutLooping"
        status: pass
    human_judgment: false
  - id: D9
    description: "always-permanent termina FAILED com attemptCount 1 e uma PERMANENT_FAILURE; as três filas de delay ficam vazias por 1s e a cópia na DLQ tem x-failure-class PERMANENT"
    requirement: TST-03
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/DeliveryGuaranteesIT.java#alwaysPermanentEndsFailedWithAPermanentCopyAndNoRetry"
        status: pass
    human_judgment: false
  - id: D10
    description: "5 cópias concorrentes do mesmo token para um PENDING: SENT, attemptCount 1, uma SUCCESS, delta 1 no timer notify.delivery.duration{email,success} e no contador notify.delivery.outcomes{email,sent,none}, estáveis por 500ms"
    requirement: TST-03
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/DeliveryGuaranteesIT.java#concurrentDuplicateDeliveryCallsTheAdapterExactlyOnce"
        status: pass
    human_judgment: false
  - id: D11
    description: "Queda da API no meio de um reprocess devolve as entregas sem ack à DLQ quando o canal fecha, e nenhum documento fica PENDING sem mensagem (truth backstop do plano)"
    verification: []
    human_judgment: true
    rationale: "Depende do requeue do broker ao fechar o canal. Provar exigiria matar a JVM dos dois contextos no meio da chamada, e o plano marca essa verdade como backstop, sem teste"

duration: 23min
completed: 2026-10-31
status: complete
---

# Phase 3 Plan 03: ITs de reprocessamento e garantias de entrega + CR-01 Summary

**Três suítes Failsafe com RabbitMQ 4.3.6 e MongoDB 8.0.32 reais. Elas provam o ciclo retry → DLQ → reprocess → SENT (TST-02), mensagem venenosa sem loop, falha permanente sem retry e duplicata concorrente com exatamente um envio (TST-03). O CR-01 fecha: o reset agora retoma PENDING, RETRYING e PROCESSING com lease vencido, e preserva no fim da DLQ o PROCESSING com lease vigente.**

## Performance

- **Duration:** 23 min
- **Started:** 2026-10-31T13:29:23Z
- **Completed:** 2026-10-31T13:52:30Z
- **Tasks:** 3 (5 commits: Task 2 com RED + GREEN + IT)
- **Files modified:** 9 (3 criados, 6 alterados)

## Accomplishments

- **`ReprocessFlowIT` (tracer, TST-02):**
  - `fail-until-reprocess@fail.test` esgota a escada de 3 níveis e termina DEAD_LETTERED com 4 falhas.
  - O reprocess pelo HTTP real responde `{10, 1, 0, 0}`, e a notificação chega a SENT com `attempts` = 4 TRANSIENT_FAILURE + REPROCESSED (`number` 0) + SUCCESS (`number` 5) e `attemptCount` 5.
  - A segunda chamada não faz nada.
  - As 6 recusas admin (401/400 em `application/problem+json`) não movem a DLQ.
- **Transições D-01:** `PENDING → {PROCESSING, PUBLISH_FAILED, PENDING}`, `RETRYING → {PROCESSING, DEAD_LETTERED, PENDING}` e `PROCESSING → {…, PROCESSING, PENDING}`. `claimableForDelivery()` e `reclaimableWhenStuck()` não mudaram, e os 356 testes do worker passam.
- **Reset com lease:** `NotificationResetService` devolve `LeaseActive(leaseUntil)`, sem escrita, quando o lease de PROCESSING está vigente. Com lease vencido ou nulo, o `findAndModify` exige `$or` de `leaseUntil < agora` / `leaseUntil` nulo além do fence por status e `retryCount`, então um worker que reivindicou o documento no meio do caminho ganha a corrida.
- **`ReprocessEdgeCasesIT` (7 testes):**
  - CR-01: os 4 casos não terminais chegam a SENT.
  - Lease vigente vai para `skipped`; depois de o lease vencer, o documento chega a SENT.
  - `discarded` 2 para SENT e para mensagem sem documento.
  - Corpo inválido é preservado.
  - Orçamento sem releitura, e `max=1` deixa 2 prontas.
  - Duas chamadas concorrentes em threads virtuais somam `reprocessed` 6, com um REPROCESSED por documento.
- **`DeliveryGuaranteesIT` (3 testes, TST-03):**
  - Venenosa: `x-death` count 1, `rejected`, `notify.email`, estável por 1s.
  - Permanente: FAILED com `attemptCount` 1, filas de delay vazias e cópia `PERMANENT`.
  - 5 cópias concorrentes: delta 1 no timer do adapter e no contador `sent`.
- **CR-01** marcado `status: resolved` em `deferred-items.md` da Fase 2, com referência a 03-02 e 03-03.

## Task Commits

1. **Task 1: tracer, retry → DLQ → reprocess → SENT pelo HTTP real** - `1c3e12c` (test). Gate do tracer: execução interativa, `end-of-phase`, `<verify>` só automático. O verify rodou de novo (3/3 verdes) e a expansão seguiu sem checkpoint.
2. **Task 2: CR-01, não terminais reprocessados e lease vigente no fim da DLQ**
   - RED `f35efd9` (test): 2/14 no `NotificationStatusTest` e 4/28 no `DlqReprocessFlowTest` falharam por asserção.
   - GREEN `a4f1b31` (feat): 14/14, 28/28 e worker 356/356.
   - IT + deferred `e1f3127` (test): `ReprocessEdgeCasesIT` 7/7 junto com `ReprocessFlowIT` 3/3.
3. **Task 3: venenosa sem loop, permanente sem retry e duplicata com um envio** - `6ea7ec9` (test)

**Plan metadata:** commit `docs(03-03)` logo após este SUMMARY.

## Files Created/Modified

- `notify-it/.../ReprocessFlowIT.java`: TST-02 de ponta a ponta (3 testes)
- `notify-it/.../ReprocessEdgeCasesIT.java`: CR-01, D-02, D-04 e concorrência (7 testes)
- `notify-it/.../DeliveryGuaranteesIT.java`: TST-03 (3 testes)
- `notify-common/.../domain/NotificationStatus.java`: transições D-01, comentários e Javadoc
- `notify-common/.../domain/NotificationStatusTest.java`: transições → PENDING e `sourcesOf(PENDING)`
- `notify-api/.../application/NotificationResetService.java`: `LeaseActive`, `$or` do lease no fence, Javadoc do CR-01
- `notify-api/.../admin/DlqReprocessService.java`: só o Javadoc (parágrafo que citava "até as transições do 03-03")
- `notify-api/.../admin/DlqReprocessFlowTest.java`: RETRYING, PENDING, PROCESSING com lease vencido/nulo/vigente; casos "não resetável" trocados para lease vigente
- `.planning/phases/02-.../deferred-items.md`: CR-01 resolvido

## Decisions Made

- **Lease derivado da tabela.** O reset decide se o status é guardado por lease com `NotificationStatus.reclaimableWhenStuck().contains(status)`, mantendo a regra "nenhum status escrito à mão" do Javadoc da classe. O `agora` da classificação é o mesmo do `$lt` do fence.
- **`NotResettable` mantido.** Com a tabela nova, só SENT não vai a PENDING, e SENT já tem resultado próprio. O record continua como rede de segurança: uma transição removida no futuro volta a cair nele, e daí em `skipped`.
- **`registry.get` em vez de `find`** no IT de duplicata: série ausente vira falha clara, não NPE.

## Deviations from Plan

### Ajustes de execução

**1. [Processo] Commits em `main`**
- **Found during:** início da execução
- **Issue:** o protocolo do executor recusa commits na branch padrão, mas o projeto usa `branching_strategy: none` e o orquestrador mandou seguir em `main`.
- **Fix:** commits normais em `main`, com hooks e sem `--no-verify`. O pin de raiz (#4254) rodou antes da primeira escrita e antes de cada commit.
- **Commits:** todos os 5

**2. [Rule 1 - Bug] Javadoc desatualizado no `DlqReprocessService`**
- **Found during:** Task 2 (GREEN)
- **Issue:** o Javadoc dizia que, "até as transições do plano 03-03", um documento PENDING com a mensagem de volta à DLQ seria só preservado no fim. Depois do GREEN, a frase ficou falsa.
- **Fix:** o parágrafo agora diz que o próximo reprocess retoma o PENDING e que `LeaseActive` cai no ramo padrão. O código não mudou (o plano pedia o serviço inalterado, e a seção de desvios permite tocar esse arquivo).
- **Files modified:** `notify-api/src/main/java/dev/notifyhub/api/admin/DlqReprocessService.java`
- **Committed in:** `a4f1b31`

---

**Total deviations:** 2 (1 de processo, 1 correção de Javadoc)
**Impact on plan:** nenhum no escopo. Nenhum IT revelou defeito no código do reprocess.

## Issues Encountered

- O cenário de lease vigente do `DlqReprocessFlowTest` já passava no RED, porque o caminho antigo (`NotResettable` → cópia no fim) dá o mesmo resultado observável. Ele ficou como teste de regressão: depois da mudança na tabela, sem a checagem do lease, o reset tomaria o documento de um worker ativo e o teste falharia.
- Cada execução isolada do Failsafe leva de 45 a 55s por causa da subida dos containers e dos dois contextos. O `./mvnw -q verify` inteiro passou com 521 unitários (77 common, 88 api, 356 worker) e 15 ITs.
- Os avisos de LF → CRLF do Git aparecem em todo commit (Windows) e são inofensivos.

## TDD Gate Compliance

A Task 2 tem o gate RED `f35efd9` (`test(03-03)`) antes do GREEN `a4f1b31` (`feat(03-03)`), nessa ordem. Não houve REFACTOR. As Tasks 1 e 3 não têm `tdd="true"`: são só ITs e foram commitadas como `test`. O checador `tdd-red-evidence` lê saída TAP de Node e não se aplica ao Surefire. A evidência do RED são as falhas por asserção registradas no commit `f35efd9`.

## Known Stubs

Nenhum.

## Threat Flags

Nenhuma superfície nova fora do `<threat_model>`. T-03-12, T-03-13, T-03-15 e T-03-16 (`mitigate`) têm teste neste plano. T-03-14 (`accept`) é a verdade backstop D11.

## User Setup Required

Nenhum. Os ITs precisam só do Docker Desktop ligado.

## Next Phase Readiness

- O 03-04 (mesma onda) pode rodar seus `./mvnw` em seguida. Os ITs deste plano usam o POST sem `Idempotency-Key` e o construtor de 16 componentes de `NotificationDocument`.
- O 03-05 completa o TST-03 com o IT de publish não roteável e o republish.
- A Fase 4 (README) pode citar `ReprocessFlowIT`, `ReprocessEdgeCasesIT` e `DeliveryGuaranteesIT` como provas automáticas.

## Self-Check: PASSED

- Os 3 arquivos criados existem em disco.
- Os commits `1c3e12c`, `f35efd9`, `a4f1b31`, `e1f3127` e `6ea7ec9` estão no histórico.
- Todos os critérios de aceite das 3 tarefas deram PASS (grep e comandos).
- Verificação do plano:
  - `./mvnw -q -pl notify-it -am verify -Dit.test=ReprocessFlowIT`: verde (3/3), duas vezes.
  - `./mvnw -q -pl notify-common install && ./mvnw -q -pl notify-worker test && ./mvnw -q -pl notify-api test -Dtest=DlqReprocessFlowTest`: verde.
  - `./mvnw -q -pl notify-it -am verify -Dit.test=ReprocessEdgeCasesIT,ReprocessFlowIT`: verde (7/7 + 3/3).
  - `./mvnw -q -pl notify-it -am verify -Dit.test=DeliveryGuaranteesIT`: verde (3/3).
  - `./mvnw -q verify`: exit 0, 0 linhas `[ERROR]`, 521 unitários + 15 ITs.

---
*Phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o*
*Completed: 2026-10-31*
