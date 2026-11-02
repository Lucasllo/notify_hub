---
phase: 01-entrega-ponta-a-ponta-no-caminho-feliz
plan: 02
subsystem: messaging
tags: [spring-amqp, rabbitmq-4.3, declarables, dlx, ttl, spring-data-mongodb, indexes, configuration-properties, jackson]

requires:
  - phase: 01-01
    provides: "Channel (conversões D-05), TtlLabel, NotificationStatus/AttemptOutcome/FailureClass, Collections, RenderedContent"
provides:
  - "TopologyProperties: notify.topology.retry-levels (padrão 5s,25s,125s) validado no construtor compacto"
  - "NotifyTopology: MAIN_EXCHANGE/RETRY_EXCHANGE/DLX_EXCHANGE e declarables(TopologyProperties) com 3 exchanges, 15 filas e 15 bindings"
  - "NotifyCommonConfiguration (@Import pela API e pelo worker): beans notifyDeclarables, notifyMessageConverter (Jackson2JsonMessageConverter com o ObjectMapper do Boot) e mongoIndexInitializer"
  - "NotificationDocument (record @Document notifications, newPending, Fields), AttemptEntry, LastError"
  - "MongoIndexInitializer.createIndexes(): status_channel_createdAt, channel_createdAt, createdAt via createIndex"
  - "notify-common 0.1.0-SNAPSHOT reinstalado em ~/.m2 para a wave 3"
affects: [01-03, 01-04, 01-05, 01-06]

actuals:
  tokens: 10189
  tasks: 2
  commits: 4
plan_head_before: 5cd9fdf0409318a196a0be842fab19084a463b9a

tech-stack:
  added: [spring-web (só escopo de teste no notify-common)]
  patterns:
    - "Argumentos de fila existem só em NotifyTopology; API e worker nunca declaram filas"
    - "Níveis de retry por propriedade; nome da fila e chave levam o rótulo do TTL"
    - "Um único MessageConverter; o Boot o aplica ao RabbitTemplate e à factory de listener"
    - "Transições de status por update condicional usando NotificationDocument.Fields; nunca save()"
    - "Índices criados por MongoIndexInitializer com nomes estáveis, sem anotações de índice"

key-files:
  created:
    - notify-common/src/main/java/dev/notifyhub/common/messaging/TopologyProperties.java
    - notify-common/src/main/java/dev/notifyhub/common/messaging/NotifyTopology.java
    - notify-common/src/main/java/dev/notifyhub/common/NotifyCommonConfiguration.java
    - notify-common/src/main/java/dev/notifyhub/common/persistence/NotificationDocument.java
    - notify-common/src/main/java/dev/notifyhub/common/persistence/AttemptEntry.java
    - notify-common/src/main/java/dev/notifyhub/common/persistence/LastError.java
    - notify-common/src/main/java/dev/notifyhub/common/persistence/MongoIndexInitializer.java
    - notify-common/src/test/java/dev/notifyhub/common/messaging/NotifyTopologyTest.java
    - notify-common/src/test/java/dev/notifyhub/common/NotifyCommonConfigurationTest.java
    - notify-common/src/test/java/dev/notifyhub/common/persistence/NotificationDocumentMappingTest.java
    - notify-common/src/test/java/dev/notifyhub/common/persistence/MongoIndexInitializerTest.java
  modified:
    - notify-common/pom.xml

key-decisions:
  - "O MessageConverter injeta o ObjectMapper do Boot de forma estrita (sem fallback): se faltar, o contexto falha em vez de usar um mapper com outro formato de Instant"
  - "spring-web entra no notify-common só em escopo de teste, para que o JacksonAutoConfiguration crie o ObjectMapper como na API e no worker"
  - "TopologyProperties exige níveis entre 1ms e Integer.MAX_VALUE ms, estritamente crescentes e com rótulos distintos"
  - "MongoIndexInitializer usa as constantes de NotificationDocument.Fields para as chaves dos índices"

patterns-established:
  - "NotifyTopology.declarables(props) é a única fonte de argumentos de fila"
  - "NotificationDocument.Fields guarda os nomes de campo para Criteria e Update"
  - "attemptCount é monotônico; o número da próxima tentativa é attemptCount + 1"

requirements-completed: [MSG-01, MSG-02, MSG-03, MSG-04, MSG-05, FND-04, FND-02]

