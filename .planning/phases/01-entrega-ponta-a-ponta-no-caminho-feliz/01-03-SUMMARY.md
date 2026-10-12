---
phase: 01-entrega-ponta-a-ponta-no-caminho-feliz
plan: 03
subsystem: worker
tags: [jmustache, templates, seed, spring-data-mongodb, configuration-properties, simulated-adapter, virtual-threads]

requires:
  - phase: 01-01
    provides: "Channel, RecipientRules.mask, Collections.TEMPLATES, RenderedContent (notify-common em ~/.m2)"
provides:
  - "TemplateDocument/TemplateVariant (coleção templates, variantes por canal embutidas)"
  - "TemplateSeeder: loadSeed() de classpath*:seed/templates/*.json e seed()/seed(List) → SeedReport(inserted, updated, unchanged), idempotente por versão (D-08)"
  - "TemplateCatalog.require(code) → TemplateNotFoundException se ausente"
  - "TemplateRenderer.render(template, channel, variables, notificationId) → RenderedContent; HTML só no corpo do EMAIL; variável ausente/nula lança MustacheException; _notificationId reservado"
  - "Seed pt-BR: welcome [name], order-shipped [name, orderId], password-reset [name, resetCode], cada um com EMAIL/SMS/WEBHOOK"
  - "ChannelAdapter (channel(), send(recipient, RenderedContent)) e SimulatedChannelAdapter com Thread.sleep e failure-rate"
  - "SimulationProperties notify.simulation.<canal>.{latency-min,latency-max,failure-rate} (50ms/200ms/0.0) e SimulationConfiguration com beans emailAdapter, smsAdapter, webhookAdapter"
affects: [01-05, 01-06, 02]

actuals:
  tokens: 11994
  tasks: 3
  commits: 6
plan_head_before: 9392001be0ee1f63233f57930de496998adfdefd

tech-stack:
  added: []
  patterns:
    - "Dois compiladores JMustache imutáveis: HTML (corpo do EMAIL) e PLAIN escapeHTML(false) (assunto, SMS, WEBHOOK)"
    - "Seed por versão: findById → insert | findAndReplace condicionado à versão lida | nada; nunca upsert com filtro de versão"
    - "Templates só vêm do seed; variáveis do cliente entram apenas como contexto"
    - "Adapter simulado parametrizado registrado uma vez por canal; RandomGenerator obtido na thread que envia"
    - "Validação de propriedades no construtor compacto do record (contexto não sobe com valor inválido)"

key-files:
  created:
    - notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateDocument.java
    - notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateVariant.java
    - notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateCatalog.java
    - notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateNotFoundException.java
    - notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateRenderer.java
    - notify-worker/src/main/java/dev/notifyhub/worker/template/TemplateSeeder.java
    - notify-worker/src/main/resources/seed/templates/welcome.json
    - notify-worker/src/main/resources/seed/templates/order-shipped.json
    - notify-worker/src/main/resources/seed/templates/password-reset.json
    - notify-worker/src/main/java/dev/notifyhub/worker/channel/ChannelAdapter.java
    - notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulatedChannelAdapter.java
    - notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulationProperties.java
    - notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulatedDeliveryException.java
    - notify-worker/src/main/java/dev/notifyhub/worker/channel/SimulationConfiguration.java
    - notify-worker/src/test/java/dev/notifyhub/worker/template/SeedTemplatesTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateSeederTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java
    - notify-worker/src/test/java/dev/notifyhub/worker/channel/SimulatedChannelAdapterTest.java
  modified: []

key-decisions:
  - "TemplateSeeder expõe seed(List<TemplateDocument>) além de seed(): a regra de versão é testada template a template sem depender do conteúdo do classpath"
  - "findAndReplace que casa 0 documentos (outro processo mudou a versão no meio) conta como unchanged e é logado, sem exceção"
  - "loadSeed() falha rápido (IllegalStateException/UncheckedIOException) com seed sem id, sem variantes ou ilegível (T-01-14)"
  - "TemplateNotFoundException guarda templateCode e tem fábricas missing(code) e missingVariant(code, canal)"
  - "SimulatedChannelAdapter expõe simulation() para que a configuração aplicada por canal seja verificável"

