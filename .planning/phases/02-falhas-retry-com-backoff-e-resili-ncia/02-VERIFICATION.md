---
phase: 02-falhas-retry-com-backoff-e-resili-ncia
verified: 2026-10-20T22:39:43Z
status: gaps_found
score: 52/56 must-haves verified
covered_files:
  - .planning/REQUIREMENTS.md
  - .planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/02-01-PLAN.md
  - .planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/02-01-SUMMARY.md
  - .planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/02-02-PLAN.md
  - .planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/02-02-SUMMARY.md
  - .planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/02-03-PLAN.md
  - .planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/02-03-SUMMARY.md
  - .planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/02-04-PLAN.md
  - .planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/02-04-SUMMARY.md
  - .planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/02-05-PLAN.md
  - .planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/02-05-SUMMARY.md
  - .planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/02-06-PLAN.md
  - .planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/02-06-SUMMARY.md
  - .planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/02-07-PLAN.md
  - .planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/02-07-SUMMARY.md
  - notify-common/src/main/java/dev/notifyhub/common/domain/AttemptOutcome.java
  - notify-common/src/main/java/dev/notifyhub/common/domain/FailureClass.java
  - notify-common/src/main/java/dev/notifyhub/common/domain/NotificationStatus.java
  - notify-common/src/main/java/dev/notifyhub/common/messaging/NotifyHeaders.java
  - notify-common/src/test/java/dev/notifyhub/common/domain/NotificationStatusTest.java
  - notify-worker/pom.xml
  - notify-worker/src/main/java/dev/notifyhub/worker/channel/AttemptContext.java
  - notify-worker/src/main/java/dev/notifyhub/worker/channel/ChannelAdapter.java
  - notify-worker/src/main/java/dev/notifyhub/worker/channel/MagicRecipients.java
  - notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulatedChannelAdapter.java
  - notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulatedDeliveryException.java
  - notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulationConfiguration.java
  - notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulationProperties.java
  - notify-worker/src/main/java/dev/notifyhub/worker/failure/DeliveryFailureException.java
  - notify-worker/src/main/java/dev/notifyhub/worker/failure/ErrorCode.java
  - notify-worker/src/main/java/dev/notifyhub/worker/failure/FailureClassifier.java
  - notify-worker/src/main/java/dev/notifyhub/worker/failure/PermanentDeliveryException.java
  - notify-worker/src/main/java/dev/notifyhub/worker/failure/TransientDeliveryException.java
  - notify-worker/src/main/java/dev/notifyhub/worker/listener/ChannelListeners.java
  - notify-worker/src/main/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisher.java
  - notify-worker/src/main/java/dev/notifyhub/worker/metrics/DeliveryMetrics.java
  - notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java
  - notify-worker/src/main/java/dev/notifyhub/worker/processing/NotificationStateService.java
  - notify-worker/src/main/java/dev/notifyhub/worker/processing/RetryPolicy.java
  - notify-worker/src/main/java/dev/notifyhub/worker/resilience/ChannelGateway.java
  - notify-worker/src/main/java/dev/notifyhub/worker/resilience/NonTransientFailurePredicate.java
  - notify-worker/src/main/java/dev/notifyhub/worker/template/JsonValueEscaper.java
  - notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateNotFoundException.java
  - notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateRenderer.java
  - notify-worker/src/main/resources/application-demo.yml
  - notify-worker/src/main/resources/application.yml
  - notify-worker/src/test/java/dev/notifyhub/worker/channel/MagicRecipientsTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/channel/SimulatedChannelAdapterTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/config/ResilienceSettingsTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/config/WorkerSettingsTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/failure/FailureClassifierTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/listener/ChannelListenersDispatchTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/listener/ChannelListenersTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisherTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/metrics/DeliveryMetricsTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/processing/NotificationStateServiceTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/processing/RetryPolicyTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/resilience/ChannelGatewayTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/resilience/ResilienceFixtures.java
  - notify-worker/src/test/java/dev/notifyhub/worker/template/SeedTemplatesTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java
  - pom.xml
  - scripts/smoke-fase2.sh
  - scripts/smoke-lib.sh
