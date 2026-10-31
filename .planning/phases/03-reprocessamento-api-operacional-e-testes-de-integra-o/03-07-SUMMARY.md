---
phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o
plan: 07
subsystem: api
tags: [springdoc, openapi, swagger-ui, api-key, testcontainers, regression]

requires:
  - phase: 03-01
    provides: harness notify-it (EndToEndSupport com get/mongo/allQueues/readyCount)
  - phase: 03-03
    provides: POST /admin/dlq/{canal}/reprocess com Report {requested, reprocessed, skipped, discarded}
  - phase: 03-05
    provides: POST /admin/notifications/republish-failed com RepublishReport e 503 republish-interrupted
  - phase: 03-06
    provides: GET /notifications (lista) e GET /notifications/stats no NotificationQueryController
provides:
  - "springdoc-openapi-starter-webmvc-ui 2.8.17 no notify-api: GET /v3/api-docs e GET /swagger-ui/index.html, abertos sem X-API-Key"
  - "OpenApiConfiguration: @OpenAPIDefinition (Notify Hub API v1, descrição em pt-BR) e @SecurityScheme adminApiKey (apiKey, header X-API-Key); constante ADMIN_API_KEY_SCHEME"
  - "@Tag/@Operation/@ApiResponse/@Parameter em pt-BR nos três controllers, com as respostas reais de cada operação e os erros como application/problem+json (ProblemDetail)"
  - "springdoc.override-with-generic-response: false e springdoc.default-produces-media-type: application/json"
  - "Profile demo da API: notify.admin.api-key ${NOTIFY_ADMIN_API_KEY:demo-admin-key}"
  - "ApiDocsIT com 4 testes contra a API real"
affects: [fase-4-readme, fase-4-compose]

actuals:
  tokens: 7766
  tasks: 2
  commits: 2
plan_head_before: b1205f5bb495171f1f0d0a750349b43ffa1f89f2

tech-stack:
  added: ["org.springdoc:springdoc-openapi-starter-webmvc-ui 2.8.17"]
  patterns:
    - "Documentação OpenAPI só por anotações io.swagger.v3.oas.annotations; o comportamento dos endpoints não muda"
    - "Respostas declaradas por operação com @ApiResponse e override-with-generic-response desligado, para o advice de erros não espalhar respostas genéricas"
    - "Prova da doc no IT (API real com Rabbit e Mongo), nunca num @SpringBootTest do notify-api, para o ./mvnw test seguir sem Docker"

key-files:
  created:
    - notify-api/src/main/java/dev/notifyhub/api/web/OpenApiConfiguration.java
    - notify-it/src/test/java/dev/notifyhub/it/ApiDocsIT.java
  modified:
    - notify-api/pom.xml
    - notify-api/src/main/resources/application.yml
    - notify-api/src/main/resources/application-demo.yml
    - notify-api/src/main/java/dev/notifyhub/api/admin/AdminController.java
    - notify-api/src/main/java/dev/notifyhub/api/web/NotificationController.java
    - notify-api/src/main/java/dev/notifyhub/api/web/NotificationQueryController.java

key-decisions:
  - "03-07: springdoc fixado em 2.8.17 (CLAUDE.md), sem sobrescrever nenhuma versão do BOM do Boot; a doc fica pública e o esquema adminApiKey só documenta, a proteção segue no interceptor de /admin/**"
  - "03-07: respostas de erro documentadas como application/problem+json com schema ProblemDetail; o 202 do POST declara Location e Idempotent-Replayed, e o 503 declara Retry-After"
  - "03-07: springdoc.default-produces-media-type: application/json para lista e stats não aparecerem como */* (só documentação); ApiDocsIT garante que o POST não documenta um 200 inferido"
  - "03-07: chave da demo demo-admin-key só no profile demo; o profile base continua vazio e fail closed"

patterns-established:
  - "Toda operação nova da API precisa de @Operation (summary pt-BR), @Tag na classe e @ApiResponse com os códigos que realmente devolve; o ApiDocsIT confere os conjuntos"

requirements-completed: [API-08]

