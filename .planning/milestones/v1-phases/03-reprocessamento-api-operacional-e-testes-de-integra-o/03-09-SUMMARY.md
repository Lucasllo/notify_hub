---
phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o
plan: 09
subsystem: api
tags: [idempotency, publish-confirm, mongodb, fence, gap-closure, tdd, testcontainers]

# Dependency graph
requires:
  - phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o
    provides: "03-08: varredura de PENDING sem confirm e UnconfirmedPublishRecoveryIT; 03-10: NotificationRepublisher.wasClaimedByWorker e releitura em publishOrMarkFailed"
provides:
  - "NotificationRepublisher.republish com fence escolhido pelo status lido: PUBLISH_FAILED → PENDING; PENDING → toque de updatedAt cercado pelo updatedAt lido; outro status → FENCE_LOST sem escrita"
  - "RepublishOutcome.Kind.FENCE_LOST e o factory fenceLost() (renome do desfecho de fence perdido)"
  - "NotificationService.replay: laço de até 3 rodadas em que todo 202 tem confirm próprio ou evidência de claim; fallback sem fence pelo publishOrMarkFailed"
  - "NotificationService.awaitPublishWindow: o replay de PENDING recente espera a evidência (polling de 100ms até updatedAt + 2× confirm-timeout) (WR-02)"
  - "IT replayOfAnUnconfirmedPendingRepublishesInsteadOfAnswering202"
affects: [verificacao-fase-03, fase-04-demo, fase-04-readme]

# Actuals (#2632) — chars/4 over the realized diff
actuals:
  tokens: 10147
  tasks: 2
  commits: 5
plan_head_before: bc2f48af62e81c66e4d05448fc1ac648885470c9

tech-stack:
  added: []
  patterns:
    - "Fence escolhido pelo status lido: um único método de republish serve PUBLISH_FAILED (transição) e PENDING (toque de updatedAt cercado pelo valor lido do Mongo)"
    - "Replay idempotente como laço limitado sobre o documento corrente: claim → 202; janela aberta → espera; senão republish cercado; fence perdido → relê"
    - "Espera de evidência por polling curto com TimeUnit.sleep, limitada por um prazo derivado de updatedAt, restaurando o flag de interrupção"

key-files:
  created: []
  modified:
    - notify-api/src/main/java/dev/notifyhub/api/application/NotificationRepublisher.java
    - notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java
    - notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java
    - notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java
    - notify-it/src/test/java/dev/notifyhub/it/UnconfirmedPublishRecoveryIT.java

key-decisions:
  - "03-09: todo 202 de replay com Idempotency-Key se apoia num confirm próprio do broker ou em wasClaimedByWorker; PENDING sozinho nunca basta"
  - "03-09: o replay de PENDING com a janela de publicação vencida (updatedAt + 2× confirm-timeout) republica com fence status PENDING + updatedAt lido, só tocando updatedAt; o corte é menor que o da varredura (≥ 3×) porque a ação é um republish cercado"
  - "03-09: dentro da janela, o replay espera (polling de 100ms) o documento sair de PENDING ou ter o updatedAt trocado; no fim da janela republica com fence (WR-02)"
  - "03-09: laço limitado a 3 rodadas; esgotado, publica sem fence e responde pelo próprio confirm (cópia extra descartada pelo claim exclusivo do worker)"
  - "03-09: PENDING sem updatedAt tem a janela tratada como vencida (defensivo)"

patterns-established:
  - "Desfecho FENCE_LOST significa 'o documento saiu do estado lido; nada foi escrito nem publicado', para qualquer fence"

requirements-completed: [API-03, ADM-03]

