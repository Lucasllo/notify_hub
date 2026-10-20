# Requirements: Notify Hub

**Defined:** 2026-10-09
**Core Value:** Uma notificação aceita pela API nunca se perde em silêncio. Ela é entregue (simuladamente), passa por retries com backoff exponencial ou termina na DLQ com histórico completo, de onde pode ser reprocessada, tudo observável e demonstrável com `docker compose up` + script.

## v1 Requirements

### Fundação (FND)

- [x] **FND-01**: O projeto compila como Maven multi-módulo (parent POM + `notify-common`, `notify-api`, `notify-worker`) com Maven Wrapper, Java 21 e Spring Boot 3.5.16
- [x] **FND-02**: `notify-common` define o contrato compartilhado: enums de canal e status, tabela de transições permitidas, record da mensagem AMQP (id, canal, templateCode), nomes de headers e constantes de topologia
- [x] **FND-03**: `docker compose up` sobe RabbitMQ 4.3 (management + prometheus plugin) e MongoDB 8.0 com healthchecks e tags de imagem pinadas
- [x] **FND-04**: Os índices do MongoDB são criados explicitamente na inicialização (não dependem de `auto-index-creation`)

### Topologia de mensageria (MSG)

- [x] **MSG-01**: A topologia é declarada em código num único bean `Declarables`: topic exchange principal (`notify.exchange`), exchange de retry (`notify.retry`) e exchange de dead-letter (`notify.dlx`)
- [x] **MSG-02**: Existe uma fila principal por canal (email, sms, webhook) ligada à topic exchange por `notification.<canal>`
- [x] **MSG-03**: Cada canal tem 3 filas de delay com TTL fixo por fila e backoff exponencial de fator 5 (5s → 25s → 125s). O TTL vai no nome da fila e cada fila faz dead-letter de volta à fila principal com routing key explícita
- [x] **MSG-04**: Cada canal tem uma DLQ final. A fila principal faz dead-letter para ela, então rejeitar uma mensagem a estaciona na DLQ
- [x] **MSG-05**: Os níveis de delay são configuráveis por propriedade: profile `demo` curto (ex.: 1s/5s/25s) e TTLs de milissegundos nos testes

### API (API)

- [x] **API-01**: O cliente pode criar uma notificação via `POST /notifications` (canal, destinatário, templateCode, variáveis) e recebe 202 + `Location`. A notificação é persistida como PENDING antes do publish
- [x] **API-02**: A API publica com publisher confirms correlacionados + `mandatory`/returns. Confirm negativo, timeout ou mensagem devolvida grava PUBLISH_FAILED (update condicional) e responde 503 com Problem Detail + `Retry-After`
- [ ] **API-03**: O cliente pode enviar o header `Idempotency-Key`. A mesma chave com o mesmo payload devolve a notificação original; a mesma chave com payload diferente devolve 422
- [x] **API-04**: O cliente pode consultar `GET /notifications/{id}` com status, histórico de tentativas embutido e último erro
- [ ] **API-05**: O cliente pode listar `GET /notifications?status=&channel=` com paginação e ordenação por data de criação decrescente
- [x] **API-06**: Erros de validação estrutural (canal inválido, campos ausentes, formato de destinatário por canal) retornam 400 com Problem Details (RFC 9457)
- [ ] **API-07**: O cliente pode consultar `GET /notifications/stats` com contagens agregadas por status e canal
- [ ] **API-08**: A API expõe Swagger UI (springdoc 2.8.x) documentando todos os endpoints

### Worker (WRK)

- [x] **WRK-01**: O worker consome uma fila por canal (um container de listener por canal), com `default-requeue-rejected=false`, retry interno do Spring desativado e prefetch baixo
- [x] **WRK-02**: O worker reivindica cada mensagem com um `findAndModify` condicional (PENDING/RETRYING → PROCESSING, cercado pelo contador de tentativas). Mensagens duplicadas ou obsoletas são descartadas sem novo envio
- [ ] **WRK-03**: Notificações presas em PROCESSING (ex.: worker morreu antes do ack) são recuperáveis na redelivery, via flag `redelivered` ou lease expirado
- [x] **WRK-04**: Cada resultado (SENT, RETRYING, FAILED, DEAD_LETTERED) é gravado numa única atualização atômica (`$set` status + `$push` tentativa com timestamp, duração, erro e classe da falha)
- [x] **WRK-05**: Virtual threads estão habilitadas no worker (`spring.threads.virtual.enabled=true`), com concorrência de listener configurável

### Templates (TPL)

