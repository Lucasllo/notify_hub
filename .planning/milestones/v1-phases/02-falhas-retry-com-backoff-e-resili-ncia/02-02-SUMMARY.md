---
phase: 02-falhas-retry-com-backoff-e-resili-ncia
plan: 02
subsystem: worker
tags: [rabbitmq, retry, backoff, dlq, ttl-dlx, publisher-confirms, mongodb, redelivered, lease]

requires:
  - phase: 02-falhas-retry-com-backoff-e-resili-ncia
    provides: "02-01: FailureClassifier, DeliveryRoutingPublisher.publishToDlq com confirm, markFailed + recordFailure privado, ErrorCode.REPUBLISH_FAILED"
  - phase: 01-entrega-ponta-a-ponta-no-caminho-feliz
    provides: "Topologia com filas de delay por nível (TopologyProperties, Channel.retryKey, TtlLabel), claim cercado, header notify-retry-count"
provides:
  - "RetryPolicy.decide(int, List<Duration>) e RetryDecision(exhausted, nextRetryCount, nextLevel)"
  - "NotificationStateService.markRetrying / markDeadLettered (fence PROCESSING, sem gravar retryCount) e markDeadLetteredAfterRepublishFailure (fence RETRYING, só lastError)"
  - "claim(id, h, redelivered) com $or derivado da tabela: claimableForDelivery() sem condição, reclaimableWhenStuck() com redelivered ou leaseUntil < agora"
  - "DeliveryRoutingPublisher.publishToRetry com confirm em notify.retry e routing key de Channel.retryKey(Duration)"
  - "DeliveryProcessor: escada transitória completa (RETRYING → próximo nível; esgotada ou republish falho → DEAD_LETTERED + cópia TRANSIENT na DLQ); construtor com TopologyProperties; process(..., boolean redelivered)"
  - "NotificationStatus: RETRYING → DEAD_LETTERED e reclaimableWhenStuck()"
  - "ChannelListeners lendo AmqpHeaders.REDELIVERED como Boolean"
affects: [02-04, 02-05, 02-06, 02-07, fase-3-reprocessamento, notify-api]

actuals:
  tokens: 24500
  tasks: 3
  commits: 6
plan_head_before: 3943d68e9798dc89983be02ead28dc3ab9b12c21

tech-stack:
  added: []
  patterns:
    - "Escada de retry como função pura (RetryPolicy) sobre os mesmos Duration da topologia; routing key nunca formatada à mão"
    - "Ordem D-11: update RETRYING cercado → publish confirmado do token h+1 → ack; resultados nunca gravam retryCount (quem grava h+1 é o claim do próximo token)"
    - "Cópia na DLQ centralizada em copyToDlqOrReject (FAILED, DEAD_LETTERED por esgotamento e por republish falho)"
    - "Filtro do claim com fence de nível fora do $or e ramos de status derivados da tabela NotificationStatus"

key-files:
  created:
    - notify-worker/src/main/java/dev/notifyhub/worker/processing/RetryPolicy.java
    - notify-worker/src/test/java/dev/notifyhub/worker/processing/RetryPolicyTest.java
  modified:
    - notify-common/src/main/java/dev/notifyhub/common/domain/NotificationStatus.java
    - notify-common/src/test/java/dev/notifyhub/common/domain/NotificationStatusTest.java
    - notify-worker/src/main/java/dev/notifyhub/worker/processing/NotificationStateService.java
    - notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java
    - notify-worker/src/main/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisher.java
    - notify-worker/src/main/java/dev/notifyhub/worker/listener/ChannelListeners.java
    - notify-worker/src/test/java/dev/notifyhub/worker/processing/NotificationStateServiceTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisherTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/listener/ChannelListenersTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/listener/ChannelListenersDispatchTest.java

key-decisions:
  - "RetryDecision esgotada repete o nível atual em nextRetryCount (nextLevel nulo), como no esboço do RESEARCH"
  - "O stuck do claim é status $in reclaimableWhenStuck() (não status = PROCESSING literal), para o filtro sair inteiro da tabela"
  - "Mensagem do REPUBLISH_FAILED: 'republicação para retry.<rótulo> falhou: <motivo do PublishOutcome>' (rótulo de TtlLabel, motivo sem PII)"
  - "Armadilha 12 aceita e documentada no javadoc de onTransientFailure; Pergunta 6 (fence com claimedAt) adiada e documentada no javadoc do claim"

patterns-established:
  - "Toda transição nova de status entra primeiro na tabela NotificationStatus e o service valida canTransitionTo antes do update"
  - "Fence perdido em qualquer resultado: WARN, nada publicado, retorno normal (ack)"

