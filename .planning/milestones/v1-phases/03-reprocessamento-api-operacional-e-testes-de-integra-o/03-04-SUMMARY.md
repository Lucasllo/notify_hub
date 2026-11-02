---
phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o
plan: 04
subsystem: api
tags: [idempotency-key, mongodb, partial-unique-index, sha-256, jackson, problem-details, testcontainers, virtual-threads]

requires:
  - phase: 03-01
    provides: harness notify-it (EndToEndSupport com post/get/mongo/worker/awaitStatus e HttpResult.headers)
  - phase: 03-02
    provides: ApiExceptionHandler.invalidRequest(List<FieldProblem>) para o 400 de parâmetro único
provides:
  - "NotificationDocument com idempotencyKey/requestHash, construtor de compatibilidade de 16 componentes e newPending com chave"
  - "Fields.IDEMPOTENCY_KEY, REQUEST_HASH, RECIPIENT e TEMPLATE_CODE (os dois últimos para a projeção da listagem do 03-06)"
  - "Índice único parcial idempotencyKey_unique ({idempotencyKey: 1}, partialFilterExpression {$exists: true}) no MongoIndexInitializer"
  - "RequestHasher.hash(CreateNotificationRequest): SHA-256 hex do pedido canônico"
  - "NotificationService.create(request, idempotencyKey) -> CreateResult(response, replayed), com replay, 422 e corrida resolvidos pelo índice"
  - "IdempotencyKeyReusedException (422) e ProblemTypes.IDEMPOTENCY_KEY_REUSED"
  - "NotificationController: header Idempotency-Key validado (400) e Idempotent-Replayed: true no replay"
  - "IdempotencyIT com 5 testes contra Mongo e RabbitMQ reais"
affects: [03-05, 03-06, 03-07, fase-4-readme]

actuals:
  tokens: 14650
  tasks: 3
  commits: 6
plan_head_before: e5d388c11b6d7145c6087cd57cef8e6e22f8ce22

tech-stack:
  added: []
  patterns:
    - "Atomicidade entre requisições só pelo índice único parcial: insert -> DuplicateKeyException -> findOne pela chave -> replay ou 422"
    - "Pedido canônico em TreeMap serializado por JsonMapper com ORDER_MAP_ENTRIES_BY_KEYS, depois SHA-256 + HexFormat"
    - "Componentes novos de record no fim, com construtor de compatibilidade que delega com null (o Spring Data lê pelo canônico)"
    - "Validação de header no controller depois do binding do corpo, via ApiExceptionHandler.invalidRequest"

key-files:
  created:
    - notify-api/src/main/java/dev/notifyhub/api/application/RequestHasher.java
    - notify-api/src/main/java/dev/notifyhub/api/application/IdempotencyKeyReusedException.java
    - notify-api/src/test/java/dev/notifyhub/api/application/RequestHasherTest.java
    - notify-it/src/test/java/dev/notifyhub/it/IdempotencyIT.java
  modified:
    - notify-common/src/main/java/dev/notifyhub/common/persistence/NotificationDocument.java
    - notify-common/src/main/java/dev/notifyhub/common/persistence/MongoIndexInitializer.java
    - notify-common/src/test/java/dev/notifyhub/common/persistence/MongoIndexInitializerTest.java
    - notify-common/src/test/java/dev/notifyhub/common/persistence/NotificationDocumentMappingTest.java
    - notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java
    - notify-api/src/main/java/dev/notifyhub/api/web/NotificationController.java
    - notify-api/src/main/java/dev/notifyhub/api/web/ProblemTypes.java
    - notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java
    - notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java

key-decisions:
  - "03-04: o construtor canônico do record basta para o Spring Data 4.5; o construtor extra de 16 componentes não precisou de @PersistenceCreator (round-trip do NotificationDocumentMappingTest e replay do IdempotencyIT verdes)"
  - "03-04: hash de referência do pedido EMAIL/ana@exemplo.com/welcome/{name: Ana} fixado em RequestHasherTest (5f913d9c...ada3); mudar a canonicalização invalida chaves já gravadas"
  - "03-04: o 422 não leva properties no Problem Details (nem notificationId), só type/title/detail fixos; o log INFO cita só o id original, nunca a chave"

