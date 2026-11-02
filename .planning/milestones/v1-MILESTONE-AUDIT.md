---
milestone: v1
audited: 2026-11-02T00:00:00Z
status: tech_debt
scores:
  requirements: 51/51
  phases: 4/4
  integration: 5/5
  flows: 6/6
gaps:
  requirements: []
  integration: []
  flows: []
tech_debt:
  - phase: 01-entrega-ponta-a-ponta-no-caminho-feliz
    items:
      - "WR-02: createdAt do 202 em nanos difere do GET (Mongo em millis)"
      - "WR-03: TIMEOUT de confirm tratado como falha definitiva; possível duplicata após 503 (mitigado por Idempotency-Key)"
      - "WR-04: texto bruto de exceção em lastError, visível via GET"
      - "WR-05: erros de infraestrutura sem mapeamento no ApiExceptionHandler (PENDING órfão hoje recuperado pela varredura da Fase 3)"
      - "WR-06: claim do worker não filtra por canal (exige mensagem forjada)"
      - "Bookkeeping: deferred-items.md marca MustacheAutoConfiguration como open, mas o exclude já está em notify-worker/src/main/resources/application.yml:9"
  - phase: 02-falhas-retry-com-backoff-e-resili-ncia
    items:
      - "Javadoc de RecipientRules ainda cita o SMS mágico antigo +5500000000001 (deferred-items open)"
      - "WR-08: Javadoc do claim e comentário do yml sugerem recuperação por lease sem mensagem"
      - "IN-07: retryCount em RETRYING mostra h ou h+1 conforme o avanço do fence (não documentado)"
      - "IN-08: Javadoc de RetryHeaders promete DEAD_LETTERED para qualquer header acima da escada"
  - phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o
    items:
      - "WR-05: replay de PENDING confirmado e não reivindicado publica cópia extra (aceito em docs/trade-offs.md)"
      - "WR-06: latência do replay pode chegar a ~7 × confirm-timeout (~35s); Javadoc promete 2×; sem deadline total"
      - "WR-07: replay responde/loga PENDING com documento ainda PUBLISH_FAILED (aceito em docs/trade-offs.md)"
      - "WR-08: DataAccessException depois de publish sem confirm vira 500 genérico sem notificationId (não documentado nos trade-offs)"
      - "WR-01, WR-03, WR-04 (review anterior) abertos: DlqReprocessService, NotificationResetService, recipient exposto em NotificationSummary"
      - "IN-07: resetState() dos ITs espera só readyCount, não unackedCount (coincidental reliance)"
      - "IN-09: UnconfirmedSweepProperties.interval não lido nem validado"
      - "IN-10: Javadocs que afirmam mais do que o código garante"
      - "IN-11: ramos novos do replay sem teste (troca de updatedAt, interrupção, DataAccessException no reread)"
  - phase: 04-observabilidade-demo-e-documenta-o
    items:
      - "WR-04: exemplos curl.exe -d '{...}' em docs/demo.md quebram no Windows PowerShell 5.1"
      - "WR-01: semântica de publish_failed/unconfirmed em notify_publish_outcomes_total"
      - "WR-03: k6/demo.js pode declarar drenado cedo após rodada interrompida"
      - "IN-03: painel Taxa de sucesso mostra NaN num stack novo até o primeiro desfecho"
      - "IN-05: docs citam admin/admin para editar um dashboard provisionado read-only"
  - phase: integration
    items:
      - ".claude/CLAUDE.md (Stack Patterns) ainda cita níveis 5s,30s,2m; código e docs usam 5s/25s/125s (demo 1s/5s/25s)"
      - "Readiness depende de MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED no compose, não no application.yml"
---

# Auditoria do milestone v1: Notify Hub

**Status:** `tech_debt`. Todos os requisitos foram atendidos, nenhum bloqueio crítico, e a dívida técnica acumulada foi registrada.

## Escopo

- Milestone v1, 4 fases e 30 planos, todos executados (2026-10-09 a 2026-11-02)
- 51 requisitos v1 mapeados na rastreabilidade do REQUIREMENTS.md
- Nyquist inativo (sem hook `validate-phase` em `verify:post`), por isso não foi avaliado

## Fases

| Fase | VERIFICATION | Score | UAT | Observação |
|------|--------------|-------|-----|------------|
| 1. Entrega ponta a ponta no caminho feliz | passed | 5/5 SC, 19/19 req | 2/2 pass | Gap SC3/API-06 fechado pelo 01-07 |
| 2. Falhas, retry com backoff e resiliência | passed | 64/65 (1 override) | 3/3 pass | Override CR-01 adiado para a Fase 3 e resolvido lá (03-02/03-03) |
| 3. Reprocessamento, API operacional e ITs | passed | 80/83 | 4/4 pass | As 15 proibições judgment-tier foram resolvidas na UAT (teste 4) |
| 4. Observabilidade, demo e documentação | passed | 38/38 | 7/7 pass | G-04-1 (screenshots) fechado; demo.md:57/67 corrigidos no quick 261102-ez1 |