patterns-established:
  - "TemplateRenderer.NOTIFICATION_ID_VARIABLE = \"_notificationId\" é a única fonte do nome da variável reservada"
  - "Falha de envio simulada = SimulatedDeliveryException (transitória na Fase 2); template/variante ausente = TemplateNotFoundException (permanente na Fase 2)"

requirements-completed: [TPL-01, DLV-01]

coverage:
  - id: D1
    description: "Seed pt-BR com welcome, order-shipped e password-reset × EMAIL/SMS/WEBHOOK, requiredVariables de D-10 sem _notificationId, todas as 9 variantes renderizáveis e cada WEBHOOK um envelope JSON {event, notificationId, data} válido"
    requirement: TPL-01
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/template/SeedTemplatesTest.java"
        status: pass
    human_judgment: false
  - id: D2
    description: "Seeder idempotente por versão: insert quando ausente, findAndReplace condicionado à versão lida quando menor, nada quando igual/maior, DuplicateKeyException absorvida, segunda rodada sem escrita"
    requirement: TPL-01
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateSeederTest.java"
        status: pass
    human_judgment: false
  - id: D3
    description: "Renderização estrita com escape por canal: HTML só no corpo do EMAIL; assunto, SMS e WEBHOOK sem escape; variável ausente ou nula lança; variante ausente vira TemplateNotFoundException"
    requirement: TPL-01
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/template/TemplateRendererTest.java"
        status: pass
    human_judgment: false
  - id: D4
    description: "Adapter simulado por canal com latência bloqueante e failure-rate configuráveis por notify.simulation.<canal>.* (padrões 50ms/200ms/0.0), validação das propriedades e um bean por canal"
    requirement: DLV-01
    verification:
      - kind: unit
        ref: "notify-worker/src/test/java/dev/notifyhub/worker/channel/SimulatedChannelAdapterTest.java"
        status: pass
      - kind: other
        ref: "./mvnw -q -pl notify-worker test (exit 0, 33 testes)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Nenhuma entrega real: o pacote channel não contém cliente HTTP/SMTP/socket"
    requirement: DLV-01
    verification:
      - kind: other
        ref: "! grep -rqE 'HttpClient|RestTemplate|RestClient|WebClient|JavaMail|java.net.Socket' notify-worker/src/main/java/dev/notifyhub/worker/channel"
        status: pass
    human_judgment: true
    rationale: "A proibição do plano é verification: judgment; o grep cobre os clientes conhecidos, mas a ausência de saída de rede é confirmada por revisão"
  - id: D6
    description: "Templates gravados no Mongo real e renderizados em runtime pelo worker"
    requirement: TPL-01
    verification: []
    human_judgment: true
    rationale: "Depende do runner de startup (plano 01-05) e do smoke contra Mongo real (plano 01-06)"

duration: 7min
completed: 2026-10-12
status: complete
---

# Phase 1 Plan 03: Templates do seed e adapters simulados Summary

**Seed pt-BR com 3 templates × 3 canais aplicado por versão (insert / findAndReplace condicional, sem upsert), renderização JMustache estrita com HTML só no corpo do e-mail e WEBHOOK em envelope JSON, e um `SimulatedChannelAdapter` por canal com `Thread.sleep` e taxa de falha configuráveis em `notify.simulation.*`, sem nenhuma chamada de rede**

## Performance

- **Duration:** 7 min
- **Started:** 2026-10-12T18:54:52Z
- **Completed:** 2026-10-12T19:01:24Z
- **Tasks:** 3
- **Files modified:** 18

