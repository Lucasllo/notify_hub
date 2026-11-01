---
phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o
verified: 2026-11-01T01:10:58Z
status: human_needed
score: 80/83 must-haves verified
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
  - .planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-08-PLAN.md
  - .planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-08-SUMMARY.md
  - .planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-09-PLAN.md
  - .planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-09-SUMMARY.md
  - .planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-10-PLAN.md
  - .planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-10-SUMMARY.md
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
  - notify-api/src/main/java/dev/notifyhub/api/application/PublishFailedException.java
  - notify-api/src/main/java/dev/notifyhub/api/application/RepublishInterruptedException.java
  - notify-api/src/main/java/dev/notifyhub/api/application/RequestHasher.java
  - notify-api/src/main/java/dev/notifyhub/api/application/UnconfirmedPublishSweeper.java
  - notify-api/src/main/java/dev/notifyhub/api/application/UnconfirmedSweepProperties.java
  - notify-api/src/main/java/dev/notifyhub/api/config/ApiConfiguration.java
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
  - notify-api/src/test/java/dev/notifyhub/api/application/UnconfirmedPublishSweeperTest.java
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
  - notify-it/src/test/java/dev/notifyhub/it/UnconfirmedPublishRecoveryIT.java
  - notify-it/src/test/java/dev/notifyhub/it/UnroutablePublishIT.java
  - notify-it/src/test/java/dev/notifyhub/it/support/EndToEndSupport.java
  - notify-it/src/test/resources/it/application-it.yml
  - notify-worker/pom.xml
  - notify-worker/src/main/java/dev/notifyhub/worker/metrics/DeliveryMetrics.java
  - pom.xml
  - scripts/smoke-fase1.sh
  - scripts/smoke-lib.sh
covered_digest: "v1:sha256:aec94e16c2e452c9a23a357252945ae92b7f1eb93652711142c544b9a3331224"
behavior_unverified: 1
overrides_applied: 0
re_verification:
  previous_status: gaps_found
  previous_score: 65/69
  gaps_closed:
    - "Goal da fase: nenhuma notificação fica presa sem saída. Todo documento que não está SENT tem um caminho de recuperação, e a API nunca confirma com 202 uma notificação que não chegou ao broker (Gap 1 / CR-01 do 03-REVIEW anterior; WR-02 fechado junto)"
  gaps_remaining: []
  regressions: []
behavior_unverified_items:
  - truth: "03-03 (verification: backstop): se o processo da API cai no meio de um reprocess, toda entrega obtida por basicGet e ainda sem ack volta para a DLQ quando o canal fecha, e nenhum documento fica PENDING sem mensagem por causa da queda"
    test: "Com notificações DEAD_LETTERED na DLQ de e-mail, chamar POST /admin/dlq/email/reprocess?max=100 e matar a JVM da API (kill -9) no meio da chamada"
    expected: "A DLQ volta a ter as mensagens não confirmadas (0 sem ack depois que a conexão fecha); um documento resetado sem publish confirmado fica PENDING com REPROCESSED e é retomado pelo próximo reprocess até SENT, ou, depois de 2m, a varredura do 03-08 o leva a PUBLISH_FAILED (PUBLISH_UNCONFIRMED)"
    why_human: "Truth backstop (não inferível): depende do requeue do broker quando o canal fecha. Presença e wiring não provam isso, e nenhum teste mata a JVM no meio da chamada"
coincidental_reliance_items:
  - truth: "Edge TST-02 (concurrency): cada IT começa com as 15 filas purgadas e notifications vazia, por isso as contagens exatas da DLQ não herdam mensagens de outro teste"
    reason: incidental-ordering
    harden: "O resetState() espera só readyCount; uma entrega do worker ainda sem ack pode publicar um token ou uma cópia na DLQ depois do purge (IN-07, ainda aberto). Incluir unackedCount == 0 na espera"