coverage:
  - id: D1
    description: "GET /v3/api-docs responde 200 sem X-API-Key e documenta as 6 operações dos 5 caminhos (POST/GET /notifications, GET /notifications/{id}, GET /notifications/stats, POST /admin/dlq/{canal}/reprocess, POST /admin/notifications/republish-failed), com info.title Notify Hub API e o header Idempotency-Key no POST"
    requirement: API-08
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/ApiDocsIT.java#apiDocsListEveryEndpointAndTheAdminKeyScheme"
        status: pass
    human_judgment: false
  - id: D2
    description: "Esquema adminApiKey (apiKey, header, X-API-Key) em components.securitySchemes, exigido só pelas duas operações /admin/**; nenhuma operação de /notifications declara segurança"
    requirement: API-08
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/ApiDocsIT.java#apiDocsListEveryEndpointAndTheAdminKeyScheme"
        status: pass
    human_judgment: false
  - id: D3
    description: "GET /swagger-ui/index.html responde 200 em text/html com o Swagger UI"
    requirement: API-08
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/ApiDocsIT.java#swaggerUiIsServed"
        status: pass
    human_judgment: false
  - id: D4
    description: "Cada operação tem summary e tag em pt-BR e declara as respostas reais (POST 202/400/422/503, GET id 200/404, lista 200/400, stats 200, reprocess e republish-failed 200/400/401/503); o POST não documenta 200"
    requirement: API-08
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/ApiDocsIT.java#everyOperationHasPtBrSummaryTagAndItsRealResponses"
        status: pass
    human_judgment: false
  - id: D5
    description: "Edge de concorrência: duas leituras simultâneas de /v3/api-docs respondem 200 com os mesmos 5 caminhos, e nenhuma fila nem documento muda"
    requirement: API-08
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/ApiDocsIT.java#apiDocsAreReadOnlyAndStableAcrossConcurrentReads"
        status: pass
    human_judgment: false
  - id: D6
    description: "Regressão final da fase: ./mvnw verify com 590 unitários e os 8 ITs (33 testes) verdes, smokes das Fases 2 e 1 OK sobre os -exec.jar e ./mvnw test verde"
    verification:
      - kind: other
        ref: "./mvnw -q verify (exit 0, 0 linhas [ERROR]; common 81, api 153, worker 356; ApiDocsIT 4, DeliveryGuaranteesIT 3, HarnessSmokeIT 2, IdempotencyIT 5, ListAndStatsIT 5, ReprocessEdgeCasesIT 7, ReprocessFlowIT 3, UnroutablePublishIT 4)"
        status: pass
      - kind: e2e
        ref: "bash scripts/smoke-fase2.sh --sem-build && bash scripts/smoke-fase1.sh --sem-build (SMOKE FASE 2: OK, SMOKE FASE 1: OK)"
        status: pass
      - kind: other
        ref: "./mvnw -q test (exit 0, 0 linhas [ERROR])"
        status: pass
    human_judgment: false
  - id: D7
    description: "Legibilidade do Swagger UI em pt-BR e o fluxo do botão Authorize com demo-admin-key no reprocess (200 com a chave, 401 Problem Details sem ela)"
    requirement: API-08
    verification: []
    human_judgment: true
    rationale: "A legibilidade da doc para quem avalia o portfólio e o fluxo do botão Authorize do Swagger UI não são verificáveis por asserção de JSON (human-check da Task 2)"

duration: 18min
completed: 2026-10-31
status: complete
---

# Phase 3 Plan 07: Swagger UI (API-08) e regressão final da fase Summary

**A API serve o Swagger UI do springdoc 2.8.17 (`/swagger-ui/index.html`) e o `/v3/api-docs`, abertos e sem chave. A documentação cobre as 6 operações com tags, resumos e parâmetros em pt-BR e declara as respostas reais de cada operação, com os erros em `application/problem+json`. O esquema `adminApiKey` (`X-API-Key` em header) aparece só nas duas operações `/admin/**`. O profile `demo` ganhou a chave documentada `demo-admin-key`. A fase fecha com `./mvnw verify` verde (590 unitários e 33 ITs) e com os smokes das Fases 1 e 2 OK.**

## Performance

- **Duração:** 18 min
- **Início:** 2026-10-31T17:47:42Z
- **Fim:** 2026-10-31T18:06:00Z
- **Tarefas:** 2 (2 commits)
- **Arquivos alterados:** 8 (2 criados, 6 alterados)

