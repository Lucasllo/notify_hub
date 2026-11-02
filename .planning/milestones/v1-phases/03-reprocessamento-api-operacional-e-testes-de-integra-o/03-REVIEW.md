---
phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o
reviewed: 2026-11-01T01:00:16Z
depth: standard
files_reviewed: 14
files_reviewed_list:
  - notify-api/src/main/java/dev/notifyhub/api/application/NotificationRepublisher.java
  - notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java
  - notify-api/src/main/java/dev/notifyhub/api/application/PublishFailedException.java
  - notify-api/src/main/java/dev/notifyhub/api/application/RepublishInterruptedException.java
  - notify-api/src/main/java/dev/notifyhub/api/application/UnconfirmedPublishSweeper.java
  - notify-api/src/main/java/dev/notifyhub/api/application/UnconfirmedSweepProperties.java
  - notify-api/src/main/java/dev/notifyhub/api/config/ApiConfiguration.java
  - notify-api/src/main/resources/application.yml
  - notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java
  - notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java
  - notify-api/src/test/java/dev/notifyhub/api/application/UnconfirmedPublishSweeperTest.java
  - notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java
  - notify-it/src/test/java/dev/notifyhub/it/UnconfirmedPublishRecoveryIT.java
  - notify-it/src/test/resources/it/application-it.yml
findings:
  critical: 0
  warning: 7
  info: 11
  total: 18
status: issues_found
---

# Fase 03: Relatório de Code Review (incremental, pós gap-closure 03-08, 03-09 e 03-10)

**Revisado em:** 2026-11-01T01:00:16Z
**Profundidade:** standard
**Arquivos revisados:** 14 (diff `e5069b04..HEAD`)
**Status:** issues_found

## Resumo

Esta revisão é incremental. Ela cobre só os arquivos alterados pelos planos de gap-closure 03-08 (varredura de PENDING sem confirm), 03-09 (replay idempotente só com evidência) e 03-10 (rastro e resposta verdadeira do publish sem confirm). Este relatório substitui o anterior. Os achados anteriores em arquivos fora deste escopo foram mantidos como **abertos (não verificados nesta passada)**. O Maven não foi executado: há um `./mvnw verify` rodando em segundo plano.

Tracei os caminhos concorrentes da varredura, do republish cercado, do laço de replay e das esperas: criação × varredura, replay × varredura, replay × replay, replay × lote `republish-failed`, replay × worker e reset da DLQ × varredura. **Não encontrei caminho de perda silenciosa.** Todo PENDING sem mensagem é alcançado pela varredura em até `after` + `interval`. Todo PUBLISH_FAILED com mensagem em fila continua reivindicável pelo worker. Os fences (`status` + `updatedAt` lido; `status` = PUBLISH_FAILED) são atômicos por documento e se resolvem corretamente contra o `updateMulti` da varredura.

O preço da correção do WR-02 é alto, e o código o descreve errado:
- como o documento não guarda nenhuma evidência de confirm, todo replay de um PENDING **confirmado mas ainda não reivindicado** espera a janela inteira (10s) e publica uma cópia duplicada (WR-05). Isso acontece justamente com o worker atrasado ou fora;
- a latência do replay não é limitada a 2 × `confirm-timeout`, como diz o Javadoc. Ela chega a cerca de 7 × `confirm-timeout`, e replays concorrentes estendem a espera uns dos outros (WR-06);
- o fallback "publicar sem fence" responde 202 PENDING com o documento ainda PUBLISH_FAILED (WR-07);
- a falha do Mongo depois de um publish sem confirm agora é rastreável no log, mas o cliente continua recebendo um 500 genérico, sem `notificationId` (WR-08).

### Situação dos achados do relatório anterior

