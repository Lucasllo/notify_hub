# Fase 1: Entrega ponta a ponta no caminho feliz - Pesquisa

**Pesquisado em:** 2026-10-10
**Domínio:** Maven multi-módulo + Spring Boot 3.5.16 (Spring AMQP 3.2.12, Spring Data MongoDB 4.5.13), topologia RabbitMQ 4.3 com TTL+DLX, MongoDB 8.0, virtual threads (Java 21), JMustache
**Confiança:** ALTA para versões e comportamento de framework (código-fonte das tags exatas lido nesta sessão); MÉDIA para algumas conclusões derivadas (marcadas `[ASSUMED]`)

> Esta pesquisa **reaproveita** `.planning/research/` (ARCHITECTURE, PITFALLS, STACK, FEATURES, SUMMARY) e não repete o que já está lá. Ela acrescenta o que faltava para planejar a Fase 1: correções de API (por exemplo `ensureIndex` agora está depreciado), as armadilhas de ordem de inicialização, o ambiente real da máquina e o mapa de validação. Código e identificadores em inglês; texto em pt-BR, conforme o CLAUDE.md.

<user_constraints>
## Restrições do usuário (de CONTEXT.md)

### Decisões travadas
**Contrato da API**
- **D-01:** O corpo do `POST /notifications` é plano, com campos no nível raiz: `channel` (enum em maiúsculo: `EMAIL` | `SMS` | `WEBHOOK`), `recipient` (string), `templateCode` (string) e `variables` (objeto string→valor). Exemplo: `{"channel":"EMAIL","recipient":"ana@exemplo.com","templateCode":"welcome","variables":{"name":"Ana"}}`. — **Reversibility:** costly — o k6, os ITs, os exemplos do README e o Swagger dependem desse formato.
- **D-02:** O 202 traz o header `Location: /notifications/{id}` e um corpo mínimo `{ "id", "status": "PENDING", "createdAt" }`. Assim o k6 e os clientes seguem a notificação sem precisar ler o header.
- **D-03:** O `GET /notifications/{id}` devolve a representação completa: `id`, `status`, `channel`, `recipient`, `templateCode`, `variables`, `rendered` (EMAIL: `subject` + `body`; SMS/WEBHOOK: `body`), `retryCount`, `lastError`, `attempts[]` completo (número, timestamp, `durationMs`, `outcome`, erro e classe da falha), `createdAt` e `updatedAt`. Por isso o worker persiste o conteúdo renderizado no documento, na mesma atualização atômica do resultado (WRK-04).
- **D-04:** A API espera o publisher confirm por até **5s**, valor configurável por propriedade (ex.: `notify.publish.confirm-timeout=5s`). Em nack, timeout ou mensagem devolvida, ela responde 503 com Problem Detail e `Retry-After: 5`. O valor do `Retry-After` também é configurável.
- **D-05:** O canal em maiúsculo é a forma pública da API e do documento no Mongo. Routing keys e nomes de fila usam o canal em minúsculo (`notification.email`, `notify.email.*`). A conversão fica centralizada no enum em `notify-common`.

**Templates do seed**
- **D-06:** O seed traz 3 templates de e-commerce, cada um com variantes EMAIL, SMS e WEBHOOK: `welcome`, `order-shipped` e `password-reset`. — **Reversibility:** costly — o k6 (Fase 4), os ITs e os exemplos do README referenciam esses códigos.
- **D-07:** O conteúdo dos templates (assunto e corpo) é em **pt-BR**.
- **D-08:** O seed fica em arquivos JSON nos resources do worker (ex.: `notify-worker/src/main/resources/seed/templates/*.json`), um arquivo por template. Na inicialização, um `ApplicationRunner` faz upsert por `_id` e só sobrescreve quando a `version` do seed é maior. O comportamento é o mesmo no compose, localmente e no Testcontainers. Não usar `docker-entrypoint-initdb.d`.
- **D-09:** A variante WEBHOOK usa um envelope de evento: `{"event":"<evento>","notificationId":"{{_notificationId}}","data":{...}}`, com eventos como `user.welcome`, `order.shipped` e `password.reset`. `_notificationId` é uma **variável reservada** que o worker injeta na renderização. Ela não aparece em `requiredVariables` e o cliente não precisa enviá-la.
- **D-10:** Variáveis obrigatórias sugeridas: `welcome` → `name`; `order-shipped` → `name`, `orderId`; `password-reset` → `name`, `resetCode`. O planner pode ajustar os nomes, mas cada template tem pelo menos uma variável obrigatória.

### Critério do Claude
- **Validação de destinatário por canal (API-06):** usar formatos simples e documentados: e-mail com regex simples (`local@dominio.tld`), SMS em E.164 (`^\+[1-9]\d{7,14}$`) e webhook como URL absoluta `http`/`https`. A regra deve aceitar os formatos dos destinatários mágicos da Fase 2 (ex.: `always-transient@fail.test`, `+5500000000001`, `https://fail.test/...`). A validação fica num validador por canal, não em anotações espalhadas.
- **Adapter simulado na Fase 1 (DLV-01):** latência bloqueante (`Thread.sleep`) e taxa de falha configuráveis por canal. Defaults da Fase 1: latência baixa (ex.: 50–200ms) e **taxa de falha 0%**, porque o caminho de retry só chega na Fase 2. Qualquer exceção nesta fase rejeita para a DLQ (sem requeue), o que já satisfaz o critério 4.
- **Escape na Fase 1:** o escape completo por canal é TPL-03 (Fase 2), mas a variante WEBHOOK não pode sair com `&quot;` já na Fase 1. Basta um compilador por canal com `escapeHTML(false)` para SMS/WEBHOOK. O escape JSON dos valores fica para a Fase 2.
- **Layout:** groupId e pacote base à escolha do planner (ex.: `dev.notifyhub`). API na porta 8080, worker na 8081 (`starter-web` para o actuator e o scrape futuro). O compose da Fase 1 sobe só RabbitMQ e MongoDB; as apps rodam via `./mvnw spring-boot:run`. Os Dockerfiles chegam na Fase 4.
- **ID:** UUID string gerado pela API (UUIDv7 é opcional), usado como `_id` e como `messageId` AMQP.
- Campos exatos do Problem Detail (`type`, `title`, `status`, `detail`, `instance` + `errors[]` por campo no 400).

### Ideias adiadas (FORA DO ESCOPO)
None — discussion stayed within phase scope.

**Fora desta fase (do Phase Boundary do CONTEXT):** classificador, retry, circuit breaker, bulkhead, destinatários mágicos e métricas (Fase 2); Idempotency-Key, listagem, stats, Swagger, admin e ITs (Fase 3); Dockerfiles das apps, Prometheus, Grafana, k6 e README (Fase 4).
</user_constraints>

<phase_requirements>
## Requisitos da fase

| ID | Descrição | Apoio da pesquisa |
|----|-----------|-------------------|
| FND-01 | Maven multi-módulo, wrapper, Java 21, Boot 3.5.16 | §Stack padrão, §Bootstrap do wrapper (Maven não está no PATH; há uma distribuição 3.9.16 em cache) |
| FND-02 | `notify-common`: enums, transições, record AMQP, headers, constantes de topologia | §Padrão 1 (contrato), §Padrão 2 (máquina de estados) |
| FND-03 | Compose com RabbitMQ 4.3 (management + prometheus) e Mongo 8.0, healthchecks, tags pinadas | §Exemplo compose; a imagem 4.3 **já habilita** `rabbitmq_prometheus` |
| FND-04 | Índices criados explicitamente na inicialização | §Padrão 6; usar `createIndex` (`ensureIndex` está depreciado para remoção na 4.5) |
| MSG-01 | Um único bean `Declarables` com `notify.exchange`, `notify.retry`, `notify.dlx` | §Padrão 3 |
| MSG-02 | Fila principal por canal ligada por `notification.<canal>` | §Padrão 3 |
| MSG-03 | 3 filas de delay por canal, TTL no nome, DLX de volta à principal com routing key explícita | §Padrão 3 + Armadilha 3 (routing key de retry com o rótulo do TTL, não o ordinal) |
| MSG-04 | DLQ por canal; a principal faz dead-letter para ela | §Padrão 3 |
| MSG-05 | Níveis configuráveis: padrão 5s/25s/125s, `demo` 1s/5s/25s, ms nos testes | §Padrão 3 (`TopologyProperties`, formatador de rótulo) |
| API-01 | `POST` → PENDING persistido antes do publish, 202 + `Location` | §Padrão 4 |
| API-02 | Confirms correlacionados + mandatory/returns; falha → PUBLISH_FAILED condicional + 503 + `Retry-After` | §Padrão 4, Armadilha 4 (0 documentos casados → 202) |
| API-04 | `GET /notifications/{id}` completo | §Padrão 4 (DTO de leitura), D-03 |
| API-06 | 400 com Problem Details (RFC 9457) | §Padrão 5 (advice que estende `ResponseEntityExceptionHandler`) |
| WRK-01 | Um container por canal, `default-requeue-rejected=false`, sem retry interno, prefetch baixo | §Padrão 7, §application.yml do worker |
| WRK-02 | Claim por `findAndModify` condicional, cercado pelo contador | §Padrão 8 |
| WRK-04 | Resultado numa única atualização atômica (`$set` + `$push`) | §Padrão 8 (`markSent`) |
| WRK-05 | `spring.threads.virtual.enabled=true`, concorrência configurável | §Padrão 7, Armadilha 6 |
| TPL-01 | Coleção `templates` com variantes por canal + `requiredVariables`, seed idempotente | §Padrão 9, Armadilhas 1 e 2 |
| DLV-01 | Adapter simulado por canal com latência bloqueante e taxa de falha configuráveis | §Padrão 10 |
</phase_requirements>

