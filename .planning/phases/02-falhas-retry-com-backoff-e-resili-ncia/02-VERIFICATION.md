---
phase: 02-falhas-retry-com-backoff-e-resili-ncia
verified: 2026-10-20T23:48:26Z
status: passed
score: 64/65 must-haves verified
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
  - .planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/02-08-PLAN.md
  - .planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/02-08-SUMMARY.md
  - .planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/deferred-items.md
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
  - notify-worker/src/main/java/dev/notifyhub/worker/listener/RetryHeaders.java
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
  - notify-worker/src/test/java/dev/notifyhub/worker/listener/RetryHeadersTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisherTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/metrics/DeliveryMetricsTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/processing/NotificationStateServiceTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/processing/RetryPolicyTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/processing/SingleNotificationStore.java
  - notify-worker/src/test/java/dev/notifyhub/worker/resilience/ChannelGatewayTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/resilience/ResilienceFixtures.java
  - notify-worker/src/test/java/dev/notifyhub/worker/template/SeedTemplatesTest.java
  - notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java
  - pom.xml
  - scripts/smoke-fase2.sh
  - scripts/smoke-lib.sh
covered_digest: "v1:sha256:bb6a19768567030483bf47b25512d91aecfd828e51900a329d6b3e2029a9b25a"
behavior_unverified: 1
overrides_applied: 1
overrides:
  - must_have: "Falha do Mongo no claim ou no update de resultado não passa pelo classificador: sobe para o container (reject sem requeue → DLQ) e o documento continua recuperável"
    reason: "CR-01 adiado para a Fase 3 por decisão explícita do usuário. A recuperação do documento não terminal (PENDING/RETRYING/PROCESSING com a mensagem na DLQ) fica com o reprocessamento ADM-01/ADM-02, ou com um sweeper de lease vencido (WR-08). Registrado em deferred-items.md (status open, target Phase 3) como entrada obrigatória do planejamento da Fase 3, cujo goal é 'Nenhuma notificação fica presa sem saída'. O Javadoc do DeliveryProcessor deixou de prometer recuperação automática (02-08)."
    accepted_by: "Lucas Lopes"
    accepted_at: "2026-10-20T23:48:26Z"
re_verification:
  previous_status: gaps_found
  previous_score: 52/56
  gaps_closed:
    - "Edge DLV-04 (boundary): h ≥ N, inclusive header adulterado = 2147483647, não publica em delay e vai para DEAD_LETTERED (02-02)"
    - "Toda falha depois do claim grava exatamente um AttemptEntry, inclusive no nível 2147483647 (02-01)"
    - "CR-01 (escalation gate): resolvido por decisão do usuário, aplicado como override e registrado para a Fase 3"
  gaps_remaining: []
  regressions: []
deferred:
  - truth: "Documento não terminal (PENDING/RETRYING/PROCESSING) cuja mensagem foi para a DLQ por falha do Mongo no claim/update ou rejeição antes do claim é recuperável (CR-01)"
    addressed_in: "Phase 3"
    evidence: "Goal da Fase 3: 'Nenhuma notificação fica presa sem saída: o operador reprocessa a DLQ...'; deferred-items.md: CR-01 status open, target Phase 3, entrada obrigatória do ADM-01/ADM-02"
behavior_unverified_items:
  - truth: "Edge DLV-04 (concurrency, verification: backstop): worker derrubado entre o update RETRYING e o publish, ou entre o confirm e o ack, não deixa notificação RETRYING sem mensagem em fila e gera no máximo uma tentativa a mais"
    test: "Com o profile demo, pôr um breakpoint no return de onTransientFailure (logo depois do log 'Notificação RETRYING'), enviar para always-transient@fail.test, derrubar o worker (taskkill //F) parado no breakpoint e subir de novo. Repetir derrubando entre o update RETRYING e o publish"
    expected: "Caso confirm→queda→ack: a original reentregue gera o log 'mensagem duplicada/obsoleta descartada', o painel mostra um único token em notify.email.retry.1s e o GET termina DEAD_LETTERED com 4 tentativas, uma por nível. Caso RETRYING→queda→publish: a original reentregue reivindica, tenta de novo e publica o token h+1; a notificação nunca fica RETRYING sem token em fila"
    why_human: "Truth backstop (não inferível). O SingleNotificationStore prova a regra do fence com filtros e updates BSON reais, mas é sequencial e foi escrito pelo próprio executor; não exercita o kill real nem a janela residual entre o confirm e o avanço. Registrado em .planning/WINDOWS.md como unrun-verify (02-08 Task 2 human-check)"
