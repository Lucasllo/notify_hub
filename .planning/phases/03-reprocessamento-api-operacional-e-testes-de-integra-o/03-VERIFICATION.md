---
phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o
verified: 2026-10-31T18:30:00Z
status: gaps_found
score: 65/69 must-haves verified
covered_files:
  - .planning/REQUIREMENTS.md
  - .planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/deferred-items.md
  - .planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-01-PLAN.md
  - .planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-01-SUMMARY.md
  - .planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-02-PLAN.md
  - .planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-02-SUMMARY.md
  - .planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-03-PLAN.md
  - .planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-03-SUMMARY.md
  - .planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-04-PLAN.md
  - .planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-04-SUMMARY.md
  - .planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-05-PLAN.md
  - .planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-05-SUMMARY.md
  - .planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-06-PLAN.md
  - .planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-06-SUMMARY.md
  - .planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-07-PLAN.md
  - .planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-07-SUMMARY.md
  - notify-api/pom.xml
  - notify-api/src/main/java/dev/notifyhub/api/admin/AdminController.java
  - notify-api/src/main/java/dev/notifyhub/api/admin/AdminProperties.java
  - notify-api/src/main/java/dev/notifyhub/api/admin/AdminWebConfiguration.java
  - notify-api/src/main/java/dev/notifyhub/api/admin/DlqReprocessService.java
  - notify-api/src/main/java/dev/notifyhub/api/admin/ReprocessInterruptedException.java
  - notify-api/src/main/java/dev/notifyhub/api/application/IdempotencyKeyReusedException.java
  - notify-api/src/main/java/dev/notifyhub/api/application/NotificationQueryService.java
  - notify-api/src/main/java/dev/notifyhub/api/application/NotificationRepublisher.java
  - notify-api/src/main/java/dev/notifyhub/api/application/NotificationResetService.java
  - notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java
  - notify-api/src/main/java/dev/notifyhub/api/application/RepublishInterruptedException.java
  - notify-api/src/main/java/dev/notifyhub/api/application/RequestHasher.java
  - notify-api/src/main/java/dev/notifyhub/api/messaging/NotificationPublisher.java
  - notify-api/src/main/java/dev/notifyhub/api/web/ApiExceptionHandler.java
  - notify-api/src/main/java/dev/notifyhub/api/web/NotificationController.java
  - notify-api/src/main/java/dev/notifyhub/api/web/NotificationQueryController.java
  - notify-api/src/main/java/dev/notifyhub/api/web/OpenApiConfiguration.java
  - notify-api/src/main/java/dev/notifyhub/api/web/ProblemTypes.java
  - notify-api/src/main/java/dev/notifyhub/api/web/dto/NotificationStats.java
  - notify-api/src/main/java/dev/notifyhub/api/web/dto/NotificationSummary.java
  - notify-api/src/main/java/dev/notifyhub/api/web/dto/PageResponse.java
  - notify-api/src/main/resources/application-demo.yml
  - notify-api/src/main/resources/application.yml
  - notify-api/src/test/java/dev/notifyhub/api/admin/AdminControllerTest.java
  - notify-api/src/test/java/dev/notifyhub/api/admin/DlqReprocessFlowTest.java
  - notify-api/src/test/java/dev/notifyhub/api/admin/MongoBson.java
  - notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java
  - notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java
  - notify-api/src/test/java/dev/notifyhub/api/application/RequestHasherTest.java
  - notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java
  - notify-api/src/test/java/dev/notifyhub/api/web/NotificationQueryControllerTest.java
  - notify-api/src/test/java/dev/notifyhub/api/web/NotificationValidationTest.java
  - notify-common/src/main/java/dev/notifyhub/common/domain/AttemptOutcome.java
  - notify-common/src/main/java/dev/notifyhub/common/domain/NotificationStatus.java
  - notify-common/src/main/java/dev/notifyhub/common/persistence/MongoIndexInitializer.java
  - notify-common/src/main/java/dev/notifyhub/common/persistence/NotificationDocument.java
  - notify-common/src/test/java/dev/notifyhub/common/domain/NotificationStatusTest.java
  - notify-common/src/test/java/dev/notifyhub/common/persistence/MongoIndexInitializerTest.java
  - notify-common/src/test/java/dev/notifyhub/common/persistence/NotificationDocumentMappingTest.java
  - notify-it/pom.xml
  - notify-it/src/test/java/dev/notifyhub/it/ApiDocsIT.java
  - notify-it/src/test/java/dev/notifyhub/it/DeliveryGuaranteesIT.java
  - notify-it/src/test/java/dev/notifyhub/it/HarnessSmokeIT.java
  - notify-it/src/test/java/dev/notifyhub/it/IdempotencyIT.java
  - notify-it/src/test/java/dev/notifyhub/it/ListAndStatsIT.java
  - notify-it/src/test/java/dev/notifyhub/it/ReprocessEdgeCasesIT.java
  - notify-it/src/test/java/dev/notifyhub/it/ReprocessFlowIT.java
  - notify-it/src/test/java/dev/notifyhub/it/UnroutablePublishIT.java
  - notify-it/src/test/java/dev/notifyhub/it/support/EndToEndSupport.java
  - notify-it/src/test/resources/it/application-it.yml
  - notify-worker/pom.xml
  - notify-worker/src/main/java/dev/notifyhub/worker/metrics/DeliveryMetrics.java
  - pom.xml
  - scripts/smoke-fase1.sh
  - scripts/smoke-lib.sh
