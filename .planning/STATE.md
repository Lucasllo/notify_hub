---
gsd_state_version: "1.0"
current_phase: 03
current_phase_name: Reprocessamento, API operacional e testes de integração
status: executing
stopped_at: Completed 03-01-PLAN.md
last_updated: "2026-10-31T12:55:19.762Z"
last_activity: 2026-10-31
last_activity_desc: Phase 03 execution started
state_head: 5e87c87790c1cd691d5d016133b8df5e1fbdbf69
progress:
  total_phases: 4
  completed_phases: 2
  total_plans: 22
  completed_plans: 16
  percent: 50
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-10-13)

**Core value:** Uma notificação aceita pela API nunca se perde em silêncio: é entregue, passa por retries com backoff exponencial ou termina na DLQ com histórico completo, de onde pode ser reprocessada, tudo observável e demonstrável com `docker compose up` + script.
**Current focus:** Phase 03 — Reprocessamento, API operacional e testes de integração

## Current Position

Phase: 03 (Reprocessamento, API operacional e testes de integração) — EXECUTING
Plan: 2 of 7
Status: Ready to execute
Last activity: 2026-10-31 — Phase 03 execution started

Progress: [█████░░░░░] 50%

## Performance Metrics

**Velocity:**

- Total plans completed: 15
- Average duration: -
- Total execution time: 0 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01 | 7 | - | - |
| 02 | 8 | - | - |

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
| Phase 02 P04 | 10min | 2 tasks | 12 files |
| Phase 02 P05 | 12min | 2 tasks | 14 files |
| Phase 02 P06 | 10min | 2 tasks | 10 files |
| Phase 02 P07 | 15min | 2 tasks | 2 files |
| Phase 02 P08 | 11min | 3 tasks | 11 files |
| Phase 03 P01 | 17min | 2 tasks | 9 files |

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
- [Phase 02]: [02-04]: Destinatários mágicos com casamento exato (domínio/host fail.test, SMS +550000000 + 001/002/004/1N3 com 13 caracteres); falha lançada no SimulatedChannelAdapter depois da latência com SimulatedDeliveryException/PermanentDeliveryException(SIMULATED_PERMANENT)
- [Phase 02]: [02-04]: fail-N-then-ok conta AttemptContext.retryCount (reprocess zera, volta a falhar); fail-until-reprocess conta attemptNumber <= 4; flag notify.simulation.magic-recipients (padrão true) desliga o parser nos três adapters
- [Phase 02]: 02-05: Resilience4j programático atrás do ChannelGateway (bulkhead → CB → adapter), instâncias por registry.find e falha ao subir se faltar; tempo aberto validado ≤ 1º nível via getWaitIntervalFunctionInOpenState().apply(1)
- [Phase 02]: 02-05: CB registra só TransientDeliveryException e ignora o resto por NonTransientFailurePredicate; recusas de CB/bulkhead são TRANSIENT (CIRCUIT_OPEN/BULKHEAD_FULL) e consomem um nível
- [Phase 02]: 02-05: profile demo fixa wait-duration-in-open-state 500ms literal; nele NOTIFY_CB_OPEN_WAIT fica sombreado (benchmark da Fase 4 precisa sobrescrever a chave direto se rodar com demo)
- [Phase 02]: 02-06: DeliveryMetrics pré-registra as 42 séries do D-15 (15 outcomes, 9 retries, 12 timers com histograma 1ms-30s, 6 rejections); outcome do timer = AttemptOutcome em minúsculo via FailureClassifier
- [Phase 02]: 02-06: failed e deadLettered contam depois do fence e antes da cópia na DLQ (reject leva a original à DLQ do mesmo jeito); retrying só depois do confirm, com nível h+1 validado em 1..N
- [Phase 02]: 02-06: timer notify.delivery.duration por dentro de bulkhead e CB, em volta só do adapter; CallNotPermitted/BulkheadFull incrementam notify.delivery.rejections e são relançadas
- [Phase 02]: Smoke da Fase 2 (02-07): helpers compartilhados em scripts/smoke-lib.sh; DLQ lida inteira com ack_requeue_true (volumes nunca apagados); worker pronto só com health UP + 'Listeners iniciados'
- [Phase 02]: 02-08: RetryPolicy testa h >= N antes do +1; RetryHeaders sem teto (teto recriaria o CR-01), header 2147483647 termina DEAD_LETTERED com cópia TRANSIENT
- [Phase 02]: 02-08: WR-01 fechado com advanceRetryLevel (RETRYING h -> retryCount h+1, fence RETRYING + retryCount = h) entre o confirm e o ack; falha ou fence perdido no avanço só gera log, nunca DLQ; filtro do claim e markRetrying inalterados
- [Phase 02]: 02-08: CR-01 adiado para a Fase 3 (deferred-items.md); o reprocessamento precisa aceitar PENDING/RETRYING/PROCESSING com a mensagem na DLQ ou criar um sweeper de lease (WR-08)
- [Phase 03]: Harness de IT: API e worker reais na mesma JVM do Failsafe via SpringApplication.from(...).with(Containers.class), spring.config.location por args (URI file: do módulo + optional:classpath:/it/) e containers estáticos com @ImportTestcontainers/@ServiceConnection; funcionou sem fallback (A4/A5 confirmadas)
- [Phase 03]: Jars executáveis com classifier exec (notify-<app>-0.1.0-SNAPSHOT-exec.jar); o jar sem classifier é dependência do notify-it; Dockerfiles da Fase 4 devem copiar *-exec.jar