covered_digest: "v1:sha256:0675a8be78818cdba8a2ce2b6b9df2d1008d9875ba97044d16ab9dd5460ec649"
behavior_unverified: 1
overrides_applied: 0
gaps:
  - truth: "Edge DLV-04 (boundary): h ≥ N, inclusive header adulterado, não publica em delay e vai para DEAD_LETTERED (02-02); e toda falha depois do claim grava exatamente um AttemptEntry (02-01)"
    status: partial
    reason: "Com notify-retry-count = 2147483647 (Integer.MAX_VALUE), que RetryHeaders.parse aceita, RetryPolicy.decide estoura: retryCount + 1 vira Integer.MIN_VALUE, passa em `next <= levels.size()` e levels.get(Integer.MAX_VALUE) lança ArrayIndexOutOfBoundsException. A exceção sai de onFailure (dentro do catch de process), o container rejeita para a DLQ sem x-failure-class, nenhuma tentativa é gravada e o documento fica PROCESSING com retryCount = MAX_VALUE, fora do alcance de qualquer token legítimo. Reproduzido com jshell sobre as classes compiladas (ver Spot-checks). Mesma causa do WR-02 do 02-REVIEW.md."
    artifacts:
      - path: "notify-worker/src/main/java/dev/notifyhub/worker/processing/RetryPolicy.java"
        issue: "decide() usa `retryCount + 1` sem proteção de overflow (linhas 26-31)"
      - path: "notify-worker/src/main/java/dev/notifyhub/worker/listener/RetryHeaders.java"
        issue: "aceita qualquer valor até Integer.MAX_VALUE; não há teto razoável"
      - path: "notify-worker/src/test/java/dev/notifyhub/worker/processing/RetryPolicyTest.java"
        issue: "tamperedLevelAboveTheLadderIsExhausted cobre só valor pequeno (7), não o limite do int"
    missing:
      - "RetryPolicy.decide: testar `retryCount >= levels.size()` antes de somar (esgotado sem overflow)"
      - "Teste em RetryPolicyTest com Integer.MAX_VALUE (e opcionalmente teto em RetryHeaders, ex.: 1000, rejeitado antes do claim)"
behavior_unverified_items:
  - truth: "Edge DLV-04 (concurrency, verification: backstop): worker derrubado entre o update RETRYING e o publish, ou entre o confirm e o ack, não deixa notificação RETRYING sem mensagem em fila e gera no máximo uma tentativa a mais"
    test: "Com o profile demo, provocar uma falha transitória e derrubar o worker (taskkill //F) logo depois do log 'Notificação RETRYING' e antes do ack; repetir derrubando entre o update RETRYING e o publish (ex.: breakpoint ou latência injetada no publisher)"
    expected: "A original volta com redelivered=true e é reprocessada no nível h; a notificação nunca fica RETRYING sem token em fila. Atenção ao WR-01: no caso confirm→queda→ack, o código atual pode gerar dois tokens h+1 e mais de uma tentativa extra (nunca SENT duplo)"
    why_human: "Truth marcada como backstop (não inferível): presença do filtro do claim e testes com MongoBson não exercitam as janelas de queda; o smoke do 02-07 só derruba o worker durante a latência do adapter (PROCESSING), não nessas janelas"