human_verification:
  - test: "Com a infra no ar e a API com --spring.profiles.active=demo, abrir http://localhost:8080/swagger-ui/index.html, clicar em Authorize, informar demo-admin-key e executar POST /admin/dlq/{canal}/reprocess com canal=email e max=1"
    expected: "Três tags (Notificações, Consultas, Administração) com resumos em pt-BR; cadeado só nas duas operações admin; 200 com {requested, reprocessed, skipped, discarded} com a chave e 401 Problem Details sem ela"
    why_human: "Legibilidade da doc e fluxo do botão Authorize não são verificáveis por asserção de JSON (human-check do 03-07). Ver IN-01: as descrições têm blocos de ~20 espaços"
  - test: "Rodar scripts/smoke-fase1.sh e scripts/smoke-fase2.sh, que sobem os -exec.jar"
    expected: "Terminam com SMOKE FASE 1: OK e SMOKE FASE 2: OK"
    why_human: "Os smokes sobem a infra e duas JVMs; o verificador não sobe serviços. A afirmação vem só do SUMMARY do 03-07"
  - test: "Matar a JVM da API no meio de um reprocess (item backstop acima)"
    expected: "Nenhuma mensagem perdida; documentos resetados retomados pelo próximo reprocess ou pela varredura"
    why_human: "Backstop sem teste"
  - test: "Resolver as 15 proibições (must_haves.prohibitions) dos planos 03-01..03-10, todas com status unresolved e flagged: true e sem campo verification (tier judgment). A tabela 'Proibições' deste relatório traz um veredito do verificador, não autoritativo, para cada uma"
    expected: "Para cada proibição, aceitar o veredito ou registrar a decisão. Atenção especial a três com ressalva: 03-09 #2 e 03-04 #1 (WR-05: cópia extra na fila em replay de PENDING confirmado e ainda não reivindicado) e 03-10 #1 (WR-07: replay responde e loga 'status PENDING' com o documento ainda PUBLISH_FAILED)"
    why_human: "Proibições judgment-tier exigem resolução humana explícita (ADR-550 D4). Nenhuma tem enforcement determinístico próprio, então não podem virar verde em silêncio"
---

# Fase 3: Reprocessamento, API operacional e testes de integração. Relatório de verificação

**Goal da fase:** Nenhuma notificação fica presa sem saída: o operador reprocessa a DLQ e republica PUBLISH_FAILED com segurança, o cliente consulta, filtra e reenvia com idempotência, e uma suíte de integração com Testcontainers prova automaticamente o fluxo retry → DLQ → reprocessamento → SENT
**Verificado em:** 2026-11-01T01:10:58Z
**Status:** human_needed
**Re-verificação:** Sim, depois do gap-closure (planos 03-08, 03-09 e 03-10)

Nota de processo: a fase está marcada `Mode: mvp`, mas o Goal não está no formato de user story (`user-story.validate` → `false`). Segui o mesmo caminho da verificação anterior: goal-backward padrão, sem a tabela "User Flow Coverage".

## Resumo

**O Gap 1 (CR-01) está fechado, e o WR-02 junto com ele.** Li o código, não os SUMMARYs:

- **PENDING órfão tem saída.** `UnconfirmedPublishSweeper` roda por `@Scheduled`, ligado pelo `@EnableScheduling` do `ApiConfiguration`. A cada execução, `NotificationRepublisher.markUnconfirmedAsPublishFailed` faz um único `updateMulti` com filtro `status = PENDING`, `updatedAt < corte` e `claimedAt` nulo, e grava PUBLISH_FAILED com o `lastError` PUBLISH_UNCONFIRMED. Daí o `republish-failed` leva a notificação até SENT. O `UnconfirmedPublishRecoveryIT` prova isso contra RabbitMQ e MongoDB reais: o órfão é varrido pela execução agendada, e não por uma chamada direta.
- **Todo 202 se apoia em evidência.** Tracei todos os caminhos que devolvem 202 em `NotificationService`:
  - criação: `publishOrMarkFailed` vazio, ou seja, confirm do broker ou releitura com `wasClaimedByWorker`;
  - replay: `wasClaimedByWorker`, ou `republish` → REPUBLISHED (confirm próprio), ou o publish sem fence do fim do laço, que também precisa do próprio confirm.
  Um PENDING dentro da janela espera (`awaitPublishWindow`) e não responde direto.
- **Mongo fora depois de um publish sem confirm:** gera log ERROR com o id e relança a exceção. Os details dos dois 503 (POST e lote) estão verdadeiros.

Execuções minhas:
- `./mvnw -pl notify-api -am test` com `UnconfirmedPublishSweeperTest`, `NotificationServiceTest`, `NotificationRepublisherTest` e `NotificationApiFlowTest`: exit 0 (3 + 21 + 16 + 11).
- `./mvnw -pl notify-it -am verify -Dit.test=UnconfirmedPublishRecoveryIT,IdempotencyIT,UnroutablePublishIT`: BUILD SUCCESS, 11 ITs, 0 falhas, contra `rabbitmq:4.3.6-management` e `mongo:8.0.32` (Docker 29.8.0).

A execução completa do orquestrador (`./mvnw -q verify`) é corroborada pelos relatórios em `target/`. Eles são das 21:56, depois do último commit de código (`96181a1`, 21:51), e não há código não commitado. Os números batem: surefire api 174, common 81, worker 356; failsafe 35 ITs em 9 classes, tudo verde.