### Pending Todos

None yet.

### Blockers/Concerns

- [Phase 2]: Mudar argumentos de fila exige `docker compose down -v`; definir argumentos num único lugar em `notify-common`
- [Phase 2]: Fábricas de listener customizadas precisam passar pelo configurer do Boot, ou o executor de virtual threads se perde sem aviso
- [Phase 2]: Pesquisa necessária: dimensionamento de CB/bulkhead vs concorrência e escada de delay; `CallNotPermitted` consome nível?; comportamento DLX/`x-death` no RabbitMQ 4.3
- [Phase 2]: Decidir ack mode (AUTO com espera do confirm dentro do listener vs MANUAL) no discuss-phase
- [Phase 2]: Mensagem rejeitada para a DLQ deixa a notificação em PROCESSING (lacuna conhecida do smoke 01-06, fecha com FAILED/DEAD_LETTERED)
- [Phase 4]: Benchmark VT precisa de spike de desenho (alvo, varredura de concorrência, executor k6, JFR)

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 261020-tm3 | Corrigir WR-05/T-02-01 (lastError de REPUBLISH_FAILED com motivo enumerado e truncado), WR-09 (fence tolera RuntimeException) e WR-07 (destinatários mágicos só no profile demo) | 2026-10-28 | 07b5fb1 | [261020-tm3-corrigir-wr-05-t-02-01-lasterror-de-repu](./quick/261020-tm3-corrigir-wr-05-t-02-01-lasterror-de-repu/) |
| 261029-u95 | Fechar a Fase 2 no ROADMAP.md e no STATE.md (UAT 3/3 aprovado em 2026-10-21) | 2026-10-29 | 871d97f | [261029-u95-fechar-a-fase-2-no-roadmap-md-e-no-state](./quick/261029-u95-fechar-a-fase-2-no-roadmap-md-e-no-state/) |

## Deferred Items

Items acknowledged and deferred at milestone close, most recent first:

| Category | Item | Status | Deferred At | Milestone |
|----------|------|--------|-------------|-----------|
| *(none)* | | | | |

## Session Continuity

Last session: 2026-10-31T12:55:19.393Z
Stopped at: Completed 03-01-PLAN.md
Resume file: None
