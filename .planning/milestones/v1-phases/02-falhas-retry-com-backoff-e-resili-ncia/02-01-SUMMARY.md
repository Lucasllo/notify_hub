---
phase: 02-falhas-retry-com-backoff-e-resili-ncia
plan: 01
subsystem: worker
tags: [rabbitmq, dlq, publisher-confirms, mongodb, jmustache, failure-classification]

requires:
  - phase: 01-entrega-ponta-a-ponta-no-caminho-feliz
    provides: "DeliveryProcessor com claim cercado, markSent atômico, topologia com notify.dlx e DLQ por canal, NotificationMessage fina"
provides:
  - "Pacote dev.notifyhub.worker.failure: ErrorCode (conjunto fechado D-14), DeliveryFailureException/TransientDeliveryException/PermanentDeliveryException, FailureClassifier exaustivo"
  - "DeliveryRoutingPublisher.publishToDlq com confirm síncrono, CorrelationData UUID, devolução antes do ack e timeout configurável"
  - "NotificationStateService.markFailed atômico e helper privado recordFailure reutilizável para RETRYING/DEAD_LETTERED (02-02)"
  - "DeliveryProcessor: falha permanente/desconhecida depois do claim vira FAILED + cópia confirmada na DLQ com x-failure-class; fallback de reject sem requeue"
  - "FailureClass.UNKNOWN e AttemptOutcome.UNKNOWN_FAILURE (enums compartilhados)"
  - "Propriedades do worker: notify.worker.confirm-timeout, spring.rabbitmq.cache.channel.size, exclusão do MustacheAutoConfiguration"
affects: [02-02, 02-03, 02-04, 02-05, 02-07, fase-3-reprocessamento]

actuals:
  tokens: 20000
  tasks: 3
  commits: 6
plan_head_before: d44494b5b60a82f4f3dd0a7e44373f0de407c541

tech-stack:
  added: []
  patterns:
    - "Classificador como tabela ordenada exceção → (FailureClass, ErrorCode, mensagem própria sem PII, máx. 300 caracteres)"
    - "Publicação do worker sempre confirmada: new CorrelationData() (UUID) por publicação, getReturned() antes de isAck(), timeout tratado como falha"
    - "Resultado terminal: update cercado primeiro, cópia na DLQ depois; cópia não confirmada → AmqpRejectAndDontRequeueException; fence perdido → ack sem DLQ"
    - "Toda falha depois do claim grava exatamente um AttemptEntry; durationMs = 0 quando o adapter não foi chamado"

key-files:
  created:
    - notify-worker/src/main/java/dev/notifyhub/worker/failure/ErrorCode.java
    - notify-worker/src/main/java/dev/notifyhub/worker/failure/DeliveryFailureException.java
    - notify-worker/src/main/java/dev/notifyhub/worker/failure/TransientDeliveryException.java
    - notify-worker/src/main/java/dev/notifyhub/worker/failure/PermanentDeliveryException.java
    - notify-worker/src/main/java/dev/notifyhub/worker/failure/FailureClassifier.java
    - notify-worker/src/main/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisher.java
    - notify-worker/src/test/java/dev/notifyhub/worker/failure/FailureClassifierTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisherTest.java
  modified:
    - notify-common/src/main/java/dev/notifyhub/common/domain/FailureClass.java
    - notify-common/src/main/java/dev/notifyhub/common/domain/AttemptOutcome.java
    - notify-common/src/main/java/dev/notifyhub/common/messaging/NotifyHeaders.java
    - notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulatedDeliveryException.java
    - notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateNotFoundException.java
    - notify-worker/src/main/java/dev/notifyhub/worker/processing/NotificationStateService.java
    - notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java
    - notify-worker/src/main/resources/application.yml
    - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/processing/NotificationStateServiceTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/config/WorkerSettingsTest.java

key-decisions:
  - "A cópia para a DLQ usa o canal da mensagem (message.channel(), já validado igual ao da fila) para a routing key <canal>.dlq"
  - "PublishOutcome.reason leva prefixo legível (nack:, devolvida:, timeout do confirm, falha ao publicar:) e entra na mensagem da AmqpRejectAndDontRequeueException"
  - "markFailed delega a um recordFailure privado que valida PROCESSING.canTransitionTo(destino) pela tabela; o 02-02 reaproveita para RETRYING e DEAD_LETTERED"
  - "Fence perdido no FAILED: o processor registra WARN e confirma sem publicar na DLQ (outro worker é dono do documento)"