requirements-completed: [DLV-04, DLV-05, WRK-03]

coverage:
  - id: D1
    description: "RetryPolicy decide próximo nível ou esgotamento dos dois lados de cada fronteira com 3, 2 e 1 níveis; h ≥ N (inclusive header adulterado 7) é esgotado"
    requirement: DLV-04
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/RetryPolicyTest.java"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java#tamperedLevelAboveTheLadderIsDeadLetteredToo"
        status: pass
    human_judgment: false
  - id: D2
    description: "Falha transitória no nível h grava RETRYING numa atualização cercada (PROCESSING + retryCount = h) sem a chave retryCount, com $push da tentativa, $inc attemptCount, lastError e $unset leaseUntil; depois publica com confirm em notify.retry/<canal>.retry.<rótulo h+1> com notify-retry-count h+1 e só então confirma a original"
    requirement: DLV-04
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java#transientFailureAtLevelZeroIsWrittenAsRetryingWithoutTouchingRetryCount"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java#transientFailurePublishesAConfirmedTokenToTheFirstDelayLevelAfterTheRetryingUpdate"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java#transientFailureAtLevelOneGoesToTheSecondDelayLevel"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/NotificationStateServiceTest.java#markRetryingIsOneFencedUpdateThatNeverWritesRetryCount"
        status: pass
    human_judgment: false
  - id: D3
    description: "Routing key do retry vem de Channel.retryKey(Duration) com o mesmo Duration da topologia (5s/25s/125s e 200ms batem com os nomes das filas)"
    requirement: DLV-04
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisherTest.java#retryTokenGoesToTheRetryExchangeWithTheLevelKeyAndTheNextRetryCount"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisherTest.java#retryKeyLabelMatchesTheDeclaredQueueForMillisecondLevels"
        status: pass
    human_judgment: false
  - id: D4
    description: "Escada esgotada (h = N) vai de PROCESSING a DEAD_LETTERED numa atualização com a tentativa e lastError TRANSIENT, e a cópia chega à DLQ com x-failure-class TRANSIENT; always-transient soma 4 tentativas com 3 níveis (5s → 25s → 125s → DLQ)"
    requirement: DLV-05
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java#transientFailureOnTheLastLevelEndsDeadLetteredWithATransientDlqCopy"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java#alwaysTransientWalksTheWholeLadderAndEndsDeadLetteredOnTheFourthAttempt"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java#exhaustedLadderIsDeadLetteredAndCopiedToTheDlqAsTransient"
        status: pass
    human_judgment: false
  - id: D5
    description: "Republicação negada/devolvida/sem confirm leva RETRYING → DEAD_LETTERED (fence RETRYING + retryCount, só $set com lastError REPUBLISH_FAILED, sem AttemptEntry extra) e cópia TRANSIENT na DLQ; transitória nunca chama markFailed; fences perdidos não publicam; cópia não confirmada cai em reject sem requeue"
    requirement: DLV-05
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java#failedRepublishMovesRetryingToDeadLetteredAndCopiesToTheDlq"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/NotificationStateServiceTest.java#deadLetteringAfterRepublishFailureOnlySetsStatusAndLastErrorWithoutANewAttempt"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java (lostFenceOnRetryingPublishesNothing, lostFenceOnDeadLetteredPublishesNothing, failedRepublishWithLostFenceOnRetryingPublishesNothingToTheDlq, unconfirmedDlqCopyAfterExhaustionFallsBackToReject, failedRepublishWithUnconfirmedDlqCopyFallsBackToReject)"
        status: pass
      - kind: unit
        ref: "notify-common/src/test/java/dev/notifyhub/common/domain/NotificationStatusTest.java#sourcesOfDeadLetteredAreProcessingAndRetrying"
        status: pass
    human_judgment: false
  - id: D6
    description: "Claim com fence retryCount ≤ h e $or derivado da tabela: PENDING/RETRYING/PUBLISH_FAILED sem condição; PROCESSING sem condição de lease quando redelivered, com leaseUntil < agora sem o flag"
    requirement: WRK-03
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/NotificationStateServiceTest.java (claimFilterIsIdInclusiveRetryLevelAndTwoStatusBranches, redeliveredClaimReclaimsProcessingWithoutLeaseCondition, firstDeliveryOnlyReclaimsProcessingWhoseLeaseHasExpired, claimStatusBranchesAreDerivedFromTheTransitionTable)"
        status: pass
      - kind: unit
        ref: "notify-common/src/test/java/dev/notifyhub/common/domain/NotificationStatusTest.java#reclaimableWhenStuckIsOnlyProcessingDerivedFromTheTable"
        status: pass
    human_judgment: false
  - id: D7
    description: "Os três @RabbitListener leem AmqpHeaders.REDELIVERED como Boolean (nulo = false) e o dispatch real do Spring AMQP com MessageProperties.setRedelivered(true) chega ao processor e ao claim como true; a reentrega retomada termina SENT"
    requirement: WRK-03
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/listener/ChannelListenersDispatchTest.java#brokerRedeliveryFlagReachesTheProcessorAsTrue"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/listener/ChannelListenersTest.java#everyListenerReadsTheRedeliveredHeaderAsAnOptionalBoolean"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java#redeliveredMessageReclaimsAStuckProcessingDocumentAndEndsSent"
        status: pass
    human_judgment: false
  - id: D8
    description: "Runtime real: escada visível no painel do RabbitMQ, DEAD_LETTERED com 4 tentativas e worker derrubado antes do ack retomado com um único SENT"
    requirement: WRK-03
    verification: []
    human_judgment: true
    rationale: "Os testes deste plano são sem Docker (MongoTemplate e RabbitTemplate mockados); o passo de runtime com broker real e kill do worker é do smoke do plano 02-07 (D-12)"

