---
phase: 02-falhas-retry-com-backoff-e-resili-ncia
plan: 03
subsystem: worker
tags: [jmustache, jackson, template, escaping, json, html, tdd]

requires:
  - phase: 02-falhas-retry-com-backoff-e-resili-ncia
    provides: "02-01: ErrorCode (MISSING_VARIABLE, INVALID_WEBHOOK_PAYLOAD, TEMPLATE_NOT_FOUND, TEMPLATE_INVALID), PermanentDeliveryException, FailureClassifier, TemplateNotFoundException"
  - phase: 01-entrega-ponta-a-ponta-no-caminho-feliz
    provides: "TemplateRenderer com compiladores HTML/PLAIN, TemplateDocument/TemplateVariant, seeds welcome/order-shipped/password-reset"
provides:
  - "JsonValueEscaper: Mustache.Escaper que escapa cada valor como conteúdo de string JSON via JsonStringEncoder"
  - "TemplateRenderer com três compiladores (HTML, PLAIN, JSON); WEBHOOK usa JSON"
  - "Validação estrita do corpo do webhook (ObjectReader com FAIL_ON_TRAILING_TOKENS + isObject) → PermanentDeliveryException(INVALID_WEBHOOK_PAYLOAD) sem causa anexada"
  - "Checagem de requiredVariables antes de renderizar → PermanentDeliveryException(MISSING_VARIABLE) com só o nome da variável"
  - "Suíte TST-01 sem Docker: TemplateRendererTest (20) e SeedTemplatesTest (10)"
affects: [02-07, fase-3-reprocessamento]

actuals:
  tokens: 5528
  tasks: 2
  commits: 4
plan_head_before: af56bb183a58e9918885bb0587b03c9a03e2ef0b

tech-stack:
  added: []
  patterns:
    - "Escape por canal com um compilador JMustache estático imutável por política de escape; nunca encadear escapeHTML depois de withEscaper"
    - "Saída estruturada (webhook) validada depois de renderizar com parse estrito; falha vira PermanentDeliveryException com mensagem própria, sem a causa do parser (pode citar dados do cliente)"
    - "Pré-condição de dados (requiredVariables) checada antes de compilar; o comportamento padrão do JMustache continua como segunda rede"

key-files:
  created:
    - notify-worker/src/main/java/dev/notifyhub/worker/template/JsonValueEscaper.java
  modified:
    - notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateRenderer.java
    - notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/template/SeedTemplatesTest.java

key-decisions:
  - "ObjectReader estrito é um ObjectMapper local e estático no TemplateRenderer, não o do Boot injetado: preserva o construtor sem argumentos usado por DeliveryFlowTest e SeedTemplatesTest"
  - "A checagem de requiredVariables roda depois de resolver a variante: template sem variante para o canal continua TEMPLATE_NOT_FOUND mesmo sem variáveis"
  - "Exceção de corpo de webhook inválido não anexa a JsonProcessingException (a mensagem do Jackson cita trechos do corpo)"

patterns-established:
  - "Compilador por política de escape: HTML (corpo de e-mail), PLAIN (assunto e SMS), JSON (webhook)"
  - "Testes de falha classificam a exceção real com new FailureClassifier() em vez de só checar o tipo"

requirements-completed: [TPL-02, TPL-03, TST-01]

