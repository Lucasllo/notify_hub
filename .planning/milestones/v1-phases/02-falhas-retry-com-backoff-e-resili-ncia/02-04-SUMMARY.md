---
phase: 02-falhas-retry-com-backoff-e-resili-ncia
plan: 04
subsystem: worker
tags: [magic-recipients, simulation, retry, dlq, deterministic-failures, attempt-context]

requires:
  - phase: 02-falhas-retry-com-backoff-e-resili-ncia
    provides: "02-02: escada transitória (RETRYING → próximo nível → DEAD_LETTERED), process(..., redelivered), attemptNumber = attemptCount + 1 depois do claim"
  - phase: 02-falhas-retry-com-backoff-e-resili-ncia
    provides: "02-01: FailureClassifier, PermanentDeliveryException, ErrorCode.SIMULATED_PERMANENT, cópia na DLQ com x-failure-class"
provides:
  - "AttemptContext(retryCount, attemptNumber) validado, passado ao adapter"
  - "ChannelAdapter.send(String, RenderedContent, AttemptContext)"
  - "MagicRecipients.parse(Channel, String) → Optional<MagicBehavior(Kind, failures)>; FAIL_UNTIL_REPROCESS_ATTEMPTS = 4"
  - "SimulatedChannelAdapter com os quatro roteiros mágicos depois da latência; construtor de 4 argumentos (magicRecipients)"
  - "SimulationProperties.magicRecipients() / notify.simulation.magic-recipients (ENV NOTIFY_SIMULATION_MAGIC_RECIPIENTS, padrão true)"
  - "Contrato de destinatários mágicos para Fase 3/4: <regra>@fail.test, http(s)://fail.test/<regra>, +550000000001/002/1N3/004"
affects: [02-05, 02-07, fase-3-reprocessamento, fase-4-k6, readme]

actuals:
  tokens: 13467
  tasks: 2
  commits: 4
plan_head_before: d8aaab22368fc315428bdd02b571f543c5ef0f6d

tech-stack:
  added: []
  patterns:
    - "Falha determinística injetada dentro do adapter, depois da latência, com as exceções de uma falha real (passa pelo CB/bulkhead do 02-05)"
    - "Parser puro de destinatário reservado com casamento exato (domínio/host/código) e tabela de parecidos no teste"
    - "Roteiro sem estado: fail-N-then-ok conta retryCount; fail-until-reprocess conta attemptNumber"

key-files:
  created:
    - notify-worker/src/main/java/dev/notifyhub/worker/channel/AttemptContext.java
    - notify-worker/src/main/java/dev/notifyhub/worker/channel/MagicRecipients.java
    - notify-worker/src/test/java/dev/notifyhub/worker/channel/MagicRecipientsTest.java
  modified:
    - notify-worker/src/main/java/dev/notifyhub/worker/channel/ChannelAdapter.java
    - notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulatedChannelAdapter.java
    - notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulationProperties.java
    - notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulationConfiguration.java
    - notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java
    - notify-worker/src/main/resources/application.yml
    - notify-worker/src/test/java/dev/notifyhub/worker/channel/SimulatedChannelAdapterTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java

key-decisions:
  - "MagicBehavior.failures de fail-until-reprocess vale FAIL_UNTIL_REPROCESS_ATTEMPTS (4); o adapter compara attemptNumber com a constante, não com o campo"
  - "Webhook mágico: regra = primeiro segmento do path decodificado por URI.getPath; https://fail.test sem path ou com path vazio não é mágico"
  - "SMS mágico exige exatamente 13 caracteres e prefixo +550000000; códigos fora de 001/002/004/1N3 (ex.: 000, 005, 103, 124) seguem o fluxo normal"
  - "Com magic-recipients=false o adapter nem consulta o parser; o destinatário reservado segue latência + failure-rate como qualquer outro"

patterns-established:
  - "Recursos de demo atrás de flag em notify.simulation.* com ENV e padrão ligado"
  - "Mensagens de exceção mágica citam só o canal, nunca o destinatário (T-02-19)"

requirements-completed: [DLV-02]