duration: 15min
completed: 2026-10-15
status: complete
---

# Fase 2 Plano 02: Escada de retry com backoff, DEAD_LETTERED e retomada de PROCESSING preso

**A falha transitória deixou de ir direto para a DLQ: grava RETRYING sem mexer em `retryCount`, publica com confirm o token `h+1` em `notify.retry`/`<canal>.retry.<rótulo>` e só então confirma a original. Escada esgotada ou republicação falha terminam DEAD_LETTERED com cópia `x-failure-class: TRANSIENT` na DLQ. O claim retoma uma notificação presa em PROCESSING quando a mensagem chega com `redelivered=true` ou com lease vencido.**

## Desempenho

- **Duração:** ~15 min
- **Início:** 2026-10-15T22:47:16Z
- **Fim:** 2026-10-15T23:02:29Z
- **Tarefas:** 3
- **Arquivos alterados:** 14

## Entregas

- `RetryPolicy.decide` como função pura: com 3 níveis, h = 0, 1 e 2 vão para L1, L2 e L3 com header 1, 2 e 3; h ≥ 3 (inclusive header adulterado) é esgotado. Testado também com 1 e 2 níveis e com rótulos em milissegundos.
- `markRetrying` e `markDeadLettered` reaproveitam o `recordFailure` do 02-01: fence PROCESSING + `retryCount` exato, `$set` de status/lastError/updatedAt **sem** `retryCount`, `$unset` do lease, `$inc` de attemptCount e `$push` com `$slice` -50. O teste com `MongoBson` fixa a ausência de `retryCount` (Armadilha 2).
- `publishToRetry` usa o mesmo `publish` com confirm do 02-01, exchange `NotifyTopology.RETRY_EXCHANGE` e routing key `channel.retryKey(level)`, derivada do mesmo `Duration` que declarou a fila.
- Republicação falha: RETRYING → DEAD_LETTERED (transição nova na tabela) com `lastError` REPUBLISH_FAILED ("republicação para retry.25s falhou: devolvida: NO_ROUTE"), sem `$push` nem `$inc` (D-17), e cópia TRANSIENT na DLQ.
- Nenhuma falha transitória chama `markFailed`. Um fence perdido em qualquer resultado não publica nada e confirma a mensagem. Uma cópia na DLQ não confirmada cai em `AmqpRejectAndDontRequeueException`.
- Claim com `$or` derivado da tabela (`claimableForDelivery()` e o novo `reclaimableWhenStuck()`) e o fence `retryCount ≤ h` fora do `$or`, valendo nos dois ramos. Os três listeners repassam `AmqpHeaders.REDELIVERED` como `Boolean`.

## Commits por tarefa

1. **Tarefa 1 (tracer): transitória → RETRYING → `email.retry.5s` com header 1**
   - RED `b0c1138` (test): `RetryPolicyTest`, cenários transitórios do `DeliveryFlowTest`, caso transitório do `DeliveryProcessorTest`, mais esqueletos que só compilam
   - GREEN `fc54cf0` (feat): `RetryPolicy`, `markRetrying`, `publishToRetry`, ramo transitório do processor
   - Gate do tracer: verify reexecutado e verde antes de expandir
