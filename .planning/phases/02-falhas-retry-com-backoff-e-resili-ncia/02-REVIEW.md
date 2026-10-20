---
phase: 02-falhas-retry-com-backoff-e-resili-ncia
reviewed: 2026-10-20T00:00:00Z
depth: standard
files_reviewed: 51
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
  - notify-worker/src/test/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisherTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/metrics/DeliveryMetricsTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/processing/NotificationStateServiceTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/processing/RetryPolicyTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/resilience/ChannelGatewayTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/resilience/ResilienceFixtures.java
  - notify-worker/src/test/java/dev/notifyhub/worker/template/SeedTemplatesTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java
  - pom.xml
  - scripts/smoke-fase2.sh
  - scripts/smoke-lib.sh
findings:
  critical: 1
  warning: 8
  info: 6
  total: 15
status: issues_found
---

# Phase 2: Code Review Report

**Reviewed:** 2026-10-20T00:00:00Z
**Depth:** standard
**Files Reviewed:** 51
**Status:** issues_found

## Summary

Revisei o caminho de falha da Fase 2 de ponta a ponta: listener → claim → template/render → gateway (bulkhead → CB → adapter) → classificador → atualização com fence → publicação confirmada para retry/DLQ, junto com as métricas, a configuração e os smokes. O caminho feliz e os ramos classificados (transitório, permanente, desconhecido, republicação falha) estão bem cercados e bem testados com mocks.

Os defeitos estão nas bordas que os testes não cobrem:

- Falhas de infraestrutura (Mongo indisponível no claim ou no update de resultado) e mensagens venenosas estacionam a mensagem na DLQ com o documento em estado **não terminal**. Nem a tabela de transições nem o reprocessamento planejado para a Fase 3 conseguem tirar o documento desse estado. Isso fere o Core Value.
- O claim aceita um segundo token do mesmo nível quando o documento está RETRYING. Isso gera envio duplicado e multiplica tokens na escada.
- Um header `notify-retry-count` = `Integer.MAX_VALUE` estoura `RetryPolicy`.
- O caminho de thread interrompida continua fazendo I/O e marca a notificação como DEAD_LETTERED mesmo quando o broker deu ack.
- Pequenas violações do contrato de PII/tamanho do `lastError`, da renderização "estrita" e do padrão de configuração dos destinatários mágicos.

## Narrative Findings (AI reviewer)

## Critical Issues

### CR-01: Falha do Mongo no claim ou no update de resultado deixa o documento preso num estado não reprocessável, com a mensagem na DLQ

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java:53-54, 114, 150, 205, 228, 262` (com `notify-common/src/main/java/dev/notifyhub/common/domain/NotificationStatus.java:27-36` e `NotificationStateService.java:84-102`)

**Issue:** O Javadoc afirma: "falha do Mongo no claim ou no update de resultado ... sobe para o container (reject → DLQ) e o documento continua recuperável". Isso não é verdade:

1. **Claim falha** (`state.claim`, linha 114, lança `DataAccessResourceFailureException`): o documento continua PENDING ou RETRYING e a mensagem vai para a DLQ sem `x-failure-class`. Nenhuma métrica é incrementada.
2. **Update de resultado falha** (`markSent` na linha 150, `markRetrying` na 205, `markDeadLettered` na 228, `markFailed` na 262): o documento fica PROCESSING com lease de 5m e a mensagem vai para a DLQ. No caso do `markSent`, o envio já aconteceu.
3. A tabela de `NotificationStatus` só permite reprocessar a partir de FAILED ou DEAD_LETTERED (`FAILED → PENDING`, `DEAD_LETTERED → PENDING`). O reprocessamento da Fase 3 (ADM-01: "basicGet, reset no Mongo, republish, ack; **para na primeira falha**") vai falhar o reset desses documentos. Esse reset é derivado da tabela. Resultado: a mensagem fica na cabeça da DLQ e bloqueia o reprocessamento de todo o canal.
4. Se alguém republicar a mensagem crua na fila principal antes de o lease vencer, o claim de um documento PROCESSING exige `redelivered=true` ou `leaseUntil < now`. Uma mensagem republicada não tem `redelivered`, então o claim volta vazio. A mensagem é confirmada como "duplicada/obsoleta descartada" (linha 116) e o documento fica PROCESSING para sempre, sem mensagem em fila nenhuma. Isso é uma **perda silenciosa**.

A mesma classe de problema ocorre com toda rejeição pré-claim: header de retry inválido (`RetryHeaders.parse`) e canal divergente (linhas 104-111). O documento fica PENDING e a mensagem vai para a DLQ.

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
- corrigir o Javadoc das linhas 53-54.

## Warnings

### WR-01: Token repetido do mesmo nível reivindica de novo um documento RETRYING (envio duplicado e tokens multiplicados)

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/processing/NotificationStateService.java:86-93, 160-162`

**Issue:** O claim aceita `status ∈ {PENDING, RETRYING, PUBLISH_FAILED}` com `retryCount ≤ h`. `markRetrying` deixa `retryCount = h` de propósito, para a reentrega do original `h` poder refazer a tentativa se o worker cair antes do publish. O claim não distingue esse caso do caso em que o token `h+1` **já foi confirmado**.

Cenário: o worker grava RETRYING(h), o broker confirma `h+1` e o canal cai antes do ack, o que é coberto pela "Pergunta em aberto 6". O original `h` volta com `redelivered=true` e reivindica de novo (RETRYING, `h ≤ h`). O worker envia de novo, falha e publica **outro** `h+1`. Agora há dois tokens `h+1`. Se o primeiro terminar RETRYING(h+1) antes de o segundo sair do delay, o segundo também reivindica (`h+1 ≤ h+1`) e envia de novo. A duplicação se propaga pelos níveis seguintes e infla `attemptCount`, o que também adianta `fail-until-reprocess`.