coverage:
  - id: D1
    description: "Replay com a mesma Idempotency-Key de um PENDING órfão (janela vencida, sem mensagem) responde 202 Idempotent-Replayed PENDING e a notificação chega a SENT, com um único documento, contra RabbitMQ e MongoDB reais (tracer)"
    requirement: "API-03"
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/UnconfirmedPublishRecoveryIT.java#replayOfAnUnconfirmedPendingRepublishesInsteadOfAnswering202"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java#replayOfAnUnconfirmedPendingRepublishesBeforeAnswering202"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java#replayOfAnUnconfirmedPendingWhosePublishIsNackedIs503"
        status: pass
    human_judgment: false
  - id: D2
    description: "republish cerca PENDING por _id + status PENDING + updatedAt lido e só faz $set de updatedAt; fence perdido ou documento reivindicado → FENCE_LOST sem publish (e sem escrita no reivindicado); lote continua só PUBLISH_FAILED"
    requirement: "ADM-03"
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java#republishOfAnUnconfirmedPendingFencesOnStatusAndTheReadUpdatedAt"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java#republishOfAPendingWhoseFenceIsLostDoesNotPublish"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java#republishOfAClaimedDocumentWritesNothing"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java#batchSearchesPublishFailedOldestFirstAndStopsAtTheFirstFailureWith503Partial"
        status: pass
    human_judgment: false
  - id: D3
    description: "Fence perdido relê e decide de novo (claim → 202 com o status atual); três fences perdidos seguidos → um único publish sem fence respondido pelo próprio confirm"
    requirement: "API-03"
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java#replayOfAnUnconfirmedPendingWhoseFenceIsLostRereadsAndAnswersTheClaimedStatus"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java#replayThatKeepsLosingTheFencePublishesWithoutFenceAndAnswersWithItsOwnConfirm"
        status: pass
    human_judgment: false
  - id: D4
    description: "WR-02: replay de PENDING recente espera a evidência; vencedor falhou → 503 com Retry-After (nunca 202); reivindicado → 202 com o status atual sem publish; ainda PENDING no fim da janela → republish cercado; 422 antes de qualquer releitura"
    requirement: "API-03"
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java#replayOfAFreshPendingWhoseOriginalPublishFailsNeverAnswers202"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java#replayOfAFreshPendingClaimedByTheWorkerAnswers202WithTheCurrentStatusWithoutPublishing"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java#replayOfAFreshPendingStillUnconfirmedAtTheEndOfTheWindowRepublishes"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java#duplicateKeyWithADifferentHashIs422WithoutEchoingKeyOrOriginalIdAndWithoutPublishing"
        status: pass
    human_judgment: false
  - id: D5
    description: "Sem regressão: corrida de 8 POSTs com a mesma chave nova (8 × 202, 1 documento, 1 envio), D-08 e varredura, com a suíte inteira (unitários e 35 ITs) verde"
    verification:
      - kind: integration
        ref: "./mvnw -q verify (IdempotencyIT#concurrentPostsWithTheSameNewKeyCreateExactlyOneNotification, UnroutablePublishIT, UnconfirmedPublishRecoveryIT ×2)"
        status: pass
      - kind: unit
        ref: "./mvnw -q -pl notify-common install && ./mvnw -q -pl notify-api test (174 testes)"
        status: pass
    human_judgment: false

duration: 21min
completed: 2026-11-01
status: complete
---

# Phase 3 Plan 09: Replay idempotente só responde 202 com evidência Summary

**O replay com a mesma `Idempotency-Key` deixou de responder 202 para um PENDING que nunca chegou ao broker. Com a janela de publicação (`updatedAt` + 2× `confirm-timeout`) vencida, ele republica com fence `status` = PENDING + `updatedAt` lido, e a notificação chega a SENT. Dentro da janela, ele espera a evidência (claim do worker, PUBLISH_FAILED ou fim da janela) antes de responder (WR-02). Todo 202 de replay agora tem confirm próprio ou `wasClaimedByWorker`.**

## Performance

- **Duration:** 21 min
- **Started:** 2026-11-01T00:30:05Z
- **Completed:** 2026-11-01T00:51:07Z
- **Tasks:** 2 (1 tracer + 1 auto, ambos TDD)
- **Files modified:** 5 (2 de produção, 3 de teste)

## Accomplishments

- **Gap 1, parte "202 enganoso" (CR-01 item 2, T-03-37).** `NotificationRepublisher.republish` escolhe o fence pelo status lido. Para PENDING, faz um toque de `updatedAt` cercado por `_id` + `status` PENDING + `updatedAt` lido do Mongo, sem mudar o status, e depois o publish com confirm. O replay de um PENDING órfão republica e a notificação chega a SENT, provado contra RabbitMQ e MongoDB reais.
- **WR-02 (mesma raiz).** O perdedor de uma corrida com a mesma chave nova espera, com polling de 100ms e no máximo 2× `confirm-timeout`, o documento sair de PENDING ou ter o `updatedAt` trocado. Se o worker reivindicou, responde 202 com o status atual (D-07). Se o vencedor falhou, segue o D-08 (202 ou 503). Se nada mudou, republica com fence. Ele nunca responde 202 antes de o vencedor confirmar.
- **Laço limitado e serviço único (D-08, ADM-03, T-03-38).** O antigo `replayPublishFailed` foi fundido no laço de até 3 rodadas. O fence perdido de um PUBLISH_FAILED agora relê e decide de novo, em vez de responder 202 direto. Esgotado o laço, o replay publica sem fence e responde pelo próprio confirm. Uma cópia extra é descartada pelo claim exclusivo do worker. O lote `republish-failed` continua buscando só PUBLISH_FAILED.

