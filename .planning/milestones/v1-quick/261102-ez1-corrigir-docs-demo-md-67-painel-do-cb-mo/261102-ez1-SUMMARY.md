---
phase: quick-261102-ez1
plan: 01
subsystem: docs
tags: [docs, demo, grafana, circuit-breaker]
status: complete
requires: []
provides:
  - "docs/demo.md com a contagem de rows (5) e o escopo do painel do CB (3 canais) corretos"
affects: [docs/demo.md]
tech-stack:
  added: []
  patterns: []
key-files:
  created: []
  modified:
    - docs/demo.md
decisions:
  - "A frase 'o CB que estava meio aberto continua amarelo (Half-open)' ficou como estava (decisão do planejador): continua correta com vários CBs no painel"
metrics:
  duration: "~1min"
  completed: 2026-11-02
requirements: [DOC-01, OBS-03, DOC-04]
plan_head_before: d481b59e5f34a1f246156add5e173959ca1f0e8e
actuals:
  tokens: 1200
  tasks: 1
  commits: 1
---

# Quick 261102-ez1 Plano 01: Corrigir docs/demo.md (rows e painel do CB) Summary

O roteiro da demo agora diz que o dashboard tem cinco rows e que o painel "Circuit breaker por canal" mostra o CB de email, sms e webhook. As duas coisas batem com o JSON provisionado: 5 painéis do tipo `row`, e a query `max by (name)` sem filtro de `name`.

## Tarefas

| Task | Nome | Commit | Arquivos |
|------|------|--------|----------|
| 1 | Corrigir a contagem de rows (linha 57) e o escopo do painel do CB (linha 67) | 27b1a34 | docs/demo.md |

## O que mudou

- Linha 57: `O dashboard tem quatro rows.` virou `O dashboard tem cinco rows.`. A lista abaixo já nomeava as cinco rows.
- Linha 67: `mostra o estado do CB do webhook.` virou `mostra o estado do CB de cada canal (email, sms e webhook).`. O resto do parágrafo não mudou, byte a byte.
- Diff contra d481b59: 2 linhas inseridas, 2 removidas, só em docs/demo.md. O arquivo continua em LF no índice e na working tree (`i/lf w/lf`).

## Verificação

- O `<automated>` do plano imprimiu `QUICK 261102-ez1: OK`.
- `grep -n` encontra "cinco rows" na linha 57 e "de cada canal (email, sms e webhook)" na linha 67.

## Desvios do plano

Nenhum. O plano foi executado como escrito.

Observação de processo: a verificação de branch protegido do executor (`git.base-branch --is-protected main` → `true`) recomendaria parar antes de commitar. O commit foi feito em `main` porque o orquestrador do quick mandou executar na working tree principal, sem isolamento em worktree, como nos commits anteriores do projeto.

## Known Stubs

Nenhum.

## Self-Check: PASSED

- FOUND: docs/demo.md
- FOUND: commit 27b1a34