human_verification:
  - test: "Com a infra no ar, rodar `bash scripts/smoke-fase2.sh --sem-build` e, durante o critério 1, abrir http://localhost:15672 (notify/notify) → Queues"
    expected: "notify.email.retry.1s, .5s e .25s recebem a mensagem em sequência e, ao final, notify.email.dlq tem a mensagem com x-failure-class (aba Get messages); o smoke termina com SMOKE FASE 2: OK"
    why_human: "O critério 1 pede passagem visível pelas filas de delay. O smoke com Docker não foi reexecutado depois do 02-08 (a evidência de runtime vem só do SUMMARY do 02-07), e o 02-08 mudou o caminho transitório (avanço do fence)"
  - test: "Abrir http://localhost:8080/notifications/<id> de uma always-transient e http://localhost:8081/actuator/prometheus"
    expected: "GET mostra 4 tentativas legíveis (nível, código, classe); o scrape mostra notify_delivery_* por canal e resilience4j_circuitbreaker_state"
    why_human: "Legibilidade da demo para quem avalia o portfólio (human-check harvestado do 02-07)"
  - test: "Janelas de queda da escada (truth backstop do 02-02 + human-check da Task 2 do 02-08): ver behavior_unverified_items"
    expected: "Original reentregue depois do confirm é descartada sem nova tentativa; queda antes do publish ainda republica; nunca RETRYING sem token"
    why_human: "Exige derrubar o processo num ponto exato; não é observável por teste unitário"
---

# Fase 2: Falhas, retry com backoff e resiliência — Relatório de Verificação

**Objetivo da fase:** Toda falha de entrega tem destino previsível e visível: transitórias percorrem a escada de delay com backoff exponencial e, esgotadas, terminam DEAD_LETTERED na DLQ; permanentes vão direto para FAILED na DLQ; circuit breaker e bulkhead protegem cada canal; e tudo é demonstrável de forma determinística com destinatários mágicos e mensurável por métricas.
**Verificado em:** 2026-10-20T23:48:26Z
**Status:** passed (era human_needed até o UAT 3/3 de 2026-10-21; ver "Fechamento")
**Re-verificação:** Sim, depois da gap closure 02-08 (status anterior: gaps_found, 52/56)

## Resumo

A lacuna bloqueante da verificação anterior está fechada no código, e não só no SUMMARY. `RetryPolicy.decide` agora testa `retryCount >= levels.size()` antes do `+1` (linhas 30-36). Com isso, um header `notify-retry-count = 2147483647` com falha transitória termina DEAD_LETTERED, com exatamente uma tentativa e cópia TRANSIENT na DLQ. Isso foi provado ponta a ponta pelo listener real em `DeliveryFlowTest.integerMaxValueRetryHeaderEndsDeadLetteredWithOneAttemptAndATransientDlqCopy`, com asserção sobre o BSON real.

O WR-01 também foi corrigido: o fence do RETRYING avança para `h+1` depois do confirm. A correção tem testes que reproduzem a reentrega sobre um documento em memória que aplica os filtros e updates reais.

O CR-01 foi aceito como override por decisão explícita do usuário e está registrado para a Fase 3. Não há gaps abertos. O status é `human_needed` porque o smoke com Docker não foi reexecutado depois do 02-08 e porque a truth backstop de concorrência (kill do worker nas janelas da escada) continua sem evidência de runtime.

## Cumprimento do objetivo

### Critérios de sucesso do ROADMAP

