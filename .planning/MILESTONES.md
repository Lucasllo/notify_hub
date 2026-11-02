# Milestones

## v1 MVP (Shipped: 2026-11-02)

**Phases completed:** 4 phases, 30 plans, 52 tasks
**Timeline:** 24 dias (2026-10-09 → 2026-11-02), 255 commits (`effe753` → `7ae84d1`)
**Código:** ~6,2k linhas Java de produção + ~11,5k de testes (common, api, worker, it); 51/51 requisitos v1
**Closeout:** `override_closeout`

**Key accomplishments:**

- Entrega ponta a ponta sobre contrato compartilhado (`notify-common`) e topologia RabbitMQ completa num único `Declarables` (3 exchanges, 15 filas): API com publisher confirms + mandatory (503 + PUBLISH_FAILED sem confirm) e worker em virtual threads com claim condicional no MongoDB, sem envio duplicado
- Toda falha tem destino previsível: classificador transitória/permanente, escada de delay 5s/25s/125s com `notify-retry-count` e confirm antes do ack, DEAD_LETTERED/FAILED na DLQ com `x-failure-class`, circuit breaker e bulkhead de semáforo por canal, destinatários mágicos determinísticos e métricas Micrometer pré-registradas
- Ciclo operacional fechado: reprocessamento da DLQ (`basicGet` → reset com REPROCESSED → republish confirmado → ack) com `X-API-Key` em tempo constante, `republish-failed` e varredura agendada de PENDING sem confirm, `Idempotency-Key` com índice único parcial, listagem paginada, stats e Swagger UI
- Suíte de integração `notify-it` (Failsafe + Testcontainers com as tags do compose) provando retry → DLQ → reprocess → SENT, mensagem venenosa sem loop, publish não roteável e duplicata concorrente com exatamente um envio
- Demo com um único `docker compose up --build`: Prometheus coletando API, worker e RabbitMQ, Grafana 12.4 provisionado (5 rows, CB em state timeline) e k6 com 1000 notificações e resumo por status; clone limpo provado
- Benchmark VT x plataforma publicado com honestidade (empate de vazão 744.4 x 786.2 msg/s; 40 x 636 threads de plataforma em C=200) e README + docs em pt-BR com Mermaid, `explain()` e 12 trade-offs

**Known verification overrides:** 2 newly acknowledged, 0 carried forward from a prior close (see STATE.md Deferred Items). As 4 verificações de fase estavam `stale` no fechamento (digests desatualizados por mudanças posteriores de fases, quick tasks e do acknowledge; nenhuma falhou). O fechamento seguiu por decisão do usuário, com base na auditoria `milestones/v1-MILESTONE-AUDIT.md` (51/51 requisitos, 6/6 fluxos E2E).

**Tech debt carregado:** 31 itens de borda e documentação, nenhum bloqueante. Os principais são WR-08 da Fase 3 (500 sem `notificationId`), os exemplos `curl.exe` no PowerShell 5.1, a latência do replay (WR-06), o Javadoc de `RecipientRules` e os níveis de delay antigos no CLAUDE.md. A lista completa está em `milestones/v1-MILESTONE-AUDIT.md`.

---
