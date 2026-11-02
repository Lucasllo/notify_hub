---
gsd_state_version: "1.0"
status: Awaiting next milestone
stopped_at: Phase 04 complete — all phases complete
last_updated: "2026-11-02T15:13:39.575Z"
last_activity: 2026-11-02
last_activity_desc: Milestone v1 completed and archived
state_head: 7ae84d100aab8f2595b11dbf3bb263a059972437
progress:
  total_phases: 4
  completed_phases: 4
  total_plans: 30
  completed_plans: 30
  percent: 100
current_phase: 04
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-11-02)

**Core value:** Uma notificação aceita pela API nunca se perde em silêncio: é entregue, passa por retries com backoff exponencial ou termina na DLQ com histórico completo, de onde pode ser reprocessada, tudo observável e demonstrável com `docker compose up` + script.
**Current focus:** Planejando o próximo milestone (v1 MVP entregue e arquivado em 2026-11-02)

## Current Position

Phase: Milestone v1 complete
Plan: —
Status: Awaiting next milestone
Last activity: 2026-11-02 — Milestone v1 completed and archived

## Performance Metrics

**Velocity:**

- Total plans completed: 30
- Average duration: -
- Total execution time: 0 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01 | 7 | - | - |
| 02 | 8 | - | - |
| 03 | 10 | - | - |
| 04 | 5 | - | - |

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
| Phase 03 P02 | 24min | 3 tasks | 15 files |
| Phase 03 P03 | 23min | 3 tasks | 9 files |
| Phase 03 P04 | 29min | 3 tasks | 13 files |
| Phase 03 P05 | 20min | 2 tasks | 11 files |
| Phase 03 P06 | 167min | 2 tasks | 9 files |
| Phase 03 P07 | 18min | 2 tasks | 8 files |
| Phase 03 P08 | 24min | 2 tasks | 8 files |
| Phase 03 P10 | 16min | 2 tasks | 7 files |
| Phase 03 P09 | 21min | 2 tasks | 5 files |
| Phase 04 P01 | 22 min | 3 tasks | 10 files |
| Phase 04 P02 | 6 min | 2 tasks | 8 files |
| Phase 04 P03 | 12min | 3 tasks | 8 files |
| Phase 04 P04 | 51min | 3 tasks | 5 files |
| Phase 04 P05 | 14min | 3 tasks | 7 files |

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
- [Phase 03]: 03-02: reprocessamento da DLQ por basicGet com ack manual e orçamento min(max, prontas); reset (findAndModify com fence status+retryCount lidos, evento REPROCESSED, attemptCount intocado) antes do republish confirmado; skipped copiado para o fim da DLQ, discarded para SENT/inexistente, 503 com motivo fechado na primeira falha
- [Phase 03]: 03-02: /admin/** com ApiKeyInterceptor (SHA-256 + MessageDigest.isEqual, fail closed com chave vazia); 400 de parâmetro único via ApiExceptionHandler.invalidRequest e handleTypeMismatch
- [Phase 03]: 03-03: reset deriva o status guardado por lease de reclaimableWhenStuck(); PROCESSING com lease vigente -> LeaseActive sem escrita (skipped no fim da DLQ); vencido ou nulo -> fence com $or leaseUntil < agora / nulo
- [Phase 03]: 03-03: tabela D-01 aceita PENDING/RETRYING/PROCESSING -> PENDING só para o reset do reprocess; CR-01 da Fase 2 resolvido e provado por ReprocessEdgeCasesIT
- [Phase 03]: 03-04: Idempotency-Key com idempotencyKey/requestHash no próprio documento e índice único parcial idempotencyKey_unique ($exists: true); DuplicateKeyException -> findOne pela chave -> replay (status atual, Idempotent-Replayed: true) ou 422 sem ecoar chave/id; sem chave ou sem documento relança
- [Phase 03]: 03-04: RequestHasher = SHA-256 hex do pedido canônico (TreeMap + ORDER_MAP_ENTRIES_BY_KEYS), constante de referência fixada no RequestHasherTest; Spring Data lê o record pelo construtor canônico sem @PersistenceCreator
- [Phase 03]: 03-04: Idempotency-Key fora de ^[\x21-\x7E]{1,255}$ dá 400 validation-error (campo Idempotency-Key) depois do binding do corpo; replay de PUBLISH_FAILED segue 202 com o status atual até o 03-05 (D-08)
- [Phase 03]: 03-05: criação, replay de PUBLISH_FAILED e POST /admin/notifications/republish-failed passam pelo NotificationRepublisher; republish faz PUBLISH_FAILED -> PENDING condicional antes do publish (fence perdido não publica) e lastError de PUBLISH_FAILED grava 'Publicação não confirmada pelo broker (<código>)', nunca o reply text
- [Phase 03]: 03-05: lote do republish-failed (createdAt asc, _id asc, limit max) para na primeira falha com 503 republish-interrupted {requested, republished, reason} (errorCode() ou MONGO_UNAVAILABLE); fence perdido não conta nem interrompe; replay de PUBLISH_FAILED responde 202 PENDING, 503 Retry-After ou o status atual relido
- [Phase 03]: 03-06: listagem lê a projeção direto como NotificationSummary (ler como NotificationDocument falha no int attemptCount); página com skip >= total sai vazia sem find, porque o Spring Data trunca skip para int
- [Phase 03]: 03-06: GET /notifications/stats por um único $group status+channel; EnumMap canal->status com 21 zeros na ordem dos enums (contrato D-12); linha desconhecida ignorada com WARN; índice status_createdAt também serve o republish-failed
- [Phase 03]: 03-07: Swagger UI do springdoc 2.8.17 aberto (/swagger-ui/index.html, /v3/api-docs); esquema adminApiKey (X-API-Key) só nas operações /admin/**, proteção real segue no interceptor; respostas reais por @ApiResponse com override-with-generic-response false e erros como application/problem+json
- [Phase 03]: 03-07: chave da demo demo-admin-key só no profile demo (${NOTIFY_ADMIN_API_KEY:demo-admin-key}); profile base vazio e fail closed
- [Phase 03]: 03-08: PENDING sem claim e sem confirm vira PUBLISH_FAILED (PUBLISH_UNCONFIRMED) por varredura agendada na API (updateMulti cercado por PENDING + updatedAt < corte + claimedAt nulo); corte padrão 2m, validado em >= 3x confirm-timeout no startup
- [Phase 03]: 03-10: update PENDING → PUBLISH_FAILED que casa 0 é relido; só wasClaimedByWorker (não nulo, fora de PENDING/PUBLISH_FAILED) segue como publicado, o resto vira 503/FAILED; DataAccessException na marcação ou releitura gera ERROR com id+código e é relançada; details dos 503 não afirmam o status gravado
- [Phase 03]: 03-09: todo 202 de replay com Idempotency-Key se apoia num confirm próprio do broker ou em wasClaimedByWorker; PENDING sozinho nunca basta
- [Phase 03]: 03-09: replay de PENDING com a janela vencida (updatedAt + 2x confirm-timeout) republica com fence status PENDING + updatedAt lido, só tocando updatedAt
- [Phase 03]: 03-09: dentro da janela, o replay espera (polling de 100ms) o documento sair de PENDING ou ter o updatedAt trocado; no fim da janela republica com fence (WR-02)
- [Phase 03]: 03-09: laço do replay limitado a 3 rodadas; esgotado, publica sem fence e responde pelo próprio confirm
- [Phase 04]: Imagens da API e do worker: build multi-stage com os 4 POMs, cache notify-m2 e só o exec.jar no runtime; usuário app (uid 999), java como PID 1
- [Phase 04]: Queries PromQL em scripts do Git Bash usam MSYS_NO_PATHCONV=1 (o seletor uri com barra vira caminho do Windows)
- [Phase 04]: Smokes com jars locais (Fases 1 e 2) param os containers api/worker e sobem só rabbitmq e mongo pelo compose
- [Phase 04]: 04-02: contrato notify.publish.outcomes{outcome,reason} fixado com 7 séries pré-registradas (confirmed/none|claimed, publish_failed/nack|returned|timeout|broker_unavailable|unconfirmed)
- [Phase 04]: 04-02: falha do MongoDB em publishOrMarkFailed não conta; a varredura conta o documento depois como publish_failed/unconfirmed. Reprocessamento da DLQ fica fora do contador
- [Phase 04]: 04-03: resumo do k6 mede não terminais como crescimento desde o setup() (volume local tem PROCESSING abandonados antigos)
- [Phase 04]: 04-03: dashboard Notify Hub escrito à mão, uid fixo, datasource uid prometheus em todo painel; Grafana 12.4.12 anônimo Viewer
- [Phase 04]: 04-04: benchmark publicado como empate de vazão (virtual 744.4 x plataforma 786.2 msg/s, intervalos sobrepostos); diferença em threads de plataforma (40 x 636 em C=200, 1536 em C=500) e memória
- [Phase 04]: 04-04: benchmark em projeto compose separado notify-bench com override só de ENV; carga concorrente de outro container declarada no docs/benchmark.md
- [Phase 04]: UAT 7/7 e reverificação passed (38/38); fix efa89d3 no painel do CB (color mode fixed/text)
- [Phase 04]: 04-05: clone limpo provado em projeto compose separado (notify-clean) com smoke-fase4 OK; volumes do notify-hub preservados
- [Phase 04]: 04-05: docs públicos em pt-BR (README, demo, arquitetura, trade-offs com 12 itens, modelagem com explain real); screenshots docs/img/*.png versionadas em 68cbb38 (G-04-1 fechado)

### Pending Todos

None yet.

### Blockers/Concerns

- [Phase 2]: Mudar argumentos de fila exige `docker compose down -v`; definir argumentos num único lugar em `notify-common`
- [Phase 3]: WR-05 (cópia extra na fila no replay de PENDING confirmado e ainda não reivindicado; o claim do worker descarta) e WR-07 (replay loga "PENDING" com o documento ainda PUBLISH_FAILED) aceitos na UAT; citar como limitação conhecida
- [Phase 4]: Warnings abertos da revisão de código: WR-01 (semântica de publish_failed/unconfirmed), WR-03 (resumo do k6 pode declarar "drenado" cedo), WR-04 (curl.exe -d no PowerShell 5.1), IN-03 (Taxa de sucesso NaN no stack novo), IN-05 (README cita admin/admin para editar, dashboard é só leitura)

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|

## Deferred Items

Items acknowledged and deferred at milestone close, most recent first:

| Category | Item | Status | Deferred At | Milestone |
|----------|------|--------|-------------|-----------|
| deferred_items | 01/deferred-items.md: MustacheAutoConfiguration ativado no worker pelo jmustache no classpath (exclude já aplicado em notify-worker application.yml) | acknowledged | 2026-11-02 | v1 |
| deferred_items | 02/deferred-items.md: Javadoc de RecipientRules cita o SMS mágico antigo +5500000000001 | acknowledged | 2026-11-02 | v1 |
| verification_override | Fases 1-4: VERIFICATION.md com covered_digest stale no fechamento (nenhuma falha; aceito pelo usuário) | override_closeout | 2026-11-02 | v1 |

## Session Continuity

Last session: 2026-11-02T13:50:00Z
Stopped at: Phase 04 complete — milestone v1 pronto para auditoria
Resume file: None

## Operator Next Steps

- Start the next milestone with /gsd-new-milestone