| ID anterior | Situação | Evidência |
|---|---|---|
| **CR-01** (PENDING órfão sem caminho de recuperação; replay 202 sem publicar) | **Resolvido** | Item 1: `UnconfirmedPublishSweeper` + `NotificationRepublisher.markUnconfirmedAsPublishFailed` (`:301-314`) levam o PENDING órfão a PUBLISH_FAILED/PUBLISH_UNCONFIRMED, com corte validado no startup (`UnconfirmedPublishSweeper.java:53-59`). Item 2: o replay de PENDING com a janela vencida republica com fence `status`+`updatedAt` lido (`NotificationRepublisher.java:220-223`). Item 3: ERROR com o id antes de relançar (`:175-182`) e detail do 503 do lote verdadeiro (`RepublishInterruptedException.java:36-39`). Os testes pedidos existem (`nackThenMongoDownWhileMarkingPublishFailedLogsTheIdAndPropagates`, `republishWithNackThenMongoDown...`, `batchWithNackThenMongoDown...`) e há o IT `UnconfirmedPublishRecoveryIT`. Ressalva: a resposta ao cliente nesse caso continua um 500 genérico (ver WR-08). |
| **WR-02** (replay de PENDING devolve 202 sem evidência) | **Resolvido**, com regressões novas | `NotificationService.replay`/`awaitPublishWindow` (`:170-243`): nenhum 202 de replay sai sem claim do worker ou confirm próprio. As regressões introduzidas estão em WR-05, WR-06 e WR-07. |
| WR-01 (documento ilegível trava a cabeça da DLQ) | **Aberto** (fora do escopo, não verificado) | `DlqReprocessService.java` e `NotificationResetService.java` não mudaram desde `e5069b04`. |
| WR-03 (reset de não terminais zera `retryCount`) | **Aberto** (fora do escopo, não verificado) | `NotificationResetService.java` não mudou. |
| WR-04 (`GET /notifications` expõe destinatários) | **Aberto** (fora do escopo, não verificado) | `NotificationSummary.java`/`NotificationQueryService.java` não mudaram. |
| IN-01, IN-02, IN-03, IN-06, IN-07 | **Abertos** (fora do escopo, não verificados) | Arquivos não mudaram. |
| IN-04 (reset com publish falho deixa PENDING com a mensagem na DLQ) | **Aberto, parcialmente mitigado** (não verificado) | Depois de `after` (2m), a varredura agora leva esse PENDING a PUBLISH_FAILED/PUBLISH_UNCONFIRMED. Ele passa a ser visível como falha, mas o `lastError` não diz que a causa foi o reprocessamento. |
| IN-05 (transições escritas à mão no `NotificationRepublisher`) | **Aberto** (no escopo, verificado) | Continua igual e ganhou mais uma ocorrência: `markUnconfirmedAsPublishFailed` grava PENDING → PUBLISH_FAILED literal (`:304-308`). |

## Narrative Findings (AI reviewer)

## Warnings

### WR-05: Todo replay de um PENDING confirmado e ainda não reivindicado espera 2 × `confirm-timeout` e publica uma cópia duplicada

**Arquivos:**
- `notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java:176-186`
- `notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java:223-243`
- `notify-api/src/main/java/dev/notifyhub/api/application/NotificationRepublisher.java:220-223`

**Problema:** depois de um confirm positivo, nada é gravado no documento (`NotificationService.java:123`). Por isso "PENDING publicado com ack, esperando na fila" é indistinguível de "PENDING órfão". O `awaitPublishWindow` só sai cedo quando o status ou o `updatedAt` mudam, e um PENDING confirmado não muda nenhum dos dois até o worker reivindicar. Na prática:

1. O cliente faz POST com a chave K, recebe 202 (ack) e repete o POST, por exemplo porque a resposta se perdeu ou o timeout HTTP dele expirou.
2. O worker está com backlog ou fora (é exatamente o cenário da demo de carga e de retry).
3. O replay dorme a janela inteira (`updatedAt` + 10s). No fim, o documento continua "igual", então ele faz o toque cercado e **publica outra cópia** (`republish` → `publishOrMarkFailed`).

Cada replay repetido adiciona mais uma cópia à fila principal. O toque também renova `updatedAt`, então o replay seguinte espera mais 10s. A cópia extra costuma ser descartada pelo claim. Mesmo assim, o Javadoc do `replay` (`:167-168`) afirma "sem segundo envio", e isso é forte demais: uma cópia de nível 0 que chega entre o `markRetrying` e o `advanceRetryLevel` do worker (`DeliveryProcessor.java:212-243`, janela que inclui a espera do confirm do token, até 5s) encontra `RETRYING` com `retryCount = 0 ≤ 0` e é reivindicada de novo. O resultado é uma cadeia de retry paralela, o mesmo efeito descrito no WR-03.