coverage:
  - id: D1
    description: "Um único Declarables com notify.exchange (topic), notify.retry e notify.dlx (direct), filas principais com DLX, filas de delay com TTL Integer e DLQs, todas duráveis, com declaração idempotente"
    requirement: MSG-01
    verification:
      - kind: unit
        ref: "notify-common/src/test/java/dev/notifyhub/common/messaging/NotifyTopologyTest.java"
        status: pass
    human_judgment: false
  - id: D2
    description: "Níveis de delay configuráveis por notify.topology.retry-levels (padrão, demo e ms); lista não crescente impede o contexto de subir"
    requirement: MSG-05
    verification:
      - kind: unit
        ref: "notify-common/src/test/java/dev/notifyhub/common/NotifyCommonConfigurationTest.java"
        status: pass
    human_judgment: false
  - id: D3
    description: "MessageConverter JSON único (Jackson2JsonMessageConverter com o ObjectMapper do Boot) exposto pela configuração compartilhada"
    requirement: FND-02
    verification:
      - kind: unit
        ref: "notify-common/src/test/java/dev/notifyhub/common/NotifyCommonConfigurationTest.java#exposesExactlyOneJsonMessageConverter"
        status: pass
    human_judgment: false
  - id: D4
    description: "Documento notifications grava _id, enums como string e attempts como array; chave com ponto é recusada pelo conversor"
    requirement: FND-02
    verification:
      - kind: unit
        ref: "notify-common/src/test/java/dev/notifyhub/common/persistence/NotificationDocumentMappingTest.java"
        status: pass
    human_judgment: false
  - id: D5
    description: "Índices nomeados status_channel_createdAt, channel_createdAt e createdAt criados por createIndex, de forma idempotente"
    requirement: FND-04
    verification:
      - kind: unit
        ref: "notify-common/src/test/java/dev/notifyhub/common/persistence/MongoIndexInitializerTest.java"
        status: pass
      - kind: other
        ref: "./mvnw -q -pl notify-common install (exit 0, 71 testes)"
        status: pass
    human_judgment: false
  - id: D6
    description: "Topologia visível no painel do RabbitMQ e índices presentes no Mongo em runtime"
    requirement: MSG-01
    verification: []
    human_judgment: true
    rationale: "Depende de um app subir contra o broker real; a prova em runtime fica para o smoke do plano 01-06"

duration: 6min
completed: 2026-10-12
status: complete
---

# Phase 1 Plan 02: Topologia RabbitMQ e modelo de persistência Summary

**Bean único `Declarables` com 3 exchanges, 15 filas e 15 bindings (DLX, TTL Integer por nível configurável e DLQ), `Jackson2JsonMessageConverter` único sobre o `ObjectMapper` do Boot, record `NotificationDocument` com histórico embutido e `attemptCount` monotônico, e três índices nomeados criados por `createIndex`**

## Performance

- **Duration:** 6 min
- **Started:** 2026-10-12T18:45:42Z
- **Completed:** 2026-10-12T18:51:56Z
- **Tasks:** 2
- **Files modified:** 12

## Accomplishments
- `NotifyTopology.declarables` monta toda a topologia a partir de `Channel` (D-05). A fila principal faz dead-letter para `notify.dlx` com `<canal>.dlq`. As filas de delay têm `x-message-ttl` Integer e voltam para `notify.exchange` por `notification.<canal>`. As DLQs não têm argumentos. Todas as filas são duráveis e nenhuma tem argumento de CQv1.
- `notify.topology.retry-levels` controla os níveis: o padrão gera `.5s/.25s/.125s`, `1s,5s,25s` gera as filas do demo e `200ms,500ms,1s` gera rótulos em ms. Uma lista não crescente derruba o contexto com `IllegalArgumentException`.
- `NotifyCommonConfiguration` está pronta para `@Import` e expõe `notifyDeclarables`, `notifyMessageConverter` e `mongoIndexInitializer`. Não declara `RabbitAdmin`, `RabbitTemplate` nem factory de listener.
- O `NotificationDocument` cobre todos os campos de D-03, com `newPending(...)` e a classe `Fields` para queries. O mapeamento real confirma `_id`, enums como string, `attempts` como array e ida e volta sem perda. Uma chave com ponto lança `MappingException`, o que justifica a regex de chave do plano 01-04.
- `MongoIndexInitializer.createIndexes()` cria os três índices na ordem ESR, com `_id` como desempate. O common foi reinstalado em `~/.m2` (71 testes verdes), e API e worker continuam compilando contra ele.