coverage:
  - id: D1
    description: "Webhook escapa cada valor como string JSON: o seed password-reset com aspas, barra invertida, quebra de linha, tab, U+0001 e </script> gera JSON válido e o valor volta igual depois do parse, sem entidades HTML"
    requirement: TPL-03
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java#seedPasswordResetWebhookStaysValidJsonWithQuotesBackslashAndControls"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java#webhookBodyNeverCarriesHtmlEntities"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/template/SeedTemplatesTest.java#everySeedWebhookIsAJsonObjectEvenWithQuotesAndBackslashInValues"
        status: pass
    human_judgment: false
  - id: D2
    description: "Corpo de webhook com lixo depois do objeto, array ou vazio vira PermanentDeliveryException(INVALID_WEBHOOK_PAYLOAD), com mensagem citando o template e sem o valor da variável nem causa"
    requirement: TPL-03
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java#webhookWithTrailingGarbageIsInvalidPayload"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java#webhookThatRendersAnArrayIsInvalidPayload"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java#webhookThatRendersOnlyWhitespaceIsInvalidPayload"
        status: pass
    human_judgment: false
  - id: D3
    description: "Corpo do e-mail escapa < > & \" ' ` =; assunto do e-mail e SMS saem literais"
    requirement: TPL-03
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java#emailBodyEscapesEveryHtmlSensitiveCharacter"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java#emailSubjectAndSmsKeepHtmlCharactersLiteral"
        status: pass
    human_judgment: false
  - id: D4
    description: "Variável de requiredVariables ausente ou nula → PermanentDeliveryException(MISSING_VARIABLE) citando só o nome, mesmo quando o template não a usa; string vazia é válida"
    requirement: TPL-02
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java#missingRequiredVariableIsPermanentMissingVariable"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java#nullRequiredVariableIsPermanentMissingVariable"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java#requiredVariableIsEnforcedEvenWhenTheTemplateDoesNotUseIt"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java#emptyRequiredVariableIsAValidValue"
        status: pass
    human_judgment: false
  - id: D5
    description: "Exceções reais do renderer classificadas pelo FailureClassifier: variável usada e não declarada → PERMANENT/MISSING_VARIABLE, sintaxe quebrada → PERMANENT/TEMPLATE_INVALID, variante ausente → PERMANENT/TEMPLATE_NOT_FOUND"
    requirement: TPL-02
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java#variableUsedButNotRequiredStillFailsAsPermanentMissingVariable"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java#brokenTemplateSyntaxIsPermanentTemplateInvalid"
        status: pass
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java#missingVariantIsClassifiedAsPermanentTemplateNotFound"
        status: pass
    human_judgment: false
  - id: D6
    description: "Seeds EMAIL/WEBHOOK sem triple mustache nem {{& (bypass do escaper)"
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/template/SeedTemplatesTest.java#emailAndWebhookSeedVariantsNeverBypassTheEscaper"
        status: pass
    human_judgment: false
  - id: D7
    description: "Suíte do worker roda inteira sem Docker com ./mvnw -q -pl notify-worker test (166 testes, 14 classes)"
    requirement: TST-01
    verification:
      - kind: other
        ref: "./mvnw -q -pl notify-worker test"
        status: pass
      - kind: other
        ref: "./mvnw -q -pl notify-worker test -Dtest=TemplateRendererTest,SeedTemplatesTest"
        status: pass
    human_judgment: false

duration: 5min
completed: 2026-10-15
status: complete
---

# Fase 02 Plano 03: Renderizador estrito com escape por canal - Resumo

**Webhook com valores escapados como string JSON (`JsonStringEncoder` num escaper JMustache) e parse estrito com `FAIL_ON_TRAILING_TOKENS`, e-mail com escape HTML completo, e `requiredVariables` checado antes de renderizar. Variável ausente, corpo de webhook inválido e template quebrado viram falha permanente com código próprio. Tudo testado sem Docker.**

## Desempenho

- **Duração:** ~5 min
- **Início:** 2026-10-15T23:05:39Z
- **Fim:** 2026-10-15T23:10:30Z
- **Tarefas:** 2
- **Arquivos alterados:** 4 (1 criado, 3 modificados)

## Entregas

- `JsonValueEscaper` (final, `Mustache.Escaper`) devolve `new String(JsonStringEncoder.getInstance().quoteAsString(raw))`. O template fornece as aspas, então valores não-string saem como texto entre aspas.
- `TemplateRenderer` ganhou um terceiro compilador estático, `JSON = Mustache.compiler().withEscaper(new JsonValueEscaper()).strictSections(true)`, sem `escapeHTML` encadeado. O canal WEBHOOK passa a usar esse compilador. EMAIL continua com HTML no corpo e PLAIN no assunto, e SMS continua com PLAIN.
- Depois de renderizado, o corpo do webhook passa por `ObjectReader` com `FAIL_ON_TRAILING_TOKENS`. Se o resultado for nulo, não for objeto ou lançar `JsonProcessingException`, sai `PermanentDeliveryException(INVALID_WEBHOOK_PAYLOAD, "corpo do webhook do template <id> não é um objeto JSON válido")`, sem a causa.
- `requiredVariables` é checado depois de resolver a variante e antes de compilar. Chave ausente ou valor nulo gera `PermanentDeliveryException(MISSING_VARIABLE, "variável obrigatória ausente: <nome>")`. String vazia é aceita. Os padrões do JMustache continuam intactos, então variável usada mas não declarada ainda lança `MustacheException.Context`.
- O construtor sem argumentos e a assinatura de `render` não mudaram, e nenhum chamador do worker foi alterado.
- Suíte TST-01: `TemplateRendererTest` foi de 8 para 20 testes e `SeedTemplatesTest` de 8 para 10. A suíte inteira do worker (166 testes) roda sem Docker.