human_verification:
  - test: "Com a infra no ar, rodar `bash scripts/smoke-fase2.sh --sem-build` e, durante o critério 1, abrir http://localhost:15672 (notify/notify) → Queues"
    expected: "notify.email.retry.1s, .5s e .25s recebem a mensagem em sequência e, ao final, notify.email.dlq tem a mensagem com x-failure-class (aba Get messages); o smoke termina com SMOKE FASE 2: OK"
    why_human: "O critério 1 pede passagem visível pelas filas de delay; o smoke com Docker não foi reexecutado nesta verificação (evidência de runtime vem só do SUMMARY do 02-07)"
  - test: "Abrir http://localhost:8080/notifications/<id> de uma always-transient e http://localhost:8081/actuator/prometheus"
    expected: "GET mostra 4 tentativas legíveis (nível, código, classe); o scrape mostra notify_delivery_* por canal e resilience4j_circuitbreaker_state"
    why_human: "Legibilidade da demo para quem avalia o portfólio (human-check harvestado do 02-07)"
  - test: "Decidir o destino do CR-01 (falha do Mongo no claim ou no update de resultado estaciona a mensagem na DLQ com o documento em PENDING/RETRYING/PROCESSING)"
    expected: "Ou aceitar como override e registrar como entrada explícita do planejamento da Fase 3 (reset administrativo precisa aceitar esses estados; DLQ não pode travar o reprocessamento), ou abrir gap closure na Fase 2"
    why_human: "Escalation gate: o truth do 02-01 'o documento continua recuperável' depende do desenho do reprocessamento da Fase 3; não é falsificável só com o código atual, mas nenhum mecanismo da Fase 2 recupera um documento PROCESSING cuja mensagem foi para a DLQ"
---

# Fase 2: Falhas, retry com backoff e resiliência — Relatório de Verificação

**Objetivo da fase:** Toda falha de entrega tem destino previsível e visível: transitórias percorrem a escada de delay com backoff exponencial e, esgotadas, terminam DEAD_LETTERED na DLQ; permanentes vão direto para FAILED na DLQ; circuit breaker e bulkhead protegem cada canal; e tudo é demonstrável de forma determinística com destinatários mágicos e mensurável por métricas.
**Verificado em:** 2026-10-20T22:39:43Z
**Status:** gaps_found
**Re-verificação:** Não (verificação inicial)

## Resumo

Os cinco critérios de sucesso do ROADMAP têm implementação real, ligada e coberta por testes unitários que exercitam o comportamento (não só a presença dos símbolos). Os 12 requisitos estão atendidos. A fase **quase** fecha: há uma lacuna concreta e reproduzida (overflow do `RetryPolicy` com header `notify-retry-count = Integer.MAX_VALUE`), que falsifica dois truths dos planos 02-01/02-02. A correção é pequena. Um ponto de desenho (CR-01) precisa de decisão humana. O smoke de runtime com Docker não foi reexecutado: a evidência de runtime vem do SUMMARY do 02-07, e por isso o painel e o smoke ficam como verificação humana.

## Cumprimento do objetivo

### Critérios de sucesso do ROADMAP

