# Phase 2: Falhas, retry com backoff e resiliência - Context

**Gathered:** 2026-10-14
**Status:** Ready for planning

<domain>
## Phase Boundary

Esta fase dá um destino previsível e visível a toda falha de entrega no `notify-worker`:
- **Classificador exaustivo (DLV-03):** separa falhas transitórias, permanentes e desconhecidas.
- **Escada de delay (DLV-04/05):** a falha transitória é republicada no próximo nível com o header `notify-retry-count`, a original só é confirmada depois do confirm, o status fica RETRYING e, quando os níveis acabam, vira DEAD_LETTERED e a mensagem vai para a DLQ.
- **Falha permanente (DLV-06):** vira FAILED direto e a mensagem vai para a DLQ com `x-failure-class`.
- **Resiliência (RES-01/02):** circuit breaker e bulkhead por semáforo em cada adapter de canal (Resilience4j 2.4).
- **Destinatários mágicos determinísticos (DLV-02).**
- **Renderizador estrito com escape por canal (TPL-02/03)** e seus testes unitários (TST-01), que rodam sem Docker.
- **Recuperação de notificação presa em PROCESSING (WRK-03).**
- **Contadores e timer Micrometer** expostos em `/actuator/prometheus` do worker (OBS-01).

Requisitos: WRK-03, TPL-02, TPL-03, DLV-02, DLV-03, DLV-04, DLV-05, DLV-06, RES-01, RES-02, OBS-01, TST-01.

Ficam fora desta fase:
- Reprocessamento da DLQ e republish de PUBLISH_FAILED (ADM-*).
- Idempotency-Key, listagem, stats e Swagger.
- ITs com Testcontainers (TST-02..04). Todos vão para a Fase 3.
- Prometheus/Grafana no compose, k6, benchmark e README, que vão para a Fase 4.

</domain>

<decisions>
## Implementation Decisions

### Destinatários mágicos (DLV-02)
- **D-01:** A convenção é prefixo semântico + sufixo, legível no k6 e no README:
  - E-mail: `always-transient@fail.test`, `always-permanent@fail.test`, `fail-<N>-then-ok@fail.test` e `fail-until-reprocess@fail.test`.
  - Webhook: `https://fail.test/always-transient`, `/always-permanent`, `/fail-<N>-then-ok` e `/fail-until-reprocess`.
  - SMS: como o E.164 só tem dígitos, usa o prefixo `+550000000` seguido de um código numérico documentado. Por exemplo, `…001` = sempre transitória, `…002` = sempre permanente, `…1N3` = falha N vezes e `…004` = até reprocessar. O planner fecha os dígitos exatos, desde que caibam em `RecipientRules` (E.164, até 16 caracteres).

  Destinatário que não casa com nenhum padrão mágico segue o fluxo normal (latência + `failure-rate` aleatório). — **Reversibility:** costly — o k6 (Fase 4), os ITs (Fase 3) e o README referenciam esses destinatários.
- **D-02:** "Falha N vezes e depois sucede" usa o `retryCount` da mensagem: falha enquanto `retryCount < N`. É determinístico e não guarda estado. Com 3 níveis, N ∈ {1,2,3} termina SENT e N ≥ 4 termina DEAD_LETTERED. Depois de um reprocess (que zera o contador) volta a falhar, e isso precisa estar documentado.
- **D-03:** Há um quarto destinatário mágico, `fail-until-reprocess`, baseado no `attemptCount` total do documento, que o reprocess não zera. Ele falha enquanto `attemptCount ≤ 4` (envio inicial + 3 níveis) e sucede depois. Isso torna demonstrável retry → DLQ → reprocess → SENT na demo e no IT da Fase 3 sem trocar configuração em tempo de execução. Por isso o adapter recebe o contexto da tentativa (`retryCount`, `attemptCount`) além de destinatário e conteúdo.
- **D-04:** A falha mágica é injetada **dentro do adapter, sob o bulkhead e o circuit breaker**. Ela aplica a latência simulada e lança a mesma exceção de uma falha real do provedor (transitória ≈ 5xx/timeout simulado; permanente ≈ destinatário inválido). Uma rajada de `always-transient` abre o CB, e isso faz parte da demo.

