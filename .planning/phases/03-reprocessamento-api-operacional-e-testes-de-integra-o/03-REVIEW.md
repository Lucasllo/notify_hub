---
phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o
reviewed: 2026-10-31T18:16:43Z
depth: standard
files_reviewed: 56
files_reviewed_list:
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
  - notify-api/src/main/java/dev/notifyhub/api/web/dto/NotificationStats.java
  - notify-api/src/main/java/dev/notifyhub/api/web/dto/NotificationSummary.java
  - notify-api/src/main/java/dev/notifyhub/api/web/dto/PageResponse.java
  - notify-api/src/main/java/dev/notifyhub/api/web/NotificationController.java
  - notify-api/src/main/java/dev/notifyhub/api/web/NotificationQueryController.java
  - notify-api/src/main/java/dev/notifyhub/api/web/OpenApiConfiguration.java
  - notify-api/src/main/java/dev/notifyhub/api/web/ProblemTypes.java
  - notify-api/src/main/resources/application.yml
  - notify-api/src/main/resources/application-demo.yml
  - notify-common/src/main/java/dev/notifyhub/common/domain/AttemptOutcome.java
  - notify-common/src/main/java/dev/notifyhub/common/domain/NotificationStatus.java
  - notify-common/src/main/java/dev/notifyhub/common/persistence/MongoIndexInitializer.java
  - notify-common/src/main/java/dev/notifyhub/common/persistence/NotificationDocument.java
  - notify-worker/pom.xml
  - notify-worker/src/main/java/dev/notifyhub/worker/metrics/DeliveryMetrics.java
  - notify-it/pom.xml
  - pom.xml
  - scripts/smoke-fase1.sh
  - scripts/smoke-lib.sh
  - notify-it/src/test/java/dev/notifyhub/it/support/EndToEndSupport.java
  - notify-it/src/test/resources/it/application-it.yml
  - notify-api/src/test/java/dev/notifyhub/api/admin/AdminControllerTest.java
  - notify-api/src/test/java/dev/notifyhub/api/admin/DlqReprocessFlowTest.java
  - notify-api/src/test/java/dev/notifyhub/api/admin/MongoBson.java
  - notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java
  - notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java
  - notify-api/src/test/java/dev/notifyhub/api/application/RequestHasherTest.java
  - notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java
  - notify-api/src/test/java/dev/notifyhub/api/web/NotificationQueryControllerTest.java
  - notify-api/src/test/java/dev/notifyhub/api/web/NotificationValidationTest.java
  - notify-common/src/test/java/dev/notifyhub/common/domain/NotificationStatusTest.java
  - notify-common/src/test/java/dev/notifyhub/common/persistence/MongoIndexInitializerTest.java
  - notify-common/src/test/java/dev/notifyhub/common/persistence/NotificationDocumentMappingTest.java
  - notify-it/src/test/java/dev/notifyhub/it/ApiDocsIT.java
  - notify-it/src/test/java/dev/notifyhub/it/DeliveryGuaranteesIT.java
  - notify-it/src/test/java/dev/notifyhub/it/HarnessSmokeIT.java
  - notify-it/src/test/java/dev/notifyhub/it/IdempotencyIT.java
  - notify-it/src/test/java/dev/notifyhub/it/ListAndStatsIT.java
  - notify-it/src/test/java/dev/notifyhub/it/ReprocessEdgeCasesIT.java
  - notify-it/src/test/java/dev/notifyhub/it/ReprocessFlowIT.java
  - notify-it/src/test/java/dev/notifyhub/it/UnroutablePublishIT.java
findings:
  critical: 1
  warning: 4
  info: 7
  total: 12
status: issues_found
---

# Fase 03: Relatório de Code Review

**Revisado em:** 2026-10-31T18:16:43Z
**Profundidade:** standard
**Arquivos revisados:** 56
**Status:** issues_found

## Resumo

Revisei o código de produção da Fase 3: reprocessamento da DLQ, republicação de PUBLISH_FAILED, Idempotency-Key, listagem e stats, Swagger, a tabela de transições, os índices, os POMs e os scripts de smoke. Os testes unitários e de integração foram lidos com foco na confiabilidade.

No geral o código é cuidadoso. Ack só depois do confirm, nack com requeue num `finally`, cercas otimistas em todos os updates e motivos de falha de um conjunto fechado estão todos corretos. A comparação da chave de admin em tempo constante, via SHA-256, também está correta.