covered_digest: "v1:sha256:f955d45b2b4e2a27b9e56fd250aa955e1a133fdc8ad77879ef0dc37b6b6bd402"
behavior_unverified: 1
overrides_applied: 0
gaps:
  - truth: "Goal da fase: nenhuma notificação fica presa sem saída. Todo documento que não está SENT tem um caminho de recuperação (worker, escada de retry, DLQ + reprocess ou republish-failed), e a API nunca confirma com 202 uma notificação que não chegou ao broker"
    status: failed
    reason: "CR-01 do 03-REVIEW (não confundir com o CR-01 da Fase 2, que está resolvido). Quando o publish volta sem ACK e o updateFirst de markPublishFailed lança DataAccessException, ou quando a JVM cai entre o insert e o confirm, o documento fica PENDING sem mensagem em fila nenhuma. Nenhum caminho o enxerga: o republish-failed filtra só status = PUBLISH_FAILED, o reprocess só age sobre mensagens que estão na DLQ, e a API não tem varredura (nenhum @Scheduled em notify-api). A Fase 3 piora o caso de duas formas: (1) o replay com a mesma Idempotency-Key acha o PENDING órfão com o mesmo hash e responde 202 Idempotent-Replayed sem publicar (NotificationService.replayOrRethrow, linhas 122-128), ou seja, confirma ao cliente uma notificação que nunca será entregue; (2) o republish (lote admin ou replay D-08) faz PUBLISH_FAILED → PENDING antes do publish, então uma falha do Mongo no markPublishFailed tira do conjunto PUBLISH_FAILED um documento que estava recuperável, e o 503 do lote afirma que 'as que faltaram continuam PUBLISH_FAILED'."
    artifacts:
      - path: "notify-api/src/main/java/dev/notifyhub/api/application/NotificationRepublisher.java"
        issue: "publishOrMarkFailed (115-128) não trata DataAccessException de markPublishFailed (199-214), nem loga o id; republish (134-152) tira o documento de PUBLISH_FAILED antes do publish sem caminho de volta se o Mongo falhar depois"
      - path: "notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java"
        issue: "replayOrRethrow (122-128) responde 202 Idempotent-Replayed para qualquer PENDING, inclusive um PENDING antigo que nunca teve confirm"
      - path: "notify-api/src/main/java/dev/notifyhub/api/application/RepublishInterruptedException.java"
        issue: "o detail do 503 (33-34) afirma que as notificações restantes continuam PUBLISH_FAILED, o que é falso para o documento órfão"
    missing:
      - "Saída para PENDING nunca confirmado: varredura agendada que leva PENDING sem claim e com updatedAt mais antigo que um múltiplo do confirm-timeout a PUBLISH_FAILED (lastError PUBLISH_UNCONFIRMED), ou incluir esse PENDING antigo na busca do republish-failed. PUBLISH_FAILED continua reivindicável pelo worker, então não há perda se a mensagem tiver chegado"
      - "No replay, um PENDING com updatedAt anterior ao cutoff segue o caminho do replayPublishFailed (republish cercado por status = PENDING + updatedAt lido) em vez de responder 202 direto"
      - "Log ERROR com o id da notificação quando markPublishFailed lançar, para o órfão ser rastreável"
      - "Teste unitário: publish NACK seguido de updateFirst lançando DataAccessResourceFailureException, na criação e no republish; e o detail do 503 do lote corrigido"