**Correção:** registre a evidência de confirm no próprio documento com um update cercado que não muda o status. Ele não viola a regra "nada depois do confirm", porque só casa se o documento ainda estiver PENDING com o `updatedAt` gravado por quem publicou:
```java
// NotificationRepublisher.publishOrMarkFailed, ramo result.ok()
mongoTemplate.updateFirst(
        query(where(Fields.ID).is(doc.id()).and(Fields.STATUS).is(NotificationStatus.PENDING)),
        new Update().set(Fields.PUBLISH_CONFIRMED_AT, Instant.now()),   // campo novo
        NotificationDocument.class);
```
Depois:
- no `replay`, trate `PENDING && publishConfirmedAt != null` como evidência (202 imediato, sem publicar);
- na varredura, acrescente `publishConfirmedAt` nulo ao filtro, o que também elimina o falso positivo do backlog;
- no `republish` (fence) e no reset da DLQ, faça `$unset` do campo.

Uma falha desse update depois do ack só degrada para o comportamento atual, então ela pode ser logada e ignorada.

### WR-06: A latência do replay não é limitada a 2 × `confirm-timeout`, e replays concorrentes estendem a espera uns dos outros

**Arquivos:**
- `notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java:172-205`
- `notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java:214-217` (Javadoc "no máximo 2 × confirm-timeout")

**Problema:** cada rodada recalcula a janela a partir do `updatedAt` **atual** (`publishWindowEnd(current)`). Um toque de outro replay muda o `updatedAt`, a rodada termina com `continue` e a rodada seguinte espera uma janela nova e completa. O pior caso é:
- 3 rodadas × 2 × `confirm-timeout` de espera;
- um `confirm-timeout` de publish dentro de alguma rodada;
- mais um `confirm-timeout` no publish sem fence depois do laço.

Isso dá cerca de 7 × 5s = 35s por requisição, sem deadline no servidor. O cenário que produz isso é o próprio uso esperado da chave: N clientes ou retries com a mesma K. Cada replay que ganha o fence toca o `updatedAt`, e os outros que estão esperando reiniciam a janela. Um cliente com timeout HTTP abaixo disso desiste e repete, criando mais um replay que vai tocar e republicar, enquanto o anterior continua rodando no servidor sem saber que o cliente sumiu. É um ciclo de realimentação de requisições penduradas e de cópias na fila (ver WR-05). O Javadoc promete "no máximo 2 × confirm-timeout", e isso é falso.

**Correção:** use um deadline único por replay, calculado uma vez no início, e respeite-o em todas as rodadas e no publish final:
```java
Instant deadline = Instant.now().plus(props.confirmTimeout().multipliedBy(PUBLISH_WINDOW_FACTOR));
...
NotificationDocument latest = awaitPublishWindow(current, min(publishWindowEnd(current), deadline));
...
if (Instant.now().isAfter(deadline)) {
    throw new ReplayInProgressException(current.id(), props.retryAfter()); // 409/425 + Retry-After
}
```
Corrija também o Javadoc para o limite real. Responder 409 "requisição original em andamento" com `Retry-After` é o padrão de várias APIs com Idempotency-Key, e quebra o ciclo.

### WR-07: O publish sem fence do fim do laço responde 202 PENDING com o documento ainda PUBLISH_FAILED e pula o republish cercado do estado recém-lido

**Arquivo:** `notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java:179-183,196-205`

**Problema:** há dois defeitos ligados.
1. Quando a **terceira** rodada termina pelo `continue` da linha 182, porque o documento mudou durante a espera (por exemplo, o vencedor gravou PUBLISH_FAILED), o laço acaba sem nunca ter tentado o republish cercado desse estado. O código cai direto no publish sem fence. O Javadoc fala em "três interferências concorrentes seguidas", mas basta uma mudança legítima em cada uma de três esperas, e esse é exatamente o padrão da corrida de replays do WR-06.
2. `publishOrMarkFailed(current)` não faz transição nenhuma. Com `current` em PUBLISH_FAILED, o ack devolve `replayed(current, PENDING)` (linha 205), mas o documento **continua PUBLISH_FAILED** com o `lastError` antigo. O cliente ouve PENDING. O `GET`/`/stats` mostram falha. O próximo `republish-failed` do operador publica mais uma cópia. Não há perda, porque o worker reivindica PUBLISH_FAILED, mas a resposta contradiz o estado gravado e gera envio redundante.

