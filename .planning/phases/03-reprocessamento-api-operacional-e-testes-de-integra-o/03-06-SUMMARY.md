---
phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o
plan: 06
subsystem: api
tags: [spring-data-mongodb, pagination, projection, aggregation, group, enummap, problem-details, testcontainers]

requires:
  - phase: 03-01
    provides: harness notify-it (EndToEndSupport com get/mongo e limpeza entre testes)
  - phase: 03-02
    provides: ApiExceptionHandler.invalidRequest(List<FieldProblem>) e handleTypeMismatch com o nome do parâmetro
  - phase: 03-04
    provides: Fields.RECIPIENT e Fields.TEMPLATE_CODE, MongoIndexInitializer com 4 índices
provides:
  - "Índice status_createdAt (status ASC, createdAt DESC, _id DESC) no MongoIndexInitializer, que também atende o republish-failed"
  - "NotificationQueryService.list(status, channel, page, size) -> PageResponse<NotificationSummary> com count antes de skip/limit, skip em long e projeção dos campos do resumo"
  - "NotificationQueryService.stats() -> NotificationStats por uma única agregação $group"
  - "NotificationQueryController: GET /notifications?status=&channel=&page=&size= e GET /notifications/stats; DEFAULT_PAGE_SIZE 20, MAX_PAGE_SIZE 100"
  - "DTOs NotificationSummary (+ LastErrorSummary(code)), PageResponse<T>(content, page, size, totalElements, totalPages) e NotificationStats(total, byStatus, byChannel, byChannelAndStatus)"
  - "ListAndStatsIT com 5 testes contra o MongoDB real"
affects: [03-07, fase-4-readme, fase-4-k6]

actuals:
  tokens: 14342
  tasks: 2
  commits: 4
plan_head_before: 9629ec1789e28a411c83700321519eb315c862bc

tech-stack:
  added: []
  patterns:
    - "Consulta projetada lida direto num record de leitura (NotificationSummary) cujos componentes têm os nomes dos campos gravados; todo componente primitivo precisa estar na projeção"
    - "Página além do total responde vazia sem find: poupa a consulta e evita o skip truncado para int no Spring Data (QueryCursorPreparer faz l2i)"
    - "Parâmetros de query como String/Integer opcionais, validados no controller e acumulados num único invalidRequest"
    - "Stats: EnumMap canal -> status pré-preenchido com zeros, somatórios derivados do mapa cruzado"

key-files:
  created:
    - notify-api/src/main/java/dev/notifyhub/api/application/NotificationQueryService.java
    - notify-api/src/main/java/dev/notifyhub/api/web/NotificationQueryController.java
    - notify-api/src/main/java/dev/notifyhub/api/web/dto/NotificationSummary.java
    - notify-api/src/main/java/dev/notifyhub/api/web/dto/PageResponse.java
    - notify-api/src/main/java/dev/notifyhub/api/web/dto/NotificationStats.java
    - notify-api/src/test/java/dev/notifyhub/api/web/NotificationQueryControllerTest.java
    - notify-it/src/test/java/dev/notifyhub/it/ListAndStatsIT.java
  modified:
    - notify-common/src/main/java/dev/notifyhub/common/persistence/MongoIndexInitializer.java
    - notify-common/src/test/java/dev/notifyhub/common/persistence/MongoIndexInitializerTest.java

key-decisions:
  - "03-06: a listagem lê o resultado projetado direto como NotificationSummary (find(query, NotificationSummary.class, notifications)); ler como NotificationDocument com a projeção falha no int attemptCount, e NotificationSummary.from(NotificationDocument) saiu"
  - "03-06: página com skip >= total responde vazia sem find; o Spring Data 4.5 trunca o skip para int, então page=2147483647&size=100 daria 500"
  - "03-06: stats ignora com WARN linha de status/canal desconhecido ou count não numérico; mapas EnumMap na ordem de declaração dos enums (contrato D-12 da Fase 4)"

