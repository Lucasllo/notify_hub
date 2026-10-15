# Phase 2: Falhas, retry com backoff e resiliência - Pattern Map

**Mapped:** 2026-10-14
**Files analyzed:** 27 (novos + modificados)
**Analogs found:** 25 / 27

Base: CONTEXT.md (D-01..D-17, com D-10/D-15/D-17 ajustados) e RESEARCH.md §"Estrutura recomendada". Todos os analogs abaixo são arquivos rastreados pelo git (`git ls-files`). Caminhos abreviados: `W = notify-worker/src/main/java/dev/notifyhub/worker`, `WT = notify-worker/src/test/java/dev/notifyhub/worker`, `C = notify-common/src/main/java/dev/notifyhub/common`.

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match |
|---|---|---|---|---|
| `C/domain/FailureClass.java` (+UNKNOWN) | model (enum) | — | próprio arquivo | exact |
| `C/domain/AttemptOutcome.java` (+UNKNOWN_FAILURE, D-17) | model (enum) | — | próprio arquivo | exact |
| `C/domain/NotificationStatus.java` (RETRYING→DEAD_LETTERED; claimable com PROCESSING) | model (state table) | — | próprio arquivo | exact |
| `C/messaging/NotifyHeaders.java` (javadoc FAILURE_CLASS cita UNKNOWN) | config/constants | — | próprio arquivo | exact |
| `W/failure/DeliveryFailureException.java` (abstrata, `errorCode()`) | utility (exception) | — | `W/template/TemplateNotFoundException.java` | role-match |
| `W/failure/TransientDeliveryException.java` / `PermanentDeliveryException.java` | utility (exception) | — | `W/channel/SimulatedDeliveryException.java` | exact |
| `W/failure/FailureClassifier.java` + `Classification` (record) + `ErrorCodes` | service (pure function) | transform | `W/listener/RetryHeaders.java` (parser puro) + switch de `SimulationProperties.forChannel` | partial |
| `W/channel/SimulatedDeliveryException.java` (re-parent para Transient) | exception | — | próprio | exact |
| `W/template/TemplateNotFoundException.java` (re-parent para Permanent) | exception | — | próprio | exact |
| `W/channel/ChannelAdapter.java` (nova assinatura com `AttemptContext`) | interface | request-response | próprio | exact |
| `W/channel/AttemptContext.java` (record) | model | — | `SimulationProperties.ChannelSimulation` (record com compact ctor) | role-match |
| `W/channel/MagicRecipients.java` (parser puro D-01..D-03) | utility | transform | `W/listener/RetryHeaders.java` | role-match |
| `W/channel/SimulatedChannelAdapter.java` (mágicos + contexto) | service (adapter) | request-response | próprio | exact |
| `W/channel/SimulationProperties.java` (+flag magic-recipients opcional) | config | — | próprio | exact |
| `W/resilience/ChannelGateway.java` (bulkhead → CB → adapter) | service | request-response | `W/processing/DeliveryProcessor.java` (`indexByChannel`, EnumMap) + `W/channel/SimulationConfiguration.java` | role-match |
| `W/resilience/NonTransientFailurePredicate.java` | utility | — | — | none (RESEARCH Padrão 5) |
| `W/messaging/DeliveryRoutingPublisher.java` (`publishToRetry`, `publishToDlq`, confirm) | service (publisher) | event-driven/pub | `notify-api/.../api/messaging/NotificationPublisher.java` | exact |
| `W/metrics/DeliveryMetrics.java` (pré-registro, EnumMap) | service | event-driven | — | none (RESEARCH "Métricas com pré-registro") |
| `W/processing/DeliveryProcessor.java` (classificador + ramos) | service | event-driven | próprio | exact |
| `W/processing/NotificationStateService.java` (markRetrying/markFailed/markDeadLettered/…AfterRepublishFailure; claim com redelivered/lease) | service | CRUD | próprio (`markSent`, `claim`) | exact |
| `W/processing/RetryPolicy.java` (função pura nível → TTL/próximo) | utility | transform | `W/listener/RetryHeaders.java` | role-match |
| `W/template/TemplateRenderer.java` (3 compiladores, estrito) | service | transform | próprio | exact |
| `W/template/JsonValueEscaper.java`, `MissingVariableException.java` | utility | transform | `TemplateRenderer` constantes de compilador / `TemplateNotFoundException` | role-match |
| `W/listener/ChannelListeners.java` (+ header `redelivered`) | controller (listener) | event-driven | próprio | exact |
| `notify-worker/src/main/resources/application.yml` (+resilience4j, prometheus, mustache excl.) | config | — | próprio | exact |
| `notify-worker/pom.xml` (+resilience4j-spring-boot3, micrometer-registry-prometheus) ; `pom.xml` pai (+resilience4j-bom 2.4.0) | config | — | próprio | exact |
| Testes: `WT/failure/FailureClassifierTest`, `WT/channel/MagicRecipientsTest`, `WT/messaging/DeliveryRoutingPublisherTest`, `WT/metrics/DeliveryMetricsTest`, `WT/resilience/ChannelGatewayTest`, `WT/config/ResilienceSettingsTest`; atualizar `NotificationStateServiceTest`, `DeliveryProcessorTest`, `DeliveryFlowTest`, `TemplateRendererTest`, `SimulatedChannelAdapterTest`, `ChannelListenersTest`, `WorkerSettingsTest`, `C/.../NotificationStatusTest` | test | — | `WT/processing/NotificationStateServiceTest.java`, `WT/config/WorkerSettingsTest.java`, `notify-api/src/test/.../NotificationPublisherTest.java` | exact |

