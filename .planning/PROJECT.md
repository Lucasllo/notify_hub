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

### Active

- [ ] `notify-api`: `GET /notifications?status=&channel=` paginado
- [ ] `notify-api`: `POST /admin/dlq/{canal}/reprocess?max=N` move mensagens da DLQ de volta à fila principal e zera as tentativas, protegido por API key em header
- [ ] `notify-worker`: consome as filas por canal, renderiza o template Mustache, envia pelo adapter simulado e atualiza status e histórico
- [ ] Classificação de falhas: transitórias (timeout/5xx simulado, circuit breaker aberto, bulkhead cheio) passam por retry; permanentes (destinatário inválido, template ausente ou variável faltando) vão direto para FAILED/DLQ
- [ ] Adapters simulados por canal com taxa de falha aleatória e latência configuráveis
- [ ] MongoDB: coleção `notifications` com histórico de tentativas embutido e coleção `templates` (Mustache, seed na inicialização); índices compostos justificados no README
- [ ] Resilience4j: circuit breaker e bulkhead por adapter de canal
- [ ] Benchmark simples (k6 ou script) comparando virtual threads com threads de plataforma, com resultados no README
- [ ] Métricas Micrometer: contadores de enviadas, falhas e retries por canal
- [ ] Prometheus e Grafana no docker compose, com dashboard provisionado
- [ ] Teste unitário do renderizador de templates
- [ ] Teste de integração com Testcontainers (RabbitMQ + MongoDB) cobrindo retry → DLQ → reprocessamento
- [ ] Demo reproduzível: `docker compose up` + script que dispara 1000 notificações e mostra as métricas
- [ ] Toda a documentação (README e afins) em português do Brasil

### Out of Scope

- Integração real com provedores (SMTP, Twilio, etc.): o foco é a mensageria; os adapters são simulados
- UI / frontend: projeto de portfólio backend
- Outbox pattern: a escrita dupla (Mongo + RabbitMQ) é tratada com publisher confirms + status PUBLISH_FAILED; a limitação fica documentada no README como evolução possível
- CRUD de templates via API: templates vêm de seed no MongoDB, para manter o escopo de ~1 semana
- Plugin `rabbitmq_delayed_message_exchange`: o requisito é TTL + dead-letter, nativo do RabbitMQ
- Autenticação e autorização completas: apenas API key simples nos endpoints admin

## Context

- Projeto de portfólio para demonstrar competência backend: mensageria, NoSQL, resiliência, concorrência moderna (Java 21) e observabilidade.
- Escopo pequeno/moderado, cerca de 1 semana de trabalho.
- Greenfield: diretório vazio, sem código prévio.
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
| Templates Mustache no MongoDB com seed | Sintaxe conhecida, validação de variáveis, sem CRUD extra | — Pending |
| Maven multi-módulo com `notify-common` | Compartilha DTOs, eventos e constantes de topologia entre API e worker | ✓ Good — Fase 1 |
| API salva PENDING e publica com publisher confirms (sem outbox) | Simples para o prazo; limitação documentada | ✓ Good — Fase 1 (503 + PUBLISH_FAILED provados no smoke) |
| Reprocessamento da DLQ na API, por canal + limite, com API key | Controle operacional mínimo e seguro o suficiente para a demo | — Pending |
| Falhas transitórias vs permanentes | Retry só onde faz sentido; permanentes não gastam tentativas | — Pending |
| Prometheus + Grafana no compose | Visual forte para a demo do portfólio | — Pending |
| Backoff de fator 5 (5s/25s/125s) com profile demo curto | Exponencial de verdade; demo e testes rápidos | ✓ Filas 5s/25s/125s e 1s/5s/25s no profile demo — Fase 1 |
| `Idempotency-Key` no POST na v1 | Torna seguro o retry do cliente após 503 | — Pending |
| Estados terminais FAILED (permanente) e DEAD_LETTERED (retries esgotados) | Clareza na demo e no histórico | — Pending |
| Publish falho → 503 + PUBLISH_FAILED + republish admin | Fecha a lacuna da ausência de outbox | — Pending |
| Spring Boot 3.5.16 (fim do suporte OSS em 06/2026) | Restrição 3.x; migração para 4.x registrada como evolução | — Pending |
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
*Last updated: 2026-10-13 after Phase 1*