patterns-established:
  - "Leitura operacional num controller próprio (NotificationQueryController) com o mesmo prefixo /notifications; rotas literais têm precedência sobre /{id}"

requirements-completed: [API-05, API-07]

coverage:
  - id: D1
    description: "GET /notifications devolve {content, page, size, totalElements, totalPages}, ordenado por createdAt desc e _id desc, com filtro por igualdade em status e channel (vazio = sem filtro) e totalElements contado antes de skip/limit"
    requirement: API-05
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/ListAndStatsIT.java#listFiltersPaginatesAndSortsByCreatedAtDesc"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/web/NotificationQueryControllerTest.java (withoutParametersListsTheFirstPageOfTwentyWithTheEnvelope, statusChannelPageAndSizeBecomeFilterSkipAndLimit, emptyStatusAndChannelMeanNoFilter, totalIsCountedBeforeSkipAndLimit, emptyCollectionHasZeroPages)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Cada item é um resumo (id, status, channel, recipient, templateCode, retryCount, lastError.code, createdAt, updatedAt); a consulta projeta só esses campos e variables, rendered, attempts, idempotencyKey e requestHash nunca aparecem"
    requirement: API-05
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/web/NotificationQueryControllerTest.java (projectionIncludesExactlyTheSummaryFields, itemIsASummaryWithOnlyTheLastErrorCodeAndNoContentNorHistory)"
        status: pass
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/ListAndStatsIT.java#listFiltersPaginatesAndSortsByCreatedAtDesc"
        status: pass
    human_judgment: false
  - id: D3
    description: "page/size/status/channel inválidos (size 0/101/-1/abc, page -1/abc, status FOO/sent, channel email/PUSH) dão 400 validation-error com o campo e os valores aceitos, sem consultar o Mongo; vários erros saem num único 400; size 1 e 100 aceitos"
    requirement: API-05
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/web/NotificationQueryControllerTest.java (invalidParameterIs400WithTheFieldAndNeverQueriesMongo x10, invalidStatusListsTheAcceptedValues, invalidChannelListsTheAcceptedValues, outOfRangePageAndSizeExplainTheLimits, severalInvalidParametersComeInASingle400, sizeAtTheLimitsIsAccepted x2)"
        status: pass
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/ListAndStatsIT.java#invalidQueryParametersAre400"
        status: pass
    human_judgment: false
  - id: D4
    description: "Skip em long: page=2147483647&size=100 vira skip 214748364700 e devolve página vazia com o totalElements real (200, não 500)"
    requirement: API-05
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/web/NotificationQueryControllerTest.java#deepPageUsesALongSkipAndAnswersAnEmptyPageWithTheRealTotal"
        status: pass
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/ListAndStatsIT.java#deepPageBeyondTheIntRangeIsEmptyWithTheRealTotal"
        status: pass
    human_judgment: false
  - id: D5
    description: "Índice status_createdAt (status ASC, createdAt DESC, _id DESC) criado pelo MongoIndexInitializer, idempotente, depois dos 4 existentes"
    requirement: API-05
    verification:
      - kind: unit
        ref: "notify-common/src/test/java/dev/notifyhub/common/persistence/MongoIndexInitializerTest.java (6 casos, statusCreatedAtIndexServesTheStatusOnlyFilter)"
        status: pass
      - kind: other
        ref: "./mvnw -q verify (todos os ITs sobem com os 5 índices)"
        status: pass
    human_judgment: false
  - id: D6
    description: "GET /notifications/stats devolve {total, byStatus, byChannel, byChannelAndStatus} com os 7 status, os 3 canais e as 21 combinações sempre presentes (zeros inclusive), numa única agregação $group; valores desconhecidos ignorados sem 500; a rota não cai no GET por id"
    requirement: API-07
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/web/NotificationQueryControllerTest.java (statsCountsByStatusChannelAndBothWithZerosAndIgnoresUnknownValues, statsKeysComeInEnumDeclarationOrder, statsWithoutRowsIsAllZeros, statsRunsASingleGroupStageByStatusAndChannel)"
        status: pass
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/ListAndStatsIT.java (statsCountsByStatusAndChannelWithZeros, statsOnAnEmptyCollectionIsAllZerosAndNotMistakenForAnId)"
        status: pass
    human_judgment: false