**Correção:** no fim do laço, se `current.status() == PUBLISH_FAILED`, faça uma última tentativa cercada (`republisher.republish(current)`) em vez do publish cru. Só use o publish sem fence para PENDING. Outra opção é contar só as rodadas que terminaram em `FENCE_LOST`, e não as que terminaram por mudança observada na espera:
```java
if (current.status() == NotificationStatus.PUBLISH_FAILED) {
    RepublishOutcome last = republisher.republish(current);
    if (last.kind() == REPUBLISHED) return replayed(current, PENDING);
    if (last.kind() == FAILED) throw new PublishFailedException(current.id(), props.retryAfter(), last.failure());
    current = reread(current.id());
    if (NotificationRepublisher.wasClaimedByWorker(current.status())) return replayed(current, current.status());
}
```

### WR-08: A falha do MongoDB depois de um publish sem confirm ainda chega ao cliente como um 500 genérico, sem `notificationId`, e numa criação sem chave isso leva a envio em dobro

**Arquivos:**
- `notify-api/src/main/java/dev/notifyhub/api/application/NotificationRepublisher.java:175-182`
- `notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java:120-122,264-270`
- relacionado: `notify-api/src/main/java/dev/notifyhub/api/web/ApiExceptionHandler.java` (sem handler para `DataAccessException`)

**Problema:** o 03-10 só loga ERROR e relança a `DataAccessException`. Como o `ApiExceptionHandler` não a trata, ela vira o 500 padrão do Boot: sem `notificationId`, sem `Retry-After` e sem indicar que a notificação **existe** e pode ser recuperada. O mesmo vale para uma falha do Mongo no meio do replay (`reread` em `awaitPublishWindow`, ou o fence do `republish`). A sequência é esta:
1. O cliente fez POST **sem** `Idempotency-Key`.
2. Recebe o 500 e faz o retry natural, criando uma segunda notificação, que é entregue.
3. Agora o 03-08 recupera a primeira: a varredura a leva a PUBLISH_FAILED depois de 2m, e o `republish-failed` do operador a entrega.

O destinatário recebe duas vezes. Antes do 03-08 o órfão se perdia. Agora ele é recuperado, mas o cliente não tem como saber disso.

**Correção:** no `create`, capture a `DataAccessException` que sai de `publishOrMarkFailed` e responda 503 com `Retry-After` e um problem que carregue o `notificationId` e diga que o estado é indeterminado e será reconciliado. Não afirme status nenhum:
```java
try {
    failure = republisher.publishOrMarkFailed(doc);
} catch (DataAccessException e) {
    throw new PublishOutcomeUnknownException(id, props.retryAfter()); // 503, type publish-outcome-unknown
}
```
Documente no Swagger do POST que um retry desse 503 deve usar `Idempotency-Key`, ou consultar `GET /notifications/{id}`, para não duplicar.

### WR-01 (mantido do relatório anterior): Uma mensagem cujo documento não pode ser mapeado trava a cabeça da DLQ para sempre

**Arquivos:** `notify-api/src/main/java/dev/notifyhub/api/admin/DlqReprocessService.java:110-128,188-193`, `notify-api/src/main/java/dev/notifyhub/api/application/NotificationResetService.java:78-88`
**Situação:** aberto. O arquivo está fora do escopo desta passada e não foi verificado de novo.
**Problema:** `ConversionFailedException`, `MappingInstantiationException` e o NPE de `status` nulo (`NotificationResetService.java:86`) não são `DataAccessException`. Eles viram nack+requeue para a cabeça da DLQ e um 500 a cada chamada.
**Correção:** trate como documento ilegível, com `catch (RuntimeException)`, e copie a mensagem para o fim da DLQ. Devolva `NotResettable(null)` quando o status for nulo.

### WR-03 (mantido do relatório anterior): O reset de PENDING/RETRYING para `retryCount` 0 reabre a cerca de token obsoleto do worker

