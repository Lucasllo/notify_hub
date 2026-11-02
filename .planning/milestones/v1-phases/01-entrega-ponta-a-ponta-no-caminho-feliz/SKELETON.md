# Walking Skeleton — Notify Hub

**Phase:** 1
**Generated:** 2026-10-10

## Capability Proven End-to-End

Um cliente faz `POST /notifications` (canal EMAIL, template `welcome`), recebe 202 com `Location` e, seguindo o `Location`, vê a notificação passar de PENDING para SENT, com o texto renderizado em pt-BR e uma tentativa registrada (timestamp, duração e thread virtual), tudo rodando localmente com `docker compose up -d` (RabbitMQ + MongoDB) e as duas apps Spring Boot (`notify-api` na 8080 e `notify-worker` na 8081).

A prova automatizada desse caminho é `scripts/smoke-fase1.sh` (plano 01-06), que sobe a infra, inicia worker e API a partir dos jars e confere o fluxo inteiro, a topologia no broker e os índices no Mongo.

## Architectural Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Build | Maven multi-módulo (`notify-hub-parent` + `notify-common`, `notify-api`, `notify-worker`), `spring-boot-starter-parent` 3.5.16, Java 21, Maven Wrapper (Maven 3.9.16) | Restrição do projeto (CLAUDE.md). Boot 3.5.16 é o último 3.x OSS; o wrapper dispensa Maven instalado (o PATH desta máquina não tem `mvn`) |
| Coordenadas | `groupId dev.notifyhub`, versão `0.1.0-SNAPSHOT`, pacote base `dev.notifyhub.{common,api,worker}` | Critério do Claude (CONTEXT). Um prefixo curto e estável para os três módulos |
| Contrato compartilhado | `notify-common`: `Channel` (única fonte das conversões maiúsculo/minúsculo, D-05), `NotificationStatus` (tabela de transições), `NotificationMessage` fino (`notificationId`, `channel`, `templateCode`, `createdAt`), `NotifyHeaders`, documentos Mongo e `MongoIndexInitializer` | API e worker nunca se chamam; só compartilham o contrato AMQP e o schema do Mongo |
| Mensageria | RabbitMQ 4.3.6 (`rabbitmq:4.3.6-management`), topologia inteira num único bean `Declarables` (`NotifyTopology`): `notify.exchange` (topic), `notify.retry` (direct), `notify.dlx` (direct); por canal, fila principal, 3 filas de delay com o TTL no nome e DLQ | Argumentos de fila num só lugar evitam 406 PRECONDITION_FAILED; TTL + DLX é nativo e visível no painel |
| Níveis de delay | `notify.topology.retry-levels` (`List<Duration>`): padrão 5s/25s/125s, profile `demo` 1s/5s/25s, testes em ms | MSG-05; a routing key de retry usa o rótulo do TTL (`email.retry.5s`), não o ordinal |
| Dados | MongoDB 8.0.32 + Spring Data MongoDB (síncrono). Transições só por `MongoTemplate` (`findAndModify`/`updateFirst` com `$set` + `$push`), nunca `save()` | O documento é a fonte da verdade; a mensagem é só um token. Atomicidade e fence por `status` + `retryCount` |
| Índices | Criados explicitamente na inicialização por `MongoIndexInitializer.createIndexes()` (`createIndex`, nomes fixos), chamado pela API e pelo worker | FND-04; `auto-index-creation` fica desligado |
| Publicação | `RabbitTemplate` com publisher confirms correlacionados + `mandatory`/returns; espera o confirm por até 5s (configurável); falha → PUBLISH_FAILED condicional + 503 com `Retry-After` | Core value: aceita = nunca perdida em silêncio, sem outbox |
| Consumo | Um container `@RabbitListener` por canal (`listener-email/sms/webhook`), executor de virtual threads instalado pelo configurer do Boot (`spring.threads.virtual.enabled=true`), `default-requeue-rejected=false`, retry interno desligado, prefetch 10, `auto-startup=false` + runner que inicia os listeners depois de índices e seed | Sem factory manual (perderia o executor VT); rejeição estaciona na DLQ sem loop |
| Templates | JMustache 1.16 no worker, coleção `templates` com variantes por canal, seed por JSON em `notify-worker/src/main/resources/seed/templates/*.json` aplicado por `ApplicationRunner` com controle de `version` (D-06..D-10) | Mesmo comportamento no compose, local e Testcontainers |
| Erros HTTP | `ProblemDetail` (RFC 9457) via `ApiExceptionHandler extends ResponseEntityExceptionHandler`, `type` em `urn:notify-hub:problem:*` | Formato padrão, sem stack trace |
| Auth | Nenhuma na API pública nesta fase; `X-API-Key` só nos endpoints admin (Fase 3) | Escopo do projeto (REQUIREMENTS Out of Scope) |
| Deployment | Compose da Fase 1 sobe só RabbitMQ e MongoDB (portas em `127.0.0.1`). As apps rodam por `./mvnw -pl <módulo> spring-boot:run` ou `java -jar`. Dockerfiles das apps chegam na Fase 4 | Critério do Claude (CONTEXT, Layout) |
| Testes | JUnit 5 + AssertJ + Mockito sem Docker (`./mvnw test`); prova de runtime por `scripts/smoke-fase1.sh` com Docker; Testcontainers e Failsafe (`*IT`) só na Fase 3 | `./mvnw test` precisa continuar rodando sem Docker |
| Directory layout | `notify-common/.../common/{domain,messaging,persistence}`, `notify-api/.../api/{config,web,web/dto,web/validation,application,messaging}`, `notify-worker/.../worker/{config,listener,processing,template,channel}` | Camadas finas por responsabilidade; `config` isolado para que testes de fatia não carreguem o contrato inteiro |

