---
phase: 04-observabilidade-demo-e-documenta-o
plan: 02
subsystem: api
tags: [micrometer, metrics, prometheus, rabbitmq, publisher-confirms, tdd]

# Dependency graph
requires:
  - phase: 03-reprocessamento-e-administracao
    provides: "NotificationRepublisher como ponto único de publish/republish e varredura de PENDING sem confirm"
provides:
  - "Contador notify.publish.outcomes{outcome, reason} (Prometheus: notify_publish_outcomes_total) com 7 séries pré-registradas"
  - "dev.notifyhub.api.metrics.PublishMetrics (@Component): confirmed(), confirmedByClaim(), failed(PublishResult.Outcome), unconfirmed(long)"
  - "Contagem em NotificationRepublisher.publishOrMarkFailed e markUnconfirmedAsPublishFailed"
affects: [04-03-dashboard-grafana, 04-05-readme, "smoke da Fase 4"]

actuals:
  tokens: 8206
  tasks: 2
  commits: 4
plan_head_before: 535d0141717be3c14e704cf2622da01ad90e54d6

tech-stack:
  added: []
  patterns:
    - "Contador pré-registrado com conjunto fechado de tags, espelhando DeliveryMetrics do worker (D-15)"
    - "Switch exaustivo do enum de domínio para o valor da tag reason"

key-files:
  created:
    - notify-api/src/main/java/dev/notifyhub/api/metrics/PublishMetrics.java
    - notify-api/src/test/java/dev/notifyhub/api/metrics/PublishMetricsTest.java
  modified:
    - notify-api/src/main/java/dev/notifyhub/api/application/NotificationRepublisher.java
    - notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java
    - notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java
    - notify-api/src/test/java/dev/notifyhub/api/application/UnconfirmedPublishSweeperTest.java
    - notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java
    - notify-api/src/test/java/dev/notifyhub/api/web/NotificationValidationTest.java

key-decisions:
  - "Contrato fixado (D-02): notify.publish.outcomes com outcome ∈ {confirmed, publish_failed} e reason ∈ {none, claimed, nack, returned, timeout, broker_unavailable, unconfirmed}, 7 séries"
  - "Falha do MongoDB ao marcar PUBLISH_FAILED ou reler o documento não conta; se o documento ficar PENDING, a varredura conta depois como publish_failed/unconfirmed"
  - "Reprocessamento da DLQ e cópia para o fim da DLQ ficam fora do contador por desenho (não gravam PUBLISH_FAILED)"

patterns-established:
  - "Métrica da API em dev.notifyhub.api.metrics, com SimpleMeterRegistry nos testes e @MockitoBean nas fatias @WebMvcTest"

requirements-completed: [OBS-02]

coverage:
  - id: D1
    description: "PublishMetrics pré-registra 7 contadores notify.publish.outcomes em 0, todos com as chaves exatas {outcome, reason} e combinações do contrato fechado"
    requirement: "OBS-02"
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/metrics/PublishMetricsTest.java#outcomesArePreRegisteredWithSevenZeroSeries"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/metrics/PublishMetricsTest.java#everySeriesHasExactlyTheOutcomeAndReasonTagKeys"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/metrics/PublishMetricsTest.java#combinationsAreExactlyTheClosedContract"
        status: pass
    human_judgment: false
  - id: D2
    description: "Cada método de PublishMetrics incrementa só a sua série; failed(ACK) e unconfirmed(-1) lançam IllegalArgumentException; unconfirmed(0) não muda nada"
    requirement: "OBS-02"
    verification:
      - kind: unit
        ref: "./mvnw -pl notify-api test -Dtest=PublishMetricsTest (13 testes)"
        status: pass
    human_judgment: false
  - id: D3
    description: "publishOrMarkFailed conta exatamente uma vez por desfecho (ack, nack gravado, claim do worker, returned/broker_unavailable sem claim) e não conta na falha do MongoDB"
    requirement: "OBS-02"
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java#ackCountsConfirmed, nackWithPublishFailedWrittenCountsPublishFailedNack, timeoutWithZeroMatchAndTheWorkerClaimCountsConfirmedClaimed, returnedWithZeroMatchAndTheDocumentPublishFailedCountsPublishFailedReturned, brokerUnavailableWithZeroMatchAndTheDocumentMissingCountsPublishFailedBrokerUnavailable, mongoDownWhileMarkingPublishFailedCountsNothing, mongoDownWhileRereadingTheDocumentCountsNothing"
        status: pass
    human_judgment: false
  - id: D4
    description: "Varredura conta publish_failed/unconfirmed por documento marcado (3 → 3, 0 → 0, 2 pelo sweeper, MongoDB fora → 0)"
    requirement: "OBS-02"
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java#sweepCountsPublishFailedUnconfirmedPerMarkedDocument, sweepThatMarksNothingCountsNothing"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/UnconfirmedPublishSweeperTest.java#sweepMarksOnlyOldUnclaimedPendingAsPublishFailed"
        status: pass
    human_judgment: false
  - id: D5
    description: "Série notify_publish_outcomes_total visível com 7 séries no /actuator/prometheus da API em container"
    requirement: "OBS-02"
    verification: []
    human_judgment: true
    rationale: "Conferência ponta a ponta prevista no smoke do 04-03, depois da reconstrução da imagem da API; este plano é só Java, sem Docker"

duration: 6min
completed: 2026-11-01
status: complete
---

# Phase 4 Plan 02: Contador de resultado do publish da API Summary

**Contador Micrometer `notify.publish.outcomes{outcome, reason}` com 7 séries pré-registradas em 0, contado só em `NotificationRepublisher.publishOrMarkFailed` (ack, claim do worker, PUBLISH_FAILED por motivo de `PublishResult.Outcome`) e na varredura de PENDING sem confirm**