patterns-established:
  - "Exceção de domínio do worker carrega ErrorCode e FailureClass; o classificador não adivinha pela mensagem"
  - "Testes de fluxo sem Docker com MongoTemplate e RabbitTemplate mockados e o resto real (classificador, publicador, catálogo, renderer, state service)"

requirements-completed: [DLV-03, DLV-06]

coverage:
  - id: D1
    description: "Template inexistente depois do claim vira FAILED numa única atualização cercada (status, lastError, updatedAt, unset leaseUntil, inc attemptCount, push com $each/$slice -50) e a tentativa é registrada com durationMs 0"
    requirement: DLV-06
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java#missingTemplateIsWrittenAsFailedInOneFencedUpdateWithTheAttempt"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/NotificationStateServiceTest.java#markFailedWritesStatusErrorAndBoundedHistoryInOneUpdate"
        status: pass
    human_judgment: false
  - id: D2
    description: "Cópia confirmada da NotificationMessage em notify.dlx/<canal>.dlq com messageId, x-failure-class e notify-retry-count, CorrelationData UUID diferente do notificationId"
    requirement: DLV-06
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java#missingTemplateSendsAConfirmedCopyToTheChannelDlqWithTheFailureClass"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisherTest.java#dlqCopyGoesToTheDeadLetterExchangeWithOnlyTheFailureHeaders"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisherTest.java#everyPublicationUsesAFreshCorrelationIdThatIsNotTheNotificationId"
        status: pass
    human_judgment: false
  - id: D3
    description: "Cópia para a DLQ não confirmada (nack, devolução, timeout, exceção) cai em AmqpRejectAndDontRequeueException; fence perdido não publica"
    requirement: DLV-06
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java#unconfirmedDlqCopyFallsBackToRejectWithoutRequeue"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java#lostFenceOnFailedSkipsTheDlqCopyAndAcks"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisherTest.java (ack, nack, returned, timeout, AmqpException, interrupt)"
        status: pass
    human_judgment: false
  - id: D4
    description: "FailureClassifier exaustivo: exceções próprias, MustacheException.Context/demais, erros do Mongo (inclusive na cadeia de causas), desconhecida → UNKNOWN/UNEXPECTED sem PII, mensagens truncadas em 300"
    requirement: DLV-03
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/failure/FailureClassifierTest.java"
        status: pass
    human_judgment: false
  - id: D5
    description: "Exceção desconhecida do adapter vira FAILED/UNKNOWN (outcome UNKNOWN_FAILURE, código UNEXPECTED) com cópia na DLQ x-failure-class UNKNOWN; transitória ainda relança; Error não é classificado"
    requirement: DLV-03
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java#unknownAdapterExceptionIsMarkedFailedUnknownAndCopiedToTheDlq"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java#transientAdapterFailureStillPropagatesAndNothingIsWritten"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java#errorFromTheAdapterPropagatesWithoutBeingClassified"
        status: pass
    human_judgment: false
  - id: D6
    description: "application.yml do worker: confirm-timeout 5s, cache.channel.size 50, MustacheAutoConfiguration excluído"
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/config/WorkerSettingsTest.java"
        status: pass
    human_judgment: false
  - id: D7
    description: "Mensagem real chega à DLQ com x-failure-class: PERMANENT vista pela API de gerenciamento do RabbitMQ (runtime real)"
    requirement: DLV-06
    verification: []
    human_judgment: true
    rationale: "Os testes deste plano são sem Docker (mocks de RabbitTemplate/MongoTemplate); a verificação contra broker real é do plano 02-07"

duration: 16min
completed: 2026-10-15
status: complete
---

# Fase 2 Plano 01: Falha permanente → FAILED + DLQ com x-failure-class

**Classificador de falhas com conjunto fechado de códigos, `markFailed` atômico cercado por PROCESSING + retryCount e cópia confirmada em `notify.dlx`/`<canal>.dlq` com `x-failure-class` (PERMANENT ou UNKNOWN). Se a cópia não for confirmada, a original vai para a DLQ por reject sem requeue.**

## Desempenho

- **Duração:** ~16 min
- **Início:** 2026-10-15T22:25:48Z
- **Fim:** 2026-10-15T22:42:12Z
- **Tarefas:** 3
- **Arquivos alterados:** 20

## Entregas