Não encontrei nenhuma verdade FAILED. O status é `human_needed` por causa dos três itens humanos que continuam da verificação anterior (Swagger, smokes, backstop) e das 15 proibições judgment-tier, que pedem resolução explícita.

## Goal Achievement

### Verdades observáveis: critérios do ROADMAP e goal

| # | Verdade | Status | Evidência |
|---|---------|--------|-----------|
| SC1 | Reprocess da DLQ com `X-API-Key`: até N mensagens voltam, contador zerado, `attempts[]` mantido com REPROCESSED, chega a SENT | ✓ VERIFIED (regressão) | `DlqReprocessService`/`NotificationResetService` não mudaram desde `e5069b0`. `ReprocessFlowIT` (3) e `ReprocessEdgeCasesIT` (7) verdes na execução das 21:56 |
| SC2 | Admin sem chave ou com chave errada é recusado sem mover mensagens; canal/`max` inválidos dão Problem Details; `republish-failed` leva PUBLISH_FAILED até SENT | ✓ VERIFIED | `AdminWebConfiguration` não mudou. `UnroutablePublishIT` (4) verde na minha execução. O `republishFailed` continua buscando só `status = PUBLISH_FAILED` (`NotificationRepublisher.java:260`) |
| SC3 | Mesma `Idempotency-Key` com o mesmo payload devolve a original sem duplicar; payload diferente dá 422; lista/stats; Swagger completo | ✓ VERIFIED | `IdempotencyIT` (5, inclusive a corrida de 8 POSTs com um documento e um envio) verde na minha execução depois da reescrita do replay. 422 antes de qualquer leitura extra (`replayOrRethrow:138`). `ListAndStatsIT` (5) e `ApiDocsIT` (4) verdes; nenhum endpoint novo |
| SC4 | `./mvnw verify` roda os ITs via Failsafe (retry→DLQ→reprocess→SENT, venenosa, não roteável, duplicata concorrente); `./mvnw test` sem Docker | ✓ VERIFIED | 35 ITs em 9 classes (enumerados: 4+3+2+5+5+7+3+2+4). `grep -rl testcontainers` nos `src` de api/common/worker: nenhum resultado |
| G | **Goal: nenhuma notificação fica presa sem saída, e a API nunca confirma com 202 o que não chegou ao broker** | ✓ VERIFIED | Antes era FAILED. Ver a seção "Re-verificação do Gap 1" |

### Re-verificação do Gap 1 (itens `gaps.missing` da verificação anterior)

| Item pedido | Status | Evidência no código | Evidência por teste |
|-------------|--------|---------------------|---------------------|
| 1. Saída para PENDING nunca confirmado (varredura agendada → PUBLISH_FAILED/PUBLISH_UNCONFIRMED) | ✓ FECHADO | `UnconfirmedPublishSweeper.java:89-93` (`@Scheduled`), `ApiConfiguration.java:147` (`@EnableScheduling`), `NotificationRepublisher.java:301-314` (filtro `status`/`updatedAt $lt`/`claimedAt null`). O reset da DLQ faz `unset(claimedAt)` (`NotificationResetService.java:113`), então um PENDING resetado também é alcançado. Não há outro caminho que grave PENDING mantendo `claimedAt` | `UnconfirmedPublishRecoveryIT.unconfirmedPendingIsSweptToPublishFailedAndRepublishedToSent` (varredura agendada real, controle recente intacto, `republish-failed` → SENT); `UnconfirmedPublishSweeperTest.sweepMarksOnlyOldUnclaimedPendingAsPublishFailed` (filtro exato) |
| 2. Replay de PENDING com janela vencida segue o republish cercado em vez de 202 direto | ✓ FECHADO | `NotificationService.replay:170-206`; fence `status = PENDING` + `updatedAt` lido (`NotificationRepublisher.java:220-223`) | `UnconfirmedPublishRecoveryIT.replayOfAnUnconfirmedPendingRepublishesInsteadOfAnswering202` (202 replay → SENT, 1 documento); `NotificationServiceTest.replayOfAnUnconfirmedPending*` (3) |
| 3. Log ERROR com o id quando `markPublishFailed` lança | ✓ FECHADO | `NotificationRepublisher.java:175-182`: id + código + nome da classe, sem `getMessage()` nem destinatário | `nackThenMongoDownWhileMarkingPublishFailedLogsTheIdAndPropagates` (criação), `republishWithNackThenMongoDown...` |
| 4. Testes do NACK + Mongo fora e detail verdadeiro do 503 do lote | ✓ FECHADO | `RepublishInterruptedException.java:245-248`; `PublishFailedException.java:196-197` (não afirma o status gravado) | `batchWithNackThenMongoDownWhileMarkingPublishFailedIs503WithATrueDetail`, `NotificationApiFlowTest.unconfirmedPublishWhoseDocumentIsAlreadyPublishFailedAnswers503WithATrueDetail` |
| WR-02 (mesma raiz): o perdedor da corrida não responde 202 antes de o vencedor confirmar | ✓ FECHADO | `awaitPublishWindow:223-243` relê a cada 100ms até a janela da rodada | `replayOfAFreshPendingWhoseOriginalPublishFailsNeverAnswers202`, `replayOfAFreshPendingClaimedByTheWorker...WithoutPublishing`, `replayOfAFreshPendingStillUnconfirmedAtTheEndOfTheWindowRepublishes` |