## Requisitos (cruzamento das 3 fontes)

Todos os 51 requisitos estão **satisfied**: VERIFICATION passed, listados em `requirements-completed` de algum SUMMARY e marcados `[x]` no REQUIREMENTS.md. Não há órfãos.

| Grupo | IDs | Fase | Status |
|-------|-----|------|--------|
| Fundação | FND-01..04 | 1 | satisfied |
| Topologia | MSG-01..05 | 1 | satisfied |
| API | API-01, 02, 04, 06 | 1 | satisfied |
| API | API-03, 05, 07, 08 | 3 | satisfied |
| Worker | WRK-01, 02, 04, 05 | 1 | satisfied |
| Worker | WRK-03 | 2 | satisfied |
| Templates | TPL-01 | 1 | satisfied |
| Templates | TPL-02, 03 | 2 | satisfied |
| Entrega | DLV-01 | 1 | satisfied |
| Entrega | DLV-02..06 | 2 | satisfied |
| Resiliência | RES-01, 02 | 2 | satisfied |
| Admin | ADM-01..04 | 3 | satisfied |
| Observabilidade | OBS-01 | 2 | satisfied |
| Observabilidade | OBS-02, 03 | 4 | satisfied |
| Testes | TST-01 | 2 | satisfied |
| Testes | TST-02..04 | 3 | satisfied |
| Demo | DEM-01..03 | 4 | satisfied |
| Docs | DOC-01..04 | 4 | satisfied |

## Integração entre fases

Análise estática do gsd-integration-checker. A evidência de runtime vem dos ITs da Fase 3 e da UAT da Fase 4.

| Conexão | Status | Requisitos |
|---------|--------|------------|
| Reset do reprocess / PUBLISH_FAILED → claim do worker (fence `retryCount <= h`, header 0) | CONNECTED | WRK-02, WRK-03, ADM-01..03 |
| Constantes de topologia e headers de `notify-common` usados por API e worker; `retry-levels` iguais nos dois módulos | CONNECTED | FND-02, MSG-01..05, DLV-04..06 |
| Nomes e labels das métricas ↔ queries do dashboard; alvos de scrape ↔ serviços do compose | CONNECTED | OBS-01..03 |
| Profile demo no compose ↔ TTLs curtos, destinatários mágicos, api-key; recipients do k6 ↔ `MagicRecipients` | CONNECTED | DEM-01, DEM-02, DLV-02, MSG-05 |
| Endpoints, headers e nomes de fila na documentação ↔ código | CONNECTED | DOC-01, DOC-02, DOC-04 |

Nenhuma rota órfã: as 8 rotas têm consumidor (k6, docs, ITs ou Swagger).

## Fluxos E2E

| Fluxo | Status |
|-------|--------|
| (a) POST → publish confirmado → claim → SENT → GET | COMPLETE |
| (b) Transitória → escada → DEAD_LETTERED → reprocess → SENT | COMPLETE |
| (c) Permanente → FAILED → DLQ com `x-failure-class: PERMANENT` | COMPLETE |
| (d) Publish sem confirm → PUBLISH_FAILED (503 ou varredura) → republish-failed → SENT | COMPLETE |
| (e) Replay idempotente (índice único parcial, 422) | COMPLETE |
| (f) compose up → k6 → painéis do Grafana | COMPLETE |

## Dívida técnica

São 31 itens em 4 fases, mais 2 de integração (detalhados no frontmatter). Nenhum deles invalida um requisito. Os mais relevantes para quem avalia o portfólio:

1. **WR-08 da Fase 3:** um publish sem confirm seguido de Mongo fora vira 500 genérico sem `notificationId`. É o único warning de comportamento da Fase 3 que não foi corrigido nem registrado em `docs/trade-offs.md`.
2. **WR-04 da Fase 4:** os exemplos `curl.exe` do roteiro da demo quebram no PowerShell 5.1, e o autor desenvolve no Windows.
3. **WR-06 da Fase 3:** um replay pode levar ~35s, e o Javadoc afirma um limite menor.
4. **Bookkeeping:** dois deferred-items têm status desatualizado (o do Mustache já está corrigido; o do Javadoc de RecipientRules continua aberto), e o CLAUDE.md cita níveis de delay antigos.

## Veredito

O milestone cumpre a definição de pronto: os 51 requisitos foram atendidos, os 5 critérios de cada fase foram verificados, a integração está ligada e os 6 fluxos E2E estão completos. A dívida é de borda e de documentação. Pode ser aceita e ir para o backlog, ou ser fechada num `/gsd-quick` antes do `/gsd-complete-milestone v1`.
