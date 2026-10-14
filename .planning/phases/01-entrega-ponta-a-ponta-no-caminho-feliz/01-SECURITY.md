---
phase: "01"
slug: "entrega-ponta-a-ponta-no-caminho-feliz"
status: verified
# threats_open = count of OPEN threats at or above workflow.security_block_on severity (the blocking gate)
threats_open: 0
asvs_level: 1
created: "2026-10-13"
---

# Fase 01 — Segurança

> Contrato de segurança da fase: registro de ameaças, riscos aceitos e trilha de auditoria.
> Registro consolidado dos `<threat_model>` dos planos 01-01 a 01-07; verificação em profundidade ASVS L1 (evidência por busca no código e nos testes).

---

## Fronteiras de confiança

| Fronteira | Descrição | Dados que atravessam |
|-----------|-----------|----------------------|
| Maven Central → build | Dependências e distribuição do Maven baixadas da internet | Artefatos de build (integridade) |
| host → containers da infra | Portas do RabbitMQ e do Mongo publicadas no host | Credenciais de demo, dados de notificação |
| cliente HTTP → API | Corpo JSON e path não confiáveis entram no controller | Destinatário (PII), variáveis do template |
| API → MongoDB | Variáveis do cliente gravadas como subdocumento | PII, variáveis |
| API/worker ↔ broker | Mensagem AMQP atravessa o broker e fica nas filas (inclusive DLQ) | Mensagem fina (id, canal, templateCode, createdAt) |
| broker → worker | Mensagens, inclusive publicadas à mão pelo painel, entram no listener | JSON não confiável, headers |
| cliente → variáveis do template | Valores do POST entram no contexto de renderização | Texto livre do cliente |
| worker → "provedor" do canal | Ponto onde um adapter real faria saída de rede (aqui simulado) | Conteúdo renderizado |
| classpath → coleção templates | Seed empacotado no jar grava documentos no Mongo | Templates |
| script → RabbitMQ / processos locais | Smoke remove/restaura binding, publica mensagens e inicia/encerra JVMs | Credenciais de demo |

---

## Registro de ameaças