### Verdades dos planos (must_haves)

| Plano | Verdades | Verificadas | Observações |
|-------|----------|-------------|-------------|
| 03-01 harness e `exec` | 6 | 5 | Sem mudança. Os smokes das Fases 1 e 2 continuam sem execução própria (? UNCERTAIN → humano) |
| 03-02 reprocess + X-API-Key | 15 | 15 | Arquivos sem mudança desde `e5069b0`; `DlqReprocessFlowTest` (28) e `AdminControllerTest` (34) verdes |
| 03-03 CR-01 da Fase 2 + ITs | 13 | 12 | Sem mudança. A verdade backstop continua `insufficient_spec` → humano |
| 03-04 Idempotency-Key | 9 | 9 | Replay reescrito no 03-09; `IdempotencyIT` verde depois disso |
| 03-05 republish-failed + D-08 | 7 | 7 | Transições preservadas (`UnroutablePublishIT` verde) |
| 03-06 lista e stats | 7 | 7 | Sem mudança |
| 03-07 Swagger + regressão | 7 | 6 | Os smokes continuam UNCERTAIN; `verify` completo verde |
| **03-08** varredura | 4 | 4 | (1) IT ponta a ponta; (2) `updateMulti` cercado, filtro exato no unitário, sem `leaseUntil`; (3) `afterShorterThanThreeConfirmTimeoutsFailsAtStartup` (10s recusado, 15s aceito com confirm de 5s) e `mongoDownDuringASweepIsLoggedAndReturnsZero`; (4) profile `it` com `after: 1h`/`interval: 200ms` e os 35 ITs verdes |
| **03-09** replay com evidência | 6 | 6 | Todos os caminhos de 202 traçados (ver resumo). Laço limitado a `MAX_REPLAY_ROUNDS = 3`. O lote continua só PUBLISH_FAILED. A verdade 4 ("cópia extra nunca gera segundo envio") se cumpre: só pode haver um SENT, porque o claim é exclusivo e SENT é terminal. O WR-05 mostra que a cópia pode gerar uma tentativa extra numa janela de RETRYING; ver "Achados" |
| **03-10** rastro e resposta verdadeira | 4 | 4 | `publishOrMarkFailed:161-191` (releitura com `wasClaimedByWorker`; ERROR + relançamento), details dos 503, regressões verdes |

**Placar:** 80/83 verdades verificadas (4 SC + 1 goal + 78 de planos). 0 FAILED, 2 UNCERTAIN (smokes não executados), 1 backstop sem evidência (`insufficient_spec`, contado em `behavior_unverified`).

### Artefatos obrigatórios (gap-closure)

| Artefato | Esperado | Status | Detalhes |
|----------|----------|--------|----------|
| `notify-api/.../application/UnconfirmedPublishSweeper.java` | `@Scheduled` + validação do corte | ✓ VERIFIED | 94 linhas; exercitado pelo IT |
| `notify-api/.../application/UnconfirmedSweepProperties.java` | `notify.publish.unconfirmed-sweep` | ✓ VERIFIED | Registrado em `@EnableConfigurationProperties`. O componente `interval` não é lido pelo `@Scheduled` (IN-09) |
| `notify-api/.../application/NotificationRepublisher.java` | `markUnconfirmedAsPublishFailed`, `FENCE_LOST`, `wasClaimedByWorker` | ✓ VERIFIED | — |
| `notify-api/.../config/ApiConfiguration.java` | `@EnableScheduling` | ✓ VERIFIED | — |
| `notify-api/.../application/NotificationService.java` | `awaitPublishWindow` | ✓ VERIFIED | — |
| `notify-api/.../application/PublishFailedException.java` | detail sem afirmar status | ✓ VERIFIED | O detail está correto; só o Javadoc afirma demais (IN-10) |
| `notify-api/.../application/RepublishInterruptedException.java` | "varredura de publicações sem confirm" | ✓ VERIFIED | — |
| `notify-api/src/test/.../UnconfirmedPublishSweeperTest.java` | 3 testes | ✓ VERIFIED | — |
| `notify-it/.../UnconfirmedPublishRecoveryIT.java` | 2 ITs | ✓ VERIFIED | Verde na minha execução |

