---
phase: 01-entrega-ponta-a-ponta-no-caminho-feliz
plan: 07
subsystem: api
tags: [jackson, enum-binding, problem-details, api-06, webmvctest, smoke, gap-closure]

requires:
  - phase: 01-04
    provides: "ApiExceptionHandler.invalidChannel (InvalidFormatException com targetType Channel → errors[].field = channel) e NotificationValidationTest"
  - phase: 01-06
    provides: "scripts/smoke-fase1.sh com espera_400 e a checagem contagem_antes/contagem_depois"
provides:
  - "spring.jackson.deserialization.fail-on-numbers-for-enums: true no application.yml da API: ordinal (1) e ordinal em string (\"2\") deixam de virar Channel e recebem 400 Problem Details"
  - "NotificationValidationTest.numericChannelIs400WithOnlyChannelErrorAndNoWrite com as linhas 1 e \"2\" (erro único em channel, nenhuma interação com Mongo/Rabbit)"
  - "Dois casos novos na seção 10 do smoke contra o jar empacotado, dentro da checagem 'nenhum 400 gravou no Mongo'"
affects: [01-verification, 02, 03, 04]

actuals:
  tokens: 1080
  tasks: 2
  commits: 3
plan_head_before: f6232ee1366a5a67a3e62f2b972b7b91dfaf68ee

tech-stack:
  added: []
  patterns:
    - "Binding estrito de enum no ObjectMapper do Boot via spring.jackson.deserialization, sem desserializador customizado"
    - "Teste de validação que pode chegar ao service usa um broker mockado que confirma (brokerAcksEveryPublish): sem a correção a resposta é 202, nunca um erro de mock"

key-files:
  created: []
  modified:
    - notify-api/src/main/resources/application.yml
    - notify-api/src/test/java/dev/notifyhub/api/web/NotificationValidationTest.java
    - scripts/smoke-fase1.sh

key-decisions:
  - "01-07: canal numérico rejeitado só pela flag fail-on-numbers-for-enums do Jackson; ApiExceptionHandler, CreateNotificationRequest e Channel não mudaram (a contingência MismatchedInputException não foi necessária)"
  - "01-07: o teste de canal numérico simula um broker que confirma, para que o RED falhe por asserção (202 em vez de 400) e não por NPE do MongoTemplate mockado"

patterns-established:
  - "brokerAcksEveryPublish(): helper do NotificationValidationTest para casos que, sem a validação, chegariam ao publish"

requirements-completed: [API-06]

coverage:
  - id: D1
    description: "POST /notifications com \"channel\":1 (payload SMS válido) → 400 application/problem+json validation-error com um único erro {field: channel, message: 'valores aceitos: EMAIL, SMS, WEBHOOK'}, sem tocar MongoTemplate nem RabbitTemplate"
    requirement: API-06
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/web/NotificationValidationTest.java#numericChannelIs400WithOnlyChannelErrorAndNoWrite(String, Object, String)[1]"
        status: pass
    human_judgment: false
  - id: D2
    description: "POST /notifications com \"channel\":\"2\" (payload WEBHOOK válido) → o mesmo 400 com erro único em channel e nenhuma gravação/publish"
    requirement: API-06
    verification:
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/web/NotificationValidationTest.java#numericChannelIs400WithOnlyChannelErrorAndNoWrite(String, Object, String)[2]"
        status: pass
    human_judgment: false
  - id: D3
    description: "Jar empacotado (profile padrão) responde 400 problem+json com errors[].field = channel para \"channel\":1 e \"channel\":\"2\"; a contagem de notifications não muda; EMAIL/SMS/WEBHOOK por nome continuam 202 → SENT"
    requirement: API-06
    verification:
      - kind: e2e
        ref: "bash scripts/smoke-fase1.sh (com build; SMOKE FASE 1: OK, exit 0)"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/web/NotificationValidationTest.java#magicRecipientsAreAccepted"
        status: pass
      - kind: unit
        ref: "notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java"
        status: pass
    human_judgment: false

duration: 7min
completed: 2026-10-13
status: complete
---

# Phase 1 Plan 07: Canal numérico rejeitado com 400 (gap API-06) Summary