O principal problema é o edge case que um executor deixou sem correção. Uma falha do MongoDB entre um publish não confirmado e a marcação PUBLISH_FAILED deixa o documento em PENDING sem mensagem em fila nenhuma. Nenhum caminho de recuperação enxerga esse estado, e o replay idempotente o transforma num 202 enganoso. Isso fere diretamente o Core Value do projeto ("nunca se perde em silêncio"), por isso está classificado como BLOCKER.

Os outros achados:
- uma mensagem cujo documento não pode ser lido trava a cabeça da DLQ para sempre;
- o replay de um documento PENDING devolve 202 sem prova de confirm;
- o reset de estados não terminais zera `retryCount` e com isso reabre a cerca de token obsoleto do worker;
- a listagem pública expõe os destinatários em massa.

## Narrative Findings (AI reviewer)

## Critical Issues

### CR-01: Falha do Mongo depois de um publish não confirmado deixa o documento PENDING sem mensagem e sem nenhum caminho de recuperação (e o replay idempotente responde 202)

**Arquivos:**
- `notify-api/src/main/java/dev/notifyhub/api/application/NotificationRepublisher.java:115-128` (`publishOrMarkFailed`)
- `notify-api/src/main/java/dev/notifyhub/api/application/NotificationRepublisher.java:134-152` (`republish`)
- `notify-api/src/main/java/dev/notifyhub/api/application/NotificationRepublisher.java:185-190` (`republishFailed`)
- `notify-api/src/main/java/dev/notifyhub/api/application/NotificationRepublisher.java:199-214` (`markPublishFailed`)
- `notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java:98-100`
- `notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java:122-128`

**Problema:** confirmei e classifiquei o edge case que o executor apontou. Ele é pior do que "o republish-failed não recupera".

1. **Criação (`create`).** O insert grava PENDING e o `publish` volta sem ACK (NACK, RETURNED, BROKER_UNAVAILABLE). Em seguida `markPublishFailed` lança `DataAccessException`, e não há tratamento. O cliente recebe um 500 genérico e o documento fica **PENDING sem mensagem em fila nenhuma**:
   - o worker nunca o vê;
   - o `republish-failed` filtra só `status = PUBLISH_FAILED` (linha 169);
   - o reprocessamento da DLQ só age sobre mensagens que estão na DLQ.

   Não existe varredura de PENDING antigos. A causa mais provável nem é rara: uma queda de rede no host da API derruba broker e Mongo ao mesmo tempo. O mesmo estado também aparece com um kill/OOM/restart da JVM entre o insert e o confirm, numa janela de até `confirm-timeout` (5s).
2. **Replay idempotente.** O cliente faz o que o contrato manda e repete o POST com a mesma `Idempotency-Key`. `replayOrRethrow` acha o documento com o mesmo hash e status PENDING, que não é PUBLISH_FAILED (linha 122). Por isso cai na linha 127 e devolve **202 `Idempotent-Replayed: true` com status PENDING, sem publicar**. A API confirma como aceita uma notificação que nunca será entregue. Essa é exatamente a perda silenciosa que o Core Value proíbe.
3. **`republish` (D-08 e lote admin).** O código faz PUBLISH_FAILED → PENDING (linha 135), o publish falha e então o `markPublishFailed` falha no Mongo. O documento que **estava** recuperável sai do conjunto PUBLISH_FAILED e fica PENDING órfão. No lote, o 503 `MONGO_UNAVAILABLE` ainda afirma "As que faltaram continuam PUBLISH_FAILED" (`RepublishInterruptedException.java:33-34`), o que é falso para esse documento. No replay D-08, o próximo POST com a chave devolve o 202 enganoso do item 2.

Nenhum teste cobre a falha de Mongo dentro de `markPublishFailed`. `NotificationRepublisherTest.mongoDownOnAFenceUpdateIs503WithTheAccumulatedCounts` só derruba o update PUBLISH_FAILED → PENDING, em que o documento continua PUBLISH_FAILED.

