---
phase: 02-falhas-retry-com-backoff-e-resili-ncia
reviewed: 2026-10-20T23:44:02Z
depth: standard
files_reviewed: 54
files_reviewed_list:
  - notify-common/src/main/java/dev/notifyhub/common/domain/AttemptOutcome.java
  - notify-common/src/main/java/dev/notifyhub/common/domain/FailureClass.java
  - notify-common/src/main/java/dev/notifyhub/common/domain/NotificationStatus.java
  - notify-common/src/main/java/dev/notifyhub/common/messaging/NotifyHeaders.java
  - notify-common/src/test/java/dev/notifyhub/common/domain/NotificationStatusTest.java
  - notify-worker/pom.xml
  - notify-worker/src/main/java/dev/notifyhub/worker/channel/AttemptContext.java
  - notify-worker/src/main/java/dev/notifyhub/worker/channel/ChannelAdapter.java
  - notify-worker/src/main/java/dev/notifyhub/worker/channel/MagicRecipients.java
  - notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulatedChannelAdapter.java
  - notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulatedDeliveryException.java
  - notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulationConfiguration.java
  - notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulationProperties.java
  - notify-worker/src/main/java/dev/notifyhub/worker/failure/DeliveryFailureException.java
  - notify-worker/src/main/java/dev/notifyhub/worker/failure/ErrorCode.java
  - notify-worker/src/main/java/dev/notifyhub/worker/failure/FailureClassifier.java
  - notify-worker/src/main/java/dev/notifyhub/worker/failure/PermanentDeliveryException.java
  - notify-worker/src/main/java/dev/notifyhub/worker/failure/TransientDeliveryException.java
  - notify-worker/src/main/java/dev/notifyhub/worker/listener/ChannelListeners.java
  - notify-worker/src/main/java/dev/notifyhub/worker/listener/RetryHeaders.java
  - notify-worker/src/main/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisher.java
  - notify-worker/src/main/java/dev/notifyhub/worker/metrics/DeliveryMetrics.java
  - notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java
  - notify-worker/src/main/java/dev/notifyhub/worker/processing/NotificationStateService.java
  - notify-worker/src/main/java/dev/notifyhub/worker/processing/RetryPolicy.java
  - notify-worker/src/main/java/dev/notifyhub/worker/resilience/ChannelGateway.java
  - notify-worker/src/main/java/dev/notifyhub/worker/resilience/NonTransientFailurePredicate.java
  - notify-worker/src/main/java/dev/notifyhub/worker/template/JsonValueEscaper.java
  - notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateNotFoundException.java
  - notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateRenderer.java
  - notify-worker/src/main/resources/application.yml
  - notify-worker/src/main/resources/application-demo.yml
  - notify-worker/src/test/java/dev/notifyhub/worker/channel/MagicRecipientsTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/channel/SimulatedChannelAdapterTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/config/ResilienceSettingsTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/config/WorkerSettingsTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/failure/FailureClassifierTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/listener/ChannelListenersDispatchTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/listener/ChannelListenersTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/listener/RetryHeadersTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisherTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/metrics/DeliveryMetricsTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/processing/NotificationStateServiceTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/processing/RetryPolicyTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/processing/SingleNotificationStore.java
  - notify-worker/src/test/java/dev/notifyhub/worker/resilience/ChannelGatewayTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/resilience/ResilienceFixtures.java
  - notify-worker/src/test/java/dev/notifyhub/worker/template/SeedTemplatesTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java
  - pom.xml
  - scripts/smoke-fase2.sh
  - scripts/smoke-lib.sh
findings:
  critical: 1
  warning: 7
  info: 8
  total: 16
status: issues_found
---

# Phase 2: Code Review Report

**Reviewed:** 2026-10-20T23:44:02Z (revisão incremental `21b504e..38928f4`; a revisão completa original é de 2026-10-20T00:00:00Z)
**Depth:** standard
**Files Reviewed:** 54 (51 da revisão completa + 3 novos da gap closure 02-08: `RetryHeaders.java`, `RetryHeadersTest.java`, `SingleNotificationStore.java`)
**Status:** issues_found

