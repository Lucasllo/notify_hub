---
phase: 02-falhas-retry-com-backoff-e-resili-ncia
plan: 05
subsystem: worker
tags: [resilience4j, circuit-breaker, bulkhead, retry, virtual-threads, spring-boot]

requires:
  - phase: 02-falhas-retry-com-backoff-e-resili-ncia
    provides: "02-04: ChannelAdapter.send(recipient, content, AttemptContext) com falha mágica dentro do adapter (always-transient em rajada)"
  - phase: 02-falhas-retry-com-backoff-e-resili-ncia
    provides: "02-02: ramo TRANSIENT (RETRYING → próximo nível → DEAD_LETTERED) e RetryPolicy"
  - phase: 02-falhas-retry-com-backoff-e-resili-ncia
    provides: "02-01: FailureClassifier, ErrorCode.CIRCUIT_OPEN/BULKHEAD_FULL, TransientDeliveryException"
provides:
  - "resilience4j-bom 2.4.0 no dependencyManagement do POM pai; resilience4j-spring-boot3 no worker (sem AOP)"
  - "ChannelGateway.send(Channel, String, RenderedContent, AttemptContext): bulkhead → CB → adapter por canal, com validação de startup"
  - "NonTransientFailurePredicate: ignore do CB para tudo que não é TransientDeliveryException"
  - "FailureClassifier: CallNotPermittedException → TRANSIENT/CIRCUIT_OPEN, BulkheadFullException → TRANSIENT/BULKHEAD_FULL"
  - "DeliveryProcessor recebe ChannelGateway no lugar de List<ChannelAdapter>"
  - "resilience4j.circuitbreaker/bulkhead configs.default + instances.{email,sms,webhook} com ENV NOTIFY_CB_WINDOW_SIZE, NOTIFY_CB_MIN_CALLS, NOTIFY_CB_FAILURE_RATE, NOTIFY_CB_OPEN_WAIT, NOTIFY_BULKHEAD_MAX_CONCURRENT"
  - "Profile demo: wait-duration-in-open-state 500ms"
  - "Métricas automáticas do resilience4j-micrometer disponíveis para o 02-06 expor"
affects: [02-06, 02-07, fase-4-benchmark, fase-4-k6, readme]

actuals:
  tokens: 11640
  tasks: 2
  commits: 4
plan_head_before: cf5862f73e3724f6c0b896f11de15eefa82e7982

tech-stack:
  added: ["io.github.resilience4j:resilience4j-bom 2.4.0", "io.github.resilience4j:resilience4j-spring-boot3 2.4.0"]
  patterns:
    - "Resilience4j programático: Bulkhead.decorateRunnable(bh, CircuitBreaker.decorateRunnable(cb, chamada)), instâncias resolvidas uma vez por registry.find(canal)"
    - "CB com record=TransientDeliveryException e ignore-exception-predicate = 'não é transitória' (nada conta como sucesso por omissão)"
    - "Invariante de configuração validada no construtor do bean (tempo aberto ≤ 1º nível), no mesmo estilo do TopologyProperties"
    - "Config do Resilience4j testada sem Docker com ApplicationContextRunner + ConfigDataApplicationContextInitializer + autoconfig real"

key-files:
  created:
    - notify-worker/src/main/java/dev/notifyhub/worker/resilience/ChannelGateway.java
    - notify-worker/src/main/java/dev/notifyhub/worker/resilience/NonTransientFailurePredicate.java
    - notify-worker/src/test/java/dev/notifyhub/worker/resilience/ChannelGatewayTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/resilience/ResilienceFixtures.java
    - notify-worker/src/test/java/dev/notifyhub/worker/config/ResilienceSettingsTest.java
  modified:
    - pom.xml
    - notify-worker/pom.xml
    - notify-worker/src/main/java/dev/notifyhub/worker/failure/FailureClassifier.java
    - notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java
    - notify-worker/src/main/resources/application.yml
    - notify-worker/src/main/resources/application-demo.yml
    - notify-worker/src/test/java/dev/notifyhub/worker/failure/FailureClassifierTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java

key-decisions:
  - "A validação do tempo aberto usa getWaitIntervalFunctionInOpenState().apply(1) (getter confirmado com javap no jar 2.4.0) e compara com topology.retryLevels().get(0); mensagem pt-BR cita canal, espera em ms e rótulo do nível"
  - "O indexByChannel saiu do DeliveryProcessor para o ChannelGateway com as mesmas mensagens; o gateway também falha ao subir sem instância de CB ou bulkhead"
  - "O profile demo fixa wait-duration-in-open-state: 500ms literal (critério do plano); nesse profile o ENV NOTIFY_CB_OPEN_WAIT fica sombreado"
  - "ResilienceFixtures (teste) reproduz a config do YAML para os testes sem Spring; o ResilienceSettingsTest prova que o YAML real monta os mesmos valores"

