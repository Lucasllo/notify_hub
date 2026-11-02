# Fase 3: Reprocessamento, API operacional e testes de integração - Mapa de padrões

**Mapeado em:** 2026-10-28
**Arquivos analisados:** 38 (novos + modificados)
**Analogs encontrados:** 30 / 38 (os 8 sem analog estão todos no módulo novo `notify-it`, mais o `OpenApiConfiguration`)

Todos os caminhos de analog abaixo são arquivos rastreados pelo git (conferido com `git ls-files`). Prefixos usados:
- `api/` = `notify-api/src/main/java/dev/notifyhub/api/`
- `common/` = `notify-common/src/main/java/dev/notifyhub/common/`
- `worker/` = `notify-worker/src/main/java/dev/notifyhub/worker/`

## Classificação dos arquivos

| Arquivo novo/modificado | Papel | Fluxo de dados | Analog mais próximo | Qualidade |
|---|---|---|---|---|
| `common/domain/NotificationStatus.java` (M) | model | transform (tabela) | ele mesmo, linhas 26-42 | exact |
| `common/domain/AttemptOutcome.java` (M) | model | — | ele mesmo | exact |
| `common/persistence/NotificationDocument.java` (M) | model | CRUD | ele mesmo (`Fields`, `newPending`) | exact |
| `common/persistence/MongoIndexInitializer.java` (M) | config | batch (startup) | ele mesmo, linhas 41-54 | exact |
| `worker/metrics/DeliveryMetrics.java` (M) | utility | metrics | ele mesmo, linhas 80-81 | exact |
| `worker/channel/MagicRecipients.java`, `common/domain/RecipientRules.java` (M, só Javadoc) | utility | — | eles mesmos | exact |
| `api/admin/AdminProperties.java` | config | — | `api/messaging/PublishProperties.java` | exact |
| `api/admin/AdminApiKeyInterceptor.java` | middleware | request-response | nenhum interceptor; erro como `api/application/PublishFailedException.java` | partial |
| `api/admin/AdminWebConfiguration.java` | config | — | `api/config/ApiConfiguration.java` | role-match |
| `api/admin/AdminController.java` | controller | request-response | `api/web/NotificationController.java` | exact |
| `api/admin/DlqReprocessService.java` | service | batch / pull AMQP + CRUD | `worker/processing/NotificationStateService.java` (Mongo) + `api/messaging/NotificationPublisher.java` (AMQP) | role-match |
| `api/admin/ReprocessOutcome.java` (records + motivos) | model | — | `worker/messaging/DeliveryRoutingPublisher.PublishOutcome` + `api/messaging/PublishResult.java` | exact |
| `api/admin/AdminUnauthorizedException`, `ReprocessInterruptedException` | utility (erro) | request-response | `api/application/PublishFailedException.java` | exact |
| `api/application/NotificationService.java` (M) | service | CRUD | ele mesmo | exact |
| `api/application/NotificationRepublisher.java` | service | CRUD + publish | `NotificationService.markPublishFailed` (linhas 71-86) | exact |
| `api/application/NotificationResetService.java` (ou dentro do reprocess) | service | CRUD (findAndModify) | `worker/processing/NotificationStateService.claim` (linhas 97-115) | exact |
| `api/application/RequestHasher.java` | utility | transform | nenhum direto (padrão RESEARCH Padrão 4) | partial |
| `api/application/IdempotencyKeyReusedException.java` | utility (erro 422) | request-response | `api/application/PublishFailedException.java` | exact |
| `api/messaging/NotificationPublisher.java` (M, `CorrelationData`) | service | publish | `worker/messaging/DeliveryRoutingPublisher.java:81` | exact |
| `api/messaging/DlqCopyPublisher.java` | service | publish | `worker/messaging/DeliveryRoutingPublisher.java` (`publishToDlq` + `publish`) | exact |
| `api/web/NotificationController.java` (M) | controller | request-response | ele mesmo | exact |
| `api/web/ApiExceptionHandler.java` (M, `handleTypeMismatch`) | middleware | request-response | ele mesmo, `handleHttpMessageNotReadable` (linhas 49-57) | exact |
| `api/web/ProblemTypes.java` (M) | config | — | ele mesmo | exact |
| `api/web/OpenApiConfiguration.java` | config | — | nenhum (RESEARCH Padrão 8) | none |
| `api/web/dto/NotificationSummary.java`, `PageResponse.java`, `NotificationStats.java` | model (DTO) | transform | `api/web/dto/NotificationView.java` | exact |
| `notify-api/pom.xml`, `notify-worker/pom.xml` (M: springdoc, classifier `exec`) | config | — | eles mesmos | exact |
| `pom.xml` pai (M: módulo `notify-it`) | config | — | ele mesmo, linhas 26-30 | exact |
| `notify-api/src/main/resources/application.yml` / `application-demo.yml` (M: `notify.admin.*`) | config | — | bloco `notify.publish` (linha 39) | exact |
| `scripts/smoke-lib.sh`, `scripts/smoke-fase1.sh` (M: `-exec.jar`) | config | — | `smoke-lib.sh:20-21`, `smoke-fase1.sh:228-229` | exact |
| Testes unitários API novos/alterados (`NotificationApiFlowTest`, `NotificationServiceTest`, `AdminControllerTest`, `DlqReprocessServiceTest`, ...) | test | — | `notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java`, `.../application/NotificationServiceTest.java` | exact |
| Testes common alterados (`NotificationStatusTest`, `MongoIndexInitializerTest`), worker (`DeliveryMetricsTest`) | test | — | eles mesmos | exact |
| `notify-it/pom.xml` | config | — | `notify-api/pom.xml` (estrutura) | partial |
| `notify-it/.../support/ItContainers.java`, `ItContainersConfig.java`, `NotifyHubApps.java` | test (harness) | — | nenhum | none |
| `notify-it/.../HarnessSmokeIT`, `ReprocessFlowIT`, `PoisonMessageIT`, `UnroutablePublishIT`, `ConcurrentDuplicateIT`, `ApiOperationsIT` | test (IT) | event-driven | nenhum IT existe; asserções no estilo de `NotificationApiFlowTest` | none |
| `notify-it/src/test/resources/it/{shared,api,worker}/application-it.yml` | config | — | `notify-worker/src/main/resources/application-demo.yml` (overlay de profile) | role-match |