behavior_unverified_items:
  - truth: "03-03 (verification: backstop): se o processo da API cai no meio de um reprocess, toda entrega obtida por basicGet e ainda sem ack volta para a DLQ quando o canal fecha, e nenhum documento fica PENDING sem mensagem por causa da queda"
    test: "Com notificações DEAD_LETTERED na DLQ de e-mail, chamar POST /admin/dlq/email/reprocess?max=100 e matar a JVM da API (kill -9) no meio da chamada"
    expected: "A DLQ volta a ter as mensagens não confirmadas (0 sem ack depois que a conexão fecha); um documento resetado sem publish confirmado fica PENDING com REPROCESSED e é retomado pelo próximo reprocess até SENT"
    why_human: "Truth backstop (não inferível): depende do requeue do broker quando o canal fecha. Presença e wiring não provam isso, e nenhum teste mata a JVM no meio da chamada"
coincidental_reliance_items:
  - truth: "Edge TST-02 (concurrency): cada IT começa com as 15 filas purgadas e notifications vazia, por isso as contagens exatas da DLQ não herdam mensagens de outro teste"
    reason: incidental-ordering
    harden: "O resetState() espera só readyCount; uma entrega do worker ainda sem ack pode publicar um token ou uma cópia na DLQ depois do purge (IN-07). Incluir unackedCount == 0 na espera, ou aguardar o status final em todo teste que cria notificações"
human_verification:
  - test: "Com a infra no ar e a API com --spring.profiles.active=demo, abrir http://localhost:8080/swagger-ui/index.html, clicar em Authorize, informar demo-admin-key e executar POST /admin/dlq/{canal}/reprocess com canal=email e max=1"
    expected: "Três tags (Notificações, Consultas, Administração) com resumos em pt-BR; cadeado só nas duas operações admin; 200 com {requested, reprocessed, skipped, discarded} com a chave e 401 Problem Details sem ela"
    why_human: "Legibilidade da doc e fluxo do botão Authorize não são verificáveis por asserção de JSON (human-check do 03-07)"
  - test: "Rodar scripts/smoke-fase1.sh e scripts/smoke-fase2.sh, que sobem os -exec.jar"
    expected: "Terminam com SMOKE FASE 1: OK e SMOKE FASE 2: OK"
    why_human: "Os smokes sobem a infra e duas JVMs; o verificador não sobe serviços. A afirmação vem só do SUMMARY do 03-07"
  - test: "Matar a JVM da API no meio de um reprocess (item backstop acima)"
    expected: "Nenhuma mensagem perdida; documentos resetados retomados pelo próximo reprocess"
    why_human: "Backstop sem teste"
---

# Fase 3: Reprocessamento, API operacional e testes de integração — Relatório de verificação

**Goal da fase:** Nenhuma notificação fica presa sem saída: o operador reprocessa a DLQ e republica PUBLISH_FAILED com segurança, o cliente consulta, filtra e reenvia com idempotência, e uma suíte de integração com Testcontainers prova automaticamente o fluxo retry → DLQ → reprocessamento → SENT
**Verificado em:** 2026-10-31T18:30:00Z
**Status:** gaps_found
**Re-verificação:** Não. É a verificação inicial.

Nota de processo: a fase está marcada `Mode: mvp`, mas o Goal não está no formato de user story (`user-story.validate` → `valid: false`). Segui o mesmo caminho das Fases 1 e 2: verificação goal-backward padrão, sem a tabela "User Flow Coverage". Para ter o enquadramento formal, rode `/gsd mvp-phase 3`.

## Resumo

Os quatro critérios de sucesso do ROADMAP estão entregues e provados por testes reais. Rodei `./mvnw -B verify` uma vez no HEAD (`e5069b0`): BUILD SUCCESS, exit 0, 590 unitários (81 common + 153 api + 356 worker) e 33 ITs em 8 classes, todos verdes contra `rabbitmq:4.3.6-management` e `mongo:8.0.32` do Testcontainers.

