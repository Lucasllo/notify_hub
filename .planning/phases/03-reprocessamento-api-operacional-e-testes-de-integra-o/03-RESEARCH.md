# Fase 3: Reprocessamento, API operacional e testes de integração - Pesquisa

**Pesquisado em:** 2026-10-28
**Domínio:** Spring AMQP (pull com `basicGet` + ack manual, publish confirmado), Spring Data MongoDB (updates condicionais, índice único parcial, agregação), Spring MVC (interceptor de API key, Problem Details, paginação), springdoc 2.8, Testcontainers 1.21 com dois contextos Spring na mesma JVM, Maven Failsafe
**Confiança:** ALTA no código existente e nas APIs de biblioteca (lidas no código e no bytecode); MÉDIA no harness de dois contextos (mecanismos documentados, mas a combinação ainda não foi exercitada neste repositório; o plano precisa de um spike na Onda 0)

<user_constraints>
## Restrições do usuário (de CONTEXT.md)

### Decisões travadas

#### Reprocessamento da DLQ (ADM-01, ADM-02) e o CR-01
- **D-01:** Um documento **não terminal** cuja mensagem está na DLQ é reprocessado normalmente. Os estados são PENDING, RETRYING e PROCESSING com `leaseUntil < agora`, os casos do CR-01 em `02-.../deferred-items.md`. A tabela de `NotificationStatus` ganha as transições PENDING/RETRYING/PROCESSING → PENDING, **usadas só pelo reprocess**. O reprocess grava o evento REPROCESSED, zera o contador e republica. Não há sweeper de lease. Se a mensagem está na DLQ, nada mais vai entregá-la, então o reprocess é a saída. — **Reversibility:** costly — a tabela de transições é o contrato dos filtros condicionais da API e do worker (D-11 da Fase 2).
- **D-02:** Casos que não interrompem o lote:
  - **PROCESSING com lease válido** (um worker pode estar com ele): a mensagem é republicada no **fim da DLQ** com confirm, depois recebe ack, e conta como `skipped`.
  - **Documento SENT ou inexistente:** a mensagem recebe ack e sai da DLQ, contada como `discarded`, porque não há nada a entregar.

  Para não girar em círculo dentro de uma chamada, o lote termina quando N mensagens foram consumidas ou a DLQ esvazia. O planner garante que uma mensagem `skipped` não seja relida na mesma chamada, por exemplo limitando pelo tamanho da fila no início.
- **D-03:** Formato da resposta:
  - Lote concluído, inclusive quando a DLQ esvazia antes de N: **200** com `{requested, reprocessed, skipped, discarded}`.
  - **Parada na primeira falha** (Mongo ou republish com nack, timeout ou return): **503** em Problem Detail com as mesmas contagens parciais e o motivo, num conjunto fechado e sem texto livre do broker, como no T-02-01. A mensagem que falhou **volta para a DLQ** (nack com requeue / sem ack).
  - Sem API key ou com chave errada: nada é movido.
- **D-04:** `max` tem **padrão 100 e teto 1000**. Fora de 1..1000, ou canal inválido, devolve **400** em Problem Detail (ADM-04). Os dois valores são configuráveis por propriedade.

#### Idempotency-Key (API-03)
- **D-05:** `idempotencyKey` e `requestHash` ficam **no próprio `NotificationDocument`**, com **índice único parcial** (só onde a chave existe) criado pelo `MongoIndexInitializer`. O insert é atômico. Numa corrida entre dois POSTs com a mesma chave, o perdedor recebe `DuplicateKeyException`, lê o vencedor e segue a regra de replay. Não há coleção extra nem TTL: a chave vale enquanto o documento existir. — **Reversibility:** costly — campo persistido e índice único no Mongo. Mudar exige migração ou `docker compose down -v`.
- **D-06:** "Mesmo payload" quer dizer o mesmo **SHA-256 do pedido canônico**: channel, recipient, templateCode e variables com chaves ordenadas, calculado depois do parse. A mesma chave com hash diferente devolve **422** em Problem Detail.
- **D-07:** O replay (mesma chave e mesmo hash) devolve **o mesmo 202**: `Location: /notifications/{id}`, corpo `{id, status, createdAt}` com o **status atual** e o header **`Idempotent-Replayed: true`**.
- **D-08:** Um replay de documento em **PUBLISH_FAILED tenta republicar**: PUBLISH_FAILED → PENDING (transição que já existe) e publish com confirm. Devolve 202 se der certo e 503 com `Retry-After` se falhar de novo. Esse caminho é **o mesmo do `republish-failed` do admin** (ADM-03), num serviço só.

#### Listagem e stats (API-05, API-07)
- **D-09:** O `GET /notifications?status=&channel=&page=&size=` usa paginação **page/size com total**, envelope `{content, page, size, totalElements, totalPages}`, ordenado por `createdAt` desc. Precisa de um índice composto que cubra filtro e ordenação, como (status, channel, createdAt desc). O planner confere quais índices o `MongoIndexInitializer` já cria.
- **D-10:** A página tem **padrão 20 e máximo 100**. Fora do intervalo devolve **400** em Problem Detail, sem limitar em silêncio. Status e canal inválidos também dão 400.
- **D-11:** Cada item da lista é um **resumo**: `id, status, channel, recipient, templateCode, retryCount, lastError.code, createdAt, updatedAt`. Não traz `attempts[]`, `rendered` nem `variables`, que continuam no `GET /{id}` (D-03 da Fase 1).
- **D-12:** O `GET /notifications/stats` devolve `{total, byStatus:{...}, byChannel:{...}, byChannelAndStatus:{EMAIL:{SENT:n,...},...}}`, com **todos os canais e status presentes (zeros inclusive)** e calculado numa agregação `$group`. — **Reversibility:** costly — o README e possivelmente o k6 (Fase 4) leem esse formato.

#### Testes de integração (TST-02, TST-03, TST-04)
- **D-13:** Os ITs ponta a ponta ficam num **módulo novo `notify-it`**, só de teste, que depende de `notify-api` e `notify-worker` e sobe **os dois contextos Spring na mesma JVM** contra os mesmos containers. O fluxo real é HTTP → broker → worker → DLQ → reprocess HTTP → SENT. **Ponto de pesquisa:** os dois `application.yml` colidem no classpath (e os pacotes `dev.notifyhub.api`/`.worker` não podem se escanear mutuamente). Resolver com `spring.config.name` próprio por contexto, propriedades explícitas no `SpringApplicationBuilder` ou outro mecanismo, sem quebrar a execução normal dos apps. O POM pai ganha o módulo, e o Failsafe roda `*IT` no `verify`. — **Reversibility:** reversible.
- **D-14:** A escada usa **TTLs curtos só de teste**, por exemplo `notify.topology.retry-levels=200ms,500ms,1s`, e o Awaitility substitui qualquer `Thread.sleep`. Cada execução usa um broker novo, então os argumentos de fila diferentes não conflitam.
- **D-15:** Há um **profile próprio `it`** (`application-it.yml` no `notify-it`) que:
  - liga `notify.simulation.magic-recipients=true` (no profile base ele está desligado desde a quick 261020-tm3);
  - usa TTLs curtos, latência baixa e failure-rate 0;
  - configura o CB para não abrir por acidente nos testes que não testam o CB.

  Os ITs não ativam o profile `demo`.
- **D-16:** Um único par RabbitMQ e Mongo fica **estático por suíte**, com `@ServiceConnection` e as tags `rabbitmq:4.3.6-management` e `mongo:8.0.32`, iguais às do compose. Entre testes, as filas são esvaziadas e as coleções de dados limpas, preservando os templates do seed.

### Critério do Claude
- **API key (ADM-04):** um valor único por ENV (por exemplo `NOTIFY_ADMIN_API_KEY` → `notify.admin.api-key`), comparado em tempo constante (`MessageDigest.isEqual`). Sem chave ou com chave errada devolve 401 em Problem Detail. No compose/demo, um valor padrão documentado. Sem chave configurada, os endpoints admin recusam tudo (fail closed).
- **`republish-failed` (ADM-03):** processa em lote com limite (`max`, os mesmos padrão e teto do D-04), do mais antigo para o mais novo, usando o serviço do D-08. A resposta segue o padrão do D-03 (`{requested, republished}` e 503 parcial na primeira falha).
- Formato exato do evento REPROCESSED no `attempts[]` (ou num campo de eventos) e a mensagem republicada com `notify-retry-count=0`. O ADM-02 exige manter o histórico.
- Como provar "exatamente um envio" na duplicata concorrente: por exemplo um contador no adapter simulado exposto ao teste, ou a métrica `notify.delivery.outcomes{outcome=sent}` mais a contagem de `attempts` SUCCESS.
- Swagger (API-08): springdoc 2.8.17, com o esquema de segurança `X-API-Key` declarado nos endpoints admin.
- Corrigir o Javadoc de `RecipientRules` (deferred item da Fase 2, exemplo de SMS mágico antigo) e o de `MagicRecipients.java:15` (novo padrão desligado) se tocar nesses arquivos.

### Ideias adiadas (FORA DO ESCOPO)
Nenhuma. A discussão ficou dentro do escopo da fase.
</user_constraints>

## Restrições do projeto (do CLAUDE.md)

- Java 21, Spring Boot **3.5.16**, Spring AMQP, Spring Data MongoDB **síncrono** (nunca reativo), Resilience4j 2.4.0, Micrometer, Testcontainers, Docker Compose.
- Maven multi-módulo com wrapper (`mvnw`, Maven 3.9.16). O novo módulo `notify-it` entra no POM pai.
- **Não sobrescrever** `spring-amqp.version`, `mongodb.version`, `testcontainers.version`, `micrometer.version`: usar as versões do BOM do Boot.
- **Testcontainers 1.21.4 gerenciado pelo Boot**; nunca 2.x (artefatos e pacotes renomeados; o `@ServiceConnection` do Boot 3.5 importa as classes 1.x `org.testcontainers.containers.RabbitMQContainer`/`MongoDBContainer`).
- Imagens dos testes iguais às do compose: `rabbitmq:4.3.6-management` e `mongo:8.0.32`. Nunca `:latest`.
- **springdoc 2.8.17** (`springdoc-openapi-starter-webmvc-ui`); nunca springdoc 3.x.
- ITs com nome `*IT.java` rodando no Failsafe (`verify`); `mvn test` rápido e sem Docker.
- **Nada de `Thread.sleep`** nos ITs: Awaitility (`await().atMost(...)`) e TTLs curtos só de teste.
- Filas sempre duráveis (`QueueBuilder.durable`), sem `x-queue-mode`/`x-queue-version`, sem `global-qos`, sem requeue em falha (`default-requeue-rejected: false`), sem `RetryInterceptorBuilder`.
- Records em vez de Lombok.
- Toda a documentação em **pt-BR**; código e identificadores podem ficar em inglês.
- Reprodutibilidade: demo com `docker compose up` + script, sem passos manuais.
- Fluxo GSD: mudanças só via comandos GSD.

<phase_requirements>
## Requisitos da fase