patterns-established:
  - "Proteção por canal fica atrás do ChannelGateway: trocar biblioteca ou ordem dos decorators muda uma classe"
  - "Recusa de proteção (CB/bulkhead) é falha transitória comum: grava tentativa com durationMs ≈ 0 e desce um nível"

requirements-completed: [RES-01, RES-02]

coverage:
  - id: D1
    description: "Com o CB email aberto, uma mensagem EMAIL em h=0 grava RETRYING com tentativa CIRCUIT_OPEN/TRANSIENT e durationMs < 50, publica em email.retry.5s com notify-retry-count 1, não chama o adapter e não publica na DLQ; com o CB fechado o caminho feliz chega a SENT pelo gateway (1 chamada bem-sucedida no CB, 8 permissões livres no bulkhead)"
    requirement: RES-01
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java (openCircuitBreakerTurnsTheDeliveryIntoACircuitOpenRetryWithoutCallingTheAdapter, closedCircuitBreakerLetsTheHappyPathReachSentThroughTheGateway)"
        status: pass
    human_judgment: false
  - id: D2
    description: "CallNotPermittedException vira TRANSIENT/CIRCUIT_OPEN e BulkheadFullException vira TRANSIENT/BULKHEAD_FULL; o processor grava RETRYING, republica no próximo nível e nunca chama markFailed"
    requirement: RES-02
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java (openCircuitBreakerRefusalIsATransientCircuitOpenRetryAndNeverFailed, fullBulkheadRefusalIsATransientBulkheadFullRetryAndNeverFailed)"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/failure/FailureClassifierTest.java (openCircuitBreakerRefusalIsTransientCircuitOpen, fullBulkheadRefusalIsTransientBulkheadFull)"
        status: pass
    human_judgment: false
  - id: D3
    description: "O CB só conta transitórias: 10 permanentes e 10 desconhecidas deixam o CB CLOSED; 10 transitórias o abrem e a 11ª chamada leva CallNotPermittedException sem chamar o adapter; um canal aberto não afeta os outros"
    requirement: RES-01
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/resilience/ChannelGatewayTest.java (tenPermanentFailuresDoNotOpenTheCircuitBreaker, tenTransientFailuresOpenTheCircuitBreakerAndTheEleventhCallIsRefusedWithoutCallingTheAdapter, tenUnknownFailuresDoNotOpenTheCircuitBreaker, openCircuitBreakerOfOneChannelDoesNotAffectTheOthers, adapterFailurePropagatesUnchangedThroughBothDecorators)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Bulkhead por semáforo com espera 0: com uma chamada presa no adapter, a segunda leva BulkheadFullException em menos de 500ms sem chamar o adapter; liberada a primeira, a próxima passa; a recusa não entra na janela do CB"
    requirement: RES-02
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/resilience/ChannelGatewayTest.java#fullBulkheadRefusesTheSecondCallImmediatelyAndReleasesThePermitAfterwards"
        status: pass
    human_judgment: false
  - id: D5
    description: "O worker não sobe sem instância de CB ou bulkhead por canal, sem adapter por canal, com adapter duplicado ou com tempo aberto maior que o 1º nível (6s contra 5s, verificado por canal); 5s contra 5s sobe"
    requirement: RES-01
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/resilience/ChannelGatewayTest.java (missingCircuitBreakerInstanceRefusesToStart, missingBulkheadInstanceRefusesToStart, missingAdapterRefusesToStart, duplicateAdapterRefusesToStart, openWaitLongerThanTheFirstRetryLevelRefusesToStart, openWaitIsCheckedForEveryChannelInstance, openWaitEqualToTheFirstRetryLevelStartsNormally)"
        status: pass
    human_judgment: false
  - id: D6
    description: "O application.yml real, pela autoconfiguração do Resilience4j, monta em email/sms/webhook um CB COUNT_BASED 20/10/50%/2500ms/3 com transição automática e record/ignore corretos, e bulkhead 8 (< concorrência 10) com espera 0; demo abre por 500ms (≤ 1s); placeholders NOTIFY_* presentes; nenhum indicador de saúde ligado"
    requirement: RES-01
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/config/ResilienceSettingsTest.java"
        status: pass
    human_judgment: false
  - id: D7
    description: "Runtime real com broker e Mongo: rajada de always-transient abre o CB visivelmente na demo e o bulkhead recusa o excesso sob carga, tudo terminando em RETRYING"
    requirement: RES-01
    verification: []
    human_judgment: true
    rationale: "Comportamento com RabbitMQ/Mongo reais e tempos da demo fica para o smoke/IT do plano 02-07; aqui só há testes sem Docker"