2. **Tarefa 2: esgotamento e republicação falha → DEAD_LETTERED**
   - RED `8d93ad2` (test): tabela, formato dos updates, casos do processor, fluxo da escada inteira e formato do `publishToRetry`
   - GREEN `416f569` (feat): transição RETRYING → DEAD_LETTERED, `markDeadLettered`, `markDeadLetteredAfterRepublishFailure`, `onLadderExhausted`, `onRepublishFailure`, `copyToDlqOrReject`
3. **Tarefa 3: retomada de PROCESSING preso (redelivered ou lease)**
   - RED `bcc92d5` (test): filtro do claim com `MongoBson`, flag nos listeners e no dispatch real; as assinaturas novas entram com o flag ainda ignorado
   - GREEN `9ae2b49` (feat): `reclaimableWhenStuck()`, claim com `orOperator`, flag repassado pelos listeners e pelo processor

Não houve commit de REFACTOR: a cópia na DLQ já foi extraída para `copyToDlqOrReject` no GREEN da Tarefa 2, que era o único ponto com duplicação.

## Arquivos criados/alterados

- `notify-worker/.../processing/RetryPolicy.java` (novo): decisão pura e record `RetryDecision`
- `notify-worker/.../processing/NotificationStateService.java`: `markRetrying`, `markDeadLettered`, `markDeadLetteredAfterRepublishFailure`, claim com `redelivered` e lease, javadoc de at-least-once
- `notify-worker/.../processing/DeliveryProcessor.java`: construtor com `TopologyProperties`, `process(..., redelivered)`, `onTransientFailure`, `onLadderExhausted`, `onRepublishFailure`, `copyToDlqOrReject`, javadoc da Armadilha 12
- `notify-worker/.../messaging/DeliveryRoutingPublisher.java`: `publishToRetry`
- `notify-worker/.../listener/ChannelListeners.java`: parâmetro `@Header(AmqpHeaders.REDELIVERED) Boolean` nos três listeners, javadoc do D-09
- `notify-common/.../domain/NotificationStatus.java`: `RETRYING → {PROCESSING, DEAD_LETTERED}`, `reclaimableWhenStuck()`, javadoc de `claimableForDelivery()`
- Testes: `RetryPolicyTest` (novo); `NotificationStatusTest`, `NotificationStateServiceTest`, `DeliveryProcessorTest`, `DeliveryFlowTest`, `DeliveryRoutingPublisherTest`, `ChannelListenersTest`, `ChannelListenersDispatchTest` (ampliados)

## Decisões

- `RetryDecision` esgotada repete o nível atual em `nextRetryCount`, com `nextLevel` nulo, como no esboço do RESEARCH. O plano só fixava `exhausted` e `nextLevel` nesse caso.
- O ramo "preso" do claim é `status $in reclaimableWhenStuck()`, e não `status = PROCESSING` escrito à mão, para o filtro inteiro sair da tabela.
- A mensagem do REPUBLISH_FAILED inclui o motivo do `PublishOutcome` (nack, devolvida, timeout ou falha ao publicar). O motivo vem do broker e não tem dados do destinatário.
- Armadilha 12 aceita e documentada no javadoc de `onTransientFailure` (Pergunta 7). O endurecimento com `claimedAt` foi adiado e documentado no javadoc do `claim` (Pergunta 6).

## Desvios do plano

### Ajustes de processo (TDD)

**1. Esqueletos que só compilam nos commits RED**
- Mesmo padrão do 02-01. Os testes de cada tarefa referenciam assinaturas novas (`RetryPolicy`, `markRetrying`, `publishToRetry`, construtor com `TopologyProperties`, `markDeadLettered*`, `claim`/`process` com o flag, terceiro parâmetro dos listeners, `reclaimableWhenStuck()`). Sem elas, o RED seria erro de compilação (INVALID_RED).
- Os esqueletos devolvem valores deliberadamente errados (política sempre esgotada, `false`, conjunto vazio, flag ignorado), então os testes-alvo falham pelo comportamento planejado. Tarefa 1: 5 asserções de `RetryPolicyTest` e 4 casos com a transitória ainda propagando. Tarefa 2: 2 falhas na tabela e 10 falhas mais 2 erros no worker (a exceção transitória ainda subia no esgotamento). Tarefa 3: 1 falha na tabela e 6 falhas mais 4 erros. Nesses 4 erros, os helpers leem um `$or` que ainda não existia; o teste de chaves do filtro falha direto por asserção.
- No RED da Tarefa 3, o `notify-common` foi instalado com `-DskipTests` (o `NotificationStatusTest` estava vermelho de propósito) para o worker compilar contra o esqueleto. No GREEN, o install rodou com os testes.

