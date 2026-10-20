---
phase: 02-falhas-retry-com-backoff-e-resili-ncia
plan: 08
subsystem: worker
tags: [rabbitmq, mongodb, retry, fence, idempotency, overflow, gap-closure]

requires:
  - phase: 02-falhas-retry-com-backoff-e-resili-ncia
    provides: "Escada de retry TTL+DLX (02-02), claim com fence retryCount ≤ h, markRetrying sem retryCount (Armadilha 2), métricas D-15 (02-06)"
provides:
  - "RetryPolicy.decide sem overflow: escada esgotada testada antes do +1 (header 2147483647 → DEAD_LETTERED com cópia TRANSIENT)"
  - "NotificationStateService.advanceRetryLevel: RETRYING(h) passa a retryCount h+1 depois do confirm do token, com fence RETRYING + retryCount = h"
  - "DeliveryProcessor avança o fence entre o confirm e o ack, tolerante a fence perdido e DataAccessException"
  - "SingleNotificationStore: documento único em memória que aplica os filtros e updates reais do worker, para reproduzir reentregas sem Docker"
  - "CR-01 registrado em deferred-items.md (open, target Phase 3) como entrada obrigatória do ADM-01"
affects: [03-reprocessamento-dlq, ADM-01, ADM-02, DOC-03]

actuals:
  tokens: 13783
  tasks: 3
  commits: 5
plan_head_before: 5c71e5fc89512494a675a56c183cbf827dfa0220

tech-stack:
  added: []
  patterns:
    - "Fence avançado depois do confirm: o resultado RETRYING mantém h (Armadilha 2) e um segundo update cercado grava h+1 só depois que o token está na fila de delay"
    - "Store em memória para testes de sequência: filtros/updates convertidos por MongoBson e aplicados num único Document; operador desconhecido lança IllegalArgumentException"

key-files:
  created:
    - notify-worker/src/test/java/dev/notifyhub/worker/processing/SingleNotificationStore.java
  modified:
    - notify-worker/src/main/java/dev/notifyhub/worker/processing/RetryPolicy.java
    - notify-worker/src/main/java/dev/notifyhub/worker/listener/RetryHeaders.java
    - notify-worker/src/main/java/dev/notifyhub/worker/processing/NotificationStateService.java
    - notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java
    - notify-worker/src/test/java/dev/notifyhub/worker/processing/RetryPolicyTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/listener/RetryHeadersTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/processing/NotificationStateServiceTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java
    - .planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/deferred-items.md

key-decisions:
  - "02-08: RetryHeaders sem teto acima da escada; um teto rejeitaria antes do claim e recriaria a classe do CR-01, sem teto o documento termina DEAD_LETTERED com cópia TRANSIENT"
  - "02-08: WR-01 fechado com advanceRetryLevel (RETRYING h → retryCount h+1) logo depois do confirm do token, sem campo novo, sem mudar o filtro do claim nem a tabela de transições; markRetrying continua sem gravar retryCount"
  - "02-08: falha ou fence perdido no avanço só gera log; a original é confirmada e nada vai para a DLQ (o token h+1 já está na fila de delay)"
  - "02-08: CR-01 adiado para a Fase 3 por decisão do usuário; o Javadoc do DeliveryProcessor deixou de prometer recuperação automática"

patterns-established:
  - "Avanço de fence pós-confirm: segundo updateFirst com fence de igualdade no nível, nunca regride um documento que o próprio token já reivindicou"
  - "SingleNotificationStore para cenários de reentrega com o worker real, sem Docker"

requirements-completed: [DLV-04, DLV-05, WRK-03]