**Correção:** é preciso um caminho que encontre PENDING "nunca confirmado". A opção mais simples e segura é uma varredura agendada na API que leva PENDING velhos sem claim para PUBLISH_FAILED. É seguro porque PUBLISH_FAILED continua reivindicável pelo worker (PUBLISH_FAILED → PROCESSING na tabela). Se a mensagem tiver chegado de fato, nada se perde, e o republish é cercado pelo status.
```java
@Scheduled(fixedDelayString = "${notify.publish.orphan-sweep-interval:30s}")
void markUnconfirmedPending() {
    Instant cutoff = Instant.now().minus(props.confirmTimeout().multipliedBy(3));
    UpdateResult r = mongoTemplate.updateMulti(
            query(where(Fields.STATUS).is(NotificationStatus.PENDING)
                    .and(Fields.UPDATED_AT).lt(cutoff)
                    .and(Fields.CLAIMED_AT).exists(false)),
            new Update().set(Fields.STATUS, NotificationStatus.PUBLISH_FAILED)
                    .set(Fields.LAST_ERROR, new LastError("PUBLISH_UNCONFIRMED",
                            "Publicação sem confirm registrado", null, Instant.now()))
                    .set(Fields.UPDATED_AT, Instant.now()),
            NotificationDocument.class);
    if (r.getModifiedCount() > 0) log.warn("{} notificações PENDING órfãs marcadas PUBLISH_FAILED", r.getModifiedCount());
}
```
Além disso:
- no `replayOrRethrow`, um PENDING com `updatedAt` mais antigo que o cutoff deve seguir o caminho do `replayPublishFailed` (republish cercado por `status = PENDING` + `updatedAt` lido), e não devolver 202 direto;
- `publishOrMarkFailed` deve logar ERROR com o id quando o `markPublishFailed` lançar, para o órfão ficar rastreável;
- falta um teste unitário com `updateFirst` lançando `DataAccessResourceFailureException` depois de um publish NACK.

## Warnings

### WR-01: Uma mensagem cujo documento não pode ser mapeado trava a cabeça da DLQ para sempre

**Arquivos:**
- `notify-api/src/main/java/dev/notifyhub/api/admin/DlqReprocessService.java:110-128`
- `notify-api/src/main/java/dev/notifyhub/api/admin/DlqReprocessService.java:188-193`
- `notify-api/src/main/java/dev/notifyhub/api/application/NotificationResetService.java:78-88`

**Problema:** `handle` só trata `DataAccessException` do reset. Nenhuma destas exceções é `DataAccessException`:
- `ConversionFailedException`: status ou canal fora do enum, por exemplo um documento gravado por outra versão ou à mão;
- `MappingInstantiationException`: documento corrompido;
- `NullPointerException` em `previousStatus.canTransitionTo(...)` (linha 86) quando o documento não tem `status`.

`MongoTemplate.findById` não traduz exceções do conversor. A exceção vira `tally.unexpected`, a entrega volta com nack+requeue **para a cabeça** da DLQ e o endpoint responde 500. Toda chamada seguinte lê a mesma mensagem primeiro e falha igual: o canal fica com a DLQ travada até alguém intervir à mão no broker. É justamente o "travar a cabeça da DLQ" que o D-01/CR-01 quis eliminar.

**Correção:** trate como "documento ilegível" e preserve no fim da DLQ, como já acontece com corpo ilegível:
```java
ResetResult result;
try {
    result = resetService.resetForReprocess(message.notificationId());
} catch (DataAccessException e) {
    return Step.failed(FailureReason.MONGO_UNAVAILABLE);
} catch (RuntimeException unreadable) { // ConversionFailedException, MappingInstantiationException, NPE de status nulo
    log.warn("Documento {} ilegível ({}): mensagem preservada no fim da DLQ",
            message.notificationId(), unreadable.getClass().getSimpleName());
    return copyToTail(channel, delivery);
}
```
No `NotificationResetService`, devolva `NotResettable(null)` quando `current.status() == null`, em vez de deixar estourar NPE.

### WR-02: O replay de um documento PENDING devolve 202 sem nenhuma evidência de confirm

**Arquivo:** `notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java:109-128`

**Problema:** o contrato (D-04 e a descrição do POST no Swagger) diz que o 202 só sai "depois do confirm do broker". Na corrida de POSTs com a mesma chave nova, quem perde lê o vencedor enquanto ele ainda está no `publish`, com o documento PENDING, e responde 202 na hora. Se o publish do vencedor falhar, o vencedor responde 503 e o documento vai a PUBLISH_FAILED. Mas os perdedores já disseram 202 a clientes que não vão repetir o pedido.

Não há perda total, porque PUBLISH_FAILED é visível e o operador pode republicar. Mesmo assim, o cliente recebe respostas contraditórias para a mesma chave. Junto com o CR-01, isso mostra que "PENDING no replay" não é um estado confiável para responder 202.