O que falha é a frase-título do goal, "nenhuma notificação fica presa sem saída". O CR-01 do code review existe no código como descrito. Um PENDING que nunca teve confirm não tem nenhum caminho de recuperação, e a idempotência desta fase o transforma em um 202 enganoso. O gap tem uma causa só e uma correção pequena, descrita abaixo.

## Goal Achievement

### Verdades observáveis: critérios do ROADMAP e goal

| # | Verdade | Status | Evidência |
|---|---------|--------|-----------|
| SC1 | Com notificações na DLQ, `POST /admin/dlq/{canal}/reprocess?max=N` com `X-API-Key` válida devolve até N mensagens à fila principal. Cada notificação recomeça com o contador zerado, mantém o `attempts[]` anterior com um evento REPROCESSED e chega a SENT | ✓ VERIFIED | `ReprocessFlowIT.retryLadderEndsInTheDlqAndReprocessDeliversSent`: 4 TRANSIENT_FAILURE → DLQ → 200 `{10,1,0,0}` → SENT com `retryCount` 0, `attempts` = 4 falhas + REPROCESSED(`number` 0, cita DEAD_LETTERED) + SUCCESS(5), `attemptCount` 5, DLQ vazia. `maxLimitsHowManyMessagesOneCallConsumes` prova o limite N. Código: `DlqReprocessService.drain/handle` (basicGet → reset → publish confirmado → ack), `NotificationResetService` (`findAndModify` cercado, `$push` com `$slice -50`) |
| SC2 | Chamadas admin sem chave ou com chave errada são recusadas sem mover mensagens. Canal inválido e `max` fora do limite dão Problem Details. `republish-failed` reenvia PUBLISH_FAILED até SENT | ✓ VERIFIED | `ReprocessFlowIT.rejectedAdminCallsNeverMoveTheDlq` (401 ×2, 400 `canal`/`max` ×4, DLQ com 1 pronta e 0 sem ack, documento intacto). `AdminWebConfiguration.ApiKeyInterceptor`: SHA-256 + `MessageDigest.isEqual`, fail closed. `UnroutablePublishIT.unroutablePublishBecomesPublishFailedAndIsRepublishedByTheOperator` → 200 `{100,1}` → SENT |
| SC3 | Mesma `Idempotency-Key` e mesmo payload devolvem a original sem duplicar; payload diferente dá 422. `GET /notifications` filtra e pagina por `createdAt` desc. `/stats` traz contagens. O Swagger documenta todos os endpoints | ✓ VERIFIED | `IdempotencyIT` (5 testes: replay, sem chave, 422, corrida de 8 POSTs com um documento só, chave inválida). Índice `idempotencyKey_unique` parcial no `MongoIndexInitializer`. `ListAndStatsIT` (5). `ApiDocsIT.apiDocsListEveryEndpointAndTheAdminKeyScheme` exige exatamente os 5 caminhos / 6 operações e o header `Idempotency-Key` |
| SC4 | `./mvnw verify` roda via Failsafe ITs com Testcontainers que provam retry → DLQ → reprocess → SENT, venenosa sem loop, publish não roteável → PUBLISH_FAILED e duplicata concorrente com um envio. `./mvnw test` continua sem Docker | ✓ VERIFIED | Execução própria: 33 ITs verdes. `DeliveryGuaranteesIT` (venenosa: `x-death.count` 1 e estável por 1s; duplicata: 5 cópias, `attemptCount` 1, delta 1 nas métricas). `UnroutablePublishIT` (binding removido → 503 + PUBLISH_FAILED). Sem Docker no `test`: o Failsafe só existe no `notify-it`, o `notify-it` não tem `*Test`, e nenhum módulo de unitários referencia Testcontainers |
| G | **Goal: nenhuma notificação fica presa sem saída** | ✗ FAILED | Documento PENDING órfão sem nenhuma saída, e replay idempotente responde 202 sem publicar. Ver "Gaps" |

### Verdades dos planos (must_haves)