| ID | Descrição | Apoio da pesquisa |
|----|-----------|-------------------|
| API-03 | `Idempotency-Key`: mesma chave e mesmo payload devolvem a original; payload diferente devolve 422 | Padrão 4 (índice único parcial + `DuplicateKeyException`, hash canônico, replay, PUBLISH_FAILED republica); Armadilhas 6, 7 |
| API-05 | `GET /notifications?status=&channel=` com paginação por `createdAt` desc | Padrão 5 (query com projeção, envelope próprio); **falta o índice `status_createdAt`** (Armadilha 5) |
| API-07 | `GET /notifications/stats` com contagens por status e canal | Padrão 6 (`$group` em `status`+`channel`, zeros preenchidos em Java) |
| API-08 | Swagger UI (springdoc 2.8.x) documentando todos os endpoints | Padrão 8 (springdoc 2.8.17, `@SecurityScheme` APIKEY em header) |
| ADM-01 | `POST /admin/dlq/{canal}/reprocess?max=N`: `basicGet` com ack manual, reset no Mongo, republish confirmado, ack; para na primeira falha | Padrão 2 (algoritmo por mensagem, orçamento pelo tamanho inicial da fila, nack com requeue na falha); Armadilhas 1, 2, 3, 4 |
| ADM-02 | Reprocessamento zera o contador, mantém `attempts[]` e registra REPROCESSED | Padrão 3 (reset atômico derivado da tabela, `AttemptOutcome.REPROCESSED`, `attemptCount` intocado); Armadilha 8 |
| ADM-03 | `POST /admin/notifications/republish-failed` | Padrão 4b (serviço único de republish, PUBLISH_FAILED → PENDING condicional) |
| ADM-04 | `X-API-Key` em tempo constante; validar canal e `max` | Padrão 1 (interceptor em `/admin/**`, SHA-256 + `MessageDigest.isEqual`, fail closed) |
| TST-02 | IT com Testcontainers: retry → DLQ → reprocess → SENT, TTLs curtos, Awaitility | Padrão 9 (harness de dois contextos), Padrão 10 (cenários); Armadilhas 9, 10, 11, 12 |
| TST-03 | ITs: venenosa sem loop, publish não roteável → PUBLISH_FAILED, duplicata concorrente com exatamente um envio | Padrão 10 (cenários e asserções) |
| TST-04 | ITs via Failsafe (`*IT`), separados dos unitários | Padrão 9 (POM do `notify-it`, classifier `exec`); Armadilha 9 |
</phase_requirements>

## Resumo

A fase é quase toda montagem sobre peças que já existem. O `NotificationPublisher` da API já publica no exchange principal com `notify-retry-count=0`, confirm correlacionado e `mandatory`. A tabela `NotificationStatus` já é a fonte dos filtros condicionais, e o `MongoIndexInitializer` já cria índices nomeados de forma idempotente. O reprocessamento entra na API como um serviço que, por mensagem, faz `basicGet(dlq, autoAck=false)` dentro de `RabbitTemplate.execute(ChannelCallback)`, um `findAndModify` de reset derivado da tabela (com o mesmo molde `$or` do claim do worker), o republish pelo publisher existente e o ack no **mesmo canal**. A falha devolve a mensagem com `basicNack(tag, false, true)`. O orçamento do lote é `min(max, mensagens prontas na DLQ no início)`, o que impede reler um `skipped` na mesma chamada.

Há quatro descobertas que mudam o plano. **(1)** O `./mvnw verify` com `notify-it` dependendo de `notify-api`/`notify-worker` quebra sem `<classifier>exec</classifier>` no `repackage`: o jar repackaged põe as classes em `BOOT-INF/classes` e o módulo dependente não as carrega. Os scripts de smoke usam o nome atual do jar e precisam passar a usar `-exec.jar`. **(2)** O índice `status_channel_createdAt` **não** serve `GET /notifications?status=X` sem canal ordenado por `createdAt`. O MongoDB exige igualdade em todos os campos antes da chave de ordenação, então falta um índice `status_createdAt`. **(3)** O `ChannelGateway` recusa subir se o tempo aberto do CB passar do 1º nível da escada. Com TTL de teste de 200ms, o padrão de 2500ms derruba o worker no IT, e o profile `it` precisa de `wait-duration-in-open-state` ≤ 200ms. **(4)** Acrescentar `REPROCESSED` ao `AttemptOutcome` cria 3 séries de timer novas, porque o `DeliveryMetrics` pré-registra um timer por valor do enum. O laço precisa passar a usar um conjunto explícito para manter as 42 séries do D-15.

Para os dois contextos na mesma JVM, a recomendação é não mexer nos apps. Cada contexto sobe com `SpringApplication.from(App::main).with(ItContainersConfig.class).run(args)`. Os `args` de linha de comando trazem `--spring.config.location` apontando para o diretório de recursos do próprio módulo, mais um diretório de overrides do `notify-it`. Linha de comando é uma das fontes que a documentação garante para essas propriedades lidas cedo. O `@ServiceConnection` do D-16 vem de `@ImportTestcontainers` sobre campos estáticos compartilhados. Os containers são iniciados uma vez por JVM e não podem usar a extensão JUnit `@Testcontainers`, que os pararia ao fim de cada classe.

**Recomendação principal:** começar por uma onda de fundação, que reúne as mudanças em `notify-common` (tabela, `REPROCESSED`, campos e índices), o classifier `exec` e os scripts de smoke. Logo depois vem um **spike de harness** (um IT que só sobe os dois contextos e confere config, portas e conexão), antes de qualquer cenário de IT. Ele é o único ponto com incerteza real.

## Mapa de responsabilidade arquitetural

| Capacidade | Camada principal | Camada secundária | Motivo |
|------------|------------------|-------------------|--------|
| Autenticação admin (`X-API-Key`) | API / Backend (interceptor MVC em `/admin/**`) | — | Precisa rodar antes do binding de parâmetros, para que um chamador sem chave receba 401 e não 400 |
| Reprocessamento da DLQ (pull, reset, republish, ack) | API / Backend (`notify-api`) | Broker (DLQ, exchange principal) + Mongo | O admin é endpoint HTTP da API. A API já tem `RabbitTemplate` com confirm e `MongoTemplate`, e é dona da escrita de PENDING |
| Reset de estado (REPROCESSED, `retryCount=0`) | Banco (update condicional atômico) | API | Tem que ser um único `findAndModify` cercado pela tabela, no estilo do `NotificationStateService` |
| Idempotência | Banco (índice único parcial) | API (hash canônico, regra de replay) | Só o índice único garante atomicidade entre requisições concorrentes |
| Listagem e stats | Banco (índices, `$group`) | API (validação, envelope, zeros) | Filtro, ordenação e contagem ficam no Mongo, e a API só formata |
| Swagger | API (springdoc) | — | Gerado a partir dos controllers |
| Entrega, retry, DLQ | Worker (sem mudança de lógica) | — | A Fase 3 só consome o comportamento da Fase 2. A única mudança no worker é o conjunto de outcomes do `DeliveryMetrics` |
| ITs ponta a ponta | Módulo `notify-it` (só testes) | Testcontainers | Precisa dos dois apps reais e do broker e Mongo reais |

## Stack padrão

### Núcleo (novo nesta fase)

| Biblioteca | Versão | Propósito | Por que é o padrão |
|------------|--------|-----------|--------------------|
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | **2.8.17** (fixada no CLAUDE.md) | Swagger UI + `/v3/api-docs` | Existe no Maven Central e o POM pai dela é `spring-boot-starter-parent` **3.5.13** [VERIFIED: repo1.maven.org springdoc-openapi-2.8.17.pom]. Publicado em 2026-04-11 [VERIFIED: header Last-Modified] |
| `org.springframework.boot:spring-boot-testcontainers` | gerenciada (3.5.16) | `@ServiceConnection`, `@ImportTestcontainers`, `SpringApplication.from(...).with(...)` | Faz parte do Boot. `SpringApplication.from(ThrowingConsumer<String[]>)`, `Augmented.with(Class<?>...)` e `Running.getApplicationContext()` existem no jar 3.5.16 [VERIFIED: javap spring-boot-3.5.16.jar] |
| `org.testcontainers:junit-jupiter`, `org.testcontainers:rabbitmq`, `org.testcontainers:mongodb` | **1.21.4** (gerenciada) | Containers reais | `<testcontainers.version>1.21.4</testcontainers.version>` [VERIFIED: spring-boot-dependencies-3.5.16.pom:204] e `testcontainers-bom` importado [VERIFIED: :2420]. Os três artefatos 1.21.4 existem no Central [VERIFIED: maven-metadata.xml]. O 1.21.4 resolve o handshake com Docker Engine 29 [CITED: CLAUDE.md] |
| `org.awaitility:awaitility` | 4.2.2 (gerenciada) | Asserções assíncronas | `<awaitility.version>4.2.2</awaitility.version>` [VERIFIED: spring-boot-dependencies-3.5.16.pom:35]. Já vem no `spring-boot-starter-test` [VERIFIED: spring-boot-starter-test-3.5.16.pom:87-88] |
| `maven-failsafe-plugin` | 3.5.6 (gerenciada) | Roda `*IT` em `integration-test` + `verify` | `<maven-failsafe-plugin.version>3.5.6</maven-failsafe-plugin.version>` [VERIFIED: spring-boot-dependencies-3.5.16.pom:134]. O `spring-boot-starter-parent` já declara as execuções `integration-test` e `verify` e `classesDirectory=${project.build.outputDirectory}` no `pluginManagement` [VERIFIED: spring-boot-starter-parent-3.5.16.pom:95-106]. Basta declarar o plugin no `notify-it` |

### Já presentes e reaproveitados

| Peça | Onde | Uso nesta fase |
|------|------|----------------|
| `NotificationPublisher.publish(msg, timeout)` | `notify-api/.../messaging/NotificationPublisher.java:30-58` | Republish do reprocess, do replay (D-08) e do `republish-failed`. Já grava `NotifyHeaders.RETRY_COUNT` = 0 (linha 36) |
| `PublishResult.errorCode()` | `notify-api/.../messaging/PublishResult.java:47-55` | Motivo do 503 parcial em conjunto fechado (veja Padrão 2) |
| `ApiExceptionHandler` (estende `ResponseEntityExceptionHandler`) | `notify-api/.../web/ApiExceptionHandler.java` | Toda `ErrorResponseException` vira `application/problem+json`. Os novos 400/401/422/503 entram como `ErrorResponseException` |
| `MongoIndexInitializer` | `notify-common/.../persistence/MongoIndexInitializer.java:41-54` | Ganha 2 índices (veja Padrões 4 e 5) |
| `NotificationStatus.sourcesOf(...)` | `notify-common/.../domain/NotificationStatus.java:49-55` | Deriva o filtro do reset do reprocess |
| `MagicRecipients` `fail-until-reprocess` | `notify-worker/.../channel/MagicRecipients.java:29-30,55` | Cenário determinístico do IT principal |
| `DeliveryMetrics.DURATION` timer | `notify-worker/.../metrics/DeliveryMetrics.java` | Prova de "exatamente um envio": conta só chamadas ao adapter |

### Alternativas consideradas

| Em vez de | Poderia usar | Trade-off |
|-----------|--------------|-----------|
| `--spring.config.location` por contexto (sem mexer nos apps) | Renomear `application.yml` → `notify-api.yml`/`notify-worker.yml` e fixar `spring.config.name` no `main` | É mais "Spring", mas mexe no `main` dos dois apps, em 3 testes do worker que carregam `application.yml` pelo nome (`WorkerSettingsTest`, `ResilienceSettingsTest`, `SimulatedChannelAdapterTest`) e nos `@WebMvcTest` da API, que dependem do YAML para `fail-on-numbers-for-enums`. A documentação também não garante que `setDefaultProperties` funcione para `spring.config.name` (veja Armadilha 10) |
| `@ImportTestcontainers` + `SpringApplication.from(...).with(...)` | Propriedades explícitas por `args` (`--spring.rabbitmq.host=...`, `--spring.data.mongodb.uri=...`) | É o fallback se o spike mostrar problema. Não usa `@ServiceConnection`, então desviaria da letra do D-16 |
| `REPROCESSED` dentro de `attempts[]` (`AttemptOutcome.REPROCESSED`) | Campo novo `events[]` no documento | `events[]` não toca no enum nem nas métricas, mas o critério de sucesso 1 fala em "`attempts[]` anterior com um evento REPROCESSED", e a linha do tempo única fica mais legível no GET |
| Timer `notify.delivery.duration{outcome=success}` como contador de envios | `BeanPostProcessor` de teste que embrulha os `ChannelAdapter` com um contador | O timer não exige código de teste no contexto do worker e mede exatamente as chamadas ao adapter que tiveram sucesso |
| springdoc 2.8.17 | springdoc **2.10.0** (2026-10-24), cujo POM pai é Boot **3.5.16** [VERIFIED: springdoc-openapi-2.10.0.pom] | O CLAUDE.md diz "Only the 2.8.x line works with Boot 3.5", o que está desatualizado. Manter 2.8.17 conforme a restrição, e só trocar com confirmação do usuário |

