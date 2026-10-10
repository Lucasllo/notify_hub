# Phase 1: Entrega ponta a ponta no caminho feliz - Context

**Gathered:** 2026-10-10
**Status:** Ready for planning

<domain>
## Phase Boundary

Esqueleto completo e o caminho feliz de ponta a ponta:
- Maven multi-módulo (parent + `notify-common`, `notify-api`, `notify-worker`) com wrapper, Java 21 e Boot 3.5.16.
- `notify-common` com o contrato compartilhado: enums, tabela de transições, record da mensagem, headers, `Declarables` e inicializador de índices.
- A topologia RabbitMQ inteira, com principal, 3 filas de delay e DLQ por canal e TTLs configuráveis por profile.
- Compose só com a infra (RabbitMQ 4.3 e MongoDB 8.0 com healthchecks).
- API com `POST /notifications` (202, publisher confirms e PUBLISH_FAILED/503), `GET /notifications/{id}` e 400 com Problem Details.
- Worker com um listener por canal em virtual threads. O worker reivindica a mensagem com `findAndModify`, renderiza o template do seed, chama o adapter simulado e grava SENT numa atualização atômica.

Ficam fora desta fase:
- Classificador, retry, circuit breaker, bulkhead, destinatários mágicos e métricas (Fase 2).
- Idempotency-Key, listagem, stats, Swagger, admin e ITs (Fase 3).
- Dockerfiles das apps, Prometheus, Grafana, k6 e README (Fase 4).

Requisitos: FND-01..04, MSG-01..05, API-01, API-02, API-04, API-06, WRK-01, WRK-02, WRK-04, WRK-05, TPL-01, DLV-01.

</domain>

<decisions>
## Implementation Decisions

### Contrato da API
- **D-01:** O corpo do `POST /notifications` é plano, com campos no nível raiz: `channel` (enum em maiúsculo: `EMAIL` | `SMS` | `WEBHOOK`), `recipient` (string), `templateCode` (string) e `variables` (objeto string→valor). Exemplo: `{"channel":"EMAIL","recipient":"ana@exemplo.com","templateCode":"welcome","variables":{"name":"Ana"}}`. — **Reversibility:** costly — o k6, os ITs, os exemplos do README e o Swagger dependem desse formato.
- **D-02:** O 202 traz o header `Location: /notifications/{id}` e um corpo mínimo `{ "id", "status": "PENDING", "createdAt" }`. Assim o k6 e os clientes seguem a notificação sem precisar ler o header.
- **D-03:** O `GET /notifications/{id}` devolve a representação completa: `id`, `status`, `channel`, `recipient`, `templateCode`, `variables`, `rendered` (EMAIL: `subject` + `body`; SMS/WEBHOOK: `body`), `retryCount`, `lastError`, `attempts[]` completo (número, timestamp, `durationMs`, `outcome`, erro e classe da falha), `createdAt` e `updatedAt`. Por isso o worker persiste o conteúdo renderizado no documento, na mesma atualização atômica do resultado (WRK-04).
- **D-04:** A API espera o publisher confirm por até **5s**, valor configurável por propriedade (ex.: `notify.publish.confirm-timeout=5s`). Em nack, timeout ou mensagem devolvida, ela responde 503 com Problem Detail e `Retry-After: 5`. O valor do `Retry-After` também é configurável.
- **D-05:** O canal em maiúsculo é a forma pública da API e do documento no Mongo. Routing keys e nomes de fila usam o canal em minúsculo (`notification.email`, `notify.email.*`). A conversão fica centralizada no enum em `notify-common`.

### Templates do seed
- **D-06:** O seed traz 3 templates de e-commerce, cada um com variantes EMAIL, SMS e WEBHOOK: `welcome`, `order-shipped` e `password-reset`. — **Reversibility:** costly — o k6 (Fase 4), os ITs e os exemplos do README referenciam esses códigos.
- **D-07:** O conteúdo dos templates (assunto e corpo) é em **pt-BR**.
- **D-08:** O seed fica em arquivos JSON nos resources do worker (ex.: `notify-worker/src/main/resources/seed/templates/*.json`), um arquivo por template. Na inicialização, um `ApplicationRunner` faz upsert por `_id` e só sobrescreve quando a `version` do seed é maior. O comportamento é o mesmo no compose, localmente e no Testcontainers. Não usar `docker-entrypoint-initdb.d`.
- **D-09:** A variante WEBHOOK usa um envelope de evento: `{"event":"<evento>","notificationId":"{{_notificationId}}","data":{...}}`, com eventos como `user.welcome`, `order.shipped` e `password.reset`. `_notificationId` é uma **variável reservada** que o worker injeta na renderização. Ela não aparece em `requiredVariables` e o cliente não precisa enviá-la.
- **D-10:** Variáveis obrigatórias sugeridas: `welcome` → `name`; `order-shipped` → `name`, `orderId`; `password-reset` → `name`, `resetCode`. O planner pode ajustar os nomes, mas cada template tem pelo menos uma variável obrigatória.