| # | Critério | Status | Evidência |
|---|----------|--------|-----------|
| 1 | always-transient passa pelas 3 filas de delay, fica RETRYING, termina DEAD_LETTERED na DLQ com uma tentativa por nível (erro e classe) no GET | ✓ VERIFIED (parte visual → humano) | `DeliveryProcessor.onTransientFailure` → `markRetrying` → `publishToRetry` (routing key via `Channel.retryKey(Duration)`) → `onLadderExhausted` → `markDeadLettered` + cópia TRANSIENT. `DeliveryFlowTest.alwaysTransientWalksTheWholeLadderAndEndsDeadLetteredOnTheFourthAttempt` e `transientFailureOnTheLastLevelEndsDeadLetteredWithATransientDlqCopy` passam. `NotificationView.AttemptView` expõe `retryLevel`, `errorCode`, `failureClass`. O smoke `smoke-fase2.sh` (linhas 160-191) checa RETRYING, `retry.25s`, 4 tentativas e o header, mas não foi reexecutado |
| 2 | always-permanent, template inexistente e variável obrigatória ausente → FAILED direto, sem delay, DLQ com `x-failure-class: PERMANENT` | ✓ VERIFIED | `onTerminalFailure` → `markFailed` + `publishToDlq(…, PERMANENT/UNKNOWN, h)`, sem chamar `publishToRetry`. Testes que passam: `alwaysPermanentEndsFailedOnTheFirstAttemptWithAPermanentDlqCopy`, `missingTemplateSendsAConfirmedCopyToTheChannelDlqWithTheFailureClass`, `TemplateRendererTest.missingRequiredVariableIsPermanentMissingVariable` e `DeliveryRoutingPublisherTest.dlqCopyGoesToTheDeadLetterExchangeWithOnlyTheFailureHeaders` |
| 3 | fail-N-then-ok termina SENT após N retries; PROCESSING deixado por worker derrubado é retomado na redelivery sem envio duplicado | ✓ VERIFIED (runtime → humano) | `SimulatedChannelAdapter.applyMagic` usa `context.retryCount() < N`. `failTwiceThenOkIsDeliveredOnTheThirdAttemptAtLevelTwo` passa. O claim tem um ramo `reclaimableWhenStuck()` com `redelivered` ou `leaseUntil < now` e fence `retryCount ≤ h`. O flag chega do listener (`AmqpHeaders.REDELIVERED`). Testes: `redeliveredMessageReclaimsAStuckProcessingDocumentAndEndsSent`, `redeliveredClaimReclaimsProcessingWithoutLeaseCondition`, `firstDeliveryOnlyReclaimsProcessingWhoseLeaseHasExpired` e `ChannelListenersDispatchTest`. O kill real (taskkill) só está no smoke, não reexecutado |
| 4 | CB abre com taxa alta e bulkhead recusa excesso, ambos → RETRYING (nunca FAILED); `/actuator/prometheus` com contadores por canal e timer de latência | ✓ VERIFIED (runtime → humano) | `ChannelGateway`: bulkhead → CB → adapter. O classificador mapeia `CallNotPermittedException`/`BulkheadFullException` para TRANSIENT. Testes: `tenTransientFailuresOpenTheCircuitBreaker…`, `fullBulkheadRefusesTheSecondCallImmediately…`, `openCircuitBreakerRefusalIsATransientCircuitOpenRetryAndNeverFailed` e `DeliveryMetricsTest` (com `PrometheusMeterRegistry`). `management.endpoints.web.exposure.include: health,info,prometheus` e `micrometer-registry-prometheus` estão no pom |
| 5 | E-mail com HTML escapado, SMS sem escape, webhook JSON válido com aspas; `./mvnw test` roda os testes do renderizador sem Docker | ✓ VERIFIED | Três compiladores em `TemplateRenderer` (HTML, PLAIN e JSON com `JsonValueEscaper`), parse estrito do webhook e 20 testes em `TemplateRendererTest` (ex.: `seedPasswordResetWebhookStaysValidJsonWithQuotesBackslashAndControls`). Não há Testcontainers/`@ServiceConnection`/`*IT.java` em `src/test`. A execução nesta verificação passou |

### Truths dos planos (agrupados)

| Plano | Truths | Status | Observações |
|-------|--------|--------|-------------|
| 02-01 (DLV-03, DLV-06) | 11 | 9 ✓, 1 ✗, 1 ? | ✗ "Toda falha depois do claim grava exatamente um AttemptEntry": falha com header MAX_VALUE (gap). ? "Falha do Mongo no claim/update… o documento continua recuperável": CR-01, escalado. Os demais (classificador ordenado, cópia confirmada com CorrelationData UUID, reject sem requeue, UNKNOWN → FAILED, fence perdido sem publicar, config do yml) foram conferidos no código e em testes |
| 02-02 (DLV-04, DLV-05, WRK-03) | 11 | 9 ✓, 1 ✗, 1 ⚠️ | ✗ boundary "qualquer h ≥ N → DEAD_LETTERED": falha com MAX_VALUE (gap). ⚠️ concurrency (backstop): `insufficient_spec`, vai para humano. Ordem do D-11, `retryKey(Duration)`, REPUBLISH_FAILED → DEAD_LETTERED sem tentativa extra, claim derivado da tabela e flag `redelivered`: verificados |
| 02-03 (TPL-02, TPL-03, TST-01) | 8 | 8 ✓ | Construtor e assinatura preservados. `requiredVariables` checado antes de renderizar. `SeedTemplatesTest` barra `{{{`/`{{&` |
| 02-04 (DLV-02) | 8 | 8 ✓ | Convenção exata (`fail.test`, SMS `+550000000` + código). Falha lançada depois da latência. `magic-recipients` com padrão true, conforme o plano (ver WR-07) |
| 02-05 (RES-01, RES-02) | 8 | 8 ✓ | `registry.find` + `orElseThrow`. Validação de open-wait ≤ 1º nível. `NonTransientFailurePredicate`. Semáforo 8 / wait 0. Sem `register-health-indicator` |
| 02-06 (OBS-01) | 5 | 5 ✓ | 42 séries pré-registradas. Contadores depois do fence/confirm. Timer só em volta do adapter, dentro do CB. Tags fechadas |
| 02-07 | 10 | incorporados aos critérios 1-5 | Asserções do smoke conferidas no script (substantivo, 378 linhas). Execução não reexecutada → humano |