coverage:
  - id: D1
    description: "Header notify-retry-count = 2147483647 com falha transitória termina DEAD_LETTERED com exatamente uma tentativa e cópia TRANSIENT na DLQ, sem exceção (WR-02, gap do 02-VERIFICATION)"
    requirement: DLV-05
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/RetryPolicyTest.java#integerMaxValueLevelIsExhaustedWithoutOverflow"
        status: pass
      - kind: integration
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java#integerMaxValueRetryHeaderEndsDeadLetteredWithOneAttemptAndATransientDlqCopy"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java#integerMaxValueLevelIsDeadLetteredWithExactlyOneAttemptAndATransientDlqCopy"
        status: pass
    human_judgment: false
  - id: D2
    description: "Depois do confirm do token h+1, a original reentregue do nível h e cópias repetidas do token h+1 são descartadas sem chamar o adapter (WR-01)"
    requirement: DLV-04
    verification:
      - kind: integration
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java#redeliveredOriginalAfterTheConfirmedTokenIsDiscardedWithoutSendingAgain"
        status: pass
      - kind: integration
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java#secondCopyOfTheSameTokenIsDiscardedOnceTheNextLevelWasConfirmed"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/NotificationStateServiceTest.java#advanceRetryLevelIsOneUpdateFencedByRetryingAndTheSameLevel"
        status: pass
    human_judgment: false
  - id: D3
    description: "Caminhos de retomada preservados: reentrega antes do publish (Armadilha 2), PROCESSING preso com redelivered/lease vencido (WRK-03) e o próprio token h+1"
    requirement: WRK-03
    verification:
      - kind: integration
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java#redeliveredOriginalStillRetriesWhenTheWorkerDiedBeforeTheRetryPublish"
        status: pass
      - kind: integration
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java#stuckProcessingIsStillResumedByARedeliveredCopy"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/NotificationStateServiceTest.java#markRetryingIsOneFencedUpdateThatNeverWritesRetryCount"
        status: pass
    human_judgment: false
  - id: D4
    description: "Falha do Mongo ou fence perdido no avanço nunca reroteia: original confirmada, retry contado, nada na DLQ; republicação falha não avança o fence (D-11, D-17)"
    requirement: DLV-04
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java#storeFailureOnTheAdvanceStillAcksWithoutTouchingTheDlq"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java#failedRepublishNeverAdvancesTheFence"
        status: pass
      - kind: integration
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java#lostFenceOnTheAdvanceStillAcksWithoutTouchingTheDlq"
        status: pass
    human_judgment: false
  - id: D5
    description: "Descarte real da original reentregue quando o worker é derrubado entre o confirm do token h+1 e o ack (kill do processo no ponto exato)"
    requirement: DLV-04
    verification: []
    human_judgment: true
    rationale: "A janela confirm → ack exige derrubar o processo no ponto exato (breakpoint + taskkill); o SingleNotificationStore prova a regra do fence, não o kill real (human-check da Task 2, truth backstop do 02-02)"
  - id: D6
    description: "CR-01 registrado em deferred-items.md (open, target Phase 3) e Javadoc do DeliveryProcessor sem a promessa de recuperação"
    verification:
      - kind: other
        ref: "grep -q 'target: Phase 3' .planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/deferred-items.md && ! grep -q 'continua recuperável' notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java"
        status: pass
    human_judgment: false

duration: 11min
completed: 2026-10-20
status: complete
---

# Phase 2 Plan 08: Fronteira 2147483647 e reentrega depois do confirm (gap closure) Summary

**`RetryPolicy` testa a escada esgotada antes do +1 (header 2147483647 vira DEAD_LETTERED com cópia TRANSIENT) e o documento RETRYING avança para `retryCount` h+1 logo depois do confirm do token, para que a original reentregue seja descartada sem novo envio.**

## Performance

- **Duration:** 11 min
- **Started:** 2026-10-20T23:25:42Z
- **Completed:** 2026-10-20T23:36:37Z
- **Tasks:** 3
- **Files modified:** 11 (1 criado, 10 alterados)

## Accomplishments