## Atribuição de padrões

### `common/domain/NotificationStatus.java` (model, tabela de transições)

**Analog:** ele mesmo, linhas 26-37. Acrescentar `PENDING` aos destinos de PENDING, RETRYING e PROCESSING, com comentário no mesmo estilo (`// ... -> PENDING: reprocessamento da DLQ (Fase 3, D-01)`):
```java
table.put(PENDING, EnumSet.of(PROCESSING, PUBLISH_FAILED));
// PENDING: republicação administrativa (Fase 3)
table.put(PUBLISH_FAILED, EnumSet.of(PROCESSING, PENDING));
// PROCESSING -> PROCESSING: reclaim de uma entrega presa (Fase 2)
table.put(PROCESSING, EnumSet.of(SENT, RETRYING, FAILED, DEAD_LETTERED, PROCESSING));
table.put(RETRYING, EnumSet.of(PROCESSING, DEAD_LETTERED));
```
`sourcesOf(...)` (linhas 49-55) deriva o filtro do reset; `claimableForDelivery()`/`reclaimableWhenStuck()` não mudam. Atualizar `NotificationStatusTest`.

### `common/persistence/MongoIndexInitializer.java` (config, startup)

**Analog:** ele mesmo, linhas 41-54. Acrescentar à `List.of(...)` no mesmo molde, e atualizar o Javadoc (lista `<ul>`) e o `MongoIndexInitializerTest` (3 → 5 índices):
```java
ops.createIndex(new Index()
        .on(STATUS, ASC).on(CHANNEL, ASC).on(CREATED_AT, DESC).on(ID, DESC)
        .named("status_channel_createdAt")),
```
Novos: `status_createdAt` = `(STATUS ASC, CREATED_AT DESC, ID DESC)` e `idempotencyKey_unique` com `.unique().partial(PartialIndexFilter.of(where(IDEMPOTENCY_KEY).exists(true)))`.

### `common/persistence/NotificationDocument.java` (model)

Acrescentar componentes `idempotencyKey`, `requestHash` ao record (linha 39+), constantes em `Fields` (linhas 66-80, ex.: `IDEMPOTENCY_KEY = "idempotencyKey"`, `REQUEST_HASH = "requestHash"`) e uma sobrecarga de `newPending(...)` (linha 58) que recebe chave e hash. Não gravar `null` explícito (o índice parcial usa `$exists`). Atualizar `NotificationDocumentMappingTest`.

