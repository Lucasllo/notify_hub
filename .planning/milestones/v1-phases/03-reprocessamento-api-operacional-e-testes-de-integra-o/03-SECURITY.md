---
phase: "03"
slug: "reprocessamento-api-operacional-e-testes-de-integra-o"
status: verified
# threats_open = count of OPEN threats at or above workflow.security_block_on severity (the blocking gate)
threats_open: 0
asvs_level: 1
created: "2026-11-01"
---

# Fase 03 — Segurança

> Contrato de segurança da fase: registro de ameaças, riscos aceitos e trilha de auditoria.

---

## Fronteiras de confiança

| Fronteira | Descrição | Dados que cruzam |
|-----------|-----------|------------------|
| Cliente → API pública | `POST/GET /notifications`, `/notifications/stats`, Swagger; sem autenticação (fora do escopo) | Destinatário, variáveis de template, `Idempotency-Key` |
| Operador → `/admin/**` | Reprocesso da DLQ e `republish-failed`, protegidos por `X-API-Key` | API key, parâmetros `max`/canal |
| API ↔ RabbitMQ (DLQ) | Leitura por `basic.get` e republish com confirms | Corpo `NotificationMessage` (não confiável até ser desserializado no tipo concreto) |
| API/worker ↔ MongoDB | Transições de status com fence condicional, varredura agendada | Documentos de notificação, histórico de tentativas |
| Build → Maven Central / Docker Hub | Dependências e imagens de teste | Artefatos pinados pelo BOM do Boot e por tags exatas |

---

## Registro de ameaças