**Instalação (POMs):**
```xml
<!-- notify-api/pom.xml -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.8.17</version>
</dependency>

<!-- notify-api/pom.xml e notify-worker/pom.xml: o jar executável vira artefato com classifier -->
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>repackage</id>
            <configuration>
                <classifier>exec</classifier>
            </configuration>
        </execution>
    </executions>
</plugin>
```

## Auditoria de legitimidade de pacotes

> O seam `gsd-tools package-legitimacy check` só aceita `npm|pypi|crates` (a execução respondeu `Usage: ... --ecosystem <npm|pypi|crates>`). Para Maven, a verificação foi feita direto no Maven Central (`maven-metadata.xml` e POMs).

| Pacote | Registro | Idade | Downloads | Repositório de origem | Veredito | Disposição |
|--------|----------|-------|-----------|------------------------|----------|------------|
| `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17` | Maven Central | linha 2.x existe desde 2022; 2.8.17 em 2026-04 | n/d no Central (projeto muito usado) | github.com/springdoc/springdoc-openapi | OK (fixado no CLAUDE.md; POM pai Boot 3.5.13) | Aprovado |
| `org.testcontainers:rabbitmq:1.21.4` | Maven Central | gerenciado pelo BOM do Boot | n/d | github.com/testcontainers/testcontainers-java | OK (versão do BOM do Boot) | Aprovado |
| `org.testcontainers:mongodb:1.21.4` | Maven Central | gerenciado pelo BOM do Boot | n/d | idem | OK | Aprovado |
| `org.testcontainers:junit-jupiter:1.21.4` | Maven Central | gerenciado pelo BOM do Boot | n/d | idem | OK | Aprovado (opcional: o harness recomendado não usa a extensão `@Testcontainers`) |
| `org.springframework.boot:spring-boot-testcontainers` | Maven Central | parte do Boot 3.5.16 | n/d | github.com/spring-projects/spring-boot | OK | Aprovado |

**Pacotes removidos por veredito [SLOP]:** nenhum
**Pacotes marcados como suspeitos [SUS]:** nenhum

## Padrões de arquitetura

### Diagrama do fluxo de reprocessamento

```
 Operador ──POST /admin/dlq/{canal}/reprocess?max=N + X-API-Key──▶ AdminApiKeyInterceptor
                                                                     │ chave ausente/errada → 401 (nada lido da DLQ)
                                                                     ▼
                                                     AdminController: valida canal (400) e max 1..teto (400)
                                                                     ▼
                                       DlqReprocessService ── getQueueInfo(dlq) → prontas no início = P
                                       orçamento = min(max, P)
                                                                     ▼
            ┌──────────── RabbitTemplate.execute(ch -> laço) ─────────────────────────────────┐
            │  basicGet(dlq, autoAck=false) ── null? ──▶ fim (200)                              │
            │        ▼                                                                         │
            │  corpo → ObjectMapper.readValue(NotificationMessage) ── inválido ─▶ cópia p/ o fim │
            │        ▼                                               da DLQ (confirm) + ack =   │
            │  findAndModify reset (filtro da tabela)                skipped                    │
            │   ├─ casou → doc PENDING, retryCount=0, $push REPROCESSED                          │
            │   │      ▼                                                                        │
            │   │   NotificationPublisher.publish(main, notify-retry-count=0, confirm)           │
            │   │      ├─ ACK → basicAck(tag) → reprocessed++                                     │
            │   │      └─ NACK/RETURNED/TIMEOUT/erro → basicNack(tag, requeue=true) → 503 parcial │
            │   └─ não casou → lê status:                                                        │
            │        ├─ SENT ou inexistente → basicAck → discarded++                             │
            │        └─ PROCESSING com lease válido (ou corrida) → cópia p/ o fim da DLQ          │
            │              (confirm) → basicAck → skipped++ ; falha da cópia → nack requeue → 503 │
            │  DataAccessException (Mongo) → basicNack(requeue=true) → 503 MONGO_UNAVAILABLE      │
            └────────────────────────────────────────────────────────────────────────────────┘
                                                                     ▼
                              notify.exchange ─notification.<canal>─▶ notify.<canal> ─▶ worker (claim h=0) ─▶ SENT
```

### Estrutura recomendada

```
notify-common/src/main/java/dev/notifyhub/common/
├── domain/NotificationStatus.java      # + PENDING/RETRYING/PROCESSING → PENDING (D-01)
├── domain/AttemptOutcome.java          # + REPROCESSED
└── persistence/
    ├── NotificationDocument.java       # + idempotencyKey, requestHash (+ Fields)
    └── MongoIndexInitializer.java      # + status_createdAt, idempotencyKey (único parcial)

notify-api/src/main/java/dev/notifyhub/api/
├── admin/                              # novo pacote
│   ├── AdminProperties.java            # notify.admin.{api-key, max-default, max-limit} (nomes propostos)
│   ├── AdminApiKeyInterceptor.java     # HandlerInterceptor em /admin/**
│   ├── AdminWebConfiguration.java      # WebMvcConfigurer que registra o interceptor
│   ├── AdminController.java            # POST /admin/dlq/{canal}/reprocess, POST /admin/notifications/republish-failed
│   ├── DlqReprocessService.java        # laço basicGet → reset → publish → ack
│   └── ReprocessOutcome.java           # records de contagem + motivo enumerado
├── application/
│   ├── NotificationService.java        # create(request, idempotencyKey) + list + stats
│   ├── NotificationRepublisher.java    # PUBLISH_FAILED → PENDING + publish (D-08 e ADM-03 num lugar só)
│   ├── NotificationResetService.java   # findAndModify do reset do reprocess (ou dentro do DlqReprocessService)
│   ├── RequestHasher.java              # SHA-256 do pedido canônico
│   └── IdempotencyKeyReusedException.java  # 422
├── messaging/
│   ├── NotificationPublisher.java      # trocar CorrelationData(id) por new CorrelationData() (Armadilha 7)
│   └── DlqCopyPublisher.java           # cópia confirmada para notify.dlx/<canal>.dlq (skipped)
└── web/
    ├── NotificationController.java     # + GET lista, GET /stats, header Idempotency-Key
    ├── OpenApiConfiguration.java       # @OpenAPIDefinition + @SecurityScheme X-API-Key
    └── dto/ NotificationSummary, PageResponse, NotificationStats

notify-it/                              # módulo novo, só testes
├── pom.xml
└── src/test/
    ├── java/dev/notifyhub/it/
    │   ├── support/ItContainers.java          # campos estáticos RabbitMQContainer/MongoDBContainer + @ServiceConnection
    │   ├── support/ItContainersConfig.java    # @TestConfiguration @ImportTestcontainers(ItContainers.class)
    │   ├── support/NotifyHubApps.java         # singleton: sobe API + worker uma vez por JVM
    │   ├── support/EndToEndIT base / helpers  # limpeza, HTTP, consultas ao broker
    │   ├── HarnessSmokeIT.java                # spike: os dois contextos sobem com a config certa
    │   ├── ReprocessFlowIT.java               # TST-02
    │   ├── PoisonMessageIT.java               # TST-03
    │   ├── UnroutablePublishIT.java           # TST-03 + ADM-03
    │   ├── ConcurrentDuplicateIT.java         # TST-03
    │   └── ApiOperationsIT.java               # idempotência, lista, stats, admin auth/400, Swagger
    └── resources/it/
        ├── shared/application-it.yml          # retry-levels iguais para os dois contextos
        ├── api/application-it.yml             # notify.admin.api-key de teste
        └── worker/application-it.yml          # magic-recipients, latência, CB, bulkhead
```

### Padrão 1: autenticação admin com interceptor (ADM-04)

**O quê:** um `HandlerInterceptor` registrado só para `/admin/**`. Sem chave configurada, recusa tudo (fail closed). Compara os SHA-256 da chave recebida e da esperada com `MessageDigest.isEqual`.
**Por que hash antes:** o `isEqual` examina todos os bytes de `digesta` e "a calculation time depends only on the length of `digesta`" [CITED: docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/MessageDigest.html]. Comparar dois digests de 32 bytes elimina até o vazamento de tamanho.
**Por que interceptor e não validação no controller:** o interceptor roda antes do binding dos argumentos. Assim um chamador sem chave recebe 401 mesmo com canal inválido e nunca chega a ler a DLQ. Uma exceção lançada em `preHandle` é resolvida pelos `HandlerExceptionResolver` do `DispatcherServlet`, então uma `ErrorResponseException(401)` cai no `ApiExceptionHandler` [ASSUMED: comportamento padrão do Spring MVC, a confirmar num `@WebMvcTest`].

```java
// Exemplo proposto (nomes novos). Nunca logar a chave.
public class AdminApiKeyInterceptor implements HandlerInterceptor {
    static final String HEADER = "X-API-Key";
    private final byte[] expectedDigest; // null = não configurada → fail closed

    AdminApiKeyInterceptor(String configuredKey) {
        this.expectedDigest = (configuredKey == null || configuredKey.isBlank()) ? null : sha256(configuredKey);
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String provided = req.getHeader(HEADER);
        if (expectedDigest == null || provided == null
                || !MessageDigest.isEqual(expectedDigest, sha256(provided))) {
            throw new AdminUnauthorizedException(); // ErrorResponseException 401, type urn:notify-hub:problem:unauthorized
        }
        return true;
    }
}
```

O canal vem do path em minúsculo (`/admin/dlq/email/reprocess`). Parse explícito com `Channel.valueOf(canal.toUpperCase(Locale.ROOT))`, e qualquer erro vira o 400 de validação do projeto. Não use binding direto para o enum: `email` falharia (o `valueOf` do enum diferencia maiúsculas), e o `MethodArgumentTypeMismatchException` sairia com `type: about:blank`, diferente do `ProblemTypes.VALIDATION` atual.

### Padrão 2: laço do reprocessamento (ADM-01, D-02, D-03)

**Regras do broker que o algoritmo usa:**
- Delivery tags são por canal. O ack tem que ir pelo canal que recebeu a entrega, senão o broker fecha o canal com "unknown delivery tag" [CITED: rabbitmq.com/docs/confirms].
- Mensagem devolvida com requeue "will be placed to its original position in its queue, if possible" [CITED: rabbitmq.com/docs/confirms]. É o comportamento certo para a mensagem que falhou (D-03), mas inútil para o `skipped` (D-02), que precisa ir para o **fim**: cópia + ack.
- Quando o canal ou a conexão fecha, toda entrega sem ack volta para a fila automaticamente [CITED: rabbitmq.com/docs/confirms]. É a rede de segurança se o processo cair no meio.
- `basic.nack` com `requeue=false` numa fila **sem** DLX descarta a mensagem [CITED: rabbitmq.com/docs/confirms]. A DLQ do projeto não tem argumentos (`QueueBuilder.durable(ch.dlq()).build()`, `NotifyTopology.java:50`), então **nunca use `requeue=false` ou `basicReject(tag, false)` na DLQ**.
- O prefetch não se aplica a `basic.get` [CITED: rabbitmq.com/docs/confirms].

**Orçamento:** leia `RabbitAdmin.getQueueInfo(channel.dlq()).getMessageCount()` (só mensagens prontas) antes do laço e use `orçamento = min(max, prontas)`. Um `skipped` é anexado ao fim, depois das P mensagens iniciais, então nunca é relido na mesma chamada. Mensagens que o worker acrescentar durante a chamada também ficam depois. Se `getQueueInfo` devolver `null`, a fila não existe, e a resposta é 503.

**Nomes vindos do código (verbatim):**
- `Channel.java:30-42`: `return "notify." + id;` (fila), `return "notify." + id + ".dlq";` (DLQ), `return id + ".dlq";` (routing key no DLX), `return "notification." + id;` (routing key principal, linha 26) [VERIFIED: notify-common/.../domain/Channel.java:25-42]
- `NotifyTopology.java:34-36`: `MAIN_EXCHANGE = "notify.exchange"`, `RETRY_EXCHANGE = "notify.retry"`, `DLX_EXCHANGE = "notify.dlx"` [VERIFIED: notify-common/.../messaging/NotifyTopology.java:34-36]
- `NotifyHeaders.java:7,15`: `RETRY_COUNT = "notify-retry-count"`, `FAILURE_CLASS = "x-failure-class"` [VERIFIED: notify-common/.../messaging/NotifyHeaders.java:7,15]