`gsd-tools verify.artifacts`: 03-08 6/6, 03-09 3/3, 03-10 4/4. Os artefatos de 03-01..03-07 estão nos mesmos arquivos da verificação anterior. Os que não estão no diff `e5069b0..HEAD` não mudaram.

### Verificação de ligações (key links)

| De | Para | Via | Status |
|----|------|-----|--------|
| `UnconfirmedPublishSweeper.sweep` | `NotificationRepublisher.markUnconfirmedAsPublishFailed` | chamada direta | WIRED |
| `ApiConfiguration` | `UnconfirmedPublishSweeper.scheduledSweep` | `@EnableScheduling` | WIRED (provado em runtime pelo IT) |
| `UnconfirmedPublishRecoveryIT` | `AdminController.republishFailed` | `/admin/notifications/republish-failed` | WIRED |
| `NotificationService.replay` | `NotificationRepublisher.republish` | PENDING vencido e PUBLISH_FAILED | WIRED |
| `NotificationService.replay` | `NotificationRepublisher.wasClaimedByWorker` | 202 só com evidência | WIRED |
| `NotificationRepublisher.republish` | `NotificationDocument.Fields.UPDATED_AT` | fence pelo `updatedAt` lido | WIRED |
| `RepublishInterruptedException` | `UnconfirmedPublishSweeper` | detail promete a varredura | WIRED |
| `NotificationApiFlowTest` | `NotificationController` | POST real → 503 `publish-failed` | WIRED |
| Ligações de 03-01..03-07 | — | — | WIRED (sem regressão; arquivos sem mudança ou ITs verdes) |

`gsd-tools verify.key-links`: 03-08 3/3, 03-09 3/3, 03-10 3/3.

### Rastreamento de dados (nível 4)

| Artefato | Dado | Fonte | Dado real | Status |
|----------|------|-------|-----------|--------|
| Varredura | documentos marcados | `updateMulti` em `notifications` | Sim (IT lê o `lastError` gravado) | ✓ FLOWING |
| Replay 202 | `status` da resposta | documento relido ou PENDING depois do confirm próprio | Sim. No caminho do WR-07 a resposta diz PENDING com o documento PUBLISH_FAILED | ✓ FLOWING (ressalva WR-07) |
| `GET /notifications`, `/stats`, resposta do reprocess | — | — | Sem mudança | ✓ FLOWING |

### Verificações comportamentais

| Comportamento | Comando | Resultado | Status |
|---------------|---------|-----------|--------|
| Unitários do gap-closure | `./mvnw -B -q -pl notify-api -am test -Dtest=UnconfirmedPublishSweeperTest,NotificationServiceTest,NotificationRepublisherTest,NotificationApiFlowTest` | exit 0; 3/21/16/11 testes, 0 falhas | ✓ PASS |
| ITs do gap-closure + regressão da idempotência e do republish | `./mvnw -B -pl notify-it -am verify -Dtest=NoSuchTest -Dit.test=UnconfirmedPublishRecoveryIT,IdempotencyIT,UnroutablePublishIT` | BUILD SUCCESS; 2 + 5 + 4 ITs, 0 falhas | ✓ PASS |
| Suíte completa (execução do orquestrador) | relatórios em `*/target/*-reports` | 174/81/356 unitários e 35 ITs, 0 falhas; relatórios posteriores ao último commit de código | ✓ PASS (corroborado) |
| Unitários sem Docker | `grep -rl testcontainers notify-{api,common,worker}/src` | nenhum resultado | ✓ PASS |
| Smokes das Fases 1 e 2 | `scripts/smoke-fase{1,2}.sh` | não executados (sobem serviços) | ? SKIP → humano |

### Execução de probes

Não há `scripts/*/tests/probe-*.sh` nem probes declarados nos planos. Não se aplica.

### Cobertura de requisitos