| Threat ID | Category | Component | Severity | Disposition | Mitigation | Status |
|-----------|----------|-----------|----------|-------------|------------|--------|
| T-03-01 | Tampering | imagens dos containers de teste | medium | mitigate | Tags exatas em `notify-it/.../support/EndToEndSupport.java`; nenhum `:latest` no repo | closed |
| T-03-SC | Tampering | dependências Maven novas (`spring-boot-testcontainers`, `org.testcontainers:rabbitmq`/`mongodb`) | high | mitigate | Testcontainers só pelo BOM do Boot 3.5.16; nenhuma `<testcontainers.version>` sobrescrita | closed |
| T-03-02 | Information Disclosure | `notify.admin.api-key: it-admin-key` no `application-it.yml` | low | accept | Risco aceito (ver log) | closed |
| T-03-03 | Elevation of Privilege | destinatários mágicos ligados no profile `it` | low | accept | Risco aceito (ver log) | closed |
| T-03-04 | Spoofing | `X-API-Key` (força bruta e timing) | high | mitigate | `AdminWebConfiguration`: SHA-256 + `MessageDigest.isEqual` | closed |
| T-03-05 | Elevation of Privilege | `/admin/**` sem chave ou com chave não configurada | high | mitigate | Interceptor `/admin/**` em `AdminWebConfiguration`; chave vazia = fail closed | closed |
| T-03-06 | Tampering | desserialização do corpo da DLQ dirigida por `__TypeId__` | high | mitigate | `DlqReprocessService:171` `objectMapper.readValue(..., NotificationMessage.class)`; sem `__TypeId__` | closed |
| T-03-07 | Information Disclosure | texto livre do broker ou de exceção no 503 | medium | mitigate | `FailureReason` (conjunto fechado) em `DlqReprocessService` | closed |
| T-03-08 | Repudiation | perda silenciosa de mensagem no reprocess (valor central) | high | mitigate | `DlqReprocessService`: ack só após confirm, `basicNack` com requeue; sem `basicReject`; `DlqReprocessFlowTest` | closed |
| T-03-09 | Denial of Service | reprocess em laço dentro de uma chamada | medium | mitigate | `notify.admin.max-limit` em `AdminProperties`/`AdminController` | closed |
| T-03-10 | Information Disclosure | API key em log ou em resposta | high | mitigate | Interceptor não loga nem ecoa a chave; detail fixo no 401 | closed |
| T-03-11 | Elevation of Privilege | endpoint admin sem interceptor entre os commits das Tasks 1 e 3 | low | accept | Risco aceito (ver log) | closed |
| T-03-12 | Tampering | duas chamadas concorrentes de reprocess no mesmo canal | medium | mitigate | `ReprocessEdgeCasesIT` | closed |
| T-03-13 | Denial of Service | documento não terminal na cabeça da DLQ travando o canal (CR-01) | medium | mitigate | Transições D-01 no reset; `ReprocessEdgeCasesIT` | closed |
| T-03-14 | Repudiation | queda da API no meio do reprocess | low | accept | Risco aceito (ver log) | closed |
| T-03-15 | Denial of Service | mensagem venenosa em laço na fila principal | medium | mitigate | `default-requeue-rejected: false` no worker; `DeliveryGuaranteesIT` | closed |
| T-03-16 | Tampering | cópias duplicadas da mesma mensagem gerando envios repetidos | medium | mitigate | Claim condicional do worker; `DeliveryGuaranteesIT` | closed |
| T-03-17 | Tampering | corrida de POSTs com a mesma chave criando duplicatas | medium | mitigate | Índice único parcial (`MongoIndexInitializer`) + `DuplicateKeyException` em `NotificationService`; `IdempotencyIT` | closed |
| T-03-18 | Information Disclosure | sondagem de chaves de outro cliente (replay devolve id e status) | low | accept | Risco aceito (ver log) | closed |
| T-03-19 | Denial of Service | chave enorme ou com bytes de controle | medium | mitigate | `NotificationController:52` `^[!-~]{1,255}$` | closed |
| T-03-20 | Information Disclosure | `idempotencyKey`/`requestHash` expostos no GET | low | mitigate | `doesNotExist` em `NotificationApiFlowTest` | closed |
| T-03-21 | Information Disclosure | reply text do broker em `lastError.message` e no GET | medium | mitigate | Mensagem fixa por `errorCode()` em `NotificationRepublisher`; `UnroutablePublishIT` | closed |
| T-03-22 | Tampering | republish de notificação que já saiu de PUBLISH_FAILED (envio duplicado) | medium | mitigate | Fence `status = PUBLISH_FAILED` antes do publish; `NotificationRepublisherTest` | closed |
| T-03-23 | Elevation of Privilege | `republish-failed` sem chave | high | mitigate | Interceptor `/admin/**`; `UnroutablePublishIT` (401) | closed |
| T-03-24 | Denial of Service | lote de republish sem limite | low | mitigate | Teto `max-limit` em `NotificationRepublisher` | closed |
| T-03-25 | Information Disclosure | enumeração de destinatários pela listagem | medium | accept | Risco aceito (ver log) | closed |
| T-03-26 | Denial of Service | paginação profunda ou páginas enormes | medium | mitigate | `size` 1..100 em `NotificationQueryService`; índice `status_createdAt` | closed |
| T-03-27 | Tampering | valores arbitrários em `status`/`channel` chegando à query | medium | mitigate | Parse para enum antes da `Criteria` em `NotificationQueryService` | closed |
| T-03-28 | Denial of Service | stats varrendo a coleção a cada chamada | low | accept | Risco aceito (ver log) | closed |
| T-03-SC2 | Tampering | dependência `springdoc-openapi-starter-webmvc-ui` | high | mitigate | `notify-api/pom.xml:49` springdoc 2.8.17; BOM do Boot intacto | closed |
| T-03-29 | Information Disclosure | superfície da API exposta pelo Swagger | low | accept | Risco aceito (ver log) | closed |
| T-03-30 | Information Disclosure | `demo-admin-key` versionada no profile demo | low | accept | Risco aceito (ver log) | closed |
| T-03-31 | Spoofing | chave digitada no "Authorize" do Swagger UI | low | accept | Risco aceito (ver log) | closed |
| T-03-32 | Tampering | `markUnconfirmedAsPublishFailed` (varredura sobrescrevendo claim do worker ou publicação em voo) | high | mitigate | `UnconfirmedPublishSweeper`/`markUnconfirmedAsPublishFailed`; `afterShorterThanThreeConfirmTimeoutsFailsAtStartup` | closed |
| T-03-36 | Denial of Service | varredura agendada (carga no Mongo, intervalo mal configurado) | low | accept | Risco aceito (ver log) | closed |
| T-03-41 | Information Disclosure | WARN da varredura (texto da exceção do driver, ids ou destinatários no log) | low | mitigate | `UnconfirmedPublishSweeper` sem `getMessage()`; `UnconfirmedPublishSweeperTest` | closed |
| T-03-37 | Spoofing | `NotificationService.replay` (202 sem prova de publicação) | high | mitigate | `wasClaimedByWorker` em `NotificationService`; `UnconfirmedPublishRecoveryIT` | closed |
| T-03-38 | Tampering | republish do replay (segundo envio ao destinatário) | medium | mitigate | Fence PENDING + `updatedAt`; `IdempotencyIT` | closed |
| T-03-39 | Denial of Service | `awaitPublishWindow` (requisições esperando e fazendo polling no Mongo) | low | accept | Risco aceito (ver log) | closed |
| T-03-40 | Information Disclosure | logs do replay | low | mitigate | Logs do replay só com id/status/rodadas | closed |
| T-03-33 | Repudiation | `publishOrMarkFailed` (órfão sem rastro quando o Mongo falha) | medium | mitigate | Log ERROR com id; `NotificationServiceTest` (`nackThenMongoDown…`) | closed |
| T-03-34 | Information Disclosure | logs e details dos 503 com mensagem da exceção do driver ou texto do broker | medium | mitigate | Sem `e.getMessage()` em `NotificationRepublisher`; testes de ausência de texto | closed |
| T-03-35 | Spoofing | 202 respondido sem prova de publicação quando o update condicional casa 0 | high | mitigate | Releitura com `wasClaimedByWorker` em `NotificationRepublisher` | closed |

