---
phase: quick-261020-tm3
plan: 01
subsystem: notify-worker
tags: [security, retry, rabbitmq, pii, configuration]
status: complete
requires: []
provides:
  - "PublishOutcome com motivo enumerado (NACK, RETURNED:<code>, CONFIRM_TIMEOUT, SEND_FAILED:<tipo>, CONFIRM_FAILED:<tipo>)"
  - "FailureClassifier.truncate público, usado no lastError REPUBLISH_FAILED"
  - "avanço do fence tolerante a qualquer RuntimeException"
  - "destinatários mágicos desligados no profile base, ligados no profile demo"
affects:
  - "ITs da Fase 3 e compose da Fase 4 (precisam do profile demo para os destinatários mágicos)"
tech-stack:
  added: []
  patterns:
    - "motivo de falha de broker como conjunto fechado; nunca texto livre de broker ou de exceção no Mongo"
key-files:
  created: []
  modified:
    - notify-worker/src/main/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisher.java
    - notify-worker/src/main/java/dev/notifyhub/worker/failure/FailureClassifier.java
    - notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java
    - notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulationProperties.java
    - notify-worker/src/main/resources/application.yml
    - notify-worker/src/main/resources/application-demo.yml
    - notify-worker/src/test/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisherTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/channel/SimulatedChannelAdapterTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/config/WorkerSettingsTest.java
decisions:
  - "O lastError REPUBLISH_FAILED só carrega um motivo de conjunto fechado; o tipo da exceção é o getSimpleName() (getName() para classe anônima)"
  - "advanceRetryFence captura RuntimeException (nunca Error) e passa a exceção ao log.warn; a causa não vai para Mongo nem DLQ"
  - "notify.simulation.magic-recipients tem padrão false (YAML base e @DefaultValue); só o profile demo ou a ENV com true liga"
metrics:
  duration: "retomada; 3 tasks"
  completed: 2026-10-28
actuals:
  tokens: 9100
  tasks: 3
  commits: 3
plan_head_before: a7ea11d
---

# Quick 261020-tm3: lastError de republicação sem PII, fence tolerante e destinatários mágicos só na demo

O lastError REPUBLISH_FAILED agora é `republicação para retry.<nível> falhou: <MOTIVO>`, com o motivo de um conjunto fechado (NACK, RETURNED:<código>, CONFIRM_TIMEOUT, SEND_FAILED:<tipo>, CONFIRM_FAILED:<tipo>) e truncado em 300 caracteres. O avanço do fence depois do confirm aceita qualquer RuntimeException sem rejeitar a original. Os destinatários mágicos vêm desligados no profile base e são ligados pelo profile `demo`.

## Tasks

| # | Task | Commit | Tipo |
|---|------|--------|------|
| 1 | WR-05/T-02-01: motivo enumerado do PublishOutcome até o BSON DEAD_LETTERED, truncado em 300 | fe60ac2 | tracer, TDD |
| 2 | WR-09: o avanço do fence tolera qualquer RuntimeException (nunca Error) e loga a causa | 7cb7ce6 | auto, TDD |
| 3 | WR-07: destinatários mágicos desligados no profile base e ligados no profile demo | 07b5fb1 | auto, TDD |

Ciclo TDD em cada task: os testes novos foram escritos antes e falharam pelo motivo esperado.
- Task 1: o BSON gravado continha `nack: canal fechado ana@exemplo.com xxx…` e `falha ao publicar: java.net.ConnectException: Connection refused ana@exemplo.com`.
- Task 2: a IllegalStateException escapava do processor nos dois testes. O teste do Error já passava, porque só fixa o comportamento atual.
- Task 3: o default ainda era true.

Depois da correção, todos passaram.

## Verificação

- Gates do plano: o publisher não chama `getMessage()`, `getReplyText()` nem `getReason()` (fora do Javadoc); `DeliveryProcessor` usa `FailureClassifier.truncate(`; não sobrou nenhum `catch (DataAccessException`.
- `./mvnw -q -B test` na raiz: exit 0, 465 testes, 0 falhas, 0 erros, 0 ignorados, sem Docker.

## Evidência para fechamento

