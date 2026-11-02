# Notify Hub

## What This Is

Notify Hub é um serviço de notificações multicanal (e-mail, SMS e webhook, todos simulados) construído sobre RabbitMQ e MongoDB. É um projeto de portfólio backend: o objetivo é demonstrar, de forma reproduzível, mensageria resiliente (retry com backoff exponencial e DLQ), modelagem NoSQL e virtual threads do Java 21. São dois módulos: `notify-api`, que recebe e consulta notificações e publica eventos, e `notify-worker`, que consome por canal, renderiza o template e "envia" a notificação por adapters simulados.

## Core Value

Uma notificação aceita pela API nunca se perde em silêncio. Ela é entregue (simuladamente), passa por retries com backoff exponencial ou termina na DLQ com histórico completo de tentativas, de onde pode ser reprocessada. Tudo isso fica observável por métricas e demonstrável com um único `docker compose up` e um script de carga.

## Requirements

### Validated

- ✓ `notify-api`: `POST /notifications` (canal, destinatário, template, variáveis) persiste a notificação como PENDING no MongoDB e publica na topic exchange com publisher confirms; se o confirm falhar, marca como PUBLISH_FAILED — Fase 1
- ✓ `notify-api`: `GET /notifications/{id}` retorna a notificação com o histórico de tentativas — Fase 1
- ✓ Topologia RabbitMQ declarada em código: topic exchange, uma fila por canal (email, sms, webhook), filas de delay com TTL fixo por nível (backoff exponencial de fator 5: 5s → 25s → 125s) usando dead-letter de volta à fila principal, e DLQ final por canal — Fase 1
- ✓ Idempotência no worker por `notification_id`, com índice único no MongoDB (redelivery não gera envio duplicado) — Fase 1
- ✓ Virtual threads habilitadas no worker (`spring.threads.virtual.enabled=true`) — Fase 1
- ✓ `notify-worker`: consome as filas por canal, renderiza o template Mustache, envia pelo adapter simulado e atualiza status e histórico — Fases 1–2
- ✓ Classificação de falhas: transitórias (timeout/5xx simulado, circuit breaker aberto, bulkhead cheio) passam por retry; permanentes (destinatário inválido, template ausente ou variável faltando) vão direto para FAILED/DLQ — Fase 2
- ✓ Adapters simulados por canal com taxa de falha aleatória e latência configuráveis — Fase 2
- ✓ Resilience4j: circuit breaker e bulkhead por adapter de canal — Fase 2
- ✓ Métricas Micrometer: contadores de enviadas, falhas e retries por canal — Fase 2
- ✓ Teste unitário do renderizador de templates — Fase 2
- ✓ `notify-api`: `GET /notifications?status=&channel=` paginado — Fase 3
- ✓ `notify-api`: `POST /admin/dlq/{canal}/reprocess?max=N` move mensagens da DLQ de volta à fila principal e zera as tentativas, protegido por API key em header — Fase 3
- ✓ Teste de integração com Testcontainers (RabbitMQ + MongoDB) cobrindo retry → DLQ → reprocessamento — Fase 3
- ✓ `notify-api`: `Idempotency-Key` no POST (replay, 422 em reuso com corpo diferente, corrida resolvida por índice único parcial) — Fase 3
- ✓ `notify-api`: `POST /admin/notifications/republish-failed` e varredura agendada de PENDING sem confirm → PUBLISH_FAILED — Fase 3
- ✓ `notify-api`: `GET /notifications/stats` e Swagger UI (springdoc 2.8.17) — Fase 3
- ✓ MongoDB: coleção `notifications` com histórico de tentativas embutido e coleção `templates` (Mustache, seed na inicialização); índices compostos justificados com `explain()` em docs/modelagem — Fases 1–4
- ✓ Benchmark virtual threads x threads de plataforma (k6 + JFR), publicado como empate de vazão com diferença em threads e memória — Fase 4
- ✓ Prometheus e Grafana no docker compose, com datasource e dashboard provisionados — Fase 4
- ✓ Demo reproduzível: `docker compose up --build` + k6 com 1000 notificações e resumo por status; clone limpo provado — Fase 4
- ✓ Toda a documentação (README, demo, arquitetura, trade-offs, modelagem, benchmark) em pt-BR, com diagramas Mermaid e screenshots — Fase 4

### Active

(nenhum: todos os requisitos da v1 foram entregues; os da próxima versão serão definidos em `/gsd-new-milestone`)

### Out of Scope

- Integração real com provedores (SMTP, Twilio, etc.): o foco é a mensageria; os adapters são simulados
- UI / frontend: projeto de portfólio backend
- Outbox pattern: a escrita dupla (Mongo + RabbitMQ) é tratada com publisher confirms + status PUBLISH_FAILED; a limitação fica documentada no README como evolução possível
- CRUD de templates via API: templates vêm de seed no MongoDB, para manter o escopo de ~1 semana
- Plugin `rabbitmq_delayed_message_exchange`: o requisito é TTL + dead-letter, nativo do RabbitMQ
- Autenticação e autorização completas: apenas API key simples nos endpoints admin

## Current State

**v1 MVP entregue em 2026-11-02** (4 fases, 30 planos, 51/51 requisitos). Arquivo em `.planning/milestones/v1-ROADMAP.md`, requisitos em `v1-REQUIREMENTS.md` e auditoria em `v1-MILESTONE-AUDIT.md`.