- Template inexistente (o caso conhecido do smoke 01-06) deixa de prender a notificação em PROCESSING: o documento termina FAILED com a tentativa no histórico e a DLQ do canal recebe uma cópia com `x-failure-class: PERMANENT`.
- Exceção desconhecida depois do claim vira FAILED/UNKNOWN (código `UNEXPECTED`, outcome `UNKNOWN_FAILURE`), não gasta retries e leva `x-failure-class: UNKNOWN` na DLQ. A mensagem gravada traz só o nome simples da classe.
- `FailureClassifier` com a tabela completa: `DeliveryFailureException`, `MustacheException.Context` (MISSING_VARIABLE, só o nome da variável), demais `MustacheException` (TEMPLATE_INVALID), erros do Mongo (STORE_UNAVAILABLE, também pela cadeia de causas) e o resto como UNKNOWN/UNEXPECTED.
- `DeliveryRoutingPublisher` com confirm síncrono, `new CorrelationData()` por publicação, devolução conferida antes do ack, timeout configurável e tratamento de interrupção, nack e `AmqpException`.
- Configuração do worker: `notify.worker.confirm-timeout: 5s`, `spring.rabbitmq.cache.channel.size: 50` e exclusão do `MustacheAutoConfiguration`. Isso fecha o item aberto em `.planning/phases/01-entrega-ponta-a-ponta-no-caminho-feliz/deferred-items.md`, que é um artefato da Fase 1 e não foi editado.

## Commits por tarefa

1. **Tarefa 1 (tracer): template inexistente → FAILED + DLQ PERMANENT**
   - RED `6221c8a` (test): cenários do `DeliveryFlowTest` e ajuste do `DeliveryProcessorTest`, mais esqueletos que só compilam
   - GREEN `994dcd8` (feat): classificador (linha `DeliveryFailureException`), publicador, `markFailed`, processor
2. **Tarefa 2: classificador exaustivo e UNKNOWN**
   - RED `4595f5c` (test): `FailureClassifierTest`, novos casos do processor e enums compartilhados com UNKNOWN
   - GREEN `1b04a69` (feat): `TransientDeliveryException`, `SimulatedDeliveryException` com novo pai, tabela completa, catch de `RuntimeException`
3. **Tarefa 3: confirm com timeout, fallback e configuração**
   - RED `575a1b9` (test): `WorkerSettingsTest` (vermelho), além de `DeliveryRoutingPublisherTest`, `markFailed` e testes de fallback, que já fixam o contrato
   - GREEN `3973be3` (feat): `application.yml`

Não houve commit de REFACTOR: nada a limpar depois do GREEN.

## Arquivos criados/alterados

- `notify-worker/.../failure/ErrorCode.java`: conjunto fechado do D-14 com javadoc pt-BR
- `notify-worker/.../failure/DeliveryFailureException.java`, `TransientDeliveryException.java`, `PermanentDeliveryException.java`: hierarquia com `errorCode()` e `failureClass()`
- `notify-worker/.../failure/FailureClassifier.java`: tabela ordenada e record `Classification` com `outcome()`
- `notify-worker/.../messaging/DeliveryRoutingPublisher.java`: `publishToDlq` e record `PublishOutcome`
- `notify-worker/.../processing/NotificationStateService.java`: `markFailed` + `recordFailure` privado
- `notify-worker/.../processing/DeliveryProcessor.java`: try único depois do claim, classificação, `onTerminalFailure`
- `notify-worker/.../template/TemplateNotFoundException.java`: estende `PermanentDeliveryException` (TEMPLATE_NOT_FOUND)
- `notify-worker/.../channel/SimulatedDeliveryException.java`: estende `TransientDeliveryException` (SIMULATED_TRANSIENT)
- `notify-common/.../FailureClass.java`, `AttemptOutcome.java`, `NotifyHeaders.java`: UNKNOWN, UNKNOWN_FAILURE e javadoc
- `notify-worker/src/main/resources/application.yml`: três propriedades novas
- Testes: `FailureClassifierTest`, `DeliveryRoutingPublisherTest` (novos); `DeliveryFlowTest`, `DeliveryProcessorTest`, `NotificationStateServiceTest`, `WorkerSettingsTest` (ampliados)

## Decisões

- A routing key da cópia vem de `message.channel()`, já validado igual ao canal da fila.
- `PublishOutcome.reason` tem prefixo legível e vai para a mensagem da `AmqpRejectAndDontRequeueException` (log), sem dados do destinatário.
- Fence perdido no FAILED: WARN e ack sem DLQ.
- `recordFailure` valida a transição pela tabela `NotificationStatus` e serve de molde para o 02-02.

## Desvios do plano

### Ajustes de processo (TDD)