**T-02-01 / WR-05 (lastError REPUBLISH_FAILED sem texto livre, no máximo 300 caracteres)**
- `notify-worker/src/main/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisher.java:90,96,98,105`: o `publish` só produz `sendFailed(e)`, `returned(replyCode)`, `nacked()`, `timeout()` e `confirmFailed(causa)`.
- `DeliveryRoutingPublisher.java:122-156`: o record `PublishOutcome` e as fábricas enumeradas. `typeOf` (linha 152) devolve só o nome simples da classe.
- `notify-worker/src/main/java/dev/notifyhub/worker/failure/FailureClassifier.java:41,91`: `public static final int MAX_MESSAGE = 300` e `public static String truncate`.
- `notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java:273`: `FailureClassifier.truncate("republicação para retry." + levelLabel + " falhou: " + outcome.reason())`.
- Testes:
  - `DeliveryFlowTest.nackedRepublishRecordsOnlyTheEnumeratedReasonWithoutBrokerText` (linha 450) e `DeliveryFlowTest.republishSendFailureRecordsOnlyTheExceptionType` (linha 476) checam o BSON DEAD_LETTERED gravado com e-mail injetado no texto do broker e na exceção.
  - `DeliveryProcessorTest.failedRepublishLastErrorIsTruncatedToTheClassifierLimit` (linha 299) e `failedRepublishMovesRetryingToDeadLetteredAndCopiesToTheDlq` (linha 277).
  - Em `DeliveryRoutingPublisherTest`: `nackIsNotOkWithOnlyTheEnumeratedReason` (74), `nackWithoutReasonNeverRecordsNull` (84), `failedConfirmFutureRecordsOnlyTheExceptionType` (94), `returnedMessageWinsOverAck` (104), `confirmThatNeverArrivesIsTimeout` (118) e `brokerFailureOnSendIsNotOkAndDoesNotPropagate` (130).

**WR-09 (avanço do fence)**
- `DeliveryProcessor.java:244-248`: `catch (RuntimeException e)`, com a exceção passada como último argumento do `log.warn`. O Javadoc de `onTransientFailure` e de `advanceRetryFence` foi atualizado, e o import de `DataAccessException` foi removido.
- Testes: `DeliveryProcessorTest.unexpectedRuntimeExceptionOnTheAdvanceStillAcksWithoutTouchingTheDlq` (693), `DeliveryProcessorTest.errorOnTheAdvanceIsNeverSwallowed` (708) e `DeliveryFlowTest.unexpectedRuntimeExceptionOnTheAdvanceStillAcksWithoutTouchingTheDlq` (893).

**WR-07 / T-02-18 (destinatários mágicos)**
- `notify-worker/src/main/resources/application.yml:73`: `magic-recipients: ${NOTIFY_SIMULATION_MAGIC_RECIPIENTS:false}`.
- `notify-worker/src/main/resources/application-demo.yml:8`: `magic-recipients: ${NOTIFY_SIMULATION_MAGIC_RECIPIENTS:true}`, dentro do bloco `notify:` que já existia.
- `notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulationProperties.java:26`: `@DefaultValue("false")`.
- Testes:
  - `WorkerSettingsTest.magicRecipientsAreOffInTheBaseProfileAndOnInTheDemoProfile` (79).
  - Em `SimulatedChannelAdapterTest`: `magicRecipientsAreDisabledByDefault` (244), `magicRecipientsPropertyTurnsTheFeatureOnForEveryAdapter` (254), `baseProfileYamlKeepsMagicRecipientsOff` (268) e `demoProfileYamlTurnsMagicRecipientsOn` (274).

Este plano não edita `02-REVIEW.md` nem `02-SECURITY.md`. Os veredictos ficam com `/gsd-secure-phase 02` e com o re-review.

## Nota para frente

Os ITs da Fase 3 e o compose da Fase 4 precisam ativar o profile `demo` (ou `NOTIFY_SIMULATION_MAGIC_RECIPIENTS=true`) para usar os destinatários mágicos. O smoke da Fase 2 já sobe o worker com `--spring.profiles.active=demo` (`scripts/smoke-lib.sh:258`), então continua coberto. Os testes unitários que usam o construtor de 3 argumentos de `SimulatedChannelAdapter` continuam com os mágicos ligados.

## Deviations from Plan

Nenhum desvio de código: o plano foi executado como escrito. Um ponto do processo: a execução foi retomada a partir de uma edição não commitada em `DeliveryFlowTest.java` (três imports). Ela foi mantida e entrou no commit da Task 1.

Fora do escopo e não alterado: o Javadoc de `MagicRecipients.java:15` ainda diz que a flag "desliga o reconhecimento". Continua correto, mas poderia dizer que o padrão agora é desligado. Isso fica para uma próxima passada de docs.

## Known Stubs

Nenhum.

## Self-Check: PASSED

- Os 11 arquivos de `key-files.modified` existem no repositório.
- Os commits fe60ac2, 7cb7ce6 e 07b5fb1 aparecem em `git log` (`git rev-list --count a7ea11d..HEAD` = 3).