**Correção:** no replay, devolva 202 para PENDING só se o documento já tiver passado da janela de confirm do vencedor. Uma forma é reler depois de `confirmTimeout`, ou fazer polling curto até sair de PENDING ou estourar o prazo. Se ele terminar PUBLISH_FAILED, siga o caminho do `replayPublishFailed`. Também dá para responder 409/425 "requisição original em andamento" com `Retry-After`, como fazem várias APIs com Idempotency-Key.

### WR-03: O reset de PENDING/RETRYING para `retryCount` 0 reabre a cerca de token obsoleto do worker e cria cadeias de retry paralelas

**Arquivos:**
- `notify-api/src/main/java/dev/notifyhub/api/application/NotificationResetService.java:108-113`
- `notify-common/src/main/java/dev/notifyhub/common/domain/NotificationStatus.java:36,45`
- relacionado: `notify-worker/src/main/java/dev/notifyhub/worker/processing/NotificationStateService.java:104-106`

**Problema:** o claim do worker recusa tokens obsoletos com a cerca `doc.retryCount ≤ h` (WR-01 da Fase 2). O reset grava `retryCount = 0` também para estados **não terminais**, que podem ter um token vivo em outra fila. O caso concreto é uma cópia antiga na DLQ, por exemplo depois de um reprocess cujo publish deu TIMEOUT mas foi roteado e voltou com nack, enquanto o documento segue RETRYING com token no nível h.

Depois do reset, o token de nível h sai da fila de delay e encontra `retryCount = 0 ≤ h`, então é reivindicado de novo. Com isso aparecem duas cadeias para a mesma notificação:
- tentativas e entradas em `attempts[]` em dobro;
- a escada "comprimida";
- duas cópias possíveis na DLQ, que no próximo reprocess geram mais cadeias.

Os claims continuam exclusivos, então não há SENT duplo. Mas o histórico e as métricas de retry deixam de refletir a escada, e o `fail-until-reprocess`/`fail-N-then-ok` passa a se comportar de forma não determinística.

**Correção:** para PENDING e RETRYING, prefira preservar no fim da DLQ (`skipped`) enquanto o documento tiver `updatedAt` recente. Outra opção: não zerar `retryCount` nesses estados e publicar o token com o `retryCount` atual, para manter a cerca. Se o reset de não terminais continuar, documente o trade-off no DOC-03 e cubra com um IT (RETRYING + token vivo na fila de delay + cópia na DLQ).

### WR-04: `GET /notifications` sem autenticação expõe em massa os destinatários de todas as notificações

**Arquivos:**
- `notify-api/src/main/java/dev/notifyhub/api/web/dto/NotificationSummary.java:21-30`
- `notify-api/src/main/java/dev/notifyhub/api/application/NotificationQueryService.java:80-81`

**Problema:** a listagem pública e paginada (até 100 por página, sem chave) devolve `recipient` em claro, ou seja, e-mails e telefones. Antes desta fase, ler um destinatário exigia adivinhar um UUID. Agora `GET /notifications?size=100&page=N` enumera todos, e os ids devolvidos abrem o `GET /{id}` com `variables` e `rendered`.

Isso contradiz o modelo de ameaça que o próprio código usa:
- o 422 não ecoa o id "porque a API pública não tem autenticação" (`IdempotencyKeyReusedException`);
- os logs mascaram o destinatário (`RecipientRules.mask`).

O Javadoc do resumo também diz que ele exclui "PII", mas inclui o destinatário. O D-11 decidiu incluir `recipient`, mas a decisão não considerou essa exposição.

**Correção:** mascare no resumo, usando a regra que já existe nos logs:
```java
// ao montar a resposta da listagem
new NotificationSummary(s.id(), s.status(), s.channel(), RecipientRules.mask(s.channel(), s.recipient()), ...)
```
A alternativa é proteger `GET /notifications` e `/stats` com o mesmo `X-API-Key` do admin (`addPathPatterns("/admin/**", "/notifications")` com exclusão do POST). No mínimo, registre o risco na seção de limitações do README.

## Info

### IN-01: Descrições do Swagger com sequências de ~20 espaços no meio do texto

**Arquivos:**
- `notify-api/src/main/java/dev/notifyhub/api/admin/AdminController.java:79,120`
- `notify-api/src/main/java/dev/notifyhub/api/web/NotificationController.java:84`
- `notify-api/src/main/java/dev/notifyhub/api/web/NotificationQueryController.java:74,115`