| # | Critério | Status | Evidência |
|---|----------|--------|-----------|
| 1 | always-transient passa pelas 3 filas de delay, fica RETRYING e termina DEAD_LETTERED na DLQ, com uma tentativa por nível no GET | ✓ VERIFIED (parte visual → humano) | Sem regressão. `alwaysTransientWalksTheWholeLadderAndEndsDeadLetteredOnTheFourthAttempt` agora vê 7 `updateFirst` (4 resultados com `$push` + 3 avanços). A borda de header adulterado acima da escada também termina DEAD_LETTERED (gap fechado). O smoke não foi reexecutado |
| 2 | always-permanent, template inexistente e variável ausente → FAILED direto, DLQ com `x-failure-class: PERMANENT` | ✓ VERIFIED | Sem alteração no 02-08 (`onTerminalFailure` intacto). Os testes continuam passando |
| 3 | fail-N-then-ok termina SENT; PROCESSING de worker derrubado é retomado na redelivery sem envio duplicado | ✓ VERIFIED (runtime → humano) | `stuckProcessingIsStillResumedByARedeliveredCopy` (lease vigente não é tomado numa primeira entrega; `redelivered=true` termina SENT) passa sobre o `SingleNotificationStore`. A reentrega depois do confirm não reenvia (`redeliveredOriginalAfterTheConfirmedTokenIsDiscardedWithoutSendingAgain`). O kill real continua só no smoke |
| 4 | CB abre e bulkhead recusa → RETRYING, nunca FAILED; `/actuator/prometheus` com contadores e timer | ✓ VERIFIED (runtime → humano) | Sem alteração em gateway, classificador e métricas. `openCircuitBreakerTurnsTheDeliveryIntoACircuitOpenRetryWithoutCallingTheAdapter` foi ajustado para 2 updates (resultado + avanço) e passa. `metrics.retrying` continua contando só depois do confirm, mesmo com o avanço perdido |
| 5 | Escape por canal; `./mvnw test` roda o renderizador sem Docker | ✓ VERIFIED | Sem alteração. Suíte completa: 453 testes, 0 falhas (orquestrador). Não há Testcontainers em `src/test` |

### Truths dos planos (agrupados)

| Plano | Truths | Status | Observações |
|-------|--------|--------|-------------|
| 02-01 (DLV-03, DLV-06) | 11 | 10 ✓, 1 PASSED (override) | ✓ "Toda falha depois do claim grava exatamente um AttemptEntry": agora vale também no nível 2147483647 (`DeliveryFlowTest` e `DeliveryProcessorTest.integerMaxValueLevelIsDeadLettered…`). Override no truth do CR-01 ("o documento continua recuperável"), aceito pelo usuário. Ver Overrides |
| 02-02 (DLV-04, DLV-05, WRK-03) | 11 | 10 ✓, 1 ⚠️ | ✓ A borda "qualquer h ≥ N → DEAD_LETTERED" foi fechada. O truth "Edge DLV-04 (idempotency)" foi substituído pelo 02-08 na janela pós-confirm: segue válido até o confirm/avanço, e depois a cópia é descartada (verificado). ⚠️ A truth de concorrência (backstop) continua `insufficient_spec` e vai para humano |
| 02-03 (TPL-02, TPL-03, TST-01) | 8 | 8 ✓ | Sem alteração (regressão rápida: testes passam) |
| 02-04 (DLV-02) | 8 | 8 ✓ | Sem alteração |
| 02-05 (RES-01, RES-02) | 8 | 8 ✓ | Sem alteração |
| 02-06 (OBS-01) | 5 | 5 ✓ | Sem alteração. O contador de retries é preservado quando o avanço falha (`lostFenceOnTheAdvance…`, `storeFailureOnTheAdvance…`) |
| 02-07 | 10 | incorporados aos critérios 1-5 | O smoke não foi reexecutado → humano |
| 02-08 (DLV-04, DLV-05, WRK-03) | 9 | 9 ✓ | Detalhe abaixo |

**Truths do 02-08 (gap closure):**