| Requisito | Plano | Descrição | Status | Evidência |
|-----------|-------|-----------|--------|-----------|
| API-03 | 03-04, 03-05, 03-09, 03-10 | Idempotency-Key: replay e 422 | ✓ SATISFIED | `IdempotencyIT`, `UnconfirmedPublishRecoveryIT.replay*`, `NotificationServiceTest`. A ressalva anterior (202 de PENDING órfão) foi resolvida |
| API-05 | 03-06 | Lista paginada por `createdAt` desc | ✓ SATISFIED | `ListAndStatsIT` |
| API-07 | 03-06 | Stats por status e canal | ✓ SATISFIED | `ListAndStatsIT` |
| API-08 | 03-07 | Swagger UI springdoc 2.8.x | ✓ SATISFIED (visual → humano) | `ApiDocsIT` |
| ADM-01 | 03-02, 03-03 | Reprocess basicGet/reset/republish/ack | ✓ SATISFIED | `ReprocessFlowIT`, `ReprocessEdgeCasesIT`, `DlqReprocessFlowTest` |
| ADM-02 | 03-02 | Zera contador, mantém histórico, REPROCESSED | ✓ SATISFIED | `ReprocessFlowIT` |
| ADM-03 | 03-05, 03-08, 03-09, 03-10 | republish-failed | ✓ SATISFIED | `UnroutablePublishIT`, `UnconfirmedPublishRecoveryIT`. A ressalva anterior (órfão por Mongo fora) foi resolvida pela varredura |
| ADM-04 | 03-02 | X-API-Key em tempo constante, valida canal e max | ✓ SATISFIED | `AdminControllerTest`, `ReprocessFlowIT.rejectedAdminCallsNeverMoveTheDlq` |
| TST-02 | 03-03 | IT retry → DLQ → reprocess → SENT | ✓ SATISFIED | `ReprocessFlowIT` |
| TST-03 | 03-03, 03-05 | Venenosa, não roteável, duplicata concorrente | ✓ SATISFIED | `DeliveryGuaranteesIT`, `UnroutablePublishIT` |
| TST-04 | 03-01 | ITs via Failsafe, separados | ✓ SATISFIED | `notify-it` só com `*IT`, Failsafe |

Os 11 IDs aparecem no `requirements:` de algum plano. A rastreabilidade de REQUIREMENTS.md não mapeia nenhum outro ID para a Fase 3, então não há requisito órfão. **Atenção:** a tabela de REQUIREMENTS.md ainda marca 9 desses IDs como "Gaps Found" (só API-03 e ADM-03 estão "Complete"). O orquestrador precisa atualizá-la depois desta verificação.

### Proibições (must_haves.prohibitions): veredito do verificador, não autoritativo

Todas as 15 estão com `status: unresolved` e `flagged: true` e não têm o campo `verification`, por isso foram tratadas como tier judgment. Cada uma precisa de resolução humana (item 4 da verificação humana).