**Motivos do 503 (conjunto fechado):** reaproveite `PublishResult.errorCode()`, que hoje devolve `case NACK -> "PUBLISH_NACK"; case RETURNED -> "PUBLISH_RETURNED"; case TIMEOUT -> "PUBLISH_TIMEOUT"; case BROKER_UNAVAILABLE -> "BROKER_UNAVAILABLE";` [VERIFIED: notify-api/.../messaging/PublishResult.java:47-55]. Acrescente códigos próprios para o que não é publish, por exemplo `MONGO_UNAVAILABLE` e `DLQ_UNAVAILABLE` (valores novos, propostos). **Nunca** use `PublishResult.detail()` na resposta: ele carrega o texto do nack, o reply text e a mensagem de exceção (T-02-01).

```java
// Esqueleto proposto. Nomes de classe e de motivo novos são propostas; valores do repositório citados acima.
public ReprocessReport reprocess(Channel channel, int max) {
    QueueInformation info = amqpAdmin.getQueueInfo(channel.dlq());
    if (info == null) throw ReprocessInterrupted.dlqUnavailable(max);
    int budget = Math.min(max, info.getMessageCount());
    Counts counts = new Counts(max);
    return rabbitTemplate.execute(ch -> {
        for (int i = 0; i < budget; i++) {
            GetResponse r = ch.basicGet(channel.dlq(), false);   // autoAck=false
            if (r == null) break;                                 // DLQ esvaziou
            long tag = r.getEnvelope().getDeliveryTag();
            boolean settled = false;
            try {
                Step step = handleOne(channel, r);                // reset/publish/cópia; nunca faz ack
                ch.basicAck(tag, false); settled = true;
                counts.add(step);                                 // REPROCESSED | SKIPPED | DISCARDED
            } catch (ReprocessStepFailed e) {                     // publish sem ACK, Mongo fora etc.
                ch.basicNack(tag, false, true); settled = true;   // volta à posição original na DLQ
                throw counts.interrupted(e.reason());             // vira 503 com as contagens parciais
            } finally {
                if (!settled) ch.basicNack(tag, false, true);     // nunca deixar entrega pendurada no canal do cache
            }
        }
        return counts.report();
    });
}
```

Detalhes:
- **Parse do corpo:** `objectMapper.readValue(r.getBody(), NotificationMessage.class)`. Não use `rabbitTemplate.getMessageConverter().fromMessage(...)`. O conversor do projeto é `new Jackson2JsonMessageConverter(objectMapper)` (`NotifyCommonConfiguration.java:39`), e esse construtor passa um array vazio de pacotes confiáveis. O `DefaultJackson2JavaTypeMapper` fica com a lista padrão `"java.util"`, `"java.lang"` [VERIFIED: javap spring-amqp-3.2.12.jar, `Jackson2JsonMessageConverter(ObjectMapper)` e `DefaultJackson2JavaTypeMapper.<clinit>`]. Com o `__TypeId__` = `dev.notifyhub.common.messaging.NotificationMessage`, a conversão sem tipo inferido seria recusada. O listener do worker só funciona porque infere o tipo pelo parâmetro. Ler para o tipo concreto também evita confiar num `__TypeId__` vindo de quem tem acesso ao broker.
- **Corpo inválido** (JSON quebrado, sem `notificationId`, canal do corpo ≠ canal do path): cópia dos **bytes originais** para o fim da DLQ e ack, contando como `skipped`. Não descarte (a mensagem se perderia em silêncio, contra o valor central) e não faça requeue (voltaria para a cabeça e seria relida até o fim do orçamento). Veja a Pergunta em aberto 1.
- **Republish:** monte a mensagem a partir do documento devolvido pelo reset, `new NotificationMessage(doc.id(), doc.channel(), doc.templateCode(), doc.createdAt())`, e não a partir do corpo da DLQ. O `NotificationPublisher` já grava o `messageId` e `RETRY_COUNT` = 0.
- **Cópia para o fim da DLQ:** publique em `NotifyTopology.DLX_EXCHANGE` com `channel.dlqKey()`, confirm e `mandatory`. Copie só `messageId`, `content-type` e os headers `x-failure-class`/`notify-retry-count` quando existirem, como o `DeliveryRoutingPublisher` do worker faz, para não carregar `x-death`. Use `new CorrelationData()` (UUID).
- **Chamadas aninhadas:** dentro do `execute`, o `NotificationPublisher` usa `rabbitTemplate.convertAndSend`, que pega **outro** canal do cache (o cache da API tem `size: 50`, `application.yml:31`). Isso é necessário porque o confirm correlacionado do template só funciona pelos métodos do template. Publicar com `ch.basicPublish` no canal do `basicGet` não registraria a correlação.
- **Duas chamadas concorrentes** no mesmo canal são seguras: `basic.get` entrega cada mensagem a um só canal.

### Padrão 3: reset atômico derivado da tabela (D-01, ADM-02)

**Tabela atual (verbatim):**
```
table.put(PENDING, EnumSet.of(PROCESSING, PUBLISH_FAILED));
table.put(PUBLISH_FAILED, EnumSet.of(PROCESSING, PENDING));
table.put(PROCESSING, EnumSet.of(SENT, RETRYING, FAILED, DEAD_LETTERED, PROCESSING));
table.put(RETRYING, EnumSet.of(PROCESSING, DEAD_LETTERED));
table.put(FAILED, EnumSet.of(PENDING));
table.put(DEAD_LETTERED, EnumSet.of(PENDING));
table.put(SENT, EnumSet.noneOf(NotificationStatus.class));
```
[VERIFIED: notify-common/.../domain/NotificationStatus.java:27-37]

**Mudança (D-01):** `PENDING → {PROCESSING, PUBLISH_FAILED, PENDING}`, `RETRYING → {PROCESSING, DEAD_LETTERED, PENDING}`, `PROCESSING → {SENT, RETRYING, FAILED, DEAD_LETTERED, PROCESSING, PENDING}`. Depois disso, `sourcesOf(PENDING)` = {PENDING, PUBLISH_FAILED, PROCESSING, RETRYING, FAILED, DEAD_LETTERED}. `claimableForDelivery()` e `reclaimableWhenStuck()` derivam de `sourcesOf(PROCESSING)` (linhas 63-81) e **não mudam**, porque nenhuma das novas transições termina em PROCESSING. O `NotificationStatusTest` precisa de atualização: a linha 79 (`claimableForDelivery`) continua igual, mas faltam asserções novas para as três transições.

**Filtro do reset (mesmo molde `$or` do claim, `NotificationStateService.java:97-115`):**
```java
// Esqueleto proposto; campos verbatim de NotificationDocument.Fields (linhas 68-80).
Instant now = Instant.now();
Set<NotificationStatus> unconditional = EnumSet.copyOf(NotificationStatus.sourcesOf(NotificationStatus.PENDING));
unconditional.remove(NotificationStatus.PROCESSING);
Criteria fresh = where(Fields.STATUS).in(unconditional);
Criteria stuck = where(Fields.STATUS).is(NotificationStatus.PROCESSING).and(Fields.LEASE_UNTIL).lt(now);
Query resettable = query(where(Fields.ID).is(id).orOperator(fresh, stuck));

Update reset = new Update()
        .set(Fields.STATUS, NotificationStatus.PENDING)
        .set(Fields.RETRY_COUNT, 0)          // ADM-02; o claim exige retryCount <= h e o header será 0
        .set(Fields.UPDATED_AT, now)
        .unset(Fields.LEASE_UNTIL)
        .unset(Fields.CLAIMED_AT);
// NÃO tocar em ATTEMPT_COUNT (Armadilha 8)
reset.push(Fields.ATTEMPTS).slice(-50).each(reprocessedEvent); // $slice exige $each, como no markSent
NotificationDocument doc = mongo.findAndModify(resettable, reset,
        FindAndModifyOptions.options().returnNew(true), NotificationDocument.class);
```
Campos verbatim: `ID = "_id"`, `STATUS = "status"`, `RETRY_COUNT = "retryCount"`, `ATTEMPT_COUNT = "attemptCount"`, `ATTEMPTS = "attempts"`, `LAST_ERROR = "lastError"`, `UPDATED_AT = "updatedAt"`, `CLAIMED_AT = "claimedAt"`, `LEASE_UNTIL = "leaseUntil"` [VERIFIED: notify-common/.../persistence/NotificationDocument.java:68-80].

Se o reset devolver `null`, leia o documento (só `status` e `leaseUntil`) e classifique: inexistente ou `SENT` dá `discarded`; `PROCESSING` com lease vigente dá `skipped`. Qualquer outro caso é uma corrida (o status mudou entre as duas leituras) e também deve virar `skipped`, por segurança.

**Ordem obrigatória:** reset no Mongo **antes** do republish. Se publicar primeiro, o worker pode reivindicar antes do reset. DEAD_LETTERED não é reivindicável, então a mensagem seria confirmada como obsoleta e o documento ficaria PENDING sem mensagem em fila nenhuma. Com o reset primeiro, uma falha no publish deixa o documento PENDING e a mensagem de volta na DLQ, e o próximo reprocess a pega, porque PENDING está no D-01. É exatamente para isso que PENDING entra no conjunto.

**Evento REPROCESSED (recomendação para o critério do Claude):**
- Acrescentar `REPROCESSED` a `AttemptOutcome`. Valores atuais: `SUCCESS`, `TRANSIENT_FAILURE`, `PERMANENT_FAILURE`, `UNKNOWN_FAILURE` [VERIFIED: notify-common/.../domain/AttemptOutcome.java:10-19].
- Entrada: `new AttemptEntry(0, retryCountAnterior, now, 0L, AttemptOutcome.REPROCESSED, null, "<texto fixo pt-BR com o status anterior>", null, Thread.currentThread().toString())`. `number = 0` sinaliza que não é tentativa de entrega. A mensagem é montada só a partir de enums, sem texto do broker.
- **Obrigatório junto:** no `DeliveryMetrics`, trocar `for (AttemptOutcome outcome : AttemptOutcome.values())` (linhas 80-81) por um `EnumSet.of(SUCCESS, TRANSIENT_FAILURE, PERMANENT_FAILURE, UNKNOWN_FAILURE)`. Sem isso nascem 3 séries `notify.delivery.duration{outcome="reprocessed"}` que nunca recebem amostra, e o contrato de 42 séries do D-15 e o `DeliveryMetricsTest` quebram.

### Padrão 4: Idempotency-Key (API-03, D-05..D-08)

**Índice único parcial** com a API do Spring Data. `Index.unique()`, `Index.partial(IndexFilter)` e `PartialIndexFilter.of(CriteriaDefinition)` existem no 4.5.13 [VERIFIED: javap spring-data-mongodb-4.5.13.jar]:
```java
ops.createIndex(new Index()
        .on(Fields.IDEMPOTENCY_KEY, ASC)               // campo novo "idempotencyKey" (proposto)
        .unique()
        .partial(PartialIndexFilter.of(where(Fields.IDEMPOTENCY_KEY).exists(true)))
        .named("idempotencyKey_unique"));               // nome proposto
```
- O MongoDB aceita `$exists: true` em `partialFilterExpression`, e "the unique constraint only applies to the documents that meet the filter expression" [CITED: mongodb.com/docs/manual/core/index-partial/]. Documentos sem chave não disputam unicidade.
- O Spring Data não grava campo `null`, então documentos sem chave ficam sem o campo [ASSUMED: padrão do `MappingMongoConverter`; o IT de concorrência confirma]. Se algum dia um `null` explícito for gravado, `$exists: true` o incluiria e dois `null` colidiriam.