**Arquivos:** `notify-api/src/main/java/dev/notifyhub/api/application/NotificationResetService.java:108-113`, `notify-common/src/main/java/dev/notifyhub/common/domain/NotificationStatus.java:36,45`
**Situação:** aberto. O arquivo está fora do escopo desta passada e não foi verificado de novo. O WR-05 acima cria mais uma fonte de tokens de nível 0 duplicados, o que agrava este achado.
**Correção:** preserve no fim da DLQ os estados não terminais com `updatedAt` recente, ou não zere `retryCount` neles. Outra opção é documentar o trade-off e cobri-lo com um IT.

### WR-04 (mantido do relatório anterior): `GET /notifications` sem autenticação expõe em massa os destinatários

**Arquivos:** `notify-api/src/main/java/dev/notifyhub/api/web/dto/NotificationSummary.java:21-30`, `notify-api/src/main/java/dev/notifyhub/api/application/NotificationQueryService.java:80-81`
**Situação:** aberto. Os arquivos estão fora do escopo desta passada e não foram verificados de novo.
**Correção:** mascare com `RecipientRules.mask` no resumo, ou proteja a listagem e o `/stats` com o `X-API-Key`.

## Info

### IN-08: Interrompida, a espera do replay segue para o publish em vez de abortar

**Arquivo:** `notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java:239-242`
**Problema:** o `InterruptedException` restaura o flag e devolve `latest`. O laço então faz o toque cercado e chama `publish` com a thread interrompida. O `NotificationPublisher.awaitConfirm` (`NotificationPublisher.java:107-109`) transforma a interrupção em `PUBLISH_TIMEOUT` na hora, mesmo que o `send` já tenha saído. Com isso, o documento é marcado PUBLISH_FAILED, o cliente recebe 503 e as rodadas seguintes não esperam mais nada. Tudo isso acontece no shutdown.
**Correção:** na interrupção, lance uma exceção 503 sem tocar nem publicar, por exemplo um `PublishFailedException` com um código `INTERRUPTED`, ou propague a interrupção.

### IN-09: `UnconfirmedSweepProperties.interval` não é usado, e o intervalo não é validado

**Arquivos:** `notify-api/src/main/java/dev/notifyhub/api/application/UnconfirmedSweepProperties.java:20`, `notify-api/src/main/java/dev/notifyhub/api/application/UnconfirmedPublishSweeper.java:89-90`
**Problema:** o `@Scheduled` lê `${notify.publish.unconfirmed-sweep.interval:30s}` direto, com o padrão duplicado. O componente `interval` do record nunca é lido. Um `interval: 0s` (ou um valor negativo) não é recusado como o `after` é. Com zero, a varredura passa a rodar em laço contra o Mongo.
**Correção:** valide `interval > 0` no construtor do sweeper e agende a partir do bean, com `fixedDelayString = "#{@unconfirmedSweepProperties.interval().toMillis()}"` ou com um `SchedulingConfigurer`. Outra opção é remover o componente do record.

### IN-10: Javadocs que afirmam mais do que o código garante

**Arquivos:**
- `notify-api/src/main/java/dev/notifyhub/api/application/PublishFailedException.java:13-15`: diz que, "se o MongoDB falhou na marcação", o documento fica PENDING. Nesse caso, porém, quem sai é a `DataAccessException`, e não este 503. O `PublishFailedException` com documento PENDING só acontece quando a releitura acha PENDING (outro ator o devolveu) ou no publish sem fence do WR-07.
- `notify-api/src/main/java/dev/notifyhub/api/application/UnconfirmedPublishSweeper.java:31-34`: "a varredura nunca alcança uma publicação ainda em voo". O `updatedAt` da criação é o instante **anterior** ao insert (`NotificationService.java:100`). Um `basicPublish` bloqueado por alarme de memória ou disco do RabbitMQ (connection.blocked) não tem timeout. Os dois podem passar de `after`. O efeito é inofensivo, porque PUBLISH_FAILED é reivindicável, mas a garantia escrita não existe.
- `notify-api/src/main/java/dev/notifyhub/api/application/NotificationService.java:167-168`: "sem segundo envio" (ver WR-05).

**Correção:** ajuste os textos para descrever o comportamento real.

### IN-11: Lacunas de teste nos ramos novos do replay