## Pattern Assignments

### `W/processing/NotificationStateService.java` — novos `markRetrying`, `markFailed`, `markDeadLettered`, `markDeadLetteredAfterRepublishFailure`; claim estendido

**Analog:** o próprio `markSent` (linhas 85-108). Copiar o molde fence + `$set` + `$push` com `$slice` + checagem de `getModifiedCount()`:

```java
Query fence = query(where(Fields.ID).is(id)
        .and(Fields.STATUS).is(NotificationStatus.PROCESSING)
        .and(Fields.RETRY_COUNT).is(retryCount));
Update sent = new Update()
        .set(Fields.STATUS, NotificationStatus.SENT)
        ...
        .unset(Fields.LEASE_UNTIL)
        .inc(Fields.ATTEMPT_COUNT, 1);
// $slice exige $each: slice(...) antes de each(...) monta os dois modificadores do $push
sent.push(Fields.ATTEMPTS).slice(-50).each(attempt);
UpdateResult result = mongo.updateFirst(fence, sent, NotificationDocument.class);
if (result.getModifiedCount() == 0) {
    log.warn("fence perdido: ...", id, retryCount);
    return false;
}
return true;
```

Regras para os novos métodos:
- RETRYING/FAILED/DEAD_LETTERED: mesmo fence (`PROCESSING` + `retryCount` exato), `$set lastError` (`LastError(code, message, failureClass, at)`) em vez de `unset`, `inc attemptCount`, `$push attempts`. **Não** incrementar `retryCount` no RETRYING (RESEARCH Armadilha 2): o fence do claim usa `retryCount ≤ h`.
- `markDeadLetteredAfterRepublishFailure`: fence `status = RETRYING` + `retryCount`; só `$set status, lastError(REPUBLISH_FAILED), updatedAt` — sem `$push` (D-17). Status de origem derivado da tabela (`RETRYING.canTransitionTo(DEAD_LETTERED)`), nunca hard-coded fora dela.
- Claim (linhas 61-74): trocar o filtro por `$or` entre `status in claimableForDelivery()` e (`status = PROCESSING` AND (`redelivered` OU `leaseUntil < now`)), mantendo `retryCount lte`. Assinatura: `claim(String id, int retryCount, boolean redelivered)`.

### `C/domain/NotificationStatus.java`

**Analog:** próprio, linhas 25-40 e 60-64. Adicionar `table.put(RETRYING, EnumSet.of(PROCESSING, DEAD_LETTERED));` com comentário pt-BR no estilo da linha 30. `claimableForDelivery()` mantém o remove de PROCESSING (o reclaim fica num ramo separado do filtro, condicionado a redelivered/lease); atualizar o javadoc "Fase 1". Teste: `notify-common/src/test/.../NotificationStatusTest.java`.

### `C/domain/FailureClass.java`, `AttemptOutcome.java`

Acrescentar `UNKNOWN` / `UNKNOWN_FAILURE`; atualizar javadoc (AttemptOutcome linha 4 diz "Na Fase 1 só SUCCESS").

### `W/messaging/DeliveryRoutingPublisher.java`

**Analog:** `notify-api/src/main/java/dev/notifyhub/api/messaging/NotificationPublisher.java` (linhas 30-58) — copiar inteiro o esqueleto convertAndSend + `cd.getFuture().get(timeout)` + checar `getReturned()` antes de `isAck()` + mapear Timeout/Interrupted/Execution:

```java
try {
    CorrelationData.Confirm confirm = cd.getFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    ReturnedMessage returned = cd.getReturned();
    if (returned != null) {
        return PublishResult.unroutable(returned.getReplyText());
    }
    return confirm.isAck() ? PublishResult.acked() : PublishResult.nacked(confirm.getReason());
} catch (TimeoutException e) { ... } catch (InterruptedException e) {
    Thread.currentThread().interrupt(); ...
} catch (ExecutionException e) { ... }
```