**Pontuação:** 52/56 truths verificados (5 critérios + 51 truths dos planos 02-01..02-06): 2 falhos (mesma causa), 1 incerto (CR-01) e 1 presente mas com comportamento não verificado (backstop).

### Proibições (test-tier)

| Plano | Proibição | Status | Evidência de enforcement |
|-------|-----------|--------|--------------------------|
| 02-01 | Sem PII na cópia da DLQ, headers, lastError e logs | ✓ | O token `NotificationMessage(notificationId, channel, templateCode, createdAt)` não tem destinatário nem variáveis. `FailureClassifierTest` confirma que a mensagem não contém `ana@exemplo.com`. `dlqCopyGoesToTheDeadLetterExchangeWithOnlyTheFailureHeaders`. Ressalva WR-05 (lastError de republicação usa texto do broker sem truncar; não é PII da notificação) |
| 02-02 | Transitória nunca vira FAILED | ✓ | Vários `verify(state, never()).markFailed(…)` em `DeliveryProcessorTest` |
| 02-02 | Status nunca diverge de onde a mensagem está | ✓ (caminho classificado) | Testes de fence perdido (10). Fora do caminho classificado, CR-01 e o gap WR-02 deixam o documento PROCESSING com a mensagem na DLQ |
| 02-04 | Mágico só em padrões exatos | ✓ | `MagicRecipientsTest`: `always-transient@fail.test.com.br` e `https://fail.test.evil.com/...` não casam. Ressalva IN-03 (`getPath` decodificado) |
| 02-05 | Recusa de CB/bulkhead nunca FAILED | ✓ | `openCircuitBreakerRefusalIsATransientCircuitOpenRetryAndNeverFailed` e `DeliveryFlowTest` linha 623 |
| 02-06 | Timer sem recusas e sem tags de alta cardinalidade | ✓ | `openCircuitBreakerRefusalIsCountedAsRejectionAndNeverTimed`, `fullBulkheadRefusalIsCountedAsRejectionAndNeverTimed` e `everyMeterOfTheSameNameHasTheSameTagKeys` |

### Artefatos obrigatórios

`gsd-tools verify.artifacts`: 27/27 aprovados (02-01 6/6, 02-02 5/5, 02-03 3/3, 02-04 4/4, 02-05 4/4, 02-06 3/3, 02-07 2/2). Todos têm conteúdo substantivo (lidos no código) e estão ligados: o `DeliveryProcessor` usa o gateway, o classificador, o publisher, a `RetryPolicy` e as métricas; o listener repassa `redelivered`.

### Verificação de key links

`gsd-tools verify.key-links`: 25/25 verificados. Conferidos à mão: `classifier.classify(`, `markFailed(`, `publishToDlq(`, `publishToRetry(`, `RetryPolicy.decide(`, `retryKey(`, `AmqpHeaders.REDELIVERED`, `reclaimableWhenStuck()`, `gateway.send(`, `CircuitBreaker.decorateRunnable(`, `NonTransientFailurePredicate` no yml, `metrics.recordAdapterCall`/`rejected` e `actuator/prometheus` no smoke.