## Task Commits

0. **Preparação (refactor):** `38a6123`. Renome `NOT_PUBLISH_FAILED` → `FENCE_LOST` e `notPublishFailed()` → `fenceLost()`, sem mudança de comportamento (ver Deviations).
1. **Task 1 (tracer): replay de PENDING sem confirm republica com fence**
   - RED: `bfbb829` (test). 34 testes, 7 falhas em asserção, exatamente os 7 alvos. No IT, o 202 `Idempotent-Replayed` passou e o `awaitStatus(SENT)` estourou 15s com o documento ainda PENDING e o `updatedAt` original (nada publicado).
   - GREEN: `de7931e` (feat). Fence por status lido no `republish`, e `replay`, `replayed`, `publishWindowEnd` e `reread` no `NotificationService`, com Javadoc.
2. **Task 2: WR-02, espera da evidência dentro da janela**
   - RED: `4001ffb` (test). 21 testes, 3 falhas em asserção, exatamente os 3 alvos (202 PENDING imediato).
   - GREEN: `96181a1` (feat). `awaitPublishWindow`, `REPLAY_POLL_INTERVAL` e o ramo do laço.

Não houve REFACTOR depois dos GREEN.

## Files Created/Modified

- `notify-api/.../application/NotificationRepublisher.java`: `FENCE_LOST`/`fenceLost()`, fence por status lido em `republish` e Javadoc da classe e do método.
- `notify-api/.../application/NotificationService.java`: constantes `MAX_REPLAY_ROUNDS`, `PUBLISH_WINDOW_FACTOR` e `REPLAY_POLL_INTERVAL`, mais os métodos `replay`, `awaitPublishWindow`, `publishWindowEnd`, `reread` e `replayed`. Inclui o Javadoc da classe e de `create`.
- `notify-api/src/test/.../NotificationRepublisherTest.java`: fence PENDING + `updatedAt`, fence perdido e documento reivindicado sem escrita. O `lostFenceDoesNotPublish` confere `FENCE_LOST`.
- `notify-api/src/test/.../NotificationServiceTest.java`: janela vencida (4 cenários), PENDING recente (3 cenários) e 422 sem `findById`.
- `notify-it/.../UnconfirmedPublishRecoveryIT.java`: `replayOfAnUnconfirmedPendingRepublishesInsteadOfAnswering202` e o helper `countWithKey`.

## Decisions Made

- O resultado do laço usa `current` (o último documento lido) para id e `createdAt`. São imutáveis, então o resultado é o mesmo de `existing`.
- O log WARN do laço esgotado cita só o id e a quantidade de rodadas. Os demais logs do replay citam só id e status (T-03-40).
- PENDING sem `updatedAt` (não acontece com `newPending`) tem a janela tratada como vencida e segue o republish cercado por `updatedAt` nulo. É o lado seguro.

## Deviations from Plan

### Ajustes de processo (sem mudança de escopo)