**Insert e corrida:** `mongoTemplate.insert(doc)` com chave duplicada lança `org.springframework.dao.DuplicateKeyException`. O `MongoExceptionTranslator` traduz o código 11000 por `MongoDbErrorCodes.isDuplicateKeyCode` [VERIFIED: javap spring-data-mongodb-4.5.13.jar]. No catch:
```java
// Esqueleto proposto
try {
    mongoTemplate.insert(NotificationDocument.newPending(id, ..., idempotencyKey, hash, now));
} catch (DuplicateKeyException e) {
    NotificationDocument existing = mongoTemplate.findOne(
            query(where(Fields.IDEMPOTENCY_KEY).is(idempotencyKey)), NotificationDocument.class);
    if (existing == null) throw e;                               // foi outra chave única (ex.: _id)
    if (!existing.requestHash().equals(hash)) throw new IdempotencyKeyReusedException(idempotencyKey); // 422
    if (existing.status() == NotificationStatus.PUBLISH_FAILED) {
        return republisher.republish(existing).asReplay();        // D-08: 202 ou PublishFailedException 503
    }
    return CreateResult.replay(existing);                         // D-07: 202 + Idempotent-Replayed: true
}
```

**Hash canônico (D-06):** monte um `TreeMap` com `channel` (nome do enum), `recipient`, `templateCode` e `variables` (convertido para `TreeMap`) e serialize com um `ObjectMapper` que tenha `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS`. Depois aplique `MessageDigest.getInstance("SHA-256")` e `HexFormat.of().formatHex(...)`. As variáveis só aceitam texto, número ou booleano (`CreateNotificationRequestValidator.java:63`), então não há aninhamento. "Depois do parse" quer dizer que `1` e `1.0` são diferentes (Integer e Double), enquanto `1.0` e `1.00` são iguais. Documente isso.

**Validação da chave:** limite de 1 a 255 caracteres ASCII imprimíveis (proposta: `^[\x21-\x7E]{1,255}$`). Fora disso, 400 de validação. A validação estrutural do corpo continua antes da idempotência: payload inválido dá 400 mesmo com uma chave já usada.

**Replay:** o controller precisa saber se foi replay. Faça o serviço devolver `CreateResult(response, replayed)` e acrescente `Idempotent-Replayed: true` no `ResponseEntity.accepted()`. O `NotificationApiFlowTest` mocka `service.create(request)`, então a mudança de assinatura exige atualizar esse teste.

### Padrão 4b: republish único (D-08, ADM-03)

`NotificationRepublisher.republish(doc)`:
1. `updateFirst` com `_id` e `status = PUBLISH_FAILED` → `$set status=PENDING, updatedAt`. A transição já existe (`PUBLISH_FAILED → {PROCESSING, PENDING}`). Se casar 0 documentos, outro caminho já agiu: o worker pode reivindicar PUBLISH_FAILED, porque está em `claimableForDelivery`. Nesse caso devolva o status atual sem publicar.
2. `publisher.publish(...)` com confirm.
3. Falha → PENDING → PUBLISH_FAILED condicional. É o `markPublishFailed` atual (`NotificationService.java:71-86`), extraído para ser compartilhado. Lança `PublishFailedException` (503 + `Retry-After`).

O `republish-failed` busca `status = PUBLISH_FAILED` ordenado por `createdAt` **asc**, `limit(max)`, chama o serviço um a um e para na primeira falha com 503 parcial `{requested, republished, reason}`. Com o índice novo `status_createdAt` (Padrão 5), essa query também é servida por índice: o Mongo percorre um índice ao contrário para inverter a ordem.

Aproveitando a extração: o `markPublishFailed` grava `new LastError(result.errorCode(), result.detail(), null, failedAt)` (`NotificationService.java:77`), e `detail()` é texto livre do broker (nack, reply text ou exceção). Alinhar com o T-02-01, gravando uma mensagem fixa a partir de `errorCode()`, é barato e fecha a mesma classe de problema do WR-05 do worker. Fica a critério do planner.

### Padrão 5: listagem paginada (API-05, D-09..D-11)

**Índices existentes (verbatim):**
```
.on(STATUS, ASC).on(CHANNEL, ASC).on(CREATED_AT, DESC).on(ID, DESC) .named("status_channel_createdAt")
.on(CHANNEL, ASC).on(CREATED_AT, DESC).on(ID, DESC)                 .named("channel_createdAt")
.on(CREATED_AT, DESC).on(ID, DESC)                                   .named("createdAt")
```
[VERIFIED: notify-common/.../persistence/MongoIndexInitializer.java:44-51]

| Filtro | Ordenação | Índice que serve |
|--------|-----------|------------------|
| status + channel | createdAt desc, _id desc | `status_channel_createdAt` |
| só channel | idem | `channel_createdAt` |
| nenhum | idem | `createdAt` |
| **só status** | idem | **nenhum**: `channel` fica entre `status` e `createdAt` sem igualdade, e isso gera SORT bloqueante. O MongoDB exige "**equality** conditions on all the prefix keys that precede the sort keys" [CITED: mongodb.com/docs/manual/tutorial/sort-results-with-indexes/] |

**Acrescente** `status_createdAt` = `(status ASC, createdAt DESC, _id DESC)`. Ele também serve o `republish-failed` (status = PUBLISH_FAILED, createdAt asc). O `MongoIndexInitializerTest` fixa 3 índices (`createsThreeNamedIndexesOnNotifications`) e precisa passar a esperar 5.

**Query:**
```java
// Esqueleto proposto
Criteria c = new Criteria();
if (status != null) c = c.and(Fields.STATUS).is(status);
if (channel != null) c = c.and(Fields.CHANNEL).is(channel);
Query q = new Query(c);
long total = mongoTemplate.count(q, NotificationDocument.class);    // antes de skip/limit
q.with(Sort.by(Sort.Order.desc(Fields.CREATED_AT), Sort.Order.desc(Fields.ID)))
 .skip((long) page * size).limit(size);
q.fields().include(Fields.STATUS, Fields.CHANNEL, "recipient", "templateCode",
        Fields.RETRY_COUNT, Fields.LAST_ERROR + ".code", Fields.CREATED_AT, Fields.UPDATED_AT);
List<NotificationDocument> docs = mongoTemplate.find(q, NotificationDocument.class);
```
- Envelope próprio `PageResponse<T>(content, page, size, totalElements, totalPages)`. Não serialize `PageImpl` direto: o Spring Data 3.3+ avisa que o formato JSON do `PageImpl` não é estável [ASSUMED].
- Parâmetros como `String`/`Integer` opcionais, validados à mão: `page ≥ 0`, `1 ≤ size ≤ 100` (padrão 20), status e canal pelo enum (maiúsculo, como no POST). Qualquer violação vira o 400 com `ProblemTypes.VALIDATION` e `errors[]`, no mesmo formato do `validationProblem` atual. Sobrescreva também `handleTypeMismatch` no `ApiExceptionHandler`, para que `?page=abc` não saia com `type: about:blank`.
- **Paginação profunda:** `skip` grande percorre o índice inteiro até o offset. Para uma demo é aceitável. Se quiser, limite `page * size` (por exemplo a 10 000) com 400, o que corta um DoS barato.
- Rota: `/notifications/stats` (literal) tem precedência sobre `/notifications/{id}` no `PathPattern` do Spring MVC.

### Padrão 6: stats com `$group` (API-07, D-12)

```java
// Esqueleto proposto
Aggregation agg = Aggregation.newAggregation(
        Aggregation.group(Fields.STATUS, Fields.CHANNEL).count().as("count"));
AggregationResults<Document> res = mongoTemplate.aggregate(agg, Collections.NOTIFICATIONS, Document.class);
// cada linha: {_id: {status: "SENT", channel: "EMAIL"}, count: n}
```
- Pré-preencha `EnumMap`s com **todos** os `Channel` × `NotificationStatus` a zero. Some cada linha (`((Number) row.get("count")).longValue()`) e derive `byStatus`, `byChannel` e `total`. Um valor desconhecido no banco é ignorado com log, para não lançar `IllegalArgumentException`.
- O Jackson serializa chaves de `EnumMap` pelo nome (`EMAIL`, `SENT`), que é o formato do D-12. Use `EnumMap`/`LinkedHashMap` para a ordem ficar estável no README.
- `Collections.NOTIFICATIONS = "notifications"` [VERIFIED: notify-common/.../persistence/Collections.java:6].

### Padrão 7: Problem Details novos

Os tipos atuais são `VALIDATION = "urn:notify-hub:problem:validation-error"`, `PUBLISH_FAILED = "urn:notify-hub:problem:publish-failed"` e `NOT_FOUND = "urn:notify-hub:problem:not-found"` [VERIFIED: notify-api/.../web/ProblemTypes.java:8-10]. Propostas novas, seguindo o mesmo esquema `urn:notify-hub:problem:*`: `unauthorized` (401), `idempotency-key-reused` (422) e `reprocess-interrupted` (503, com `requested/reprocessed/skipped/discarded/reason`, ou `requested/republished/reason` no republish). Todos como `ErrorResponseException`, para herdar o `instance` preenchido pelo `handleExceptionInternal`.

### Padrão 8: Swagger (API-08)

```java
// Exemplo proposto (anotações io.swagger.v3.oas.annotations, transitivas do springdoc)
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(title = "Notify Hub API", version = "v1"))
@SecurityScheme(name = "adminApiKey", type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.HEADER, paramName = "X-API-Key")
class OpenApiConfiguration { }

@RestController @RequestMapping("/admin")
@SecurityRequirement(name = "adminApiKey")
class AdminController { ... }
```
- Caminhos padrão `/swagger-ui.html` (redireciona para `/swagger-ui/index.html`) e `/v3/api-docs` [ASSUMED: padrão do springdoc 2.x]. O interceptor só pega `/admin/**`, então o Swagger fica aberto.
- Documente o header `Idempotency-Key` no POST com `@RequestHeader(name = "Idempotency-Key", required = false)`, que o springdoc lê. Use `@Parameter(description = ...)` em pt-BR.
- O springdoc acrescenta às operações respostas genéricas tiradas dos `@ExceptionHandler` do `@ControllerAdvice` (`springdoc.override-with-generic-response`). Com `ResponseEntityExceptionHandler`, a doc pode ficar poluída. Se ficar, desligue a propriedade e declare `@ApiResponse` por operação [ASSUMED].
- A prova automática do API-08 fica no IT: `GET /v3/api-docs` tem os 5 caminhos e `components.securitySchemes.adminApiKey`, e `GET /swagger-ui/index.html` responde 200. Um `@SpringBootTest` dentro do `notify-api` exigiria Rabbit e Mongo (o runner `declareTopology` conecta ao subir) e quebraria o `./mvnw test` sem Docker.

### Padrão 9: harness de dois contextos na mesma JVM (D-13, D-16)

**O problema, confirmado:** os dois módulos têm `src/main/resources/application.yml` e `application-demo.yml`. No classpath do `notify-it`, um carregamento por `classpath:/application.yml` enxerga só o primeiro. Os pacotes não se escaneiam: `@SpringBootApplication` em `dev.notifyhub.api` e em `dev.notifyhub.worker` [VERIFIED: NotifyApiApplication.java:6-7, NotifyWorkerApplication.java:6-7]. Mas **as autoconfigurações veem o classpath inteiro**: o contexto da API ganha Resilience4j, o registry Prometheus e o `MustacheAutoConfiguration` (JMustache vem do worker), e o do worker ganha o springdoc. Nada disso quebra, mas é bom saber (Armadilha 12).

**Mecanismo de config (recomendado, sem mudar os apps):** passe por **linha de comando** um `spring.config.location` próprio a cada contexto. A documentação diz que essas propriedades "must be defined as an environment property (typically an OS environment variable, a system property, or a command-line argument)" [CITED: docs.spring.io/spring-boot/3.5/reference/features/external-config.html]. Quando `spring.config.location` é definido, ele substitui os locais padrão, então o `classpath:/application.yml` que colide não é lido. Entre locais, "values from lower items overriding earlier ones" [CITED: mesma página].