### `worker/metrics/DeliveryMetrics.java` (M)

Linhas 80-81, trocar `AttemptOutcome.values()` por conjunto explícito:
```java
Map<AttemptOutcome, Timer> durations = new EnumMap<>(AttemptOutcome.class);
for (AttemptOutcome outcome : AttemptOutcome.values()) {   // -> EnumSet.of(SUCCESS, TRANSIENT_FAILURE, PERMANENT_FAILURE, UNKNOWN_FAILURE)
```

---

### `api/admin/AdminProperties.java` (config)

**Analog:** `api/messaging/PublishProperties.java` (arquivo inteiro, 17 linhas):
```java
@ConfigurationProperties("notify.publish")
public record PublishProperties(
        @DefaultValue("5s") Duration confirmTimeout,
        @DefaultValue("5s") Duration retryAfter) {
}
```
Copiar: record + Javadoc `@param` com o nome da propriedade. Prefixo `notify.admin`: `apiKey` (sem default, fail closed), `@DefaultValue("100") int maxDefault`, `@DefaultValue("1000") int maxLimit`. Registrar em `@EnableConfigurationProperties` de `ApiConfiguration` (linha 24).

### `api/admin/AdminWebConfiguration.java` (config)

**Analog:** `api/config/ApiConfiguration.java` linhas 22-25 (`@Configuration(proxyBeanMethods = false)`, Javadoc pt-BR explicando o motivo). Implementa `WebMvcConfigurer.addInterceptors` com `.addPathPatterns("/admin/**")`. Atenção: `@WebMvcTest` carrega `WebMvcConfigurer`, então os testes de fatia da API precisam de `AdminProperties` no contexto (`@EnableConfigurationProperties`, como `NotificationApiFlowTest` linha 52).

### `api/admin/AdminApiKeyInterceptor.java` (middleware)

Sem analog de interceptor. Código base no RESEARCH Padrão 1 (SHA-256 + `MessageDigest.isEqual`, `expectedDigest == null` → recusa). O 401 é uma `ErrorResponseException` lançada em `preHandle`, no molde abaixo.

### Exceções Problem Detail novas (`AdminUnauthorizedException` 401, `IdempotencyKeyReusedException` 422, `ReprocessInterruptedException` 503, validação 400)

**Analog:** `api/application/PublishFailedException.java` linhas 15-35:
```java
public class PublishFailedException extends ErrorResponseException {
    public PublishFailedException(String notificationId, Duration retryAfter, PublishResult result) {
        super(HttpStatus.SERVICE_UNAVAILABLE, problem(notificationId, result), null);
        ...
        getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter.toSeconds()));
    }
    private static ProblemDetail problem(String notificationId, PublishResult result) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setType(ProblemTypes.PUBLISH_FAILED);
        problem.setTitle("Publicação não confirmada");
        problem.setDetail("A notificação " + notificationId + " ... (" + result.errorCode() + "). ...");
        problem.setProperty("notificationId", notificationId);
        return problem;
    }
}
```
Regras: título/detalhe pt-BR fixos, só códigos de conjunto fechado (nunca `result.detail()`), contagens parciais via `setProperty("requested"| "reprocessed"| "skipped"| "discarded"| "reason", ...)`. Novos `URI` em `api/web/ProblemTypes.java` no padrão `URI.create("urn:notify-hub:problem:...")` (linhas 8-10): `unauthorized`, `idempotency-key-reused`, `reprocess-interrupted`. Para 400 de parâmetros (canal, `max`, `page`, `size`, `status`, chave inválida), reaproveitar o formato `validationProblem(errors)` de `ApiExceptionHandler` linhas 70-77 (`type VALIDATION`, `errors[]` de `FieldProblem`) — sugerido: tornar `validationProblem`/`FieldProblem` reutilizáveis e lançar uma `ErrorResponseException` com esse corpo.

### `api/web/ApiExceptionHandler.java` (M)

**Analog:** override existente, linhas 49-57:
```java
@Override
protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
        HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    FieldProblem error = ...;
    return handleExceptionInternal(ex, validationProblem(List.of(error)), headers, HttpStatus.BAD_REQUEST, request);
}
```
Acrescentar `handleTypeMismatch` (e, se usar `@RequestParam` obrigatório, `handleMissingServletRequestParameter`) no mesmo molde, para `?page=abc` não sair `about:blank`. O `handleExceptionInternal` (linhas 59-68) já preenche `instance`.