| # | Truth | Status | Evidência |
|---|-------|--------|-----------|
| 1 | h ≥ N, inclusive 2147483647, não publica em delay e vai a DEAD_LETTERED; `decide` testa esgotado antes do +1 | ✓ VERIFIED | `RetryPolicy.java:31` `if (retryCount >= levels.size())` vem antes de `retryCount + 1`. Os testes `RetryPolicyTest.integerMaxValueLevelIsExhaustedWithoutOverflow`, `levelJustBelowIntegerMaxValueIsExhausted` e `integerMaxValueLevelIsExhaustedOnASingleLevelLadder` passam. `RetryHeadersTest.integerMaxValueIsAcceptedWithoutACeiling` fixa a decisão de não ter teto |
| 2 | Exatamente um AttemptEntry no nível 2147483647; nada escapa; cópia DLQ com TRANSIENT e `notify-retry-count` 2147483647 | ✓ VERIFIED | `DeliveryFlowTest.integerMaxValueRetryHeaderEndsDeadLettered…`. Asserções sobre o BSON real: fence `{_id, PROCESSING, 2147483647}`, `$set.status` DEAD_LETTERED, `$inc.attemptCount` 1, `$push.$each` com 1 elemento e `retryLevel` 2147483647. Headers da cópia na DLQ conferidos e `never()` em `notify.retry` |
| 3 | Depois do confirm do token h+1, o RETRYING passa a `retryCount = h+1`; a cópia de nível h reentregue não reivindica, não envia e não republica (WR-01) | ✓ VERIFIED | `NotificationStateService.advanceRetryLevel` (238-253) usa fence `RETRYING ∧ retryCount = h` com `$set` só de retryCount/updatedAt. Chamado em `DeliveryProcessor.java:222-224`, dentro de `outcome.ok()`. Teste comportamental `redeliveredOriginalAfterTheConfirmedTokenIsDiscardedWithoutSendingAgain`: adapter 1x, 1 publish e 1 tentativa depois da reentrega. `NotificationStateServiceTest.retryingWhoseFenceAdvancedIsNotClaimedByTheLevelBelow` |
| 4 | Segunda cópia do token h+1, chegando depois de h+2 confirmado, é descartada | ✓ VERIFIED | `secondCopyOfTheSameTokenIsDiscardedOnceTheNextLevelWasConfirmed`: adapter 1x, 1 publish em `email.retry.25s`, retryCount 2 e 1 tentativa nova |
| 5 | Caminhos legítimos preservados (Armadilha 2, PROCESSING preso, token h+1 reivindica) | ✓ VERIFIED | `redeliveredOriginalStillRetriesWhenTheWorkerDiedBeforeTheRetryPublish`, `stuckProcessingIsStillResumedByARedeliveredCopy` e a segunda metade do teste 3 (o token "1" reivindica o documento avançado). `markRetryingIsOneFencedUpdateThatNeverWritesRetryCount` continua passando |
| 6 | Fence perdido ou `DataAccessException` no avanço nunca muda o roteamento | ✓ VERIFIED | `advanceRetryFence` (241-248) ignora o retorno e captura `DataAccessException`. Testes `lostFenceOnTheAdvanceStillAcksWithoutTouchingTheDlq` / `storeFailureOnTheAdvanceStillAcksWithoutTouchingTheDlq` (Flow e Processor): sem exceção, sem `notify.dlx`, `retries{email,1}` = 1. Ressalva WR-09: outra `RuntimeException` não é capturada (ver Anti-padrões) |
| 7 | Ordem D-11 preservada; republicação falha não avança (D-17) | ✓ VERIFIED | `confirmedRetryAdvancesTheFenceToTheNextLevelAfterThePublish` (InOrder markRetrying → publishToRetry → advanceRetryLevel), `confirmedRetryFromLevelOneAdvancesTheFenceToLevelTwo` e `failedRepublishNeverAdvancesTheFence` |
| 8 | CR-01 em `deferred-items.md` (open, Phase 3); Javadoc sem promessa de recuperação | ✓ VERIFIED | A entrada CR-01 tem `status: open` e `target: Phase 3`, com os quatro cenários e a entrada obrigatória do ADM-01. O Javadoc do `DeliveryProcessor` (56-60) diz "nada nesta fase o recupera sozinho" e não tem nenhuma ocorrência de "continua recuperável" |
| 9 | `./mvnw -q -B test` verde sem Docker, com os testes novos | ✓ VERIFIED | Orquestrador: 453 testes, 0 falhas. Nesta verificação: `./mvnw -q -B -pl notify-worker -am test -Dtest=RetryPolicyTest,RetryHeadersTest,DeliveryFlowTest,NotificationStateServiceTest,DeliveryProcessorTest` → EXIT=0 (10 + 11 + 31 + 28 + 41 testes) |

**Pontuação:** 64/65 truths verificados (5 critérios + 60 truths dos planos 02-01..02-06 e 02-08), incluindo 1 PASSED (override) do CR-01. 1 truth presente mas com comportamento não verificado (backstop de concorrência do 02-02).