patterns-established:
  - "Replay devolve o status atual do documento (inclusive PUBLISH_FAILED) até o 03-05 trocar esse caso por republicação (D-08)"
  - "IT de corrida HTTP: 8 threads virtuais liberadas por CountDownLatch + delta do timer notify.delivery.duration{email,success} estável por 500ms"

requirements-completed: [API-03]

coverage:
  - id: D1
    description: "Mesma Idempotency-Key + mesmo payload devolve o mesmo 202 (Location e id originais, status atual, Idempotent-Replayed: true), sem segundo documento nem segundo envio; o primeiro POST não leva o header"
    requirement: API-03
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/IdempotencyIT.java#samePayloadWithTheSameKeyReplaysTheOriginal"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java#duplicateKeyWithTheSameHashReplaysTheExistingNotificationWithoutPublishing"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java#firstPostWithAKeyHasNoReplayHeaderAndAReplayHasIt"
        status: pass
    human_judgment: false
  - id: D2
    description: "idempotencyKey e requestHash no próprio documento, índice único parcial idempotencyKey_unique ({idempotencyKey: 1}, {$exists: true}); documentos sem chave não gravam os campos e dois POSTs sem chave criam duas notificações"
    requirement: API-03
    verification:
      - kind: unit
        ref: "notify-common/src/test/java/dev/notifyhub/common/persistence/MongoIndexInitializerTest.java (5 casos)"
        status: pass
      - kind: unit
        ref: "notify-common/src/test/java/dev/notifyhub/common/persistence/NotificationDocumentMappingTest.java#pendingWithoutKeyStoresNeitherKeyNorHash"
        status: pass
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/IdempotencyIT.java#postsWithoutKeyStillCreateDistinctNotifications"
        status: pass
    human_judgment: false
  - id: D3
    description: "SHA-256 hex minúsculo do pedido canônico depois do parse: ordem das variáveis não importa, qualquer campo diferente muda o hash, 1 e 1.0 diferem, 1.0 e 1.00 empatam, constante de referência estável"
    requirement: API-03
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/RequestHasherTest.java (6 casos)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Mesma chave com outro payload responde 422 urn:notify-hub:problem:idempotency-key-reused em application/problem+json, sem ecoar a chave nem o id original, sem criar documento e sem publicar"
    requirement: API-03
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/IdempotencyIT.java#sameKeyWithADifferentPayloadIs422"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java#duplicateKeyWithADifferentHashIs422WithoutEchoingKeyOrOriginalIdAndWithoutPublishing"
        status: pass
    human_judgment: false
  - id: D5
    description: "8 POSTs simultâneos com a mesma chave nova: todos 202 com o mesmo id, exatamente 1 sem Idempotent-Replayed, 1 documento com a chave, SENT e delta 1 no timer do adapter estável por 500ms"
    requirement: API-03
    verification:
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/IdempotencyIT.java#concurrentPostsWithTheSameNewKeyCreateExactlyOneNotification"
        status: pass
    human_judgment: false
  - id: D6
    description: "DuplicateKeyException sem chave, ou cujo documento não é achado pela chave, é relançada (Armadilha 6)"
    requirement: API-03
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java#duplicateKeyWhoseDocumentIsNotFoundByTheKeyIsRethrown"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java#duplicateKeyWithoutIdempotencyKeyIsRethrownWithoutLookingUp"
        status: pass
    human_judgment: false
  - id: D7
    description: "Idempotency-Key com espaço, com 256 caracteres ou vazia responde 400 validation-error com errors[0].field = Idempotency-Key, sem insert; corpo inválido com chave válida continua no 400 do corpo"
    requirement: API-03
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java#invalidIdempotencyKeyAnswers400WithoutInserting (3 casos)"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java#invalidBodyWithAValidKeyStillAnswersTheBodyValidation400"
        status: pass
      - kind: integration
        ref: "notify-it/src/test/java/dev/notifyhub/it/IdempotencyIT.java#invalidKeyIsRejectedWithoutCreatingANotification"
        status: pass
    human_judgment: false
  - id: D8
    description: "GET /notifications/{id} (e o corpo do replay) nunca expõe idempotencyKey nem requestHash"
    requirement: API-03
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java#getNeverExposesIdempotencyKeyNorRequestHash"
        status: pass
    human_judgment: false