## Resumo

A Fase 1 é greenfield e densa (19 requisitos). O desenho já está definido em `.planning/research/ARCHITECTURE.md` (Padrões 1, 3, 4 e 5) e nas decisões D-01..D-10. Esta pesquisa leu o **código-fonte das versões exatas** que o BOM do Boot 3.5.16 gerencia (Boot v3.5.16, Spring AMQP v3.2.12, Spring Data MongoDB 4.5.13, JMustache 1.16 e a imagem Docker oficial do RabbitMQ 4.3) para fechar os pontos que o planner precisa acertar de primeira.

Achados novos que mudam o plano:
1. **Os runners rodam depois que os listeners já subiram.** `SpringApplication` chama `refreshContext` (que inicia os containers `@RabbitListener`) antes de `callRunners`. Como D-08 exige um `ApplicationRunner` para o seed, o worker precisa de `spring.rabbitmq.listener.simple.auto-startup=false` e de um runner final que inicie os containers **depois** de criar os índices e aplicar o seed. Sem isso, uma fila com backlog no primeiro boot manda mensagens para a DLQ por "template inexistente".
2. **A API só declara a topologia quando abre a primeira conexão.** O `RabbitAdmin` declara num connection listener. Chame `amqpAdmin.initialize()` num runner da API para que o painel mostre a topologia ao subir e para falhar cedo se o broker estiver fora.
3. **`IndexOperations.ensureIndex` está `@Deprecated(since = "4.5", forRemoval = true)`.** Use `createIndex` com nomes de índice explícitos.
4. **Routing key de retry com o rótulo do TTL** (`email.retry.5s`), não o ordinal (`email.retry.1`). Assim, trocar de profile com o volume do broker persistido não faz duas filas de delay (a antiga e a nova) receberem a mesma mensagem.
5. **Chaves de `variables` com `.` quebram a gravação** (`MappingException` do Spring Data). Valide as chaves na API com regex.
6. **Ambiente:** o JDK 21 está instalado, mas o Maven **não** está no PATH (há uma distribuição 3.9.16 em `~/.m2/wrapper/dists`, então o wrapper pode ser gerado com ela) e o **daemon do Docker não está rodando**. A imagem `rabbitmq:4.3.6-management` já habilita `rabbitmq_prometheus` e permite `guest` pela rede, então não monte `enabled_plugins`.

**Recomendação principal:** divida a fase em 5 planos: (1) esqueleto Maven + compose, (2) `notify-common` com topologia, contrato e índices + testes unitários, (3) API e (4) worker em paralelo, ambos dependentes do 2, e (5) verificação ponta a ponta com checkpoint humano. Use `MongoTemplate` para todas as transições, um único `MessageConverter` JSON e um único `Declarables` vindo do common, e a ordem de inicialização do worker: índices → seed → `registry.start()`.

## Mapa de responsabilidade arquitetural

| Capacidade | Camada principal | Camada secundária | Justificativa |
|------------|------------------|-------------------|---------------|
| Validação estrutural + formato do destinatário | API (`notify-api`) | `notify-common` (`RecipientRules`, puro Java) | 400 antes de persistir; as regras ficam no common para o worker da Fase 2 reutilizar |
| Persistir PENDING + publish confirmado | API | Broker | A API é dona de PENDING/PUBLISH_FAILED (sempre com update condicional) |
| Topologia (exchanges/filas/bindings/args) | `notify-common` (`Declarables`) | Broker | Fonte única de argumentos; evita 406 |
| Retry por TTL / dead-letter | Broker (RabbitMQ) | — | Nativo: `x-message-ttl` + DLX; nenhum código conta tempo |
| Claim idempotente e resultado atômico | Worker | MongoDB | O documento é a fonte da verdade; a mensagem é só um token |
| Renderização de template | Worker | MongoDB (`templates`) | Templates vêm só do seed; o cliente nunca envia template |
| Envio simulado | Worker (adapter) | — | Latência bloqueante para o caso de VT |
| Índices | `notify-common` (`MongoIndexInitializer`) | API e worker executam | Idempotente; quem subir primeiro cria |
| Leitura `GET /{id}` | API | MongoDB | `findById` por `_id` |

## Stack padrão

Fixado pelo CLAUDE.md e confirmado no BOM local `~/.m2/.../spring-boot-dependencies-3.5.16.pom` [VERIFIED: spring-boot-dependencies-3.5.16.pom linhas 35, 109, 134, 143, 148, 174, 187, 190, 204]:
```
<awaitility.version>4.2.2</awaitility.version>
<jmustache.version>1.16</jmustache.version>
<maven-failsafe-plugin.version>3.5.6</maven-failsafe-plugin.version>
<maven-surefire-plugin.version>3.5.6</maven-surefire-plugin.version>
<mongodb.version>5.5.2</mongodb.version>
<rabbit-amqp-client.version>5.25.0</rabbit-amqp-client.version>
<spring-amqp.version>3.2.12</spring-amqp.version>
<spring-data-bom.version>2025.0.13</spring-data-bom.version>
<testcontainers.version>1.21.4</testcontainers.version>
```

### Núcleo (Fase 1)
| Biblioteca | Versão | Módulo | Finalidade |
|------------|--------|--------|------------|
| `spring-boot-starter-parent` | 3.5.16 | parent | BOM + pluginManagement |
| `spring-rabbit` (via `spring-boot-starter-amqp`) | 3.2.12 (gerenciada) | common (`spring-rabbit`), api e worker (starter) | `Declarables`, `RabbitTemplate`, `@RabbitListener` |
| `spring-data-mongodb` (via `spring-boot-starter-data-mongodb`) | 4.5.13 (gerenciada) | common, api e worker | `MongoTemplate`, `@Document`, `IndexOperations` |
| `spring-boot-starter-web` | gerenciada | api e worker | REST (api) e actuator na 8081 (worker) |
| `spring-boot-starter-validation` | gerenciada | api | Bean Validation |
| `spring-boot-starter-actuator` | gerenciada | api e worker | `/actuator/health` (o Prometheus fica para a Fase 4, mas pode já expor `health,info`) |
| `com.samskivert:jmustache` | 1.16 (gerenciada, sem `<version>`) | worker | Renderização |
| `spring-boot-starter-test` | gerenciada | todos | JUnit 5.12.2, AssertJ, Mockito |

**Não entram na Fase 1:** Resilience4j, `spring-boot-starter-aop`, springdoc, Testcontainers, `micrometer-registry-prometheus` (pode entrar já, mas sem contadores; OBS-01 é da Fase 2), Lombok (proibido pelo CLAUDE.md) e `spring-boot-docker-compose` (rodaria o compose automaticamente em `spring-boot:run` e mistura com a Fase 4).

**`notify-common`:** jar simples, **sem** `spring-boot-maven-plugin`. O `starter-parent` só declara o plugin em `pluginManagement`, então o common não é reempacotado. Dependências: `spring-boot` (para `@ConfigurationProperties`), `spring-rabbit`, `spring-data-mongodb` e `jackson-annotations`, todas sem versão.

### Bootstrap do wrapper (Maven ausente no PATH)
```bash
# Git Bash. A distribuição 3.9.16 já está em cache (verificado: .../0daed3be.../bin/mvn existe)
MVN="$HOME/.m2/wrapper/dists/apache-maven-3.9.16/0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0/bin/mvn"
"$MVN" -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.9.16
```
`maven-wrapper-plugin` 3.3.4 é o `<release>` atual no Maven Central [VERIFIED: repo1.maven.org maven-metadata.xml]. Commite `.mvn/wrapper/maven-wrapper.properties`, `mvnw` e `mvnw.cmd`. Rode o comando **depois** de criar o `pom.xml` pai. [ASSUMED: o goal também funciona sem POM; criar o POM antes elimina a dúvida.] Plano B: baixar `apache-maven-3.9.16-bin.zip` do Maven Central para o scratchpad e rodar o mesmo goal.

## Auditoria de legitimidade de pacotes

O seam `package-legitimacy` só cobre npm/PyPI/crates. Para Maven, a verificação foi feita contra o Maven Central e o BOM do Boot.

| Pacote | Registro | Fonte | Veredito | Disposição |
|--------|----------|-------|----------|------------|
| `org.springframework.boot:*` 3.5.16 (starters) | Maven Central | BOM local 3.5.16 | OK (oficial Spring) | Aprovado |
| `org.springframework.amqp:spring-rabbit` 3.2.12 | Maven Central | gerenciado pelo BOM; POM respondeu HTTP 200 | OK | Aprovado |
| `com.samskivert:jmustache` 1.16 | Maven Central | gerenciado pelo BOM; `<release>1.16</release>` | OK | Aprovado |
| `org.apache.maven.plugins:maven-wrapper-plugin` 3.3.4 | Maven Central | `<release>3.3.4</release>` | OK (Apache) | Aprovado |

**Removidos [SLOP]:** nenhum. **Sinalizados [SUS]:** nenhum. Nenhuma dependência fora do BOM entra na Fase 1.

## Padrões de arquitetura

### Diagrama do fluxo (Fase 1)