| Plano | Verdades | Verificadas | Observações |
|-------|----------|-------------|-------------|
| 03-01 harness e `exec` | 6 | 5 | Harness, configuração por contexto, limpeza e Failsafe foram verificados pelo código e pelo `HarnessSmokeIT` (2 testes). Os jars `-exec` existem no POM (classifier `exec` no api e no worker). **Os smokes das Fases 1 e 2 não foram executados aqui** (? UNCERTAIN, item humano) |
| 03-02 reprocess + X-API-Key | 15 | 15 | `DlqReprocessFlowTest` cobre cada ramo: ordem reset → publish → ack, fence, orçamento, skipped/discarded, 503 com motivo fechado, nack no `finally` (`unexpectedExceptionRequeuesTheDeliveryAndReachesTheCallerUnchanged`) e Mongo fora. `AdminControllerTest` cobre max/canal/chave. `DeliveryMetrics` não tem série `reprocessed` |
| 03-03 CR-01 da Fase 2 + ITs | 13 | 12 | `ReprocessEdgeCasesIT` (7) e `DeliveryGuaranteesIT` (3). Tabela `NotificationStatus` com PENDING/RETRYING/PROCESSING → PENDING. `deferred-items.md` está com CR-01 `status: resolved`. A verdade **backstop** (queda da API no meio do reprocess) ficou como `insufficient_spec`, item humano |
| 03-04 Idempotency-Key | 9 | 9 | `IdempotencyIT` + `RequestHasherTest` + `NotificationServiceTest`. Regex `^[\x21-\x7E]{1,255}$` no controller. `DuplicateKeyException` sem documento é relançada |
| 03-05 republish-failed + D-08 | 7 | 7 | `UnroutablePublishIT` (4) e `NotificationRepublisherTest`. As verdades como escritas se cumprem; o caso de Mongo fora dentro do `markPublishFailed` não está em nenhuma delas e é o gap do goal |
| 03-06 lista e stats | 7 | 7 | `NotificationQueryService`: skip em long, projeção só do resumo, `$group` único, 3×7 zeros. Índice `status_createdAt`. `ListAndStatsIT.deepPageBeyondTheIntRangeIsEmptyWithTheRealTotal` |
| 03-07 Swagger + regressão | 7 | 6 | springdoc 2.8.17 em `notify-api/pom.xml`. `ApiDocsIT` (4) cobre caminhos, segurança só no admin, respostas reais e leituras concorrentes. `application-demo.yml` com `${NOTIFY_ADMIN_API_KEY:demo-admin-key}`. O `verify` passou na execução própria; a parte dos smokes ficou UNCERTAIN |

**Placar:** 65/69 verdades verificadas (4 SC + 1 goal + 64 de planos). 1 FAILED (goal), 1 backstop sem evidência (`insufficient_spec`), 2 UNCERTAIN (smokes não executados).

### Artefatos obrigatórios

| Artefato | Esperado | Status | Detalhes |
|----------|----------|--------|----------|
| `notify-api/.../admin/DlqReprocessService.java` | Reprocess basicGet → reset → publish → ack | ✓ VERIFIED | 303 linhas, ligado ao `AdminController`, exercitado por IT |
| `notify-api/.../application/NotificationResetService.java` | Reset atômico com REPROCESSED | ✓ VERIFIED | `findAndModify` com fence + `$or` de lease |
| `notify-api/.../admin/AdminWebConfiguration.java` | X-API-Key em tempo constante, fail closed | ✓ VERIFIED | Interceptor em `/admin/**` |
| `notify-api/.../application/NotificationRepublisher.java` | Republish de PUBLISH_FAILED | ⚠️ VERIFIED com gap | Funciona no caminho feliz e no de falha do broker; não trata Mongo fora no `markPublishFailed` (CR-01) |
| `notify-api/.../application/NotificationService.java` | Replay e 422 por Idempotency-Key | ⚠️ VERIFIED com gap | Replay de PENDING órfão responde 202 (CR-01) |
| `notify-api/.../web/NotificationQueryController.java` + `NotificationQueryService.java` | Lista e stats | ✓ VERIFIED | — |
| `notify-api/.../web/OpenApiConfiguration.java` + springdoc 2.8.17 | Swagger UI | ✓ VERIFIED | — |
| `notify-common/.../MongoIndexInitializer.java` | `idempotencyKey_unique` e `status_createdAt` | ✓ VERIFIED | — |
| `notify-it/` (8 ITs + `EndToEndSupport`) | Suíte Failsafe com Testcontainers | ✓ VERIFIED | 33 testes verdes |

### Verificação de ligações (key links)