**1. [Rule 3 - Blocking] Renome em commit `refactor` antes do RED**
- **Found during:** Task 1 (RED)
- **Issue:** os testes do `<behavior>` usam `Kind.FENCE_LOST`. Escritos antes do renome, eles falhariam por compilação, o que conta como INVALID_RED (tdd.md #3770) e não autoriza o GREEN.
- **Fix:** o renome puro (`NOT_PUBLISH_FAILED` → `FENCE_LOST`, `notPublishFailed()` → `fenceLost()` e a asserção do `lostFenceDoesNotPublish`) foi para um commit `refactor(03-09)` com os testes verdes. Assim o RED falhou só em asserções.
- **Files modified:** NotificationRepublisher.java, NotificationRepublisherTest.java
- **Verification:** `NotificationRepublisherTest`, `NotificationServiceTest` e `AdminControllerTest` verdes depois do renome
- **Committed in:** 38a6123

**2. [Rule 3 - Blocking] Evidência RED em formato TAP**
- Igual ao 03-08 e ao 03-10. `gsd-tools check tdd-red-evidence` lê TAP, então o resultado real do Surefire/Failsafe (contagens e nomes dos testes que falharam) foi reescrito em TAP. Veredito `RED_EVIDENCE_OK` para os 7 alvos unitários e o IT da Task 1, e para os 3 alvos da Task 2. Os registros ficaram no scratchpad da sessão.

**3. Asserções extras nos testes (endurecimento, sem mudar a intenção)**
- `republishOfAPendingWhoseFenceIsLostDoesNotPublish` também confere que o filtro do fence traz `status` PENDING. Sem isso, o teste passaria no código antigo.
- `republishOfAClaimedDocumentWritesNothing` simula `updateFirst` casando 0. Assim, o código antigo falha na asserção `never().updateFirst` e não com um NPE.
- `replayOfAFreshPendingStillUnconfirmedAtTheEndOfTheWindowRepublishes` confere `atLeastOnce().findById`, para provar que houve espera.

**4. RED do IT com os unitários desligados**
- No RED da Task 1, o IT rodou com `-Dtest=NoUnitTestsHere -Dsurefire.failIfNoSpecifiedTests=false`, porque os unitários RED do `notify-api` (puxado pelo `-am`) teriam derrubado o build antes do Failsafe. Os comandos de verify do plano rodaram sem mudança.

**5. Commits na branch `main`**
- O orquestrador mandou executar em modo sequencial na árvore principal (`git.branching_strategy: none`), como nos planos anteriores. Os commits foram para `main`, com hooks normais.

---

**Total deviations:** 2 de processo (Rule 3), 1 endurecimento de testes, 2 notas de execução
**Impact on plan:** nenhuma mudança de escopo ou de comportamento além do planejado.

## TDD Gate Compliance

- Task 1: RED `bfbb829` → GREEN `de7931e`. Houve um `refactor` preparatório (`38a6123`) antes do RED (ver Deviation 1).
- Task 2: RED `4001ffb` → GREEN `96181a1`.
- Tracer feedback gate: execução interativa, `human_verify_mode` end-of-phase, sem `gate="blocking-human"` e verify só `<automated>`. Os dois comandos de verify da Task 1 passaram sobre o código do commit GREEN (`UnconfirmedPublishRecoveryIT` 2/2, `UnroutablePublishIT` 4/4), então a expansão seguiu sem checkpoint.

## Verification

- Task 1: `./mvnw -q -pl notify-common install && ./mvnw -q -pl notify-api test -Dtest=NotificationRepublisherTest,NotificationServiceTest,AdminControllerTest` saiu com 0. `./mvnw -q -pl notify-it -am verify -Dit.test=UnconfirmedPublishRecoveryIT,UnroutablePublishIT` saiu com 0 (2 + 4 testes, 0 falhas/erros).
- Task 2 e plano: `./mvnw -q -pl notify-common install && ./mvnw -q -pl notify-api test` saiu com 0 (174 testes, 0 falhas). `./mvnw -q verify` saiu com 0: suíte inteira, 35 ITs, inclusive `IdempotencyIT` (5, com a corrida de 8), `UnconfirmedPublishRecoveryIT` (2) e `UnroutablePublishIT` (4). Os relatórios do failsafe foram apagados antes, então são todos desta execução.
- Todos os greps de `acceptance_criteria` das duas tasks passaram. Nenhuma referência de código a `NOT_PUBLISH_FAILED` (contagem 0).

## Issues Encountered

None.

## Known Stubs

Nenhum.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Os itens do Gap 1 do 03-VERIFICATION estão cobertos (varredura no 03-08, rastro e 503 verdadeiros no 03-10, replay com evidência aqui), assim como o WR-02. A fase está pronta para a re-verificação.
- Para o README da Fase 4 (limitações): o replay de um PENDING recente pode segurar a requisição por até 2× `confirm-timeout` (10s) em cada rodada do laço (T-03-39, aceito). A janela usa o relógio da API, e o desvio entre instâncias não é tratado.
- As duas proibições do plano (API-03) continuam `unresolved`/sinalizadas, sem probe de check, como o plano previa.

---
*Phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o*
*Completed: 2026-11-01*

## Self-Check: PASSED

- Arquivos alterados presentes: NotificationRepublisher, NotificationService, NotificationRepublisherTest, NotificationServiceTest, UnconfirmedPublishRecoveryIT
- Commits presentes: 38a6123, bfbb829, de7931e, 4001ffb, 96181a1