- Quatro módulos Maven (`notify-common`, `notify-api`, `notify-worker`, `notify-it`): ~6,2k linhas Java de produção e ~11,5k de testes, com unitários sem Docker e ITs Failsafe com Testcontainers
- Stack: Java 21, Spring Boot 3.5.16, Spring AMQP 3.2, Spring Data MongoDB 4.5, Resilience4j 2.4.0, Micrometer, RabbitMQ 4.3.6, MongoDB 8.0.32, Prometheus 3.13 LTS, Grafana 12.4, k6 2.3
- Demo: `docker compose up --build` + `k6/demo.js` (1000 notificações), com o dashboard "Notify Hub" provisionado
- Dívida conhecida (não bloqueante): WR-08 da Fase 3 (500 genérico sem `notificationId` com Mongo fora depois de publish sem confirm), latência do replay até ~35s (WR-06), exemplos `curl.exe` quebrados no PowerShell 5.1, Javadoc de `RecipientRules` desatualizado, verificações de fase com digest `stale` no fechamento

## Next Milestone Goals

Ainda não definidos; serão levantados em `/gsd-new-milestone`. Os candidatos já registrados em REQUIREMENTS v2 são:

- Operação: correlation ID em HTTP/AMQP/MDC (OPS-01), roteiro "mate o worker" com graceful shutdown (OPS-02), peek da DLQ (OPS-03) e reprocessamento por ID (OPS-04)
- Evolução: outbox transacional (EVO-01), migração para Spring Boot 4.x (EVO-02) e jitter no backoff (EVO-03)
- Limpeza da dívida técnica da auditoria v1

## Context

- Projeto de portfólio para demonstrar competência backend: mensageria, NoSQL, resiliência, concorrência moderna (Java 21) e observabilidade.
- Escopo pequeno/moderado, cerca de 1 semana de trabalho (a v1 levou 24 dias de calendário).
- Começou greenfield; a v1 está entregue e o projeto agora é brownfield.
- Quem avaliar o projeto deve conseguir rodar a demo sem configuração manual. A topologia aparece no painel do RabbitMQ (filas de retry por nível ficam visíveis) e as métricas aparecem no Grafana.
- Backoff com filas de delay fixas por nível foi escolhido por ser didático e por evitar o head-of-line blocking de TTL por mensagem numa fila única.

## Constraints

- **Tech stack**: Java 21, Spring Boot 3.x, Spring AMQP, Spring Data MongoDB, Resilience4j, Micrometer, Testcontainers, Docker Compose — definido pelo objetivo do portfólio
- **Build**: Maven multi-módulo (parent POM + `notify-common` + `notify-api` + `notify-worker`)
- **Templates**: Mustache (JMustache) guardados no MongoDB
- **Timeline**: ~1 semana — o escopo precisa caber nesse prazo
- **Idioma**: toda a documentação em português (pt-BR); código e identificadores podem seguir em inglês
- **Reprodutibilidade**: a demo precisa subir com `docker compose up` e um script, sem passos manuais

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Retry com filas de delay fixas por nível (TTL + DLX) | Didático, visível no painel e sem head-of-line blocking | ✓ Topologia entregue na Fase 1 (escada exercitada na Fase 2) |
| Templates Mustache no MongoDB com seed | Sintaxe conhecida, validação de variáveis, sem CRUD extra | ✓ Good — Fases 1–2 (variável faltando → MISSING_VARIABLE, falha permanente) |
| Maven multi-módulo com `notify-common` | Compartilha DTOs, eventos e constantes de topologia entre API e worker | ✓ Good — Fase 1 |
| API salva PENDING e publica com publisher confirms (sem outbox) | Simples para o prazo; limitação documentada | ✓ Good — Fase 1 (503 + PUBLISH_FAILED provados no smoke) |
| Reprocessamento da DLQ na API, por canal + limite, com API key | Controle operacional mínimo e seguro o suficiente para a demo | ✓ Good — Fase 3 (basic.get + ack só após confirm; SHA-256 + `isEqual`, fail closed) |
| Falhas transitórias vs permanentes | Retry só onde faz sentido; permanentes não gastam tentativas | ✓ Good — Fase 2 |
| Prometheus + Grafana no compose | Visual forte para a demo do portfólio | ✓ Good — Fase 4 (Grafana 12.4.12 provisionado, dashboard com 5 rows, screenshots no README) |
| Backoff de fator 5 (5s/25s/125s) com profile demo curto | Exponencial de verdade; demo e testes rápidos | ✓ Filas 5s/25s/125s e 1s/5s/25s no profile demo — Fase 1 |
| `Idempotency-Key` no POST na v1 | Torna seguro o retry do cliente após 503 | ✓ Good — Fase 3 (replay só responde 202 com confirm próprio ou evidência de claim) |
| Estados terminais FAILED (permanente) e DEAD_LETTERED (retries esgotados) | Clareza na demo e no histórico | ✓ Good — Fase 2 |
| Publish falho → 503 + PUBLISH_FAILED + republish admin | Fecha a lacuna da ausência de outbox | ✓ Good — Fase 3 (mais a varredura de PENDING sem confirm) |
| Spring Boot 3.5.16 (fim do suporte OSS em 06/2026) | Restrição 3.x; migração para 4.x registrada como evolução | ✓ Registrado em docs/trade-offs.md — Fase 4 |
| ITs com dois contextos Spring na mesma JVM (módulo `notify-it`) e Testcontainers com as tags do compose | Prova o fluxo ponta a ponta sem mocks; mesmo broker da demo | ✓ Good — Fase 3 |
| Benchmark VT x plataforma publicado como empate (744.4 x 786.2 msg/s, intervalos sobrepostos) | Só afirmar o que foi medido; o ganho real aparece em threads de plataforma e memória | ✓ Good — Fase 4 |
| Contador de tentativas pela aplicação (header + Mongo), sem `x-death` | `x-death` mudou de comportamento no RabbitMQ 3.13+/4.x | ✓ Header `notify-retry-count` validado por `RetryHeaders` — Fase 1 |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-11-02 after v1 milestone*