duration: 12min
completed: 2026-10-15
status: complete
---

# Phase 02 Plan 05: Circuit breaker e bulkhead por canal Summary

**Cada entrega passa por bulkhead de semáforo → circuit breaker → adapter do canal (Resilience4j 2.4.0 programático, sem AOP); recusas viram RETRYING CIRCUIT_OPEN/BULKHEAD_FULL no próximo nível, nunca FAILED, e o worker não sobe com instância faltando ou CB aberto por mais tempo que o 1º nível**

## Performance

- **Duration:** 12 min
- **Started:** 2026-10-15T23:28:04Z
- **Completed:** 2026-10-15T23:40:00Z
- **Tasks:** 2
- **Files modified:** 14

## Accomplishments
- `ChannelGateway` com `Bulkhead.decorateRunnable(bh, CircuitBreaker.decorateRunnable(cb, adapter))` por canal; instâncias `email`/`sms`/`webhook` resolvidas uma vez por `registry.find(...)` (fatia tracer ponta a ponta no worker)
- CB aberto vira tentativa CIRCUIT_OPEN transitória com `durationMs` perto de 0 e desce para `email.retry.5s` sem chamar o adapter
- CB conta só `TransientDeliveryException` (`record-exceptions` + `NonTransientFailurePredicate` no ignore); 10 transitórias abrem, 10 permanentes ou desconhecidas não
- Bulkhead por semáforo 8 < 10 consumidores e espera 0: o excesso é recusado na hora
- Configuração por ENV e por canal (D-08), profile demo com 500ms aberto, validação de startup do tempo aberto contra o 1º nível
- Suíte do worker verde: 284 testes, sem Docker; `./mvnw -q test` do reactor também verde

## Task Commits

1. **Task 1: CB aberto → CIRCUIT_OPEN transitória ponta a ponta (tracer)** — RED `860ef95` (test), GREEN `dab66f0` (feat)
2. **Task 2: bulkhead, contagem do CB e validação de configuração** — RED `37a87dd` (test), GREEN `219f8ac` (feat)

**Plan metadata:** commit final de docs (este SUMMARY + STATE/ROADMAP/REQUIREMENTS)

## TDD Gate Compliance

| Tarefa | RED | GREEN | REFACTOR |
|--------|-----|-------|----------|
| 1 | `860ef95` (RED_EVIDENCE_OK: `DeliveryFlowTest#openCircuitBreakerTurnsTheDeliveryIntoACircuitOpenRetryWithoutCallingTheAdapter` esperava RETRYING, veio SENT) | `dab66f0` | não precisou |
| 2 | `37a87dd` (RED_EVIDENCE_OK: `ChannelGatewayTest#openWaitLongerThanTheFirstRetryLevelRefusesToStart` esperava `IllegalStateException`, o construtor voltou normal; também `ResilienceSettingsTest#demoProfileOpensForHalfOfItsOneSecondFirstRetryLevel` esperava 500, veio 2500) | `219f8ac` | não precisou |

O commit RED da Task 1 leva esqueletos só de compilação (gateway chamando o adapter direto, predicate que não ignora nada, processor já recebendo o gateway), no mesmo molde do 02-02/02-04. No RED da Task 2, os testes de contagem do CB, bulkhead e classificador já passavam, porque a Task 1 os implementou; só a validação do tempo aberto e o profile demo falharam. A evidência RED foi convertida dos relatórios do surefire para TAP e validada com `check tdd-red-evidence`. Todas as falhas foram de asserção, nenhuma de compilação ou carga.

Tracer gate (Task 1): modo interativo, `human_verify_mode` end-of-phase, `<verify>` só automatizado. O verify foi reexecutado (`./mvnw -q install -DskipTests && ./mvnw -q -pl notify-worker test -Dtest=DeliveryFlowTest,DeliveryProcessorTest`, saída 0) antes da expansão.