### Overrides aplicados

| Must-have | Motivo | Aceito por |
|-----------|--------|------------|
| "Falha do Mongo no claim ou no update de resultado … o documento continua recuperável" (02-01) | CR-01 adiado para a Fase 3. A recuperação de PENDING/RETRYING/PROCESSING com a mensagem na DLQ fica com o ADM-01/ADM-02 ou com um sweeper de lease (WR-08). Está registrado em `deferred-items.md` | Lucas Lopes, 2026-10-20 (decisão explícita na sessão) |

### Itens adiados

| # | Item | Tratado em | Evidência |
|---|------|------------|-----------|
| 1 | Documento não terminal com a mensagem na DLQ (CR-01), incluindo rejeições antes do claim e a cabeça da DLQ travando o ADM-01 | Fase 3 | Goal da Fase 3: "Nenhuma notificação fica presa sem saída". `deferred-items.md`: CR-01 open, target Phase 3 |

### Proibições (test-tier)

| Plano | Proibição | Status | Evidência de enforcement |
|-------|-----------|--------|--------------------------|
| 02-01 | Sem PII na cópia da DLQ, headers, lastError e logs | ✓ | Sem alteração. Os novos logs do avanço (`NotificationStateService:248`, `DeliveryProcessor:245`) só levam id e nível |
| 02-02 | Transitória nunca vira FAILED | ✓ | `integerMaxValueLevelIsDeadLettered…` verifica `never()` em markRetrying/publishToRetry/advance. O ramo esgotado chama `markDeadLettered`, nunca `markFailed` |
| 02-02 | Status nunca diverge de onde a mensagem está | ✓ (caminho classificado) | O caso MAX_VALUE (antes PROCESSING com a mensagem na DLQ) foi fechado. Fora do caminho classificado: CR-01 (override) e WR-09 (abaixo) |
| 02-04 | Mágico só em padrões exatos | ✓ | Sem alteração |
| 02-05 | Recusa de CB/bulkhead nunca FAILED | ✓ | Sem alteração |
| 02-06 | Timer sem recusas e sem alta cardinalidade | ✓ | Sem alteração |
| 02-08 | MUST NOT gravar `retryCount` no update PROCESSING → RETRYING | ✓ | `recordFailure` (262-288) não faz `set(RETRY_COUNT)`. `markRetryingIsOneFencedUpdateThatNeverWritesRetryCount` passa |
| 02-08 | MUST NOT rejeitar nem copiar para a DLQ depois do token h+1 confirmado por falha ou fence perdido no avanço | ✓ (com ressalva) | Testes `lostFence…`/`storeFailure…` em Flow e Processor. A ressalva WR-09 vale só para `RuntimeException` fora da hierarquia `DataAccessException` |

### Artefatos obrigatórios

- `gsd-tools verify.artifacts` no 02-08: 5/5 aprovados.
- Artefatos dos planos 02-01..02-07: 27/27 na verificação anterior. Dos arquivos de produção desses planos, o diff `6fb62e6..HEAD` só tocou `RetryHeaders` (Javadoc), `RetryPolicy`, `NotificationStateService` e `DeliveryProcessor`, todos relidos inteiros nesta verificação. Não houve regressão.
- O `SingleNotificationStore` (212 linhas) é substantivo: os filtros e updates passam por `MongoBson` (os mappers reais do Spring Data), e operadores não suportados lançam exceção.

### Verificação de key links

`gsd-tools verify.key-links` no 02-08: 4/4 verificados (`advanceRetryLevel(`, `RetryPolicy.decide(`, `RETRY_COUNT).lte(`, `SingleNotificationStore` no `DeliveryFlowTest`). Os 25/25 anteriores não mudaram. Conferi à mão que o avanço está **dentro** do `if (outcome.ok())` e antes do `return`, ou seja, antes do ack, e que nem o ramo esgotado nem o `onRepublishFailure` o chamam.

### Rastreamento do fluxo de dados (nível 4)