| De | Para | Via | Status |
|----|------|-----|--------|
| `AdminController.reprocess` | `DlqReprocessService.reprocess` | chamada direta após validar canal/max | WIRED |
| `DlqReprocessService.handle` | `NotificationResetService.resetForReprocess` → `NotificationPublisher.publish` | reset antes do publish, ack depois do confirm | WIRED |
| `AdminController.republishFailed` | `NotificationRepublisher.republishFailed` | chamada direta | WIRED |
| `NotificationService.replayOrRethrow` | `NotificationRepublisher.republish` | só para PUBLISH_FAILED (D-08) | WIRED (PENDING antigo não é encaminhado; ver gap) |
| `NotificationController` (header `Idempotency-Key`) | `NotificationService.create` | validação de formato e depois serviço | WIRED |
| `NotificationQueryController` | `NotificationQueryService` | `list` / `stats` | WIRED |
| `ApiKeyInterceptor` | `/admin/**` | `addPathPatterns` | WIRED |

### Rastreamento de dados (nível 4)

| Artefato | Dado | Fonte | Dado real | Status |
|----------|------|-------|-----------|--------|
| `GET /notifications` | `content`, `totalElements` | `mongo.count` + `mongo.find` com projeção | Sim (`ListAndStatsIT`) | ✓ FLOWING |
| `GET /notifications/stats` | contagens | `$group` em `notifications` | Sim | ✓ FLOWING |
| Resposta do reprocess | `{requested, reprocessed, skipped, discarded}` | `Tally` acumulado no loop real | Sim (`ReprocessFlowIT`) | ✓ FLOWING |

### Verificações comportamentais

| Comportamento | Comando | Resultado | Status |
|---------------|---------|-----------|--------|
| Suíte completa (uma execução) | `./mvnw -B verify` | BUILD SUCCESS, exit 0; unitários 81/153/356; ITs 33, 0 falhas | ✓ PASS |
| Enumeração de ITs | `grep -c @Test notify-it/.../*IT.java` | 4+3+2+5+5+7+3+4 = 33 | ✓ PASS |
| Unitários sem Docker | `grep -rl testcontainers notify-{api,common,worker}/src` | nenhum resultado | ✓ PASS |
| Ausência de saída para PENDING órfão | `grep -rn "@Scheduled\|EnableScheduling" notify-api/src/main` | nenhum resultado | ✗ confirma o CR-01 |
| Smokes das Fases 1 e 2 | `scripts/smoke-fase{1,2}.sh` | não executados (sobem serviços) | ? SKIP → humano |

### Execução de probes

Não há `scripts/*/tests/probe-*.sh` nem probes declarados nos planos da fase. Não se aplica.

### Cobertura de requisitos

| Requisito | Plano | Descrição | Status | Evidência |
|-----------|-------|-----------|--------|-----------|
| API-03 | 03-04, 03-05 | Idempotency-Key: replay e 422 | ✓ SATISFIED (com ressalva) | `IdempotencyIT`, `UnroutablePublishIT.replay*`. Ressalva: o replay de PENDING órfão dá 202 (gap do goal) |
| API-05 | 03-06 | Lista paginada por `createdAt` desc | ✓ SATISFIED | `ListAndStatsIT` |
| API-07 | 03-06 | Stats por status e canal | ✓ SATISFIED | `ListAndStatsIT` |
| API-08 | 03-07 | Swagger UI springdoc 2.8.x | ✓ SATISFIED (visual → humano) | `ApiDocsIT` |
| ADM-01 | 03-02, 03-03 | Reprocess basicGet/reset/republish/ack, para na 1ª falha | ✓ SATISFIED | `ReprocessFlowIT`, `ReprocessEdgeCasesIT`, `DlqReprocessFlowTest` |
| ADM-02 | 03-02 | Zera contador, mantém histórico, evento REPROCESSED | ✓ SATISFIED | `ReprocessFlowIT` (attempts 4+1+1, `retryCount` 0) |
| ADM-03 | 03-05 | republish-failed | ✓ SATISFIED (com ressalva) | `UnroutablePublishIT`. Ressalva: Mongo fora no `markPublishFailed` gera órfão (gap) |
| ADM-04 | 03-02 | X-API-Key em tempo constante, valida canal e max | ✓ SATISFIED | `AdminControllerTest`, `ReprocessFlowIT.rejectedAdminCallsNeverMoveTheDlq` |
| TST-02 | 03-03 | IT retry → DLQ → reprocess → SENT | ✓ SATISFIED | `ReprocessFlowIT` |
| TST-03 | 03-03, 03-05 | Venenosa, não roteável, duplicata concorrente | ✓ SATISFIED | `DeliveryGuaranteesIT`, `UnroutablePublishIT` |
| TST-04 | 03-01 | ITs via Failsafe, separados | ✓ SATISFIED | `notify-it` só com `*IT`, Failsafe |