## Accomplishments

- **Dependência:** `springdoc-openapi-starter-webmvc-ui` 2.8.17 com versão explícita, porque não está no BOM do Boot. O comentário em pt-BR explica por que a linha fica na 2.8.x enquanto o projeto estiver no Boot 3.5.
- **`OpenApiConfiguration`:**
  - `@OpenAPIDefinition` com título `Notify Hub API`, versão `v1` e uma descrição em pt-BR que cobre criação, consulta, `Idempotency-Key` e as operações admin com `X-API-Key`.
  - `@SecurityScheme adminApiKey`.
  - Não é `WebMvcConfigurer`, então as fatias `@WebMvcTest` não a carregam.
- **Anotações nos controllers:**
  - Tags `Notificações`, `Consultas` e `Administração`.
  - `@Operation` com as descrições que o plano pede: no POST, 202 + `Location`, replay com `Idempotent-Replayed` e 503 com `Retry-After`; no reprocess, o orçamento min(max, prontas), as quatro contagens e a parada na primeira falha; no republish, a ordem do mais antigo para o mais novo.
  - `@ApiResponse` com schema (`CreateNotificationResponse`, `NotificationView`, `Report`, `RepublishReport`, `ProblemDetail`). Headers documentados no 202 e no 503.
  - `@Parameter` em `Idempotency-Key`, `id`, `canal`, `max`, `status`, `channel`, `page` e `size`.
- **YAML:**
  - `override-with-generic-response: false` e `default-produces-media-type: application/json`.
  - O profile `demo` tem `notify.admin.api-key: ${NOTIFY_ADMIN_API_KEY:demo-admin-key}`, mantendo `retry-levels`.
- **`ApiDocsIT`:** 4 testes contra a API real, todos verdes.
- **Regressão da fase:**
  - `./mvnw -q verify`: exit 0, 0 linhas `[ERROR]`.
  - Os smokes das Fases 2 e 1 terminaram OK sobre os `-exec.jar` recém-empacotados.
  - `./mvnw -q test`: verde.

## Task Commits

1. **Task 1 (tracer): `/v3/api-docs` e Swagger UI com todos os endpoints e o esquema `X-API-Key` só no admin:** `5ecf458` (feat).
   - Gate do tracer: execução interativa, `end-of-phase`, `<verify>` só automático. `./mvnw -q -pl notify-api test` e `./mvnw -q -pl notify-it -am verify -Dit.test=ApiDocsIT` (3/3) passaram exatamente sobre a árvore que foi commitada. A expansão seguiu sem checkpoint.
2. **Task 2: Swagger em pt-BR com as respostas reais, chave da demo e regressão completa:** `2ac8118` (feat).

**Plan metadata:** commit `docs(03-07)` logo após este SUMMARY.

## Files Created/Modified

- `notify-api/pom.xml`: dependência springdoc 2.8.17
- `notify-api/.../web/OpenApiConfiguration.java` (novo): metadados OpenAPI e esquema `adminApiKey`
- `notify-api/.../admin/AdminController.java`: `@SecurityRequirement`, `@Tag`, `@Operation`/`@ApiResponse`/`@Parameter`
- `notify-api/.../web/NotificationController.java`: `@Tag`, documentação do POST (headers 202/503) e do GET por id
- `notify-api/.../web/NotificationQueryController.java`: `@Tag`, documentação da lista e do stats, parâmetros com valores e limites do D-10
- `notify-api/src/main/resources/application.yml`: bloco `springdoc`
- `notify-api/src/main/resources/application-demo.yml`: chave admin da demo
- `notify-it/.../ApiDocsIT.java` (novo): 4 testes

## Decisions Made

- **Erros como Problem Details na doc.** Os 400/401/404/422/503 declaram `application/problem+json` com o schema `ProblemDetail`, o mesmo formato que o `ApiExceptionHandler` devolve. Assim o leitor do Swagger vê o contrato real.
- **`default-produces-media-type: application/json`.** Sem isso, as respostas 200 da lista e do stats, que não têm `@Content` explícito, apareciam como `*/*`. A propriedade só afeta a documentação. Pôr `produces` no `@GetMapping` mudaria a negociação de conteúdo, e o plano proíbe mudar o comportamento.
- **O POST não documenta 200.** Inspecionei o JSON gerado: o springdoc não inferiu um 200 ao lado do 202 declarado. O `ApiDocsIT` fixa isso.

