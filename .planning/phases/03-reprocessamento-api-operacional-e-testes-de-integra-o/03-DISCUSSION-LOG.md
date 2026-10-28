# Fase 3: Reprocessamento, API operacional e testes de integração - Registro da discussão

> **Só trilha de auditoria.** Não usar como entrada para os agentes de planejamento, pesquisa ou execução.
> As decisões estão no CONTEXT.md. Este registro guarda as alternativas consideradas.

**Data:** 2026-10-28
**Fase:** 03-reprocessamento-api-operacional-e-testes-de-integra-o
**Áreas discutidas:** DLQ com doc não terminal, Idempotency-Key, Listagem e stats, Onde os ITs moram

---

## DLQ com doc não terminal

### Documento em PENDING/RETRYING/PROCESSING (lease vencido) com mensagem na DLQ
| Opção | Descrição | Escolhida |
|--------|-------------|----------|
| Reprocessar mesmo assim | Transições extras → PENDING só para o reprocess; sem sweeper | ✓ |
| Devolver ao fim da DLQ | Não trava a cabeça, mas o documento fica preso | |
| Sweeper de lease vencido | Job no worker; não cobre PENDING/RETRYING | |

### PROCESSING com lease válido / SENT / inexistente
| Opção | Descrição | Escolhida |
|--------|-------------|----------|
| Lease válido: fim da DLQ; SENT/inexistente: descartar | Nenhum interrompe o lote | ✓ |
| Parar o lote em qualquer um deles | Conservador, trava o canal | |

### Resposta do endpoint
| Opção | Descrição | Escolhida |
|--------|-------------|----------|
| 200 com resumo; 503 com resumo parcial | `{requested, reprocessed, skipped, discarded}` | ✓ |
| Sempre 200 com stoppedReason | Falha menos visível | |

### Padrão e teto do max
| Opção | Descrição | Escolhida |
|--------|-------------|----------|
| Padrão 100, teto 1000 | Esvazia uma rodada do k6 | ✓ |
| Padrão 10, teto 100 | Lotes pequenos | |
| Você decide | | |

---

## Idempotency-Key

| Pergunta | Opções | Escolha |
|----------|--------|---------|
| Armazenamento | Campo no documento + índice único / Coleção própria com TTL | Campo no documento + índice único |
| Mesmo payload | Hash do pedido canônico / Hash do corpo bruto | Hash do pedido canônico |
| Resposta do replay | Mesmo 202 + `Idempotent-Replayed` / 200 com a visão atual | Mesmo 202 + header de replay |
| Replay após 503 (PUBLISH_FAILED) | Tenta republicar / Só devolve o original | Tenta republicar |

---

## Listagem e stats

| Pergunta | Opções | Escolha |
|----------|--------|---------|
| Paginação | page/size com total / Cursor (keyset) | page/size com total |
| Tamanho | 20/100 com 400 / 20/100 limitando em silêncio | 20/100 com 400 |
| Item da lista | Resumo / Visão completa | Resumo |
| Stats | Totais + matriz canal×status / Lista plana | Totais + matriz |

---

## Onde os ITs moram

| Pergunta | Opções | Escolha |
|----------|--------|---------|
| Módulo | `notify-it` com os dois contextos / ITs por módulo / Worker como container | `notify-it` |
| TTLs | Curtos só de teste / Os do profile demo | Curtos só de teste |
| Profile | Profile próprio `it` / Reusar `demo` | Profile `it` |
| Containers | Um par por suíte + limpeza / Por classe | Um par por suíte |

---

## A critério do Claude

- API key (ENV, tempo constante, 401, fail closed), formato do `republish-failed`, formato do evento REPROCESSED, prova de "exatamente um envio", esquema de segurança no Swagger, correções de Javadoc pendentes.

## Ideias adiadas

Nenhuma.