```java
// Esqueleto proposto — singleton por JVM (Failsafe usa 1 fork por padrão)
final class NotifyHubApps {
    private static ConfigurableApplicationContext worker, api;

    static synchronized void start() {
        if (api != null) return;
        Startables.deepStart(ItContainers.RABBIT, ItContainers.MONGO).join(); // start() é idempotente
        worker = SpringApplication.from(NotifyWorkerApplication::main)
                .with(ItContainersConfig.class)
                .run("--spring.config.location=" + dir("notify.it.worker-config", "notify-worker")
                        + ",optional:classpath:/it/shared/,optional:classpath:/it/worker/",
                     "--spring.profiles.active=it", "--server.port=0")
                .getApplicationContext();
        api = SpringApplication.from(NotifyApiApplication::main)
                .with(ItContainersConfig.class)
                .run("--spring.config.location=" + dir("notify.it.api-config", "notify-api")
                        + ",optional:classpath:/it/shared/,optional:classpath:/it/api/",
                     "--spring.profiles.active=it", "--server.port=0")
                .getApplicationContext();
    }
    // dir(...) devolve Path.toUri().toString() terminado em "/" (ex.: file:///C:/Users/Lucas%20Lopes/.../src/main/resources/)
}

interface ItContainers {   // sem @Testcontainers/@Container: a extensão JUnit pararia tudo ao fim de cada classe
    @ServiceConnection RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3.6-management");
    @ServiceConnection MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0.32");
}

@TestConfiguration(proxyBeanMethods = false)
@ImportTestcontainers(ItContainers.class)
class ItContainersConfig { }
```

Fatos que sustentam o esqueleto:
- `ContainerFieldsImporter` registra cada campo **estático** do tipo `Container` como bean com `setInstanceSupplier(() -> container)`. Por isso os dois contextos recebem a **mesma** instância [VERIFIED: github spring-boot v3.5.16, `ContainerFieldsImporter`/`TestcontainerFieldBeanDefinition`]. O campo não precisa de `@Container`.
- `TestcontainersLifecycleBeanPostProcessor.postProcessBeforeDestruction` chama `startable.close()` quando o contexto fecha, a menos que o container seja reutilizado [VERIFIED: mesmo fonte, linhas 179-181]. Fechar **um** contexto para os containers do outro. Os dois contextos vivem até o fim da JVM (o shutdown hook padrão do Spring fecha os dois, e o Ryuk limpa os containers).
- `GenericContainer.start()` retorna na hora se `containerId != null` [VERIFIED: javap testcontainers-1.21.4.jar]. Iniciar antes e deixar o post-processor chamar de novo é seguro.
- A extensão JUnit para containers em campos estáticos "are stopped after the test class has run", e a doc recomenda beans ou `@ImportTestcontainers` quando o contexto precisa sobreviver [CITED: docs.spring.io/spring-boot/3.5/reference/testing/testcontainers.html].
- Tabela de service connection: `RabbitConnectionDetails` ← `RabbitMQContainer`, `MongoConnectionDetails` ← `MongoDBContainer` [CITED: mesma página]. O `MongoDBContainer` entrega `getReplicaSetUrl()` com o banco `test`, e os dois contextos usam esse mesmo banco [ASSUMED: padrão do Testcontainers 1.x].
- A porta da API sai de `api.getEnvironment().getProperty("local.server.port", Integer.class)`.

**Caminho dos recursos dos módulos:** Failsafe `systemPropertyVariables` → `notify.it.api-config=${project.basedir}/../notify-api/src/main/resources/` (e o equivalente do worker), com fallback relativo a `user.dir` para rodar na IDE. Use `src/main/resources` (sempre existe, e não há filtragem de recursos no projeto) e converta com `Path.toUri()`. O caminho do autor tem espaço (`Lucas Lopes`), e a forma URI `%20` evita surpresa. As listas de `spring.config.location` são separadas por vírgula. **Valide tudo no spike** (Armadilha 10).

**Conteúdo do profile `it`:**
- `it/shared/application-it.yml`: `notify.topology.retry-levels: 200ms,500ms,1s`, idêntico nos dois contextos. Precisa ter **exatamente 3 níveis** por causa do `fail-until-reprocess` (Armadilha 11).
- `it/worker/application-it.yml`: `notify.simulation.magic-recipients: true`; latência baixa (por exemplo 5ms a 20ms) e `failure-rate: 0.0` nos três canais; `resilience4j.circuitbreaker.configs.default.wait-duration-in-open-state` **≤ 200ms** (por exemplo 100ms) e `minimum-number-of-calls`/`sliding-window-size` altos (por exemplo 1000), para não abrir; `resilience4j.bulkhead.configs.default.max-concurrent-calls` ≥ concorrência (por exemplo 20); opcional `springdoc.api-docs.enabled: false`.
- `it/api/application-it.yml`: `notify.admin.api-key: it-admin-key` (nome proposto).

**POM do `notify-it`** (esqueleto):
```xml
<artifactId>notify-it</artifactId>
<dependencies>
  <dependency><groupId>dev.notifyhub</groupId><artifactId>notify-api</artifactId><version>${project.version}</version><scope>test</scope></dependency>
  <dependency><groupId>dev.notifyhub</groupId><artifactId>notify-worker</artifactId><version>${project.version}</version><scope>test</scope></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-testcontainers</artifactId><scope>test</scope></dependency>
  <dependency><groupId>org.testcontainers</groupId><artifactId>rabbitmq</artifactId><scope>test</scope></dependency>
  <dependency><groupId>org.testcontainers</groupId><artifactId>mongodb</artifactId><scope>test</scope></dependency>
</dependencies>
<build><plugins>
  <plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>   <!-- execuções herdadas do starter-parent -->
    <configuration><systemPropertyVariables>...</systemPropertyVariables></configuration>
  </plugin>
</plugins></build>
```
O Surefire (padrões `*Test`/`*Tests`) não pega `*IT`. O `notify-it` não tem `*Test`, então `./mvnw test` não precisa de Docker. Não declare `spring-boot-maven-plugin` no `notify-it`.

### Padrão 10: cenários dos ITs (TST-02, TST-03)

| IT | Roteiro | Asserções |
|----|---------|-----------|
| Reprocess (TST-02) | POST EMAIL para `fail-until-reprocess@fail.test` (template `welcome`, `variables: {name: ...}`; `requiredVariables` = `["name"]`, `seed/templates/welcome.json`) | Awaitility: DEAD_LETTERED, `attempts.size()==4`, DLQ `notify.email.dlq` com 1 pronta. `POST /admin/dlq/email/reprocess?max=10` + `X-API-Key` → 200 `{requested:10, reprocessed:1, skipped:0, discarded:0}`. Awaitility: SENT, `retryCount==0`, attempts = 4 falhas + REPROCESSED + SUCCESS (`number` 5), DLQ vazia |
| Admin auth / 400 | Com a DLQ populada: sem header, chave errada, canal `push`, `max=0`, `max=1001` | 401/401/400/400/400 em `application/problem+json`, e a contagem da DLQ **não muda** |
| Venenosa (TST-03) | `rabbitTemplate.send(notify.exchange, notification.email, Message("isto não é json"))`, e um segundo caso com `always-permanent@fail.test` | Awaitility: DLQ = 1 e fila principal = 0. `during(1s)` a contagem continua 1, sem loop. Na mensagem lida da DLQ (`basicGet` + nack requeue, ou `receive` num teste que limpa depois): `x-death[0].count == 1` (caso JSON). No caso permanente: FAILED, `attemptCount==1`, cópia com `x-failure-class: PERMANENT` |
| Não roteável (TST-03 + ADM-03) | `amqpAdmin.removeBinding(notify.email ← notify.exchange, notification.email)`, POST, restaurar o binding no `finally` com `declareBinding`, depois `POST /admin/notifications/republish-failed` | 503 + `Retry-After`. Documento PUBLISH_FAILED com `lastError.code == "PUBLISH_RETURNED"`. Depois do republish: 200 `{requested, republished:1}` e Awaitility até SENT |
| Duplicata concorrente (TST-03) | Insere documento PENDING direto no Mongo (`NotificationDocument.newPending`) e publica de uma vez K cópias (ex.: 5) do mesmo `NotificationMessage` com `notify-retry-count=0` | SENT, `attemptCount==1`, uma única entrada SUCCESS. Delta de `notify.delivery.duration{channel=email,outcome=success}.count()` == 1 e de `notify.delivery.outcomes{channel=email,outcome=sent,failure_class=none}` == 1, no `MeterRegistry` do contexto do worker. `during(500ms)` continua igual |
| Idempotência | POST com chave; repetir igual; repetir com payload diferente; N POSTs concorrentes com a mesma chave nova | 202 + `Idempotent-Replayed: true` com o mesmo id; 422; um único documento com aquela chave (`count == 1`), e todas as respostas com o mesmo id |
| Lista e stats | Popular alguns documentos com status e canais variados | Ordem `createdAt` desc, `totalElements`/`totalPages`, 400 com `size=101`/`status=FOO`. Stats com todos os 3 canais × 7 status presentes e zeros |
| Swagger | `GET /v3/api-docs`, `GET /swagger-ui/index.html` | Caminhos e esquema `adminApiKey` presentes; 200 |

O timer certo para "exatamente um envio" é o de duração: o `ChannelGateway` grava `notify.delivery.duration` em volta **só** da chamada ao adapter, por dentro de bulkhead e CB (`ChannelGateway.java:52-56,129-130`). Ele conta envios reais. O contador `outcomes{sent}` conta escritas de SENT que ganharam o fence.

### Anti-padrões a evitar
- **`rabbitTemplate.receive(dlq)`** para o reprocess: faz auto-ack, e a mensagem se perde se o reset ou o publish falhar.
- **`basicReject(tag, false)` / `basicNack(..., requeue=false)` na DLQ:** a DLQ não tem DLX e o broker **descarta** a mensagem.
- **Requeue do `skipped`:** volta para a cabeça e é relido em seguida. Use cópia para o fim + ack.
- **Publicar antes do reset:** a corrida com o claim do worker deixa PENDING sem mensagem.
- **`save()`/leitura-modificação-gravação** no reset: sobrescreve `attempts[]` concorrente. Use um único `findAndModify`.
- **Serializar `PageImpl`** direto na resposta.
- **Dropar a coleção `notifications`** na limpeza entre ITs: some com o índice único parcial e com os demais criados no startup. Use `remove(new Query(), ...)`.
- **`@Testcontainers` + `@Container static`** numa classe base com contextos compartilhados entre classes.

## Não construa à mão

| Problema | Não construa | Use | Por quê |
|----------|--------------|-----|---------|
| Unicidade da Idempotency-Key | "find-then-insert" ou lock em memória | Índice único parcial + `DuplicateKeyException` | Só o índice é atômico entre threads e entre instâncias |
| Comparação da API key | `String.equals` | SHA-256 + `MessageDigest.isEqual` | Tempo constante documentado |
| Confirm do republish | Novo publisher | `NotificationPublisher` existente (+ `CorrelationData` UUID) | Já trata return antes de `isAck()` e o timeout |
| Problem Details | `@ExceptionHandler` escrevendo JSON | `ErrorResponseException` + `ApiExceptionHandler` | Mesmo formato, `instance` e headers (`Retry-After`) |
| Ciclo de vida dos containers | `docker run` em script | Testcontainers + `@ImportTestcontainers` | Portas aleatórias, Ryuk, `@ServiceConnection` |
| Espera assíncrona | `Thread.sleep`/laços com sleep | Awaitility `await().atMost(...)` e `during(...)` | TTLs variam, e `during` prova "sem loop" sem sleep |
| Swagger | YAML OpenAPI escrito à mão | springdoc | Fica sincronizado com os controllers |
| Contagem de status | 21 `count()` ou laço em Java | `$group` (D-12) | Uma ida ao banco |

**Ideia central:** o projeto já tem um molde para cada operação delicada (fence condicional, publish confirmado, Problem Details, índice nomeado). A Fase 3 deve **estender** esses moldes, nunca criar caminhos paralelos.

## Estado em tempo de execução

Esta fase não é rename, mas muda **dados persistidos** e o **formato de artefatos**, então o inventário vale:

| Categoria | Itens encontrados | Ação |
|-----------|-------------------|------|
| Dados armazenados | Volume `mongo-data` do compose: documentos sem `idempotencyKey`/`requestHash` (compatível: o índice parcial ignora) e índices novos criados no startup. Nenhum dado precisa de migração | Nenhuma migração. Se um índice com o mesmo nome e outras chaves existir, o startup falha de propósito (`MongoIndexInitializer` Javadoc, linhas 28-29), e a correção é `docker compose down -v` |
| Config de serviço vivo | Volume `rabbitmq-data`: a topologia não muda nesta fase (nenhum argumento de fila novo) | Nenhuma |
| Estado registrado no SO | Nenhum (verificado: o projeto não registra tarefas nem serviços) | Nenhuma |
| Segredos/variáveis de ambiente | Nova `NOTIFY_ADMIN_API_KEY` (proposta) → `notify.admin.api-key`. Vazia no profile base (fail closed) e com valor documentado no `demo` | Documentar. O compose da Fase 4 precisa passar a variável ou usar o `demo` |
| Artefatos de build | Com `<classifier>exec</classifier>`, o jar executável vira `notify-api-0.1.0-SNAPSHOT-exec.jar`/`notify-worker-...-exec.jar`. Os scripts usam `WORKER_JAR="$RAIZ/notify-worker/target/notify-worker-$VERSAO.jar"` e `API_JAR="$RAIZ/notify-api/target/notify-api-$VERSAO.jar"` [VERIFIED: scripts/smoke-lib.sh:20-21], e `smoke-fase1.sh:228-229` repete os mesmos caminhos | Atualizar os dois scripts para `-exec.jar`. Os Dockerfiles da Fase 4 já devem copiar `*-exec.jar` |

## Armadilhas comuns

### Armadilha 1: entrega do `basicGet` esquecida no canal do cache
**O que dá errado:** uma exceção não prevista no meio do processamento deixa a entrega sem ack nem nack. O canal volta ao cache do `CachingConnectionFactory` ainda aberto, e a mensagem fica "unacked" até a conexão cair: some da DLQ sem estar em lugar nenhum.
**Por que acontece:** o requeue automático só ocorre quando o canal ou a conexão fecha [CITED: rabbitmq.com/docs/confirms]. Um canal em cache não fecha fisicamente [ASSUMED: comportamento do `CachingConnectionFactory`].
**Como evitar:** um `finally` por mensagem com `basicNack(tag, false, true)` se nada foi resolvido (esqueleto do Padrão 2). Teste unitário com `com.rabbitmq.client.Channel` mockado.
**Sinal de alerta:** a coluna "Unacked" da DLQ no painel acima de 0 depois de uma chamada.

### Armadilha 2: requeue descarta em vez de devolver
**O que dá errado:** usar `requeue=false` (ou `basicReject(tag,false)`) achando que "volta para a DLQ".
**Por quê:** sem DLX, o broker descarta [CITED: rabbitmq.com/docs/confirms], e a DLQ é declarada sem argumentos (`NotifyTopology.java:50`).
**Como evitar:** sempre `requeue=true` na DLQ. Um teste unitário verifica o `basicNack(tag, false, true)`.

### Armadilha 3: o `skipped` relido na mesma chamada
**O que dá errado:** com a regra "até N ou DLQ vazia", uma DLQ com 1 mensagem PROCESSING com lease válido e `max=100` seria copiada para o fim e relida 100 vezes.
**Como evitar:** orçamento = `min(max, prontas no início)` (Padrão 2).

### Armadilha 4: converter o corpo da DLQ com o `MessageConverter` do template
**O que dá errado:** `fromMessage` recusa `dev.notifyhub.common.messaging.NotificationMessage` como pacote não confiável. Os pacotes padrão são só `java.util` e `java.lang` [VERIFIED: javap spring-amqp-3.2.12.jar].
**Como evitar:** `objectMapper.readValue(body, NotificationMessage.class)`.

### Armadilha 5: listar por status sem índice
**O que dá errado:** `GET /notifications?status=FAILED` faz SORT em memória. Com volume, isso fica lento e pode estourar o limite de memória de sort.
**Como evitar:** índice `status_createdAt` (Padrão 5). Confira com `explain()`, porque o README da Fase 4 (DOC-02) vai mostrar justamente isso.

### Armadilha 6: `DuplicateKeyException` que não é da Idempotency-Key
**O que dá errado:** tratar toda duplicata como replay. Se o `findOne` por chave não achar nada, a duplicata foi em outro índice único.
**Como evitar:** `existing == null` → relançar.

### Armadilha 7: `CorrelationData` com o id da notificação
**O que dá errado:** o publisher da API usa `new CorrelationData(msg.notificationId())` (`NotificationPublisher.java:32`). Na Fase 1 era uma publicação por notificação, mas agora o reprocess, o replay e o `republish-failed` republicam o mesmo id. Com `mandatory`, o template associa a devolução pelo id da correlação, e ids repetidos em voo podem marcar a publicação errada. É a Armadilha 8 da Fase 2, que fez o worker usar UUID.
**Como evitar:** `new CorrelationData()` (UUID), mantendo `messageId` = id da notificação, e corrigir o comentário da linha 31.

### Armadilha 8: zerar ou incrementar `attemptCount` no reset
**O que dá errado:** `fail-until-reprocess` falha enquanto `attemptNumber <= 4` (`FAIL_UNTIL_REPROCESS_ATTEMPTS = 4`) [VERIFIED: notify-worker/.../channel/MagicRecipients.java:55] e `attemptNumber = doc.attemptCount() + 1` (`DeliveryProcessor.java:128`). Zerar `attemptCount` faz o IT principal falhar para sempre. Incrementar faz o evento parecer uma tentativa.
**Como evitar:** o reset mexe em `retryCount`, `status`, lease e `attempts` (push), **nunca** em `attemptCount`.

### Armadilha 9: `./mvnw verify` sem classifier
**O que dá errado:** o `notify-it` não compila contra o jar repackaged. A doc: "if your module is used as a dependency of another module, you need to provide a classifier for the repackaged one... application classes are packaged in `BOOT-INF/classes` so that the dependent module cannot load a repackaged jar's classes" [CITED: docs.spring.io/spring-boot/3.5/maven-plugin/packaging.html].
**Detalhe:** no `./mvnw test`, o reactor resolve para `target/classes` e tudo parece funcionar. Só o `verify` (depois do `package`) quebra.
**Como evitar:** `<classifier>exec</classifier>` na execução `repackage` (id já definido pelo `starter-parent`) nos dois apps, e atualizar os scripts de smoke.

### Armadilha 10: `spring.config.*` em fonte que não é lida cedo
**O que dá errado:** pôr `spring.config.location`/`spring.config.name` em `SpringApplicationBuilder.properties(...)` (default properties) ou num YAML. A doc garante variável de ambiente, propriedade de sistema e linha de comando, e "does not list default properties" para essas chaves [CITED: docs.spring.io/spring-boot/3.5/reference/features/external-config.html, conforme leitura nesta sessão]. Propriedade de sistema não serve, porque é global da JVM e os dois contextos colidiriam.
**Como evitar:** `args` de `run(...)`. Validar no spike que (a) o worker vê `notify.worker.channels.email.concurrency` e o `resilience4j` do próprio YAML, (b) a API vê `notify.publish.confirm-timeout`, (c) as portas são diferentes e (d) o caminho com espaço resolve.

### Armadilha 11: escada do IT com outro número de níveis
**O que dá errado:** `fail-until-reprocess` tem limite fixo de 4 tentativas ("O limite é fixo, mesmo com outro número de níveis", `MagicRecipients.java:50`). Com 2 níveis, a notificação chega a DEAD_LETTERED depois de 3 tentativas, e a primeira reprocessada (4ª) **falha de novo**.
**Como evitar:** profile `it` com exatamente 3 níveis (`200ms,500ms,1s`).

### Armadilha 12: o worker não sobe no IT por causa do CB
**O que dá errado:** o `ChannelGateway` lança `IllegalStateException` se o `wait-duration-in-open-state` passar do 1º nível [VERIFIED: notify-worker/.../resilience/ChannelGateway.java:91-99]. O padrão é `${NOTIFY_CB_OPEN_WAIT:2500ms}` (`application.yml:104`) e o 1º nível do IT é 200ms.
**Como evitar:** `it/worker/application-it.yml` com `wait-duration-in-open-state: 100ms`. Atenção a listas: definir `spring.autoconfigure.exclude` no YAML de IT do worker **substituiria** a exclusão do `MustacheAutoConfiguration` do `application.yml` do worker (linha 9), porque listas sobrescrevem e não se somam.

### Armadilha 13: vazamento de mensagens entre ITs
**O que dá errado:** um teste com escada deixa tokens nas filas de delay, ou uma entrega em voo publica na DLQ depois do purge. O teste seguinte vê `discarded:1` ou contagens erradas.
**Como evitar:** limpeza em `@BeforeEach`: (1) Awaitility até as filas principais e de delay ficarem com 0 prontas `during(300ms)`; (2) `purgeQueue` em todas as filas, incluindo DLQs e delay; (3) `remove(new Query(), "notifications")`, nunca drop, preservando `templates`; (4) `CircuitBreakerRegistry.getAllCircuitBreakers().forEach(CircuitBreaker::reset)` no contexto do worker. Asserções por id da notificação sempre que der.

### Armadilha 14: binding removido que não volta
**O que dá errado:** o IT de não roteável falha no meio e deixa `notify.email` sem binding. Todos os ITs seguintes de e-mail ficam 503.
**Como evitar:** restaurar com `amqpAdmin.declareBinding(...)` num `finally` (o smoke da Fase 1 faz o mesmo no trap de saída).

## Exemplos de código

### Montar o evento REPROCESSED sem texto livre
```java
// Proposta. AttemptEntry: (number, retryLevel, startedAt, durationMs, outcome, errorCode, errorMessage, failureClass, thread)
AttemptEntry reprocessed = new AttemptEntry(0, previousRetryCount, now, 0L,
        AttemptOutcome.REPROCESSED, null,
        "reprocessada da DLQ pelo operador (status anterior: " + previousStatus.name() + ")",
        null, Thread.currentThread().toString());
```
Ordem dos parâmetros do record [VERIFIED: notify-common/.../persistence/AttemptEntry.java:20-29].

Como o reset é um único `findAndModify`, `previousStatus` e `previousRetryCount` não são conhecidos antes da escrita. Há duas saídas. Uma é ler o documento antes e cercar o reset também por `status = lido` e `retryCount = lido`, o que dá um fence otimista. A outra é usar `returnNew(false)` e montar a mensagem só com o status atual. Recomendação: ler antes e cercar pelo lido. Se o fence se perder, trate como `skipped`.

### Asserção de "sem loop" com Awaitility
```java
await().atMost(Duration.ofSeconds(10)).until(() -> readyCount("notify.email.dlq") == 1);
await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
       .until(() -> readyCount("notify.email.dlq") == 1 && readyCount("notify.email") == 0);
```

### Contador de envios pelo timer do worker
```java
MeterRegistry registry = worker.getBean(MeterRegistry.class);
double before = registry.find(DeliveryMetrics.DURATION)
        .tags("channel", "email", "outcome", "success").timer().count();
// ... publica K cópias e espera SENT ...
assertThat(registry.find(DeliveryMetrics.DURATION)
        .tags("channel", "email", "outcome", "success").timer().count() - before).isEqualTo(1);
```
`DURATION = "notify.delivery.duration"` e a tag `outcome` em minúsculo (`tagValue`) [VERIFIED: notify-worker/.../metrics/DeliveryMetrics.java, `DURATION` e `tagValue(Enum)`].

## Estado da arte

| Abordagem antiga | Atual | Quando mudou | Impacto |
|------------------|-------|--------------|---------|
| `@DynamicPropertySource` com propriedades do container | `@ServiceConnection` / `@ImportTestcontainers` | Boot 3.1 | Sem mapear host e porta à mão |
| Containers via `@Testcontainers` + `@Container static` compartilhados | Beans gerenciados pelo Spring ou `@ImportTestcontainers` | Recomendação da doc do Boot 3.x | A extensão JUnit para os containers ao fim da classe |
| Testcontainers ≤ 1.21.3 | 1.21.4 | 2025-12 | Handshake com Docker Engine 29 (o ambiente tem Engine **29.8.0**, API 1.56) |
| springdoc 2.8.x como única linha para Boot 3.5 | 2.9.x/2.10.0 também compilados contra Boot 3.5.16 | 2026-08 a 2026-10 | Opção futura, e o CLAUDE.md precisa de atualização (veja Suposições) |

**Desatualizado/obsoleto:** nada usado na fase.

## Registro de suposições