duration: 29min
completed: 2026-10-31
status: complete
---

# Phase 3 Plan 04: Idempotency-Key no POST /notifications (API-03) Summary

**`POST /notifications` com `Idempotency-Key`: a mesma chave com o mesmo payload devolve o 202 original com `Idempotent-Replayed: true`, e com outro payload devolve 422. A atomicidade vem só do índice único parcial `idempotencyKey_unique` no próprio documento, comparando o SHA-256 do pedido canônico. Chave inválida dá 400, e 8 POSTs simultâneos com a mesma chave nova terminam em um documento e um envio.**

## Performance

- **Duração:** 29 min
- **Início:** 2026-10-31T13:55:49Z
- **Fim:** 2026-10-31T14:25:09Z
- **Tarefas:** 3 (6 commits: RED + GREEN em cada uma)
- **Arquivos alterados:** 13 (4 criados, 9 alterados)

## Accomplishments

- **Documento e índice (D-05):**
  - `NotificationDocument` ganhou `idempotencyKey` e `requestHash` no fim do record.
  - Um construtor com os 16 componentes antigos delega com `null, null`, então worker, testes e ITs do 03-03 compilaram sem mudança.
  - `newPending` ganhou uma sobrecarga com chave e hash.
  - O `MongoIndexInitializer` cria `idempotencyKey_unique` (único, parcial `$exists: true`) no startup da API e do worker.
- **`RequestHasher` (D-06):** monta um `TreeMap` com `channel` (nome do enum), `recipient`, `templateCode` e `variables` ordenadas, serializa com um `JsonMapper` com `ORDER_MAP_ENTRIES_BY_KEYS` e devolve o SHA-256 em hexadecimal minúsculo. `1` e `1.0` dão hashes diferentes; `1.0` e `1.00` dão o mesmo.
- **`NotificationService.create(request, idempotencyKey)` → `CreateResult(response, replayed)`:**
  - O insert carrega chave e hash.
  - Uma `DuplicateKeyException` com chave leva a `findOne` pela chave. Hash igual devolve replay com o status atual, sem publicar. Hash diferente lança `IdempotencyKeyReusedException` (422).
  - Sem chave, ou sem documento achado pela chave, a exceção é relançada (Armadilha 6).
  - Os logs citam só o id original, nunca a chave nem o destinatário.
- **`NotificationController`:**
  - Valida o header contra `^[\x21-\x7E]{1,255}$` depois do binding do corpo e responde 400 com `errors[0].field` = `Idempotency-Key`.
  - Acrescenta `Idempotent-Replayed: true` só no replay.
  - Constantes `IDEMPOTENCY_KEY_HEADER` e `IDEMPOTENT_REPLAYED_HEADER`.
- **`IdempotencyIT` (5 testes, Mongo e RabbitMQ reais):** cobre replay até SENT, POSTs sem chave distintos, 422 sem vazar id nem chave, corrida de 8 threads virtuais com um envio e chave de 256 caracteres sem documento.

## Task Commits

1. **Task 1: Tracer, o mesmo POST com a mesma chave devolve a notificação original com `Idempotent-Replayed: true`**
   - RED `fcb0cec` (test): `MongoIndexInitializerTest` 5/5 falharam por `times(4)` contra 3 chamadas. No serviço, o teste da chave no insert falhou por asserção, e o do replay saiu com a `DuplicateKeyException`. O `IdempotencyIT` de replay falhou pelo id, e o teste sem chave ficou verde (comportamento existente).
   - GREEN `46a84dc` (feat). Gate do tracer: execução interativa, `end-of-phase`, `<verify>` só automático. O verify rodou de novo no código commitado (API e IT 2/2 verdes), e a expansão seguiu sem checkpoint.