Os 11 IDs da fase aparecem no `requirements:` de algum plano. A tabela de rastreabilidade de REQUIREMENTS.md não mapeia nenhum outro ID para a Fase 3, então não há requisito órfão.

### Anti-padrões e achados do code review

Nenhum marcador `TBD`/`FIXME`/`XXX`/`TODO`/`HACK` nos 56 arquivos alterados pela fase.

| Achado | Arquivo | Severidade | Classificação neste relatório |
|--------|---------|------------|-------------------------------|
| CR-01: PENDING órfão sem saída + replay 202 | `NotificationRepublisher.java`, `NotificationService.java` | 🛑 Blocker | **Gap** (ver abaixo) |
| WR-01: documento ilegível trava a cabeça da DLQ | `DlqReprocessService.java:188-193`, `NotificationResetService.java:82-86` | ⚠️ Warning | Limitação, não gap. Confirmado no código: só `DataAccessException` é tratada, e qualquer outra exceção leva nack com requeue para a cabeça e 500 (comportamento fixado pelo próprio `unexpectedExceptionRequeuesTheDeliveryAndReachesTheCallerUnchanged`). O gatilho, porém, é um documento corrompido ou gravado fora da aplicação (enum desconhecido, `status` nulo), e nenhum fluxo do sistema produz esse estado. Nenhuma mensagem se perde: ela continua na DLQ e o operador consegue movê-la pela UI do RabbitMQ. Correção barata e recomendada: tratar como "documento ilegível" e copiar para o fim (`skipped`) |
| WR-02: perdedores da corrida respondem 202 antes do confirm do vencedor | `NotificationService.java:109-128` | ⚠️ Warning | Não é perda: o vencedor recebe 503 e o documento fica PUBLISH_FAILED, visível no stats e recuperável pelo republish-failed. Tem a mesma raiz do CR-01 ("PENDING no replay não prova confirm"), e a correção do gap pode resolver os dois |
| WR-03: reset de não terminais zera `retryCount` e reabre a cerca de token obsoleto | `NotificationResetService.java:108-113` | ⚠️ Warning | Claims continuam exclusivos e não há SENT duplo, mas pode haver cadeias de retry paralelas e histórico duplicado. Documentar no DOC-03 ou não zerar `retryCount` em PENDING/RETRYING |
| WR-04: `GET /notifications` público expõe `recipient` em massa | `NotificationSummary.java`, `NotificationQueryService.java:80-81` | ⚠️ Warning (segurança) | Fora dos critérios do ROADMAP (D-11 decidiu incluir `recipient`). Recomendo mascarar com `RecipientRules.mask` ou registrar o risco no README |
| IN-01: text blocks com ~20 espaços no meio da descrição | `AdminController.java:79,120`, `NotificationController.java:84`, `NotificationQueryController.java:74,115` | ℹ️ Info | Confirmado no código; aparece no `/v3/api-docs` e afeta o item humano do Swagger |
| IN-02..IN-07 | vários | ℹ️ Info | Sem impacto no goal. IN-07 aparece em `coincidental_reliance_items` |

### Verificação humana necessária

Estes itens continuam válidos depois que o gap for fechado:

1. **Swagger UI e Authorize** (human-check do 03-07). Teste: perfil `demo`, `/swagger-ui/index.html`, Authorize com `demo-admin-key`, reprocess `email` com `max=1`. Esperado: três tags em pt-BR, cadeado só no admin, 200 com a chave e 401 sem ela. Motivo: legibilidade e fluxo de UI. Veja também o IN-01: as descrições hoje têm blocos de espaços.
2. **Smokes das Fases 1 e 2 com os `-exec.jar`.** Teste: `scripts/smoke-fase1.sh` e `scripts/smoke-fase2.sh`. Esperado: `SMOKE FASE 1: OK` e `SMOKE FASE 2: OK`. Motivo: sobem serviços; só o SUMMARY afirma o resultado.
3. **Queda da API no meio do reprocess** (backstop do 03-03). Teste: `kill -9` na API durante `reprocess?max=100`. Esperado: entregas sem ack voltam à DLQ e documentos resetados são retomados pelo próximo reprocess. Motivo: truth backstop sem teste.