```
cliente ──POST /notifications──> [Controller: Bean Validation + RecipientRules]
                                   │ 400 Problem Details ◄── inválido
                                   v
                     [NotificationService] ── insert PENDING (_id=UUID) ──> Mongo notifications
                                   │
                                   v
                     [NotificationPublisher] ── convertAndSend(notify.exchange, notification.<ch>,
                                   │                CorrelationData(id), mandatory) ──> RabbitMQ
                                   │ espera future ≤ confirm-timeout; confere getReturned()
                    ┌──────────────┴───────────────┐
                 ack e não devolvida         nack | devolvida | timeout
                    │                              │
                    v                              v
               202 + Location         updateFirst({_id, status:PENDING} → PUBLISH_FAILED)
                                         casou 1 → 503 + Retry-After
                                         casou 0 → worker já pegou → 202

RabbitMQ: notify.exchange(topic) ──notification.<ch>──> notify.<ch> ──(reject)──> notify.dlx ──<ch>.dlq──> notify.<ch>.dlq
          notify.retry(direct) ──<ch>.retry.<ttl>──> notify.<ch>.retry.<ttl> ──(TTL expira)──> notify.exchange / notification.<ch>
          (o publish para notify.retry só é exercitado na Fase 2)

notify.<ch> ──> [Listener do canal, VT "rabbit-simple-N"] ──> [DeliveryProcessor]
      1. claim: findAndModify(_id, status∈{PENDING,RETRYING,PUBLISH_FAILED}, retryCount ≤ h) → PROCESSING
           null → ack sem enviar (duplicada ou obsoleta)
      2. carrega o template por templateCode → renderiza a variante do canal (+ _notificationId)
      3. adapter simulado: Thread.sleep(latência); falha com probabilidade p
      4. markSent: updateFirst(_id, status=PROCESSING, retryCount=h) $set SENT+rendered, $push attempt
      exceção em qualquer passo → reject sem requeue → notify.<ch>.dlq
cliente ──GET /notifications/{id}──> findById → DTO completo (D-03)
```

### Estrutura de projeto recomendada
```
pom.xml                         # parent: spring-boot-starter-parent 3.5.16, <java.version>21, módulos
.mvn/wrapper/ mvnw mvnw.cmd
.gitattributes                  # *.sh text eol=lf ; mvnw text eol=lf ; *.cmd text eol=crlf
docker-compose.yml              # só rabbitmq + mongo na Fase 1
notify-common/src/main/java/dev/notifyhub/common/
  NotifyCommonConfiguration.java   # @Configuration @EnableConfigurationProperties(TopologyProperties)
  domain/      Channel, NotificationStatus (+ tabela de transições), AttemptOutcome, RecipientRules
  messaging/   NotificationMessage (record), NotifyHeaders, NotifyTopology, TopologyProperties, TtlLabel
  persistence/ NotificationDocument, AttemptEntry, RenderedContent, LastError, TemplateDocument,
               TemplateVariant, MongoIndexInitializer, Collections
notify-api/src/main/java/dev/notifyhub/api/
  NotifyApiApplication.java  (@SpringBootApplication @Import(NotifyCommonConfiguration.class))
  web/ NotificationController, ApiExceptionHandler, ProblemTypes, dto/{CreateNotificationRequest,
       CreateNotificationResponse, NotificationView}, validation/{ValidRecipient, ValidRecipientValidator}
  application/ NotificationService, NotificationQueryService
  messaging/ NotificationPublisher, PublishResult
  startup/ ApiStartup (ApplicationRunner: índices + amqpAdmin.initialize())
notify-worker/src/main/java/dev/notifyhub/worker/
  NotifyWorkerApplication.java
  listener/ EmailListener, SmsListener, WebhookListener   (finos)
  processing/ DeliveryProcessor, NotificationStateService
  template/ TemplateRenderer, TemplateSeeder
  channel/ ChannelAdapter, SimulatedChannelAdapter, SimulationProperties, SimulatedDeliveryException
  startup/ WorkerStartup (runners ordenados: índices → seed → iniciar listeners)
notify-worker/src/main/resources/seed/templates/{welcome,order-shipped,password-reset}.json
```

### Padrão 1: contrato compartilhado (FND-02)
- `Channel` (enum `EMAIL, SMS, WEBHOOK`) centraliza toda conversão (D-05): `id()` → `"email"`, `routingKey()` → `"notification.email"`, `queue()` → `"notify.email"`, `dlq()` → `"notify.email.dlq"`, `dlqKey()` → `"email.dlq"`, `retryQueue(Duration)` → `"notify.email.retry." + TtlLabel.of(ttl)`, `retryKey(Duration)` → `"email.retry." + TtlLabel.of(ttl)`.
- `NotificationMessage(String notificationId, Channel channel, String templateCode, Instant createdAt)`: payload fino. Destinatário e variáveis ficam fora do broker, porque são PII e porque o documento é a fonte da verdade.
- `NotifyHeaders.RETRY_COUNT = "notify-retry-count"` (e já reservar `FAILURE_CLASS = "x-failure-class"` para a Fase 2).
- Um único bean `MessageConverter`, declarado em `NotifyCommonConfiguration`: `new Jackson2JsonMessageConverter(objectMapper)` usando o `ObjectMapper` do Boot (o mesmo tratamento de `Instant` da API). O Boot aplica esse bean **tanto** ao `RabbitTemplate` **quanto** à factory de listener, desde que seja único [VERIFIED: RabbitAutoConfiguration.java v3.5.16 l.145-148 `ObjectProvider<MessageConverter> messageConverter,` … `configurer.setMessageConverter(messageConverter.getIfUnique());`; RabbitAnnotationDrivenConfiguration.java l.126 `configurer.setMessageConverter(this.messageConverter.getIfUnique());`].
- Com `@RabbitListener` num método que recebe um tipo concreto, o tipo inferido do parâmetro vence o `__TypeId__` [VERIFIED: AbstractJackson2MessageConverter.java v3.2.12 l.235-237 "By default, if the type is concrete (not abstract, not an interface), this will be used ahead of type information provided in the {@code __TypeId__} and associated headers provided by the sender."]. Por isso não é preciso configurar pacotes confiáveis.
- Mensagem sem `content_type` é tratada como JSON [VERIFIED: idem l.315-318 `(contentType == null || contentType.equals(MessageProperties.DEFAULT_CONTENT_TYPE) || contentType.contains(this.supportedContentType.getSubtype()))`]. Isso ajuda nos testes manuais pelo painel.

### Padrão 2: máquina de estados em `notify-common`
```java
public enum NotificationStatus {
  PENDING, PROCESSING, SENT, RETRYING, FAILED, DEAD_LETTERED, PUBLISH_FAILED;

  private static final Map<NotificationStatus, Set<NotificationStatus>> ALLOWED = Map.of(
      PENDING,        EnumSet.of(PROCESSING, PUBLISH_FAILED),
      PUBLISH_FAILED, EnumSet.of(PROCESSING, PENDING),           // PENDING: republish admin (Fase 3)
      PROCESSING,     EnumSet.of(SENT, RETRYING, FAILED, DEAD_LETTERED, PROCESSING), // PROCESSING→PROCESSING: reclaim (WRK-03, Fase 2)
      RETRYING,       EnumSet.of(PROCESSING),
      FAILED,         EnumSet.of(PENDING),                       // reprocessamento (Fase 3)
      DEAD_LETTERED,  EnumSet.of(PENDING),
      SENT,           EnumSet.noneOf(NotificationStatus.class));

  public boolean canTransitionTo(NotificationStatus target) { return ALLOWED.get(this).contains(target); }
  public static Set<NotificationStatus> sourcesOf(NotificationStatus target) { /* derivado de ALLOWED */ }
}
```
A tabela inteira entra já na Fase 1 (ela é o contrato). Os filtros dos updates condicionais são **derivados** dela. Exemplo: o claim da Fase 1 usa `sourcesOf(PROCESSING) - {PROCESSING}`, e a Fase 2 inclui PROCESSING com lease/redelivered. Teste unitário obrigatório.

### Padrão 3: topologia num único `Declarables` (MSG-01..05)

| Exchange | Tipo | | Fila (por canal) | Argumentos | Binding |
|---|---|---|---|---|---|
| `notify.exchange` | topic | | `notify.<ch>` | `x-dead-letter-exchange=notify.dlx`, `x-dead-letter-routing-key=<ch>.dlq` | `notify.exchange` / `notification.<ch>` |
| `notify.retry` | direct | | `notify.<ch>.retry.<ttl>` ×3 | `x-message-ttl=<int ms>`, `x-dead-letter-exchange=notify.exchange`, `x-dead-letter-routing-key=notification.<ch>` | `notify.retry` / `<ch>.retry.<ttl>` |
| `notify.dlx` | direct | | `notify.<ch>.dlq` | nenhum | `notify.dlx` / `<ch>.dlq` |