## Summary

Revisei o caminho de falha da Fase 2 de ponta a ponta: listener → claim → template/render → gateway (bulkhead → CB → adapter) → classificador → atualização com fence → publicação confirmada para retry/DLQ, junto com as métricas, a configuração e os smokes. O caminho feliz e os ramos classificados (transitório, permanente, desconhecido, republicação falha) estão bem cercados e bem testados com mocks.

Os defeitos estão nas bordas que os testes não cobrem:

- Falhas de infraestrutura (Mongo indisponível no claim ou no update de resultado) e mensagens venenosas estacionam a mensagem na DLQ com o documento em estado **não terminal**. Nem a tabela de transições nem o reprocessamento planejado para a Fase 3 conseguem tirar o documento desse estado. Isso fere o Core Value. **(CR-01, ainda aberto; adiado para a Fase 3 em `deferred-items.md`.)**
- ~~O claim aceita um segundo token do mesmo nível quando o documento está RETRYING.~~ **Resolvido no 02-08 (WR-01).**
- ~~Um header `notify-retry-count` = `Integer.MAX_VALUE` estoura `RetryPolicy`.~~ **Resolvido no 02-08 (WR-02).**
- O caminho de thread interrompida continua fazendo I/O e marca a notificação como DEAD_LETTERED mesmo quando o broker deu ack.
- Pequenas violações do contrato de PII/tamanho do `lastError`, da renderização "estrita" e do padrão de configuração dos destinatários mágicos.

### Revisão incremental da gap closure 02-08 (`21b504e..38928f4`)

Escopo: `RetryHeaders`, `DeliveryProcessor`, `NotificationStateService`, `RetryPolicy` e os testes correspondentes, incluindo o novo `SingleNotificationStore`. Rodei `RetryPolicyTest`, `RetryHeadersTest`, `DeliveryFlowTest`, `DeliveryProcessorTest` e `NotificationStateServiceTest` (`mvnw -o -pl notify-worker -am test`). Os 121 testes passaram.

- **WR-02: resolvido.** `RetryPolicy.decide` (`RetryPolicy.java:30-36`) testa `retryCount >= levels.size()` antes do `+1`, então `retryCount + 1` só é calculado com `retryCount < levels.size()` e não pode estourar. Procurei outros `+ 1` sobre o nível no código principal do worker e não há nenhum (`attemptNumber` vem de `doc.attemptCount()`, não do header). Testes: `RetryPolicyTest` (`MAX_VALUE`, `MAX_VALUE-1`, escada de 1 nível), `DeliveryProcessorTest.integerMaxValueLevelIsDeadLettered…` e `DeliveryFlowTest.integerMaxValueRetryHeaderEndsDeadLettered…` (BSON real: DEAD_LETTERED, 1 tentativa, cópia TRANSIENT com `notify-retry-count=2147483647` e nenhum publish em `notify.retry`). A sugestão de teto no `RetryHeaders` foi recusada de propósito, com justificativa razoável (um teto reproduziria o CR-01 antes do claim). Ver IN-08 sobre a precisão desse Javadoc.
- **WR-01: resolvido, com a janela residual documentada.** A correção não usa o `nextRetryPublished` sugerido. Ela grava `retryCount = h+1` no documento RETRYING logo depois do confirm do token `h+1` (`NotificationStateService.advanceRetryLevel`, linhas 238-253, chamado em `DeliveryProcessor.java:222-224`). Tracei os casos:
  - *Original `h` reentregue depois do avanço*: o claim exige `retryCount(h+1) ≤ h`, que é falso, então a cópia é descartada e confirmada. Esse é o cenário do WR-01, e o caso está fechado.
  - *Cópia repetida do token `h+1` depois que o primeiro avançou para `h+2`*: o fence também a barra. Se o primeiro ainda está PROCESSING com lease vigente, a cópia sem `redelivered` não casa. Se terminou SENT, FAILED ou DEAD_LETTERED, o status não é reivindicável. A propagação descrita no WR-01 original não acontece mais.
  - *Token `h+1` reivindicado antes do avanço* (TTL curto ou pausa longa): o avanço exige `status = RETRYING` **e** `retryCount = h` exatos. PROCESSING(h+1), RETRYING(h+1) e qualquer estado terminal ficam fora do fence, então não há regressão de nível.
  - *Worker morre entre `markRetrying` e o publish*: não há avanço, o documento continua RETRYING(h) e a reentrega refaz a tentativa (Armadilha 2, coberta por `redeliveredOriginalStillRetriesWhenTheWorkerDiedBeforeTheRetryPublish`).
  - *Republicação falha*: não há avanço (`failedRepublishNeverAdvancesTheFence`), e o fence `RETRYING ∧ h` de `markDeadLetteredAfterRepublishFailure` continua coerente. Se a reentrega concorrente de outro worker já tiver avançado o documento, esse fence perde e a notificação segue pelo token vivo, que é o resultado correto.
  - *Janela residual* entre o confirm e o avanço (cópia `redelivered` reivindica RETRYING(h) antes do `updateFirst`): o avanço de A perde o fence, B faz uma tentativa extra e publica um segundo `h+1`. Os dois `h+1` expiram quase juntos. O segundo encontra PROCESSING(h+1) com lease vigente, ou já `h+2`, e é descartado. O custo fica em uma tentativa extra no nível `h`, exatamente como o Javadoc do claim (linhas 84-90) documenta.
  - *Ordem em relação ao ack*: o avanço roda antes do retorno do listener, ou seja, antes do ack. `metrics.retrying` continua contando só depois do confirm, mesmo com fence perdido, o que está correto porque o token existe.