### `api/admin/AdminController.java` e `api/web/NotificationController.java` (M) (controller)

**Analog:** `api/web/NotificationController.java` linhas 22-51:
```java
@RestController
@RequestMapping("/notifications")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service = service; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CreateNotificationResponse> create(@Valid @RequestBody CreateNotificationRequest request) {
        CreateNotificationResponse response = service.create(request);
        return ResponseEntity.accepted()
                .location(URI.create("/notifications/" + response.id()))
                .body(response);
    }
    @GetMapping("/{id}")
    public NotificationView get(@PathVariable String id) { return service.get(id); }
}
```
- POST ganha `@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey`; serviço devolve `CreateResult(response, replayed)` e o controller acrescenta `.header("Idempotent-Replayed", "true")` quando replay. Atualizar `NotificationApiFlowTest` (mocka `service.create(request)`).
- `GET /notifications` (lista) e `GET /notifications/stats`: parâmetros `String`/`Integer` opcionais validados à mão; `/stats` literal tem precedência sobre `/{id}`.
- `AdminController`: `@RequestMapping("/admin")`, `@SecurityRequirement(name = "adminApiKey")`, `@PathVariable String canal` parseado com `Channel.valueOf(canal.toUpperCase(Locale.ROOT))` (nunca binding direto do enum). Javadoc de classe curto citando os requisitos (como linha 21).

### `api/web/dto/NotificationSummary.java`, `PageResponse.java`, `NotificationStats.java` (DTO)

**Analog:** `api/web/dto/NotificationView.java` linhas 16-55: record + `static ... from(NotificationDocument doc)` com tratamento de nulos:
```java
public static NotificationView from(NotificationDocument doc) {
    List<AttemptView> attempts = doc.attempts() == null ? List.of() : ...;
    return new NotificationView(doc.id(), doc.status(), doc.channel(), ...);
}
```
`NotificationSummary.from(doc)` com `lastError == null ? null : lastError.code()`. `PageResponse<T>(content, page, size, totalElements, totalPages)` próprio (não `PageImpl`). `NotificationStats` com `EnumMap` pré-preenchido com zeros.

### `api/application/NotificationService.java` (M) e `NotificationRepublisher.java` (service, CRUD + publish)

**Analog:** `NotificationService.java` linhas 47-86. Fluxo de criação e update condicional que deve ser extraído para o `NotificationRepublisher`:
```java
private void markPublishFailed(String id, PublishResult result) {
    Instant failedAt = Instant.now();
    UpdateResult update = mongoTemplate.updateFirst(
            query(where(Fields.ID).is(id).and(Fields.STATUS).is(NotificationStatus.PENDING)),
            new Update()
                    .set(Fields.STATUS, NotificationStatus.PUBLISH_FAILED)
                    .set(Fields.LAST_ERROR, new LastError(result.errorCode(), result.detail(), null, failedAt))
                    .set(Fields.UPDATED_AT, failedAt),
            NotificationDocument.class);
    if (update.getModifiedCount() == 1) {
        log.warn("Notificação {} marcada PUBLISH_FAILED ({})", id, result.errorCode());
        throw new PublishFailedException(id, props.retryAfter(), result);
    }
    log.info("... o worker já a reivindicou; respondendo 202", ...);
}
```
- Republish: `updateFirst` com `_id` + `status = PUBLISH_FAILED` → `$set status=PENDING, updatedAt`; `getModifiedCount()==0` → não publica; depois `publisher.publish(new NotificationMessage(doc.id(), doc.channel(), doc.templateCode(), doc.createdAt()), props.confirmTimeout())`; falha → `markPublishFailed`.
- Opcional (T-02-01): trocar `result.detail()` por mensagem fixa derivada de `errorCode()` no `LastError`.
- Imports estáticos `where`/`query` (linhas 3-4), logger SLF4J com `RecipientRules.mask(...)` para destinatário (linha 54-55).
- Idempotência: `insert` em `try/catch (DuplicateKeyException)` conforme RESEARCH Padrão 4 (relançar se `findOne` por chave devolver `null`).
- Listagem: `Criteria`/`Query` + `count` antes de `skip/limit` + `q.fields().include(...)` (RESEARCH Padrão 5). Stats: `Aggregation.group(STATUS, CHANNEL).count().as("count")` em `Collections.NOTIFICATIONS` (Padrão 6).