```java
@ConfigurationProperties("notify.topology")
@Validated
public record TopologyProperties(@DefaultValue("5s,25s,125s") @NotEmpty List<Duration> retryLevels) {
  public TopologyProperties {  // valida: positivos, estritamente crescentes, rótulos distintos
    // lançar IllegalArgumentException se não for crescente → falha na inicialização
  }
}

public final class TtlLabel {            // 5s→"5s", 125s→"125s", 200ms→"200ms", 1500ms→"1500ms"
  public static String of(Duration d) {
    long ms = d.toMillis();
    return (ms % 1000 == 0) ? (ms / 1000) + "s" : ms + "ms";
  }
}

@Bean
Declarables notifyDeclarables(TopologyProperties props) {
  var main  = ExchangeBuilder.topicExchange(MAIN_EXCHANGE).durable(true).build();
  var retry = ExchangeBuilder.directExchange(RETRY_EXCHANGE).durable(true).build();
  var dlx   = ExchangeBuilder.directExchange(DLX_EXCHANGE).durable(true).build();
  List<Declarable> d = new ArrayList<>(List.of(main, retry, dlx));
  for (Channel ch : Channel.values()) {
    Queue q   = QueueBuilder.durable(ch.queue()).deadLetterExchange(DLX_EXCHANGE).deadLetterRoutingKey(ch.dlqKey()).build();
    Queue dlq = QueueBuilder.durable(ch.dlq()).build();
    d.add(q); d.add(dlq);
    d.add(BindingBuilder.bind(q).to(main).with(ch.routingKey()));
    d.add(BindingBuilder.bind(dlq).to(dlx).with(ch.dlqKey()));
    for (Duration ttl : props.retryLevels()) {
      Queue delay = QueueBuilder.durable(ch.retryQueue(ttl))
          .ttl(Math.toIntExact(ttl.toMillis()))                    // int, nunca String
          .deadLetterExchange(MAIN_EXCHANGE).deadLetterRoutingKey(ch.routingKey()).build();
      d.add(delay);
      d.add(BindingBuilder.bind(delay).to(retry).with(ch.retryKey(ttl)));
    }
  }
  return new Declarables(d);
}
```
Perfis: o default vem do `@DefaultValue` (5s/25s/125s). Cada app tem `application-demo.yml` com `notify.topology.retry-levels: 1s,5s,25s`, e os testes usam `200ms,500ms,1s`. **API e worker precisam usar os mesmos níveis.** Se divergirem não há 406 (os nomes são diferentes), mas surgem filas extras. A conversão de `"5s,25s,125s"` para `List<Duration>` pelo binder do Boot fica coberta por um teste com `ApplicationContextRunner` [ASSUMED: comportamento padrão do binder, conferido no teste].

### Padrão 4: criação na API (API-01/02/04)
```java
public PublishResult publish(NotificationMessage msg, Duration timeout) {
  var cd = new CorrelationData(msg.notificationId());          // id único: necessário para getReturned()
  rabbit.convertAndSend(MAIN_EXCHANGE, msg.channel().routingKey(), msg, m -> {
    m.getMessageProperties().setMessageId(msg.notificationId());
    m.getMessageProperties().setHeader(NotifyHeaders.RETRY_COUNT, 0);
    return m;                                                    // delivery mode PERSISTENT é o padrão
  }, cd);
  try {
    var confirm = cd.getFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (cd.getReturned() != null) return PublishResult.unroutable(cd.getReturned().getReplyText());
    return confirm.isAck() ? PublishResult.ok() : PublishResult.nacked(confirm.getReason());
  } catch (TimeoutException e) { return PublishResult.timeout(); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); return PublishResult.timeout(); }
    catch (ExecutionException e) { return PublishResult.nacked(e.getCause().getMessage()); }
}
```
[VERIFIED: CorrelationData.java v3.2.12 l.58-59 "Must be unique if returns are enabled to allow population of the {@link #setReturned(ReturnedMessage) returned} message."; l.93 `public CompletableFuture<Confirm> getFuture()`; l.104 `public ReturnedMessage getReturned()`; l.140 `public boolean isAck()`.]

Serviço: `insert` do documento PENDING (`retryCount=0`, `attempts=[]`, `createdAt=updatedAt=now`) → `publish`. Se o publish não for ok → `updateFirst(query(_id=id, status=PENDING), set(status, PUBLISH_FAILED).set(lastError, …).set(updatedAt, now))`:
- `modifiedCount == 1` → 503 com Problem Detail (`detail` contém o id) + `Retry-After` (configurável, padrão 5).
- `modifiedCount == 0` → o worker já reivindicou a notificação, então a mensagem chegou. Responda **202** normalmente (Armadilha 4).

Resposta 202: `ResponseEntity.accepted().location(URI.create("/notifications/" + id)).body(new CreateNotificationResponse(id, PENDING, createdAt))`.

Não grave nenhum status depois do ack (nada de "QUEUED"). Isso causaria corrida com o worker.

### Padrão 5: Problem Details (API-06)
Crie um `@RestControllerAdvice class ApiExceptionHandler extends ResponseEntityExceptionHandler`. Com esse bean, o handler próprio do Boot é desativado [VERIFIED: WebMvcAutoConfiguration.java v3.5.16 l.674-678 `@ConditionalOnBooleanProperty("spring.mvc.problemdetails.enabled")` … `@ConditionalOnMissingBean(ResponseEntityExceptionHandler.class)`], e não é preciso ligar `spring.mvc.problemdetails.enabled`.
- Sobrescreva `handleMethodArgumentNotValid`: `ProblemDetail` com `type`, `title="Requisição inválida"`, `status=400` e `setProperty("errors", List.of({field, message}))`.
- Sobrescreva `handleHttpMessageNotReadable`: se a causa for `InvalidFormatException` num campo do enum `Channel`, gere `errors=[{field:"channel", message:"valores aceitos: EMAIL, SMS, WEBHOOK"}]`. JSON malformado também gera 400.
- `NotificationNotFoundException` → 404. `PublishFailedException(id)` → 503 com header `Retry-After`.
- Constantes de `type` numa classe `ProblemTypes` (sugestão: `urn:notify-hub:problem:validation-error`, `…:publish-failed`, `…:not-found`; o RFC 9457 aceita URIs não-HTTP).
- Destinatário por canal: uma constraint **de classe** `@ValidRecipient` no record de requisição que delega para `RecipientRules.isValid(channel, recipient)` (common). Ela gera um `FieldError` em `recipient` via `context.buildConstraintViolationWithTemplate(...).addPropertyNode("recipient")`, e os erros caem no mesmo `errors[]`.
- `RecipientRules`: e-mail `^[^@\s]+@[^@\s]+\.[^@\s]+$` (≤254); SMS `^\+[1-9]\d{7,14}$`; webhook: `URI` absoluta, `scheme ∈ {http, https}`, `host != null`, ≤2048. Os casos mágicos `always-transient@fail.test`, `+5500000000001` e `https://fail.test/x` passam (cubra com teste).
- Chaves de `variables`: `^[a-zA-Z][a-zA-Z0-9_]{0,63}$` (sem `.`, sem `$`, sem `_` inicial, o que reserva `_notificationId`). No máximo 50 chaves. Valores escalares (string/número/booleano, string ≤1000).

### Padrão 6: índices explícitos (FND-04)
```java
public void createIndexes() {   // idempotente; chamado no startup da API e do worker
  IndexOperations ops = mongo.indexOps(Collections.NOTIFICATIONS);
  ops.createIndex(new Index().on("status", ASC).on("channel", ASC).on("createdAt", DESC).on("_id", DESC).named("status_channel_createdAt"));
  ops.createIndex(new Index().on("channel", ASC).on("createdAt", DESC).on("_id", DESC).named("channel_createdAt"));
  ops.createIndex(new Index().on("createdAt", DESC).on("_id", DESC).named("createdAt"));
}
```
Use `createIndex`, não `ensureIndex` [VERIFIED: IndexOperations.java 4.5.13 l.36-39 "@deprecated since 4.5, in favor of {@link #createIndex(IndexDefinition)}." `@Deprecated(since = "4.5", forRemoval = true)`]. Nomeie os índices: `createIndex` "Might error if the collection already defines an index with the same name but different settings" (l.44-46), então nomes estáveis tornam essa situação explícita. O índice parcial de `Idempotency-Key` é da Fase 3. `templates` usa só `_id`.

### Padrão 7: listeners por canal em VT (WRK-01/05)
```java
@RabbitListener(id = "listener-email",
    queues = "#{T(dev.notifyhub.common.domain.Channel).EMAIL.queue()}",
    concurrency = "${notify.worker.channels.email.concurrency:10}")
public void onMessage(NotificationMessage msg,
                      @Header(name = NotifyHeaders.RETRY_COUNT, required = false) Object retryHeader,
                      @Header(AmqpHeaders.REDELIVERED) boolean redelivered) {
  processor.process(Channel.EMAIL, msg, RetryHeaders.parse(retryHeader), redelivered);
}
```
- Com `spring.threads.virtual.enabled=true`, o Boot instala o executor de VT na **configurer** [VERIFIED: RabbitAnnotationDrivenConfiguration.java v3.5.16 l.76-79 `@ConditionalOnThreading(Threading.VIRTUAL)` … `configurer.setTaskExecutor(new VirtualThreadTaskExecutor("rabbit-simple-"));`]. Não crie uma `SimpleRabbitListenerContainerFactory` à mão. Se precisar de uma, ela tem de passar por `SimpleRabbitListenerContainerFactoryConfigurer.configure(...)`, senão o executor de VT se perde (STATE.md, Blockers).
- A configurer aplica `auto-startup`, `prefetch` e `default-requeue-rejected` [VERIFIED: AbstractRabbitListenerContainerFactoryConfigurer.java l.118 `factory.setAutoStartup(configuration.isAutoStartup());`, l.123 `factory.setPrefetchCount(configuration.getPrefetch());`, l.125-126 `factory.setDefaultRequeueRejected(...)`].
- `RetryHeaders.parse(Object)`: aceita `Number`, `String` numérica ou ausente (vira 0). Mensagens publicadas pelo painel chegam com o header como **string**.
- Se `msg.channel()` for diferente do canal do listener → `AmqpRejectAndDontRequeueException` (vai para a DLQ).
- Prova de VT (critério 5): grave `thread` (`Thread.currentThread().toString()`, que mostra `VirtualThread[#…]/runnable@ForkJoinPool…`, ou nome + `isVirtual()`) no `AttemptEntry`, e logue no INFO no primeiro processamento.