- **Fidelidade do `SingleNotificationStore`:** boa para o que é testado. Os filtros e updates passam pelos mesmos `QueryMapper`/`UpdateMapper` do `MongoTemplate` (`MongoBson`), operadores não suportados lançam exceção em vez de passar calados, ausência de campo não satisfaz `$lt`/`$lte`/`$in` (igual ao Mongo) e `Date`/`Instant`/enum são normalizados. Limitações conhecidas, que hoje não afetam nenhum teste: `$slice` é ignorado, `channel`/`templateCode` são fixos em EMAIL/"welcome" e o modelo é sequencial, então não reproduz a janela residual acima. Não há defeito de confiabilidade nos testes novos.
- **Novos achados:** WR-09 (o `catch` do avanço é mais estreito que a invariante que ele protege, e descarta a causa), IN-07 (`retryCount` do GET em RETRYING passou a depender do avanço) e IN-08 (Javadoc do `RetryHeaders` promete mais do que o código faz).

## Narrative Findings (AI reviewer)

## Critical Issues

### CR-01: Falha do Mongo no claim ou no update de resultado deixa o documento preso num estado não reprocessável, com a mensagem na DLQ

**Status:** ABERTO, adiado para a Fase 3 (`deferred-items.md`, decisão do usuário na gap closure 02-08). O 02-08 corrigiu só o Javadoc (`DeliveryProcessor.java:56-60`, que agora não promete recuperação automática). O comportamento continua o descrito abaixo.

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java:120, 156, 213, 254, 288` (com `notify-common/src/main/java/dev/notifyhub/common/domain/NotificationStatus.java:27-36` e `NotificationStateService.java:97-115`)

**Issue:** Antes do 02-08, o Javadoc afirmava: "falha do Mongo no claim ou no update de resultado ... sobe para o container (reject → DLQ) e o documento continua recuperável". Isso não era verdade, e o comportamento não mudou:

1. **Claim falha** (`state.claim`, linha 120, lança `DataAccessResourceFailureException`): o documento continua PENDING ou RETRYING e a mensagem vai para a DLQ sem `x-failure-class`. Nenhuma métrica é incrementada.
2. **Update de resultado falha** (`markSent` na linha 156, `markRetrying` na 213, `markDeadLettered` na 254, `markFailed` na 288): o documento fica PROCESSING com lease de 5m e a mensagem vai para a DLQ. No caso do `markSent`, o envio já aconteceu.
3. A tabela de `NotificationStatus` só permite reprocessar a partir de FAILED ou DEAD_LETTERED (`FAILED → PENDING`, `DEAD_LETTERED → PENDING`). O reprocessamento da Fase 3 (ADM-01: "basicGet, reset no Mongo, republish, ack; **para na primeira falha**") vai falhar o reset desses documentos. Esse reset é derivado da tabela. Resultado: a mensagem fica na cabeça da DLQ e bloqueia o reprocessamento de todo o canal.
4. Se alguém republicar a mensagem crua na fila principal antes de o lease vencer, o claim de um documento PROCESSING exige `redelivered=true` ou `leaseUntil < now`. Uma mensagem republicada não tem `redelivered`, então o claim volta vazio. A mensagem é confirmada como "duplicada/obsoleta descartada" (linha 122) e o documento fica PROCESSING para sempre, sem mensagem em fila nenhuma. Isso é uma **perda silenciosa**.

A mesma classe de problema ocorre com toda rejeição pré-claim: header de retry inválido (`RetryHeaders.parse`) e canal divergente (linhas 110-117). O documento fica PENDING e a mensagem vai para a DLQ.

Uma queda do Mongo é um cenário que o próprio código modela (`ErrorCode.STORE_UNAVAILABLE`). Numa queda de alguns segundos durante a carga do k6, todas as mensagens em voo ficam encalhadas desse jeito.

**Fix:** Não rejeitar falhas de armazenamento para a DLQ. Devolver o token à escada sem tocar no Mongo. O fence `retryCount ≤ h` do claim aceita o mesmo `h` de novo:
```java
Optional<NotificationDocument> claimed;
try {
    claimed = state.claim(id, retryCount, redelivered);
} catch (DataAccessException e) {
    // Mongo fora: o token volta pela fila de delay do 1º nível com o mesmo h, documento intocado
    PublishOutcome out = publisher.publishToRetry(listenerChannel, message, retryCount,
            topology.retryLevels().get(0));
    if (!out.ok()) throw new AmqpRejectAndDontRequeueException("store e broker indisponíveis id=" + id, e);
    metrics.storeUnavailable(listenerChannel);   // nova série
    return;
}
```
Faça o mesmo em volta de `markSent`/`recordFailure`. O claim seguinte também precisa reivindicar PROCESSING no mesmo `h` quando vier desse caminho, por exemplo com um header `x-store-retry` tratado como `redelivered`. Além disso:

- incluir na tabela as saídas de reprocessamento para documentos encalhados (`PENDING`/`RETRYING`/`PROCESSING` com lease vencido → `PENDING` no reset administrativo), ou um sweeper de lease vencido;
- ~~corrigir o Javadoc das linhas 53-54~~ (feito no 02-08).

## Warnings

### WR-01: Token repetido do mesmo nível reivindica de novo um documento RETRYING (envio duplicado e tokens multiplicados)

**Status:** RESOLVIDO no 02-08 (commits `cd50847`, `fc724ee`, `587d5bd`). Não conta no frontmatter.

**Evidência:** `NotificationStateService.advanceRetryLevel` (linhas 238-253) faz um `updateFirst` cercado por `status = RETRYING ∧ retryCount = h`, só com `$set` de `retryCount = h+1` e `updatedAt`. `DeliveryProcessor.onTransientFailure` o chama logo depois de `outcome.ok()` e antes do retorno, ou seja, antes do ack (linhas 222-224). Com isso, o fence `retryCount ≤ h` do claim recusa a original `h` reentregue e as cópias tardias do token `h+1`. A análise caso a caso está no Summary. Testes de regressão com filtro e update BSON reais: `DeliveryFlowTest.redeliveredOriginalAfterTheConfirmedTokenIsDiscardedWithoutSendingAgain`, `secondCopyOfTheSameTokenIsDiscardedOnceTheNextLevelWasConfirmed`, `redeliveredOriginalStillRetriesWhenTheWorkerDiedBeforeTheRetryPublish` e `NotificationStateServiceTest.retryingWhoseFenceAdvancedIsNotClaimedByTheLevelBelow`. Resta uma janela de um round-trip ao Mongo entre o confirm e o avanço. Ela custa no máximo uma tentativa extra no nível `h` e está documentada (`NotificationStateService.java:84-90`). Ver WR-09 para a robustez do `catch` em volta do avanço.

**File (original):** `notify-worker/src/main/java/dev/notifyhub/worker/processing/NotificationStateService.java:86-93, 160-162`

**Issue (original):** O claim aceita `status ∈ {PENDING, RETRYING, PUBLISH_FAILED}` com `retryCount ≤ h`. `markRetrying` deixa `retryCount = h` de propósito, para a reentrega do original `h` poder refazer a tentativa se o worker cair antes do publish. O claim não distingue esse caso do caso em que o token `h+1` **já foi confirmado**. Cenário: o original `h` volta com `redelivered=true` depois do confirm e reivindica de novo, envia de novo e publica **outro** `h+1`. A duplicação se propaga pelos níveis seguintes.

### WR-02: `notify-retry-count` = `Integer.MAX_VALUE` estoura `RetryPolicy` depois do claim

**Status:** RESOLVIDO no 02-08. Não conta no frontmatter.

**Evidência:** `RetryPolicy.java:30-36` testa `retryCount >= levels.size()` antes de somar, como na correção sugerida. O header continua aceito até `Integer.MAX_VALUE` (`RetryHeaders.java:42-44`), decisão documentada em `RetryHeaders.java:14-19`. A notificação reivindicada nesse nível termina DEAD_LETTERED com uma tentativa e cópia TRANSIENT na DLQ, sem `IndexOutOfBoundsException`. Os testes estão listados no Summary.

**File (original):** `notify-worker/src/main/java/dev/notifyhub/worker/processing/RetryPolicy.java:26-28`

**Issue (original):** `next = MAX + 1` virava `Integer.MIN_VALUE`, `next <= levels.size()` era verdadeiro e `levels.get(next - 1)` lançava fora do `try` de `process`, deixando o documento PROCESSING com `retryCount` máximo.

### WR-03: Thread interrompida continua o fluxo e grava DEAD_LETTERED mesmo com o retry confirmado pelo broker

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulatedChannelAdapter.java:83-85`, `notify-worker/src/main/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisher.java:101-103`