## Performance

- **Duration:** 6 min nesta retomada (a execução anterior caiu por erro de rede durante o RED da Task 1, sem commits)
- **Started:** 2026-11-01T22:34:24Z
- **Completed:** 2026-11-01T22:40:58Z
- **Tasks:** 2
- **Files modified:** 8

## Accomplishments
- Contrato D-02 fixado: `notify_publish_outcomes_total` com `outcome` ∈ {confirmed, publish_failed} e `reason` ∈ {none, claimed, nack, returned, timeout, broker_unavailable, unconfirmed}. As 7 séries existem desde o startup, com as mesmas chaves e sem tag dinâmica (T-04-07, T-04-08)
- Ponto único de contagem: cada `publishOrMarkFailed` que termina conta uma vez, a falha do MongoDB não conta e a varredura conta por documento (T-04-09)
- `NotificationPublisher`, `DlqReprocessService` e `UnconfirmedPublishSweeper` sem diff, então o reprocessamento da DLQ fica fora do contador
- Suíte da API verde: 196 testes, 0 falhas

## Task Commits

1. **Task 1 (tracer): publicação confirmada em confirmed/none**
   - `2002313` test(04-02): RED, contrato do PublishMetrics e ackCountsConfirmed
   - `17553b0` feat(04-02): GREEN, PublishMetrics completo e `metrics.confirmed()` no ack
2. **Task 2: sem confirm, claim, varredura e falha do MongoDB**
   - `823e9a9` test(04-02): RED, testes por ramo e contagem da varredura
   - `4ed8e81` feat(04-02): GREEN, `metrics.failed(...)` ×2, `confirmedByClaim()` e `unconfirmed(marked)`

## Files Created/Modified
- `notify-api/src/main/java/dev/notifyhub/api/metrics/PublishMetrics.java`: contador pré-registrado, com Javadoc de contrato em pt-BR
- `notify-api/src/test/java/dev/notifyhub/api/metrics/PublishMetricsTest.java`: 7 séries, chaves exatas, combinações fechadas e incrementos (13 testes)
- `notify-api/src/main/java/dev/notifyhub/api/application/NotificationRepublisher.java`: quarto parâmetro `PublishMetrics` no construtor, contagem nos ramos e parágrafo no Javadoc da classe
- `notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java`: 9 testes novos do contador
- `notify-api/src/test/java/dev/notifyhub/api/application/UnconfirmedPublishSweeperTest.java`: campos `registry`/`metrics` e asserções de `publish_failed/unconfirmed`
- `notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java`: construções com `new PublishMetrics(new SimpleMeterRegistry())`
- `notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java` e `NotificationValidationTest.java`: `@MockitoBean PublishMetrics publishMetrics`

## Decisions Made
- `failed(Outcome)` usa um `EnumMap` montado por um switch exaustivo (`reason()`), e `ACK` lança `IllegalArgumentException`. Um valor novo no enum quebra a compilação, então nunca vira série silenciosa
- O ramo `confirmed/claimed` tem série própria, como a pesquisa recomendou (A4)
- Falha do MongoDB não conta. O comentário no `catch` explica que a varredura conta o documento depois, como `unconfirmed`

## Deviations from Plan

### Notas de processo

**1. Commit RED da Task 1 inclui o esqueleto de produção**
- **Found during:** Task 1 (retomada)
- **Issue:** em Java, o RED só compila com `PublishMetrics` e o construtor de 4 parâmetros existindo
- **Fix:** o commit `test(04-02)` leva o esqueleto sem comportamento (construtor e métodos vazios) e a injeção no construtor, sem nenhuma contagem. A falha do RED veio de asserções ("Expected size: 7 but was: 0"), não de compilação
- **Verification:** `check tdd-red-evidence` → RED_EVIDENCE_OK nos dois REDs. O registro foi montado em TAP a partir dos XML do Surefire
- **Committed in:** 2002313

**2. Teste extra: MongoDB fora na releitura**
- **Found during:** Task 2
- **Issue:** a must_have cita a falha "ao marcar PUBLISH_FAILED ou ao reler o documento", mas o `<behavior>` só listava a marcação
- **Fix:** acrescentado `mongoDownWhileRereadingTheDocumentCountsNothing`
- **Committed in:** 823e9a9

---

**Total deviations:** 0 auto-fixes de código; 2 notas de processo
**Impact on plan:** nenhum desvio de escopo. O trabalho parcial da execução anterior foi revisado, conferia com o plano e foi mantido

## TDD Gate Compliance

| Task | RED | GREEN | REFACTOR |
|------|-----|-------|----------|
| 1 | 2002313 (14 falhas de asserção, RED_EVIDENCE_OK) | 17553b0 | não necessário |
| 2 | 823e9a9 (6 falhas de asserção, RED_EVIDENCE_OK; os 2 guardas "não conta nada" passam por desenho) | 4ed8e81 | não necessário |

## Issues Encountered
- A execução anterior caiu durante o RED da Task 1, sem commits. Os arquivos parciais foram revisados e mantidos sem reescrita

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- O 04-03 pode escrever o painel "Resultado do publish" com `sum by (outcome, reason) (rate(notify_publish_outcomes_total[1m]))` e conferir as 7 séries no smoke, depois de reconstruir a imagem da API
- Sem bloqueios

---
*Phase: 04-observabilidade-demo-e-documenta-o*
*Completed: 2026-11-01*

## Self-Check: PASSED
- Arquivos criados existem: PublishMetrics.java, PublishMetricsTest.java
- Commits existem: 2002313, 17553b0, 823e9a9, 4ed8e81
- Critérios de aceite das 2 tasks: PASS; `./mvnw -q -pl notify-api test`: 196/196