### Padrão 8: claim e resultado atômicos (WRK-02/04)
```java
// Claim (Fase 1). A Fase 2 acrescenta o ramo PROCESSING+redelivered/lease (WRK-03)
Query q = query(where("_id").is(id)
    .and("status").in(claimableStatuses())          // PENDING, RETRYING, PUBLISH_FAILED (da tabela)
    .and("retryCount").lte(h));
Update u = new Update().set("status", PROCESSING).set("retryCount", h)
    .set("claimedAt", now).set("leaseUntil", now.plus(lease)).set("updatedAt", now);
NotificationDocument doc = mongo.findAndModify(q, u, FindAndModifyOptions.options().returnNew(true), NotificationDocument.class);
if (doc == null) { log.info("mensagem duplicada/obsoleta descartada id={}", id); return; }   // ack, sem envio

// Resultado SENT: uma única operação, cercada pelo fence (status + retryCount)
UpdateResult r = mongo.updateFirst(
    query(where("_id").is(id).and("status").is(PROCESSING).and("retryCount").is(h)),
    new Update().set("status", SENT).set("rendered", rendered).set("sentAt", now).set("updatedAt", now)
        .unset("lastError").unset("leaseUntil")
        .push("attempts").slice(-50).each(attempt),
    NotificationDocument.class);
if (r.getModifiedCount() == 0) log.warn("fence perdido id={} (reclaim concorrente)", id);
```
O `$slice` exige `$each` [VERIFIED: Update.java 4.5.13 l.808 "Propagates {@code $slice} to {@code $push}. {@code $slice} requires the {@code $each operator}." e l.801 `public Update each(Object... values)`]. Chamar `.slice(-50)` antes de `.each(...)` monta os dois modificadores.

Nunca use `save()`/`repository.save()` em transições (sobrescreve o documento e perde `$push` concorrente).

`AttemptEntry`: `number`, `retryLevel` (=h), `startedAt`, `durationMs`, `outcome` (`SUCCESS` na Fase 1; enum completo no common), `errorCode`, `errorMessage`, `failureClass` (null na Fase 1) e `thread`. Para o `number` continuar monotônico depois do reprocessamento da Fase 3 (que zera `retryCount`), mantenha um campo `attemptCount` no documento, incrementado com `$inc` no mesmo update, e use `attemptCount+1` lido no claim.

**Falhas na Fase 1:** qualquer exceção sobe e o container rejeita sem requeue → DLQ. O documento fica em PROCESSING. Essa lacuna é conhecida e fechada na Fase 2 (DLV-03/06 + WRK-03). Não construa o classificador agora.

### Padrão 9: templates, seed e ordem de inicialização (TPL-01)
Documento `templates` (um por template):
```json
{ "_id": "order-shipped", "version": 1, "requiredVariables": ["name", "orderId"],
  "variants": {
    "EMAIL":   { "subject": "Seu pedido {{orderId}} foi enviado", "body": "<p>Olá, {{name}}! Seu pedido <strong>{{orderId}}</strong> saiu para entrega.</p>" },
    "SMS":     { "body": "{{name}}, seu pedido {{orderId}} saiu para entrega." },
    "WEBHOOK": { "body": "{\"event\":\"order.shipped\",\"notificationId\":\"{{_notificationId}}\",\"data\":{\"name\":\"{{name}}\",\"orderId\":\"{{orderId}}\"}}" } } }
```
Renderizador: dois compiladores imutáveis. `HTML = Mustache.compiler()` para o corpo do EMAIL e `PLAIN = Mustache.compiler().escapeHTML(false)` para SMS, WEBHOOK e o assunto do e-mail (o assunto é texto puro; escapá-lo geraria `&amp;`).

Fatos verificados de JMustache 1.16:
- o compilador padrão escapa HTML e não tem valor para nulos/ausentes [VERIFIED: Mustache.java tag jmustache-1.16 l.418-420 `new Compiler(/*standardsMode=*/false, /*strictSections=*/false, /*nullValue=*/null, /*missingIsNull=*/false, …, DEFAULT_FORMATTER, Escapers.HTML, …)`];
- variável que resolve para null lança exceção quando `nullValue` é null [VERIFIED: l.46-48 "A value to use when a variable resolves to null. If this value is null (which is the default null value), an exception will be thrown."];
- `escapeHTML(false)` usa `Escapers.NONE` [VERIFIED: l.96-97];
- **nunca** use `defaultValue(...)`, que liga `missingIsNull=true` [VERIFIED: l.123-124 `new Compiler(this.standardsMode, this.strictSections, defaultValue, true, …`].

Contexto de renderização: `new HashMap<>(doc.variables())` + `put("_notificationId", id)`. Cache opcional de `Template` compilado por `(templateId, version, channel, parte)` em `ConcurrentHashMap`. Não faça I/O dentro de `computeIfAbsent`.

Seeder (`ApplicationRunner`, D-08): lê `classpath*:seed/templates/*.json` com `PathMatchingResourcePatternResolver` (funciona dentro do jar) e, para cada template:
1. `existing = findById`;
2. se ausente → `insert` (capture `DuplicateKeyException` se outro processo inseriu antes);
3. se `existing.version < seed.version` → `findAndReplace(query(_id, version=existing.version), seed)`;
4. senão → nada.

**Não** faça `upsert` com filtro `version < n` (Armadilha 2).

**Ordem de inicialização do worker (Armadilha 1):** `spring.rabbitmq.listener.simple.auto-startup: false` + runners ordenados:
```java
@Bean @Order(1) ApplicationRunner indexes(MongoIndexInitializer i) { return a -> i.createIndexes(); }
@Bean @Order(2) ApplicationRunner seed(TemplateSeeder s) { return a -> s.seed(); }
@Bean @Order(3) ApplicationRunner startListeners(AmqpAdmin admin, RabbitListenerEndpointRegistry reg) {
  return a -> { ((RabbitAdmin) admin).initialize(); reg.start(); };
}
```
Depois do refresh, `registry.start()` inicia os containers mesmo com `autoStartup=false` [VERIFIED: RabbitListenerEndpointRegistry.java v3.2.12 l.295-297 `private void startIfNecessary(MessageListenerContainer listenerContainer) { if (this.contextRefreshed || listenerContainer.isAutoStartup()) { listenerContainer.start();`]. Os runners rodam depois do refresh [VERIFIED: SpringApplication.java v3.5.16 l.318 `refreshContext(context);` … l.325 `callRunners(context, applicationArguments);`].

**API:** um runner `@Order(1)` cria os índices e outro `@Order(2)` chama `amqpAdmin.initialize()`. Sem isso, o `RabbitAdmin` só declara quando a primeira conexão abre [VERIFIED: RabbitAdmin.java v3.2.12 l.608 `this.connectionFactory.addConnectionListener(connection -> {` e l.645-646 "Declares all the exchanges, queues and bindings … It should be safe (but unnecessary) to call this method more than once."]. O bean `AmqpAdmin` é um `RabbitAdmin` criado pelo Boot [VERIFIED: RabbitAutoConfiguration.java l.166-169].

### Padrão 10: adapter simulado (DLV-01)
`SimulationProperties` em `notify.simulation.<email|sms|webhook>`: `latency-min` (50ms), `latency-max` (200ms) e `failure-rate` (0.0). Um `SimulatedChannelAdapter` por canal (ou um adapter parametrizado registrado 3 vezes):
- latência: `Thread.sleep(Duration)` com valor aleatório de `ThreadLocalRandom` no intervalo, **fora** de qualquer `synchronized`;
- falha: `if (rnd.nextDouble() < failureRate) throw new SimulatedDeliveryException(...)`;
- log do destinatário mascarado.

A interface `ChannelAdapter { Channel channel(); void send(String recipient, RenderedContent c); }` já fica pronta para o gateway de resiliência da Fase 2.

### Antipadrões a evitar
- `@CompoundIndex`/`@Indexed` contando com `auto-index-creation` (desligado por padrão).
- Um `@RabbitListener(queues = {email, sms, webhook})` único.
- `spring.rabbitmq.listener.simple.retry.enabled=true` ou `default-requeue-rejected` no padrão.
- `spring-boot-starter-mustache` (traz o view resolver).
- Montar `enabled_plugins` no RabbitMQ 4.3 (desnecessário, e sobrescreve o padrão da imagem).
- Compilar como template uma string vinda do cliente (SSTI). Templates vêm só do seed.
- `QUEUED` depois do confirm.

## Não construa à mão

