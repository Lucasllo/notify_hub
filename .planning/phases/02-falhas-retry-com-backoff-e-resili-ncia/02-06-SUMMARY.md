---
phase: 02-falhas-retry-com-backoff-e-resili-ncia
plan: 06
subsystem: worker
tags: [micrometer, prometheus, metrics, resilience4j, actuator, spring-boot]

requires:
  - phase: 02-falhas-retry-com-backoff-e-resili-ncia
    provides: "02-05: ChannelGateway (bulkhead → CB → adapter) e recusas CIRCUIT_OPEN/BULKHEAD_FULL como transitórias"
  - phase: 02-falhas-retry-com-backoff-e-resili-ncia
    provides: "02-02: pontos de resultado com fence (markSent, markRetrying + publishToRetry, markDeadLettered, markDeadLetteredAfterRepublishFailure, markFailed)"
  - phase: 02-falhas-retry-com-backoff-e-resili-ncia
    provides: "02-01: FailureClassifier e AttemptOutcome com UNKNOWN_FAILURE"
provides:
  - "DeliveryMetrics (@Component): 42 séries do D-15 pré-registradas com valor 0 (15 outcomes, 9 retries, 12 timers de duração com histograma, 6 rejections)"
  - "Métodos sent, retrying(canal, nível), failed(canal, classe), deadLettered, rejected(canal, RejectionReason) e recordAdapterCall(canal, Runnable)"
  - "micrometer-registry-prometheus no worker (versão do Boot) e management.endpoints.web.exposure.include: health,info,prometheus"
  - "DeliveryProcessor conta cada resultado só depois do fence (e do confirm, no retry)"
  - "ChannelGateway mede só a chamada ao adapter, por dentro do CB e do bulkhead, e conta as recusas"
affects: [02-07, fase-4-dashboard, fase-4-benchmark, readme]

actuals:
  tokens: 15454
  tasks: 2
  commits: 4
plan_head_before: 3d15ff84f0710c65c140578cc0b5b26e4861d0d2

tech-stack:
  added: ["io.micrometer:micrometer-registry-prometheus (1.15.12, gerenciado pelo Boot 3.5.16)"]
  patterns:
    - "Métricas pré-registradas no construtor e guardadas em EnumMap por canal; incremento sem lookup no registry"
    - "Tags de conjunto fechado com as mesmas chaves por nome (sent leva failure_class=\"none\")"
    - "Timer por dentro dos decorators do Resilience4j, em volta só do adapter; recusas num contador próprio"
    - "Contador incrementado no ponto de resultado só depois do update condicional devolver true"

key-files:
  created:
    - notify-worker/src/main/java/dev/notifyhub/worker/metrics/DeliveryMetrics.java
    - notify-worker/src/test/java/dev/notifyhub/worker/metrics/DeliveryMetricsTest.java
  modified:
    - notify-worker/pom.xml
    - notify-worker/src/main/resources/application.yml
    - notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java
    - notify-worker/src/main/java/dev/notifyhub/worker/resilience/ChannelGateway.java
    - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/resilience/ChannelGatewayTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/config/WorkerSettingsTest.java

key-decisions:
  - "O outcome do timer vem de AttemptOutcome em minúsculo (success, transient_failure, permanent_failure, unknown_failure), via FailureClassifier.classify(e).outcome(); Error não é medido nem capturado"
  - "retrying(canal, nível) recebe o nível de destino h+1 e lança IllegalArgumentException fora de 1..N; failed com TRANSIENT também lança, porque transitória nunca vira FAILED"
  - "failed e deadLettered contam logo depois do fence ganho, antes da cópia na DLQ: se a cópia não for confirmada, o reject leva a original à DLQ do mesmo jeito"
  - "DeliveryProcessorTest passou a usar DeliveryMetrics real sobre SimpleMeterRegistry (não mais mock), para afirmar valores dos contadores"

patterns-established:
  - "Nova métrica de domínio: pré-registrar no construtor, tags fechadas, conferir nomes com PrometheusMeterRegistry.scrape() em teste sem Docker"
  - "Medição de latência sempre por dentro de CB/bulkhead; recusa nunca entra em timer"