duration: 167min
completed: 2026-10-31
status: complete
---

# Phase 3 Plan 06: Listagem paginada e stats (API-05, API-07) Summary

**`GET /notifications` lista com filtros por status e canal e pagina com `page`/`size` (padrão 20, máximo 100). A ordem é `createdAt` desc com desempate por `_id`, e o envelope é próprio, `{content, page, size, totalElements, totalPages}`. Cada item é um resumo projetado no Mongo, sem conteúdo nem histórico. Parâmetros inválidos saem num único 400 Problem Details. `GET /notifications/stats` traz `{total, byStatus, byChannel, byChannelAndStatus}` com todos os zeros, numa única agregação `$group`. O novo índice `status_createdAt` cobre o filtro só por status.**

## Performance

- **Duração:** 167 min de relógio, incluindo uma pausa longa por limite de uso no meio da Task 1 (GREEN)
- **Início:** 2026-10-31T14:55:01Z
- **Fim:** 2026-10-31T17:42:44Z
- **Tarefas:** 2 (4 commits: RED + GREEN em cada uma)
- **Arquivos alterados:** 9 (7 criados, 2 alterados)

## Accomplishments

- **Índice `status_createdAt`:** (`status` ASC, `createdAt` DESC, `_id` DESC). É o 5º do `MongoIndexInitializer`. O Javadoc explica por que o `status_channel_createdAt` não serve sem igualdade em `channel` (Armadilha 5) e por que o `republish-failed` usa o mesmo índice percorrido ao contrário.
- **`NotificationQueryService.list`:**
  - O filtro usa igualdade nos enums já validados.
  - O `count` roda antes de skip/limit, e o skip é calculado em long (`(long) page * size`).
  - A projeção traz só `status`, `channel`, `recipient`, `templateCode`, `retryCount`, `lastError.code`, `createdAt` e `updatedAt`, e o resultado é lido direto como `NotificationSummary`.
  - Uma página além do total sai vazia sem `find`.
- **`NotificationQueryController`:**
  - Valida `status`/`channel` pelo nome exato do enum (minúsculo é inválido, vazio = sem filtro), `page ≥ 0` e `size` em 1..100.
  - Junta todos os erros num `ApiExceptionHandler.invalidRequest`, com a lista de valores aceitos.
  - `page=abc` passa pelo `handleTypeMismatch` existente.
- **`GET /notifications/stats`:**
  - Faz `$group` por `status` + `channel` com `count` e preenche um `EnumMap` canal → status com as 21 combinações a zero.
  - `byStatus`, `byChannel` e `total` saem do mapa cruzado.
  - Uma linha desconhecida gera log WARN e é ignorada.
  - A rota literal tem precedência sobre `/{id}`.
- **Testes:**
  - `NotificationQueryControllerTest`: 28 casos, sem Docker.
  - `MongoIndexInitializerTest`: 6 casos.
  - `ListAndStatsIT`: 5 testes contra o Mongo real.

## Task Commits

1. **Task 1 (tracer): `GET /notifications` lista, filtra e pagina contra o Mongo real**
   - RED `7ab68f8` (test). Todas as falhas foram de asserção:
     - `MongoIndexInitializerTest`: 6/6 falharam (4 chamadas em vez de 5).
     - `NotificationQueryControllerTest`: 22/24 falharam. `page=abc` e `size=abc` já davam 400 pelo `handleTypeMismatch` e ficaram como caracterização.
     - `ListAndStatsIT`: 3/3 falharam.
   - GREEN `294a6a5` (feat). Gate do tracer: execução interativa, `end-of-phase`, `<verify>` só automático. Os dois comandos do verify passaram no código commitado, e a expansão seguiu sem checkpoint.