| Problema | Não construa | Use | Por quê |
|----------|--------------|-----|---------|
| Executor de VT para listeners | factory própria com `Executors.newVirtualThreadPerTaskExecutor()` | `spring.threads.virtual.enabled=true` (configurer do Boot) | O Boot já faz isso; uma factory manual perde prefetch, requeue e o converter |
| Correlação de confirms | `ConfirmCallback` global + mapa de ids | `CorrelationData.getFuture()` por envio | Só existe um callback por template; o future já é por envio |
| Declaração de topologia | `channel.queueDeclare` manual | `Declarables` + `RabbitAdmin` | Redeclara a cada reconexão |
| Problem Details | JSON de erro próprio | `ProblemDetail` + `ResponseEntityExceptionHandler` | `application/problem+json` e cobertura das exceções do MVC |
| Conversão de payload | serialização Java | `Jackson2JsonMessageConverter` | Segurança e legibilidade no painel |
| Durations em propriedades | parse de string | binder do Boot (`List<Duration>`) | `5s`, `200ms` nativos |
| Atualização de status/histórico | read-modify-`save` | `findAndModify`/`updateFirst` com `$set` + `$push` | Atomicidade e fence |

## Estado em tempo de execução

Greenfield, sem renomeação. Mas o **estado persistido do broker** importa:

| Categoria | Item | Ação |
|-----------|------|------|
| Dados armazenados | Volume do RabbitMQ (filas e bindings de rodadas anteriores) e volume do Mongo | `docker compose down -v` ao mudar argumentos de fila; documentar no README (Fase 4) |
| Config de serviço vivo | Nada além do que o código declara | — |
| Estado registrado no SO | Nenhum | — |
| Segredos/env | Usuário do RabbitMQ (`notify`/`notify`, só para demo) | env no compose; propriedades da app |
| Artefatos de build | `target/` | `.gitignore` |

## Armadilhas comuns

(Complementam PITFALLS.md. As da pesquisa de projeto continuam valendo, em especial 1, 6, 7, 8, 18, 21 e 25.)

### Armadilha 1: listener consome antes do seed/índices
**O que dá errado:** com backlog na fila, o primeiro boot processa mensagens antes do `ApplicationRunner` do seed → "template inexistente" → DLQ.
**Causa:** os containers são `SmartLifecycle` e iniciam no refresh; os runners rodam depois (verificado acima).
**Como evitar:** `auto-startup: false` + runner final `registry.start()` (Padrão 9).
**Sinal de alerta:** mensagens na DLQ logo após um restart com a fila cheia.

### Armadilha 2: upsert com filtro de versão gera `DuplicateKeyException`
**O que dá errado:** `upsert(query(_id=X, version<2), …)` não casa quando o banco já tem uma versão ≥2 e tenta **inserir** o mesmo `_id` → E11000 → o worker não sobe.
**Como evitar:** ler → inserir ou `findAndReplace` condicional por `version` (Padrão 9). Teste: rodar o seed duas vezes e depois com uma versão menor.

### Armadilha 3: routing key de retry por ordinal duplica tokens ao trocar de profile
**O que dá errado:** com `email.retry.1` como chave, a fila antiga `notify.email.retry.5s` (do profile padrão, persistida no volume) e a nova `notify.email.retry.1s` (do `demo`) ficam ligadas à mesma chave no exchange direct. Um republish da Fase 2 vira duas mensagens.
**Como evitar:** chave = `<ch>.retry.<rótulo do TTL>` (`email.retry.1s`). As filas antigas continuam no broker, mas sem tráfego. [ASSUMED: semântica padrão de exchange direct (entrega a todas as filas ligadas com a chave); não testado nesta sessão.]

### Armadilha 4: 503 enganoso quando o worker já pegou a mensagem
**O que dá errado:** o confirm demora mais que o timeout, mas a mensagem chegou e o worker já marcou PROCESSING/SENT. A API responde 503, e o cliente (com Idempotency-Key na Fase 3) reenvia.
**Como evitar:** o update condicional `PENDING → PUBLISH_FAILED` que casa 0 documentos significa entregue, então responda 202.

### Armadilha 5: chaves de `variables` com ponto
**O que dá errado:** `MappingException: Map key a.b contains dots but no replacement was configured` → 500 no POST [VERIFIED: MappingMongoConverter.java 4.5.13 l.1256-1259 `if (mapKeyDotReplacement == null) { throw new MappingException(String.format("Map key %s contains dots but no replacement was configured; Make"`].
**Como evitar:** regex de chave na API (Padrão 5).

### Armadilha 6: VT + SMLC com concorrência maior que o número de CPUs
**Histórico:** Boot #38563. O container simples travava na inicialização ("Consumer failed to start in 60000 milliseconds") com concorrência > CPUs lógicas, por causa de `synchronized (consumersMonitor)` (Spring AMQP #2481/#2568).
**Estado na 3.2.12:** o SMLC usa `consumersLock` (`ReentrantLock`) em `doStart` e `isActive` [VERIFIED: SimpleMessageListenerContainer.java v3.2.12 l.580 `this.consumersLock.lock();`, l.606 `waitForConsumersToStart(processors);`, l.742-744 `private boolean isActive(BlockingQueueConsumer consumer) {` … `this.consumersLock.lock();`]. Lock não prende o carrier, então o travamento **não deve** ocorrer [ASSUMED: conclusão a partir do código, não executado].
**Como evitar:** a máquina tem **8 CPUs lógicas**. Use concorrência padrão 10 por canal (acima de 8 de propósito) e inclua na verificação "worker sobe sem `Consumer failed to start`". Plano B: `spring.rabbitmq.listener.type=direct` + `direct.consumers-per-queue`.

### Armadilha 7: templates JSON e chaves `{{{`
**O que dá errado:** em JSON, `{` seguido de `{{var}}` vira `{{{var}}}` (triple mustache sem escape) ou dá erro de parse.
**Como evitar:** valores sempre entre aspas no envelope (`"{{name}}"`). Teste unitário que renderiza cada variante WEBHOOK do seed e faz `objectMapper.readTree(body)`. Aspas dentro dos valores continuam quebrando o JSON até a Fase 2 (TPL-03). [ASSUMED: comportamento do parser com `{{{`; coberto pelo teste.]

### Armadilha 8: hostname do RabbitMQ em compose
**O que dá errado:** o RabbitMQ guarda dados por nome de nó (`rabbit@<hostname>`). Sem `hostname:` fixo, um container recriado tem outro hostname e "perde" as filas do volume.
**Como evitar:** `hostname: notify-rabbit` no serviço. [ASSUMED: documentado na página da imagem no Docker Hub; não buscado nesta sessão.]

### Armadilha 9: daemon do Docker desligado / Maven ausente
Neste ambiente o Docker Desktop não está rodando (`failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine`) e não há `mvn` no PATH. O plano 1 precisa gerar o wrapper (comando acima) e a verificação precisa de um checkpoint "inicie o Docker Desktop".

## Exemplos de código

### `docker-compose.yml` (Fase 1)
```yaml
name: notify-hub
services:
  rabbitmq:
    image: rabbitmq:4.3.6-management
    hostname: notify-rabbit
    environment:
      RABBITMQ_DEFAULT_USER: notify
      RABBITMQ_DEFAULT_PASS: notify
    ports: ["127.0.0.1:5672:5672", "127.0.0.1:15672:15672", "127.0.0.1:15692:15692"]
    volumes: ["rabbitmq-data:/var/lib/rabbitmq"]
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "check_port_connectivity"]
      interval: 10s
      timeout: 10s
      retries: 6
      start_period: 30s
  mongo:
    image: mongo:8.0.32
    ports: ["127.0.0.1:27017:27017"]
    volumes: ["mongo-data:/data/db"]
    healthcheck:
      test: ["CMD", "mongosh", "--quiet", "--eval", "db.adminCommand('ping').ok"]
      interval: 10s
      timeout: 5s
      retries: 6
      start_period: 20s
volumes:
  rabbitmq-data:
  mongo-data:
```
- O `rabbitmq_prometheus` já vem habilitado na imagem [VERIFIED: docker-library/rabbitmq 4.3/ubuntu/Dockerfile (ENV RABBITMQ_VERSION=4.3.6) l.302 `RUN gosu rabbitmq rabbitmq-plugins enable --offline rabbitmq_prometheus`], e a variante management habilita `rabbitmq_management` (4.3/ubuntu/management/Dockerfile l.14).
- O `guest` é aceito pela rede [VERIFIED: 4.3/ubuntu/10-defaults.conf `loopback_users.guest = false`]. Mesmo assim, use um usuário dedicado. `RABBITMQ_DEFAULT_USER/PASS` **não** estão na lista de variáveis depreciadas do entrypoint (só as variantes `_FILE`) [VERIFIED: docker-entrypoint.sh l.13-15 `deprecatedEnvVars=( RABBITMQ_DEFAULT_PASS_FILE RABBITMQ_DEFAULT_USER_FILE`].
- O `mongosh` existir na imagem `mongo:8.0.32` é [ASSUMED] (padrão das imagens mongo ≥6).
- Sem `version:` no topo.