**Arquivos:** `notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java:403-473`, `notify-it/src/test/java/dev/notifyhub/it/UnconfirmedPublishRecoveryIT.java`
**Problema:** não há teste para:
- o ramo "`updatedAt` trocado por outro replay" (`NotificationService.java:179-183`);
- o esgotamento das rodadas por mudanças observadas na espera, que é o caminho do WR-07;
- uma `DataAccessException` no `reread` durante a espera;
- a interrupção.

Nenhum IT exercita a corrida real do WR-02 (dois POSTs simultâneos com a mesma chave nova). O teste `replayThatKeepsLosingTheFencePublishesWithoutFence...` só cobre o esgotamento por `FENCE_LOST` com PENDING antigo.
**Correção:** acrescente testes unitários com `findById` devolvendo PENDING com `updatedAt` novo em sequência, e um caso com `thenThrow(DataAccessResourceFailureException)`.

### IN-05 (mantido, verificado): `NotificationRepublisher` escreve transições à mão, contra o contrato da tabela

**Arquivo:** `notify-api/src/main/java/dev/notifyhub/api/application/NotificationRepublisher.java:215-219,304-308,325-327`
**Problema:** PUBLISH_FAILED → PENDING, PENDING → PUBLISH_FAILED (`markPublishFailed`) e agora também a varredura (`markUnconfirmedAsPublishFailed`) gravam status literais, sem `canTransitionTo`. O Javadoc de `NotificationStatus` diz que os filtros "são derivados dela, nunca escritos à mão".
**Correção:** adicione uma guarda `if (!PENDING.canTransitionTo(PUBLISH_FAILED)) throw new IllegalStateException(...)`, como o worker faz em `recordFailure`, ou corrija o Javadoc da tabela.

### IN-01 (mantido): Descrições do Swagger com sequências de cerca de 20 espaços no meio do texto

**Arquivos:** `notify-api/src/main/java/dev/notifyhub/api/admin/AdminController.java:79,120`, `notify-api/src/main/java/dev/notifyhub/api/web/NotificationController.java:84`, `notify-api/src/main/java/dev/notifyhub/api/web/NotificationQueryController.java:74,115`
**Situação:** aberto (fora do escopo, não verificado). **Correção:** quebre as linhas com `\` no fim.

### IN-02 (mantido): `MAX_DESCRIPTION` com padrão e limite fixos no texto

**Arquivo:** `notify-api/src/main/java/dev/notifyhub/api/admin/AdminController.java:53`
**Situação:** aberto (fora do escopo, não verificado). **Correção:** use um texto genérico ou um `OpenApiCustomizer`.

### IN-03 (mantido): O profile demo traz uma chave de admin padrão conhecida

**Arquivo:** `notify-api/src/main/resources/application-demo.yml:9`
**Situação:** aberto (fora do escopo, não verificado). **Correção:** logue um WARN no startup e prenda a porta a `127.0.0.1` no compose.

### IN-04 (mantido, parcialmente mitigado): Reset gravado com publish falho deixa o documento PENDING enquanto a mensagem está na DLQ

**Arquivo:** `notify-api/src/main/java/dev/notifyhub/api/admin/DlqReprocessService.java:195-200`
**Situação:** aberto (fora do escopo, não verificado). Depois de `after`, a varredura do 03-08 leva o documento a PUBLISH_FAILED/PUBLISH_UNCONFIRMED. Ele passa a ser visível, mas com um código que não aponta o reprocessamento como causa. **Correção:** volte ao status anterior com uma cerca condicional, ou grave o código `REPROCESS_PUBLISH_FAILED`.

### IN-06 (mantido): O `basicNack` do `finally` pode mascarar a exceção original

**Arquivo:** `notify-api/src/main/java/dev/notifyhub/api/admin/DlqReprocessService.java:158-163`
**Situação:** aberto (fora do escopo, não verificado). **Correção:** envolva o nack em try/catch e use `addSuppressed`.

### IN-07 (mantido): `resetState()` dos ITs não espera entregas em voo (unacked)

**Arquivo:** `notify-it/src/test/java/dev/notifyhub/it/support/EndToEndSupport.java:341-356`
**Situação:** aberto (fora do escopo, não verificado). **Correção:** inclua `unackedCount(queue) == 0` na condição de espera.

---

_Revisado em: 2026-11-01T01:00:16Z_
_Revisor: Claude (gsd-code-reviewer)_
_Profundidade: standard_