## Accomplishments
- O caminho inteiro foi provado sem banco. O arquivo `welcome.json` passa por `TemplateSeeder` (com o `MongoTemplate` mockado), `TemplateCatalog` e `TemplateRenderer` e sai como "Olá, Ana" no EMAIL e no SMS e como `{"event":"user.welcome","notificationId":"n-1","data":{"name":"Ana"}}` no WEBHOOK.
- O seed tem exatamente `welcome`, `order-shipped` e `password-reset`, com textos em pt-BR, os eventos de D-09 e as variáveis de D-10. As 9 variantes renderizam e cada WEBHOOK passa em `readTree`.
- O `TemplateSeeder` segue D-08 e a Armadilha 2: insere quando o template está ausente, faz `findAndReplace` filtrando por `_id` e pela versão lida quando o banco tem versão menor, e não escreve nada quando ela é igual ou maior. A `DuplicateKeyException` de um insert concorrente é absorvida.
- O `TemplateRenderer` usa dois compiladores imutáveis. O compilador HTML escapa `<b>` para `&lt;b&gt;` no corpo do e-mail. O PLAIN deixa "Ana & Bia" intacto no assunto e no SMS e evita `&quot;` no WEBHOOK. Uma variável ausente ou nula lança `MustacheException`.
- Há um `SimulatedChannelAdapter` por canal (`emailAdapter`, `smsAdapter`, `webhookAdapter`). O sleep é bloqueante e fica fora de qualquer monitor. A falha simulada vira `SimulatedDeliveryException`, e uma interrupção restaura a flag. O destinatário sai mascarado no log. Propriedades inválidas impedem o contexto de subir.

## Task Commits

1. **Task 1: Template ponta a ponta (tracer)**: `b204798` (test, RED) e `7d3f20f` (feat, GREEN)
2. **Task 2: Seed completo, seeder por versão e renderizador estrito**: `651f24e` (test, RED) e `d1e8cfe` (feat, GREEN)
3. **Task 3: Adapters simulados**: `8e38726` (test, RED) e `13cad8e` (feat, GREEN)

**Plan metadata:** commit do SUMMARY (docs)

## Files Created/Modified
- `worker/template/TemplateDocument.java`, `TemplateVariant.java`: records da coleção `templates` e do JSON do seed
- `worker/template/TemplateCatalog.java`: `require(code)` por `findById`
- `worker/template/TemplateNotFoundException.java`: template ou variante ausente, com `templateCode()`
- `worker/template/TemplateRenderer.java`: renderização por canal, `_notificationId` reservado
- `worker/template/TemplateSeeder.java`: `loadSeed()`, `seed()`, `seed(List)` e `SeedReport`
- `resources/seed/templates/{welcome,order-shipped,password-reset}.json`: seed em UTF-8 e pt-BR
- `worker/channel/ChannelAdapter.java`, `SimulatedChannelAdapter.java`, `SimulatedDeliveryException.java`
- `worker/channel/SimulationProperties.java` (+ `ChannelSimulation`), `SimulationConfiguration.java`
- 4 classes de teste (33 testes no worker)

## Decisions Made
- `seed(List<TemplateDocument>)` é público ao lado de `seed()`. Assim o `TemplateSeederTest` cobre a regra de versão para um template isolado, sem depender do classpath.
- Quando o `findAndReplace` não casa nenhum documento (outro processo mudou a versão no meio), o template conta como "sem mudança" e o caso é logado, sem exceção. O worker continua subindo.
- O `loadSeed()` falha rápido com um seed ilegível, sem id ou sem variantes (mitigação de T-01-14). O worker não sobe com um seed quebrado.
- O `SimulatedChannelAdapter.simulation()` expõe a configuração aplicada, para que o teste de contexto confira padrões e overrides por canal.

## Deviations from Plan

### Critério de aceitação não satisfeito literalmente