2. **Task 2: `GET /notifications/stats` com todos os zeros numa única agregação**
   - RED `2843fe9` (test): 4 testes unitários e 2 ITs de stats falharam por asserção.
   - GREEN `f72c99c` (feat).

**Plan metadata:** commit `docs(03-06)` logo após este SUMMARY.

## Files Created/Modified

- `notify-common/.../persistence/MongoIndexInitializer.java`: índice `status_createdAt` e Javadoc
- `notify-api/.../application/NotificationQueryService.java` (novo): `list` e `stats`
- `notify-api/.../web/NotificationQueryController.java` (novo): `GET /notifications` e `GET /notifications/stats`, validação, constantes
- `notify-api/.../web/dto/NotificationSummary.java` (novo): item resumido + `LastErrorSummary(code)`, também usado como tipo de leitura da consulta projetada
- `notify-api/.../web/dto/PageResponse.java` (novo): envelope da página
- `notify-api/.../web/dto/NotificationStats.java` (novo): contagens D-12
- Testes: `MongoIndexInitializerTest` (5 índices, +1 caso), `NotificationQueryControllerTest` (28, novo), `ListAndStatsIT` (5, novo)

## Decisions Made

- **O resultado projetado é lido como `NotificationSummary`.** Os componentes do record têm os nomes dos campos gravados (`id` ↔ `_id`, `lastError.code` aninhado), então o Spring Data monta o resumo direto do documento projetado.
- **Página além do total não consulta o Mongo.** Esse caso também cobre `page=2147483647&size=100` sem truncar o skip.
- **Stats tolerante.** Status ou canal desconhecido e `count` que não é número viram log WARN, nunca 500. A ordem das chaves segue a declaração dos enums, e o `statsKeysComeInEnumDeclarationOrder` fixa isso para o README.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] A projeção não podia ser lida como `NotificationDocument`**
- **Found during:** Task 1 (GREEN), pelo `ListAndStatsIT` contra o Mongo real
- **Issue:** o plano manda `mongo.find(query, NotificationDocument.class)` mapeado por `NotificationSummary::from`. Com a projeção do resumo, o `attemptCount` (um `int` no construtor canônico do record) fica de fora, e o Spring Data lança `MappingInstantiationException: Parameter attemptCount must not be null`. Resultado: 500 em toda listagem com documentos.
- **Fix:** `mongo.find(query, NotificationSummary.class, Collections.NOTIFICATIONS)`. A projeção continua exatamente a do truth (incluir `attemptCount` violaria "projeta só esses campos"). `NotificationSummary.from(NotificationDocument)` saiu, porque não teria uso. O `NotificationQueryControllerTest` passou a simular `find(query, NotificationSummary.class, "notifications")`.
- **Files modified:** `NotificationQueryService.java`, `NotificationSummary.java`, `NotificationQueryControllerTest.java`
- **Verification:** `ListAndStatsIT` 3/3 e fatia 24/24 verdes
- **Committed in:** `294a6a5`

**2. [Rule 1 - Bug] Skip acima de `Integer.MAX_VALUE` truncado pelo Spring Data**
- **Found during:** Task 1 (antes do RED), lendo o bytecode do `MongoTemplate$QueryCursorPreparer` (spring-data-mongodb 4.5.13 faz `l2i` antes de `FindIterable.skip(int)`)
- **Issue:** o truth pede que `page=2147483647&size=100` devolva página vazia com o total real. O skip em long chegaria ao driver como -100 e daria erro (500).
- **Fix:** com `skip >= total`, a página sai vazia sem `find`. A Query ainda leva o skip em long. O teste unitário confere 214748364700 na Query, e um IT extra (`deepPageBeyondTheIntRangeIsEmptyWithTheRealTotal`) prova o 200 contra o Mongo real.
- **Files modified:** `NotificationQueryService.java`, `NotificationQueryControllerTest.java`, `ListAndStatsIT.java`
- **Committed in:** `7ab68f8` (testes), `294a6a5` (código)

### Ajustes de execução