| Plano | Proibição (resumo) | Veredito do verificador | Base |
|-------|--------------------|-------------------------|------|
| 03-01 #1 | ITs sem mocks de Rabbit/Mongo, sem profile demo, sem desligar o comportamento | Provavelmente respeitada | Nenhum `MockBean`/`MockitoBean`/`@Mock`/demo em `notify-it/src`; Testcontainers reais |
| 03-01 #2 | Destinatários mágicos só nos profiles it e demo | Provavelmente respeitada | `notify-worker/application.yml:73` com padrão `false`; `true` só em `application-it.yml` e `application-demo.yml` |
| 03-02 #1 | Nunca dar ack na DLQ sem republish/cópia com confirm ou SENT/inexistente | Provavelmente respeitada | Ordem reset → publish → ack provada no `DlqReprocessFlowTest` |
| 03-02 #2 | Não apagar/reescrever `attempts[]` nem mexer em `attemptCount` | Respeitada, com ressalva | O reset não toca `attemptCount`. O `$push` com `$slice -50` descarta entradas mais antigas que as últimas 50, o mesmo teto do worker |
| 03-03 #1 | Sem segundo envio por reprocess repetido, concorrente ou cópia duplicada | Provavelmente respeitada | `ReprocessEdgeCasesIT` (concorrência), `DeliveryGuaranteesIT`. O WR-03 permite tentativas extras, não um segundo SENT |
| 03-03 #2 | ITs sem mocks e com contagem exata da DLQ | Provavelmente respeitada | Asserções exatas mantidas |
| 03-04 #1 | POST repetido com a mesma chave não gera segundo documento nem segundo envio | Respeitada, com ressalva | Um documento (índice único) e um SENT. **WR-05:** replay de PENDING confirmado e ainda não reivindicado publica uma cópia depois de 10s. Ela é descartada pelo claim, salvo numa janela de RETRYING, onde gera uma tentativa extra |
| 03-04 #2 | Não expor `idempotencyKey`/`requestHash` | Provavelmente respeitada | Ausentes nos DTOs; `getNeverExposesIdempotencyKeyNorRequestHash` |
| 03-05 | Não republicar o que já saiu de PUBLISH_FAILED | Provavelmente respeitada | Fence `status = PUBLISH_FAILED` (`NotificationRepublisher.java:216`) |
| 03-06 | Lista sem `variables`/`rendered`/`attempts` | Provavelmente respeitada | `NotificationSummary` tem só id, status, channel, recipient, templateCode, retryCount, lastError e datas. O `recipient` exposto é o WR-04, uma decisão do D-11 |
| 03-08 | A varredura não toca documento fora de PENDING, com claim ou recente | Provavelmente respeitada | Filtro exato no unitário; controle recente intacto no IT |
| 03-09 #1 | Nenhum 202 de replay sem confirm próprio ou evidência de claim | Respeitada | Todos os caminhos de retorno traçados (`replay:170-206`) |
| 03-09 #2 | O republish do replay não gera segundo envio; cópias extras só terminam descartadas pelo claim | Respeitada quanto a "segundo envio", **violada na letra** quanto a "só descartadas" | WR-05: numa janela entre `markRetrying` e `advanceRetryLevel`, a cópia de nível 0 encontra RETRYING com `retryCount` 0 e é reivindicada, o que gera uma tentativa extra. Continua sendo um SENT só (claim exclusivo, SENT terminal) |
| 03-10 #1 | Nenhum 503 ou log afirma um status que o documento pode não ter | Respeitada nos 503 e no ERROR, **com ressalva** | WR-07: no publish sem fence do fim do laço, a resposta 202 e o log `replayed` dizem "status PENDING" com o documento ainda PUBLISH_FAILED. A proibição fala de "503 ou log", e o log aqui descreve o status da resposta |
| 03-10 #2 | Criação e republish não tratam como publicada uma notificação sem confirm nem evidência de claim | Respeitada | `publishOrMarkFailed:165-190`; `zeroMatch*` (3 testes) |

### Anti-padrões e achados do code review

Nenhum marcador `TBD`/`FIXME`/`XXX`/`TODO`/`HACK` nos 14 arquivos alterados desde `e5069b0`. Os arquivos da fase que já tinham sido verificados não mudaram.

Para cada warning novo do `03-REVIEW.md`, avaliei se ele contradiz uma verdade da fase:

| Achado | Arquivo | Severidade | Contradiz must-have/goal? | Classificação |
|--------|---------|------------|---------------------------|---------------|
| WR-05: replay de PENDING confirmado e ainda não reivindicado espera 10s e publica cópia extra | `NotificationService.java:176-186`, `NotificationRepublisher.java:220-223` | ⚠️ Warning | **Não.** Nenhuma notificação se perde e não há 202 sem confirm. Pode haver cópia extra na fila e, numa janela estreita (RETRYING com `retryCount` 0), uma tentativa extra e uma cadeia de retry paralela, o mesmo efeito do WR-03. Ainda é um SENT só. Viola só a letra da proibição 03-09 #2 ("só descartadas"), por isso ela está sinalizada para decisão humana. Correção sugerida no review: gravar `publishConfirmedAt` cercado depois do ack | Warning |
| WR-06: latência do replay até ~7 × `confirm-timeout` (~35s); Javadoc promete 2× | `NotificationService.java:172-217` | ⚠️ Warning | **Não.** A verdade 3 do 03-09 limita cada espera à janela do `updatedAt` corrente, e isso vale. O que está errado é o Javadoc ("no máximo 2 × confirm-timeout"), e não existe deadline total. Afeta a latência e a carga sob replays concorrentes, não a garantia de entrega | Warning |
| WR-07: publish sem fence da 3ª rodada responde 202 PENDING com o documento PUBLISH_FAILED | `NotificationService.java:196-205` | ⚠️ Warning | **Não.** O 202 tem confirm próprio, então a mensagem está no broker, e PUBLISH_FAILED continua reivindicável pelo worker. O comportamento é exatamente o da verdade 4 do 03-09. A incoerência entre resposta e estado gravado é transitória e pode levar a um envio redundante pelo `republish-failed` (descartado pelo fence ou pelo claim) | Warning |
| WR-08: `DataAccessException` depois de publish sem confirm vira 500 genérico sem `notificationId` | `NotificationRepublisher.java:175-182`, `ApiExceptionHandler.java` | ⚠️ Warning | **Não.** A verdade 2 do 03-10 pede exatamente "ERROR com o id e propaga". O goal vale: o órfão é recuperado pela varredura. O risco de envio em dobro só aparece quando o cliente repete o POST **sem** `Idempotency-Key`, caso fora das proibições (a 03-04 #1 cobre a mesma chave). Recomendado: 503 `publish-outcome-unknown` com `notificationId` |
| WR-01, WR-03, WR-04 (anteriores, abertos) | `DlqReprocessService`, `NotificationResetService`, `NotificationSummary` | ⚠️ Warning | Não (avaliados na verificação anterior; arquivos sem mudança) | Warning |
| IN-08: espera interrompida segue para o publish | `NotificationService.java:239-242` | ℹ️ Info | Não (só no shutdown; o resultado é 503 e PUBLISH_FAILED, recuperável) | Info |
| IN-09: `UnconfirmedSweepProperties.interval` não é lido e `interval` não é validado | `UnconfirmedPublishSweeper.java:89-90` | ℹ️ Info | Não. Confirmado no código: `@Scheduled` lê a propriedade direto, e `interval: 0s` não é recusado | Info |
| IN-10: Javadocs que afirmam mais do que o código garante | `PublishFailedException.java:13-15`, `UnconfirmedPublishSweeper.java:31-34`, `NotificationService.java:167-168` | ℹ️ Info | Não. Os details do Problem são verdadeiros; os Javadocs não | Info |
| IN-11: faltam testes nos ramos novos do replay (troca de `updatedAt`, esgotamento por mudança observada, `DataAccessException` no reread, interrupção, corrida real do WR-02 em IT) | `NotificationServiceTest`, `UnconfirmedPublishRecoveryIT` | ℹ️ Info | Não. Os ramos que sustentam as verdades têm teste | Info |
| IN-01..IN-07 (anteriores) | vários | ℹ️ Info | Não. IN-04 parcialmente mitigado pela varredura; IN-07 em `coincidental_reliance_items` | Info |

### Advisory (escopo novo, sem evidência)

Nenhum. Todos os achados novos estão em arquivos alterados desde a verificação anterior e foram classificados acima com base no código. Nenhum é Blocker.

### Verificação humana necessária

1. **Swagger UI e Authorize** (human-check do 03-07). Perfil `demo`, `/swagger-ui/index.html`, Authorize com `demo-admin-key`, reprocess `email` com `max=1`. Esperado: três tags em pt-BR, cadeado só no admin, 200 com a chave e 401 sem ela. Veja o IN-01: as descrições têm blocos de espaços.
2. **Smokes das Fases 1 e 2 com os `-exec.jar`.** `scripts/smoke-fase1.sh` e `scripts/smoke-fase2.sh`. Esperado: `SMOKE FASE 1: OK` e `SMOKE FASE 2: OK`.
3. **Queda da API no meio do reprocess** (backstop do 03-03). `kill -9` na API durante `reprocess?max=100`. Esperado: entregas sem ack voltam à DLQ, e os documentos resetados são retomados pelo próximo reprocess ou levados a PUBLISH_FAILED pela varredura.
4. **Resolver as 15 proibições judgment-tier** (tabela acima). Três pedem decisão explícita:
   - **03-09 #2:** WR-05, cópia de replay reivindicada na janela de RETRYING. Aceitar como at-least-once de tentativa (documentado no DOC-03) ou corrigir com `publishConfirmedAt`;
   - **03-04 #1:** a mesma causa (WR-05);
   - **03-10 #1:** WR-07, replay que responde e loga PENDING com o documento PUBLISH_FAILED.

### Decisões sugeridas ao desenvolvedor (não bloqueiam a fase)

Os warnings WR-05..WR-08 não derrubam nenhuma verdade, mas são baratos de corrigir antes da demo de carga da Fase 4, onde o backlog do worker torna o WR-05 provável:
- WR-05 + WR-06: `publishConfirmedAt` cercado depois do ack e um deadline único por replay (409 com `Retry-After` quando estoura). Isso elimina a cópia extra e a espera de 10s com o worker atrasado;
- WR-07: republish cercado no fim do laço quando o documento corrente é PUBLISH_FAILED;
- WR-08: 503 `publish-outcome-unknown` com `notificationId` em vez do 500 genérico.

Se a decisão for não corrigir, registre os quatro no DOC-03 (trade-offs da Fase 4).

## Gaps

Nenhum. O Gap 1 da verificação anterior está fechado (ver "Re-verificação do Gap 1"), e não surgiu nenhum gap novo nem regressão.

---

_Verificado em: 2026-11-01T01:10:58Z_
_Verificador: Claude (gsd-verifier)_
