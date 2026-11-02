# Project Retrospective

*Documento vivo, atualizado a cada milestone. As lições alimentam o planejamento seguinte.*

## Milestone: v1 — MVP

**Shipped:** 2026-11-02
**Phases:** 4 | **Plans:** 30 | **Sessions:** não medido

### What Was Built
- Entrega ponta a ponta com contrato compartilhado, topologia RabbitMQ completa em código e publisher confirms; worker em virtual threads com claim condicional no MongoDB
- Escada de retry por filas de delay (TTL + DLX), DLQ com `x-failure-class`, circuit breaker e bulkhead por canal, destinatários mágicos e métricas
- Reprocessamento seguro da DLQ, `republish-failed`, varredura de PENDING sem confirm, `Idempotency-Key`, listagem/stats, Swagger UI e suíte `notify-it` com Testcontainers
- Demo com um `docker compose up --build`, Prometheus + Grafana provisionados, k6 com 1000 notificações, benchmark VT x plataforma e docs em pt-BR

### What Worked
- Fatias verticais demonstráveis: cada fase terminou com um smoke contra infra real (`scripts/smoke-fase*.sh`), o que pegou defeitos que unitários não pegariam
- Declarar a topologia inteira (inclusive delay e DLQ) já na Fase 1 evitou 406 PRECONDITION_FAILED e retrabalho nos ITs da Fase 3
- Níveis de TTL configuráveis desde o início: ITs com TTLs em ms e demo com 1s/5s/25s, sem mudar código
- Code review + verificação goal-backward por fase encontraram problemas reais de correção (overflow em `RetryPolicy`, CR-01 de documento preso, 202 de replay sem evidência) antes do fechamento
- Publicar o benchmark como empate, em vez de forçar uma vitória das VTs, deixou a documentação mais crível

### What Was Inefficient
- 7 dos 30 planos foram gap closure (01-07, 02-08, 03-08/09/10 e as re-verificações), principalmente na Fase 3, onde garantir "todo 202 tem evidência" exigiu três rodadas
- O CR-01 foi adiado da Fase 2 para a Fase 3 e virou entrada obrigatória do planejamento; resolver no lugar teria evitado o override
- Os digests das verificações ficaram `stale` porque fases posteriores alteram arquivos cobertos pelas anteriores, e isso forçou um override no fechamento
- Bookkeeping manual repetido: REQUIREMENTS.md com "Gaps Found" depois de verificações aprovadas, deferred-items com status desatualizado, CLAUDE.md com níveis de delay antigos
- Os warnings de code review (WR-xx) se acumularam entre fases e terminaram como 31 itens de dívida

### Patterns Established
- Fence de estado no MongoDB (`status` + `retryCount`/`updatedAt`) em toda transição, com `$push` do histórico na mesma atualização
- Confirm do broker antes do ack (worker) e antes do 202 (API); sem confirm, o resultado é PUBLISH_FAILED recuperável
- Contadores Micrometer pré-registrados em 0 com tags de baixa cardinalidade, contados só depois do fence
- Smokes em bash com `docker compose up -d --wait` e `MSYS_NO_PATHCONV=1` no Git Bash do Windows
- Proibições judgment-tier resolvidas explicitamente na UAT, e não em silêncio

### Key Lessons
1. Em fluxos at-least-once, defina primeiro a regra de evidência ("o que prova que a mensagem chegou?") e só depois os caminhos de resposta. Na Fase 3, isso veio depois, e as rodadas de gap closure foram o custo
2. Não adie achados críticos de review para outra fase; o override cria dívida de planejamento e de verificação
3. Verificações com digest de arquivos precisam de re-fingerprint ao fim do milestone, ou o fechamento vira override por motivos puramente mecânicos
4. Feche os warnings de review com um `/gsd-quick` por fase, em vez de deixar acumular até a auditoria

### Cost Observations
- Model mix: não medido
- Sessions: não medido
- Notable: 255 commits em 24 dias; ~6,2k linhas de produção para ~11,5k de testes (relação ~1:1,9)

---

## Cross-Milestone Trends

### Process Evolution

| Milestone | Sessions | Phases | Key Change |
|-----------|----------|--------|------------|
| v1 | — | 4 | Baseline: fatias verticais com smoke por fase, code review + verificação goal-backward, UAT humana no fim de cada fase |

### Cumulative Quality

| Milestone | Tests | Coverage | Zero-Dep Additions |
|-----------|-------|----------|-------------------|
| v1 | 611 unitários (common 81, api 174, worker 356) + 35 ITs | não medido | — |

### Top Lessons (Verified Across Milestones)

1. (a validar no próximo milestone) Regra de evidência antes dos caminhos de resposta
2. (a validar no próximo milestone) Fechar achados críticos na própria fase