**Problema:** cada text block está numa única linha física com blocos de espaços onde deveria haver quebra. Provavelmente o `\` de continuação se perdeu numa edição. O `/v3/api-docs` publica esses espaços. O `OpenApiConfiguration.java:26-31` usa `\` corretamente.
**Correção:** quebre as linhas com `\` no fim, como no `OpenApiConfiguration`.

### IN-02: `MAX_DESCRIPTION` com padrão e limite fixos no texto, embora sejam configuráveis

**Arquivo:** `notify-api/src/main/java/dev/notifyhub/api/admin/AdminController.java:53`
**Problema:** "padrão 100, de 1 a 1000" fica errado se `notify.admin.max-default`/`max-limit` mudarem.
**Correção:** use um texto genérico ("de 1 a notify.admin.max-limit") ou monte a descrição via `OpenApiCustomizer` a partir de `AdminProperties`.

### IN-03: O profile demo traz uma chave de admin padrão conhecida

**Arquivo:** `notify-api/src/main/resources/application-demo.yml:9`
**Problema:** com `demo` ativo e sem `NOTIFY_ADMIN_API_KEY`, `/admin/**` aceita `demo-admin-key`, que é pública no repositório. É intencional, mas o compose da Fase 4 pode expor a porta.
**Correção:** logue um WARN no startup quando a chave em uso for a de demo, e mantenha a porta da API presa a `127.0.0.1` no compose.

### IN-04: Reset gravado com publish falho deixa o documento PENDING enquanto a mensagem está na DLQ

**Arquivo:** `notify-api/src/main/java/dev/notifyhub/api/admin/DlqReprocessService.java:195-200`
**Problema:** depois do 503, o documento aparece como PENDING (em andamento) no GET e no stats, mas está parado na DLQ. Cada tentativa falha acrescenta mais um `REPROCESSED` ao histórico. A contagem de DEAD_LETTERED subestima o que está na DLQ. O comportamento está documentado, mas engana a operação.
**Correção:** num publish falho depois do reset, devolva o documento ao status anterior com uma cerca condicional (PENDING + `updatedAt` gravado), ou registre no `lastError` um código `REPROCESS_PUBLISH_FAILED`.

### IN-05: `NotificationRepublisher` escreve transições à mão, contra o contrato da tabela

**Arquivo:** `notify-api/src/main/java/dev/notifyhub/api/application/NotificationRepublisher.java:135-140,202-208`
**Problema:** o Javadoc de `NotificationStatus` diz que os filtros "são derivados dela, nunca escritos à mão". PUBLISH_FAILED → PENDING e PENDING → PUBLISH_FAILED estão literais aqui, sem `canTransitionTo`.
**Correção:** adicione `assert`/guarda `NotificationStatus.PUBLISH_FAILED.canTransitionTo(PENDING)` (e o inverso), ou corrija o Javadoc da tabela.

### IN-06: O `basicNack` do `finally` pode mascarar a exceção original e relatar BROKER_UNAVAILABLE

**Arquivo:** `notify-api/src/main/java/dev/notifyhub/api/admin/DlqReprocessService.java:158-163`
**Problema:** se `handle` lança um `RuntimeException` (bug) e o `basicNack` do `finally` também falha, porque o canal fechou, o `IOException` substitui a exceção original. Ela vira `AmqpException` no `execute` e é relatada como `BROKER_UNAVAILABLE`, e a causa real se perde.
**Correção:** no `finally`, envolva o nack em try/catch e use `addSuppressed` na exceção original, ou guarde a original antes.

### IN-07: `resetState()` dos ITs não espera entregas em voo (unacked)

**Arquivo:** `notify-it/src/test/java/dev/notifyhub/it/support/EndToEndSupport.java:341-356`
**Problema:** a espera olha só `readyCount` das filas principais e de delay. Uma mensagem que o worker está processando (unacked) pode publicar um token de retry ou uma cópia na DLQ **depois** do purge e vazar para o teste seguinte, que tem contagens exatas de DLQ (`ReprocessEdgeCasesIT.awaitReady`). O risco é baixo, porque a maioria dos testes espera o status final, mas alguns terminam com notificações em voo, como `IdempotencyIT.sameKeyWithADifferentPayloadIs422`.
**Correção:** inclua `unackedCount(queue) == 0` (API de gerenciamento, com o `atMost` já usado) na condição de espera, ou aguarde o status final em todos os testes que criam notificações.

---

_Revisado em: 2026-10-31T18:16:43Z_
_Revisor: Claude (gsd-code-reviewer)_
_Profundidade: standard_