coverage:
  - id: D1
    description: "fail-2-then-ok@fail.test falha de forma transitória em h=0 (email.retry.5s) e h=1 (email.retry.25s) mesmo com failure-rate 0.0 e termina SENT na 3ª tentativa em h=2, sem publicação"
    requirement: DLV-02
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java (failTwiceThenOkFailsTransientlyOnTheFirstDeliveryEvenWithFailureRateZero, failTwiceThenOkFailsAgainOnTheFirstRetryLevel, failTwiceThenOkIsDeliveredOnTheThirdAttemptAtLevelTwo)"
        status: pass
    human_judgment: false
  - id: D2
    description: "DeliveryProcessor monta AttemptContext(h, attemptCount + 1) depois do claim e passa ao adapter (ex.: AttemptContext(2, 4) e AttemptContext(0, 1)); AttemptContext rejeita retryCount negativo e attemptNumber < 1"
    requirement: DLV-02
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java (attemptNumberFollowsAttemptCountAndRecordsTheRetryLevel, firstDeliveryPassesLevelZeroAndAttemptOneToTheAdapter)"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/channel/SimulatedChannelAdapterTest.java#attemptContextRejectsNegativeLevelAndAttemptBelowOne"
        status: pass
    human_judgment: false
  - id: D3
    description: "MagicRecipients reconhece só os padrões reservados exatos nos três canais (tabela de mágicos e de parecidos como fail.test.com.br, fail.test.evil.com, N=0, zero à esquerda, códigos SMS desconhecidos, nulo e vazio) e todo mágico passa em RecipientRules.isValid"
    requirement: DLV-02
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/channel/MagicRecipientsTest.java"
        status: pass
    human_judgment: false
  - id: D4
    description: "Adapter executa os roteiros depois da latência com as exceções reais: always-transient ignora failure-rate, always-permanent lança PermanentDeliveryException(SIMULATED_PERMANENT), fail-until-reprocess falha até a 4ª tentativa e sucede na 5ª, SMS e webhook seguem os mesmos roteiros"
    requirement: DLV-02
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/channel/SimulatedChannelAdapterTest.java (magicFailureIsThrownOnlyAfterTheSimulatedLatency, alwaysTransientFailsEvenWithFailureRateZero, alwaysPermanentThrowsAPermanentSimulatedFailure, failUntilReprocessFailsUpToTheFourthAttemptWhateverTheLevel, failUntilReprocessSucceedsOnTheFifthAttemptAfterAReprocessResetTheLevel, smsAndWebhookMagicRecipientsFollowTheSameScripts, failOneThenOkFailsTransientlyOnTheFirstLevel, failOneThenOkSucceedsOnceRetryCountReachesN)"
        status: pass
    human_judgment: false
  - id: D5
    description: "always-permanent termina FAILED com 1 tentativa SIMULATED_PERMANENT e cópia PERMANENT na DLQ; always-transient em h=3 termina DEAD_LETTERED (4ª tentativa) com cópia TRANSIENT"
    requirement: DLV-02
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java (alwaysPermanentEndsFailedOnTheFirstAttemptWithAPermanentDlqCopy, alwaysTransientOnTheLastLevelEndsDeadLetteredWithATransientDlqCopy)"
        status: pass
    human_judgment: false
  - id: D6
    description: "notify.simulation.magic-recipients padrão true; =false desliga o reconhecimento nos três adapters (o destinatário reservado segue só failure-rate)"
    requirement: DLV-02
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/channel/SimulatedChannelAdapterTest.java (magicRecipientsAreEnabledByDefault, magicRecipientsPropertyTurnsTheFeatureOffForEveryAdapter, disabledMagicRecipientsFallBackToTheRandomFailureOnly)"
        status: pass
    human_judgment: false
  - id: D7
    description: "Comportamento real com broker e Mongo (always-transient até DEAD_LETTERED com 4 tentativas, fail-2-then-ok até SENT) nos TTLs reais"
    requirement: DLV-02
    verification: []
    human_judgment: true
    rationale: "Runtime real fica para o smoke/IT do plano 02-07; aqui só há testes sem Docker"

duration: 10min
completed: 2026-10-15
status: complete
---

# Phase 02 Plan 04: Destinatários mágicos Summary

**Falhas determinísticas por destinatário reservado (`<regra>@fail.test`, `https://fail.test/<regra>`, `+550000000001/002/1N3/004`) executadas dentro do `SimulatedChannelAdapter` depois da latência, com as exceções de uma falha real e decididas só pelo `AttemptContext(retryCount, attemptNumber)`**

## Performance

- **Duration:** 10 min
- **Started:** 2026-10-15T23:13:41Z
- **Completed:** 2026-10-15T23:24:00Z
- **Tasks:** 2
- **Files modified:** 12

## Accomplishments
- `fail-2-then-ok@fail.test` falha nos níveis 0 e 1 e é entregue no nível 2 (fatia tracer ponta a ponta no worker), sem estado guardado
- Quatro roteiros (sempre transitória, sempre permanente, falha N vezes, até reprocessar) nos três canais, só nos padrões reservados exatos
- Nova assinatura `ChannelAdapter.send(recipient, content, context)`; o processor passa o mesmo `attemptNumber` gravado no `AttemptEntry`
- Flag `notify.simulation.magic-recipients` (ENV `NOTIFY_SIMULATION_MAGIC_RECIPIENTS`) para desligar o recurso fora da demo
- Suíte do worker verde: 253 testes, sem Docker