2. **Task 2: 422 para payload diferente e corrida concorrente com uma notificação**
   - RED `eecf886` (test): o 422 do serviço e o do IT falharam por asserção (saía `DuplicateKeyException` / status diferente de 422).
   - GREEN `2db2e66` (feat).
3. **Task 3: chave inválida dá 400, documentos sem chave não gravam os campos e o GET não expõe chave nem hash**
   - RED `36ba53e` (test): os 3 casos de chave inválida no `NotificationApiFlowTest` e o IT falharam com 202 em vez de 400.
   - GREEN `1d534d7` (feat).

**Plan metadata:** commit `docs(03-04)` logo após este SUMMARY.

## Files Created/Modified

- `notify-common/.../persistence/NotificationDocument.java`: componentes novos, construtor de compatibilidade, `newPending` com chave, `Fields` novos
- `notify-common/.../persistence/MongoIndexInitializer.java`: índice `idempotencyKey_unique` e Javadoc
- `notify-api/.../application/RequestHasher.java` (novo): hash do pedido canônico
- `notify-api/.../application/IdempotencyKeyReusedException.java` (novo): 422 com corpo fixo
- `notify-api/.../application/NotificationService.java`: `CreateResult`, `create(request, key)`, replay e 422
- `notify-api/.../web/NotificationController.java`: header validado, `Idempotent-Replayed`, constantes
- `notify-api/.../web/ProblemTypes.java`: `IDEMPOTENCY_KEY_REUSED`
- Testes: `MongoIndexInitializerTest` (5), `NotificationDocumentMappingTest` (+2 e `Fields` ampliado), `RequestHasherTest` (6, novo), `NotificationServiceTest` (11), `NotificationApiFlowTest` (+6), `IdempotencyIT` (5, novo)

## Decisions Made

- **Sem `@PersistenceCreator`.** O Spring Data 4.5 leu o record pelo construtor canônico mesmo com o construtor extra de 16 componentes. O round-trip com chave do `NotificationDocumentMappingTest` e o replay do IT confirmam isso, então o fallback do plano não foi preciso.
- **Hash de referência fixado:** `5f913d9c6f1eb824fd55e6e689847733b115da2d6c66aa36af32c91cdaadada3` para EMAIL, `ana@exemplo.com`, `welcome`, `{name: Ana}`. O comentário no teste avisa que mudar a canonicalização faz chaves já gravadas responderem 422.
- **422 sem `properties`.** O Problem Details do 422 tem só `type`, `title` e `detail` fixos (mais o `instance` do handler), sem `notificationId`. O teste unitário confere `getProperties()` nulo.

## Deviations from Plan

### Ajustes de execução