### Rastreamento do fluxo de dados (nível 4)

| Artefato | Dado | Fonte | Dado real | Status |
|----------|------|-------|-----------|--------|
| GET /notifications/{id} (attempts) | `AttemptEntry` (retryLevel, errorCode, failureClass) | `$push` com `$slice -50` em `recordFailure`/`markSent` a partir da `Classification` real | Sim | ✓ FLOWING |
| `/actuator/prometheus` | `notify_delivery_*` | `DeliveryMetrics`, incrementado nos pontos de resultado do `DeliveryProcessor`/`ChannelGateway` | Sim | ✓ FLOWING |
| Cópia na DLQ | header `x-failure-class` | `Classification.failureClass()` / TRANSIENT fixo na escada | Sim | ✓ FLOWING |

### Spot-checks de comportamento

| Comportamento | Comando | Resultado | Status |
|---------------|---------|-----------|--------|
| Testes do worker (renderer, política, gateway, métricas, estado, fluxo) | `./mvnw -q -pl notify-worker -am test -Dtest=TemplateRendererTest,SeedTemplatesTest,RetryPolicyTest,ChannelGatewayTest,DeliveryMetricsTest,NotificationStateServiceTest` | EXIT=0, 0 falhas | ✓ PASS |
| Suíte completa (orquestrador) | `./mvnw test` | 431 testes, 0 falhas (api 34, common 75, worker 322) | ✓ PASS |
| `./mvnw test` sem Docker | busca por Testcontainers/`@ServiceConnection`/`*IT.java` em `src/test` | nenhum resultado | ✓ PASS |
| `RetryPolicy.decide(7, 3 níveis)` | jshell sobre `target/classes` | `exhausted=true` | ✓ PASS |
| `RetryHeaders.parse("2147483647")` + `RetryPolicy.decide(Integer.MAX_VALUE, 3 níveis)` | jshell sobre `target/classes` | aceita 2147483647; `ArrayIndexOutOfBoundsException: Index 2147483647 out of bounds for length 3` | ✗ FAIL (gap) |

### Execução de probes

Não há `scripts/*/tests/probe-*.sh` nem probes declarados nos planos. O smoke `scripts/smoke-fase2.sh` exige Docker e subir API/worker. Por isso não foi rodado nesta verificação (restrição: não subir serviços) e ficou como verificação humana.

### Cobertura de requisitos

