---
phase: "02"
slug: "falhas-retry-com-backoff-e-resili-ncia"
status: draft
# threats_open = count of OPEN threats at or above workflow.security_block_on severity (the blocking gate)
threats_open: 1
asvs_level: 1
created: "2026-10-21"
---

# Fase 02 — Segurança

> Contrato de segurança da fase: registro de ameaças, riscos aceitos e trilha de auditoria.

---

## Fronteiras de confiança

| Fronteira | Descrição | Dados que cruzam |
|-----------|-----------|------------------|
| broker → worker | Mensagens da fila principal e das filas de delay (publicáveis pelo painel), com `notify-retry-count` e `redelivered` | Token da notificação + headers (não confiáveis) |
| worker → broker | Republicação nas filas de delay e cópias na DLQ com `x-failure-class` | Token + headers próprios |
| worker → MongoDB | Claim, resultados (SENT/RETRYING/DEAD_LETTERED/FAILED), `lastError`, `attempts[]`, avanço do fence | Estado e histórico da notificação (destinatário = PII) |
| cliente → template | Variáveis do `POST /notifications` entram como dado no JMustache | Texto livre do cliente (não confiável) |
| worker → destinatário simulado | Corpo renderizado (HTML do e-mail, JSON do webhook) | Conteúdo da notificação |
| cliente → worker (destinatário) | O destinatário decide o roteiro mágico de falhas | Endereço (PII) |
| Prometheus → worker | Scrape não autenticado de `/actuator/prometheus` (8081) | Contagens/latências agregadas |
| build → Maven Central | Resilience4j 2.4.0 e micrometer-registry-prometheus | Dependências |
| script → processos locais / painel RabbitMQ | Smoke inicia/encerra processos e lê a DLQ com credenciais de demo | pids, credenciais de demo |

---

## Registro de ameaças