## Task Commits

1. **Task 1: Topologia ponta a ponta (tracer)**: `7a878b8` (test, RED) e `7973d52` (feat, GREEN)
2. **Task 2: Documento notifications, histórico e índices**: `a86963e` (test, RED) e `d2434a0` (feat, GREEN)

**Plan metadata:** commit do SUMMARY (docs)

## Files Created/Modified
- `notify-common/.../messaging/TopologyProperties.java`: record `@ConfigurationProperties("notify.topology")` com validação
- `notify-common/.../messaging/NotifyTopology.java`: constantes de exchange e a fábrica do `Declarables`
- `notify-common/.../NotifyCommonConfiguration.java`: configuração compartilhada (topologia, conversor, inicializador de índices)
- `notify-common/.../persistence/NotificationDocument.java`: documento `notifications`, `newPending` e `Fields`
- `notify-common/.../persistence/AttemptEntry.java`, `LastError.java`: records embutidos
- `notify-common/.../persistence/MongoIndexInitializer.java`: criação idempotente dos índices nomeados
- `notify-common/pom.xml`: `spring-web` em escopo de teste
- 4 classes de teste (26 testes novos)

## Decisions Made
- O `ObjectMapper` do Boot é injetado sem fallback. Se ele faltar, o contexto falha de forma visível em vez de serializar `Instant` em outro formato.
- O `TopologyProperties` também recusa níveis abaixo de 1ms e níveis acima de `Integer.MAX_VALUE` ms, porque o TTL vai como Integer.
- As chaves dos índices usam `NotificationDocument.Fields`, e um teste confere que essas constantes batem com as chaves gravadas.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] `spring-web` em escopo de teste no notify-common**
- **Found during:** Task 1 (GREEN)
- **Issue:** O `NotifyCommonConfigurationTest` falhou com `No qualifying bean of type ObjectMapper`. O `JacksonAutoConfiguration` só cria o `ObjectMapper` quando `Jackson2ObjectMapperBuilder` (spring-web) está no classpath. Na API e no worker ele existe (`spring-boot-starter-web`), mas no classpath de teste do common não existia.
- **Fix:** Adicionei `org.springframework:spring-web` (versão gerenciada) com `<scope>test</scope>` no `notify-common/pom.xml`. O código de produção continua exigindo o `ObjectMapper` do Boot, como o plano pede.
- **Files modified:** notify-common/pom.xml
- **Verification:** os 5 testes do `NotifyCommonConfigurationTest` passaram (hoje são 6)
- **Committed in:** 7973d52

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Só afeta o classpath de teste. Não muda o comportamento de produção nem o escopo.

Nota de processo TDD: nas duas tarefas o RED foi falha de compilação por falta das classes (`cannot find symbol`), a mesma convenção do plano 01-01. Os commits `test(01-02)` vêm antes dos `feat(01-02)`. O teste da lista inválida foi reforçado depois do primeiro GREEN: agora confere que a causa raiz é a `IllegalArgumentException` de "estritamente crescente", e não outra falha qualquer de startup.

## Issues Encountered
- `./mvnw -q` não imprime a contagem de testes. Confirmei a execução nos relatórios do surefire (`tests=... failures=0`), o que descarta "No tests matching pattern".

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- `notify-common` completo está em `~/.m2`. Os planos 01-04 (API) e 01-05 (worker) podem fazer `@Import(NotifyCommonConfiguration.class)` e chamar `MongoIndexInitializer.createIndexes()` nos seus runners de startup.
- Os dois apps precisam usar os mesmos `notify.topology.retry-levels` (o profile `demo` usa `1s,5s,25s` em ambos).
- A topologia no painel e os índices no Mongo só são provados em runtime no plano 01-06.

---
*Phase: 01-entrega-ponta-a-ponta-no-caminho-feliz*
*Completed: 2026-10-12*

## Self-Check: PASSED
- Os 11 arquivos de key-files.created e o pom modificado existem no disco
- Os commits 7a878b8, 7973d52, a86963e e d2434a0 estão no histórico
- `./mvnw -q -pl notify-common install` verde (71 testes, 0 falhas); `./mvnw -q -pl notify-api,notify-worker compile` verde
- Todos os critérios de aceitação das Tasks 1 e 2 passaram (grep/teste)