| Artefato | Dado | Fonte | Dado real | Status |
|----------|------|-------|-----------|--------|
| GET /notifications/{id} (attempts) | `AttemptEntry` | `$push` com `$slice -50` em `recordFailure`/`markSent` | Sim | ✓ FLOWING |
| GET /notifications/{id} (`retryCount` em RETRYING) | nível do token na fila de delay | `advanceRetryLevel` depois do confirm | Sim (ver IN-07: pode mostrar `h` se o avanço perder) | ✓ FLOWING |
| `/actuator/prometheus` | `notify_delivery_*` | `DeliveryMetrics` | Sim | ✓ FLOWING |
| Cópia na DLQ | `x-failure-class`, `notify-retry-count` | `Classification` / TRANSIENT fixo na escada | Sim (inclusive 2147483647) | ✓ FLOWING |

### Spot-checks de comportamento

| Comportamento | Comando | Resultado | Status |
|---------------|---------|-----------|--------|
| Classes de teste da gap closure | `./mvnw -q -B -pl notify-worker -am test -Dtest=RetryPolicyTest,RetryHeadersTest,DeliveryFlowTest,NotificationStateServiceTest,DeliveryProcessorTest` | EXIT=0; 10/11/31/28/41 testes, 0 falhas | ✓ PASS |
| Suíte completa (orquestrador) | `./mvnw test` | 453 testes, 0 falhas (api 34, common 75, worker 344) | ✓ PASS |
| Overflow do gap anterior | `RetryPolicyTest.integerMaxValueLevelIsExhaustedWithoutOverflow` (no run acima) | `exhausted=true`, `nextRetryCount=MAX_VALUE`, `nextLevel=null` | ✓ PASS (antes ✗) |
| Reprodução do WR-01 | `DeliveryFlowTest.redeliveredOriginalAfterTheConfirmedTokenIsDiscardedWithoutSendingAgain` (no run acima) | log "fence do retry não avançado"/descartada; adapter 1x | ✓ PASS |
| Sem Docker em `mvnw test` | busca por Testcontainers/`@ServiceConnection`/`*IT.java` em `src/test` | nenhum | ✓ PASS |

### Execução de probes

Não há `scripts/*/tests/probe-*.sh` nem probes declarados nos planos. O smoke `scripts/smoke-fase2.sh` exige Docker e subir os serviços. Por isso não foi rodado (restrição: não subir serviços) e fica como verificação humana.

### Cobertura de requisitos

| Requisito | Plano | Descrição | Status | Evidência |
|-----------|-------|-----------|--------|-----------|
| WRK-03 | 02-02, 02-07, 02-08 | PROCESSING preso recuperável na redelivery | ✓ SATISFIED | Claim com `reclaimableWhenStuck()`, preservado no 02-08 (`stuckProcessingIsStillResumedByARedeliveredCopy`). Ressalva WR-08 (só avaliado quando chega mensagem; ligado ao CR-01, adiado) |
| TPL-02 | 02-03 | Renderizador estrito | ✓ SATISFIED | Sem alteração |
| TPL-03 | 02-03 | Escape por canal | ✓ SATISFIED | Sem alteração |
| DLV-02 | 02-04 | Destinatários mágicos | ✓ SATISFIED | Sem alteração |
| DLV-03 | 02-01, 02-05 | Classificador exaustivo | ✓ SATISFIED | Sem alteração. Infra no claim/update fora do classificador, por desenho (CR-01, override) |
| DLV-04 | 02-02, 02-08 | Republish no próximo nível com `notify-retry-count`, confirm antes do ack | ✓ SATISFIED | A borda MAX_VALUE foi fechada. O fence avança depois do confirm (WR-01) |
| DLV-05 | 02-02, 02-08 | Escada esgotada → DEAD_LETTERED + DLQ | ✓ SATISFIED | Vale para todo h ≥ N, inclusive 2147483647 |
| DLV-06 | 02-01 | Permanente → FAILED + DLQ PERMANENT | ✓ SATISFIED | Sem alteração |
| RES-01 | 02-05 | CB por canal | ✓ SATISFIED | Sem alteração |
| RES-02 | 02-05 | Bulkhead por semáforo | ✓ SATISFIED | Sem alteração |
| OBS-01 | 02-06 | Contadores e timer | ✓ SATISFIED | Sem alteração; retries contados mesmo com o avanço falho |
| TST-01 | 02-03 | Testes do renderizador | ✓ SATISFIED | Sem alteração |