### `api/application/NotificationResetService.java` (service, findAndModify atômico)

**Analog:** `worker/processing/NotificationStateService.java` linhas 97-115 (claim) e 126-140 (`$push` com `$slice`):
```java
Instant now = Instant.now();
Criteria fresh = where(Fields.STATUS).in(NotificationStatus.claimableForDelivery());
Criteria stuck = where(Fields.STATUS).in(NotificationStatus.reclaimableWhenStuck());
if (!redelivered) { stuck = stuck.and(Fields.LEASE_UNTIL).lt(now); }
Query claimable = query(where(Fields.ID).is(id)
        .and(Fields.RETRY_COUNT).lte(retryCount)
        .orOperator(fresh, stuck));
Update toProcessing = new Update()
        .set(Fields.STATUS, NotificationStatus.PROCESSING)
        ...
return Optional.ofNullable(mongo.findAndModify(claimable, toProcessing,
        FindAndModifyOptions.options().returnNew(true), NotificationDocument.class));
```
```java
// $slice exige $each: slice(...) antes de each(...) monta os dois modificadores do $push
sent.push(Fields.ATTEMPTS).slice(-50).each(attempt);
```
Para o reset: `fresh` = `sourcesOf(PENDING)` menos PROCESSING; `stuck` = PROCESSING com `leaseUntil < now`; `$set status=PENDING, retryCount=0, updatedAt`, `$unset leaseUntil, claimedAt`, `$push` do evento REPROCESSED; **nunca** tocar `attemptCount`. Javadoc de classe no estilo do analog (linhas 26-49: lista `<ul>` de cada transição e fence). Teste unitário no molde de `notify-worker/src/test/java/dev/notifyhub/worker/processing/NotificationStateServiceTest.java` + `MongoBson.java` (asserção sobre o BSON da query/update, sem Docker).

### `api/messaging/NotificationPublisher.java` (M) e `api/messaging/DlqCopyPublisher.java` (publish confirmado)

**Analog:** `worker/messaging/DeliveryRoutingPublisher.java` linhas 55-61 e 79-107:
```java
public PublishOutcome publishToDlq(Channel channel, NotificationMessage message, FailureClass failureClass,
                                   int retryCount) {
    Map<String, Object> headers = new LinkedHashMap<>();
    headers.put(NotifyHeaders.FAILURE_CLASS, failureClass.name());
    headers.put(NotifyHeaders.RETRY_COUNT, retryCount);
    return publish(NotifyTopology.DLX_EXCHANGE, channel.dlqKey(), message, headers);
}

private PublishOutcome publish(String exchange, String routingKey, NotificationMessage message,
                               Map<String, Object> headers) {
    CorrelationData cd = new CorrelationData(); // UUID: nunca o notificationId
    try {
        rabbit.convertAndSend(exchange, routingKey, message, m -> {
            MessageProperties props = m.getMessageProperties();
            props.setMessageId(message.notificationId());
            headers.forEach(props::setHeader);
            return m;
        }, cd);
    } catch (AmqpException e) { return PublishOutcome.sendFailed(e); }
    try {
        CorrelationData.Confirm confirm = cd.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        ReturnedMessage returned = cd.getReturned();
        if (returned != null) { return PublishOutcome.returned(returned.getReplyCode()); }
        return confirm.isAck() ? PublishOutcome.acked() : PublishOutcome.nacked();
    } catch (TimeoutException e) { ... } catch (InterruptedException e) { Thread.currentThread().interrupt(); ... }
      catch (ExecutionException e) { ... }
}
```
- `NotificationPublisher.java:31-32`: trocar `new CorrelationData(msg.notificationId())` por `new CorrelationData()` e corrigir o comentário (Armadilha 7). Atualizar `NotificationPublisherTest` se ele afirma o id da correlação.
- `DlqCopyPublisher`: como o corpo pode ser inválido, publicar os **bytes originais** com `rabbit.send(DLX_EXCHANGE, channel.dlqKey(), new Message(body, props), cd)` copiando só `messageId`, `contentType`, `x-failure-class` e `notify-retry-count` (sem `x-death`); resto do confirm idêntico.