**Issue:** O adapter restaura o flag de interrupção e lança uma transitória. O processador segue com o flag ligado: `markRetrying` pode lançar `MongoInterruptedException`, classificada como UNKNOWN, e `cd.getFuture().get(...)` lança `InterruptedException` **imediatamente**, porque `CompletableFuture.get` consulta o flag antes de esperar. `publish` devolve `timeout()` mesmo que o broker já tenha dado ack. O documento vira DEAD_LETTERED (REPUBLISH_FAILED) com um token `h+1` vivo na fila de delay, e `copyToDlqOrReject` também falha pelo mesmo motivo e rejeita o original. O teste `interruptedWaitIsTimeoutAndRestoresTheInterruptFlag` fixa esse comportamento em vez de detectá-lo.

**Fix:** Na interrupção, abortar sem decidir o resultado: deixar a exceção subir sem ack, para o broker reentregar com `redelivered=true`. Por exemplo, o adapter lança uma exceção não classificada (ou `Thread.interrupted()` é verificado no início de `onFailure`) que o processador relança sem tocar no Mongo. No publisher, tratar `InterruptedException` como resultado "desconhecido", diferente de `timeout`, e não gravar DEAD_LETTERED nesse caso.

### WR-04: O canal do documento e o canal da mensagem são usados de forma intercambiável, sem validação

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java:114, 138, 144, 157, 220, 226, 259, 306`

**Issue:** Só `message.channel()` é comparado com o canal do listener. A renderização, o gateway (adapter, bulkhead e CB) e `metrics.sent` usam `doc.channel()`. A republicação, `metrics.retrying`, `metrics.deadLettered` e `metrics.failed` usam `message.channel()`. Um token com canal divergente do documento (adulterado pelo painel ou republicado errado) é entregue pelo adapter/CB de outro canal, consumido na fila do canal A e conta métricas em canais diferentes no mesmo fluxo.

**Fix:** Logo depois do claim, `if (doc.channel() != listenerChannel)` grava FAILED/UNKNOWN (ou rejeita antes, comparando no filtro do claim com `channel = listenerChannel`). Depois disso, usar uma única variável `channel` em todo o fluxo.

### WR-05: `lastError` da republicação usa mensagem de exceção alheia e não respeita o limite de 300 caracteres

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java:270-272`, `notify-worker/src/main/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisher.java:89-90, 104-106, 122-123`