**1. [Rule 1 - Bug no critério] `grep -q '"user.welcome"' welcome.json` não casa**
- **Found during:** Task 1 (verificação dos critérios de aceitação)
- **Issue:** A própria ação do plano exige o envelope WEBHOOK "escrito como string JSON escapada", então o arquivo traz `\"user.welcome\"`. O caractere depois de `welcome` é `\`, não `"`, e o grep com aspas literais nunca casa. O critério equivalente de `order-shipped.json` passa só por acaso: o `.` da regex casa com o `-` de `"id": "order-shipped"`.
- **Fix:** Nenhuma mudança no arquivo. Passar no grep literal exigiria um campo novo no documento (o record do plano tem só 4 componentes) ou um seed que deixa de ser uma string JSON. O `SeedTemplatesTest` prova a mesma coisa de forma mais forte: depois de `ObjectMapper.readTree`, `event` é `"user.welcome"` (e `order.shipped`/`password.reset` para os outros). `grep -q 'user.welcome'` sem aspas passa.
- **Files modified:** nenhum
- **Verification:** `SeedTemplatesTest.welcomeWebhookRendersValidJsonEnvelope` e `allNineSeedVariantsRenderAndEveryWebhookIsAValidEnvelope` verdes
- **Committed in:** n/a

### Nota de processo

- O guard de branch protegida do executor (`git.base-branch --is-protected main` → `true`) recusaria commits em `main`. O orquestrador despachou este plano como executor sequencial na árvore principal, com `branching_strategy: none`, e o plano 01-02 já fez commits diretos em `main` do mesmo jeito. Segui esse padrão e não reescrevi nenhuma ref.
- Sobre o TDD: nas Tasks 1 e 3 o RED foi falha de compilação por falta das classes, a mesma convenção dos planos 01-01 e 01-02. Na Task 2 o RED foi falha de asserção real (`seedContainsExactlyTheThreeEcommerceTemplates` e `seedRequiredVariablesMatchTheContract`, porque o seed ainda não tinha os dois templates). Os 14 testes de seeder e renderizador já passaram no RED, porque as classes vieram da Task 1, e agora travam esse comportamento. Em todas as tarefas os commits `test(01-03)` vêm antes dos `feat(01-03)`.

---

**Total deviations:** 1 (critério de aceitação com bug, sem mudança de código)
**Impact on plan:** Nenhum no escopo nem no comportamento. O critério literal está com defeito, e o teste cobre a intenção dele.

## Issues Encountered
- `iconv` não existe no Git Bash. Confirmei o UTF-8 dos JSONs pela sequência de bytes de `á` (C3 A1) e pelo teste que procura "Olá, Ana".
- O `./mvnw -q` não imprime a contagem de testes. Usei os relatórios do surefire: 11 + 8 + 8 + 6 = 33 testes, 0 falhas e 0 erros.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- O plano 01-05 pode injetar `TemplateCatalog`, `TemplateRenderer`, `TemplateSeeder` e os `ChannelAdapter`s. O runner `@Order(2)` deve chamar `templateSeeder.seed()`. Para escolher o adapter por canal, basta um `Map<Channel, ChannelAdapter>` montado a partir de `channel()`.
- O worker precisa importar `SimulationConfiguration`, ou garantir que ela esteja no component scan (ela fica em `dev.notifyhub.worker.channel`, abaixo de `NotifyWorkerApplication`). O bloco `notify.simulation` do `application.yml` é opcional, porque os padrões vêm do record.
- O escape JSON dos valores no WEBHOOK continua para a Fase 2 (TPL-03): um valor com aspas ainda quebra o envelope.

---
*Phase: 01-entrega-ponta-a-ponta-no-caminho-feliz*
*Completed: 2026-10-12*

## Self-Check: PASSED
- Os 18 arquivos de key-files.created existem no disco
- Os commits b204798, 7d3f20f, 651f24e, d1e8cfe, 8e38726 e 13cad8e estão no histórico
- `./mvnw -q -pl notify-worker test` verde (33 testes, 0 falhas); critérios de aceitação das 3 tarefas verdes, exceto o grep literal `'"user.welcome"'` documentado em Deviations