## Commits das tarefas

1. **Tarefa 1 (tracer): webhook com aspas, barra e quebra de linha sai como JSON válido**
   - RED `7319b10`: test(02-03), 4 testes-alvo falhando por asserção
   - GREEN `3d9c3ce`: feat(02-03), `JsonValueEscaper` + compilador JSON + parse estrito
2. **Tarefa 2: renderização estrita e escape por canal (TST-01)**
   - RED `85849b5`: test(02-03), 3 testes-alvo falhando por asserção
   - GREEN `68c2f7c`: feat(02-03), checagem de `requiredVariables`

REFACTOR: não houve; o código ficou enxuto depois do GREEN.

## Arquivos criados/modificados

- `notify-worker/src/main/java/dev/notifyhub/worker/template/JsonValueEscaper.java`: escaper JMustache para conteúdo de string JSON
- `notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateRenderer.java`: três compiladores, `STRICT_JSON`, `requireJsonObject`, `requireVariables` e javadoc da Fase 2
- `notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java`: webhook com valor hostil do seed real, corpo inválido (lixo no fim, array, vazio), variáveis obrigatórias, classificação das exceções reais, escape HTML completo, assunto e SMS literais
- `notify-worker/src/test/java/dev/notifyhub/worker/template/SeedTemplatesTest.java`: proibição de `{{{`/`{{&` em EMAIL/WEBHOOK e webhook de cada seed válido com aspas e barra nos valores

## Decisões tomadas

- O `ObjectReader` estrito usa um `ObjectMapper` local e estático, não o do Boot. Assim o construtor sem argumentos continua valendo (`DeliveryFlowTest` e `SeedTemplatesTest` instanciam `new TemplateRenderer()`).
- `requiredVariables` é checado depois da resolução da variante, como o plano pede. Por isso variante ausente continua sendo TEMPLATE_NOT_FOUND.
- O RED usa `assertThatCode(...).doesNotThrowAnyException()` antes do parse do webhook, para que a falha seja de asserção e não um erro de execução do teste.

## Desvios do plano

Nenhum. O plano foi executado como escrito.

## TDD Gate Compliance

- Tarefa 1: RED `7319b10` → GREEN `3d9c3ce`
- Tarefa 2: RED `85849b5` → GREEN `68c2f7c`
- Nos dois casos, os testes-alvo falharam por asserção antes da implementação (Tarefa 1: 4 falhas, incluindo `JsonParseException` capturada pelo `assertThatCode`; Tarefa 2: 3 falhas por "Expecting actual throwable to be an instance of" / "Expecting code to raise a throwable") e passaram depois.
- `gsd-tools check tdd-red-evidence` só interpreta saída TAP do Node, a mesma limitação registrada no 02-01, então não foi gerado o registro JSON. A evidência está nos logs do Surefire citados acima.
- Testes de regressão que já passavam no RED (escape HTML completo, classificação TEMPLATE_INVALID/TEMPLATE_NOT_FOUND, `{{&` nos seeds) são guardas de comportamento. Eles não autorizaram o GREEN.

## Problemas encontrados

Nenhum.

## Configuração manual necessária

Nenhuma. Não há serviço externo a configurar.

## Prontidão para a próxima fase

- Variável faltando, corpo de webhook inválido e template quebrado já saem do renderer como falhas PERMANENT com código próprio. O pipeline do 02-01 (`DeliveryProcessor` → FAILED + DLQ) as leva a FAILED sem mudança.
- A verificação em runtime real (variável faltando → FAILED com cópia PERMANENT na DLQ) fica para o plano 02-07.
- Suposições sinalizadas no plano continuam valendo: string vazia é valor válido, e valores não-string no webhook saem como texto JSON entre aspas.

---
*Fase: 02-falhas-retry-com-backoff-e-resili-ncia*
*Concluída: 2026-10-15*

## Self-Check: PASSED

- FOUND: notify-worker/src/main/java/dev/notifyhub/worker/template/JsonValueEscaper.java
- FOUND: commits 7319b10, 3d9c3ce, 85849b5, 68c2f7c
- Critérios de aceitação das duas tarefas: todos PASS (greps e `./mvnw -q -pl notify-worker test` com exit 0)