## Task Commits

1. **Task 1: fail-2-then-ok ponta a ponta (tracer)** — RED `c5f7a35` (test), GREEN `b8872af` (feat)
2. **Task 2: todos os roteiros, códigos SMS e flag** — RED `c4a5350` (test), GREEN `419b308` (feat)

**Plan metadata:** commit final de docs (este SUMMARY + STATE/ROADMAP/REQUIREMENTS)

## TDD Gate Compliance

| Tarefa | RED | GREEN | REFACTOR |
|--------|-----|-------|----------|
| 1 | `c5f7a35` (RED_EVIDENCE_OK: `DeliveryFlowTest#failTwiceThenOkFailsTransientlyOnTheFirstDeliveryEvenWithFailureRateZero` esperava RETRYING, veio SENT) | `b8872af` | não precisou |
| 2 | `c4a5350` (RED_EVIDENCE_OK: `DeliveryFlowTest#alwaysPermanentEndsFailedOnTheFirstAttemptWithAPermanentDlqCopy` esperava FAILED, veio SENT) | `419b308` | não precisou |

Os commits RED levam esqueletos só de compilação (record sem validação, parser devolvendo vazio, processor passando contexto nulo, construtor de 4 argumentos ignorando a flag), no mesmo molde do 02-02. A evidência RED foi convertida dos relatórios do surefire para TAP e validada com `check tdd-red-evidence`; as falhas foram de asserção, nenhuma de compilação ou carga.

## Files Created/Modified
- `notify-worker/.../channel/AttemptContext.java`: record `(retryCount, attemptNumber)` com validação pt-BR
- `notify-worker/.../channel/MagicRecipients.java`: parser puro por canal, enum `Kind`, record `MagicBehavior`, javadoc com a tabela completa e as notas do D-02/D-03
- `notify-worker/.../channel/ChannelAdapter.java`: assinatura com `AttemptContext`
- `notify-worker/.../channel/SimulatedChannelAdapter.java`: roteiros mágicos depois da latência; construtor de 4 argumentos
- `notify-worker/.../channel/SimulationProperties.java`: componente `magicRecipients` (padrão true)
- `notify-worker/.../channel/SimulationConfiguration.java`: repassa a flag aos três adapters
- `notify-worker/.../processing/DeliveryProcessor.java`: `new AttemptContext(retryCount, attemptNumber)` depois do claim
- `notify-worker/src/main/resources/application.yml`: `magic-recipients` com ENV e comentários da convenção e do D-08
- Testes: `MagicRecipientsTest` (novo), `SimulatedChannelAdapterTest`, `DeliveryProcessorTest`, `DeliveryFlowTest`

## Decisions Made
- `MagicBehavior.failures` de `fail-until-reprocess` vale 4 (`FAIL_UNTIL_REPROCESS_ATTEMPTS`), para o campo ter sentido em todos os roteiros; o adapter compara com a constante.
- Webhook: a regra é o primeiro segmento de `URI.getPath()`; sem path, path `/` ou primeiro segmento diferente (ex.: `/x/always-transient`) não é mágico.
- SMS: exatamente 13 caracteres; só `001`, `002`, `004` e `1N3` (N de 1 a 9) são mágicos.
- Com a flag desligada o adapter nem consulta o parser.

## Deviations from Plan

None - plan executed exactly as written. (Testes extras além do `<behavior>`: validação do `AttemptContext`, contexto `(0, 1)` no primeiro envio, isolamento entre canais e parse que nunca lança com entrada estranha.)

## Issues Encountered
- O javadoc de `RecipientRules` (notify-common) ainda cita `+5500000000001` (14 caracteres) como exemplo de SMS mágico; o contrato fechado aqui é `+550000000001`. Só documentação, fora do escopo (o plano só altera `notify-worker`); registrado em `deferred-items.md`.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- 02-05 pode envolver `ChannelAdapter.send(recipient, content, context)` com bulkhead e circuit breaker; a falha mágica já sai de dentro do adapter, então `always-transient` em rajada abre o CB.
- 02-07 valida o runtime real (always-transient → DEAD_LETTERED com 4 tentativas, fail-2-then-ok → SENT).
- Fase 3 usa `fail-until-reprocess` para retry → DLQ → reprocess → SENT sem trocar configuração.

---
*Phase: 02-falhas-retry-com-backoff-e-resili-ncia*
*Completed: 2026-10-15*

## Self-Check: PASSED

- Arquivos criados presentes: AttemptContext.java, MagicRecipients.java, MagicRecipientsTest.java
- Commits presentes: c5f7a35, b8872af, c4a5350, 419b308
- Critérios de aceite das duas tarefas: todos PASS (grep + `./mvnw -q -pl notify-worker test` com saída 0, 253 testes)