| Threat ID | Category | Component | Severity | Disposition | Mitigation | Status |
|-----------|----------|-----------|----------|-------------|------------|--------|
| T-01-01 | Tampering | POMs / wrapper | medium | mitigate | Parent `spring-boot-starter-parent` 3.5.16, sem versões sobrescritas; wrapper em `https://repo.maven.apache.org` (`.mvn/wrapper/maven-wrapper.properties`) | closed |
| T-01-SC | Tampering | dependências Maven | medium | mitigate | Pacotes auditados no Maven Central (RESEARCH); nada fora do BOM nesta fase | closed |
| T-01-02 | Information Disclosure | portas do `docker-compose.yml` | medium | mitigate | Todas as portas publicadas em `127.0.0.1:` (`docker-compose.yml`) | closed |
| T-01-03 | Spoofing | credenciais `notify`/`notify`, Mongo sem auth | low | accept | Ver AR-01 | closed |
| T-01-04 | Information Disclosure | `NotificationMessage` | medium | mitigate | Mensagem de 4 campos; `NotificationMessageJsonTest` | closed |
| T-01-05 | Denial of Service | regex de `RecipientRules` | low | mitigate | Tamanho (254/2048) conferido antes da regex | closed |
| T-01-06 | Denial of Service | `NotifyTopology` | medium | mitigate | Argumentos numa fonte única; `NotifyTopologyTest` | closed |
| T-01-07 | Denial of Service | filas transientes / argumentos CQv1 | low | mitigate | Só `QueueBuilder.durable`; teste garante ausência de `x-queue-mode`/`x-queue-version` | closed |
| T-01-08 | Tampering | desserialização AMQP | high | mitigate | `Jackson2JsonMessageConverter` único em `NotifyCommonConfiguration`; listener com tipo concreto | closed |
| T-01-09 | Tampering | chaves de `variables` no Mongo | high | mitigate | `NotificationDocumentMappingTest`; regex de chave na API antes do insert | closed |
| T-01-10 | Elevation of Privilege | `TemplateRenderer` (injeção de template) | high | mitigate | Só templates do seed (`TemplateCatalog`/`TemplateSeeder`); nenhum endpoint aceita texto de template | closed |
| T-01-11 | Tampering | corpo do EMAIL (HTML) | medium | mitigate | `Mustache.compiler()` com escape padrão no corpo; `TemplateRendererTest` confere `&lt;b&gt;` | closed |
| T-01-12 | Information Disclosure | logs do adapter | medium | mitigate | `RecipientRules.mask` em `SimulatedChannelAdapter` | closed |
| T-01-13 | Information Disclosure | adapter WEBHOOK (SSRF) | high | mitigate | Nenhum cliente HTTP/SMTP/socket em `notify-worker/src/main` (busca sem ocorrências) | closed |
| T-01-14 | Denial of Service | seed inválido | low | accept | Ver AR-02 | closed |
| T-01-15 | Tampering | `variables` (injeção de operador NoSQL) | high | mitigate | Regex `^[a-zA-Z][a-zA-Z0-9_]{0,63}$` em `CreateNotificationRequestValidator`; queries com `Criteria` tipado | closed |
| T-01-16 | Denial of Service | corpo do POST | medium | mitigate | `MAX_VARIABLES = 50`, `MAX_STRING_LENGTH = 1000`, `@Size` 2048/100 | closed |
| T-01-17 | Repudiation | promessa "aceita = nunca perdida" | high | mitigate | Insert PENDING antes do publish; confirm + mandatory; PUBLISH_FAILED + 503 (`NotificationService`) | closed |
| T-01-18 | Information Disclosure | respostas de erro | medium | mitigate | `ProblemDetail` sem stack trace | closed |
| T-01-19 | Information Disclosure | logs da API | medium | mitigate | `RecipientRules.mask` em `NotificationService` | closed |
| T-01-20 | Information Disclosure | actuator | low | mitigate | `management.endpoints.web.exposure.include: health,info` | closed |
| T-01-21 | Spoofing | API pública sem autenticação | low | accept | Ver AR-03 | closed |
| T-01-22 | Denial of Service | mensagem venenosa em loop | high | mitigate | `default-requeue-rejected: false` no worker + DLX; `WorkerSettingsTest` | closed |
| T-01-23 | Tampering | entrega duplicada / mensagem forjada | high | mitigate | Claim por `findAndModify` condicional em `NotificationStateService` | closed |
| T-01-24 | Tampering | header `notify-retry-count` | medium | mitigate | `RetryHeaders.parse` aceita só inteiro ≥ 0 | closed |
| T-01-25 | Tampering | desserialização do corpo no worker | medium | mitigate | `ChannelListeners` recebe `NotificationMessage` concreto | closed |
| T-01-26 | Information Disclosure | logs do worker | medium | mitigate | `RecipientRules.mask` em `DeliveryProcessor` e no adapter | closed |
| T-01-27 | Denial of Service | exaustão de threads/conexões | low | mitigate | Virtual threads; concorrência e prefetch 10 configuráveis | closed |
| T-01-28 | Tampering | binding removido no cenário 503 | medium | mitigate | `restaurar_binding` no fluxo e no `trap` (`scripts/smoke-fase1.sh`) | closed |
| T-01-29 | Information Disclosure | credenciais no script de smoke | low | accept | Ver AR-04 | closed |
| T-01-30 | Denial of Service | JVMs órfãs em 8080/8081 | low | mitigate | `trap encerrar` + `porta_livre` | closed |
| T-01-31 | Tampering | `channel` por ordinal numérico | medium | mitigate | `fail-on-numbers-for-enums: true`; `numericChannelIs400WithOnlyChannelErrorAndNoWrite` + smoke | closed |
| T-01-32 | Information Disclosure | corpo do 400 de canal numérico | low | mitigate | Mensagem fixa "valores aceitos: ..." em `ApiExceptionHandler`; `NotificationValidationTest` | closed |
| T-01-33 | Tampering | regressão da flag por override | low | accept | Ver AR-05 | closed |

*Status: open · closed · open — below high threshold (non-blocking)*
*Severity: critical > high > medium > low — only open threats at or above workflow.security_block_on count toward threats_open*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Registro de riscos aceitos

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-01 | T-01-03 | Ambiente de demo local, portas só em loopback; limitação a documentar no README (Fase 4) | Planejamento 01-01 | 2026-10-12 |
| AR-02 | T-01-14 | Seed quebrado derruba o worker na subida (fail fast); seed versionado e coberto por `SeedTemplatesTest` | Planejamento 01-03 | 2026-10-12 |
| AR-03 | T-01-21 | Autenticação da API pública fora do escopo do portfólio; ids UUID v4; API key nos endpoints admin da Fase 3 | Planejamento 01-04 | 2026-10-12 |
| AR-04 | T-01-29 | Credenciais de demo iguais às do compose, portas em loopback | Planejamento 01-06 | 2026-10-12 |
| AR-05 | T-01-33 | Flag no `application.yml` base; só quem controla o deploy a desligaria; teste WebMvc falha se a chave sumir | Planejamento 01-07 | 2026-10-12 |

*Riscos aceitos não voltam em auditorias futuras.*

---

## Trilha de auditoria

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-10-13 | 34 | 34 (29 mitigadas, 5 aceitas) | 0 | /gsd-secure-phase (orquestrador, ASVS L1 por busca; auditor dispensado pela regra de atalho) |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-10-13
