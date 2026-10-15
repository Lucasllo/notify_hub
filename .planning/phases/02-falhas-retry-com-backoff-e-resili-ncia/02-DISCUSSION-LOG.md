# Phase 2: Falhas, retry com backoff e resiliência - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-10-14
**Phase:** 02-falhas-retry-com-backoff-e-resiliência
**Areas discussed:** Destinatários mágicos, CB e bulkhead na escada, PROCESSING preso e ack, Histórico e métricas

---

## Destinatários mágicos

| Option | Description | Selected |
|--------|-------------|----------|
| Prefixo semântico + sufixo | `always-transient@fail.test`, `https://fail.test/...`, SMS `+550000000` + código | ✓ |
| Só domínio/host reservado + tabela | Mapa de destinatários em `application.yml` | |
| Você decide | | |

| Option | Description | Selected |
|--------|-------------|----------|
| Pelo retryCount da mensagem | Determinístico, sem estado; N ≥ 4 termina DEAD_LETTERED | ✓ |
| Pelo attemptCount do documento | Não zera no reprocess | |
| Contador em memória | Some ao reiniciar | |

| Option | Description | Selected |
|--------|-------------|----------|
| Mágico extra por attemptCount | `fail-until-reprocess` permite retry → DLQ → reprocess → SENT | ✓ |
| IT troca simulação | failure-rate mutável no teste | |
| Deixar para a Fase 3 | | |

| Option | Description | Selected |
|--------|-------------|----------|
| Dentro do adapter, sob o CB | Funciona como falha real, abre o CB | ✓ |
| Antes do CB | Curto-circuito no processor | |
| Você decide | | |

**User's choice:** todas as opções recomendadas.

---

## CB e bulkhead na escada

| Option | Description | Selected |
|--------|-------------|----------|
| Consome nível como qualquer transitória | Regra única | ✓ |
| Reenfileira no mesmo nível | Risco de loop | |
| Não consome, com teto próprio | Segundo contador | |

| Option | Description | Selected |
|--------|-------------|----------|
| Sensível e rápido de recuperar | COUNT_BASED 20, mín. 10, 50%, aberto ≤ 1º nível, 3 em half-open | ✓ |
| Conservador | Janela 100, 30s aberto | |
| Você decide (pesquisa) | | |

| Option | Description | Selected |
|--------|-------------|----------|
| Menor que a concorrência, sem espera | 8 vs 10, maxWait=0 | ✓ |
| Igual à concorrência, espera curta | | |
| Você decide (pesquisa) | | |

| Option | Description | Selected |
|--------|-------------|----------|
| Profile do benchmark afrouxa/desliga | Fase 2 garante configurabilidade | ✓ |
| Bulkhead sempre ativo | | |

**User's choice:** todas as opções recomendadas.

---

## PROCESSING preso e ack

| Option | Description | Selected |
|--------|-------------|----------|
| redelivered OU lease expirado | Fence por retryCount mantido | ✓ |
| Só lease expirado | | |
| Só redelivered | | |

| Option | Description | Selected |
|--------|-------------|----------|
| AUTO + confirm síncrono no listener | Mantém a Fase 1 | ✓ |
| MANUAL | | |

| Option | Description | Selected |
|--------|-------------|----------|
| DEAD_LETTERED com motivo | Grava RETRYING, publica, se falhar RETRYING→DEAD_LETTERED + reject | ✓ |
| Publica antes, grava depois | Corrida com TTL curto | |
| Você decide | | |

| Option | Description | Selected |
|--------|-------------|----------|
| Unitário do claim + roteiro no smoke | IT fica para a Fase 3 | ✓ |
| Só unitário | | |
| IT com Testcontainers já agora | | |

**User's choice:** todas as opções recomendadas.

---

## Histórico e métricas

| Option | Description | Selected |
|--------|-------------|----------|
| FAILED + classe UNKNOWN | Novo valor no enum e no header | ✓ |
| FAILED + PERMANENT | | |
| Tratar como transitória | | |

| Option | Description | Selected |
|--------|-------------|----------|
| Toda passagem gera entrada em attempts[] | Com código de erro estável | ✓ |
| Só quando o adapter foi chamado | | |

| Option | Description | Selected |
|--------|-------------|----------|
| Contador de resultado + retries por nível + timer | `notify.delivery.outcomes/retries/duration` | ✓ |
| Contadores separados por resultado | | |
| Incluir o motivo como tag | | |

| Option | Description | Selected |
|--------|-------------|----------|
| Parse do JSON do webhook, inválido = PERMANENT | | ✓ |
| Só escaper | | |

**User's choice:** todas as opções recomendadas.

---

## Claude's Discretion

- Dígitos exatos dos códigos mágicos de SMS
- API programática vs anotações do Resilience4j
- Desenho do classificador e pré-registro de métricas
- Correção do deferred item `MustacheAutoConfiguration`

## Deferred Ideas

- Profile de benchmark (Fase 4)
- IT de worker derrubado / entrega concorrente (Fase 3)
