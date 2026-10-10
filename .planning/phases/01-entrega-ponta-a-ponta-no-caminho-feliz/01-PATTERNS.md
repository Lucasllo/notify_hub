# Fase 1: Entrega ponta a ponta no caminho feliz - Mapa de padrões

**Mapeado em:** 2026-10-10
**Arquivos analisados:** ~60 (novos; nenhum modificado)
**Análogos encontrados:** 0 / ~60

> **Greenfield.** `git ls-files` só lista arquivos em `.planning/` e `.claude/`. Não há código-fonte para servir de análogo. Esta fase **estabelece** as convenções. Cada arquivo abaixo aponta para o padrão de referência em `01-RESEARCH.md` (abreviado `R`), que traz trechos já conferidos no código-fonte das versões exatas. Os arquivos criados nos planos 02 (common) viram os análogos dos planos 03/04 (api/worker).

## Classificação de arquivos

Base de pacotes sugerida: `dev.notifyhub` (escolha do planner).

### Plano 01: esqueleto
| Arquivo | Papel | Fluxo | Referência |
|---------|-------|-------|------------|
| `pom.xml` (pai) | config | build | R §Stack padrão (starter-parent 3.5.16, `java.version` 21, módulos) |
| `notify-common/pom.xml` | config | build | R §Stack padrão: jar simples, **sem** `spring-boot-maven-plugin`; deps `spring-boot`, `spring-rabbit`, `spring-data-mongodb`, `jackson-annotations` sem versão |
| `notify-api/pom.xml`, `notify-worker/pom.xml` | config | build | R §Núcleo (web, amqp, data-mongodb, actuator, validation só na api, jmustache só no worker) |
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` | config | build | R §Bootstrap do wrapper (gerar com a dist 3.9.16 em cache, depois do POM pai) |
| `.gitignore`, `.gitattributes` | config | — | R §Estrutura (`mvnw`/`*.sh` eol=lf, `*.cmd` eol=crlf) |
| `docker-compose.yml` | config | infra | R §Exemplos de código, compose (linhas 507-542) |

### Plano 02: `notify-common` (`dev.notifyhub.common`)
| Arquivo | Papel | Fluxo | Referência |
|---------|-------|-------|------------|
| `NotifyCommonConfiguration.java` | config | — | R Padrão 1 (único `Jackson2JsonMessageConverter` com o `ObjectMapper` do Boot) + Padrão 3 (bean `Declarables`) + `@EnableConfigurationProperties(TopologyProperties)` |
| `domain/Channel.java` | model (enum) | transform | R Padrão 1 (`id/routingKey/queue/dlq/dlqKey/retryQueue/retryKey`) |
| `domain/NotificationStatus.java` | model (enum) | — | R Padrão 2 (tabela `ALLOWED`, `canTransitionTo`, `sourcesOf`) |
| `domain/AttemptOutcome.java` | model (enum) | — | R Padrão 8 (`SUCCESS` na Fase 1, enum completo) |
| `domain/RecipientRules.java` | utility | validação | R Padrão 5 (regex e-mail/E.164/URI http(s), limites) |
| `messaging/NotificationMessage.java` | model (record) | event | R Padrão 1 (payload fino, sem PII) |
| `messaging/NotifyHeaders.java` | constants | — | R Padrão 1 (`RETRY_COUNT`, `FAILURE_CLASS`) |
| `messaging/NotifyTopology.java` | config | — | R Padrão 3 (nomes de exchange + factory do `Declarables`) |
| `messaging/TopologyProperties.java` | config (record) | — | R Padrão 3 (`@DefaultValue("5s,25s,125s")`, valida crescente) |
| `messaging/TtlLabel.java` | utility | transform | R Padrão 3 |
| `persistence/NotificationDocument.java`, `AttemptEntry`, `RenderedContent`, `LastError` | model | CRUD | R Padrão 8 (campos, `attemptCount`, `thread`) + D-03 |
| `persistence/TemplateDocument.java`, `TemplateVariant` | model | CRUD | R Padrão 9 (JSON do template) |
| `persistence/Collections.java` | constants | — | R Padrão 6 |
| `persistence/MongoIndexInitializer.java` | service | batch (startup) | R Padrão 6 (`createIndex`, nomes explícitos) |
| Testes: `ChannelTest`, `NotificationStatusTest`, `NotifyTopologyTest`, `TopologyPropertiesTest`, `TtlLabelTest`, `RecipientRulesTest`, `MongoIndexInitializerTest` | test | — | R §Requisito → teste |

### Plano 03: `notify-api` (`dev.notifyhub.api`)
| Arquivo | Papel | Fluxo | Referência |
|---------|-------|-------|------------|
| `NotifyApiApplication.java` | config | — | R §Estrutura (`@Import(NotifyCommonConfiguration.class)`) |
| `web/NotificationController.java` | controller | request-response | R Padrão 4 (resposta 202 + Location) |
| `web/ApiExceptionHandler.java`, `ProblemTypes.java` | middleware | request-response | R Padrão 5 |
| `web/dto/CreateNotificationRequest`, `CreateNotificationResponse`, `NotificationView` | model (record) | — | D-01, D-02, D-03 |
| `web/validation/ValidRecipient`, `ValidRecipientValidator` | middleware (validação) | — | R Padrão 5 (constraint de classe → `addPropertyNode("recipient")`) |
| `application/NotificationService.java` | service | CRUD + publish | R Padrão 4 (insert PENDING → publish → `updateFirst` condicional para PUBLISH_FAILED) |
| `application/NotificationQueryService.java` | service | CRUD (read) | R Padrão 4 (`findById` → DTO) |
| `messaging/NotificationPublisher.java`, `PublishResult.java` | service | request-response (confirms) | R Padrão 4 (linhas 290-307) |
| `startup/ApiStartup.java` | config | batch (startup) | R Padrão 9, parágrafo "API" (índices `@Order(1)`, `amqpAdmin.initialize()` `@Order(2)`) |
| `application.yml`, `application-demo.yml` | config | — | R linha 575 |
| Testes: `NotificationControllerTest` (`@WebMvcTest`), `NotificationServiceTest`, `NotificationPublisherTest` | test | — | R §Requisito → teste |

### Plano 04: `notify-worker` (`dev.notifyhub.worker`)
| Arquivo | Papel | Fluxo | Referência |
|---------|-------|-------|------------|
| `NotifyWorkerApplication.java` | config | — | igual à API |
| `listener/EmailListener`, `SmsListener`, `WebhookListener` | controller (listener) | event-driven | R Padrão 7 |
| `listener/RetryHeaders.java` | utility | transform | R Padrão 7 (`parse(Object)`) |
| `processing/DeliveryProcessor.java` | service | event-driven | R §Diagrama passos 1-4 |
| `processing/NotificationStateService.java` | service | CRUD (atômico) | R Padrão 8 (claim `findAndModify`, `markSent` `updateFirst` com `$push`/`$each`/`$slice`) |
| `template/TemplateRenderer.java` | service | transform | R Padrão 9 (compiladores `HTML` e `PLAIN`, sem `defaultValue`) |
| `template/TemplateSeeder.java` | service | file-I/O + CRUD | R Padrão 9 (ler → insert / `findAndReplace` por versão) |
| `channel/ChannelAdapter`, `SimulatedChannelAdapter`, `SimulationProperties`, `SimulatedDeliveryException` | service | request-response (simulado) | R Padrão 10 |
| `startup/WorkerStartup.java` | config | batch (startup) | R Padrão 9 (runners `@Order` 1-3, `registry.start()`) |
| `resources/seed/templates/{welcome,order-shipped,password-reset}.json` | config (dados) | file-I/O | R Padrão 9 (exemplo `order-shipped`), D-06..D-10 |
| `application.yml`, `application-demo.yml` | config | — | R linhas 544-573 |
| Testes: `DeliveryProcessorTest`, `NotificationStateServiceTest`, `TemplateRendererTest`, `TemplateSeederTest`, `SeedTemplatesTest`, `SimulatedChannelAdapterTest` | test | — | R §Requisito → teste |

## Atribuições de padrão (trechos-chave a copiar de RESEARCH)

- **Contrato e topologia:** R linhas 212-287. O `Declarables` usa sempre `QueueBuilder.durable(...)`, `.ttl(int)` e a chave de retry com o rótulo do TTL (`email.retry.5s`).
- **Publish com confirm:** R linhas 290-307. O `CorrelationData` usa o id da notificação, e o código confere `getReturned()` antes de `isAck()`.
- **PUBLISH_FAILED condicional:** R linhas 309-315. Se casar 0 documentos, a resposta é 202 (Armadilha 4).
- **Problem Details:** R linhas 317-325.
- **Índices:** R linhas 327-336.
- **Listener:** R linhas 338-353. Não criar uma factory manual.
- **Claim/markSent:** R linhas 355-381. Nunca usar `save()`.
- **Seed + ordem de startup:** R linhas 383-420.
- **Adapter:** R linhas 422-428.

## Padrões compartilhados (convenções que esta fase estabelece)

| Convenção | Aplica-se a | Fonte |
|-----------|-------------|-------|
| Records para DTOs, mensagens e properties; sem Lombok | todos os módulos | CLAUDE.md, CONTEXT §Established Patterns |
| Transições de status só via `MongoTemplate` (`findAndModify`/`updateFirst`), com filtros derivados de `NotificationStatus.sourcesOf` | api, worker | R Padrões 2 e 8 |
| Um único `MessageConverter` e um único `Declarables`, ambos em `NotifyCommonConfiguration` | api, worker | R Padrões 1 e 3 |
| Toda conversão de canal (maiúsculo ↔ minúsculo, nomes de fila e chaves) só pelo enum `Channel` | todos | D-05 |
| Runners de startup ordenados com `@Order` (índices → seed → `initialize`/`start`) | api, worker | R Padrão 9, Armadilha 1 |
| Erros HTTP como `ProblemDetail` com `type` vindo de `ProblemTypes` | api | R Padrão 5 |
| Destinatário mascarado nos logs; PII fora do payload AMQP | api, worker | R §Domínio de segurança |
| Testes unitários sem Docker (`./mvnw -q test`); nada de `Thread.sleep` em testes assíncronos | todos | CLAUDE.md, R §Arquitetura de validação |

## Sem análogo

Todos os arquivos acima (o repositório é greenfield). O planner deve usar os padrões de `01-RESEARCH.md` citados por linha e, nos planos 03/04, tratar os arquivos do plano 02 (`Channel`, `NotificationStatus`, `NotificationDocument`, `NotifyCommonConfiguration`) como análogos internos.

## Metadados

**Escopo da busca:** a raiz do repositório inteira (`git ls-files`, excluindo `.planning/` e `.claude/`). Resultado: vazio.
**Arquivos varridos:** 0 de código-fonte.
**Data da extração:** 2026-10-10