- **WR-02 / gap bloqueante do 02-VERIFICATION:** `RetryPolicy.decide` devolve esgotado quando `retryCount >= levels.size()`, antes de somar. Com o header `notify-retry-count` = 2147483647, o worker real (listener, processor, política, estado e publisher) grava DEAD_LETTERED com exatamente uma tentativa (number 1, retryLevel 2147483647, TRANSIENT_FAILURE) e publica a cópia em `email.dlq` com `x-failure-class: TRANSIENT`. Antes, o `int` estourava e `levels.get` lançava fora do tratamento.
- **WR-01:** `NotificationStateService.advanceRetryLevel(id, h, h+1)` grava `retryCount = h+1` no documento RETRYING, com fence `status = RETRYING` + `retryCount = h`. O `DeliveryProcessor` chama o avanço entre o confirm do token e o ack. O filtro do claim (`retryCount ≤ h`) não mudou e agora recusa a original reentregue e a segunda cópia do mesmo token.
- **Caminhos legítimos preservados:** a reentrega antes do publish (Armadilha 2; `markRetrying` continua sem gravar `retryCount`), o PROCESSING preso (WRK-03) e o próprio token h+1 continuam reivindicando. Tudo isso é provado com o worker real sobre o `SingleNotificationStore`.
- **Tolerância:** fence perdido ou `DataAccessException` no avanço só geram log. A original é confirmada, `notify.delivery.retries{level=1}` conta 1 e nada vai para a DLQ.
- **CR-01:** registrado em `deferred-items.md` (open, target Phase 3) como entrada obrigatória do ADM-01. O Javadoc do `DeliveryProcessor` não promete mais recuperação automática.
- Suíte inteira verde e sem Docker: **453 testes** (431 anteriores mais 22 novos): notify-common 75, notify-api 34, notify-worker 344.

## Task Commits

1. **Task 1: header 2147483647 termina DEAD_LETTERED (WR-02)**
   - `2191d8f` test: RED com `ArrayIndexOutOfBoundsException` vindo de `RetryPolicy.decide` (RED_EVIDENCE_OK)
   - `ef57184` fix: GREEN
2. **Task 2: reentrega do nível h depois do token confirmado é descartada (WR-01)**
   - `cd50847` test: RED, 11 falhas; o alvo para em `retryCount` 0 e a cascata mostra o adapter chamado 2 vezes (RED_EVIDENCE_OK)
   - `fc724ee` fix: GREEN
3. **Task 3: orquestração no DeliveryProcessorTest, CR-01 para a Fase 3, suíte verde**
   - `587d5bd` test

**Plan metadata:** commit `docs(02-08)` deste SUMMARY

## Files Created/Modified

- `notify-worker/.../processing/RetryPolicy.java`: esgotado testado antes do +1; o Javadoc cita a fronteira 2147483647.
- `notify-worker/.../listener/RetryHeaders.java`: só Javadoc, com a decisão "sem teto" e o motivo.
- `notify-worker/.../processing/NotificationStateService.java`: novo `advanceRetryLevel`. O Javadoc da classe, do claim (janela residual at-least-once) e do `markRetrying` foi atualizado.
- `notify-worker/.../processing/DeliveryProcessor.java`: novo `advanceRetryFence` (try/catch de `DataAccessException`) no ramo `outcome.ok()`. O Javadoc da classe e do `onTransientFailure` foi atualizado, e o item do CR-01 agora é honesto.
- `notify-worker/src/test/.../SingleNotificationStore.java`: documento único em memória. `matches` cobre igualdade, `$lte`, `$lt`, `$in` e `$or`; o update cobre `$set`, `$unset`, `$inc` e `$push`/`$each`. Tem `install(MongoTemplate)` com `doAnswer` e acessores.
- `RetryPolicyTest` (+3), `RetryHeadersTest` (+1), `NotificationStateServiceTest` (+6: avanço e tabela de casamento do claim), `DeliveryProcessorTest` (+5) e `DeliveryFlowTest` (+7, além de 6 testes existentes ajustados ao avanço: `captureRetryResult` exige 2 `updateFirst` e a caminhada always-transient passa a ver 7).
- `deferred-items.md`: entrada CR-01.

## Decisions Made

- **Sem teto no `RetryHeaders`.** Um teto rejeitaria a mensagem antes do claim e deixaria o documento parado (classe CR-01). Sem teto, o documento chega a um estado terminal visível e reprocessável. A decisão está fixada no Javadoc e em `integerMaxValueIsAcceptedWithoutACeiling`.
- **Fence avançado depois do confirm, sem campo novo.** O review sugeria um `nextRetryPublished`. O plano preferiu reaproveitar `retryCount` com um segundo update cercado, sem mudar o contrato compartilhado (`notify-common`), o filtro do claim nem a tabela de transições.
- **O avanço nunca muda o roteamento.** Rejeitar a original com o token h+1 já confirmado deixaria a notificação na DLQ e na escada ao mesmo tempo.