### `api/admin/DlqReprocessService.java` (service, pull AMQP + reset + republish)

Sem analog de `basicGet`. Combina: esqueleto do RESEARCH Padrão 2 (`rabbitTemplate.execute(ch -> ...)`, `basicGet(dlq, false)`, `basicAck`/`basicNack(tag,false,true)` no `finally`), reset via `NotificationResetService`, republish via `NotificationPublisher`, cópia via `DlqCopyPublisher`. Parse com `objectMapper.readValue(body, NotificationMessage.class)` (nunca o `MessageConverter`). Nomes: `channel.dlq()`, `channel.dlqKey()`, `NotifyTopology.DLX_EXCHANGE` (`common/domain/Channel.java:25-42`, `common/messaging/NotifyTopology.java:34-36`).

### `api/admin/ReprocessOutcome.java` (motivos em conjunto fechado)

**Analog:** `api/messaging/PublishResult.java` linhas 46-55 e o Javadoc de `PublishOutcome` (`DeliveryRoutingPublisher.java:109-120`):
```java
public String errorCode() {
    return switch (outcome) {
        case ACK -> null;
        case NACK -> "PUBLISH_NACK";
        case RETURNED -> "PUBLISH_RETURNED";
        case TIMEOUT -> "PUBLISH_TIMEOUT";
        case BROKER_UNAVAILABLE -> "BROKER_UNAVAILABLE";
    };
}
```
Motivo do 503 = `PublishResult.errorCode()` ou códigos novos (`MONGO_UNAVAILABLE`, `DLQ_UNAVAILABLE`); documentar no Javadoc que nunca contém texto livre (T-02-01).

### `notify-api/pom.xml`, `notify-worker/pom.xml`, `pom.xml` (config)

`notify-api/pom.xml` linhas 18-58: dependências sem versão (BOM), exceto springdoc `2.8.17`. Plugin atual (linhas 53-56) ganha a execução `repackage` com `<classifier>exec</classifier>` (mesmo bloco nos dois apps; XML no RESEARCH "Instalação"). `pom.xml` pai linhas 26-30: acrescentar `<module>notify-it</module>` depois de `notify-worker`; entradas `dev.notifyhub:notify-api`/`notify-worker` com `${project.version}` podem entrar em `dependencyManagement` (linhas 32-38, mesmo molde do `notify-common`).

### `notify-api/src/main/resources/application.yml` / `application-demo.yml`

Bloco `notify:` (linha 39) com comentário pt-BR por chave, como `confirm-timeout`. Acrescentar `admin.api-key: ${NOTIFY_ADMIN_API_KEY:}` (vazio = fail closed) no base e valor documentado no `demo`.

### `scripts/smoke-lib.sh` / `smoke-fase1.sh`

Trocar `notify-worker-$VERSAO.jar`/`notify-api-$VERSAO.jar` (smoke-lib.sh:20-21; smoke-fase1.sh:228-229) por `-$VERSAO-exec.jar`.

### Testes unitários da API (fatia web, sem Docker)

**Analog:** `notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java` linhas 50-53:
```java
@WebMvcTest(NotificationController.class)
@Import({NotificationService.class, NotificationPublisher.class})
@EnableConfigurationProperties(PublishProperties.class)
class NotificationApiFlowTest {
```
Com `@MockitoBean RabbitTemplate`/`MongoTemplate`, `MockMvc` e `jsonPath`/`header` matchers. Copiar para `AdminControllerTest` (`@WebMvcTest(AdminController.class)` + `AdminWebConfiguration` + `AdminProperties`): 401 sem chave/chave errada antes de 400, `max` fora do intervalo, canal inválido. `DlqReprocessServiceTest`: `com.rabbitmq.client.Channel` mockado verificando `basicNack(tag, false, true)` (Armadilhas 1-2).

---

### `notify-it` (módulo novo, só testes) — sem analog