**1. Esqueletos que só compilam no commit RED da Tarefa 1**
- **Motivo:** os testes da Tarefa 1 referenciam tipos novos (`FailureClassifier`, `DeliveryRoutingPublisher`, `markFailed`, construtor de 6 argumentos). Sem esses tipos o RED seria erro de compilação, que a referência TDD classifica como INVALID_RED.
- **O que foi feito:** o commit `6221c8a` inclui `ErrorCode`, as exceções base e esqueletos com `UnsupportedOperationException`. Os testes-alvo falharam por asserção: `TemplateNotFoundException` ainda propagava ("Expecting code not to raise a throwable").
- **Mesmo padrão na Tarefa 2:** o commit RED `4595f5c` já inclui os valores dos enums compartilhados (UNKNOWN/UNKNOWN_FAILURE) e o mapeamento de `outcome()`. São dados necessários para os testes compilarem; os 9 testes-alvo falharam por asserção.

**2. Testes da Tarefa 3 que já passavam no RED**
- `DeliveryRoutingPublisherTest`, os testes de `markFailed` e os de fallback do processor passaram na primeira execução. A Tarefa 1 já tinha implementado o publicador inteiro, com timeout, interrupção, nack, devolução e `AmqpException`, seguindo o molde da API que o plano pedia. Esses testes só fixam o contrato. O RED válido da Tarefa 3 veio do `WorkerSettingsTest`: 2 falhas por asserção antes da mudança no `application.yml`.

**3. Evidência de RED**
- `gsd-tools check tdd-red-evidence` só interpreta saída TAP do Node. A saída do Surefire/JUnit não é compatível, então não foi gerado o registro JSON. A evidência está nos logs: Tarefa 1 com 5 testes-alvo falhando (1 asserção e 4 com `TemplateNotFoundException` propagando), Tarefa 2 com 9 falhas por asserção e Tarefa 3 com 2 falhas por asserção.

---

**Total de desvios:** 0 correções de código fora do plano; 3 ajustes de processo TDD documentados acima.
**Impacto:** nenhum no escopo nem no comportamento entregue.

## Problemas encontrados

Nenhum.

## TDD Gate Compliance

| Tarefa | RED | GREEN | REFACTOR |
|--------|-----|-------|----------|
| 1 | `6221c8a` | `994dcd8` | não precisou |
| 2 | `4595f5c` | `1b04a69` | não precisou |
| 3 | `575a1b9` | `3973be3` | não precisou |

## Verificação

- `./mvnw -q -pl notify-worker test -Dtest=DeliveryFlowTest,DeliveryProcessorTest`: verde (Tarefa 1; reexecutado como gate do tracer)
- `./mvnw -q -pl notify-common install && ./mvnw -q -pl notify-worker test -Dtest=FailureClassifierTest,DeliveryProcessorTest,DeliveryFlowTest && ./mvnw -q -pl notify-api test`: verde (Tarefa 2, com a regressão da API)
- `./mvnw -q -pl notify-worker test -Dtest=DeliveryRoutingPublisherTest,NotificationStateServiceTest,DeliveryProcessorTest,WorkerSettingsTest`: verde (Tarefa 3)
- `./mvnw -q -pl notify-worker test` (suíte inteira do worker): verde
- Todos os greps de `acceptance_criteria` das três tarefas: PASS

## Requisitos

`DLV-03` e `DLV-06` estão em `requirements-completed` porque o frontmatter do plano os declara. Pelo gate de IDs compartilhados (`requirements.ready-ids`: 0/2 prontos), eles **não** foram marcados no REQUIREMENTS.md: os planos irmãos da Fase 2 também os declaram e ainda não têm SUMMARY.

## Setup do usuário

Nenhum.

## Prontidão para os próximos planos

- O 02-02 troca o ramo `case TRANSIENT -> throw e` do `DeliveryProcessor.onFailure` pela escada de delay. Para isso pode reaproveitar `recordFailure` (RETRYING/DEAD_LETTERED) e acrescentar `publishToRetry` ao `DeliveryRoutingPublisher` usando o `publish` privado já existente.
- O 02-04 (mágicos) e o 02-05 (CB/bulkhead) só precisam lançar `PermanentDeliveryException`/`TransientDeliveryException` com o `ErrorCode` certo, ou acrescentar linhas ao classificador (`CallNotPermittedException`, `BulkheadFullException`).
- A verificação contra broker real fica para o 02-07.

---
*Fase: 02-falhas-retry-com-backoff-e-resili-ncia*
*Concluído: 2026-10-15*

## Self-Check: PASSED