| Threat ID | Category | Component | Severity | Disposition | Mitigation | Status |
|-----------|----------|-----------|----------|-------------|------------|--------|
| T-02-01 | Information Disclosure | cópia na DLQ, `lastError`, `attempts[].errorMessage`, logs | high | mitigate | Classificador com mensagens próprias truncadas em 300 e `RecipientRules.mask` — **mas o caminho REPUBLISH_FAILED (`DeliveryProcessor.onRepublishFailure` + `DeliveryRoutingPublisher`) grava `AmqpException.getMessage()`/causa do `ExecutionException` sem truncar (02-REVIEW WR-05)** | open |
| T-02-02 | Denial of Service | mensagem venenosa em loop | high | mitigate | Confirma original após cópia na DLQ; fallback `AmqpRejectAndDontRequeueException`; `default-requeue-rejected: false` | closed |
| T-02-03 | Tampering | confirm associado à publicação errada | medium | mitigate | `new CorrelationData()` por publicação em `DeliveryRoutingPublisher`; `getReturned()` antes de `isAck()` | closed |
| T-02-04 | Denial of Service | listener bloqueado esperando confirm | medium | mitigate | `notify.worker.confirm-timeout` (5s) | closed |
| T-02-05 | Repudiation | FAILED sem mensagem na DLQ | medium | mitigate | Reject sem requeue estaciona a original via DLX; fence perdido não publica | closed |
| T-02-06 | Spoofing | `x-failure-class` forjado | low | accept | Worker nunca lê o header para decidir; painel exige credenciais | closed |
| T-02-07 | Denial of Service | retry storm | medium | mitigate | Escada finita em `RetryPolicy`, publish com confirm/timeout, DEAD_LETTERED terminal | closed |
| T-02-08 | Repudiation | RETRYING sem mensagem | high | mitigate | `markRetrying` sem `retryCount`; publish falho → DEAD_LETTERED + DLQ | closed |
| T-02-09 | Tampering | `notify-retry-count` adulterado | low | mitigate | `RetryHeaders.parse` rejeita negativo/não numérico; h ≥ N → DEAD_LETTERED | closed |
| T-02-10 | Tampering | reentrega dupla de worker vivo | medium | accept | At-least-once documentado; fence status+retryCount | closed |
| T-02-11 | Repudiation | DEAD_LETTERED sem DLQ (falha dupla) | low | accept | Visível no GET (`REPUBLISH_FAILED`) | closed |
| T-02-12 | Elevation of Privilege | reclaim de PROCESSING ativo | low | mitigate | Lease de 5m; reclaim sem redelivered exige `leaseUntil < agora` | closed |
| T-02-13 | Tampering | injeção de JSON no webhook | high | mitigate | `JsonValueEscaper` + parse estrito `FAIL_ON_TRAILING_TOKENS` em `TemplateRenderer` | closed |
| T-02-14 | Tampering | injeção de HTML no e-mail | high | mitigate | Escaper HTML padrão do JMustache; `TemplateRendererTest` | closed |
| T-02-15 | Elevation of Privilege | SSTI / bypass do escaper | high | mitigate | Só templates do seed; `SeedTemplatesTest` proíbe `{{{` e `{{&` | closed |
| T-02-16 | Information Disclosure | mensagem de erro de variável/JSON | medium | mitigate | Mensagens citam só nome da variável/código | closed |
| T-02-17 | Tampering | destinatário real tratado como mágico | medium | mitigate | Casamento exato `fail.test` (TLD reservado) em `MagicRecipients`; tabela de parecidos no teste | closed |
| T-02-18 | Elevation of Privilege | recurso de demo ativo fora da demo | low | mitigate | Interruptor `notify.simulation.magic-recipients` existe — default ainda `true` em `application.yml:71` (02-REVIEW WR-07, recomendação de correção) | closed |
| T-02-19 | Information Disclosure | destinatário em logs/erros | medium | mitigate | Exceções mágicas não citam destinatário; logs com `RecipientRules.mask` | closed |
| T-02-20 | Denial of Service | provedor lento derruba consumidores | high | mitigate | Bulkhead por semáforo (espera 0) + CB por canal (`ChannelGateway`) | closed |
| T-02-21 | Denial of Service | CB com defaults | medium | mitigate | `registry.find(...)` falha ao subir; `ResilienceSettingsTest` | closed |
| T-02-22 | Tampering | falha do cliente abrindo o CB | medium | mitigate | `record-exceptions` só transitória; `NonTransientFailurePredicate` no ignore | closed |
| T-02-23 | Denial of Service | worker unhealthy com CB aberto | low | mitigate | Health indicator do Resilience4j desligado; teste confere | closed |
| T-02-SC | Tampering | dependências Maven novas | high | mitigate | `resilience4j-bom` 2.4.0 pinado no POM pai; nenhuma versão do Boot sobrescrita; auditoria manual em 02-RESEARCH | closed |
| T-02-24 | Denial of Service | cardinalidade no Prometheus | medium | mitigate | Tags de conjunto fechado pré-registradas em `DeliveryMetrics`; teste conta séries | closed |
| T-02-25 | Information Disclosure | `/actuator/prometheus` sem auth | low | accept | Só agregados sem PII; porta local | closed |
| T-02-26 | Tampering | métrica enganosa | low | mitigate | Timer dentro de CB/bulkhead; contadores após fence/confirm | closed |
| T-02-27 | Denial of Service | encerramento de processo errado | low | mitigate | Smoke mata só pids próprios via `/proc/<pid>/winpid` (`smoke-lib.sh`/`smoke-fase1.sh`) | closed |
| T-02-28 | Information Disclosure | credenciais `notify`/`notify` no script | low | accept | Credenciais de demo locais | closed |
| T-02-29 | Tampering | DLQ alterada pela inspeção | low | mitigate | `ackmode: ack_requeue_true` | closed |
| T-02-30 | Tampering | `notify-retry-count` = 2147483647 → overflow | high | mitigate | `retryCount >= levels.size()` antes do +1 em `RetryPolicy`; testes de fronteira e de fluxo (02-08) | closed |
| T-02-31 | Tampering / DoS | cópia de nível h reivindica RETRYING após token h+1 | medium | mitigate | `advanceRetryLevel` com fence RETRYING + `retryCount = h` (02-08) | closed |
| T-02-32 | Repudiation | falha do Mongo no avanço manda original à DLQ | medium | mitigate | `advanceRetryFence` registra e ignora `DataAccessException`/fence perdido (outras `RuntimeException` ainda escapam — 02-REVIEW WR-09) | closed |
| T-02-33 | Tampering | header com h enorme encurta a escada | low | accept | Exige credencial no broker; resultado terminal visível e reprocessável | closed |
| T-02-34 | Repudiation | janela entre confirm e avanço | low | accept | At-least-once documentado; sem SENT duplo nem perda | closed |
| T-02-35 | Denial of Service | CR-01: documento não terminal com mensagem na DLQ | medium | transfer | Transferido para a Fase 3 (ADM-01) via `deferred-items.md` (open, target Phase 3) | closed |

*Status: open · closed · open — below high threshold (non-blocking)*
*Severity: critical > high > medium > low — only open threats at or above workflow.security_block_on count toward threats_open*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-02-01 | T-02-06 | Worker não usa `x-failure-class` para decidir; publicar exige credencial do broker | plano 02-01 | 2026-10-15 |
| AR-02-02 | T-02-10 | At-least-once é a semântica documentada do sistema | plano 02-02 | 2026-10-15 |
| AR-02-03 | T-02-11 | Janela de falha dupla; estado visível no GET | plano 02-02 | 2026-10-15 |
| AR-02-04 | T-02-25 | Métricas agregadas sem PII, porta local | plano 02-06 | 2026-10-15 |
| AR-02-05 | T-02-28 | Credenciais de demo locais, já no compose | plano 02-07 | 2026-10-15 |
| AR-02-06 | T-02-33 | Exige credencial de publicação; estado terminal reprocessável | plano 02-08 | 2026-10-20 |
| AR-02-07 | T-02-34 | At-least-once documentado | plano 02-08 | 2026-10-20 |

*Accepted risks do not resurface in future audit runs.*

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-10-21 | 36 | 35 | 1 (T-02-01, high) | orquestrador /gsd-secure-phase (verificação L1 por grep + 02-REVIEW) |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [ ] `threats_open: 0` confirmed
- [ ] `status: verified` set in frontmatter

**Approval:** pending — T-02-01 aguardando correção (usuário escolheu corrigir agora)