| Requisito | Plano | Descrição | Status | Evidência |
|-----------|-------|-----------|--------|-----------|
| WRK-03 | 02-02, 02-07 | PROCESSING preso recuperável na redelivery (redelivered ou lease) | ✓ SATISFIED | Ramo `reclaimableWhenStuck()` do claim e flag do listener, com testes. Ressalva WR-08: o lease só é avaliado quando chega uma mensagem |
| TPL-02 | 02-03 | Renderizador estrito: variável obrigatória ausente ou template inexistente → permanente | ✓ SATISFIED | `requireVariables`, `TemplateNotFoundException`, JMustache sem default. Ressalva WR-06: seções `{{#x}}` em EMAIL/SMS somem em silêncio. Nenhum seed usa seções (grep), então não contradiz o requisito hoje |
| TPL-03 | 02-03 | Escape por canal | ✓ SATISFIED | Compiladores HTML/PLAIN/JSON e testes |
| DLV-02 | 02-04 | Destinatários mágicos determinísticos | ✓ SATISFIED | `MagicRecipients` + adapter, 69 + 23 testes |
| DLV-03 | 02-01, 02-05 | Classificador exaustivo (inclui CB, bulkhead e timeout do Mongo) | ✓ SATISFIED | `FailureClassifier` (13 testes). O timeout do Mongo no lookup de template entra no classificador. No claim e no update de resultado não entra, por desenho (ver CR-01) |
| DLV-04 | 02-02 | Republish no próximo nível com `notify-retry-count`, confirm antes do ack, RETRYING | ✓ SATISFIED | `onTransientFailure` e `publishToRetry` com confirm síncrono. Borda MAX_VALUE é o gap |
| DLV-05 | 02-02 | Escada esgotada → DEAD_LETTERED + DLQ | ✓ SATISFIED (borda aberta) | `onLadderExhausted`. O gap MAX_VALUE deixa um caso de header adulterado sem DEAD_LETTERED |
| DLV-06 | 02-01 | Permanente → FAILED sem retries, DLQ com `x-failure-class: PERMANENT` | ✓ SATISFIED | `onTerminalFailure` com testes |
| RES-01 | 02-05 | CB por canal, só transitórias | ✓ SATISFIED | `ChannelGateway`, `NonTransientFailurePredicate`, `ResilienceSettingsTest` |
| RES-02 | 02-05 | Bulkhead por semáforo; cheio = transitória | ✓ SATISFIED | Semáforo 8 < concorrência 10, wait 0, classificador |
| OBS-01 | 02-06 | Contadores por canal pré-registrados e timer de latência | ✓ SATISFIED | `DeliveryMetrics` + `DeliveryMetricsTest` |
| TST-01 | 02-03 | Testes unitários do renderizador | ✓ SATISFIED | `TemplateRendererTest` (20) e `SeedTemplatesTest` (10), sem Docker |

Requisitos órfãos: nenhum. Os 12 IDs mapeados para a Fase 2 em REQUIREMENTS.md aparecem no `requirements:` de algum plano.

### Anti-padrões e achados do code review (conferidos no código)

| Arquivo | Linha | Achado | Severidade | Impacto |
|---------|-------|--------|------------|---------|
| `processing/RetryPolicy.java` | 26-31 | Overflow com `retryCount = Integer.MAX_VALUE` (WR-02), reproduzido | 🛑 Blocker (gap) | Falsifica os truths de borda do 02-01/02-02 |
| `processing/DeliveryProcessor.java` | 53-54, 114, 150, 205, 228, 262 | Falha do Mongo no claim/update → DLQ com o documento não terminal (CR-01). O Javadoc promete "continua recuperável" | ⚠️ Warning (escalado) | Nada da Fase 2 recupera um documento PROCESSING cuja mensagem foi para a DLQ. A tabela de status não permite reset de PENDING/RETRYING/PROCESSING. É entrada obrigatória para a Fase 3 |
| `processing/NotificationStateService.java` | 86-93 | Token do mesmo nível reivindica RETRYING de novo após confirm+queda (WR-01) | ⚠️ Warning | Tentativas extras e tokens duplicados na escada (nunca SENT duplo). At-least-once documentado como Pergunta em aberto 6 |
| `channel/SimulatedChannelAdapter.java` / `messaging/DeliveryRoutingPublisher.java` | 83-85 / 101-103 | Interrupção tratada como timeout (WR-03) | ⚠️ Warning | No shutdown, pode gravar DEAD_LETTERED com token h+1 vivo, que é descartado depois. A mensagem fica na DLQ |
| `processing/DeliveryProcessor.java` | 108-151 | Canal do documento e canal da mensagem misturados (WR-04) | ⚠️ Warning | Só com token adulterado |
| `processing/DeliveryProcessor.java` | 244-247 | lastError da republicação sem truncar (WR-05) | ⚠️ Warning | Texto do broker, sem PII da notificação |
| `template/TemplateRenderer.java` | 53-54 | `strictSections` só no JSON (WR-06) | ⚠️ Warning | Latente: os seeds não usam seções |
| `resources/application.yml` | 71 | `magic-recipients` true por padrão (WR-07) | ⚠️ Warning | Escolha explícita do plano 02-04. Risco de abrir o CB de propósito fora da demo |
| `processing/NotificationStateService.java` | 66-71 | Nada varre leases vencidos (WR-08) | ⚠️ Warning | Mesma raiz do CR-01 |

