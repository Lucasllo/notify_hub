---
gsd_state_version: "1.0"
current_phase: 01
current_phase_name: Entrega ponta a ponta no caminho feliz
status: executing
stopped_at: Phase 1 context gathered
last_updated: "2026-10-12T18:03:20.935Z"
last_activity: 2026-10-12
last_activity_desc: Phase 01 execution started
state_head: e071d3a59505e6a09150c277d9de92618e58b68a
progress:
  total_phases: 4
  completed_phases: 0
  total_plans: 6
  completed_plans: 0
  percent: 0
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-10-09)

**Core value:** Uma notificação aceita pela API nunca se perde em silêncio: é entregue, passa por retries com backoff exponencial ou termina na DLQ com histórico completo, de onde pode ser reprocessada, tudo observável e demonstrável com `docker compose up` + script.
**Current focus:** Phase 01 — Entrega ponta a ponta no caminho feliz

## Current Position

Phase: 01 (Entrega ponta a ponta no caminho feliz) — EXECUTING
Plan: 1 of 6
Status: Executing Phase 01
Last activity: 2026-10-12 — Phase 01 execution started

Progress: [░░░░░░░░░░] 0%

## Performance Metrics

**Velocity:**

- Total plans completed: 0
- Average duration: -
- Total execution time: 0 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| - | - | - | - |

**Recent Trend:**

- Last 5 plans: -
- Trend: -

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [Roadmap]: 4 fatias verticais (modo MVP); topologia completa e TTLs configuráveis já na Fase 1 para evitar 406 PRECONDITION_FAILED e destravar os ITs
- [Roadmap]: Publisher confirms + PUBLISH_FAILED/503 entram na Fase 1 (base do "aceita = nunca perdida"); republish admin e IT de publish não roteável ficam na Fase 3
- [Roadmap]: Destinatários mágicos e contadores Micrometer nos pontos de resultado entram na Fase 2, junto com classificador, retry e resiliência
- [Projeto]: Backoff fator 5 (5s/25s/125s), profile `demo` 1s/5s/25s; contador de tentativas pela aplicação, sem `x-death`
- [Projeto]: Estados terminais FAILED (permanente) e DEAD_LETTERED (retries esgotados)

### Pending Todos

None yet.

### Blockers/Concerns

- [Phase 1]: Fase mais densa (19 requisitos, esqueleto + fundação); o plan-phase deve dividi-la em vários planos
- [Phase 1]: Mudar argumentos de fila exige `docker compose down -v`; definir argumentos num único lugar em `notify-common`
- [Phase 1]: Fábricas de listener customizadas precisam passar pelo configurer do Boot, ou o executor de virtual threads se perde sem aviso
- [Phase 2]: Pesquisa necessária: dimensionamento de CB/bulkhead vs concorrência e escada de delay; `CallNotPermitted` consome nível?; comportamento DLX/`x-death` no RabbitMQ 4.3
- [Phase 2]: Decidir ack mode (AUTO com espera do confirm dentro do listener vs MANUAL) no discuss-phase
- [Phase 4]: Benchmark VT precisa de spike de desenho (alvo, varredura de concorrência, executor k6, JFR)

## Deferred Items

Items acknowledged and deferred at milestone close, most recent first:

| Category | Item | Status | Deferred At | Milestone |
|----------|------|--------|-------------|-----------|
| *(none)* | | | | |

## Session Continuity

Last session: 2026-10-10T13:10:36.058Z
Stopped at: Phase 1 context gathered
Resume file: .planning/phases/01-entrega-ponta-a-ponta-no-caminho-feliz/01-CONTEXT.md