### Circuit breaker e bulkhead na escada (RES-01, RES-02)
- **D-05:** `CallNotPermittedException` (CB aberto) e `BulkheadFullException` são transitórias **e consomem um nível da escada**, como qualquer transitória. A regra é uma só, sem contador separado. O risco conhecido é que um CB aberto por muito tempo esgote a escada e mande a notificação para DEAD_LETTERED. Ele é mitigado pelo tempo aberto ≤ 1º nível (D-06), e o reprocess da Fase 3 recupera o que sobrar.
- **D-06:** O CB é sensível e se recupera rápido: COUNT_BASED com janela de 20, mínimo de 10 chamadas, limiar de 50%, `waitDurationInOpenState` ≤ 1º nível da escada (demo ~1s; padrão ~5s) e 3 chamadas em half-open. Ele registra **só falhas transitórias**: as permanentes ficam em `ignoreExceptions`, porque um destinatário inválido não indica provedor doente. Tudo é configurável por canal em `application.yml` (instâncias `email`, `sms`, `webhook`).
- **D-07:** O bulkhead por semáforo fica abaixo da concorrência do listener (ex.: `maxConcurrentCalls=8` com 10 consumidores) e `maxWaitDuration=0`, para que a recusa apareça sob carga. Configurável por canal. Não usar `ThreadPoolBulkhead`, que tira o trabalho das virtual threads.
- **D-08:** Bulkhead, CB e failure-rate precisam ser totalmente configuráveis por propriedade/ENV, porque o profile do benchmark da Fase 4 vai afrouxar o bulkhead (≥ concorrência) e neutralizar o CB/falhas. A Fase 2 não cria esse profile, só garante que ele seja possível.

### PROCESSING preso e ack (WRK-03)
- **D-09:** O claim também aceita PROCESSING quando **a mensagem chega com `redelivered=true` OU quando `leaseUntil < agora`**. O fence por `retryCount` continua valendo. `redelivered` cobre o caso típico (worker derrubado, broker reentrega na hora) e o lease cobre o resto. Isso atualiza `NotificationStatus.claimableForDelivery()` e o filtro do `NotificationStateService.claim`, e a transição PROCESSING→PROCESSING já existe na tabela.
- **D-10:** O ack mode continua **AUTO, com confirm síncrono dentro do listener**. O listener republica na fila de delay e bloqueia até o confirm, o que é barato em virtual threads. Retorno normal faz ack. Para mandar para a DLQ usa `AmqpRejectAndDontRequeueException`. MANUAL não é usado. Isso resolve o blocker do STATE.md.
- **D-11:** O retry segue esta ordem:
  1. Update atômico PROCESSING→RETRYING + `$push` attempt.
  2. Publish na fila de delay do próximo nível com `notify-retry-count` incrementado e espera do confirm.
  3. Retorno normal (ack).

  Se o publish der nack, timeout ou for devolvido, faz o update condicional RETRYING→DEAD_LETTERED com `lastError` "republicação para retry.<nível> falhou" e rejeita a mensagem para a DLQ. Assim o status nunca diverge de onde a mensagem está e nada fica em PROCESSING. **A transição RETRYING→DEAD_LETTERED entra na tabela de `NotificationStatus`.** — **Reversibility:** costly — a tabela de transições é o contrato dos filtros condicionais da API e do worker.
- **D-12:** A prova do critério 3 nesta fase tem duas partes: testes unitários do filtro de claim (redelivered/lease/fence) com `MongoBson`, como na Fase 1, e um passo no smoke da fase (notificação com latência longa → kill do worker antes do ack → restart → SENT com um único envio de sucesso). O IT automatizado fica para a Fase 3 (TST-03).