Marcadores de dívida (TBD/FIXME/XXX) e TODO/HACK em `notify-worker/src/main`, `notify-common/src/main` e nos smokes: nenhum.

### Verificação humana necessária

1. **Smoke de runtime e painel do RabbitMQ.** Rodar `bash scripts/smoke-fase2.sh --sem-build` com a infra no ar e acompanhar `notify.email.retry.1s/.5s/.25s` e `notify.email.dlq` (header `x-failure-class`) em http://localhost:15672. Esperado: `SMOKE FASE 2: OK` e passagem visível pela escada. Precisa de humano porque o smoke não foi reexecutado nesta verificação e a "passagem visível" é julgamento humano (harvestado do 02-07).
2. **Legibilidade do GET e do scrape.** O GET de uma `always-transient` deve mostrar 4 tentativas com nível, código e classe, e `/actuator/prometheus` deve mostrar `notify_delivery_*` e o estado do CB (harvestado do 02-07).
3. **Janelas de queda da escada (truth backstop do 02-02).** Derrubar o worker entre RETRYING e o publish, e entre o confirm e o ack. A notificação nunca pode ficar RETRYING sem token. Avaliar o WR-01 (tokens h+1 duplicados).
4. **Decisão sobre o CR-01 (escalation gate).** Aceitar via override e levar para o planejamento da Fase 3 (o reset administrativo precisa aceitar documentos PENDING/RETRYING/PROCESSING com lease vencido cuja mensagem está na DLQ, ou criar um sweeper), ou corrigir já na Fase 2. Se aceitar, adicionar ao frontmatter:

```yaml
overrides:
  - must_have: "Falha do Mongo no claim ou no update de resultado não passa pelo classificador: sobe para o container (reject sem requeue → DLQ) e o documento continua recuperável"
    reason: "Recuperação do documento não terminal fica com o reprocessamento da Fase 3 (ADM-01), que passa a aceitar PENDING/RETRYING/PROCESSING com mensagem na DLQ"
    accepted_by: "Lucas Lopes"
    accepted_at: "<ISO timestamp>"
```

### Resumo das lacunas

**Uma lacuna bloqueante, com uma única causa raiz.** `RetryHeaders.parse` aceita `notify-retry-count` até `Integer.MAX_VALUE` (valor que o modelo de ameaça T-01-24 prevê vindo do painel), e `RetryPolicy.decide` soma 1 sem proteção. Com uma falha transitória nesse nível, `levels.get(Integer.MAX_VALUE)` estoura fora do tratamento: nenhuma tentativa é gravada, a mensagem vai para a DLQ sem `x-failure-class` e o documento fica PROCESSING com um `retryCount` que nenhum token legítimo alcança. Isso contradiz dois truths explícitos ("qualquer h ≥ N … vai para DEAD_LETTERED" e "toda falha depois do claim grava exatamente um AttemptEntry"). A correção é de duas linhas em `RetryPolicy`, mais um teste de fronteira, e opcionalmente um teto em `RetryHeaders`. Nenhuma fase posterior cobre isso, então a lacuna não foi adiada.

**Fora isso, o objetivo da fase está atingido no código.** As falhas classificadas têm destino previsível (RETRYING → DEAD_LETTERED, FAILED e cópia com `x-failure-class`), CB e bulkhead viram retries, os destinatários mágicos são determinísticos e as métricas são pré-registradas e expostas. Os critérios 1, 3 e 4 têm uma parte de runtime/visual que depende do smoke com Docker, não reexecutado aqui.

**Escalado para decisão humana:** o CR-01 (falha de infraestrutura no claim/update deixa o documento não terminal com a mensagem na DLQ). Não é coberto por nenhum critério de sucesso da Fase 2, mas desafia o Core Value na Fase 3 se o reprocessamento não tratar esses estados.

---

_Verificado em: 2026-10-20T22:39:43Z_
_Verificador: Claude (gsd-verifier)_