**Fix:** Registrar no Mongo que o token do próximo nível foi confirmado (`$set nextRetryPublished: h+1` depois do confirm), e no ramo RETRYING do claim exigir `retryCount < h OR nextRetryPublished não existe/≠ h+1`. Alternativa: o claim de RETRYING exige `retryCount == h-1`, exceto quando `redelivered && nextRetryPublished ausente`. Acrescentar um teste de fluxo com o original redelivered depois do confirm.

### WR-02: `notify-retry-count` = `Integer.MAX_VALUE` estoura `RetryPolicy` depois do claim

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/processing/RetryPolicy.java:26-28` (entrada aceita por `listener/RetryHeaders.java:42`)

**Issue:** `RetryHeaders.parse` aceita até `Integer.MAX_VALUE`, e o modelo de ameaça (T-01-24) prevê header publicado pelo painel. Com esse valor, o claim casa (`retryCount ≤ MAX`) e grava `retryCount = MAX_VALUE`. Na primeira falha transitória, `next = MAX + 1` vira `Integer.MIN_VALUE`, `next <= levels.size()` é verdadeiro e `levels.get(next - 1)` (= `get(Integer.MAX_VALUE)`) lança `IndexOutOfBoundsException` fora do `try` de `process`. A mensagem vai para a DLQ e o documento fica PROCESSING com `retryCount` máximo. Nenhuma mensagem legítima volta a casar o fence, e o documento fica preso como no CR-01.

**Fix:**
```java
public static RetryDecision decide(int retryCount, List<Duration> levels) {
    if (retryCount >= levels.size()) {
        return new RetryDecision(true, retryCount, null);
    }
    int next = retryCount + 1;
    return new RetryDecision(false, next, levels.get(next - 1));
}
```
Também vale limitar `RetryHeaders` a um teto razoável (por exemplo 1000) e rejeitar antes do claim.

### WR-03: Thread interrompida continua o fluxo e grava DEAD_LETTERED mesmo com o retry confirmado pelo broker

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulatedChannelAdapter.java:83-85`, `notify-worker/src/main/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisher.java:101-103`

**Issue:** O adapter restaura o flag de interrupção e lança uma transitória. O processador segue com o flag ligado: `markRetrying` pode lançar `MongoInterruptedException`, classificada como UNKNOWN, e `cd.getFuture().get(...)` lança `InterruptedException` **imediatamente**, porque `CompletableFuture.get` consulta o flag antes de esperar. `publish` devolve `timeout()` mesmo que o broker já tenha dado ack. O documento vira DEAD_LETTERED (REPUBLISH_FAILED) com um token `h+1` vivo na fila de delay, e `copyToDlqOrReject` também falha pelo mesmo motivo e rejeita o original. O teste `interruptedWaitIsTimeoutAndRestoresTheInterruptFlag` fixa esse comportamento em vez de detectá-lo.

**Fix:** Na interrupção, abortar sem decidir o resultado: deixar a exceção subir sem ack, para o broker reentregar com `redelivered=true`. Por exemplo, o adapter lança uma exceção não classificada (ou `Thread.interrupted()` é verificado no início de `onFailure`) que o processador relança sem tocar no Mongo. No publisher, tratar `InterruptedException` como resultado "desconhecido", diferente de `timeout`, e não gravar DEAD_LETTERED nesse caso.

### WR-04: O canal do documento e o canal da mensagem são usados de forma intercambiável, sem validação

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java:108, 132, 138, 151, 212, 216, 233, 280`

**Issue:** Só `message.channel()` é comparado com o canal do listener. A renderização, o gateway (adapter, bulkhead e CB) e `metrics.sent` usam `doc.channel()`. A republicação, `metrics.retrying`, `metrics.deadLettered` e `metrics.failed` usam `message.channel()`. Um token com canal divergente do documento (adulterado pelo painel ou republicado errado) é entregue pelo adapter/CB de outro canal, consumido na fila do canal A e conta métricas em canais diferentes no mesmo fluxo.

**Fix:** Logo depois do claim, `if (doc.channel() != listenerChannel)` grava FAILED/UNKNOWN (ou rejeita antes, comparando no filtro do claim com `channel = listenerChannel`). Depois disso, usar uma única variável `channel` em todo o fluxo.

### WR-05: `lastError` da republicação usa mensagem de exceção alheia e não respeita o limite de 300 caracteres

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java:244-247`, `notify-worker/src/main/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisher.java:89-90, 104-106, 122-123`

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

**File:** `notify-worker/src/main/java/dev/notifyhub/worker/processing/NotificationStateService.java:66-71, 88-90`

**Issue:** O ramo `leaseUntil < now` do claim só é avaliado quando **chega uma mensagem**. Um documento PROCESSING cuja mensagem foi para a DLQ (CR-01, WR-02) ou foi confirmada como obsoleta nunca mais é avaliado. O lease, sozinho, não garante a recuperação que o Javadoc e o `application.yml:62` ("a recuperação de PROCESSING preso é da Fase 2") descrevem.

**Fix:** Um `@Scheduled` (virtual thread) que procura `status=PROCESSING, leaseUntil < now` e republica o token no nível `retryCount` (ou marca DEAD_LETTERED com `lastError` `LEASE_EXPIRED` e cópia na DLQ). Ou documentar explicitamente que a recuperação depende do reprocessamento da Fase 3 e ajustar a tabela de transições (ver CR-01).

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

---

_Reviewed: 2026-10-20T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