### Histórico e métricas (DLV-03, OBS-01)
- **D-13:** Uma exceção desconhecida (não classificada) leva a notificação a **FAILED com a nova classe `UNKNOWN`**: o enum `FailureClass` e o header `x-failure-class` ganham esse valor. Ela não gasta retries e vai para a DLQ. Assim o operador distingue bug de erro do cliente. — **Reversibility:** costly — o enum é compartilhado (`notify-common`), persistido no Mongo e exposto no `GET /{id}`.
- **D-14:** **Todo processamento após o claim gera um `AttemptEntry`**, inclusive rejeição de CB/bulkhead e falha de template antes do adapter. A entrada traz outcome, `failureClass` e um código de erro estável de um conjunto fechado (ex.: `SIMULATED_TRANSIENT`, `SIMULATED_PERMANENT`, `CIRCUIT_OPEN`, `BULKHEAD_FULL`, `TEMPLATE_NOT_FOUND`, `MISSING_VARIABLE`, `INVALID_WEBHOOK_PAYLOAD`, `REPUBLISH_FAILED`, `UNEXPECTED`), com `durationMs` ≈ 0 quando o adapter não foi chamado. Isso garante "uma tentativa por nível" no critério 1.
- **D-15:** Formato das métricas, do qual o dashboard da Fase 4 depende:
  - `notify.delivery.outcomes{channel, outcome=sent|retrying|failed|dead_lettered, failure_class}`
  - `notify.delivery.retries{channel, level=1|2|3}`
  - timer `notify.delivery.duration{channel, outcome}` com histograma/percentis para o p95

  As tags têm conjunto fechado e são pré-registradas na inicialização. Os códigos de erro detalhados **não** viram tag: ficam no Mongo e no log. O worker passa a expor `prometheus` no actuator (`micrometer-registry-prometheus`). As métricas do Resilience4j (estado do CB, bulkhead) vêm do `resilience4j-micrometer`. — **Reversibility:** costly — o dashboard provisionado da Fase 4 e as queries do README usam esses nomes.

### Templates (TPL-02, TPL-03)
- **D-16:** No webhook, os valores são escapados como string JSON (aspas, barra invertida, caracteres de controle) por um escaper próprio do JMustache. Depois de renderizar, o corpo passa por um parse com o `ObjectMapper`. JSON inválido gera falha PERMANENT com `INVALID_WEBHOOK_PAYLOAD` em vez de sair corrompido. E-mail escapa HTML no corpo, e o SMS sai sem escape. Template inexistente, variante ausente e variável obrigatória faltando são PERMANENT. A variável obrigatória é conferida contra `requiredVariables` e também pelo comportamento padrão do JMustache, que lança exceção.

### Claude's Discretion
- Dígitos exatos dos códigos mágicos de SMS (D-01) e onde fica o parser de destinatário mágico (provavelmente `notify-worker/channel`).
- Uso da API programática (`CircuitBreakerRegistry`/`Decorators`) ou de anotações + `spring-boot-starter-aop`. A programática é a preferida por deixar explícita a ordem bulkhead → CB → adapter e não depender de proxy.
- Desenho do classificador: mapa de exceção para classe/código, exaustivo e testado unitariamente.
- Momento de registrar cada contador (pré-registro de todas as combinações de tag).
- Corrigir o deferred item da Fase 1 (`MustacheAutoConfiguration` ativo no worker) se for trivial ao mexer no `application.yml`.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Escopo e requisitos
- `.planning/PROJECT.md`: core value, restrições e Key Decisions (fator 5, contador sem `x-death`, estados terminais)
- `.planning/REQUIREMENTS.md`: texto exato de WRK-03, TPL-02/03, DLV-02..06, RES-01/02, OBS-01, TST-01
- `.planning/ROADMAP.md` §Phase 2: critérios de sucesso e notas de pesquisa (dimensionamento CB/bulkhead, `x-death` no RabbitMQ 4.3)
- `.planning/STATE.md` §Blockers/Concerns: argumentos de fila num único lugar; factories de listener só pelo configurer do Boot; lacuna "rejeitada fica em PROCESSING"

### Fase anterior
- `.planning/phases/01-entrega-ponta-a-ponta-no-caminho-feliz/01-CONTEXT.md`: D-03 (`GET /{id}` com `attempts[]`), D-09 (`_notificationId` reservado), validação de destinatário
- `.planning/phases/01-entrega-ponta-a-ponta-no-caminho-feliz/deferred-items.md`: `MustacheAutoConfiguration` no worker
- `.planning/phases/01-entrega-ponta-a-ponta-no-caminho-feliz/01-RESEARCH.md` e `01-PATTERNS.md`: padrões já estabelecidos (claim, update atômico, confirms)