| # | Afirmação | Seção | Risco se estiver errada |
|---|-----------|-------|-------------------------|
| A1 | `ErrorResponseException` lançada em `HandlerInterceptor.preHandle` é resolvida pelo `@RestControllerAdvice` (vira Problem Detail) | Padrão 1 | O 401 sairia no formato padrão do Boot. Alternativa: escrever o Problem Detail na resposta dentro do interceptor |
| A2 | O Spring Data não grava campo `null`, então o índice parcial `$exists:true` ignora documentos sem chave | Padrão 4 | Dois POSTs sem chave colidiriam (`DuplicateKeyException`). Alternativa: filtro `$type: "string"` |
| A3 | `CachingConnectionFactory` devolve o canal ao cache sem fechar, e entregas sem ack ficam pendentes | Armadilha 1 | Nenhum: a mitigação (nack no `finally`) é correta nos dois casos |
| A4 | O `MongoDBContainer` 1.x expõe o banco `test` via `getReplicaSetUrl()` e os dois contextos o compartilham | Padrão 9 | Contextos em bancos diferentes, e o worker não veria os documentos da API. O spike detecta |
| A5 | `--spring.config.location` com vários locais (diretório `file:` + `optional:classpath:`) em `args` de `SpringApplication.from(...).run(...)` carrega o YAML do módulo e os overrides `application-it.yml` | Padrão 9 | O harness não sobe. Fallback: renomear os YAMLs dos apps (Alternativas) ou passar tudo por `args` |
| A6 | Caminhos padrão do springdoc 2.x: `/swagger-ui.html` e `/v3/api-docs`; `override-with-generic-response` polui a doc com `ResponseEntityExceptionHandler` | Padrão 8 | Só ajustes de teste e de doc |
| A7 | O Spring Data 3.3+ avisa contra serializar `PageImpl` | Padrão 5 | Nenhum (o envelope próprio é a recomendação de qualquer forma) |
| A8 | Recomendar springdoc 2.10.0 em vez de 2.8.17 exigiria mudar o CLAUDE.md. A afirmação do CLAUDE.md "Only the 2.8.x line works with Boot 3.5" está desatualizada (2.9.1 e 2.10.0 têm POM pai Boot 3.5.16) | Stack | Nenhum se ficar em 2.8.17 (compilado contra 3.5.13) |
| A9 | Guardar REPROCESSED dentro de `attempts[]` com `number=0` atende à leitura do critério de sucesso 1 | Padrão 3 | O usuário pode preferir `events[]`. Mudar depois custa porque altera o formato persistido |

## Perguntas em aberto

1. **Corpo inválido na DLQ durante o reprocess**
   - O que sabemos: o D-02 só cobre lease válido (`skipped`) e SENT/inexistente (`discarded`). Mensagens com JSON quebrado ou sem `notificationId` existem (é o cenário venenoso do TST-03).
   - O que falta: em qual contagem elas entram.
   - Recomendação: cópia para o fim + ack, contada como `skipped`, com log WARN pelo `messageId`. Isso preserva a mensagem para inspeção (OPS-03, v2) e nunca trava o lote. A alternativa (descartar) contraria o valor central.

2. **`recipient` exposto na listagem sem autenticação**
   - O que sabemos: o D-11 inclui `recipient` no resumo, e a API pública não tem autenticação (fora do escopo). A listagem permite enumerar todos os destinatários, coisa que o `GET /{id}` sozinho não permitia sem conhecer o UUID.
   - Recomendação: manter o D-11 e registrar como risco aceito no threat model e no README (DOC-03). Mascarar com `RecipientRules.mask` seria desvio do D-11 e precisa de confirmação.

3. **Chave do admin no compose da Fase 4**
   - Recomendação: o `application-demo.yml` da API define `notify.admin.api-key: ${NOTIFY_ADMIN_API_KEY:demo-admin-key}` (valor proposto), documentado no README. O profile base fica vazio (fail closed).

4. **Fence otimista do reset**
   - Recomendação: ler o documento, depois `findAndModify` cercado por `status` e `retryCount` lidos (Exemplos de código). Isso dá a mensagem REPROCESSED com o status anterior e protege contra corrida com o claim do worker. Fence perdido vira `skipped`.

## Disponibilidade do ambiente

| Dependência | Requerida por | Disponível | Versão | Fallback |
|-------------|---------------|------------|--------|----------|
| JDK 21 | build e testes | ✓ | 21.0.10 (Oracle) | — |
| Maven Wrapper | build | ✓ | wrapper 3.3.4 → Maven 3.9.16 (`.mvn/wrapper/maven-wrapper.properties`) | — |
| Docker Engine | ITs (Testcontainers) | ✓ | 29.8.0, API 1.56, Docker Desktop | — (sem Docker, só `./mvnw test` roda, como o critério exige) |
| Imagens `rabbitmq:4.3.6-management`, `mongo:8.0.32` | ITs | ✓ (já usadas pelo compose) | — | — |
| `javap` | — | ✓ em `C:/Program Files/Java/jdk-21.0.10/bin` (fora do PATH do Git Bash) | — | — |

**Dependências ausentes sem fallback:** nenhuma.
**Dependências ausentes com fallback:** nenhuma.

## Domínio de segurança

`security_enforcement: true`, ASVS nível 1 (`.planning/config.json`).

### Categorias ASVS aplicáveis

| Categoria ASVS | Aplica | Controle padrão |
|----------------|--------|-----------------|
| V2 Autenticação | sim (só admin) | API key única por ENV, SHA-256 + `MessageDigest.isEqual`, fail closed sem chave, chave nunca logada nem ecoada |
| V3 Sessão | não | Sem sessão (stateless) |
| V4 Controle de acesso | sim | Interceptor em `/admin/**` **antes** do binding, e nada é lido da DLQ sem autorização |
| V5 Validação de entrada | sim | Canal e status pelo enum (400), `max` 1..teto, `page ≥ 0`, `size` 1..100, `Idempotency-Key` com tamanho e charset limitados, corpo da DLQ lido para o tipo concreto |
| V6 Criptografia | parcial | SHA-256 do JDK (hash do pedido e comparação da chave); nada feito à mão |
| V7 Erros e logs | sim | Problem Details sem mensagem interna nem texto do broker (T-02-01); motivos de 503 em conjunto fechado; destinatário mascarado nos logs (`RecipientRules.mask`) |
| V8 Proteção de dados | sim | O resumo da lista expõe `recipient` (Pergunta em aberto 2); o `requestHash` não é exposto no GET |
| V13 API | sim | 401/400/422/503 coerentes; Swagger aberto (portfólio) |

### Ameaças conhecidas para esta stack

| Padrão | STRIDE | Mitigação padrão |
|--------|--------|------------------|
| Força bruta ou timing na API key | Spoofing | Comparação em tempo constante de digests de tamanho fixo; a chave da demo é documentada e o base fica vazio |
| Admin move mensagens sem autorização | Elevation of Privilege | Interceptor recusa antes do controller; IT confere que a contagem da DLQ não muda |
| Desserialização dirigida por `__TypeId__` no reprocess | Tampering | `readValue(body, NotificationMessage.class)`, sem conversor por header |
| Texto livre do broker na resposta ou no `lastError` | Information Disclosure | `PublishResult.errorCode()` e motivos enumerados; opcionalmente parar de gravar `detail()` (Padrão 4b) |
| Perda silenciosa de mensagem no reprocess | Repudiation/DoS do valor central | Ack só depois de reset + confirm; nack com requeue na falha; `finally` com nack; nunca `requeue=false` |
| Reprocess em loop numa chamada | DoS | Orçamento `min(max, prontas no início)`; teto 1000 |
| Paginação profunda e stats em COLLSCAN | DoS | `size ≤ 100`; opcional limite de `page*size`; stats numa única agregação |
| Sondagem de Idempotency-Key de outro cliente | Information Disclosure | Inerente sem autenticação; documentar que a chave deve ser de alta entropia (UUID) |
| Enumeração de destinatários pela lista | Information Disclosure | Risco aceito documentado ou mascaramento (Pergunta em aberto 2) |

## Fontes

### Primárias (ALTA confiança)
- Código do repositório lido nesta sessão: `NotificationStatus`, `AttemptOutcome`, `AttemptEntry`, `NotificationDocument`, `MongoIndexInitializer`, `Channel`, `NotifyTopology`, `NotifyHeaders`, `NotifyCommonConfiguration`, `NotificationService`, `NotificationPublisher`, `PublishResult`, `ApiExceptionHandler`, `ProblemTypes`, `NotificationController`, `DeliveryProcessor`, `NotificationStateService`, `DeliveryRoutingPublisher`, `ChannelGateway`, `MagicRecipients`, `SimulatedChannelAdapter`, `DeliveryMetrics`, `application*.yml` dos dois apps, POMs, `scripts/smoke-lib.sh`, `docker-compose.yml`, `deferred-items.md` (CR-01), `02-SECURITY.md`
- `spring-boot-dependencies-3.5.16.pom` e `spring-boot-starter-parent-3.5.16.pom` (local `~/.m2`): versões gerenciadas, Failsafe e repackage
- Bytecode (`javap`): `spring-data-mongodb-4.5.13` (`Index.partial/unique`, `PartialIndexFilter.of`, tradução para `DuplicateKeyException`), `spring-rabbit-3.2.12` (`RabbitTemplate.execute`, `DefaultMessagePropertiesConverter`), `spring-amqp-3.2.12` (pacotes confiáveis do conversor), `spring-boot-3.5.16` (`SpringApplication.from/with/Running`), `testcontainers-1.21.4` (`start()` idempotente)
- Fonte do Spring Boot v3.5.16 no GitHub: `ContainerFieldsImporter`, `TestcontainerFieldBeanDefinition`, `TestcontainersLifecycleBeanPostProcessor`, `ImportTestcontainersRegistrar`
- Maven Central: `springdoc-openapi-starter-webmvc-ui` (2.8.17, 2.9.x, 2.10.0 e POMs pai), `org.testcontainers:{rabbitmq,mongodb,junit-jupiter}` 1.21.4
- https://docs.spring.io/spring-boot/3.5/maven-plugin/packaging.html: classifier no repackage
- https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html: leitura antecipada de `spring.config.*`, ordem de locais
- https://docs.spring.io/spring-boot/3.5/reference/features/dev-services.html: `SpringApplication.from(...).with(...)`, `@ImportTestcontainers`
- https://docs.spring.io/spring-boot/3.5/reference/testing/testcontainers.html: tabela de service connections, ciclo de vida
- https://www.rabbitmq.com/docs/confirms: requeue, canal fechado, `basic.get`, tags por canal, nack sem DLX
- https://www.mongodb.com/docs/manual/core/index-partial/: filtros suportados, unique + partial
- https://www.mongodb.com/docs/manual/tutorial/sort-results-with-indexes/: igualdade nos prefixos antes da chave de sort
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/MessageDigest.html: `isEqual` em tempo constante

### Secundárias (MÉDIA confiança)
- `.claude/CLAUDE.md` (versões e fontes listadas lá): correção do Docker 29 no Testcontainers 1.21.4, imagens fixadas

### Terciárias (BAIXA confiança)
- Comportamentos marcados `[ASSUMED]` no Registro de suposições (A1-A9)

## Metadados

**Detalhamento da confiança:**
- Stack padrão: ALTA. Versões conferidas no BOM local e no Maven Central.
- Arquitetura (reprocess, idempotência, lista, stats): ALTA. Assenta em código lido e em APIs conferidas no bytecode e nas docs oficiais do RabbitMQ e do MongoDB.
- Harness de dois contextos: MÉDIA. Cada peça está documentada ou conferida na fonte, mas a combinação (config por `args` + `@ImportTestcontainers` compartilhado + caminho com espaço) precisa do spike da Onda 0.
- Armadilhas: ALTA. A maioria vem de código lido (CB vs 1º nível, `attemptCount`, laço do `DeliveryMetrics`, nomes de jar nos scripts) ou da doc oficial (classifier, requeue, índices).

**Data da pesquisa:** 2026-10-28
**Válido até:** 2026-11-27 (stack fixada; reavaliar só se mudar a versão do Boot, do springdoc ou do Testcontainers)