### `notify-worker/src/main/resources/application.yml`
```yaml
server.port: 8081
spring:
  application.name: notify-worker
  threads.virtual.enabled: ${VIRTUAL_THREADS:true}
  data.mongodb.uri: ${MONGODB_URI:mongodb://localhost:27017/notify}
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    username: ${RABBITMQ_USER:notify}
    password: ${RABBITMQ_PASS:notify}
    publisher-confirm-type: correlated     # a Fase 2 publica retries com confirm
    publisher-returns: true
    template.mandatory: true
    listener:
      simple:
        auto-startup: false               # iniciado pelo runner após índices + seed
        acknowledge-mode: auto
        prefetch: 10
        default-requeue-rejected: false
        retry.enabled: false              # explícito: retry só por filas de delay
management.endpoints.web.exposure.include: health,info
notify:
  worker.channels: { email.concurrency: 10, sms.concurrency: 10, webhook.concurrency: 10 }
  simulation:
    email:   { latency-min: 50ms, latency-max: 200ms, failure-rate: 0.0 }
    sms:     { latency-min: 50ms, latency-max: 200ms, failure-rate: 0.0 }
    webhook: { latency-min: 50ms, latency-max: 200ms, failure-rate: 0.0 }
```
O `mandatory` cai para o valor de `publisher-returns` se ficar ausente [VERIFIED: RabbitTemplateConfigurer.java l.129-131 `return (mandatory != null) ? mandatory : this.rabbitProperties.isPublisherReturns();`]. Defina os dois de forma explícita mesmo assim.

API (`application.yml`): `server.port: 8080`, a mesma conexão, `publisher-confirm-type: correlated`, `publisher-returns: true`, `template.mandatory: true`, `spring.rabbitmq.cache.channel.size: 50` (vários publishers concorrentes em VT), `notify.publish.confirm-timeout: 5s`, `notify.publish.retry-after: 5s` e `spring.threads.virtual.enabled: ${VIRTUAL_THREADS:true}`. `application-demo.yml` nas duas apps: `notify.topology.retry-levels: 1s,5s,25s`.

## Estado da arte

| Antigo | Atual | Quando | Impacto |
|--------|-------|--------|---------|
| `IndexOperations.ensureIndex` | `createIndex` | Spring Data MongoDB 4.5 (depreciado para remoção) | Corrige ARCHITECTURE.md/PITFALLS.md, que citam `ensureIndex` |
| `synchronized(consumersMonitor)` no SMLC | `consumersLock` (`ReentrantLock`) | presente na 3.2.12 | VT + concorrência > CPUs deve funcionar; confirmar no smoke |
| Montar `enabled_plugins` para o Prometheus | Já habilitado na imagem oficial | docker-library/rabbitmq #419 | Compose mais simples |

## Registro de suposições

| # | Suposição | Seção | Risco se estiver errada |
|---|-----------|-------|-------------------------|
| A1 | O binder do Boot converte `"5s,25s,125s"` em `List<Duration>` no record com `@DefaultValue` | Padrão 3 | Baixo: o teste com `ApplicationContextRunner` pega; alternativa: `List<String>` + parse |
| A2 | Exchange direct entrega a todas as filas ligadas com a mesma chave (motivo da chave com rótulo) | Armadilha 3 | Baixo: a recomendação é segura de qualquer forma |
| A3 | O SMLC na 3.2.12 não trava com VT e concorrência 10 > 8 CPUs | Armadilha 6 | Médio: o worker não sobe; plano B é o container `direct` |
| A4 | `{{{` adjacente em JSON quebra o parse e os valores entre aspas evitam o problema | Armadilha 7 | Baixo: teste do seed |
| A5 | O `hostname` fixo é necessário para o volume do RabbitMQ sobreviver à recriação do container | Armadilha 8 | Baixo: só custa um `down -v` |
| A6 | O goal `wrapper:wrapper` funciona com a distribuição em cache | Bootstrap | Baixo: plano B é baixar o zip do Maven |
| A7 | `mongosh` está presente em `mongo:8.0.32` | compose | Baixo: healthcheck falha visivelmente |
| A8 | Valores de `variables` restritos a escalares não limitam os templates do seed | Padrão 5 | Baixo: os templates D-06 usam só strings |

## Perguntas em aberto

1. **Numeração de `attempts[].number` depois do reprocessamento (Fase 3).**
   - Recomendação: o campo `attemptCount` com `$inc` já na Fase 1 (Padrão 8), barato e à prova de reset.
2. **O que acontece com o documento quando o worker rejeita na Fase 1.**
   - O que se sabe: ele fica em PROCESSING (sem classificador).
   - Recomendação: aceitar e registrar como lacuna conhecida, fechada pela Fase 2. O critério 4 só exige que a mensagem chegue à DLQ sem loop.
3. **Valores aninhados em `variables`.**
   - Recomendação: escalares apenas na v1 (simplifica o escape JSON da Fase 2). Se o usuário quiser objetos, a validação de chaves precisa ser recursiva.

## Disponibilidade do ambiente

| Dependência | Usada por | Disponível | Versão | Alternativa |
|-------------|-----------|------------|--------|-------------|
| JDK 21 | build/execução | ✓ | Oracle 21.0.10 LTS | — (Temurin entra só nas imagens da Fase 4) |
| Maven | build | ✗ no PATH | dist. 3.9.16 em `~/.m2/wrapper/dists` | gerar o wrapper com a dist. em cache (comando acima) |
| Repositório local | build | parcial | só o BOM 3.5.16 + `resilience4j-bom` em cache | o Maven Central responde (HTTP 200) |
| Docker CLI / Compose | infra | ✓ | Compose v5.5.1 | — |
| Daemon do Docker | compose, smoke | ✗ (Docker Desktop parado) | — | **Bloqueante para a verificação:** iniciar o Docker Desktop |
| curl | smoke manual | ✓ | 8.18.0 | PowerShell `Invoke-RestMethod` |
| CPUs lógicas | dimensionamento de VT | 8 | — | — |

**Ausentes sem alternativa:** daemon do Docker (só precisa ser iniciado; não bloqueia código nem testes unitários).
**Ausentes com alternativa:** Maven (wrapper).

## Arquitetura de validação

> `workflow.nyquist_validation` está `false` no config, mas o orquestrador pediu esta seção explicitamente.

### Framework de testes
| Propriedade | Valor |
|-------------|-------|
| Framework | JUnit 5.12.2 + AssertJ + Mockito (via `spring-boot-starter-test`), Surefire 3.5.6 |
| Config | nenhum arquivo próprio; Surefire do `starter-parent`. Failsafe/`*IT` é da Fase 3 (TST-04) |
| Execução rápida (sem Docker) | `./mvnw -q test` (Windows: `mvnw.cmd -q test`) |
| Suíte completa da fase | `./mvnw verify` (compila 3 módulos + unitários + empacota) |
| Por módulo | `./mvnw -q -pl notify-common test` / `-pl notify-api -am test` / `-pl notify-worker -am test` |
| Ponta a ponta | `docker compose up -d` → `./mvnw -pl notify-worker spring-boot:run` e `./mvnw -pl notify-api spring-boot:run` (dois terminais) → `curl` (checkpoint humano) |

### Requisito → teste
| Req | Comportamento | Tipo | Comando / prova | Existe? |
|-----|---------------|------|-----------------|---------|
| FND-01 | 3 módulos compilam no wrapper | build | `./mvnw verify` | ❌ Wave 0 |
| FND-02 | Conversões do enum Channel; tabela de transições | unit | `NotificationStatusTest`, `ChannelTest` | ❌ |
| FND-03 | infra saudável | smoke | `docker compose up -d && docker compose ps` (ambos `healthy`) | manual |
| FND-04 | índices criados com nome e chaves | unit + smoke | `MongoIndexInitializerTest` (mock de `IndexOperations`, conferir `IndexDefinition`); smoke `mongosh notify --eval 'db.notifications.getIndexes()'` | ❌ |
| MSG-01..04 | 3 exchanges, 15 filas, args (`x-message-ttl` Integer, DLX/DLRK), bindings | unit | `NotifyTopologyTest`: instancia `Declarables` com 5s/25s/125s e confere nomes, args e chaves; cada chave de binding casa só com a fila esperada | ❌ |
| MSG-05 | `demo` → 1s/5s/25s; testes → ms | unit | `TopologyPropertiesTest` com `ApplicationContextRunner` (`retry-levels=1s,5s,25s` e `200ms,500ms,1s`; lista não crescente falha); `TtlLabelTest` | ❌ |
| API-01 | 202 + Location + corpo `{id,status,createdAt}`; insert antes do publish | unit | `NotificationControllerTest` (`@WebMvcTest`); `NotificationServiceTest` (`InOrder`: insert → publish) | ❌ |
| API-02 | ack/nack/devolvida/timeout → resultado; PUBLISH_FAILED condicional; 0 casados → 202; 503 + Retry-After | unit | `NotificationPublisherTest` (mock de `RabbitTemplate` com `doAnswer` completando o `CorrelationData` / `setReturned` / sem completar com timeout de 50ms); `NotificationServiceTest`; `@WebMvcTest` 503 | ❌ |
| API-04 | GET completo / 404 Problem Detail | unit | `@WebMvcTest` com `NotificationQueryService` mockado | ❌ |
| API-06 | 400 problem+json com `errors[]`: canal desconhecido, campo ausente, destinatário inválido por canal, chave de variável inválida; destinatários mágicos aceitos | unit | `@WebMvcTest` parametrizado; `RecipientRulesTest` | ❌ |
| WRK-01 | requeue false, retry off, prefetch 10, um container por canal | unit + smoke | `WorkerConfigTest` (contexto com Rabbit mockado não é trivial; aceitável conferir no smoke: painel mostra 1 consumidor ×10 por fila, prefetch 10) | smoke |
| WRK-02 | claim nulo → nenhum envio; query do claim correta | unit | `DeliveryProcessorTest` (mocks); `NotificationStateServiceTest` conferindo `query.getQueryObject()`/`update.getUpdateObject()` | ❌ |
| WRK-04 | `markSent` = um `updateFirst` com `$set` + `$push {$each,$slice:-50}` cercado pelo fence | unit | `NotificationStateServiceTest` (capturar `Update` e conferir `getUpdateObject()`) | ❌ |
| WRK-05 | listener roda em VT com concorrência configurável | smoke | log/`attempts[].thread` contém `VirtualThread`; worker sobe com concorrência 10 > 8 CPUs | manual |
| TPL-01 | seed idempotente e de versão crescente; 3×3 variantes; WEBHOOK é JSON válido, sem `&quot;` | unit | `TemplateSeederTest` (MongoTemplate mockado: ausente → insert, menor → replace, igual/maior → nada); `SeedTemplatesTest` (renderiza todos e faz `readTree` do webhook); `TemplateRendererTest` | ❌ |
| DLV-01 | latência no intervalo; `failure-rate` 1.0 sempre lança e 0.0 nunca | unit | `SimulatedChannelAdapterTest` (latência curta) | ❌ |