- [x] **TPL-01**: Templates Mustache ficam na coleção `templates` (um documento por template, com variantes por canal e `requiredVariables`) e são populados por seed idempotente na inicialização
- [ ] **TPL-02**: O renderizador é estrito: variável obrigatória ausente ou template inexistente gera falha permanente
- [ ] **TPL-03**: O escape é por canal: HTML no e-mail, sem escape HTML no SMS e valores escapados como JSON no webhook

### Entrega e falhas (DLV)

- [x] **DLV-01**: Há um adapter simulado por canal, com latência bloqueante e taxa de falha aleatória configuráveis
- [ ] **DLV-02**: Destinatários "mágicos" injetam falhas determinísticas: sempre transitória, sempre permanente e "falha N vezes e depois sucede"
- [ ] **DLV-03**: Um classificador exaustivo separa falhas transitórias (timeout/5xx simulado, circuit breaker aberto, bulkhead cheio, timeout do Mongo) de permanentes (destinatário inválido, template ausente, variável faltando). Exceção desconhecida vai para a DLQ
- [ ] **DLV-04**: Uma falha transitória com níveis restantes é republicada na fila de delay do próximo nível com o header `notify-retry-count` (sem depender de `x-death`), aguardando confirm antes do ack da original. O status vira RETRYING
- [ ] **DLV-05**: Quando os retries se esgotam, o status vira DEAD_LETTERED e a mensagem é rejeitada para a DLQ
- [ ] **DLV-06**: Uma falha permanente marca FAILED sem gastar retries e a mensagem é rejeitada para a DLQ com o header `x-failure-class: PERMANENT`

### Resiliência (RES)

- [ ] **RES-01**: Cada adapter de canal tem um circuit breaker próprio (Resilience4j 2.4) que registra só falhas transitórias, com janela e tempo de abertura ajustados para abrir visivelmente na demo
- [ ] **RES-02**: Cada adapter de canal tem um bulkhead por semáforo dimensionado em relação à concorrência do listener. Bulkhead cheio é tratado como falha transitória

### Admin (ADM)

- [ ] **ADM-01**: O operador pode chamar `POST /admin/dlq/{canal}/reprocess?max=N` para mover até N mensagens da DLQ de volta à fila principal. O fluxo é: `basicGet` com ack manual, reset no Mongo, republish confirmado, ack; para na primeira falha
- [ ] **ADM-02**: O reprocessamento zera o contador de tentativas, mantém o histórico `attempts[]` e registra um evento REPROCESSED
- [ ] **ADM-03**: O operador pode chamar `POST /admin/notifications/republish-failed` para reenviar notificações em PUBLISH_FAILED
- [ ] **ADM-04**: Os endpoints admin exigem o header `X-API-Key` (comparação em tempo constante) e validam o canal e o limite `max`

### Observabilidade (OBS)

- [ ] **OBS-01**: O worker publica contadores Micrometer de enviadas, falhas e retries por canal (tags de baixa cardinalidade, pré-registrados) e um timer de latência de envio
- [ ] **OBS-02**: Prometheus coleta métricas da API, do worker e do RabbitMQ (`rabbitmq_prometheus`)
- [ ] **OBS-03**: O Grafana sobe com datasource e dashboard provisionados: throughput, resultados, retries por nível, profundidade das DLQs, latência p95 e estado do circuit breaker

### Testes (TST)

- [ ] **TST-01**: Testes unitários do renderizador cobrem renderização, variável ausente, template inexistente e escape por canal
- [ ] **TST-02**: Um teste de integração com Testcontainers (RabbitMQ + MongoDB) cobre o fluxo retry → DLQ → reprocessamento → SENT, com TTLs curtos e Awaitility
- [ ] **TST-03**: Testes de integração cobrem mensagem venenosa (chega à DLQ sem loop), publish não roteável (vira PUBLISH_FAILED) e entrega duplicada concorrente (exatamente um envio)
- [ ] **TST-04**: Testes de integração rodam via Failsafe (`*IT`), separados dos unitários

### Demo e benchmark (DEM)

- [ ] **DEM-01**: API e worker têm Dockerfiles multi-stage e sobem junto com a infra num único `docker compose up --build`
- [ ] **DEM-02**: Um script k6 dispara 1000 notificações (mix de canais e destinatários mágicos) e imprime um resumo das métricas e dos status ao final
- [ ] **DEM-03**: Um benchmark compara virtual threads e threads de plataforma, alternando só o tipo de thread com concorrência alta e igual, latência bloqueante simulada, aquecimento e várias execuções. Os resultados vão para o README

### Documentação (DOC)