## Deviations from Plan

### Ajustes de execução

**1. [Documentação extra] `default-produces-media-type` e asserção contra o 200 do POST**
- **Found during:** Task 2, ao inspecionar o `/v3/api-docs` real (dump temporário, removido antes do commit)
- **Issue:** lista e stats saíam com o media type `*/*`. Não chega a ser bug, mas a doc fica menos legível.
- **Fix:** propriedade `springdoc.default-produces-media-type: application/json` e uma asserção no `everyOperationHasPtBrSummaryTagAndItsRealResponses` de que `POST /notifications` não tem resposta `200`.
- **Files modified:** `application.yml`, `ApiDocsIT.java`
- **Committed in:** `2ac8118`

**2. [Processo] Commits em `main`:** `branching_strategy: none` e instrução do orquestrador. Os commits rodaram com hooks, e o pin de raiz (#4254) rodou antes da primeira escrita e antes de cada commit.

**3. [Precondição] Portas dos smokes:** 5672, 15672 e 27017 estavam ocupadas pelos containers do próprio compose do projeto (`notify-hub-rabbitmq-1`, `notify-hub-mongo-1`), que são exatamente a infra que os smokes usam. 8080 e 8081 estavam livres. Os dois smokes passaram.

---

**Total deviations:** 3 (1 de documentação extra, 2 de processo/ambiente)
**Impact on plan:** nenhum no escopo; o comportamento dos endpoints não mudou.

## Issues Encountered

- Os avisos de LF → CRLF do Git aparecem em todo commit (Windows) e são inofensivos.

## Known Stubs

Nenhum. O dump temporário do `/v3/api-docs` foi removido do teste antes do commit (o grep por `TEMP`, `TODO`, `FIXME` e `placeholder` nos arquivos do plano não acha nada).

## Threat Flags

Nenhuma superfície nova fora do `<threat_model>`.
- T-03-SC2 (`mitigate`): versão exata 2.8.17, conferida no jar executável. Nenhuma versão do BOM do Boot foi sobrescrita.
- T-03-29, T-03-30 e T-03-31 são `accept`: doc pública sem segredos, `demo-admin-key` só no profile demo, chave do "Authorize" só no navegador.

## User Setup Required

Nenhum. A chave da demo é `demo-admin-key`, e `NOTIFY_ADMIN_API_KEY` sobrescreve.

## Next Phase Readiness

- **Fase 3 completa** (7/7 planos). Falta a verificação humana de fim de fase (human-check da Task 2): com a infra no ar e a API subida com `--spring.profiles.active=demo`, abrir `http://localhost:8080/swagger-ui/index.html`, clicar em "Authorize", informar `demo-admin-key` e executar `POST /admin/dlq/{canal}/reprocess` com `canal=email` e `max=1`. O esperado: as três tags com resumos em pt-BR, o cadeado só nas operações admin, 200 com `{requested, reprocessed, skipped, discarded}` com a chave e 401 Problem Details sem ela.
- A Fase 4 (README/compose) pode citar `demo-admin-key` e o Swagger UI na porta 8080. Os Dockerfiles devem copiar `*-exec.jar`.

## Self-Check: PASSED

- Os arquivos criados existem: `OpenApiConfiguration.java` e `ApiDocsIT.java`.
- Os commits `5ecf458` e `2ac8118` estão no histórico (2 = `git rev-list --count b1205f5..HEAD`).
- Os critérios de aceite das duas tarefas e os `key_links` deram PASS (grep).
- Verificação do plano:
  - `./mvnw -q -pl notify-it -am verify -Dit.test=ApiDocsIT`: verde (4/4).
  - `./mvnw -q verify`: verde (590 unitários; 33 ITs em 8 classes).
  - Smokes das Fases 2 e 1 com `--sem-build`: `SMOKE FASE 2: OK` e `SMOKE FASE 1: OK`.
  - `./mvnw -q test`: verde.

---
*Phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o*
*Completed: 2026-10-31*