Requisitos órfãos: nenhum. Os 12 IDs da Fase 2 em REQUIREMENTS.md aparecem no `requirements:` de algum plano. Nota: a tabela de rastreabilidade de REQUIREMENTS.md ainda marca 9 IDs como "Gaps Found". O orquestrador precisa atualizá-la depois desta verificação.

### Anti-padrões e achados do code review (conferidos no código)

| Arquivo | Linha | Achado | Severidade | Impacto |
|---------|-------|--------|------------|---------|
| `processing/RetryPolicy.java` | 30-36 | WR-02 (overflow) | ✓ Resolvido | Gap fechado |
| `processing/NotificationStateService.java` / `DeliveryProcessor.java` | 238-253 / 222-224 | WR-01 (reentrega pós-confirm) | ✓ Resolvido | Resta a janela de um round-trip entre o confirm e o avanço, documentada (at-least-once) |
| `processing/DeliveryProcessor.java` | 241-248 | WR-09: `advanceRetryFence` só captura `DataAccessException`. Outra `RuntimeException` do `updateFirst` (ex.: `IllegalStateException` do driver com o client fechado no shutdown) sai para o container, que rejeita a original para a DLQ com o token h+1 vivo. O log também não recebe `e` | ⚠️ Warning | A notificação fica na DLQ e na escada ao mesmo tempo (duplicação, não perda), e `metrics.retrying` não conta. Não falsifica o truth 6 do 02-08, que é delimitado a `DataAccessException`. A correção é de uma linha (`catch (RuntimeException e)` + `e` no log). Recomendado antes da Fase 3, porque o reprocessamento a partir da DLQ criaria uma cadeia paralela |
| `processing/DeliveryProcessor.java` | 56-60, 120, 156 | CR-01 | ⚠️ Override / adiado | Fase 3 (`deferred-items.md`) |
| `processing/NotificationStateService.java` / `resources/application.yml` | 64-77 / 62 | WR-08: o Javadoc do claim e o comentário do yml ("a recuperação de PROCESSING preso é da Fase 2") ainda sugerem recuperação por lease sem mensagem | ℹ️ Info | Só documentação. A recuperação sem mensagem é do CR-01 adiado |
| `listener/RetryHeaders.java` | 14-19 | IN-08: o Javadoc promete DEAD_LETTERED para qualquer header acima da escada, mas isso só vale se a tentativa falhar transitoriamente | ℹ️ Info | Só texto |
| `processing/NotificationStateService.java` | 43-47 | IN-07: `retryCount` em RETRYING mostra `h+1` ou `h`, conforme o avanço tenha ganhado ou perdido | ℹ️ Info | Nenhum consumidor atual depende disso. Documentar na Fase 4 |
| WR-03..WR-07 | — | Sem alteração desde a verificação anterior | ⚠️ Warning | Não falsificam nenhum truth |

Marcadores de dívida (TBD/FIXME/XXX/TODO/HACK) nos arquivos alterados pelo 02-08: nenhum.

Nota de processo: a fase está marcada `Mode: mvp`, mas o Goal não está no formato de user story (`user-story.validate` → `valid: false`). O 02-01-PLAN registrou a decisão de não reescrevê-lo, e a verificação inicial seguiu a metodologia goal-backward padrão. Esta re-verificação mantém a mesma base para não mudar o contrato no meio da fase. Se quiser o enquadramento formal, rode `/gsd mvp-phase 2`.

### Verificação humana necessária

1. **Smoke de runtime e painel do RabbitMQ.** Rodar `bash scripts/smoke-fase2.sh --sem-build` com a infra no ar e acompanhar `notify.email.retry.1s/.5s/.25s` e `notify.email.dlq` (`x-failure-class`) em http://localhost:15672. Esperado: `SMOKE FASE 2: OK`. O smoke não foi reexecutado depois do 02-08, que alterou o caminho transitório.
2. **Legibilidade do GET e do scrape.** O GET de uma `always-transient` deve mostrar 4 tentativas com nível, código e classe, e `/actuator/prometheus` deve mostrar `notify_delivery_*` e o estado do CB.
3. **Janelas de queda da escada (truth backstop do 02-02 + human-check da Task 2 do 02-08, em `.planning/WINDOWS.md` como unrun-verify).** Derrubar o worker parado no return de `onTransientFailure` (depois do confirm, antes do ack): a original reentregue deve ser descartada ("mensagem duplicada/obsoleta descartada"), deve haver um único token em `notify.email.retry.1s` e o GET deve terminar DEAD_LETTERED com 4 tentativas. Derrubar entre o update RETRYING e o publish: a original reentregue ainda deve republicar.