- [ ] **DOC-01**: O README em pt-BR traz visão geral, diagramas Mermaid (arquitetura, topologia, ciclo de vida do status) e passo a passo da demo
- [ ] **DOC-02**: O README justifica os índices compostos (regra ESR, com `explain()`) e o modelo de documentos
- [ ] **DOC-03**: O README documenta os trade-offs: sem outbox, at-least-once com possível duplicata após crash, Spring Boot 3.5 fora do suporte OSS e sem jitter
- [ ] **DOC-04**: Toda a documentação do projeto está em português do Brasil

## v2 Requirements

### Operação

- **OPS-01**: Correlation ID propagado em HTTP, AMQP e MDC dos logs
- **OPS-02**: Roteiro de demo "mate o worker" com graceful shutdown
- **OPS-03**: Visualização (peek) do conteúdo da DLQ via API
- **OPS-04**: Reprocessamento de uma notificação específica por ID

### Evolução

- **EVO-01**: Outbox pattern para eliminar a escrita dupla
- **EVO-02**: Migração para Spring Boot 4.x
- **EVO-03**: Jitter no backoff

## Out of Scope

| Feature | Reason |
|---------|--------|
| Integração real com provedores (SMTP, Twilio etc.) | O foco é a mensageria; adapters simulados bastam |
| UI / frontend | Portfólio backend |
| CRUD de templates via API | Seed no Mongo atende; mantém o escopo de ~1 semana |
| Plugin `rabbitmq_delayed_message_exchange` | O requisito é TTL + dead-letter nativo |
| Agendamento, preferências de usuário, fan-out, digests | Recursos de produto, fora do objetivo de demonstrar mensageria |
| Rate limiting, multi-tenancy, autenticação completa | Só API key simples nos endpoints admin |
| Endpoint de envio em lote, filas de prioridade | Aumentam o escopo sem reforçar o valor central |
| Tracing distribuído completo (OpenTelemetry) | Métricas atendem ao critério de pronto |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| FND-01 | Phase 1 | Complete |
| FND-02 | Phase 1 | Complete |
| FND-03 | Phase 1 | Complete |
| FND-04 | Phase 1 | Complete |
| MSG-01 | Phase 1 | Complete |
| MSG-02 | Phase 1 | Complete |
| MSG-03 | Phase 1 | Complete |
| MSG-04 | Phase 1 | Complete |
| MSG-05 | Phase 1 | Complete |
| API-01 | Phase 1 | Complete |
| API-02 | Phase 1 | Complete |
| API-03 | Phase 3 | Pending |
| API-04 | Phase 1 | Complete |
| API-05 | Phase 3 | Pending |
| API-06 | Phase 1 | Complete |
| API-07 | Phase 3 | Pending |
| API-08 | Phase 3 | Pending |
| WRK-01 | Phase 1 | Complete |
| WRK-02 | Phase 1 | Complete |
| WRK-03 | Phase 2 | Gaps Found |
| WRK-04 | Phase 1 | Complete |
| WRK-05 | Phase 1 | Complete |
| TPL-01 | Phase 1 | Complete |
| TPL-02 | Phase 2 | Gaps Found |
| TPL-03 | Phase 2 | Gaps Found |
| DLV-01 | Phase 1 | Complete |
| DLV-02 | Phase 2 | Gaps Found |
| DLV-03 | Phase 2 | Gaps Found |
| DLV-04 | Phase 2 | Gaps Found |
| DLV-05 | Phase 2 | Gaps Found |
| DLV-06 | Phase 2 | Gaps Found |
| RES-01 | Phase 2 | Gaps Found |
| RES-02 | Phase 2 | Gaps Found |
| ADM-01 | Phase 3 | Pending |
| ADM-02 | Phase 3 | Pending |
| ADM-03 | Phase 3 | Pending |
| ADM-04 | Phase 3 | Pending |
| OBS-01 | Phase 2 | Gaps Found |
| OBS-02 | Phase 4 | Pending |
| OBS-03 | Phase 4 | Pending |
| TST-01 | Phase 2 | Gaps Found |
| TST-02 | Phase 3 | Pending |
| TST-03 | Phase 3 | Pending |
| TST-04 | Phase 3 | Pending |
| DEM-01 | Phase 4 | Pending |
| DEM-02 | Phase 4 | Pending |
| DEM-03 | Phase 4 | Pending |
| DOC-01 | Phase 4 | Pending |
| DOC-02 | Phase 4 | Pending |
| DOC-03 | Phase 4 | Pending |
| DOC-04 | Phase 4 | Pending |

**Coverage:**

- v1 requirements: 51 total
- Mapped to phases: 51
- Unmapped: 0 ✓

---
*Requirements defined: 2026-10-09*
*Last updated: 2026-10-09 after roadmap creation (traceability mapped)*