### Claude's Discretion
- **Validação de destinatário por canal (API-06):** usar formatos simples e documentados: e-mail com regex simples (`local@dominio.tld`), SMS em E.164 (`^\+[1-9]\d{7,14}$`) e webhook como URL absoluta `http`/`https`. A regra deve aceitar os formatos dos destinatários mágicos da Fase 2 (ex.: `always-transient@fail.test`, `+5500000000001`, `https://fail.test/...`). A validação fica num validador por canal, não em anotações espalhadas.
- **Adapter simulado na Fase 1 (DLV-01):** latência bloqueante (`Thread.sleep`) e taxa de falha configuráveis por canal. Defaults da Fase 1: latência baixa (ex.: 50–200ms) e **taxa de falha 0%**, porque o caminho de retry só chega na Fase 2. Qualquer exceção nesta fase rejeita para a DLQ (sem requeue), o que já satisfaz o critério 4.
- **Escape na Fase 1:** o escape completo por canal é TPL-03 (Fase 2), mas a variante WEBHOOK não pode sair com `&quot;` já na Fase 1. Basta um compilador por canal com `escapeHTML(false)` para SMS/WEBHOOK. O escape JSON dos valores fica para a Fase 2.
- **Layout:** groupId e pacote base à escolha do planner (ex.: `dev.notifyhub`). API na porta 8080, worker na 8081 (`starter-web` para o actuator e o scrape futuro). O compose da Fase 1 sobe só RabbitMQ e MongoDB; as apps rodam via `./mvnw spring-boot:run`. Os Dockerfiles chegam na Fase 4.
- **ID:** UUID string gerado pela API (UUIDv7 é opcional), usado como `_id` e como `messageId` AMQP.
- Campos exatos do Problem Detail (`type`, `title`, `status`, `detail`, `instance` + `errors[]` por campo no 400).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Escopo e requisitos
- `.planning/PROJECT.md` — core value, restrições e tabela de Key Decisions
- `.planning/REQUIREMENTS.md` — texto exato de FND/MSG/API/WRK/TPL/DLV da Fase 1
- `.planning/ROADMAP.md` §Phase 1 — critérios de sucesso e notas de build order
- `.planning/STATE.md` §Blockers/Concerns — argumentos de fila num único lugar; fábricas de listener pelo configurer do Boot

### Pesquisa (padrões e armadilhas)
- `.planning/research/SUMMARY.md` — síntese e gaps resolvidos
- `.planning/research/ARCHITECTURE.md` — Patterns 1 (topologia), 3 (ack/erro do listener), 4 (findAndModify), 5 (confirms); §MongoDB Document Design (`notifications`, `templates`, índices ESR); §Recommended Project Structure
- `.planning/research/STACK.md` — versões pinadas, armadilhas do RabbitMQ 4.3 (sem CQv1, sem filas transientes, sem global QoS)
- `.planning/research/PITFALLS.md` — requeue em loop, confirms sem returns, 406 PRECONDITION_FAILED, perda de VT em factory customizada
- `.planning/research/FEATURES.md` — máquina de estados e tabela de transições

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- Nenhum: greenfield, o repositório só tem `.planning/` e `.claude/`.

### Established Patterns
- Nenhum padrão de código ainda. Esta fase estabelece as convenções (records para DTOs/eventos, sem Lombok, `MongoTemplate` para transições, um único bean `Declarables`).

### Integration Points
- `notify-common` é o ponto de integração entre API e worker. Eles nunca se chamam; compartilham só o contrato AMQP e o schema do Mongo.

</code_context>

<specifics>
## Specific Ideas

- O `GET /{id}` deve deixar a demo autoexplicativa: o examinador vê o texto renderizado em pt-BR e o histórico de tentativas sem abrir o Mongo.
- O envelope do webhook segue o estilo de eventos (`event` + `notificationId` + `data`), como em webhooks reais.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 01-entrega-ponta-a-ponta-no-caminho-feliz*
*Context gathered: 2026-10-10*