requirements-completed: [OBS-01]

coverage:
  - id: D1
    description: "Logo depois do construtor existem 15 contadores notify.delivery.outcomes, 9 notify.delivery.retries (6 com 2 níveis), 12 timers notify.delivery.duration e 6 contadores notify.delivery.rejections, todos em 0; mesmo conjunto de chaves de tag por nome; só as 42 séries e só as chaves channel/outcome/failure_class/level/reason"
    requirement: OBS-01
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/metrics/DeliveryMetricsTest.java (outcomesArePreRegisteredWithFifteenZeroSeries, retriesArePreRegisteredOnePerChannelAndLevel, retriesFollowTheNumberOfConfiguredLevels, durationTimersArePreRegisteredWithTheFourAdapterOutcomes, rejectionsArePreRegisteredForBulkheadAndCircuitBreaker, everyMeterOfTheSameNameHasTheSameTagKeys, onlyTheFortyTwoClosedSetSeriesExist)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Com PrometheusMeterRegistry, depois de sent(EMAIL) o scrape tem notify_delivery_outcomes_total{channel=\"email\",failure_class=\"none\",outcome=\"sent\"} 1.0 e contém notify_delivery_retries_total, notify_delivery_duration_seconds_bucket e notify_delivery_rejections_total; o application.yml expõe health,info,prometheus"
    requirement: OBS-01
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/metrics/DeliveryMetricsTest.java#sentShowsUpInThePrometheusScrapeWithTheD15Names"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/config/WorkerSettingsTest.java#actuatorExposesThePrometheusScrapeEndpoint"
        status: pass
    human_judgment: false
  - id: D3
    description: "Contadores só sobem com resultado real: sent depois do markSent; retrying + retries{level=h+1} só depois do confirm; dead_lettered depois de markDeadLettered ou markDeadLetteredAfterRepublishFailure; failed{permanent|unknown} depois de markFailed (mesmo com cópia na DLQ caindo no reject); fence perdido em qualquer ramo não conta nada"
    requirement: OBS-01
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java (sentIsCountedOnlyAfterTheSentUpdateWinsTheFence, lostFenceOnSentCountsNothing, confirmedRetryCountsRetryingAndTheTargetLevel, retryFromLevelOneCountsTheSecondLevel, failedRepublishCountsDeadLetteredAndNoRetry, exhaustedLadderCountsDeadLettered, permanentFailureCountsFailedPermanent, unknownFailureCountsFailedUnknown, failedIsCountedEvenWhenTheDlqCopyFallsBackToReject, lostFenceOnRetryingCountsNothing, lostFenceOnFailedCountsNothing, lostFenceOnDeadLetteredCountsNothing, lostFenceAfterFailedRepublishCountsNothing)"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java (happyPathCountsOneSentEmailOutcome, lostFenceOnSentCountsNothing, alwaysTransientCountsOneRetryPerLevelAndOneDeadLettered, alwaysPermanentCountsOneFailedPermanent)"
        status: pass
    human_judgment: false
  - id: D4
    description: "O timer notify.delivery.duration mede só a chamada ao adapter (success/transient_failure/permanent_failure/unknown_failure, exceção relançada sem alteração); CB aberto e bulkhead cheio incrementam rejections{circuit_open|bulkhead_full} e não mexem em nenhum timer; com o gateway real, CB email aberto conta circuit_open e um retry confirmado"
    requirement: OBS-01
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/resilience/ChannelGatewayTest.java (successfulAdapterCallIsTimedAsSuccess, transientAdapterFailureIsTimedAsTransientAndStillReachesTheCaller, permanentAdapterFailureIsTimedAsPermanent, openCircuitBreakerRefusalIsCountedAsRejectionAndNeverTimed, fullBulkheadRefusalIsCountedAsRejectionAndNeverTimed)"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java#openCircuitBreakerCountsACircuitOpenRejectionAndAConfirmedRetry"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/metrics/DeliveryMetricsTest.java#adapterCallIsTimedWithTheOutcomeOfItsClassificationAndTheFailureIsRethrown"
        status: pass
    human_judgment: false
  - id: D5
    description: "Runtime real: curl http://localhost:8081/actuator/prometheus mostra as séries notify_delivery_* e resilience4j_* com o worker ligado a RabbitMQ e Mongo"
    requirement: OBS-01
    verification: []
    human_judgment: true
    rationale: "O endpoint real só existe com o contexto Spring completo e broker/Mongo; o plano 02-07 (smoke) confere isso. Aqui só há testes sem Docker"

duration: 10min
completed: 2026-10-15
status: complete
---

# Phase 02 Plan 06: Métricas de entrega no Prometheus Summary

**`DeliveryMetrics` pré-registra as 42 séries do D-15 com valor 0 e o worker passa a expor `/actuator/prometheus`: resultados por canal só depois do fence (e do confirm, no retry), retries por nível, recusas de CB/bulkhead num contador próprio e latência do adapter com histograma, medida por dentro do CB e do bulkhead**

## Performance

- **Duration:** 10 min
- **Started:** 2026-10-15T23:43:27Z
- **Completed:** 2026-10-15T23:53:55Z
- **Tasks:** 2
- **Files modified:** 10

## Accomplishments
- `notify.delivery.outcomes{channel,outcome,failure_class}` (15), `notify.delivery.retries{channel,level}` (9), timer `notify.delivery.duration{channel,outcome}` com `publishPercentileHistogram()` entre 1ms e 30s (12) e `notify.delivery.rejections{channel,reason}` (6), todos em 0 desde a subida
- Nomes do Prometheus conferidos com `PrometheusMeterRegistry.scrape()` sem Docker: `notify_delivery_outcomes_total`, `notify_delivery_retries_total`, `notify_delivery_duration_seconds_bucket`, `notify_delivery_rejections_total`
- `DeliveryProcessor` conta sent/retrying/dead_lettered/failed só quando o update condicional vence; retry só depois do confirm da fila de delay
- `ChannelGateway` mede só o adapter, por dentro dos dois decorators; `CallNotPermittedException` e `BulkheadFullException` incrementam `rejections` e são relançadas sem alteração
- `micrometer-registry-prometheus` no worker, sem versão própria, e `management.endpoints.web.exposure.include: health,info,prometheus`. Isso também publica as métricas automáticas do `resilience4j-micrometer`
- Suíte do worker verde: 322 testes sem Docker (eram 284); `./mvnw -q test` do reactor também verde

## Task Commits

1. **Task 1: séries do D-15 pré-registradas e `sent` em `/actuator/prometheus` (tracer)**: RED `99699e2` (test), GREEN `38ee4ea` (feat)
2. **Task 2: retries, falhas, DLQ, recusas e latência real do adapter**: RED `a6ca33c` (test), GREEN `93e76a1` (feat)

**Plan metadata:** commit final de docs (este SUMMARY + STATE/ROADMAP/REQUIREMENTS)

## TDD Gate Compliance

| Tarefa | RED | GREEN | REFACTOR |
|--------|-----|-------|----------|
| 1 | `99699e2` (RED_EVIDENCE_OK: `DeliveryMetricsTest#outcomesArePreRegisteredWithFifteenZeroSeries` esperava 15 séries e encontrou 0; 13 de 63 testes falharam por asserção) | `38ee4ea` | não precisou |
| 2 | `a6ca33c` (RED_EVIDENCE_OK: `DeliveryProcessorTest#confirmedRetryCountsRetryingAndTheTargetLevel` esperava 1.0 e veio 0.0; 15 de 92 testes falharam por asserção) | `93e76a1` | não precisou |

O commit RED da Task 1 leva um esqueleto de `DeliveryMetrics` que só compila (métodos vazios) e o construtor do processor já recebendo as métricas, como nos planos 02-02, 02-04 e 02-05. O RED da Task 2 só mudou o construtor do `ChannelGateway`, para os testes compilarem. Os testes novos do `DeliveryMetricsTest` na Task 2 (incrementos e `recordAdapterCall`) já passaram no RED, porque a Task 1 implementou a classe inteira como o plano pedia. Eles ficam como cobertura do contrato. Em cada RED, os relatórios do surefire foram convertidos para TAP e validados com `check tdd-red-evidence`. Nenhuma falha foi de compilação ou de carga.

Tracer gate (Task 1): modo interativo, `human_verify_mode` end-of-phase, `<verify>` só automatizado. O verify foi reexecutado (`./mvnw -q -pl notify-worker test -Dtest=DeliveryMetricsTest,DeliveryFlowTest,DeliveryProcessorTest,WorkerSettingsTest`, saída 0, 63 testes) antes da expansão.

## Files Created/Modified
- `notify-worker/pom.xml`: `io.micrometer:micrometer-registry-prometheus` sem versão
- `notify-worker/src/main/resources/application.yml`: exposição `health,info,prometheus` com comentário sobre a porta 8081 sem autenticação
- `notify-worker/.../metrics/DeliveryMetrics.java`: pré-registro, `EnumMap` por canal, `RejectionReason`, `recordAdapterCall` com `Timer.Sample`
- `notify-worker/.../processing/DeliveryProcessor.java`: `DeliveryMetrics` no construtor; contadores nos pontos de resultado
- `notify-worker/.../resilience/ChannelGateway.java`: `DeliveryMetrics` no construtor; timer por dentro dos decorators; captura e relançamento das recusas
- Testes: `DeliveryMetricsTest` (novo); `DeliveryProcessorTest`, `DeliveryFlowTest`, `ChannelGatewayTest`, `WorkerSettingsTest` (ampliados)

## Decisions Made
- O `outcome` do timer reaproveita `AttemptOutcome` em minúsculo, a mesma classificação gravada na tentativa, para o dashboard e o Mongo usarem o mesmo vocabulário.
- `failed` e `deadLettered` contam depois do fence e antes da cópia na DLQ. A suposição sinalizada do plano vale: a mensagem chega à DLQ pelo reject mesmo sem confirm da cópia.
- `retrying` valida o nível (1..N) e `failed(TRANSIENT)` lança exceção. Um uso errado aparece no teste, em vez de criar série nova ou contar errado.
- O `DeliveryProcessorTest` trocou o mock de `DeliveryMetrics` da Task 1 por métricas reais sobre `SimpleMeterRegistry`, como pede o `<behavior>` da Task 2.

## Deviations from Plan

None - plan executed exactly as written. Os testes a mais fora do `<behavior>` (todos de teste, sem efeito em produção) foram: `onlyTheFortyTwoClosedSetSeriesExist` (T-02-24), retry do nível 1 indo para o nível 2, perda de fence em cada ramo, `failed` contado mesmo com reject, escada completa e permanente no `DeliveryFlowTest`, e os testes unitários de incremento do `DeliveryMetricsTest`.

## Issues Encountered
- Um heredoc grande com script Python falhou no Git Bash (`unexpected EOF`) e não alterou nenhum arquivo. O script foi gravado no scratchpad e executado de lá.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- 02-07 (smoke) deve conferir no endpoint real `curl http://localhost:8081/actuator/prometheus` as séries `notify_delivery_*` em 0 logo após a subida, os incrementos depois dos cenários mágicos e as séries `resilience4j_circuitbreaker_*` / `resilience4j_bulkhead_*`.
- Fase 4: o dashboard usa `notify_delivery_outcomes_total`, `notify_delivery_retries_total`, `histogram_quantile(0.95, sum by (le, channel) (rate(notify_delivery_duration_seconds_bucket[1m])))` e `notify_delivery_rejections_total`. Os nomes e as tags ficam fixos daqui em diante (D-15, reversibilidade costly).

---
*Phase: 02-falhas-retry-com-backoff-e-resili-ncia*
*Completed: 2026-10-15*

## Self-Check: PASSED

- Arquivos criados presentes: DeliveryMetrics.java, DeliveryMetricsTest.java
- Commits presentes: 99699e2, 38ee4ea, a6ca33c, 93e76a1
- Critérios de aceite das duas tarefas: todos PASS (grep + `./mvnw -q -pl notify-worker test` com saída 0, 322 testes; `./mvnw -q test` do reactor com saída 0)