Diferenças obrigatórias:
- `new CorrelationData()` (UUID), **não** o `notificationId` (linha 32 da API usa o id; RESEARCH Armadilha 8).
- Retry: exchange `NotifyTopology.RETRY_EXCHANGE`, routing key `channel.retryKey(ttl)`, header `NotifyHeaders.RETRY_COUNT = retryCount+1`, `messageId` = notificationId (molde linhas 34-38).
- DLQ (D-10): exchange `NotifyTopology.DLX_EXCHANGE`, routing key `channel.dlqKey()`, header `NotifyHeaders.FAILURE_CLASS`; mantém `RETRY_COUNT` atual.
- Retorno: um record de resultado próprio do worker (molde `notify-api/.../PublishResult.java`) ou booleano + motivo; o processor converte falha em `AmqpRejectAndDontRequeueException` (fallback).

### `W/processing/DeliveryProcessor.java`

**Analog:** próprio. Manter guardas (linhas 83-90), claim vazio → return (93-97), `logThreadOnce`. Envolver linhas 100-114 em `try { template → render → gateway.send } catch (RuntimeException e) { Classification c = classifier.classify(e); ... }`. Medição de duração já existe (103-106): reutilizar para o timer só em torno da chamada do gateway (D-15). Construção de `AttemptEntry` (108-109) é o molde para as tentativas de falha (`durationMs≈0` quando o adapter não foi chamado, D-14). Log sempre com `RecipientRules.mask(...)` (111-113). Substituir `Map<Channel, ChannelAdapter>` por `ChannelGateway`. Atualizar javadoc da classe (linhas 28-39 descrevem Fase 1).

### `W/resilience/ChannelGateway.java`

**Analog:** `DeliveryProcessor.indexByChannel` (linhas 62-75) — EnumMap por canal e falha no startup se faltar canal:

```java
Map<Channel, ChannelAdapter> byChannel = new EnumMap<>(Channel.class);
for (ChannelAdapter adapter : adapters) {
    if (byChannel.putIfAbsent(adapter.channel(), adapter) != null) {
        throw new IllegalStateException("mais de um adapter para o canal " + adapter.channel());
    }
}
Set<Channel> missing = EnumSet.allOf(Channel.class);
missing.removeAll(byChannel.keySet());
if (!missing.isEmpty()) { throw new IllegalStateException("canais sem adapter: " + missing); }
```

Mesmo molde para resolver `bulkheadRegistry.find(channel.id())` / `circuitBreakerRegistry.find(channel.id())` no construtor (nunca `registry.circuitBreaker(name)`, RESEARCH Padrão 5). Composição: `Bulkhead.decorateRunnable(bh, CircuitBreaker.decorateRunnable(cb, () -> adapter.send(...)))`.

### `W/channel/ChannelAdapter.java`, `SimulatedChannelAdapter.java`, `AttemptContext.java`, `MagicRecipients.java`

- Interface: `void send(String recipient, RenderedContent content, AttemptContext context);` (linha 20).
- Adapter (linhas 48-63): manter sleep fora de `synchronized` e tratamento de `InterruptedException`; antes do sorteio de `failureRate`, consultar `MagicRecipients.parse(channel, recipient)` e lançar `SimulatedDeliveryException` (transitória) ou uma `PermanentDeliveryException` com código `SIMULATED_PERMANENT`, **depois** da latência (D-04).
- `AttemptContext`: record com compact constructor validando (molde `ChannelSimulation` linhas 137-151 de `SimulationProperties`).
- `MagicRecipients`: classe final com métodos estáticos puros, como `RetryHeaders`.

### `W/failure/*` exceptions

**Analog:** `SimulatedDeliveryException` (2 ctors message / message+cause) e `TemplateNotFoundException` (campo extra + fábricas estáticas `missing(...)`/`missingVariant(...)`, linhas 17-25). Base abstrata com `errorCode()`; `SimulatedDeliveryException extends TransientDeliveryException`; `TemplateNotFoundException extends PermanentDeliveryException` mantendo as fábricas.

### `W/template/TemplateRenderer.java`, `JsonValueEscaper.java`

**Analog:** próprio, linhas 29-30 (compiladores imutáveis estáticos) e 48-53 (switch por canal). Adicionar `private static final Mustache.Compiler JSON = Mustache.compiler().withEscaper(new JsonValueEscaper()).strictSections(true);` — nunca encadear `escapeHTML(...)` depois (RESEARCH Armadilha 7). WEBHOOK: após `execute`, validar com `ObjectMapper` (rejeitar trailing tokens, Armadilha 6) → `PermanentDeliveryException(INVALID_WEBHOOK_PAYLOAD)`. Checagem de `requiredVariables` antes de executar → `MissingVariableException`.

### `W/listener/ChannelListeners.java`

**Analog:** próprio, linhas 37-43. Acrescentar a cada método:

```java
@Header(name = AmqpHeaders.REDELIVERED, required = false) Boolean redelivered
```

e passar `Boolean.TRUE.equals(redelivered)` ao processor. Nunca `Object` (javadoc linhas 22-26).

### `notify-worker/src/main/resources/application.yml`

**Analog:** próprio. Comentários pt-BR curtos acima de cada chave (estilo linhas 202, 209, 213, 216). Mudanças: `management.endpoints.web.exposure.include: health,info,prometheus` (linha 223); bloco `resilience4j.circuitbreaker.instances.{email,sms,webhook}` + `resilience4j.bulkhead.instances.*` com ENV `${NOTIFY_CB_*:...}` (D-08); `notify.retry`/níveis se ainda não existir em `TopologyProperties`; `spring.autoconfigure.exclude: org.springframework.boot.autoconfigure.mustache.MustacheAutoConfiguration` (deferred da Fase 1). Simulação (linhas 237-249) segue o mesmo formato por canal.

### `notify-worker/pom.xml` / `pom.xml`

Seguir o bloco `<dependency>` sem versão (linhas 19-41); versão do Resilience4j só via `resilience4j-bom` 2.4.0 no `<dependencyManagement>` do pai. Adicionar `micrometer-registry-prometheus` (gerenciado). Sem `spring-boot-starter-aop` (API programática).

## Shared Patterns

### Transição condicional derivada da tabela
**Source:** `C/domain/NotificationStatus.java` (linhas 11-12, 43-54) + `NotificationStateService.markSent`
**Apply to:** todos os `mark*` novos e o claim. Nunca filtro escrito à mão fora da tabela; sempre `updateFirst`/`findAndModify`, nunca `save()`.

### Publish com confirm síncrono
**Source:** `notify-api/.../messaging/NotificationPublisher.java` linhas 30-58
**Apply to:** `DeliveryRoutingPublisher` (retry e DLQ). Returned antes de `isAck()`; `CorrelationData` com UUID.

### Logging e mascaramento
**Source:** `DeliveryProcessor` linhas 43, 111-113; `SimulatedChannelAdapter` 61-62
**Apply to:** processor, adapter, gateway, publisher. SLF4J `LoggerFactory.getLogger(X.class)`, mensagens pt-BR, `RecipientRules.mask(channel, recipient)` sempre.

### Records sem Lombok, validação em compact constructor
**Source:** `W/channel/SimulationProperties.java` linhas 46-88
**Apply to:** `AttemptContext`, `Classification`, propriedades novas (`@ConfigurationProperties` + `@DefaultValue`).

### Beans por canal
**Source:** `W/channel/SimulationConfiguration.java` (`@Configuration(proxyBeanMethods = false)`, `@EnableConfigurationProperties`, um `@Bean` por canal)
**Apply to:** configuração de gateway/métricas se precisar de beans novos.

## Testing Patterns

- **Filtros/updates Mongo sem Docker:** `WT/processing/NotificationStateServiceTest.java` — `mock(MongoTemplate.class)`, `ArgumentCaptor<Query>`/`<UpdateDefinition>`, `MongoBson.query(...)` e `assertThat(filter.keySet()).containsExactly(...)`. Aplicar a claim com redelivered/lease (D-12) e a cada `mark*`.
- **Config lida do YAML sem contexto:** `WT/config/WorkerSettingsTest.java` — `YamlPropertySourceLoader` + `base.getProperty("...")`. Aplicar para chaves do resilience4j (wait do CB ≤ 1º nível, `maxWaitDuration=0`, `maxConcurrentCalls < concurrency`), exposição `prometheus` e exclusão do Mustache.
- **Publisher com confirm:** `notify-api/src/test/java/dev/notifyhub/api/messaging/NotificationPublisherTest.java` como molde para `DeliveryRoutingPublisherTest`.
- **Renderer:** estender `WT/template/TemplateRendererTest.java` (TST-01, sem Docker).
- **Métricas:** `SimpleMeterRegistry` em teste unitário (sem analog no repo).

## No Analog Found

| File | Role | Data Flow | Reason |
|---|---|---|---|
| `W/metrics/DeliveryMetrics.java` | service | event-driven | Nenhum código Micrometer no repo; usar RESEARCH §"Métricas com pré-registro" (linhas ~700-722) e D-15 (nomes/tags fechados) |
| `W/resilience/NonTransientFailurePredicate.java` | utility | — | Sem Resilience4j no repo; usar RESEARCH Padrão 5 (`t -> !(t instanceof TransientDeliveryException)`, classe pública com ctor sem args) |

## Metadata

**Analog search scope:** `notify-worker/src`, `notify-common/src`, `notify-api/src/main/.../messaging`, POMs, `application.yml`
**Files scanned:** ~90 (lista do `git ls-files`), 14 lidos
**Pattern extraction date:** 2026-10-14