## Gaps

### Gap 1 (BLOCKER): PENDING órfão sem saída e replay idempotente que confirma o que não foi publicado

Este é o CR-01 do `03-REVIEW.md`. Não é o CR-01 da Fase 2 em `deferred-items.md`, que está resolvido e provado pelo `ReprocessEdgeCasesIT`.

**O que confirmei lendo o código:**
- `NotificationService.create`: o insert grava PENDING. `publishOrMarkFailed` publica, e se não houver ACK chama `markPublishFailed`. Se esse `updateFirst` lançar `DataAccessException`, a exceção sobe sem tratamento: o cliente recebe 500 e o documento fica **PENDING sem mensagem**.
- Nenhum caminho de recuperação o alcança. O `republishFailed` busca só `status = PUBLISH_FAILED` (linha 169). O reprocess só lê a DLQ. Não há `@Scheduled` na API.
- O cliente faz o que o contrato manda e repete com a mesma `Idempotency-Key`. O `replayOrRethrow` acha o documento com o mesmo hash e status PENDING, que não é PUBLISH_FAILED, e responde **202 `Idempotent-Replayed: true` sem publicar**. A notificação passa a ser "aceita pela API" e nunca será entregue. É exatamente o que o Core Value proíbe ("nunca se perde em silêncio").
- `republish` (lote e D-08) grava PUBLISH_FAILED → PENDING antes do publish. Com broker e Mongo falhando em sequência, um documento recuperável vira órfão, e o 503 do lote diz o contrário.
- Nenhum teste derruba o Mongo dentro do `markPublishFailed`.

**Por que é gap e não limitação aceita.** Avaliei o argumento contrário. O projeto aceitou explicitamente a ausência de outbox (PROJECT.md, EVO-01, DOC-03 na Fase 4), e o 01-04 já registrava que "se a API cair entre o insert e o publish, o documento fica PENDING sem mensagem". A janela de escrita dupla em si é, então, um trade-off decidido. Mas o mesmo PROJECT.md diz que "publish falho → 503 + PUBLISH_FAILED + republish admin **fecha a lacuna da ausência de outbox**", e o goal desta fase promete que nada fica sem saída. Duas coisas não estão cobertas por nenhuma decisão registrada:
1. o replay idempotente (API-03, desta fase) responder 202 para um PENDING que nunca teve confirm. Isso contradiz o D-04 e a própria doc do POST ("202 depois do confirm do broker");
2. o republish desta fase tirar um documento do conjunto recuperável.

O D-01 descartou um sweeper de **lease**, que é outro problema. O WR-05 do review da Fase 1 já pedia "PENDING mais antigo que N minutos" no escopo do sweeper das Fases 2/3, e isso não foi feito. A Fase 4 só documenta trade-offs (DOC-03), não corrige este caso, por isso não o tratei como item adiado.

**Correção mínima sugerida** (frontmatter `gaps.missing`): uma varredura agendada PENDING antigo sem claim → PUBLISH_FAILED, e o replay de PENDING antigo encaminhado ao `replayPublishFailed`. É seguro porque PUBLISH_FAILED continua reivindicável pelo worker e o republish é cercado por status. Acrescentar log ERROR com o id e os testes unitários do NACK + Mongo fora.

**Se você decidir aceitar como limitação documentada**, por exemplo com o compromisso de registrá-la no DOC-03, adicione ao frontmatter:

```yaml
overrides:
  - must_have: "Goal da fase: nenhuma notificação fica presa sem saída"
    reason: "Janela de escrita dupla sem outbox (EVO-01). Falha dupla broker + Mongo, ou queda da JVM na janela do confirm, pode deixar PENDING órfão; o replay idempotente responde 202 nesse caso. Aceito como limitação, registrada no README (DOC-03)"
    accepted_by: "Lucas Lopes"
    accepted_at: "<ISO timestamp>"
```

Mesmo com o override, recomendo pelo menos a correção do replay (item 2 dos `missing`). Ela é local ao `NotificationService` e elimina o 202 enganoso, que é a parte "em silêncio" do problema.

---

_Verificado em: 2026-10-31T18:30:00Z_
_Verificador: Claude (gsd-verifier)_