**Issue:** `FailureClassifier` documenta o contrato "nunca usa `getMessage()` de exceção alheia" e "no máximo 300 caracteres" para `lastError`. `onRepublishFailure` monta a mensagem com `outcome.reason()`, que inclui `AmqpException.getMessage()` e `ExecutionException.getCause().getMessage()`. Esse texto é livre, de tamanho arbitrário, e não passa por `truncate`. Um nack sem motivo ainda grava a string literal `"nack: null"`.

**Fix:** Mapear o `PublishOutcome` para um motivo enumerado (`NACK`, `RETURNED:<replyCode>`, `CONFIRM_TIMEOUT`, `SEND_FAILED:<SimpleClassName>`) e passar a mensagem final pelo mesmo `truncate` do classificador. Por exemplo, expor `FailureClassifier.truncate` como utilitário package-shared.

### WR-06: Renderização "estrita" só vale para seções no WEBHOOK; EMAIL e SMS omitem seções com variável ausente

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateRenderer.java:53-56`

**Issue:** Só o compilador `JSON` usa `strictSections(true)`. Nos compiladores `HTML` e `PLAIN`, uma seção `{{#x}}...{{/x}}` (ou `{{^x}}`) cuja variável não existe é tratada como falsa e some do texto em silêncio. Isso contradiz o Javadoc ("A renderização nunca sai em branco") e TPL-02 ("variável faltando → falha permanente").

**Fix:**
```java
private static final Mustache.Compiler HTML = Mustache.compiler().strictSections(true);
private static final Mustache.Compiler PLAIN = Mustache.compiler().escapeHTML(false).strictSections(true);
```
Acrescentar casos ao `TemplateRendererTest` para seção ausente em EMAIL e SMS.

### WR-07: Destinatários mágicos ligados por padrão no profile base

**File:** `notify-worker/src/main/resources/application.yml:71`

**Issue:** A injeção determinística de falhas fica ativa fora da demo. Qualquer cliente da API pode forçar FAILED, DEAD_LETTERED e, com cerca de 10 `https://fail.test/always-transient`, **abrir o circuit breaker do canal inteiro**. Com o CB aberto, as entregas legítimas de todos os outros clientes são recusadas e gastam níveis da escada (D-05). O Javadoc de `SimulationProperties` trata o recurso como "de demo e de teste", mas o padrão está invertido.

**Fix:** `magic-recipients: ${NOTIFY_SIMULATION_MAGIC_RECIPIENTS:false}` em `application.yml` e `notify.simulation.magic-recipients: true` em `application-demo.yml` e no profile de teste.

### WR-08: A doc promete recuperação por lease, mas nada varre leases vencidos

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/processing/NotificationStateService.java:71-75, 101-103`

**Issue:** O ramo `leaseUntil < now` do claim só é avaliado quando **chega uma mensagem**. Um documento PROCESSING cuja mensagem foi para a DLQ (CR-01) ou foi confirmada como obsoleta nunca mais é avaliado. O lease, sozinho, não garante a recuperação que o Javadoc do claim e o `application.yml:62` ("a recuperação de PROCESSING preso é da Fase 2") descrevem. O 02-08 corrigiu essa promessa só no Javadoc do `DeliveryProcessor`. O do claim e o comentário do `application.yml` continuam iguais.

**Fix:** Um `@Scheduled` (virtual thread) que procura `status=PROCESSING, leaseUntil < now` e republica o token no nível `retryCount` (ou marca DEAD_LETTERED com `lastError` `LEASE_EXPIRED` e cópia na DLQ). Ou documentar explicitamente que a recuperação depende do reprocessamento da Fase 3 (como já foi feito no `DeliveryProcessor`) e ajustar a tabela de transições (ver CR-01).

### WR-09: O avanço do fence só tolera `DataAccessException`: outra `RuntimeException` rejeita a original depois de o token `h+1` já estar confirmado

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java:222-231, 241-248`

**Issue:** O Javadoc de `advanceRetryFence` (linhas 235-240) define a invariante: o avanço "nunca muda o roteamento", porque "o token já está confirmado na fila de delay, e rejeitar a original deixaria a mesma notificação na DLQ e na escada ao mesmo tempo". O `catch` (linha 244), porém, só cobre `DataAccessException`. O avanço é um extra best-effort e não tem efeito no roteamento, mas qualquer outra `RuntimeException` vinda de `mongo.updateFirst` sai de `onTransientFailure` → `onFailure` → `process` (o `try` da linha 136 já terminou) e chega ao container. Com `default-requeue-rejected: false`, a original vai para a DLQ do canal enquanto o token `h+1` continua vivo na fila de delay. Exceções que o `MongoExceptionTranslator` não converte são, por exemplo, o `IllegalStateException("state should be: open")` do driver com o `MongoClient` já fechado, num shutdown em que o `shutdownTimeout` do container estoura com uma mensagem em voo, e erros de mapeamento/conversão do Spring Data. Nesse caso:
- a notificação fica ao mesmo tempo na DLQ e na escada. O reprocessamento da Fase 3 a partir da DLQ cria uma segunda cadeia paralela, com envio duplicado e dois tokens disputando o documento;
- `metrics.retrying` (linha 226) não é contado, embora o retry tenha sido publicado.

Além disso, o `log.warn` da linha 245 não recebe `e`, então a causa (tipo e mensagem do erro do Mongo) se perde no único registro desse caminho.

**Fix:** Capturar `RuntimeException` (nunca `Error`, seguindo o padrão da linha 147) e passar a exceção ao logger:
```java
private void advanceRetryFence(String id, int retryCount, int nextRetryCount) {
    try {
        state.advanceRetryLevel(id, retryCount, nextRetryCount);
    } catch (RuntimeException e) {
        // best-effort: o token h+1 já está confirmado; nada aqui pode mudar o roteamento da original
        log.warn("fence do retry não avançado id={} nivel={}: reentrega do nível {} pode repetir a "
                + "tentativa (at-least-once)", id, nextRetryCount, retryCount, e);
    }
}
```
Acrescentar ao `DeliveryProcessorTest` um caso com `advanceRetryLevel` lançando `IllegalStateException`, verificando que não há exceção, que não há `publishToDlq` e que `retries(1) == 1`.

## Info

### IN-01: O profile demo fixa `wait-duration-in-open-state` e ignora `NOTIFY_CB_OPEN_WAIT`

**File:** `notify-worker/src/main/resources/application-demo.yml:12`
**Issue:** O valor literal `500ms` sobrescreve o placeholder `${NOTIFY_CB_OPEN_WAIT:2500ms}` do profile base. No profile demo a variável de ambiente que o benchmark da Fase 4 deveria usar (D-08) deixa de ter efeito.
**Fix:** `wait-duration-in-open-state: ${NOTIFY_CB_OPEN_WAIT:500ms}`.

### IN-02: Variante sem `body` vira UNKNOWN/UNEXPECTED (NPE) em vez de TEMPLATE_INVALID

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateRenderer.java:85-90, 111-112`
**Issue:** `compiler.compile(null)` lança `NullPointerException`, classificada como bug (UNKNOWN). Um seed com `body` ausente é um erro de template, não do worker.
**Fix:** Validar `variant.body() != null` e lançar `PermanentDeliveryException(ErrorCode.TEMPLATE_INVALID, ...)`. Também validar no `TemplateSeeder.read`.

### IN-03: O casamento do webhook mágico usa o path decodificado

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/channel/MagicRecipients.java:101`
**Issue:** `uri.getPath()` decodifica escapes percentuais. Com isso, `https://fail.test/always%2Dtransient` e `https://fail.test/always-transient%2Fx` casam, embora a regra documentada (T-02-17) seja "sempre exata".
**Fix:** Usar `uri.getRawPath()`.

### IN-04: Assunto de e-mail renderizado sem escape de CR/LF

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateRenderer.java:86`
**Issue:** O assunto usa `PLAIN` sem escape, então uma variável com `\r\n` produz um assunto multilinha. Hoje é inofensivo, porque o envio é simulado, mas vira header injection se um adapter SMTP real for plugado.
**Fix:** Rejeitar ou normalizar `\r` e `\n` no assunto renderizado (falha PERMANENT, ou troca por espaço).

### IN-05: `esperar_todas` não falha rápido em DEAD_LETTERED quando espera SENT

**File:** `scripts/smoke-fase2.sh:84-108, 306, 326`
**Issue:** No teste do bulkhead (6 SMS), uma notificação que esgote a escada só é detectada no timeout de 45s, com a mensagem enganosa "não chegaram a SENT". O helper só aborta cedo em FAILED.
**Fix:** Aceitar uma lista de estados terminais proibidos e abortar em `DEAD_LETTERED` quando o esperado é `SENT`.

### IN-06: `--help` do smoke falha quando o script é chamado por caminho relativo de outro diretório

**File:** `scripts/smoke-fase2.sh:31, 37`
**Issue:** O `cd` da linha 31 acontece antes do parse de argumentos, e `uso()` lê `"${BASH_SOURCE[0]}"`, que é relativo ao diretório original. Com `bash ../x/scripts/smoke-fase2.sh --help`, o awk não acha o arquivo.
**Fix:** Guardar `SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"` antes do `cd` e usar `$SCRIPT` em `uso()`.

### IN-07: `retryCount` do GET num documento RETRYING passou a ter dois significados

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/processing/NotificationStateService.java:43-47, 238-253` (exposto por `notify-api/src/main/java/dev/notifyhub/api/web/dto/NotificationView.java:24, 53`)
**Issue:** Com o avanço do WR-01, um documento RETRYING mostra `retryCount = h+1` (nível do token que espera na fila de delay). Mas mostra `h` (nível da última tentativa, igual a `attempts[-1].retryLevel`) quando o avanço perde o fence ou lança exceção, já que o processor tolera as duas coisas de propósito. Nos estados terminais, `retryCount` continua sendo o nível da última tentativa. Assim, o mesmo campo do contrato da API muda de significado conforme o status e conforme uma corrida. Isso confunde quem lê o GET e o script de demo, e qualquer verificação futura do tipo "`retryCount == attempts[-1].retryLevel`" vai falhar de forma intermitente. Nada no código atual (API, smokes) depende disso, por isso é Info.
**Fix:** Documentar na descrição do campo (OpenAPI/README da Fase 4) que, em RETRYING, `retryCount` é o nível do próximo token e pode ainda não ter avançado, e que o nível da última tentativa está em `attempts[].retryLevel`. Outra opção é expor um campo derivado `lastAttemptLevel`.

### IN-08: Javadoc do `RetryHeaders` promete DEAD_LETTERED para qualquer header acima da escada

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/listener/RetryHeaders.java:14-19`
**Issue:** O texto diz que, sem teto, "o documento é reivindicado, a escada aparece esgotada ... e ele termina num estado terminal visível, DEAD_LETTERED". Isso só vale se a tentativa falhar de forma transitória. Com header acima da escada, o claim grava `retryCount = h` e a tentativa roda normalmente: sucesso vira SENT (com `retryCount` absurdo no GET), falha permanente vira FAILED e documento já terminal ou em nível maior descarta a mensagem. Além disso, o header adulterado reivindica na hora um documento RETRYING cujo token legítimo ainda está na fila de delay (fence `retryCount ≤ h`), o que adianta o envio e torna o token legítimo obsoleto. A decisão de não ter teto é defensável. O Javadoc é que está impreciso sobre o efeito.
**Fix:** Reescrever como: "Sem teto, o documento é reivindicado e a tentativa roda; se falhar transitoriamente, a escada aparece esgotada e ele termina DEAD_LETTERED com cópia TRANSIENT na DLQ. Um header adulterado pode adiantar uma notificação que espera na escada (acesso ao painel é confiável por premissa, T-01-24)."

---

_Reviewed: 2026-10-20T23:44:02Z (incremental `21b504e..38928f4`)_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