Não existe IT nem Testcontainers no repositório. Usar diretamente o RESEARCH:
- `notify-it/pom.xml`: esqueleto do Padrão 9 (parent `notify-hub-parent`, mesma estrutura de cabeçalho de `notify-api/pom.xml` linhas 1-16; deps `scope test`; `maven-failsafe-plugin` sem versão com `systemPropertyVariables`; **sem** `spring-boot-maven-plugin`).
- `support/ItContainers`, `ItContainersConfig`, `NotifyHubApps`: esqueleto do Padrão 9 (`SpringApplication.from(App::main).with(ItContainersConfig.class).run("--spring.config.location=...", "--spring.profiles.active=it", "--server.port=0")`; sem `@Testcontainers`/`@Container`).
- Cenários e asserções: Padrão 10; limpeza `@BeforeEach`: Armadilha 13 (`remove(new Query(), "notifications")`, nunca drop; reset dos CBs; `purgeQueue`).
- `application-it.yml` em `it/shared` (retry-levels `200ms,500ms,1s`, exatamente 3), `it/worker` (magic-recipients true, CB `wait-duration-in-open-state: 100ms`, não redefinir `spring.autoconfigure.exclude`), `it/api` (`notify.admin.api-key: it-admin-key`). Estrutura de overlay como `notify-worker/src/main/resources/application-demo.yml`.

### `api/web/OpenApiConfiguration.java` — sem analog

Usar RESEARCH Padrão 8 (`@OpenAPIDefinition` + `@SecurityScheme(name="adminApiKey", type=APIKEY, in=HEADER, paramName="X-API-Key")`), com `@Configuration(proxyBeanMethods = false)` como `ApiConfiguration`.

## Padrões compartilhados

### Updates condicionais no Mongo (fence)
**Fonte:** `worker/processing/NotificationStateService.java` 97-140; `api/application/NotificationService.java` 71-86
**Aplicar a:** reset do reprocess, republish (PUBLISH_FAILED → PENDING), markPublishFailed
Sempre uma única operação (`findAndModify`/`updateFirst`) com o status de origem no filtro; nunca `save()` nem ler-modificar-gravar; `$push` com `.slice(-50).each(...)`.

### Publish confirmado com motivo fechado
**Fonte:** `worker/messaging/DeliveryRoutingPublisher.java` 79-107; `api/messaging/PublishResult.java` 46-55
**Aplicar a:** `NotificationPublisher`, `DlqCopyPublisher`, `DlqReprocessService`, `NotificationRepublisher`
`new CorrelationData()` UUID, `messageId` = id da notificação, `getReturned()` antes de `isAck()`, timeout limitado, nunca expor `detail()`/reply text.

### Problem Details
**Fonte:** `api/application/PublishFailedException.java` 15-35; `api/web/ApiExceptionHandler.java` 59-77; `api/web/ProblemTypes.java`
**Aplicar a:** todos os 400/401/422/503 novos
`ErrorResponseException` + `ProblemDetail` com `type urn:notify-hub:problem:*`, título/detalhe pt-BR fixos, propriedades extras via `setProperty`; 400 sempre com `errors[]` de `FieldProblem`.

### Propriedades tipadas
**Fonte:** `api/messaging/PublishProperties.java`; registro em `api/config/ApiConfiguration.java:24`
**Aplicar a:** `AdminProperties` (e limites de paginação, se configuráveis).

### Convenções gerais
Records sem Lombok; Javadoc e logs em pt-BR; destinatário sempre mascarado com `RecipientRules.mask`; imports estáticos `Criteria.where`/`Query.query`; constantes de campo de `NotificationDocument.Fields`, nunca strings soltas (exceto `recipient`/`templateCode`, que ainda não têm constante — acrescentar em `Fields` se usados na projeção).

## Sem analog

| Arquivo | Papel | Fluxo | Motivo |
|---|---|---|---|
| `notify-it/pom.xml` | config | — | Primeiro módulo só de testes / Failsafe |
| `notify-it/.../support/ItContainers.java`, `ItContainersConfig.java`, `NotifyHubApps.java` | test harness | — | Não há Testcontainers no repositório |
| `notify-it/.../*IT.java` (6 classes) | test | event-driven | Não há ITs; seguir RESEARCH Padrão 10 |
| `api/web/OpenApiConfiguration.java` | config | — | springdoc é novo |
| `api/admin/AdminApiKeyInterceptor.java` | middleware | request-response | Nenhum `HandlerInterceptor`; só o formato do erro tem analog |
| `api/application/RequestHasher.java` | utility | transform | Sem hashing no código; seguir RESEARCH Padrão 4 |

## Metadados

**Escopo da busca:** `notify-api`, `notify-common`, `notify-worker` (main e test), `pom.xml`, `scripts/`
**Arquivos lidos:** 14
**Data da extração:** 2026-10-28