**`spring.jackson.deserialization.fail-on-numbers-for-enums: true` no `application.yml` da API faz `"channel":1` e `"channel":"2"` responderem 400 Problem Details com um único erro em `channel` e nada gravado. A prova está na fatia WebMvc (linhas `1` e `"2"`) e no smoke contra o jar empacotado. O gap SC3/API-06 do 01-VERIFICATION.md está fechado sem mudar nenhuma classe de produção.**

## Performance

- **Duration:** 7 min
- **Started:** 2026-10-13T00:38:13Z
- **Completed:** 2026-10-13T00:44:53Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments
- O ordinal do enum deixou de ser aceito como canal. Com a flag, o Jackson lança `InvalidFormatException` com `targetType` = `Channel` para `1` e para `"2"`. O `ApiExceptionHandler.invalidChannel` já existente transforma isso em `errors = [{field: "channel", message: "valores aceitos: EMAIL, SMS, WEBHOOK"}]`.
- Fatia WebMvc: `numericChannelIs400WithOnlyChannelErrorAndNoWrite` cobre `1` (destinatário SMS válido) e `"2"` (destinatário WEBHOOK válido). O teste confere o 400 `problem+json`, `type`, `status`, `instance`, `errors.length() = 1`, o campo e a mensagem literal, e termina com `verifyNoInteractions(mongoTemplate, rabbitTemplate)`.
- Smoke contra o jar (profile padrão, com build) imprimiu `OK: canal numérico 1 (ordinal de SMS) → 400 problem+json, errors[].field = channel`, `OK: canal numérico em string 2 (ordinal de WEBHOOK) → 400 ...` e `OK: nenhum 400 gravou no Mongo (notifications continua com 26 documentos)`. O caminho feliz por nome (EMAIL/SMS/WEBHOOK → SENT), o 503, a duplicata e a DLQ continuaram verdes, e o script terminou com `SMOKE FASE 1: OK`.
- `./mvnw -q verify` na raiz: exit 0, 24 relatórios surefire, 184 testes (182 + 2), 0 falhas, 0 erros.

## Task Commits

1. **Task 1: Tracer, `"channel":1` → 400 sem gravar**: `67021b2` (test, RED) e `a824e80` (fix, GREEN)
2. **Task 2: Linha `"2"`, regressão dos nomes e prova no smoke**: `dfbeb8f` (test)

**Plan metadata:** commit do SUMMARY (docs)

## Files Created/Modified
- `notify-api/src/main/resources/application.yml`: `jackson.deserialization.fail-on-numbers-for-enums: true` dentro do bloco `spring:` raiz (continua havendo um único `spring:`), com comentário em pt-BR citando D-01/API-06.
- `notify-api/src/test/java/dev/notifyhub/api/web/NotificationValidationTest.java`: fonte `numericChannels()` (2 linhas), teste parametrizado `numericChannelIs400WithOnlyChannelErrorAndNoWrite(String, Object, String)` e helper `brokerAcksEveryPublish()`, agora usado também por `magicRecipientsAreAccepted`.
- `scripts/smoke-fase1.sh`: dois `espera_400 "canal numérico ..."` na seção 10, logo depois do caso PUSH e antes de `contagem_depois`. Modo 100755 e LF preservados.