**2. Testes que já passavam no RED**
- `markRetrying` (formato do update) e o formato do `publishToRetry` foram implementados na Tarefa 1, então os testes da Tarefa 2 sobre eles passaram de primeira. Eles só fixam o contrato que o plano pediu para a Tarefa 2.

**3. Evidência de RED**
- `gsd-tools check tdd-red-evidence` só interpreta saída TAP do Node, e o Surefire não é compatível. A evidência está nos logs, resumida no item 1.

### Testes além do plano (Regra 2, cobertura de caminhos críticos)

- `tamperedLevelAboveTheLadderIsDeadLetteredToo`, `unconfirmedDlqCopyAfterExhaustionFallsBackToReject`, `failedRepublishWithUnconfirmedDlqCopyFallsBackToReject`, `failedRepublishWithLostFenceOnRetryingPublishesNothingToTheDlq` (processor), `alwaysTransientWalksTheWholeLadderAndEndsDeadLetteredOnTheFourthAttempt` e `redeliveredMessageReclaimsAStuckProcessingDocumentAndEndsSent` (fluxo). Eles cobrem truths do frontmatter (4 tentativas, fallback de reject, fence perdido no republish) que a lista `<behavior>` não tinha como teste explícito.

### Nota de execução

- Commits feitos direto em `main`: execução sequencial despachada pelo orquestrador, `branching_strategy: none` e histórico linear da Fase 1 no `main`. A asserção de branch protegido do executor acusaria `main`, mas não havia outra branch prevista para esta execução.

---

**Total de desvios:** 0 correções de código fora do plano; 3 ajustes de processo TDD e 6 testes extras documentados acima.
**Impacto:** nenhum no escopo. O comportamento entregue é o do plano.

## Problemas encontrados

Nenhum.

## TDD Gate Compliance

| Tarefa | RED | GREEN | REFACTOR |
|--------|-----|-------|----------|
| 1 | `b0c1138` | `fc54cf0` | não precisou |
| 2 | `8d93ad2` | `416f569` | não precisou |
| 3 | `bcc92d5` | `9ae2b49` | não precisou |

## Verificação

- `./mvnw -q -pl notify-worker test -Dtest=RetryPolicyTest,DeliveryFlowTest,DeliveryProcessorTest`: verde (Tarefa 1; reexecutado como gate do tracer)
- `./mvnw -q -pl notify-common install && ./mvnw -q -pl notify-worker test -Dtest=NotificationStateServiceTest,DeliveryProcessorTest,DeliveryRoutingPublisherTest,DeliveryFlowTest`: verde (Tarefa 2)
- `./mvnw -q -pl notify-common install && ./mvnw -q -pl notify-worker test -Dtest=NotificationStateServiceTest,DeliveryProcessorTest,DeliveryFlowTest,ChannelListenersTest,ChannelListenersDispatchTest`: verde (Tarefa 3, 74 testes)
- `./mvnw -pl notify-worker test`: verde, 152 testes (suíte inteira do worker, sem Docker)
- `./mvnw -pl notify-api test`: verde, 34 testes (regressão da tabela de status)
- Todos os greps de `acceptance_criteria` das três tarefas: PASS
- Runtime real (escada no painel, DEAD_LETTERED com 4 tentativas, kill do worker antes do ack): plano 02-07

## Requisitos

`DLV-04`, `DLV-05` e `WRK-03` estão em `requirements-completed` porque o frontmatter do plano os declara. Pelo gate de IDs compartilhados (`requirements.ready-ids`: 0/3 prontos), eles **não** foram marcados no REQUIREMENTS.md: outros planos da Fase 2 também os declaram e ainda não têm SUMMARY.

## Setup do usuário

Nenhum.

## Prontidão para os próximos planos

- O 02-04 (mágicos) e o 02-05 (CB/bulkhead) só precisam lançar `TransientDeliveryException`/`PermanentDeliveryException` com o `ErrorCode` certo: a escada e o DEAD_LETTERED já tratam qualquer transitória, inclusive recusas de CB/bulkhead (que consomem nível, conforme a suposição sinalizada do DLV-05).
- O 02-06 (métricas) tem os pontos de resultado prontos para contadores: o RETRYING com publish ok, `onLadderExhausted`, `onRepublishFailure` e `onTerminalFailure`.
- O smoke do 02-07 deve cobrir a escada no painel, DEAD_LETTERED com 4 tentativas e o kill do worker antes do ack (D-12).
- A API (`notify-api`) deriva filtros da mesma tabela. A transição nova RETRYING → DEAD_LETTERED passou na regressão sem mudança na API.

---
*Fase: 02-falhas-retry-com-backoff-e-resili-ncia*
*Concluído: 2026-10-15*

## Self-Check: PASSED