## Files Created/Modified
- `pom.xml`: import de `io.github.resilience4j:resilience4j-bom:2.4.0`
- `notify-worker/pom.xml`: `resilience4j-spring-boot3` sem versão; sem starter de AOP
- `notify-worker/.../resilience/ChannelGateway.java`: bulkhead → CB → adapter, `indexByChannel` vindo do processor, `find(...)` com falha ao subir, validação do tempo aberto, javadoc do "giro" de recusas
- `notify-worker/.../resilience/NonTransientFailurePredicate.java`: `!(t instanceof TransientDeliveryException)`
- `notify-worker/.../failure/FailureClassifier.java`: linhas CIRCUIT_OPEN e BULKHEAD_FULL antes de `DeliveryFailureException`
- `notify-worker/.../processing/DeliveryProcessor.java`: construtor com `ChannelGateway`; `gateway.send(...)` no mesmo ponto da medição de `durationMs`
- `notify-worker/src/main/resources/application.yml`: bloco `resilience4j` com placeholders de ENV e comentários pt-BR
- `notify-worker/src/main/resources/application-demo.yml`: `wait-duration-in-open-state: 500ms`
- Testes: `ChannelGatewayTest`, `ResilienceSettingsTest`, `ResilienceFixtures` (novos); `DeliveryFlowTest`, `DeliveryProcessorTest`, `FailureClassifierTest` (ampliados)

## Decisions Made
- Getter real da espera no estado aberto na 2.4.0: `getWaitIntervalFunctionInOpenState()` (`IntervalFunction`, `apply(1)` em ms), confirmado com `javap` no jar. Sem desvio da suposição A10.
- Mensagem de validação: `wait-duration-in-open-state do canal <id> (<ms>ms) maior que o 1º nível de retry (<rótulo>)`.
- O profile demo usa `500ms` literal, como pede o critério do plano. Por isso o `NOTIFY_CB_OPEN_WAIT` não vale nesse profile; o profile de benchmark da Fase 4 (D-08) precisa sobrescrever a chave direto, se rodar junto com `demo`.
- `ResilienceFixtures` no pacote de teste `resilience` concentra a config do YAML para os testes sem Spring.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fixture de teste compartilhada `ResilienceFixtures`**
- **Found during:** Task 1 (RED)
- **Issue:** `DeliveryFlowTest` e `ChannelGatewayTest` precisam de registries com a mesma config do YAML. O plano não lista um arquivo para isso, e repetir a config em cada teste deixaria as cópias divergirem.
- **Fix:** `notify-worker/src/test/java/dev/notifyhub/worker/resilience/ResilienceFixtures.java` (só de teste). O `ResilienceSettingsTest` confere que o YAML real monta os mesmos valores.
- **Files modified:** ResilienceFixtures.java (novo, test)
- **Verification:** suíte do worker verde
- **Committed in:** `860ef95`

---

**Total deviations:** 1 auto-fixed (1 blocking, só de teste)
**Impact on plan:** nenhum efeito em código de produção. Testes extras além do `<behavior>`: instância de bulkhead ausente, tempo aberto checado por canal, falha do adapter propagada sem alteração e isolamento entre canais.

## Issues Encountered
- Uma limpeza de `notify-worker/target/surefire-reports` foi bloqueada pela checagem de segurança do ambiente. Não fazia falta: os relatórios antigos de outras classes, todos verdes, só entraram na contagem do TAP da evidência RED.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- 02-06 pode expor no `/actuator/prometheus` as métricas automáticas do `resilience4j-micrometer` (`resilience4j.circuitbreaker.state`, `.calls`, `.not.permitted.calls`, `resilience4j.bulkhead.available.concurrent.calls`, `.max.allowed.concurrent.calls`). O `micrometer-registry-prometheus` ainda não está no worker.
- 02-07 valida o runtime real: rajada de `always-transient` abrindo o CB e bulkhead recusando com 2 permissões (`NOTIFY_BULKHEAD_MAX_CONCURRENT=2`).
- Fase 4: o benchmark neutraliza o CB por `NOTIFY_CB_MIN_CALLS`/`NOTIFY_CB_FAILURE_RATE` e afrouxa o bulkhead por `NOTIFY_BULKHEAD_MAX_CONCURRENT` (≥ 10), sem violar a invariante do tempo aberto.

---
*Phase: 02-falhas-retry-com-backoff-e-resili-ncia*
*Completed: 2026-10-15*

## Self-Check: PASSED

- Arquivos criados presentes: ChannelGateway.java, NonTransientFailurePredicate.java, ChannelGatewayTest.java, ResilienceFixtures.java, ResilienceSettingsTest.java
- Commits presentes: 860ef95, dab66f0, 37a87dd, 219f8ac
- Critérios de aceite das duas tarefas: todos PASS (grep + `./mvnw -q install -DskipTests && ./mvnw -q -pl notify-worker test` com saída 0, 284 testes)