## Deviations from Plan

### Ajustes menores (sem regra de desvio acionada)

**1. Testes unitários do `advanceRetryLevel` entraram no commit GREEN da Task 2, não no RED**
- **Found during:** Task 2
- **Issue:** os dois testes de `NotificationStateServiceTest` chamam um método que ainda não existia. No RED eles impediriam a compilação da suíte, e isso é INVALID_RED.
- **Fix:** o RED (`cd50847`) levou o helper, os cenários do `DeliveryFlowTest` e a tabela de casamento do claim, que compilam com a API existente. Os testes do método novo foram junto com o método em `fc724ee`.
- **Verification:** os dois passam, e o RED da Task 2 teve RED_EVIDENCE_OK com o cenário-alvo falhando numa asserção.

**2. Javadoc do `markRetrying` atualizado (só texto)**
- **Issue:** o Javadoc dizia que "quem grava h+1 é o claim do próximo token", o que deixou de ser a única fonte.
- **Fix:** passou a citar o `advanceRetryLevel` (depois do confirm) ou o claim. O comportamento não mudou, e `markRetryingIsOneFencedUpdateThatNeverWritesRetryCount` passa sem alteração.

**3. Teste extra `confirmedRetryFromLevelOneAdvancesTheFenceToLevelTwo`**
- O plano pedia "o equivalente no nível 1" dentro do teste do nível 0. Ficou como teste separado, com o mesmo InOrder.

---

**Total deviations:** 0 auto-fixes por regra; 3 ajustes menores de organização e documentação.
**Impact on plan:** nenhum no escopo; todos os critérios de aceite passaram.

## TDD Gate Compliance

As tarefas são `tdd="true"` num plano `type: execute`. Task 1 e Task 2 seguiram RED → GREEN, com RED_EVIDENCE_OK registrado via `check tdd-red-evidence`; o JUnit foi convertido para TAP a partir dos relatórios surefire. Os commits GREEN usam o tipo `fix(02-08)` em vez de `feat(02-08)`, porque são correções de bugs apontados no review (WR-02, WR-01), segundo a tabela de tipos de commit. Um validador que procure só `^feat\(02-08\)` não vai encontrar o GREEN: os GREEN são `ef57184` e `fc724ee`. Não houve REFACTOR.

## Issues Encountered

- No Git Bash, o heredoc com aspas simples dentro de um script Python quebrou o parser. A solução foi gravar o script de edição no scratchpad e rodá-lo de lá.

## Known Stubs

Nenhum.

## Threat Flags

Nenhuma superfície nova fora do `<threat_model>`. O avanço é mais um `updateFirst` worker→MongoDB, coberto por T-02-31 e T-02-32.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- O gap bloqueante do 02-VERIFICATION (overflow no 2147483647) e o WR-01 estão fechados. A fase está pronta para nova verificação (`/gsd-verify-work 02`).
- Fica pendente o human-check opcional de fim de fase da Task 2: derrubar o worker entre o confirm e o ack. Ele está registrado em `.planning/WINDOWS.md` como `unrun-verify`.
- A Fase 3 precisa consumir o CR-01 do `deferred-items.md`: o reprocessamento tem de aceitar PENDING, RETRYING e PROCESSING com a mensagem na DLQ, ou é preciso criar um sweeper (WR-08).
- O GET de um documento RETRYING com token confirmado agora mostra `retryCount` = h+1, o nível do token que espera na fila de delay.

## Self-Check: PASSED

- FOUND: `notify-worker/src/test/java/dev/notifyhub/worker/processing/SingleNotificationStore.java`
- FOUND: commits `2191d8f`, `ef57184`, `cd50847`, `fc724ee`, `587d5bd`
- Plan-level verification: os três comandos `./mvnw` do `<verification>` saíram com 0 (Task 1, Task 2 e a suíte inteira com 453 testes)

---
*Phase: 02-falhas-retry-com-backoff-e-resili-ncia*
*Completed: 2026-10-20*