### Resumo das lacunas

**Nenhuma lacuna aberta.** As duas truths que falharam na verificação anterior (mesma causa raiz: overflow em `RetryPolicy.decide` com `notify-retry-count = 2147483647`) estão fechadas. A guarda vem antes do `+1`, e o fluxo real (listener → processor → estado → publisher) termina DEAD_LETTERED com uma tentativa e cópia TRANSIENT, provado por teste sobre o BSON real.

O WR-01, que a verificação anterior tinha deixado como ressalva da truth backstop, foi corrigido e tem testes de reprodução. Os caminhos de retomada legítimos (Armadilha 2, PROCESSING preso, o próprio token h+1) foram preservados e testados.

O CR-01 saiu do escalation gate. Ele foi aplicado como override por decisão do usuário e está registrado para a Fase 3, cujo goal o cobre.

**Por que `human_needed` e não `passed`:** há três itens de runtime (smoke/painel, legibilidade da demo e o kill nas janelas da escada) que não são observáveis por teste unitário. A truth de concorrência do 02-02 é backstop e continua sem evidência de runtime.

**Recomendação não bloqueante:** corrigir o WR-09 (`catch (RuntimeException e)` em `advanceRetryFence`, com a exceção no log) antes de a Fase 3 implementar o reprocessamento a partir da DLQ.

---

_Verificado em: 2026-10-20T23:48:26Z_
_Verificador: Claude (gsd-verifier)_

## Fechamento (2026-10-29)

Registro do fechamento da Fase 2 feito pelo quick 261029-u95.

- **(a) UAT:** o 02-UAT.md está com status complete, 3/3 pass e 0 issues, concluído em 2026-10-21T00:14:15Z. Ele cobre os 3 itens de `human_verification` (smoke com Docker e painel do RabbitMQ, legibilidade do GET e do scrape do Prometheus, queda do worker nas janelas da escada), inclusive o item de `behavior_unverified_items` (quedas do worker nas janelas da escada, teste 3).
- **(b) Arquivos cobertos alterados depois da re-verificação 84fcebe:** só os do quick 261020-tm3 (fe60ac2 WR-05/T-02-01, 7cb7ce6 WR-09, 07b5fb1 WR-07). A comparação entre `git diff --name-only 84fcebe HEAD -- <covered_files>` e os arquivos desses três commits deu exatamente estes 11 arquivos, sem nenhum outro:
  - `notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulationProperties.java`
  - `notify-worker/src/main/java/dev/notifyhub/worker/failure/FailureClassifier.java`
  - `notify-worker/src/main/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisher.java`
  - `notify-worker/src/main/java/dev/notifyhub/worker/processing/DeliveryProcessor.java`
  - `notify-worker/src/main/resources/application.yml`
  - `notify-worker/src/main/resources/application-demo.yml`
  - `notify-worker/src/test/java/dev/notifyhub/worker/channel/SimulatedChannelAdapterTest.java`
  - `notify-worker/src/test/java/dev/notifyhub/worker/config/WorkerSettingsTest.java`
  - `notify-worker/src/test/java/dev/notifyhub/worker/messaging/DeliveryRoutingPublisherTest.java`
  - `notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryFlowTest.java`
  - `notify-worker/src/test/java/dev/notifyhub/worker/processing/DeliveryProcessorTest.java`
- **(c) Testes:** `./mvnw -B test` verde em 2026-10-29 (exit 0, BUILD SUCCESS): notify-common 75, notify-api 34 e notify-worker 356 testes, total 465, com 0 falhas, 0 erros e 0 ignorados.
- **(d) Status:** `human_needed` passou a `passed`, o mesmo passo que o verify-work aplica quando o UAT termina sem issues. O `covered_digest` foi recalculado com `verification.fingerprint` sobre a mesma lista `covered_files`, na mesma ordem. Score, `covered_files`, `behavior_unverified` e `overrides` não mudaram.
- **(e) Recálculo depois do `phase complete 2`:** pendente (preenchido na Task 2 do quick 261029-u95).
