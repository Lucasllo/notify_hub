# Phase 1: Entrega ponta a ponta no caminho feliz - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-10-10
**Phase:** 01-entrega-ponta-a-ponta-no-caminho-feliz
**Areas discussed:** Contrato da API, Templates do seed

---

## Contrato da API

### Corpo do POST /notifications
| Option | Description | Selected |
|--------|-------------|----------|
| Plano | Campos na raiz, canal em maiúsculo | ✓ |
| Canal minúsculo | Mesmo formato, canal igual à routing key | |
| Você decide | Claude escolhe | |

### Resposta 202
| Option | Description | Selected |
|--------|-------------|----------|
| Resumo mínimo | id, status PENDING, createdAt | ✓ |
| Corpo vazio | Só o Location | |
| Documento completo | Igual ao GET /{id} | |

### GET /notifications/{id}
| Option | Description | Selected |
|--------|-------------|----------|
| Completo + lastError | Inclui conteúdo renderizado e attempts[] | ✓ |
| Sem conteúdo renderizado | Menos espaço no Mongo | |
| Você decide | Claude define | |

### Timeout do confirm / Retry-After
| Option | Description | Selected |
|--------|-------------|----------|
| 5s / Retry-After 5 | Configurável | ✓ |
| 2s / Retry-After 10 | Falha mais rápido | |
| Você decide | Claude escolhe | |

**User's choice:** todas as recomendações.

---

## Templates do seed

### Catálogo
| Option | Description | Selected |
|--------|-------------|----------|
| 3 de e-commerce | welcome, order-shipped, password-reset × 3 canais | ✓ |
| Apenas 1 | order-shipped | |
| 3 + um parcial | Mais um template só com EMAIL | |

### Idioma
| Option | Description | Selected |
|--------|-------------|----------|
| pt-BR | Coerente com a documentação | ✓ |
| Inglês | Combina com o código | |

### Origem do seed
| Option | Description | Selected |
|--------|-------------|----------|
| JSON em resources do worker | Upsert por _id com versão | ✓ |
| Em código Java | Classe que monta os documentos | |

### Corpo do webhook
| Option | Description | Selected |
|--------|-------------|----------|
| Envelope de evento | event + notificationId + data | ✓ |
| JSON simples | Só os dados | |
| Você decide | Claude define | |

**User's choice:** todas as recomendações.

---

## Claude's Discretion

- Validação de destinatário por canal (área não selecionada para discussão)
- Defaults do adapter simulado, groupId/pacote, portas e escopo do compose da Fase 1 (área não selecionada)
- Campos exatos do Problem Detail; formato do UUID

## Deferred Ideas

Nenhuma.