### Pesquisa (padrões e armadilhas)
- `.planning/research/ARCHITECTURE.md`: Pattern 3 (ack/erro do listener), Pattern 4 (findAndModify), Pattern 5 (confirms)
- `.planning/research/PITFALLS.md`: requeue em loop, perda de VT em factory customizada, `x-death`
- `.planning/research/STACK.md`: Resilience4j 2.4.0 (AOP necessário para anotações; semáforo, não ThreadPoolBulkhead), JMustache e o comportamento com variável ausente
- `.planning/research/FEATURES.md`: máquina de estados e tabela de transições

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `notify-worker/.../processing/DeliveryProcessor.java`: pipeline claim → template → render → adapter → SENT. Ganha o classificador e os ramos RETRYING/FAILED/DEAD_LETTERED.
- `notify-worker/.../processing/NotificationStateService.java`: `claim` (findAndModify com fence e `leaseUntil`) e `markSent` (update atômico `$set` + `$push`). Os novos resultados seguem o mesmo molde.
- `notify-worker/.../channel/SimulatedChannelAdapter.java` + `SimulationProperties`: latência e failure-rate por canal. Recebem os destinatários mágicos e o contexto da tentativa.
- `notify-worker/.../template/TemplateRenderer.java`: compiladores HTML/PLAIN. Ganha o escaper JSON e a validação estrita.
- `notify-worker/.../listener/RetryHeaders.java`: parse do `notify-retry-count`, reutilizado na republicação.
- `notify-common/.../domain/{FailureClass, AttemptOutcome, NotificationStatus}`, `persistence/AttemptEntry`, `messaging/{NotifyHeaders, NotifyTopology, TopologyProperties}`: contrato compartilhado (filas de delay por nível já declaradas).
- Teste: `MongoBson` (asserção de filtros/updates), `DeliveryFlowTest`, `DeliveryProcessorTest`, `TemplateRendererTest`.

### Established Patterns
- Records, sem Lombok. `MongoTemplate` para transições condicionais derivadas da tabela `NotificationStatus.ALLOWED` (nunca filtros escritos à mão).
- Listener `@RabbitListener` por canal na factory do Boot (VT). O header de retry chega como `String`.
- `default-requeue-rejected=false` e retry do Spring desligado. Exceção no listener manda para a DLQ.
- Worker com `publisher-confirm-type: correlated`, `publisher-returns` e `mandatory` já configurados.
- Comentários e mensagens de log em pt-BR. Destinatário sempre mascarado com `RecipientRules.mask`.

### Integration Points
- O `NotifyTopology` define os nomes das filas de delay por nível e a routing key de cada uma. A republicação usa esses nomes, sem duplicar strings.
- `GET /notifications/{id}` (API) já expõe `attempts[]`/`lastError`. Os novos campos de erro e classe precisam aparecer lá sem quebrar o contrato D-03 da Fase 1.
- O actuator do worker (porta 8081) passa a expor `prometheus`, que o compose da Fase 4 vai coletar.

</code_context>

<specifics>
## Specific Ideas

- A demo precisa mostrar o CB abrindo e fechando várias vezes durante a carga do k6 e o bulkhead recusando excesso, com tudo terminando em RETRYING, nunca em FAILED.
- `fail-until-reprocess` existe para contar a história completa (retry → DLQ → reprocess → SENT) sem truques de configuração.
- O `GET /{id}` de uma notificação `always-transient` deve mostrar 4 tentativas (inicial + 3 níveis) com código e classe da falha, legíveis sem abrir o Mongo.

</specifics>

<deferred>
## Deferred Ideas

- Profile de benchmark com bulkhead afrouxado e CB neutralizado. Pertence à Fase 4 (DEM-03). A Fase 2 só garante a configurabilidade.
- IT automatizado de worker derrubado / entrega duplicada concorrente: Fase 3 (TST-03).

</deferred>

---

*Phase: 02-falhas-retry-com-backoff-e-resiliência*
*Context gathered: 2026-10-14*
