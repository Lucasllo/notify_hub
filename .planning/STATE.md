---
gsd_state_version: "1.0"
current_phase: 02
current_phase_name: Falhas, retry com backoff e resiliência
status: executing
stopped_at: Completed 02-03-PLAN.md
last_updated: "2026-10-15T23:11:22.863Z"
last_activity: 2026-10-15
last_activity_desc: Phase 02 execution started
state_head: e02e69f2d287814eab34af55f3e4345ec111cc69
progress:
  total_phases: 4
  completed_phases: 1
  total_plans: 14
  completed_plans: 10
  percent: 25
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-10-13)

**Core value:** Uma notificação aceita pela API nunca se perde em silêncio: é entregue, passa por retries com backoff exponencial ou termina na DLQ com histórico completo, de onde pode ser reprocessada, tudo observável e demonstrável com `docker compose up` + script.
**Current focus:** Phase 02 — Falhas, retry com backoff e resiliência

## Current Position

Phase: 02 (Falhas, retry com backoff e resiliência) — EXECUTING
Plan: 3 of 7
Status: Ready to execute
Last activity: 2026-10-15 — Phase 02 execution started

Progress: [███░░░░░░░] 25%

## Performance Metrics

**Velocity:**

- Total plans completed: 7
- Average duration: -
- Total execution time: 0 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01 | 7 | - | - |

**Recent Trend:**

- Last 5 plans: -
- Trend: -

*Updated after each plan completion*
**Per-Plan Metrics:**

| Plan | Duration | Tasks | Files |
|------|----------|-------|-------|
| Phase 01 P02 | 6min | 2 tasks | 12 files |
| Phase 01 P03 | 7min | 3 tasks | 18 files |
| Phase 01 P04 | 11min | 3 tasks | 20 files |
| Phase 01 P05 | 14min | 3 tasks | 16 files |
| Phase 01 P06 | 10min | 2 tasks | 1 files |
| Phase 01 P07 | 7min | 2 tasks | 3 files |
| Phase 02 P02 | 15min | 3 tasks | 14 files |
| Phase 02 P03 | 5min | 2 tasks | 4 files |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [Roadmap]: 4 fatias verticais (modo MVP); topologia completa e TTLs configuráveis já na Fase 1 para evitar 406 PRECONDITION_FAILED e destravar os ITs
- [Roadmap]: Publisher confirms + PUBLISH_FAILED/503 entram na Fase 1 (base do "aceita = nunca perdida"); republish admin e IT de publish não roteável ficam na Fase 3
- [Roadmap]: Destinatários mágicos e contadores Micrometer nos pontos de resultado entram na Fase 2, junto com classificador, retry e resiliência
- [Projeto]: Backoff fator 5 (5s/25s/125s), profile `demo` 1s/5s/25s; contador de tentativas pela aplicação, sem `x-death`
- [Projeto]: Estados terminais FAILED (permanente) e DEAD_LETTERED (retries esgotados)
- [Phase 01]: 01-02: MessageConverter injeta o ObjectMapper do Boot sem fallback; spring-web só em escopo de teste no notify-common
- [Phase 01]: 01-02: TopologyProperties exige níveis entre 1ms e Integer.MAX_VALUE ms, estritamente crescentes e com rótulos distintos
- [Phase 01]: 01-03: TemplateSeeder expõe seed(List) além de seed(); findAndReplace que casa 0 documentos conta como unchanged (outro processo mudou a versão)
- [Phase 01]: 01-03: loadSeed() falha rápido com seed sem id/variantes ou ilegível; worker não sobe com seed quebrado
- [Phase 01]: 01-04: PublishResult usa acked() como fábrica de sucesso; ok() fica só como boolean de instância (conflito de assinatura em Java)
- [Phase 01]: 01-04: 503 de publish cita só id e errorCode; motivo do nack/devolução fica em lastError do documento
- [Phase 01]: 01-04: validador por canal instanciado pelo controller via @InitBinder (não é bean); >50 variáveis gera um único erro
- [Phase 01]: 01-05: header notify-retry-count recebido como String no @RabbitListener; com Object o Spring AMQP entrega a Message AMQP inteira
- [Phase 01]: 01-05: DeliveryProcessor falha ao subir sem um adapter por canal; mensagem sem notificationId vai para a DLQ
- [Phase 01]: 01-05: VT + SMLC com concorrência 10 > 8 CPUs sobe sem Consumer failed to start (A3 confirmada no smoke real)
- [Phase 01]: 01-06: smoke de fase em bash portável para Git Bash (curl/grep, sem jq); porta livre = curl exit 7 com timeout 10s, porque no Windows a recusa leva ~2s
- [Phase 01]: 01-06: binding removido no cenário 503 é restaurado no fluxo e no trap de saída; apps encerradas antes da linha final SMOKE FASE 1: OK
- [Phase 01]: 01-07: canal numérico rejeitado só pela flag spring.jackson.deserialization.fail-on-numbers-for-enums; ApiExceptionHandler/CreateNotificationRequest/Channel inalterados
- [Phase 01]: 01-07: teste de canal numérico simula broker que confirma, para o RED falhar por asserção (202) e não por NPE do mock
- [Phase 02]: 02-02: markRetrying/markDeadLettered nunca gravam retryCount; quem grava h+1 é o claim do próximo token (Armadilha 2)
- [Phase 02]: 02-02: claim com fence retryCount <= h fora de um $or derivado da tabela (claimableForDelivery + reclaimableWhenStuck com redelivered ou leaseUntil < agora)
- [Phase 02]: 02-02: republish falho vai de RETRYING a DEAD_LETTERED só com lastError REPUBLISH_FAILED e cópia TRANSIENT na DLQ; Armadilha 12 aceita e documentada
- [Phase 02]: [02-03]: Webhook escapa valores com JsonValueEscaper (JsonStringEncoder) num compilador JMustache próprio e valida o corpo com ObjectReader FAIL_ON_TRAILING_TOKENS + isObject → INVALID_WEBHOOK_PAYLOAD sem causa anexada
- [Phase 02]: [02-03]: requiredVariables checado depois de resolver a variante e antes de compilar (ausente/nula → MISSING_VARIABLE com só o nome; string vazia válida); JMustache sem defaultValue continua como segunda rede

### Pending Todos

None yet.

### Blockers/Concerns

- [Phase 2]: Mudar argumentos de fila exige `docker compose down -v`; definir argumentos num único lugar em `notify-common`
- [Phase 2]: Fábricas de listener customizadas precisam passar pelo configurer do Boot, ou o executor de virtual threads se perde sem aviso
- [Phase 2]: Pesquisa necessária: dimensionamento de CB/bulkhead vs concorrência e escada de delay; `CallNotPermitted` consome nível?; comportamento DLX/`x-death` no RabbitMQ 4.3
- [Phase 2]: Decidir ack mode (AUTO com espera do confirm dentro do listener vs MANUAL) no discuss-phase
- [Phase 2]: Mensagem rejeitada para a DLQ deixa a notificação em PROCESSING (lacuna conhecida do smoke 01-06, fecha com FAILED/DEAD_LETTERED)
- [Phase 4]: Benchmark VT precisa de spike de desenho (alvo, varredura de concorrência, executor k6, JFR)

## Deferred Items

Items acknowledged and deferred at milestone close, most recent first:

| Category | Item | Status | Deferred At | Milestone |
|----------|------|--------|-------------|-----------|
| *(none)* | | | | |

## Session Continuity

Last session: 2026-10-15T23:11:22.765Z
Stopped at: Completed 02-03-PLAN.md
Resume file: None