*Status: open · closed · open — below high threshold (non-blocking)*
*Severity: critical > high > medium > low — only open threats at or above workflow.security_block_on count toward threats_open*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Log de riscos aceitos

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-03-01 | T-03-02 | Chave só de teste, válida apenas contra containers efêmeros; o profile base dos apps continua sem chave (fail closed, 03-02) | Lucas Lopes (threat model do plano; UAT teste 4) | 2026-11-01 |
| AR-03-02 | T-03-03 | Ligados só em `notify-it/src/test/resources`; o profile base continua desligado (WR-07) e o profile `it` não vai para nenhum jar executável | Lucas Lopes (threat model do plano; UAT teste 4) | 2026-11-01 |
| AR-03-03 | T-03-11 | Janela dentro de uma única execução do plano, sem deploy; o plano só fecha com a Task 3 e o `./mvnw -q test` final | Lucas Lopes (threat model do plano; UAT teste 4) | 2026-11-01 |
| AR-03-04 | T-03-14 | Entregas sem ack voltam à DLQ quando o canal fecha (rede de segurança do broker); reset antes do publish deixa PENDING retomável. Truth backstop, sem teste que mate a JVM | Lucas Lopes (threat model do plano; UAT teste 4) | 2026-11-01 |
| AR-03-05 | T-03-18 | Inerente a uma API pública sem autenticação (fora do escopo); o 422 não ecoa a chave nem o id original; o Javadoc recomenda chaves de alta entropia (UUID). Registrar no README (DOC-03, Fase 4) | Lucas Lopes (threat model do plano; UAT teste 4) | 2026-11-01 |
| AR-03-06 | T-03-25 | D-11 (decisão do usuário) inclui `recipient` no resumo. Autenticação da API pública está fora do escopo (REQUIREMENTS). Conteúdo, variáveis e histórico ficam fora da lista (projeção). Registrar como risco aceito no README (DOC-03, Fase 4) | Lucas Lopes (threat model do plano; UAT teste 4) | 2026-11-01 |
| AR-03-07 | T-03-28 | Uma única agregação `$group` por chamada; volume da demo (milhares de documentos); sem cache, por simplicidade | Lucas Lopes (threat model do plano; UAT teste 4) | 2026-11-01 |
| AR-03-08 | T-03-29 | Portfólio: a doc não tem segredos nem exemplos com dados reais; os endpoints admin continuam exigindo `X-API-Key` | Lucas Lopes (threat model do plano; UAT teste 4) | 2026-11-01 |
| AR-03-09 | T-03-30 | Valor só da demo local (compose na rede local), documentado para quem avalia. O profile base fica vazio (fail closed), e `NOTIFY_ADMIN_API_KEY` sobrescreve | Lucas Lopes (threat model do plano; UAT teste 4) | 2026-11-01 |
| AR-03-10 | T-03-31 | Fica só no navegador do operador, durante a sessão; a demo roda em localhost | Lucas Lopes (threat model do plano; UAT teste 4) | 2026-11-01 |
| AR-03-11 | T-03-36 | Um `updateMulti` por intervalo (30s), servido pelo prefixo `status` do índice `status_createdAt`; Mongo fora → WARN e próxima execução, sem retry em laço | Lucas Lopes (threat model do plano; UAT teste 4) | 2026-11-01 |
| AR-03-12 | T-03-39 | Espera limitada a 2× `confirm-timeout` (10s), polling de 100ms, só no replay de PENDING recente, em threads virtuais. A API pública já aceita POSTs sem limite de taxa (registrado para o README da Fase 4) | Lucas Lopes (threat model do plano; UAT teste 4) | 2026-11-01 |

*Accepted risks do not resurface in future audit runs.*

---

## Trilha de auditoria

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-11-01 | 43 | 43 | 0 | /gsd-secure-phase (orquestrador, verificação L1 por grep; auditor pulado pelo short-circuit ASVS 1) |

31 `mitigate` com evidência no código, 12 `accept`, 0 `transfer`.

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-11-01