### Roteiro ponta a ponta (checkpoint humano no fim da fase)
1. `docker compose up -d` → `docker compose ps` mostra os dois serviços `healthy`.
2. Subir o worker e depois a API. Painel http://localhost:15672 (notify/notify): exchanges `notify.exchange`, `notify.retry`, `notify.dlx`; 15 filas, com `notify.<ch>.retry.{5s,25s,125s}`. `db.notifications.getIndexes()` lista os 3 índices nomeados, e `db.templates.find()` traz 3 documentos.
3. `curl -i -X POST localhost:8080/notifications -H 'Content-Type: application/json' -d '{"channel":"EMAIL","recipient":"ana@exemplo.com","templateCode":"welcome","variables":{"name":"Ana"}}'` → 202 + `Location`. `GET` no `Location` → `SENT`, `rendered.subject/body` em pt-BR e `attempts[0]` com `startedAt`, `durationMs` e `thread` virtual.
4. 400: `"channel":"PUSH"`; sem `recipient`; SMS com `"recipient":"123"`; variável `"a.b"`.
5. 503: no painel, remover o binding `notify.exchange → notify.email` e fazer o POST → 503 + `Retry-After: 5`, documento `PUBLISH_FAILED`. Reiniciar a API (redeclara o binding).
6. Duplicata: no painel, publicar em `notify.exchange` / `notification.email` o payload `{"notificationId":"<id SENT>","channel":"EMAIL","templateCode":"welcome"}` → `attempts` continua com 1 entrada e o log diz "duplicada/obsoleta".
7. DLQ sem loop: POST com `templateCode` inexistente (ex.: `"nao-existe"`) → a mensagem aparece em `notify.email.dlq`, entregue 1 vez (sem `redelivered` repetido).
8. `docker compose down -v`, subir de novo com `--spring.profiles.active=demo` nas duas apps → filas `notify.<ch>.retry.{1s,5s,25s}`.

### Amostragem
- **Por commit de tarefa:** `./mvnw -q -pl <módulo> -am test`
- **Por wave:** `./mvnw verify`
- **Gate da fase:** `./mvnw verify` verde + roteiro ponta a ponta acima (requer Docker Desktop ligado)

### Lacunas do Wave 0
- [ ] `pom.xml` pai + POMs dos módulos + wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`)
- [ ] `.gitignore` (`target/`, `.idea/`, `*.iml`) e `.gitattributes`
- [ ] `src/test/java` em cada módulo; nenhum framework extra a instalar

## Domínio de segurança

`security_enforcement: true`, ASVS nível 1.

### Categorias ASVS aplicáveis
| Categoria | Aplica | Controle |
|-----------|--------|----------|
| V2 Autenticação | não nesta fase | API key admin é da Fase 3 (ADM-04) |
| V3 Sessão | não | API stateless |
| V4 Controle de acesso | não nesta fase | — |
| V5 Validação de entrada | **sim** | Bean Validation + `@ValidRecipient` + regex de chave de variável + limites de tamanho e quantidade; enum desconhecido → 400 |
| V6 Criptografia | não | — |
| V7 Erros e logs | **sim** | Problem Details sem stack trace (`server.error.include-stacktrace` fica no padrão `never`); destinatário mascarado nos logs |
| V8 Proteção de dados | **sim** | PII (destinatário/variáveis) fora do payload AMQP e da DLQ (mensagem fina) |
| V12/V13 API | **sim** | `Content-Type: application/json` exigido; 415 para outros tipos (padrão do MVC) |
| V14 Configuração | **sim** | Portas da infra ligadas a `127.0.0.1`; actuator expõe só `health,info`; credenciais por env com padrão de demo documentado |

### Ameaças conhecidas para este stack
| Padrão | STRIDE | Mitigação |
|--------|--------|-----------|
| Injeção de operador NoSQL por chaves `$`/`.` em `variables` | Tampering | Regex `^[a-zA-Z][a-zA-Z0-9_]{0,63}$`; `Criteria` tipado; ids tratados como strings |
| SSTI (template enviado pelo cliente) | Elevation | Só se compilam templates do seed; variáveis são dados |
| Desserialização insegura na mensagem | Tampering | `Jackson2JsonMessageConverter` com tipo inferido do parâmetro; nada de `SimpleMessageConverter` |
| Poison message em loop | DoS | `default-requeue-rejected=false` + DLX da fila principal |
| Payload gigante | DoS | No máximo 50 variáveis, valor ≤1000 caracteres, destinatário ≤2048 |
| SSRF por destinatário de webhook | Info disclosure | Não se aplica (adapter simulado, sem chamada de saída); anotar no README que um adapter real exigiria allowlist |
| Exposição do painel/Mongo na LAN | Info disclosure | `127.0.0.1:` nos ports; Mongo sem autenticação é aceitável só em dev (documentar) |

## Fontes

### Primárias (ALTA, lidas nesta sessão)
- `~/.m2/repository/org/springframework/boot/spring-boot-dependencies/3.5.16/spring-boot-dependencies-3.5.16.pom`: versões gerenciadas
- Spring Boot v3.5.16: `RabbitAnnotationDrivenConfiguration`, `RabbitProperties`, `RabbitTemplateConfigurer`, `AbstractRabbitListenerContainerFactoryConfigurer`, `RabbitAutoConfiguration`, `SpringApplication`, `WebMvcAutoConfiguration` (raw.githubusercontent.com/spring-projects/spring-boot/v3.5.16/…)
- Spring AMQP v3.2.12: `CorrelationData`, `RabbitAdmin`, `RabbitListenerEndpointRegistry`, `SimpleMessageListenerContainer`, `Jackson2JsonMessageConverter`, `AbstractJackson2MessageConverter`
- Spring Data MongoDB 4.5.13: `IndexOperations`, `Update`, `MappingMongoConverter`
- JMustache tag `jmustache-1.16`: `Mustache.java`
- docker-library/rabbitmq (master, 4.3 = 4.3.6): `4.3/ubuntu/Dockerfile`, `4.3/ubuntu/management/Dockerfile`, `10-defaults.conf`, `docker-entrypoint.sh`
- Maven Central `maven-metadata.xml`: `maven-wrapper-plugin` (3.3.4) e `jmustache` (1.16)

### Secundárias (MÉDIA)
- [Spring Boot #38563](https://github.com/spring-projects/spring-boot/issues/38563), [Spring AMQP #2481](https://github.com/spring-projects/spring-amqp/issues/2481): histórico do travamento VT + SMLC (fechado no milestone 3.1.1; parte movida para #2568)
- [Threading and Asynchronous Consumers (Spring AMQP)](https://docs.spring.io/spring-amqp/reference/amqp/receiving-messages/threading.html)
- `.planning/research/*.md` (pesquisa de projeto de 2026-10-09)

### Terciárias (BAIXA)
- Docker Hub `rabbitmq` (hostname/nome de nó) e a presença de `mongosh` na imagem mongo: conhecimento prévio, não buscado

## Metadados

**Confiança:**
- Stack padrão: ALTA (BOM local + Maven Central)
- Arquitetura: ALTA (padrões já decididos; detalhes de API conferidos no código das tags exatas)
- Armadilhas: ALTA para 1, 2, 5 e 9 (código lido / ambiente sondado); MÉDIA para 3, 6, 7 e 8 (`[ASSUMED]`, cobertas por teste ou smoke)

**Data da pesquisa:** 2026-10-10
**Válida até:** 2026-11-10 (stack congelado no Boot 3.5.16, último patch OSS)

**Sugestão de divisão em planos para o planner:**
| Plano | Escopo | Requisitos | Depende de |
|-------|--------|------------|------------|
| 01 | Esqueleto Maven + wrapper + compose + `.gitattributes`/`.gitignore` | FND-01, FND-03 | — |
| 02 | `notify-common`: Channel, transições, record, headers, TopologyProperties/TtlLabel, Declarables, documentos, MongoIndexInitializer, RecipientRules + testes | FND-02, FND-04, MSG-01..05 | 01 |
| 03 | API: POST/GET, publisher, PUBLISH_FAILED/503, Problem Details, runner de startup + testes | API-01, 02, 04, 06 | 02 |
| 04 | Worker: listeners VT, claim, renderer, seed, adapter, markSent, ordem de startup + testes | WRK-01, 02, 04, 05, TPL-01, DLV-01 | 02 (paralelo ao 03) |
| 05 | Verificação ponta a ponta (roteiro acima), checkpoint humano com o Docker ligado | critérios 1–5 | 03, 04 |