## Decisions Made
- A correção é só a flag do Jackson, como o plano previa. O GREEN já devolveu a mensagem "valores aceitos", então a contingência de ampliar `invalidChannel` para `MismatchedInputException` não foi usada. `ApiExceptionHandler`, `CreateNotificationRequest` e `Channel` não mudaram.
- O teste de canal numérico simula um broker que confirma o publish (ver o desvio 1). Assim o único jeito de sair 400 é o binding do canal, e uma regressão aparece como `expected:<400> but was:<202>`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] O primeiro RED era uma falha de fixture, não de asserção**
- **Found during:** Task 1 (RED)
- **Issue:** Escrito exatamente como o plano descreve, o teste não falhava com `Status expected:<400>`. Ele dava ERROR: a requisição passava pela validação, o insert e o publish iam para os mocks e, após o `confirm-timeout` de 5s, o service chamava `updateFirst` no `MongoTemplate` mockado, que devolvia `null` (`NullPointerException ... UpdateResult.getModifiedCount()`). O comportamento provado era o certo (a requisição chegou à gravação), mas pela `tdd.md` uma falha de fixture é INVALID_RED e não autoriza o GREEN.
- **Fix:** Antes do POST o teste chama `brokerAcksEveryPublish()`, o mesmo stub de confirm que `magicRecipientsAreAccepted` já usava, agora extraído para um helper que os dois testes compartilham. O RED passou a ser `java.lang.AssertionError: Status expected:<400> but was:<202>`, só no caso alvo (21 testes, 20 verdes, 1 falha). `gsd-tools check tdd-red-evidence` devolveu `RED_EVIDENCE_OK` (`target_test_failed`), com o relatório do surefire transcrito para TAP. O stub com `doAnswer().when()` não conta como interação, então `verifyNoInteractions(mongoTemplate, rabbitTemplate)` continua valendo no GREEN.
- **Files modified:** notify-api/src/test/java/dev/notifyhub/api/web/NotificationValidationTest.java
- **Verification:** RED por asserção (acima); GREEN com 21/21 e depois 22/22 na classe; `magicRecipientsAreAccepted` verde com o helper
- **Committed in:** 67021b2 (commit RED da Task 1)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Só no desenho do teste. O teste fica mais estrito, porque um broker saudável é o pior caso para a validação, e roda em ~50ms em vez de 5s. Nenhuma mudança de produção além da flag planejada.

Nota de processo TDD:
- Task 1: o commit `test(01-07)` (67021b2) vem antes do `fix(01-07)` (a824e80). O plano pede `fix` em vez de `feat` para o GREEN, porque é correção de um gap.
- Task 2: a linha `"2"` já nasce verde, como o plano previa. É o segundo caminho do desserializador de enum (string numérica), coberto pela mesma flag, e fica como guarda de regressão.
- Gate do tracer: execução interativa em `end-of-phase` com `<verify>` só automatizado. O verify foi refeito no GREEN (exit 0, caso `[1]` no XML, `failures="0"`) e a expansão seguiu sem checkpoint.

## Issues Encountered
- No Git Bash, `grep -c $'\r'` contou todas as linhas do `smoke-fase1.sh` como CR. É artefato do grep (o padrão vira vazio). `git ls-files --eol` mostra `i/lf w/lf`, então o arquivo continua LF.
- Efeito colateral da verificação anterior: as notificações 83679994-... (SMS) e 10ba7ccf-... (WEBHOOK), criadas pela sonda numérica do verificador, foram consumidas pelo worker quando o smoke o subiu e estão SENT.
- Nenhuma JVM da API ou do worker ficou rodando: o smoke encerrou as duas (winpids 17008 e 14032). Resta só o `java.exe` pid 6408, que é o language server do VS Code. RabbitMQ e Mongo continuam no ar.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- O único gap do 01-VERIFICATION.md (SC3/API-06) tem correção e prova automatizada nas duas camadas. Falta a re-verificação da fase, que é do orquestrador, assim como marcar o API-06 como Complete no REQUIREMENTS.md.
- WR-02..WR-06 do 01-REVIEW.md continuam abertos e fora do escopo deste plano.

---
*Phase: 01-entrega-ponta-a-ponta-no-caminho-feliz*
*Completed: 2026-10-13*

## Self-Check: PASSED
- Os 3 arquivos de key-files.modified existem e estão nos commits do plano
- Os commits 67021b2, a824e80 e dfbeb8f estão no histórico (`git rev-list --count f6232ee..HEAD` = 3 antes deste commit de docs)
- Critérios de aceitação da Task 1: a flag está fora de comentário; há 1 bloco `spring:`; o teste e o `verifyNoInteractions` estão presentes; `-Dtest=NotificationValidationTest` sai com exit 0, o caso `[1]` está no XML com `failures="0"`; o `test(01-07)` é mais antigo que o `fix(01-07)`
- Critérios de aceitação da Task 2: `./mvnw -q -pl notify-api test` sai com exit 0; `[1]` e `[2]` estão no XML; `NotificationApiFlowTest` tem `failures="0"`; `grep -c 'espera_400 "canal numérico'` = 2; `bash -n` passa; o smoke sai com exit 0 e mostra as três linhas OK exigidas
- Verificação do plano: `./mvnw -q verify` sai com exit 0 (184 testes, 0 falhas/erros); smoke com `SMOKE FASE 1: OK`