**3. [Processo] Commits em `main`**: `branching_strategy: none` e instrução do orquestrador. Os commits rodaram com hooks, e o pin de raiz (#4254) rodou antes da primeira escrita e antes de cada commit.

**4. [Teste extra] Casos além do `<behavior>`:** `size=-1`, `size=abc`, a mensagem com os valores aceitos, o count antes de skip/limit, a ordem das chaves do stats e o IT da página profunda. Todos estão nos commits RED.

---

**Total deviations:** 4 (2 auto-fixed Rule 1, 1 de processo, 1 de cobertura extra)
**Impact on plan:** as duas correções eram necessárias para os truths valerem contra o Mongo real. Sem mudança de escopo.

## Issues Encountered

- A execução foi interrompida por limite de uso durante o GREEN da Task 1 e retomada sem refazer nada: o RED `7ab68f8` já estava commitado, e o GREEN ainda não.
- Um heredoc com o código dos testes quebrou o parser do shell. O script passou para um arquivo no scratchpad.
- Os avisos de LF → CRLF do Git aparecem em todo commit (Windows) e são inofensivos.

## TDD Gate Compliance

Os 2 gates RED (`test(03-06)`: `7ab68f8`, `2843fe9`) vêm antes dos 2 gates GREEN (`feat(03-06)`: `294a6a5`, `f72c99c`), em ordem. Não houve REFACTOR. O RED usa esqueletos que compilam (serviço devolvendo página e stats vazios, controller sem validação), para a falha vir de asserção e não de compilação. O checador `tdd-red-evidence` lê saída TAP de Node e não se aplica ao Surefire/Failsafe. A evidência do RED está nas mensagens dos commits RED.

## Known Stubs

Nenhum. Os esqueletos do RED foram substituídos nos GREEN. O grep por `Esqueleto`, `TODO` e `FIXME` nos arquivos de produção do plano não acha nada.

## Threat Flags

Nenhuma superfície nova fora do `<threat_model>`. Os `mitigate` têm teste:
- T-03-26: `size` 1..100 com 400, skip em long sem 500 e índice `status_createdAt`.
- T-03-27: parse para enum antes da `Criteria`; `status=FOO`/`sent` e `channel=email`/`PUSH` dão 400 sem tocar no Mongo.

T-03-25 (`recipient` na listagem) e T-03-28 (stats sem cache) são `accept` e vão para o README na Fase 4.

## User Setup Required

Nenhum. Bases existentes ganham o índice `status_createdAt` no próximo startup da API ou do worker.

## Next Phase Readiness

- O 03-07 (smoke/demo) pode usar `GET /notifications/stats` para conferir contagens e `GET /notifications?status=...` para listar.
- A Fase 4 (README/k6) tem o formato D-12 fixado por teste, chaves e ordem incluídas. O `explain()` do filtro só por status (DOC-02) deve mostrar `status_createdAt` sem SORT.

## Self-Check: PASSED

- Os 7 arquivos criados existem em disco.
- Os commits `7ab68f8`, `294a6a5`, `2843fe9` e `f72c99c` estão no histórico (4 = `git rev-list --count 9629ec1..HEAD`).
- Os critérios de aceite das 2 tarefas e os `key_links` do plano deram PASS (grep e comandos).
- Verificação do plano:
  - `./mvnw -q -pl notify-common install && ./mvnw -q -pl notify-api test`: verde (81 common, 153 api).
  - `./mvnw -q -pl notify-it -am verify -Dit.test=ListAndStatsIT`: verde (5/5).
  - `./mvnw -q verify`: exit 0, 0 linhas `[ERROR]`, 590 unitários (81 common, 153 api, 356 worker) e 29 ITs (`DeliveryGuaranteesIT` 3, `HarnessSmokeIT` 2, `IdempotencyIT` 5, `ListAndStatsIT` 5, `ReprocessEdgeCasesIT` 7, `ReprocessFlowIT` 3, `UnroutablePublishIT` 4).

---
*Phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o*
*Completed: 2026-10-31*