**1. [Processo] Commits em `main`**
- **Found during:** início da execução
- **Issue:** o protocolo do executor recusa commits na branch padrão, mas o projeto usa `branching_strategy: none` e o orquestrador mandou seguir em `main`.
- **Fix:** commits normais em `main`, com hooks e sem `--no-verify`. O pin de raiz (#4254) rodou antes da primeira escrita e antes de cada commit.
- **Commits:** todos os 6

**2. [Processo] Testes de caracterização verdes no RED das Tasks 2 e 3**
- **Found during:** Task 2 (RED) e Task 3 (RED)
- **Issue:** o plano põe na Task 2 o `RequestHasherTest` (cujo hasher a Task 1 já entrega, com a constante calculada pela própria implementação) e o IT de corrida (resolvida pelo índice da Task 1). Na Task 3 entram o round-trip do mapeamento, o replay na fatia web e o GET sem os campos. Esses testes passam desde o RED.
- **Fix:** ficaram como testes de caracterização e regressão no mesmo commit RED, que também tem testes falhando por asserção para o comportamento novo da tarefa: o 422 na Task 2 e o 400 da chave na Task 3. O que passou e o que falhou está nas mensagens dos commits RED.
- **Committed in:** `eecf886`, `36ba53e`

---

**Total deviations:** 2 (ambas de processo)
**Impact on plan:** nenhum no escopo. O código de produção seguiu o plano.

## Issues Encountered

- Na primeira tentativa, um script de edição gerou `"^[\x21-\x7E]{1,255}$"` com barra simples, que é um escape inválido em Java. Foi corrigido para `\\x21` antes de compilar e entrou só no GREEN da Task 3.
- A mensagem do commit `eecf886` tem um erro de digitação ("RequestHasterTest"). Não foi feito amend.
- Os stack traces de `DeliveryFlowTest`/`DeliveryProcessorTest` na saída do `-am verify` são exceções logadas de propósito pelos testes do worker (exit 0).
- Os avisos de LF → CRLF do Git aparecem em todo commit (Windows) e são inofensivos.

## TDD Gate Compliance

Os 3 gates RED (`test(03-04)`: `fcb0cec`, `eecf886`, `36ba53e`) vêm antes dos 3 gates GREEN (`feat(03-04)`: `46a84dc`, `2db2e66`, `1d534d7`), em ordem. Não houve REFACTOR. O RED usa esqueletos que compilam (`RequestHasher` devolvendo `""`, `create(request, key)` ignorando a chave e `IdempotencyKeyReusedException` sem corpo), para a falha vir de asserção e não de compilação. O checador `tdd-red-evidence` lê saída TAP de Node e não se aplica ao Surefire. A evidência do RED está nas mensagens dos commits RED.

## Known Stubs

Nenhum. Os esqueletos do RED foram substituídos nos commits GREEN, e o grep por `TODO`/`FIXME`/`Esqueleto`/`não implementado` em `notify-api/src/main` e `notify-common/src/main` não acha nada.

O replay de um documento PUBLISH_FAILED devolve 202 com o status atual, sem republicar. Isso é intencional e está no escopo do plano (D-07). A republicação (D-08) fica no 03-05.

## Threat Flags

Nenhuma superfície nova fora do `<threat_model>`. Os `mitigate` têm teste:
- T-03-17: corrida no `IdempotencyIT`.
- T-03-19: chave de 256 caracteres no `NotificationApiFlowTest` e no IT.
- T-03-20: `doesNotExist` no GET.

O T-03-18 (`accept`) está documentado no Javadoc do controller (recomendação de UUID) e vai para o README na Fase 4.

## User Setup Required

Nenhum. Bases existentes ganham o índice no próximo startup. Documentos antigos não têm `idempotencyKey`, então ficam fora do índice parcial e não há conflito.

## Next Phase Readiness

- O 03-05 pode trocar o ramo de replay para republicar um PUBLISH_FAILED (D-08). O ponto de extensão é o `replayOrRethrow` do `NotificationService`, e o `markPublishFailed` atual é o candidato a ser extraído para o serviço único de republish.
- O 03-06 já tem `Fields.RECIPIENT` e `Fields.TEMPLATE_CODE` para a projeção da listagem.
- A Fase 4 (README) pode citar o `IdempotencyIT` e registrar o T-03-18 (sondagem de chaves numa API sem autenticação).

## Self-Check: PASSED

- Os 4 arquivos criados existem em disco.
- Os commits `fcb0cec`, `46a84dc`, `eecf886`, `2db2e66`, `36ba53e` e `1d534d7` estão no histórico (6 = `git rev-list --count e5d388c..HEAD`).
- Todos os critérios de aceite das 3 tarefas deram PASS (grep e comandos).
- Verificação do plano:
  - `./mvnw -q -pl notify-common install && ./mvnw -q -pl notify-worker test`: verde (80 common, 356 worker).
  - `./mvnw -q -pl notify-api test`: verde (106).
  - `./mvnw -q -pl notify-it -am verify -Dit.test=IdempotencyIT`: verde (5/5).
  - `./mvnw -q verify`: exit 0, 0 linhas `[ERROR]`, 542 unitários e 20 ITs (`DeliveryGuaranteesIT` 3, `HarnessSmokeIT` 2, `IdempotencyIT` 5, `ReprocessEdgeCasesIT` 7, `ReprocessFlowIT` 3).

---
*Phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o*
*Completed: 2026-10-31*