## Stack Touched in Phase 1

- [x] Project scaffold — Maven multi-módulo + wrapper + `.gitattributes`/`.gitignore` (plano 01-01)
- [x] Routing — `POST /notifications` e `GET /notifications/{id}` (plano 01-04)
- [x] Database — escrita real (insert PENDING, claim `findAndModify`, `markSent` com `$push`) e leitura real (`findById` no GET, templates do seed) (planos 01-02, 01-03, 01-04, 01-05)
- [x] UI — não se aplica (portfólio backend). A "interface" é a API REST + o painel do RabbitMQ em http://localhost:15672, verificados no plano 01-06
- [x] Deployment — execução local documentada da stack completa: `docker compose up -d` + jars das apps, automatizada por `scripts/smoke-fase1.sh` (plano 01-06)

## Out of Scope (Deferred to Later Slices)

- Classificador de falhas, retry pela escada de delay, DEAD_LETTERED/FAILED, `x-failure-class`, circuit breaker, bulkhead, destinatários mágicos, métricas Micrometer, recuperação de PROCESSING preso (Fase 2)
- Escape JSON dos valores no WEBHOOK e renderizador estrito com `requiredVariables` (Fase 2, TPL-02/TPL-03)
- `Idempotency-Key`, listagem paginada, stats, Swagger UI, endpoints admin (DLQ reprocess, republish de PUBLISH_FAILED), ITs com Testcontainers via Failsafe (Fase 3)
- Dockerfiles das apps, Prometheus, Grafana, k6, benchmark de virtual threads e README (Fase 4)
- Outbox, jitter, Spring Boot 4.x (v2 / evolução)

## Subsequent Slice Plan

Cada fase seguinte acrescenta uma fatia vertical sobre este esqueleto sem alterar as decisões acima:

- Phase 2: toda falha tem destino previsível (retry por nível na escada de delay já declarada, DEAD_LETTERED, FAILED), com circuit breaker, bulkhead, destinatários mágicos e métricas
- Phase 3: operador reprocessa DLQ e PUBLISH_FAILED, cliente consulta/filtra/reenvia com `Idempotency-Key`, e ITs com Testcontainers provam retry → DLQ → reprocessamento → SENT
- Phase 4: `docker compose up --build` sobe tudo, Grafana mostra 1000 notificações do k6, README em pt-BR com arquitetura, trade-offs e benchmark
