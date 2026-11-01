# Phase 4: Observabilidade, demo e documentação - Discussion Log

> **Apenas trilha de auditoria.** Não use como entrada para agentes de pesquisa, planejamento ou execução.
> As decisões estão no CONTEXT.md. Este log preserva as alternativas consideradas.

**Data:** 2026-11-01
**Fase:** 04-observabilidade-demo-e-documentação
**Áreas discutidas:** Dashboard Grafana, Estrutura do README e docs
**Áreas oferecidas e não escolhidas:** Desenho do benchmark VT e Script k6/roteiro da demo, que ficaram a critério do Claude

---

## Dashboard Grafana

| Pergunta | Opções | Escolha |
|----------|--------|---------|
| Quantos dashboards | Um só, em linhas / Dois dashboards / Você decide | Um só, em linhas ✓ |
| Métricas da API | HTTP + publish / Só HTTP do Actuator / Nenhuma da API | HTTP + publish ✓ |
| Painel do CB | Linha do tempo por canal / Stat com estado atual / Os dois | Linha do tempo por canal ✓ |
| Scrape e janela | Scrape 5s, 15min, refresh 5s / Scrape 15s padrão / Você decide | Scrape 5s, 15min, refresh 5s ✓ |
| Login | Anônimo Viewer + admin/admin / Login admin/admin | Anônimo Viewer + admin/admin ✓ |
| CB de 500ms com scrape 5s | Somar painel de recusas / Alongar o OPEN na demo / Os dois | Somar painel de recusas ✓ |

**Notas:** o tempo aberto de 500ms do profile demo (Fase 2) foi mantido. A visibilidade vem dos contadores de recusa.

---

## Estrutura do README e docs

| Pergunta | Opções | Escolha |
|----------|--------|---------|
| Organização | README vitrine + docs/ / README único e longo / Você decide | README vitrine + docs/ ✓ |
| explain() | Script + saída colada / Só saída colada / Só explicação | Script + saída colada ✓ |
| Público | Revisor técnico, topo para recrutador / Entrevista técnica (ADR) / Você decide | Revisor técnico, topo para recrutador ✓ |
| Imagens | Screenshots em docs/img / Só Mermaid | Screenshots em docs/img ✓ |
| Relação com .planning | docs/ independente / Linkar decisões do planning | docs/ independente ✓ |
| Trade-offs | Tabela + parágrafo por item / Lista simples | Tabela + parágrafo por item ✓ |

---

## A critério do Claude

- Desenho do benchmark (alvo, varredura, execuções, JFR, profile próprio sem demo)
- Script k6 (mix, executor, espera por estados terminais, resumo via /stats)
- Compose com profile demo por padrão e habilitação do rabbitmq_prometheus
- Layout dos painéis, PromQL e arquivos de provisionamento

## Ideias adiadas

Nenhuma.
