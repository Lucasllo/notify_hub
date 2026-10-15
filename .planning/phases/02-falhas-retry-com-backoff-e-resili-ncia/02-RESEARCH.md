# Fase 2: Falhas, retry com backoff e resiliência - Pesquisa

**Pesquisado em:** 2026-10-14
**Domínio:** classificação de falhas e escada de delay (Spring AMQP 3.2.12 sobre RabbitMQ 4.3), circuit breaker e bulkhead programáticos (Resilience4j 2.4.0), JMustache 1.16 com escape por canal, Micrometer 1.15.12 + Prometheus, claim/lease no MongoDB
**Confiança:** ALTA para APIs e comportamento de biblioteca (código-fonte das versões exatas lido nesta sessão e probes executados com os jars reais); MÉDIA para dimensionamento de CB/bulkhead (modelo + simulação local); itens marcados `[ASSUMED]` precisam de confirmação

> Esta pesquisa parte do código da Fase 1 (lido nesta sessão) e de `.planning/research/`. Ela não repete o que já está lá: acrescenta as respostas às "notas de pesquisa" do ROADMAP (dimensionamento de CB/bulkhead, `CallNotPermitted`, `x-death` no 4.3), três conflitos/armadilhas que mudam o plano e o desenho concreto sobre as classes existentes. Código e identificadores em inglês; texto em pt-BR, conforme o CLAUDE.md. `workflow.nyquist_validation` está `false` em `.planning/config.json`, então a seção "Arquitetura de validação" foi omitida; a estratégia de teste aparece em "Requisitos da fase" e em "Armadilhas".

<user_constraints>
## Restrições do usuário (de CONTEXT.md)

### Decisões travadas

#### Destinatários mágicos (DLV-02)
- **D-01:** A convenção é prefixo semântico + sufixo, legível no k6 e no README:
  - E-mail: `always-transient@fail.test`, `always-permanent@fail.test`, `fail-<N>-then-ok@fail.test` e `fail-until-reprocess@fail.test`.
  - Webhook: `https://fail.test/always-transient`, `/always-permanent`, `/fail-<N>-then-ok` e `/fail-until-reprocess`.
  - SMS: como o E.164 só tem dígitos, usa o prefixo `+550000000` seguido de um código numérico documentado. Por exemplo, `…001` = sempre transitória, `…002` = sempre permanente, `…1N3` = falha N vezes e `…004` = até reprocessar. O planner fecha os dígitos exatos, desde que caibam em `RecipientRules` (E.164, até 16 caracteres).

  Destinatário que não casa com nenhum padrão mágico segue o fluxo normal (latência + `failure-rate` aleatório). — **Reversibility:** costly — o k6 (Fase 4), os ITs (Fase 3) e o README referenciam esses destinatários.
- **D-02:** "Falha N vezes e depois sucede" usa o `retryCount` da mensagem: falha enquanto `retryCount < N`. É determinístico e não guarda estado. Com 3 níveis, N ∈ {1,2,3} termina SENT e N ≥ 4 termina DEAD_LETTERED. Depois de um reprocess (que zera o contador) volta a falhar, e isso precisa estar documentado.
- **D-03:** Há um quarto destinatário mágico, `fail-until-reprocess`, baseado no `attemptCount` total do documento, que o reprocess não zera. Ele falha enquanto `attemptCount ≤ 4` (envio inicial + 3 níveis) e sucede depois. Isso torna demonstrável retry → DLQ → reprocess → SENT na demo e no IT da Fase 3 sem trocar configuração em tempo de execução. Por isso o adapter recebe o contexto da tentativa (`retryCount`, `attemptCount`) além de destinatário e conteúdo.
- **D-04:** A falha mágica é injetada **dentro do adapter, sob o bulkhead e o circuit breaker**. Ela aplica a latência simulada e lança a mesma exceção de uma falha real do provedor (transitória ≈ 5xx/timeout simulado; permanente ≈ destinatário inválido). Uma rajada de `always-transient` abre o CB, e isso faz parte da demo.

#### Circuit breaker e bulkhead na escada (RES-01, RES-02)
- **D-05:** `CallNotPermittedException` (CB aberto) e `BulkheadFullException` são transitórias **e consomem um nível da escada**, como qualquer transitória. A regra é uma só, sem contador separado. O risco conhecido é que um CB aberto por muito tempo esgote a escada e mande a notificação para DEAD_LETTERED. Ele é mitigado pelo tempo aberto ≤ 1º nível (D-06), e o reprocess da Fase 3 recupera o que sobrar.
- **D-06:** O CB é sensível e se recupera rápido: COUNT_BASED com janela de 20, mínimo de 10 chamadas, limiar de 50%, `waitDurationInOpenState` ≤ 1º nível da escada (demo ~1s; padrão ~5s) e 3 chamadas em half-open. Ele registra **só falhas transitórias**: as permanentes ficam em `ignoreExceptions`, porque um destinatário inválido não indica provedor doente. Tudo é configurável por canal em `application.yml` (instâncias `email`, `sms`, `webhook`).
- **D-07:** O bulkhead por semáforo fica abaixo da concorrência do listener (ex.: `maxConcurrentCalls=8` com 10 consumidores) e `maxWaitDuration=0`, para que a recusa apareça sob carga. Configurável por canal. Não usar `ThreadPoolBulkhead`, que tira o trabalho das virtual threads.
- **D-08:** Bulkhead, CB e failure-rate precisam ser totalmente configuráveis por propriedade/ENV, porque o profile do benchmark da Fase 4 vai afrouxar o bulkhead (≥ concorrência) e neutralizar o CB/falhas. A Fase 2 não cria esse profile, só garante que ele seja possível.

#### PROCESSING preso e ack (WRK-03)
- **D-09:** O claim também aceita PROCESSING quando **a mensagem chega com `redelivered=true` OU quando `leaseUntil < agora`**. O fence por `retryCount` continua valendo. `redelivered` cobre o caso típico (worker derrubado, broker reentrega na hora) e o lease cobre o resto. Isso atualiza `NotificationStatus.claimableForDelivery()` e o filtro do `NotificationStateService.claim`, e a transição PROCESSING→PROCESSING já existe na tabela.
- **D-10:** O ack mode continua **AUTO, com confirm síncrono dentro do listener**. O listener republica na fila de delay e bloqueia até o confirm, o que é barato em virtual threads. Retorno normal faz ack. Para mandar para a DLQ usa `AmqpRejectAndDontRequeueException`. MANUAL não é usado. Isso resolve o blocker do STATE.md.
- **D-11:** O retry segue esta ordem:
  1. Update atômico PROCESSING→RETRYING + `$push` attempt.
  2. Publish na fila de delay do próximo nível com `notify-retry-count` incrementado e espera do confirm.
  3. Retorno normal (ack).

  Se o publish der nack, timeout ou for devolvido, faz o update condicional RETRYING→DEAD_LETTERED com `lastError` "republicação para retry.<nível> falhou" e rejeita a mensagem para a DLQ. Assim o status nunca diverge de onde a mensagem está e nada fica em PROCESSING. **A transição RETRYING→DEAD_LETTERED entra na tabela de `NotificationStatus`.** — **Reversibility:** costly — a tabela de transições é o contrato dos filtros condicionais da API e do worker.
- **D-12:** A prova do critério 3 nesta fase tem duas partes: testes unitários do filtro de claim (redelivered/lease/fence) com `MongoBson`, como na Fase 1, e um passo no smoke da fase (notificação com latência longa → kill do worker antes do ack → restart → SENT com um único envio de sucesso). O IT automatizado fica para a Fase 3 (TST-03).

#### Histórico e métricas (DLV-03, OBS-01)
- **D-13:** Uma exceção desconhecida (não classificada) leva a notificação a **FAILED com a nova classe `UNKNOWN`**: o enum `FailureClass` e o header `x-failure-class` ganham esse valor. Ela não gasta retries e vai para a DLQ. Assim o operador distingue bug de erro do cliente. — **Reversibility:** costly — o enum é compartilhado (`notify-common`), persistido no Mongo e exposto no `GET /{id}`.
- **D-14:** **Todo processamento após o claim gera um `AttemptEntry`**, inclusive rejeição de CB/bulkhead e falha de template antes do adapter. A entrada traz outcome, `failureClass` e um código de erro estável de um conjunto fechado (ex.: `SIMULATED_TRANSIENT`, `SIMULATED_PERMANENT`, `CIRCUIT_OPEN`, `BULKHEAD_FULL`, `TEMPLATE_NOT_FOUND`, `MISSING_VARIABLE`, `INVALID_WEBHOOK_PAYLOAD`, `REPUBLISH_FAILED`, `UNEXPECTED`), com `durationMs` ≈ 0 quando o adapter não foi chamado. Isso garante "uma tentativa por nível" no critério 1.
- **D-15:** Formato das métricas, do qual o dashboard da Fase 4 depende:
  - `notify.delivery.outcomes{channel, outcome=sent|retrying|failed|dead_lettered, failure_class}`
  - `notify.delivery.retries{channel, level=1|2|3}`
  - timer `notify.delivery.duration{channel, outcome}` com histograma/percentis para o p95

  As tags têm conjunto fechado e são pré-registradas na inicialização. Os códigos de erro detalhados **não** viram tag: ficam no Mongo e no log. O worker passa a expor `prometheus` no actuator (`micrometer-registry-prometheus`). As métricas do Resilience4j (estado do CB, bulkhead) vêm do `resilience4j-micrometer`. — **Reversibility:** costly — o dashboard provisionado da Fase 4 e as queries do README usam esses nomes.

#### Templates (TPL-02, TPL-03)
- **D-16:** No webhook, os valores são escapados como string JSON (aspas, barra invertida, caracteres de controle) por um escaper próprio do JMustache. Depois de renderizar, o corpo passa por um parse com o `ObjectMapper`. JSON inválido gera falha PERMANENT com `INVALID_WEBHOOK_PAYLOAD` em vez de sair corrompido. E-mail escapa HTML no corpo, e o SMS sai sem escape. Template inexistente, variante ausente e variável obrigatória faltando são PERMANENT. A variável obrigatória é conferida contra `requiredVariables` e também pelo comportamento padrão do JMustache, que lança exceção.

### Critério do Claude
- Dígitos exatos dos códigos mágicos de SMS (D-01) e onde fica o parser de destinatário mágico (provavelmente `notify-worker/channel`).
- Uso da API programática (`CircuitBreakerRegistry`/`Decorators`) ou de anotações + `spring-boot-starter-aop`. A programática é a preferida por deixar explícita a ordem bulkhead → CB → adapter e não depender de proxy.
- Desenho do classificador: mapa de exceção para classe/código, exaustivo e testado unitariamente.
- Momento de registrar cada contador (pré-registro de todas as combinações de tag).
- Corrigir o deferred item da Fase 1 (`MustacheAutoConfiguration` ativo no worker) se for trivial ao mexer no `application.yml`.

### Ideias adiadas (FORA DO ESCOPO)
- Profile de benchmark com bulkhead afrouxado e CB neutralizado. Pertence à Fase 4 (DEM-03). A Fase 2 só garante a configurabilidade.
- IT automatizado de worker derrubado / entrega duplicada concorrente: Fase 3 (TST-03).

**Fora desta fase (do Phase Boundary do CONTEXT):** reprocessamento da DLQ e republish de PUBLISH_FAILED (ADM-*); Idempotency-Key, listagem, stats e Swagger; ITs com Testcontainers (TST-02..04); Prometheus/Grafana no compose, k6, benchmark e README.
</user_constraints>

## Restrições do projeto (do CLAUDE.md)

| Diretiva | Impacto nesta fase |
|----------|--------------------|
| Java 21, Spring Boot 3.5.16, Spring AMQP, Spring Data MongoDB, Resilience4j, Micrometer | Resilience4j **2.4.0** (`resilience4j-spring-boot3`), não 3.x nem o módulo `-spring-boot4` |
| Não sobrescrever versões gerenciadas pelo Boot (`spring-amqp.version`, `micrometer.version` etc.) | `micrometer-registry-prometheus` entra **sem versão**; só o Resilience4j é pinado (via `resilience4j-bom` 2.4.0) |
| `ThreadPoolBulkhead` proibido; usar `Bulkhead` por semáforo | Confirmado no código: `SemaphoreBulkhead` usa `ReentrantLock` + `Semaphore` (sem `synchronized`) |
| Sem `RetryInterceptorBuilder`/`listener.simple.retry.enabled=true`; sem requeue em falha | O retry é só pelas filas de delay; `default-requeue-rejected: false` continua |
| Sem `x-queue-mode`/`x-queue-version`, só filas duráveis | Esta fase **não cria filas novas**; a topologia da Fase 1 já tem as 3 filas de delay e a DLQ por canal |
| Sem Lombok; records para DTOs | `AttemptContext`, `Classification`, `RetryDecision` como records |
| `spring-boot-starter-mustache` proibido | Desligar `MustacheAutoConfiguration` no worker (deferred item da Fase 1) |
| Sem `Thread.sleep` em ITs (Awaitility) | Testes desta fase são unitários; o smoke em bash usa polling com limite |
| `mvn test` rápido e sem Docker | TST-01 e todos os testes novos precisam rodar sem Docker (sem `@SpringBootTest` com Mongo/Rabbit) |
| Documentação em pt-BR; comentários e logs em pt-BR; destinatário sempre mascarado com `RecipientRules.mask` | Mensagens de erro gravadas no Mongo e logs em pt-BR, sem destinatário |
| GSD workflow | Edições só via `/gsd-execute-phase` |

<phase_requirements>
## Requisitos da fase

| ID | Descrição | Apoio da pesquisa |
|----|-----------|-------------------|
| WRK-03 | PROCESSING preso recuperável via `redelivered` ou lease expirado | Padrão 4 (filtro do claim com `$or`); header `AmqpHeaders.REDELIVERED` verificado; Armadilha 2 (retryCount não sobe no RETRYING); smoke com `taskkill //F` |
| TPL-02 | Renderizador estrito: variável ausente / template inexistente = permanente | Padrão 9; probe confirmou `MustacheException.Context` para variável ausente **e** nula, `MustacheParseException` para template quebrado |
| TPL-03 | Escape por canal: HTML no e-mail, nada no SMS, JSON no webhook | Padrão 9; escaper com `JsonStringEncoder` do Jackson (probe com aspas, `\`, `\n`, `\t`, `\u0001`); Armadilha 6 (`readTree` aceita lixo depois do JSON) |
| DLV-02 | Destinatários mágicos determinísticos | Padrão 8 (parser puro + dígitos SMS que cabem em `RecipientRules`) |
| DLV-03 | Classificador exaustivo; desconhecida vai para a DLQ | Padrão 7 (tabela ordenada exceção → classe/código); tradução de exceções do Mongo verificada |
| DLV-04 | Transitória com níveis restantes → fila de delay com `notify-retry-count`, confirm antes do ack, RETRYING | Padrões 2 e 5; `CorrelationData()` com UUID (id único é exigido quando há returns) |
| DLV-05 | Retries esgotados → DEAD_LETTERED + DLQ | Padrões 2 e 3 |
| DLV-06 | Permanente → FAILED + DLQ com `x-failure-class: PERMANENT` | **Padrão 3 e Pergunta em aberto 1: impossível com reject em AMQP 0-9-1; exige publicação explícita em `notify.dlx`** |
| RES-01 | CB por canal, só transitórias, abre visivelmente na demo | Padrões 5 e 6; semântica record/ignore lida no código; `wait` < 1º nível |
| RES-02 | Bulkhead por semáforo dimensionado vs concorrência; cheio = transitória | Padrões 5 e 6; simulação local do "giro" de recusas |
| OBS-01 | Contadores por canal + timer de latência, pré-registrados | Padrão 10; nomes de métricas do Resilience4j lidos no código; mesmo conjunto de chaves de tag |
| TST-01 | Testes unitários do renderizador (render, variável ausente, template inexistente, escape) sem Docker | `TemplateRendererTest` já existe e roda sem Docker; ampliar com JSON/aspas, `requiredVariables`, `MustacheParseException` |
</phase_requirements>

## Resumo

A Fase 1 deixou quase tudo pronto: a topologia já declara as três filas de delay e a DLQ por canal, o worker já tem `publisher-confirm-type: correlated`, `mandatory`, ack AUTO e `default-requeue-rejected: false`, e o claim/markSent já seguem o molde de update condicional derivado da tabela `NotificationStatus`. A Fase 2 é, essencialmente, (1) um `try/catch` com classificador em volta de template → render → adapter no `DeliveryProcessor`, (2) três novos resultados atômicos no `NotificationStateService` (RETRYING, FAILED, DEAD_LETTERED), (3) um publicador com confirm no worker para a fila de delay e para a DLQ, (4) um `ChannelGateway` que decora o adapter com bulkhead → CB, (5) o renderizador estrito com escaper JSON, (6) métricas pré-registradas e (7) o claim ampliado para PROCESSING com `redelivered`/lease.

Três achados mudam o plano e precisam aparecer nas tarefas. **(a)** Um consumidor AMQP 0-9-1 **não consegue acrescentar headers ao rejeitar** uma mensagem (`[CITED: rabbitmq.com/blog/2024/10/11/modified-outcome]`: "An AMQP 0.9.1 consumer cannot set custom headers before dead lettering a message"). Logo, "rejeitada para a DLQ com o header `x-failure-class`" (DLV-06, critério 2) só é possível **publicando** uma cópia em `notify.dlx` com o header, esperando o confirm e então confirmando a original — o que ajusta a letra do D-10. Isso precisa de confirmação do usuário (Pergunta em aberto 1). **(b)** Na ordem do D-11 (update RETRYING antes do publish), o update RETRYING **não pode incrementar `retryCount`**: se incrementar e o worker cair entre o update e o publish, a reentrega da original (`h`) é cercada pelo filtro `retryCount ≤ h` e descartada como obsoleta, e a notificação fica RETRYING para sempre sem mensagem em lugar nenhum. **(c)** Com `maxWaitDuration=0` e bulkhead abaixo da concorrência, os consumidores recusados não esperam: eles terminam rápido e pegam a próxima mensagem, que também é recusada. Numa simulação local com 10 consumidores, 8 permissões e backlog de 1000, **~40% das mensagens foram recusadas na primeira passagem** (com 9 permissões, ~17%). O mesmo "giro" acontece com o CB aberto e no half-open (só 3 chamadas permitidas). Isso é exatamente o que a demo quer mostrar, mas precisa de números conscientes (Padrão 6).

Também foram verificados detalhes que evitam retrabalho: `Decorators` **não** vem no `resilience4j-spring-boot3` (está no `resilience4j-all`), então a composição deve usar `Bulkhead.decorateRunnable(bh, CircuitBreaker.decorateRunnable(cb, call))`; uma exceção que não está em `recordExceptions` nem em `ignoreExceptions` **conta como sucesso** no CB; o Resilience4j não publica contador de recusas do bulkhead (só `available`/`max` concurrent calls); e `ObjectMapper.readTree("{...} lixo")` devolve o objeto sem erro, então o parse do webhook precisa de `FAIL_ON_TRAILING_TOKENS`.

**Recomendação principal:** implementar o caminho de falha como um pipeline explícito no `DeliveryProcessor` — claim → (template, render, `ChannelGateway.send`) dentro de um único `try` → `FailureClassifier` → um de quatro resultados atômicos (SENT, RETRYING+publish delay, FAILED+DLQ, DEAD_LETTERED+DLQ) — com a DLQ alimentada por **publicação confirmada em `notify.dlx` com `x-failure-class`** e o reject só como rede de segurança.

## Mapa de responsabilidade arquitetural

| Capacidade | Camada principal | Camada secundária | Motivo |
|------------|------------------|-------------------|--------|
| Classificação de falha (DLV-03) | Worker (`processing`/`failure`) | — | Só o worker vê as exceções do adapter, do template e do CB |
| Escada de delay e DLQ (DLV-04/05/06) | Broker (filas TTL+DLX da Fase 1) | Worker (publica no nível certo com confirm) | O delay é do broker; a escolha do nível é da aplicação (`notify-retry-count`) |
| Estado e histórico (RETRYING/FAILED/DEAD_LETTERED, `attempts[]`) | Banco (MongoDB, updates condicionais) | Worker | O documento é a fonte da verdade; a API só lê |
| Recuperação de PROCESSING (WRK-03) | Banco (filtro do claim) | Broker (flag `redelivered`) | O claim atômico decide; o broker só sinaliza a reentrega |
| CB e bulkhead (RES-01/02) | Worker (`ChannelGateway`, Resilience4j em memória) | — | Protegem o adapter, que roda no worker |
| Destinatários mágicos (DLV-02) | Worker (adapter simulado) | API (validação de formato, já existe) | D-04: falha injetada dentro do adapter, sob CB/bulkhead |
| Renderização e escape (TPL-02/03) | Worker (`template`) | — | Templates vêm do Mongo; só o worker renderiza |
| Métricas (OBS-01) | Worker (Micrometer + actuator) | Resilience4j-micrometer (automático) | Contadores nos pontos de resultado; estado do CB vem pronto |
| Exibição do resultado | API (`GET /notifications/{id}`) | — | `NotificationView` já expõe `attempts[]`, `lastError` e `failureClass`; nada muda na API além do novo valor `UNKNOWN` |

## Stack padrão

### Núcleo (novo nesta fase)

| Biblioteca | Versão | Propósito | Por que é o padrão |
|------------|--------|-----------|--------------------|
| `io.github.resilience4j:resilience4j-spring-boot3` | **2.4.0** (via `resilience4j-bom` 2.4.0 importado no `dependencyManagement` do POM pai) | Registries de CB e bulkhead configurados por YAML, métricas Micrometer automáticas | Última versão no Maven Central (`<latest>2.4.0</latest>`, publicada 2026-03-14) `[VERIFIED: repo1.maven.org maven-metadata.xml + Last-Modified do POM]`. Traz `resilience4j-circuitbreaker`, `-bulkhead` e `-micrometer` em escopo compile via `resilience4j-framework-common` `[VERIFIED: POMs 2.4.0 lidos nesta sessão]` |
| `io.micrometer:micrometer-registry-prometheus` | gerenciado (1.15.12) | `/actuator/prometheus` no worker | Gerenciado pelo BOM do Boot 3.5.16 (`micrometer.version` 1.15.12) `[VERIFIED: spring-boot-dependencies-3.5.16.pom:145; micrometer-bom-1.15.12.pom:162-163]` |

### Já presentes e reaproveitados

| Biblioteca | Versão | Uso nesta fase |
|------------|--------|----------------|
| `com.samskivert:jmustache` | 1.16 (gerenciado) | `withEscaper(...)` para o webhook; `Escapers.HTML` no corpo do e-mail |
| `jackson-core`/`jackson-databind` | 2.21.4 (gerenciado; `jackson-bom.version` 2.21.4 no BOM do 3.5.16) | `JsonStringEncoder` como escaper JSON; `ObjectReader` com `FAIL_ON_TRAILING_TOKENS` para validar o corpo |
| `spring-rabbit` | 3.2.12 | `RabbitTemplate.convertAndSend(..., CorrelationData)`, `AmqpHeaders.REDELIVERED`, `AmqpRejectAndDontRequeueException` |
| `spring-boot-starter-actuator` | gerenciado | Expor `prometheus` |
| `spring-boot-starter-test` | gerenciado | `SimpleMeterRegistry`, `ApplicationContextRunner` para testar a config do Resilience4j sem Docker |

### Alternativas consideradas

| Em vez de | Poderia usar | Trade-off |
|-----------|--------------|-----------|
| `Bulkhead.decorateRunnable` + `CircuitBreaker.decorateRunnable` | `io.github.resilience4j.decorators.Decorators` | `Decorators` está no artefato `resilience4j-all` (existe em 2.4.0), **não** no `resilience4j-spring-boot3` `[VERIFIED: busca da classe nos sources dos 6 módulos baixados; resilience4j-all-2.4.0.pom existe]`. Acrescentar o `-all` só por açúcar sintático puxa ratelimiter/retry/timelimiter/cache. Não vale |
| API programática | Anotações `@CircuitBreaker`/`@Bulkhead` + `spring-boot-starter-aop` | O aspect só é registrado com AspectJ no classpath (`@Conditional(AspectJOnClasspathCondition.class)`) `[VERIFIED: AbstractCircuitBreakerConfigurationOnMissingBean.java:83-85]`. Anotações escondem a ordem e a escolha da instância por canal; a programática é a preferência do CONTEXT |
| Escaper com `JsonStringEncoder` | Escaper escrito à mão (loop de `char`) | O encoder do Jackson já trata `"`, `\`, U+0000–U+001F e pares substitutos; zero código novo de escape |
| Publicar na DLQ com header (recomendado) | Só `AmqpRejectAndDontRequeueException` | O reject não carrega header; perde `x-failure-class` (ver Padrão 3) |

**Instalação (POM pai e worker):**
```xml
<!-- pom.xml (pai), dentro de <dependencyManagement><dependencies> -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-bom</artifactId>
    <version>2.4.0</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>

<!-- notify-worker/pom.xml -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```
Não adicionar `spring-boot-starter-aop` (API programática). O `resilience4j-bom` 2.4.0 só lista artefatos `io.github.resilience4j` (22 `artifactId`), então não interfere no BOM do Boot `[VERIFIED: resilience4j-bom-2.4.0.pom]`.

## Auditoria de legitimidade de pacotes

O seam `package-legitimacy` do GSD só aceita `npm|pypi|crates` (resposta: `Usage: ... --ecosystem <npm|pypi|crates>`), então a verificação de Maven foi manual contra o Maven Central.

| Pacote | Registro | Idade | Downloads | Repositório | Veredito | Disposição |
|--------|----------|-------|-----------|-------------|----------|------------|
| `io.github.resilience4j:resilience4j-spring-boot3:2.4.0` | Maven Central | 2.4.0 de 2026-03-14; projeto desde 2016 | não medido (Central não expõe) | github.com/resilience4j/resilience4j (no POM) | OK (manual) | Aprovado — já listado em STACK.md e no CLAUDE.md |
| `io.github.resilience4j:resilience4j-bom:2.4.0` | Maven Central | idem | — | idem | OK (manual) | Aprovado |
| `io.micrometer:micrometer-registry-prometheus` | Maven Central | gerenciado pelo Boot 3.5.16 | — | github.com/micrometer-metrics/micrometer | OK (gerenciado) | Aprovado |

**Removidos por [SLOP]:** nenhum.
**Marcados [SUS]:** nenhum.
Nenhum pacote foi descoberto por busca web: todos vêm do STACK.md/CLAUDE.md e foram conferidos no Central nesta sessão. Pacotes Maven não têm `postinstall`.

## Padrões de arquitetura

### Diagrama do fluxo de falha

```
 notify.<canal> (fila principal)                     notify.<canal>.retry.<L1|L2|L3> (TTL)
        │  NotificationMessage + notify-retry-count=h        ▲              │ expira → DLX notify.exchange
        ▼                                                    │              │ (routing key notification.<canal>)
 ChannelListeners (@RabbitListener, VT, ack AUTO)            │              ▼
        │  h = RetryHeaders.parse(...), redelivered          │        volta à fila principal com h+1
        ▼                                                    │
 DeliveryProcessor.process                                   │
   1. valida mensagem (canal, id) ── inválida ──► AmqpRejectAndDontRequeueException ─► DLX ─► notify.<canal>.dlq
   2. claim(id, h, redelivered) ── vazio ──► return (ack, duplicada/obsoleta)
   3. try {
        TemplateCatalog.require → TemplateRenderer.render (estrito, escape por canal)
        ChannelGateway.send: Bulkhead ─► CircuitBreaker ─► [timer] SimulatedChannelAdapter (mágicos + latência)
        markSent ──────────────────────────────────────────► return (ack)          [outcomes sent]
      } catch (Exception e) → FailureClassifier.classify(e) → (classe, código)
        ├─ TRANSIENT e h < níveis ─► markRetrying(h) ─► publish confirmado em notify.retry/<canal>.retry.<L(h+1)> com h+1
        │                              ├─ ok ─► return (ack)                                   [outcomes retrying, retries level=h+1]
        │                              └─ falhou ─► RETRYING→DEAD_LETTERED (REPUBLISH_FAILED) ─► DLQ(TRANSIENT)
        ├─ TRANSIENT e h = níveis ─► markDeadLettered ─► DLQ(TRANSIENT)                          [outcomes dead_lettered]
        └─ PERMANENT | UNKNOWN ─► markFailed ─► DLQ(PERMANENT|UNKNOWN)                           [outcomes failed]

 DLQ(classe) = publish confirmado em notify.dlx/<canal>.dlq com x-failure-class=<classe> ─► return (ack)
               se o publish falhar ─► throw AmqpRejectAndDontRequeueException (broker dead-letter sem header)
```

### Estrutura recomendada (dentro de `notify-worker`)

```
dev/notifyhub/worker/
├── channel/        # ChannelAdapter (assinatura nova), SimulatedChannelAdapter, MagicRecipients (parser puro),
│                   # AttemptContext (record), SimulationProperties (+ flag magic-recipients)
├── failure/        # DeliveryFailureException (abstrata, errorCode), TransientDeliveryException,
│                   # PermanentDeliveryException, FailureClassifier, Classification (record), ErrorCodes
├── resilience/     # ChannelGateway (bulkhead → CB → adapter), NonTransientFailurePredicate,
│                   # validação de startup (wait do CB ≤ 1º nível)
├── messaging/      # DeliveryRoutingPublisher: publishToRetry(...) e publishToDlq(...), ambos com confirm
├── metrics/        # DeliveryMetrics: pré-registro e incrementos (EnumMap por canal)
├── processing/     # DeliveryProcessor (pipeline + ramos), NotificationStateService (+ markRetrying/markFailed/
│                   # markDeadLettered/markDeadLetteredAfterRepublishFailure), RetryPolicy (função pura)
├── template/       # TemplateRenderer (estrito, 3 compiladores), JsonValueEscaper, MissingVariableException
└── listener/       # ChannelListeners (+ header redelivered), RetryHeaders (inalterado)
notify-common/
├── domain/         # FailureClass + UNKNOWN; NotificationStatus + RETRYING→DEAD_LETTERED; claimable com PROCESSING
└── messaging/      # NotifyHeaders (sem mudança de nomes; javadoc de FAILURE_CLASS atualizado)
```

### Padrão 1: pipeline do `DeliveryProcessor` com classificador

**O que:** um único bloco `try` em volta de tudo que acontece **depois do claim** e **antes do resultado**: busca do template, checagem de `requiredVariables`, renderização e `ChannelGateway.send`. O `catch` captura `Exception` (nunca `Throwable`/`Error`), classifica e escolhe o ramo. Fora do `try` ficam: validação da mensagem (já lança `AmqpRejectAndDontRequeueException`), o claim e os updates de resultado. Uma falha do Mongo no claim ou no update de resultado **sobe** para o container e vira reject → DLQ (rede de segurança da Fase 1); o documento fica onde estava e é recuperável (lease, reprocess da Fase 3).

**Por que assim:** D-14 exige um `AttemptEntry` para todo processamento após o claim, inclusive falha de template antes do adapter (`durationMs` ≈ 0). Um único `catch` garante isso sem duplicar código por tipo de falha.

```java
// Esboço — nomes de classes novas são sugestões
public void process(Channel listenerChannel, NotificationMessage message, int retryCount, boolean redelivered) {
    // validação igual à Fase 1 (lança AmqpRejectAndDontRequeueException)
    Optional<NotificationDocument> claimed = state.claim(id, retryCount, redelivered);
    if (claimed.isEmpty()) { log.info("mensagem duplicada/obsoleta descartada ..."); return; }
    NotificationDocument doc = claimed.get();
    int attemptNumber = doc.attemptCount() + 1;          // D-03: base do fail-until-reprocess
    Instant startedAt = Instant.now();
    long start = System.nanoTime();
    try {
        TemplateDocument template = catalog.require(doc.templateCode());
        RenderedContent rendered = renderer.render(template, doc.channel(), doc.variables(), id);
        gateway.send(doc.channel(), doc.recipient(), rendered, new AttemptContext(retryCount, attemptNumber));
        onSent(doc, retryCount, rendered, attempt(attemptNumber, retryCount, startedAt, start, SUCCESS, null));
    } catch (Exception e) {
        Classification c = classifier.classify(e);
        AttemptEntry attempt = attempt(attemptNumber, retryCount, startedAt, start, c.outcome(), c);
        switch (c.failureClass()) {
            case TRANSIENT -> onTransient(doc, message, retryCount, attempt, c);
            case PERMANENT, UNKNOWN -> onTerminalFailure(doc, message, retryCount, attempt, c);
        }
    }
}
```

### Padrão 2: ordem do retry (D-11) e o fence que **não** pode mudar

D-11 trava a ordem: update PROCESSING→RETRYING + `$push` → publish na fila de delay com confirm → retorno normal. A consequência que o plano precisa fixar:

| Campo no update RETRYING | Valor | Por quê |
|--------------------------|-------|---------|
| `status` | `RETRYING` | — |
| `retryCount` | **mantém `h`** (não grava `h+1`) | O próximo token (header `h+1`) faz o claim e grava `h+1` (o claim da Fase 1 já faz `.set(Fields.RETRY_COUNT, retryCount)`). Se o RETRYING gravasse `h+1` e o worker caísse antes do publish, a reentrega da original (`h`) falharia no filtro `retryCount ≤ h` e seria descartada: notificação RETRYING sem mensagem em lugar nenhum = perda silenciosa |
| `attemptCount` | `$inc 1` | Todo resultado conta (D-14); base do `fail-until-reprocess` |
| `attempts` | `$push` com `$slice: -50` (mesmo molde do `markSent`) | — |
| `lastError` | `$set` (código, mensagem, classe, momento) | GET mostra a causa enquanto RETRYING |
| `leaseUntil` | `$unset` | Não está mais em processamento |

**Janelas de crash com essa regra:**

| Crash entre... | O que o broker faz | O que acontece | Perda? |
|----------------|--------------------|----------------|--------|
| update RETRYING e publish | reentrega a original (`h`, `redelivered=true`) | claim casa (RETRYING é reivindicável, `h ≤ h`) → nova tentativa no nível `h` (uma entrada a mais em `attempts[]`) | Não |
| confirm do publish e ack | reentrega a original (`h`) **e** o token `h+1` já está na fila de delay | a original reprocessa o nível `h`; o token `h+1` chega depois e é cercado (SENT) ou segue a escada | Não; possível tentativa extra (at-least-once, documentar) |

**Decisão pura de retry** (testável sem nada):
```java
// níveis = TopologyProperties.retryLevels(); h = nível em que a tentativa rodou (0 = fila principal)
record RetryDecision(boolean exhausted, int nextRetryCount, Duration nextLevel) {}
static RetryDecision decide(int h, List<Duration> levels) {
    int next = h + 1;
    return next <= levels.size()
            ? new RetryDecision(false, next, levels.get(next - 1))
            : new RetryDecision(true, h, null);
}
```
Com 3 níveis: `h=0,1,2` → publica em `L1,L2,L3` com header `1,2,3`; `h=3` → esgotado → DEAD_LETTERED. Total de 4 tentativas para `always-transient`, como pede o critério 1 e o CONTEXT ("4 tentativas (inicial + 3 níveis)").

**Publicação no nível:** exchange `NotifyTopology.RETRY_EXCHANGE`, routing key `channel.retryKey(level)` — os dois já existem `[VERIFIED: NotifyTopology.java:35 e Channel.java:49-52]`, sem duplicar string:
> `public static final String RETRY_EXCHANGE = "notify.retry";` (NotifyTopology.java:35)
> `public String retryKey(Duration ttl) { return id + ".retry." + TtlLabel.of(ttl); }` (Channel.java:50-52)

**Transição nova na tabela** (D-11): `RETRYING → {PROCESSING, DEAD_LETTERED}`. Hoje a linha é:
> `table.put(RETRYING, EnumSet.of(PROCESSING));` `[VERIFIED: NotificationStatus.java:32]`

Efeito colateral a tratar no plano: `NotificationStatus.sourcesOf(DEAD_LETTERED)` passa a incluir RETRYING, e `NotificationStatusTest` tem asserções `containsExactly...` sobre a tabela que precisam ser atualizadas.

### Padrão 3: chegar à DLQ **com** `x-failure-class` (conflito com a letra do D-10)

**Fato:** em AMQP 0-9-1, `basic.reject`/`basic.nack(requeue=false)` faz o broker dead-letterar a mensagem **como ela está**, acrescentando apenas `x-death`/`x-first-death-*`/`x-last-death-*` `[CITED: rabbitmq.com/docs/dlx]`. O próprio blog da RabbitMQ é explícito: "An AMQP 0.9.1 consumer cannot set custom headers before dead lettering a message", e a solução indicada é o cliente "republish the message with new custom headers to a dead letter queue, then acknowledge the original" `[CITED: rabbitmq.com/blog/2024/10/11/modified-outcome]`. O recurso de anotar ao dead-letterar (outcome `modified`) é só AMQP 1.0 e só em quorum queues — as filas aqui são clássicas e o cliente é 0-9-1.

**Logo, com só `AmqpRejectAndDontRequeueException` (letra do D-10) o critério 2 ("a mensagem chega à DLQ com o header `x-failure-class: PERMANENT`") não é atingível.**

**Recomendação (precisa de confirmação — Pergunta em aberto 1):**
1. Depois do update terminal (FAILED ou DEAD_LETTERED), publicar a `NotificationMessage` em `NotifyTopology.DLX_EXCHANGE` (`"notify.dlx"`) com routing key `channel.dlqKey()` (`id + ".dlq"`), `messageId = notificationId`, headers `x-failure-class=<TRANSIENT|PERMANENT|UNKNOWN>` e `notify-retry-count=h`, com `CorrelationData` e espera do confirm (mesmo molde do retry).
2. Confirm OK → retorno normal (ack da original). A DLQ ganha exatamente uma mensagem, como hoje (o smoke da Fase 1 continua válido: ele só conta +1 na DLQ).
3. Publish falhou (nack/timeout/devolvido/exceção) → `throw new AmqpRejectAndDontRequeueException(...)`: o broker dead-lettera a original pela DLX da fila principal (sem o header, mas com `x-death` reason `rejected`). A mensagem continua na DLQ; só perde o header. É a rede de segurança que o D-10 descreve.

Assim o espírito do D-10 (AUTO, confirm síncrono, reject como caminho para a DLQ) se mantém e o critério 2 passa. A mensagem publicada na DLQ não tem `x-death` (é uma publicação nova); a Fase 3 (reprocess) lê só `messageId` e o corpo, então não depende disso.

Detalhes verificados:
- `NotifyHeaders.FAILURE_CLASS` já existe: `public static final String FAILURE_CLASS = "x-failure-class";` `[VERIFIED: NotifyHeaders.java:10]`. O javadoc ("TRANSIENT/PERMANENT ... Reservado para a Fase 2") deve passar a citar UNKNOWN.
- `DLX_EXCHANGE = "notify.dlx"` `[VERIFIED: NotifyTopology.java:36]`; `dlqKey()` devolve `id + ".dlq"` `[VERIFIED: Channel.java:39-42]`; o binding `dlq → dlx` por `dlqKey` já é declarado `[VERIFIED: NotifyTopology.java:57]`.

### Padrão 4: claim com `redelivered` ou lease (WRK-03, D-09)

**Como ler o flag:** `@Header(name = AmqpHeaders.REDELIVERED, required = false) Boolean redelivered` no `@RabbitListener`. O mapper do Spring AMQP coloca `isRedelivered()` nesse header: `public static final String REDELIVERED = PREFIX + "redelivered";` `[VERIFIED: spring-amqp-3.2.12 AmqpHeaders.java:78]` e `.acceptIfNotNull(AmqpHeaders.REDELIVERED, amqpMessageProperties.isRedelivered(), putObject)` `[VERIFIED: SimpleAmqpHeaderMapper.java:162]`. Use `Boolean` (pode vir nulo) e trate nulo como `false`. Não use `Object` (armadilha já documentada no `ChannelListeners`).

**Filtro novo** (derivado da tabela, nunca escrito à mão; D-09 diz que `claimableForDelivery()` passa a incluir PROCESSING):
```
{ _id: id,
  retryCount: { $lte: h },
  $or: [
    { status: { $in: [PENDING, RETRYING, PUBLISH_FAILED] } },             // claimableForDelivery() sem PROCESSING
    { status: PROCESSING }                                                 // se redelivered == true
    { status: PROCESSING, leaseUntil: { $lt: now } }                       // se redelivered == false
  ] }
```
```java
Criteria fresh = where(Fields.STATUS).in(freshStatuses);                    // claimable sem PROCESSING
Criteria stuck = redelivered
        ? where(Fields.STATUS).is(NotificationStatus.PROCESSING)
        : where(Fields.STATUS).is(NotificationStatus.PROCESSING).and(Fields.LEASE_UNTIL).lt(now);
Query claimable = query(where(Fields.ID).is(id).and(Fields.RETRY_COUNT).lte(retryCount)
        .orOperator(fresh, stuck));
```
O update do claim não muda (status, `retryCount`, `claimedAt`, `leaseUntil`, `updatedAt`). Os testes do `NotificationStateServiceTest` que fazem `containsExactly("_id", "status", "retryCount")` precisam ser reescritos para a forma com `$or`; o `MongoBson` (helper da Fase 1) mostra o BSON real. A forma exata do BSON gerado por `orOperator` encadeado é `[ASSUMED]` até o teste com `MongoBson` rodar.

**Limite conhecido do `redelivered`:** o flag também vem `true` quando o canal de um worker **vivo** cai (blip de conexão) e o broker reentrega. Aí dois workers processam o mesmo nível; os dois updates de resultado usam o mesmo fence (`PROCESSING` + `retryCount = h`), então o primeiro vence e o segundo vê `modifiedCount = 0` (fence perdido) — mas o envio pode ter acontecido duas vezes. É o at-least-once documentado. Endurecimento opcional (Pergunta em aberto 6): usar `claimedAt` devolvido pelo claim como parte do fence dos resultados.

**Lease:** `notify.worker.lease: 5m` `[VERIFIED: notify-worker application.yml:53]`. Ele precisa ser maior que o pior caso de uma tentativa (latência + espera do confirm de retry + confirm da DLQ). 5m sobra; não precisa mudar.

### Padrão 5: `ChannelGateway` — bulkhead → CB → adapter, programático

```java
// Ordem do CONTEXT: bulkhead (externo) → circuit breaker → adapter (interno)
Runnable call = () -> metrics.recordAdapterCall(channel, () -> adapter.send(recipient, content, ctx));
Bulkhead.decorateRunnable(bulkhead, CircuitBreaker.decorateRunnable(circuitBreaker, call)).run();
```
- `static Runnable decorateRunnable(Bulkhead bulkhead, Runnable runnable)` `[VERIFIED: Bulkhead.java:245]` e `static Runnable decorateRunnable(CircuitBreaker circuitBreaker, Runnable runnable)` `[VERIFIED: CircuitBreaker.java:264]`.
- Com o bulkhead **por fora**, o CB nunca vê `BulkheadFullException` (não polui a janela). Com o CB aberto, a chamada pega a permissão do bulkhead por microssegundos e falha com `CallNotPermittedException`. Mesmo assim, coloque `BulkheadFullException` em `ignore-exceptions` como defesa.
- **Resolva as instâncias uma vez, no construtor, com `registry.find(channel.id())`** (`Optional<E> find(String name)` `[VERIFIED: core/Registry.java:47]`) e falhe ao subir se faltar uma. `registry.circuitBreaker(name)` cria silenciosamente uma instância com a config padrão se o nome não estiver no YAML — e o padrão do Resilience4j é `minimumNumberOfCalls=100`, `waitDurationInOpenState=60s` (ver tabela abaixo), que nunca abre na demo e queimaria a escada.
- As instâncias declaradas em `instances:` são criadas na inicialização (`circuitBreakerProperties.getInstances().forEach(... circuitBreakerRegistry.circuitBreaker(name, ...))` `[VERIFIED: resilience4j-spring6 CircuitBreakerConfiguration.java:161-162]`), então as métricas de estado aparecem no `/actuator/prometheus` antes da primeira chamada.
- Sem AOP: os registries são beans do autoconfigure mesmo sem AspectJ; só os aspects dependem dele `[VERIFIED: AbstractCircuitBreakerConfigurationOnMissingBean.java:56-85]`.
- VT: `SemaphoreBulkhead` e `CircuitBreakerStateMachine` usam `ReentrantLock`, e a janela COUNT_BASED padrão (`SYNCHRONIZED`) usa `ReentrantLock` em `FixedSizeSlidingWindowMetrics` — nenhum `synchronized`, então não prende a virtual thread no JDK 21 `[VERIFIED: grep nos sources 2.4.0]`.

**Defaults do Resilience4j que a config precisa sobrescrever** `[VERIFIED: CircuitBreakerConfig.java:43-52; BulkheadConfig.java:33-34]`:
> `DEFAULT_FAILURE_RATE_THRESHOLD = 50`, `DEFAULT_WAIT_DURATION_IN_OPEN_STATE = 60` (segundos), `DEFAULT_PERMITTED_CALLS_IN_HALF_OPEN_STATE = 10`, `DEFAULT_MINIMUM_NUMBER_OF_CALLS = 100`, `DEFAULT_SLIDING_WINDOW_SIZE = 100`, `DEFAULT_SLIDING_WINDOW_TYPE = SlidingWindowType.COUNT_BASED`; bulkhead `DEFAULT_MAX_CONCURRENT_CALLS = 25`, `DEFAULT_MAX_WAIT_DURATION = Duration.ofSeconds(0)`.

**Semântica record/ignore (lida no código):** em `handleThrowable`, o predicado de ignore é testado primeiro; depois o de record; **qualquer exceção que não é ignorada nem registrada é contada como sucesso** (`"recorded an exception as success"`) `[VERIFIED: CircuitBreakerStateMachine.java:205-219]`. Por isso só `record-exceptions` não basta: um bug inesperado dentro do adapter contaria como sucesso. Recomendado:
- `record-exceptions: [TransientDeliveryException]`
- `ignore-exception-predicate: dev.notifyhub.worker.resilience.NonTransientFailurePredicate` (classe pública, construtor sem argumentos, `t -> !(t instanceof TransientDeliveryException)`), que ignora permanentes, `BulkheadFullException` e desconhecidas de uma vez. O campo existe: `private Class<Predicate<Throwable>> ignoreExceptionPredicate;` `[VERIFIED: CommonCircuitBreakerConfigurationProperties.java:364]`.
- Alternativa equivalente e mais legível no YAML: `ignore-exceptions: [PermanentDeliveryException, BulkheadFullException]` aceitando que desconhecidas contem como sucesso. Escolha do planner; a primeira é exaustiva.

**Config sugerida** (nomes de propriedade conferidos nos campos `waitDurationInOpenState`, `slidingWindowType`, `slidingWindowSize`, `minimumNumberOfCalls`, `failureRateThreshold`, `permittedNumberOfCallsInHalfOpenState`, `automaticTransitionFromOpenToHalfOpenEnabled`, `recordExceptions`, `ignoreExceptionPredicate`, `baseConfig`, `maxConcurrentCalls`, `maxWaitDuration` `[VERIFIED: CommonCircuitBreakerConfigurationProperties.java:307-370; CommonBulkheadConfigurationProperties.java:111-115]`):
```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        sliding-window-type: COUNT_BASED
        sliding-window-size: ${NOTIFY_CB_WINDOW_SIZE:20}
        minimum-number-of-calls: ${NOTIFY_CB_MIN_CALLS:10}
        failure-rate-threshold: ${NOTIFY_CB_FAILURE_RATE:50}
        wait-duration-in-open-state: ${NOTIFY_CB_OPEN_WAIT:2500ms}   # ≤ 1º nível (5s); demo: 500ms
        permitted-number-of-calls-in-half-open-state: 3
        automatic-transition-from-open-to-half-open-enabled: true
        record-exceptions:
          - dev.notifyhub.worker.failure.TransientDeliveryException
        ignore-exception-predicate: dev.notifyhub.worker.resilience.NonTransientFailurePredicate
    instances:
      email:   { base-config: default }
      sms:     { base-config: default }
      webhook: { base-config: default }
  bulkhead:
    configs:
      default:
        max-concurrent-calls: ${NOTIFY_BULKHEAD_MAX_CONCURRENT:8}
        max-wait-duration: 0ms
    instances:
      email:   { base-config: default }
      sms:     { base-config: default }
      webhook: { base-config: default }
```
Placeholders com nomes de ENV do projeto (como `VIRTUAL_THREADS`) cumprem o D-08 de forma mais simples que o binding relaxado de ENV para chaves de mapa. Se o planner quiser valores por canal via ENV, use placeholders dentro de `instances.<canal>` em vez de `configs.default`. **Não** ligar `register-health-indicator`: com ele, um CB aberto pode derrubar o `/actuator/health` do worker, e o healthcheck do compose (Fase 4) marcaria o container como unhealthy durante a demo `[ASSUMED]`.

### Padrão 6: dimensionamento de CB e bulkhead (respostas às notas de pesquisa)

**6.1 `CallNotPermitted` consome nível?** Já decidido no D-05: sim. A pesquisa quantifica o custo para o planner calibrar a demo:

- **O "giro" de recusas.** Com `maxWaitDuration=0` (bulkhead) e com o CB aberto, uma chamada recusada não espera: o consumidor grava RETRYING, publica no delay (~ms) e pega a próxima mensagem do prefetch, que também é recusada. Enquanto há backlog, o recusado anda muito mais rápido que o permitido.
- **Simulação local** (modelo, não o worker real; `Semaphore` + virtual threads, 10 consumidores, latência U(50,200)ms, overhead 5ms por mensagem, backlog de 1000) `[VERIFIED: simulação executada nesta sessão; números são de um modelo]`:

  | Permissões do bulkhead | Recusadas na 1ª passagem |
  |------------------------|--------------------------|
  | 8 (de 10) | ~40% (412 e 396 em duas rodadas) |
  | 9 (de 10) | ~17% |
  | 10 (de 10) | 0% |

  Com 40% de recusa por passagem e a recusa consumindo nível, a chance de uma mensagem ser recusada nas 4 passagens (→ DEAD_LETTERED só por bulkhead) é ≈ 0,4⁴ ≈ 2,6% — se o backlog durar a escada inteira. Na demo (1s/5s/25s), 1000 mensagens a ~64 envios/s por canal drenam em ~16s, então o 3º nível (25s) quase sempre encontra o bulkhead livre. **Conclusão:** 8/10 com `maxWaitDuration=0` mostra a recusa com folga e raramente leva a DEAD_LETTERED; se o k6 da Fase 4 mostrar DEAD_LETTERED demais, o ajuste é subir para 9/10, não mexer na regra.

- **CB aberto com backlog:** durante o `wait`, todos os 10 consumidores giram sobre o backlog e jogam tudo no nível seguinte em fração de segundo. É o comportamento esperado do D-05; o efeito é "o backlog inteiro desce um nível por abertura".

**6.2 Tempo aberto vs 1º nível — por que estritamente menor.** As mensagens recusadas durante a abertura voltam do delay ~`L1` depois de recusadas, concentradas logo depois de `t_abertura + L1`. Se `wait = L1`, a volta coincide com o half-open, que só deixa passar **3** chamadas: as demais (≈7 consumidores girando) levam `CallNotPermittedException` de novo e queimam o **segundo** nível. Com `wait ≈ L1/2`, as 3 chamadas de teste terminam (~150–250ms com a latência simulada) e o CB fecha antes de o lote voltar. `wait = L1/2` respeita o "≤ 1º nível" do D-06. Recomendação: **demo 500ms (L1 = 1s); padrão 2500ms (L1 = 5s)** e `automatic-transition-from-open-to-half-open-enabled: true` (sem isso o estado só muda na próxima chamada, e o gauge de estado do CB fica "preso" em OPEN no Grafana). O raciocínio de tempo é `[ASSUMED]` (modelo), e o smoke deve observá-lo.

**Invariante validável:** na inicialização, comparar `waitDurationInOpenState` de cada instância com `TopologyProperties.retryLevels().get(0)` e falhar ao subir se for maior, como o `TopologyProperties` já faz com níveis inválidos. O benchmark da Fase 4 neutraliza o CB por limiar/mínimo de chamadas, sem violar a invariante.

**6.3 Quando o CB abre com falha aleatória** (janela 20, mínimo 10, limiar 50%). A probabilidade de uma janela de 20 chamadas ter ≥ 10 falhas, com taxa `p` `[VERIFIED: binomial calculada nesta sessão]`:

| `failure-rate` | P(janela ≥ 50%) |
|----------------|-----------------|
| 0.2 | 0,26% |
| 0.3 | 4,8% |
| 0.4 | 24,5% |
| 0.5 | 58,8% |

A janela é reavaliada a cada chamada, então em centenas de chamadas o CB abre várias vezes com `p ≈ 0.3–0.4`. Para a demo do critério 4, use rajadas de `always-transient` (abertura certa: 10 chamadas com 100% de falha) e/ou `failure-rate` 0.3–0.4 num profile de demo. Com 0.2 o CB quase nunca abre.

**6.4 `x-death` no RabbitMQ 4.3.** O projeto já não lê `x-death` (contador próprio `notify-retry-count`). O que importa verificar no smoke: (a) a mensagem que expira da fila de delay volta à fila principal com `notify-retry-count` preservado (os headers são mantidos no dead-letter; só exchange/routing key mudam `[CITED: rabbitmq.com/docs/dlx]`); (b) na rede de segurança (reject), a mensagem chega à DLQ com `x-death` `reason=rejected` e sem `x-failure-class`. Cada republicação do worker usa `MessageProperties` novas, então `x-death` antigo nunca é copiado (o 4.x não interpreta `x-death` publicado por cliente, segundo as notas do 4.0.1). A detecção de ciclo não se aplica: o salto principal → delay é uma publicação, não um dead-letter, e o único dead-letter "de volta" (delay → principal) não volta à mesma fila sem passar por publicação `[CITED: rabbitmq.com/docs/dlx — "detect a cycle and drop the message if there was no rejection in the entire cycle"]`. Docker estava desligado nesta sessão, então (a) e (b) não foram observados no 4.3.6 — ficam para o smoke `[ASSUMED]`.

### Padrão 7: classificador exaustivo (DLV-03, D-13, D-14)

Ordem importa (subclasses antes das superclasses). Percorrer a cadeia de `getCause()` só para exceções do Mongo.

| # | Exceção | Classe | Código | Observação |
|---|---------|--------|--------|------------|
| 1 | `io.github.resilience4j.circuitbreaker.CallNotPermittedException` | TRANSIENT | `CIRCUIT_OPEN` | `extends RuntimeException` `[VERIFIED: CallNotPermittedException.java:25]` |
| 2 | `io.github.resilience4j.bulkhead.BulkheadFullException` | TRANSIENT | `BULKHEAD_FULL` | `extends RuntimeException` `[VERIFIED: BulkheadFullException.java:24]` |
| 3 | `TransientDeliveryException` (inclui `SimulatedDeliveryException` re-parentada) | TRANSIENT | `e.errorCode()` (`SIMULATED_TRANSIENT`) | Hoje `SimulatedDeliveryException extends RuntimeException` `[VERIFIED: SimulatedDeliveryException.java:4]` |
| 4 | `PermanentDeliveryException` | PERMANENT | `e.errorCode()` (`SIMULATED_PERMANENT`, `MISSING_VARIABLE`, `INVALID_WEBHOOK_PAYLOAD`) | — |
| 5 | `TemplateNotFoundException` (template ou variante) | PERMANENT | `TEMPLATE_NOT_FOUND` | Pode virar subclasse de `PermanentDeliveryException`; tem `missing(...)` e `missingVariant(...)` `[VERIFIED: TemplateNotFoundException.java:17-25]` |
| 6 | `com.samskivert.mustache.MustacheException.Context` | PERMANENT | `MISSING_VARIABLE` | Probe: variável ausente **e** valor nulo lançam `Context` (com `key` e `lineNo`) `[VERIFIED: probe com jmustache-1.16.jar]` |
| 7 | `com.samskivert.mustache.MustacheException` (demais, ex.: `MustacheParseException`) | PERMANENT | `TEMPLATE_INVALID` (novo código) | Probe: `{{#a}}` sem fechar → `MustacheParseException` |
| 8 | `DataAccessResourceFailureException`, `TransientDataAccessException`, ou causa `com.mongodb.MongoTimeoutException`/`MongoSocketException`/`MongoExecutionTimeoutException` | TRANSIENT | `STORE_UNAVAILABLE` (novo código) | O tradutor do Spring Data mapeia `"MongoSocketException", "MongoServerSelectionException", "MongoTimeoutException"` (e outros) para `DataAccessResourceFailureException` `[VERIFIED: MongoExceptionTranslator.java:60-62, 4.5.13]`. Só se aplica à busca do template (dentro do `try`) |
| 9 | qualquer outra `Exception` | UNKNOWN | `UNEXPECTED` | D-13: FAILED + DLQ, sem gastar retries |

`InterruptedException` embrulhada pelo adapter (hoje vira `SimulatedDeliveryException`) continua TRANSIENT; restaure o flag de interrupção. Mensagens de erro gravadas: curtas, em pt-BR, sem destinatário nem variáveis (PII), truncadas (ex.: 300 caracteres).

**Enums compartilhados hoje** (o que muda):
> `public enum FailureClass { TRANSIENT, PERMANENT }` `[VERIFIED: FailureClass.java:4-7]` → acrescentar `UNKNOWN` (D-13).
> `public enum AttemptOutcome { SUCCESS, TRANSIENT_FAILURE, PERMANENT_FAILURE }` `[VERIFIED: AttemptOutcome.java:7-11]` → para UNKNOWN, ou acrescentar `UNKNOWN_FAILURE` ou gravar `PERMANENT_FAILURE` com `failureClass=UNKNOWN` (Pergunta em aberto 4).

A API não tem `switch` sobre esses enums (grep nesta sessão), então acrescentar valores não quebra compilação; `NotificationView` só repassa.

### Padrão 8: destinatários mágicos (DLV-02, D-01..D-04)

Parser puro `MagicRecipients.parse(Channel, String) → Optional<MagicBehavior>`, testado sem Spring:

| Canal | Forma | Comportamento |
|-------|-------|---------------|
| EMAIL | `<regra>@fail.test` (domínio exato, sem diferenciar maiúsculas) | `always-transient`, `always-permanent`, `fail-<N>-then-ok` (N ≥ 1), `fail-until-reprocess` |
| WEBHOOK | `http(s)://fail.test/<regra>` (host exato) | mesmas regras no primeiro segmento do path |
| SMS | `+550000000` + 3 dígitos | `001` sempre transitória; `002` sempre permanente; `1N3` falha N vezes (N = 1..9, ex.: `+550000000123` = falha 2); `004` até reprocessar |

**Cabe em `RecipientRules`:** `+550000000001` tem 13 caracteres e 12 dígitos depois do `+`, dentro de `SMS_MAX_LENGTH = 16` e do padrão `"^\\+[1-9]\\d{7,14}$"` `[VERIFIED: RecipientRules.java:18 e :22]`. O domínio `.test` é reservado para testes e nunca resolve na internet `[CITED: RFC 2606 §2]`. Os números `+55 00…` não correspondem a DDD brasileiro válido `[ASSUMED]`.

**Regras (D-02/D-03):**
- `always-transient` → `TransientDeliveryException(SIMULATED_TRANSIENT)` sempre.
- `always-permanent` → `PermanentDeliveryException(SIMULATED_PERMANENT)` sempre (é o "destinatário inválido" simulado).
- `fail-N-then-ok` → falha (transitória) enquanto `ctx.retryCount() < N`.
- `fail-until-reprocess` → falha (transitória) enquanto `ctx.attemptNumber() ≤ 4`, onde `attemptNumber = doc.attemptCount() + 1` (número da tentativa corrente, 1-based). Como **todo** resultado incrementa `attemptCount` (D-14), rejeições de CB/bulkhead também contam; documentar.
- A falha mágica é lançada **depois** da latência simulada e **dentro** do adapter (D-04), para passar pelo CB e pelo bulkhead.
- Recomendado: `notify.simulation.magic-recipients: true` (padrão) para poder desligar fora da demo (ver Segurança).

**Assinatura nova do adapter:** hoje `void send(String recipient, RenderedContent content);` `[VERIFIED: ChannelAdapter.java:20]` → `void send(String recipient, RenderedContent content, AttemptContext context)` com `record AttemptContext(int retryCount, int attemptNumber)`. Testes que mocam `ChannelAdapter` (`DeliveryProcessorTest`, `DeliveryFlowTest`, `SimulatedChannelAdapterTest`) mudam junto.

### Padrão 9: renderizador estrito com escape por canal (TPL-02/03, D-16)

Três compiladores imutáveis (compilados no construtor, como hoje):

| Uso | Compilador | Verificado |
|-----|-----------|------------|
| Corpo do EMAIL | `Mustache.compiler()` (escaper padrão `Escapers.HTML`: `& ' " < > \` =`) | `[VERIFIED: Escapers.java:13-21]`; probe: `<a href='x'>&"=\`` → `&lt;a href&#x3D;&#39;x&#39;&gt;&amp;&quot;&#x3D;&#x60;` |
| Assunto do EMAIL e SMS | `Mustache.compiler().escapeHTML(false)` | igual à Fase 1 |
| Corpo do WEBHOOK | `Mustache.compiler().withEscaper(JSON).strictSections(true)` | `withEscaper(Escaper)` existe `[VERIFIED: Mustache.java:170]`; atenção: `escapeHTML(...)` **sobrescreve** qualquer escaper (`Note: this overrides any escaper set via {@link #withEscaper}`) `[VERIFIED: Mustache.java:94-98]` — nunca encadear `escapeHTML` depois de `withEscaper` |

**Escaper JSON sem código novo:** `raw -> new String(JsonStringEncoder.getInstance().quoteAsString(raw))` (jackson-core). Probe com `Ana "Bia" \ back<LF>line<TAB>tab<U+0001> </script>`: saída `Ana \"Bia\" \\ back\nline\ttab\u0001 </script>`, e o corpo inteiro passa no `ObjectMapper` `[VERIFIED: probe com jackson-core-2.21.4 + jmustache-1.16]`. Valores não-string (ex.: `42`) viram texto e ficam entre as aspas do template.

**Validação depois de renderizar (webhook):**
```java
private static final ObjectReader STRICT_JSON =
        new ObjectMapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
JsonNode node = STRICT_JSON.readTree(body);           // lança em JSON quebrado ou lixo no fim
if (node == null || !node.isObject()) throw invalidWebhookPayload();
```
Probe `[VERIFIED: jackson-databind 2.21.4]`: `readTree("{\"a\":1} lixo")` padrão devolve `{"a":1}` **sem erro**; com `FAIL_ON_TRAILING_TOKENS` lança `JsonParseException`. String vazia/só espaços devolve `MissingNode` nos dois modos — por isso o `isObject()`. Use o `ObjectMapper` do Boot (injetado) ou um local; o teste unitário passa `new ObjectMapper()`.

**Variável obrigatória:** antes de renderizar, para cada nome em `template.requiredVariables()`, exigir chave presente e valor não nulo em `variables` → senão `PermanentDeliveryException(MISSING_VARIABLE)` com o **nome** da variável na mensagem (sem o valor). O JMustache cobre o resto (variável usada no template mas fora de `requiredVariables`). `_notificationId` continua injetado pelo worker e nunca está em `requiredVariables`.

**Triple mustache:** `{{{x}}}` e `{{& x}}` pulam o escaper; o probe mostrou que `{{{name}}}` no webhook gera JSON inválido. Os seeds atuais não usam; um teste sobre os seeds (`SeedTemplatesTest` já existe) pode proibir `{{{`/`{{&` nas variantes WEBHOOK e EMAIL.

**Testes TST-01** (todos sem Docker, ampliando `TemplateRendererTest`): e-mail escapa HTML; assunto e SMS sem escape; webhook com aspas, `\`, quebra de linha e controle continua JSON válido e o valor volta igual depois do parse; variável ausente → MISSING_VARIABLE; valor nulo → MISSING_VARIABLE; `requiredVariables` sem a chave → MISSING_VARIABLE mesmo que o template não a use; template inexistente/variante ausente → TEMPLATE_NOT_FOUND; corpo de webhook inválido (template de teste com JSON quebrado) → INVALID_WEBHOOK_PAYLOAD.

### Padrão 10: métricas pré-registradas (OBS-01, D-15)

- **Mesmo conjunto de chaves de tag para o mesmo nome.** O registry Prometheus do Micrometer não aceita meters de mesmo nome com conjuntos de chaves diferentes; os seguintes são descartados (ou geram erro), e a documentação sugere preencher com `"none"` `[CITED: docs.micrometer.io/micrometer/reference/implementations/prometheus]`. Se isso continua igual no registry novo (`io.micrometer.prometheusmetrics`, cliente 1.x) é `[ASSUMED]`; seguir a regra é seguro nos dois. Então `sent` leva `failure_class="none"`.
- **Combinações válidas** de `notify.delivery.outcomes` por canal: `sent/none`, `retrying/transient`, `dead_lettered/transient`, `failed/permanent`, `failed/unknown` → 5 × 3 canais = 15 séries. Valores de tag em minúsculo.
- `notify.delivery.retries{channel, level}`: `level` de `1` a `retryLevels().size()` (3) → 9 séries. Incrementar **depois** do confirm do publish no delay.
- Timer `notify.delivery.duration{channel, outcome}`: medir **só a chamada ao adapter** (dentro do CB), para que recusas de CB/bulkhead (~0ms) não distorçam o p95. `outcome` ∈ `success|transient_failure|permanent_failure|unknown_failure` (Pergunta em aberto 3) → 12 séries. `Timer.builder(...).publishPercentileHistogram().minimumExpectedValue(Duration.ofMillis(1)).maximumExpectedValue(Duration.ofSeconds(30))` limita o número de buckets.
- **Quando incrementar:** depois que o update condicional retorna `true` (fence ganho) e, nos ramos com publish, depois do confirm. Fence perdido = nada conta.
- Nomes Prometheus resultantes: `notify_delivery_outcomes_total`, `notify_delivery_retries_total`, `notify_delivery_duration_seconds_bucket|_count|_sum|_max` `[ASSUMED: convenção padrão do PrometheusNamingConvention; conferir no smoke]`.
- **Resilience4j (automático):** `resilience4j.circuitbreaker.state` (tag `state`), `.calls` (tag `kind` = `successful|failed|ignored|not_permitted`), `.not.permitted.calls`, `.failure.rate`, `.buffered.calls` e `resilience4j.bulkhead.available.concurrent.calls`, `.max.allowed.concurrent.calls`; tag `name` = instância `[VERIFIED: CircuitBreakerMetricNames.java:9-19; BulkheadMetricNames.java:11-14; AbstractCircuitBreakerMetrics.java:30-34; TagNames.java:23-24]`. Ligados por padrão (`resilience4j.circuitbreaker.metrics.enabled` com `matchIfMissing = true`) e só com `MeterRegistry` presente `[VERIFIED: CircuitBreakerMetricsAutoConfiguration.java:36-54]`.
- **Lacuna:** o Resilience4j **não** publica contador de chamadas recusadas pelo bulkhead (só `available`/`max`). Para provar "o bulkhead recusa o excesso" no Prometheus há duas saídas: o código `BULKHEAD_FULL` nas tentativas (Mongo/log) ou um contador extra `notify.delivery.rejections{channel, reason=circuit_open|bulkhead_full}`, aditivo ao D-15 (Pergunta em aberto 2).
- **Actuator:** `management.endpoints.web.exposure.include: health,info,prometheus` no worker.

### Anti-padrões a evitar

- **Incrementar `retryCount` no update RETRYING:** perda silenciosa na janela de crash do D-11 (Padrão 2).
- **Contar com `AmqpRejectAndDontRequeueException` para levar `x-failure-class`:** o broker não acrescenta header de cliente (Padrão 3).
- **`registry.circuitBreaker(name)` com nome fora do YAML:** cria instância com defaults (100 chamadas, 60s) sem aviso.
- **CB sem `ignore` para não-transitórias:** desconhecidas contam como sucesso; permanentes abrem o CB se entrarem em `record`.
- **Timer em volta do bulkhead/CB:** recusas de ~0ms puxam o p95 para baixo.
- **Catch de `Throwable`:** engole `Error` (OOM) e o transforma em FAILED.
- **Ler o `x-death` para qualquer decisão:** o contador é o `notify-retry-count`.
- **Reaproveitar o `notificationId` como id do `CorrelationData`:** com returns ligados o id **precisa ser único** por publicação pendente no canal ("Must be unique if returns are enabled") `[VERIFIED: CorrelationData.java:57-61]`; um retry e um DLQ do mesmo id concorrentes (ou duas reentregas) colidiriam no mapa `pendingReturns` `[VERIFIED: PublisherCallbackChannelImpl.java:1111-1113]`. Use `new CorrelationData()` (UUID) `[VERIFIED: CorrelationData.java:53-55]`.

## Não construa à mão

| Problema | Não construa | Use | Por quê |
|----------|--------------|-----|---------|
| Escape de string JSON | loop de `char` com `\uXXXX` | `JsonStringEncoder.getInstance().quoteAsString(raw)` como `Mustache.Escaper` | Já trata aspas, `\`, controle e substitutos; testado pelo Jackson |
| Validação do JSON renderizado | regex/contagem de chaves | `ObjectReader` com `FAIL_ON_TRAILING_TOKENS` + `isObject()` | `readTree` padrão aceita lixo no fim |
| Escape HTML | `replace` próprio | `Escapers.HTML` (padrão do JMustache) | Cobre `& ' " < > \` =` |
| Circuit breaker / bulkhead | contadores e semáforos próprios | `CircuitBreakerRegistry`/`BulkheadRegistry` do `resilience4j-spring-boot3` | Janela deslizante, half-open, métricas e config por YAML prontos |
| Composição de decorators | `Decorators` (exige `resilience4j-all`) ou try/finally manual de permissões | `Bulkhead.decorateRunnable(bh, CircuitBreaker.decorateRunnable(cb, r))` | Libera permissão e registra resultado corretamente em exceção |
| Espera do confirm | `ConfirmCallback` global no template | `CorrelationData.getFuture().get(timeout)` + `getReturned()` (molde do `NotificationPublisher` da API) | Callback global é um só por template; o futuro é por publicação; a devolução é garantida antes do ack `[CITED: docs.spring.io/spring-amqp/reference/amqp/template.html]` |
| Delay do retry | `Thread.sleep`/agendador | Filas TTL da Fase 1 | Visível no painel, sobrevive a crash |
| Métricas | mapas de `AtomicLong` | Micrometer `Counter`/`Timer` registrados uma vez e guardados em `EnumMap` | Exposição Prometheus e histograma prontos |

**Ideia-chave:** quase tudo nesta fase é composição de peças que já existem (topologia, confirm, claim condicional, Resilience4j, JMustache). O código novo de verdade é o classificador, o parser mágico e os updates condicionais — e os três são funções pequenas testáveis sem Docker.

## Estado em tempo de execução

Fase sem renomeação, mas com mudança de comportamento sobre dados já existentes:

| Categoria | Itens encontrados | Ação |
|-----------|-------------------|------|
| Dados guardados | Documentos deixados em PROCESSING pelo smoke da Fase 1 (template inexistente → DLQ). A mensagem deles já está na DLQ, então o claim novo não os toca. Valores novos de enum (`UNKNOWN`) só aparecem em documentos novos | Nenhuma migração. Fase 3 (reprocess) trata os antigos |
| Config de serviço vivo | Nenhuma fila, exchange ou argumento novo: as filas de delay e DLQ já existem desde a Fase 1 (verificado em `NotifyTopology`) | Nenhuma. **Não** é preciso `docker compose down -v` |
| Estado registrado no SO | Nenhum | — |
| Segredos/ENV | ENV novos opcionais (`NOTIFY_CB_*`, `NOTIFY_BULKHEAD_*`); `VIRTUAL_THREADS` inalterado | Documentar no `application.yml` |
| Artefatos de build | Jar do worker precisa ser reconstruído (dependências novas) | `./mvnw -pl notify-worker -am package` antes do smoke |

## Armadilhas comuns

### Armadilha 1: header `x-failure-class` esperado de um reject
**O que dá errado:** a DLQ recebe a mensagem, mas sem o header; o critério 2 falha.
**Por quê:** AMQP 0-9-1 não permite alterar a mensagem ao rejeitar.
**Como evitar:** Padrão 3 (publicar em `notify.dlx` com confirm; reject só como fallback).
**Sinal:** `POST /api/queues/%2F/notify.email.dlq/get` mostra `x-death` e nenhum `x-failure-class`.

### Armadilha 2: `retryCount` incrementado no RETRYING
**O que dá errado:** crash entre update e publish deixa RETRYING sem mensagem.
**Como evitar:** manter `retryCount = h` no RETRYING; o claim do próximo token grava `h+1`. Teste unitário com `MongoBson` que fixa o `$set` do `markRetrying` sem `retryCount`.

### Armadilha 3: tempo aberto do CB igual ao 1º nível
**O que dá errado:** o lote recusado volta durante o half-open e queima o 2º nível.
**Como evitar:** `wait ≈ L1/2` e transição automática; validar `wait ≤ L1` ao subir.

### Armadilha 4: "giro" de recusas do bulkhead com `maxWaitDuration=0`
**O que dá errado:** sob backlog, ~40% das mensagens vão para o delay já na primeira passagem (8/10); o k6 da Fase 4 mostra muitos RETRYING e alguns DEAD_LETTERED.
**Como evitar:** é o comportamento pedido (D-07); documentar o número e, se necessário, usar 9/10. Nunca resolver com requeue ou espera fora do bulkhead.

### Armadilha 5: CB contando o que não deve
**O que dá errado:** permanentes abrem o CB (se em `record`), ou bugs contam como sucesso (se nem `record` nem `ignore`).
**Como evitar:** `record=Transient`, `ignore-exception-predicate` = "não é transitória". Teste com `CircuitBreaker.of("t", config)`: 10 permanentes não abrem; 10 transitórias abrem.

### Armadilha 6: `readTree` aceita JSON com lixo no fim
**O que dá errado:** `{"a":1}{"b":2}` passa como válido e sai um webhook corrompido.
**Como evitar:** `FAIL_ON_TRAILING_TOKENS` + `isObject()` (probe nesta sessão).

### Armadilha 7: `escapeHTML(false)` depois de `withEscaper(JSON)`
**O que dá errado:** o escaper JSON é substituído por `NONE` sem aviso.
**Como evitar:** compilador do webhook só com `withEscaper`; teste com aspas no valor.

### Armadilha 8: id de `CorrelationData` repetido
**O que dá errado:** devolução (`mandatory`) atribuída à publicação errada; publicação devolvida parece confirmada.
**Como evitar:** `new CorrelationData()` em todo publish do worker; checar `getReturned()` antes de `isAck()` (molde da API).

### Armadilha 9: confirm bloqueando o listener sem limite
**O que dá errado:** `future.get()` sem timeout prende o consumidor para sempre se o broker não responder.
**Como evitar:** timeout configurável (`notify.worker.confirm-timeout: 5s`), tratado como falha de publish. Bloquear o listener é seguro: desde o Spring AMQP 2.2 os callbacks de confirm rodam numa thread do executor da connection factory, não na thread do listener nem no I/O do cliente `[CITED: docs.spring.io/spring-amqp/reference/amqp/template.html]`.

### Armadilha 10: cache de canais pequeno no worker
**O que dá errado:** 30 consumidores + publicações com confirm concorrentes abrem e fecham canais o tempo todo.
**Como evitar:** `spring.rabbitmq.cache.channel.size: 50` no worker, como na API `[ASSUMED: impacto não medido]`.

### Armadilha 11: testes da Fase 1 que fixam o formato antigo
**O que muda:** `NotificationStateServiceTest` (filtro do claim com 3 chaves), `NotificationStatusTest` (tabela e `claimableForDelivery`), `DeliveryProcessorTest`/`DeliveryFlowTest` (assinatura do adapter e "erros sobem"), `ChannelListenersDispatchTest` (novo header), `TemplateRendererTest` (construtor do renderer, se ganhar `ObjectMapper`). O plano deve prever a atualização junto com cada mudança, não numa tarefa no fim.

### Armadilha 12: DEAD_LETTERED fora da DLQ na pior janela
**O que dá errado:** o publish de retry falha **e** o canal morre antes do reject; a original volta (`redelivered`), o claim não casa (DEAD_LETTERED não é reivindicável) e é confirmada: documento DEAD_LETTERED visível, mas sem mensagem na DLQ para a Fase 3.
**Como evitar:** aceitar e documentar (não é silencioso: status + `lastError` REPUBLISH_FAILED), ou, quando o claim não casar **e** `redelivered=true` **e** o status for FAILED/DEAD_LETTERED, rejeitar para a DLQ em vez de confirmar. Decisão do planner; a segunda opção custa um `findById`.

## Exemplos de código

### Publicador do worker com confirm (molde da API, id único)
```java
// Baseado em notify-api NotificationPublisher (lido nesta sessão) + CorrelationData() com UUID
public PublishOutcome publish(String exchange, String routingKey, NotificationMessage msg,
                              Map<String, Object> headers, Duration timeout) {
    CorrelationData cd = new CorrelationData();                    // UUID: único por publicação
    try {
        rabbit.convertAndSend(exchange, routingKey, msg, m -> {
            m.getMessageProperties().setMessageId(msg.notificationId());
            headers.forEach(m.getMessageProperties()::setHeader);   // propriedades novas: nada de x-death
            return m;                                               // PERSISTENT é o padrão
        }, cd);
        CorrelationData.Confirm confirm = cd.getFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (cd.getReturned() != null) return PublishOutcome.returned(cd.getReturned().getReplyText());
        return confirm.isAck() ? PublishOutcome.acked() : PublishOutcome.nacked(confirm.getReason());
    } catch (TimeoutException e) {
        return PublishOutcome.timeout();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return PublishOutcome.timeout();
    } catch (ExecutionException | AmqpException e) {
        return PublishOutcome.failed(e.getMessage());
    }
}
// retry:  publish(NotifyTopology.RETRY_EXCHANGE, ch.retryKey(level), msg, Map.of(NotifyHeaders.RETRY_COUNT, h + 1), t)
// DLQ:    publish(NotifyTopology.DLX_EXCHANGE, ch.dlqKey(), msg,
//                 Map.of(NotifyHeaders.FAILURE_CLASS, cls.name(), NotifyHeaders.RETRY_COUNT, h), t)
```
`CorrelationData.getFuture()` e `getReturned()` existem na 3.2.12 `[VERIFIED: CorrelationData.java:93 e :104]`.

### Ramo transitório (ordem do D-11)
```java
private void onTransient(NotificationDocument doc, NotificationMessage msg, int h, AttemptEntry attempt,
                         Classification c) {
    RetryDecision d = RetryPolicy.decide(h, topology.retryLevels());
    if (d.exhausted()) {                                            // DLV-05
        if (state.markDeadLettered(doc.id(), h, attempt, c)) {       // PROCESSING(h) → DEAD_LETTERED
            metrics.outcome(doc.channel(), Outcome.DEAD_LETTERED, FailureClass.TRANSIENT);
            dlq.send(doc.channel(), msg, FailureClass.TRANSIENT, h); // publish confirmado ou reject
        }
        return;
    }
    if (!state.markRetrying(doc.id(), h, attempt, c)) return;        // fence perdido: outro worker cuida
    PublishOutcome p = publisher.publishToRetry(doc.channel(), msg, d.nextRetryCount(), d.nextLevel());
    if (p.ok()) {
        metrics.outcome(doc.channel(), Outcome.RETRYING, FailureClass.TRANSIENT);
        metrics.retry(doc.channel(), d.nextRetryCount());
        return;                                                      // ack
    }
    // D-11: RETRYING(h) → DEAD_LETTERED com "republicação para retry.<nível> falhou"
    if (state.markDeadLetteredAfterRepublishFailure(doc.id(), h, TtlLabel.of(d.nextLevel()), p)) {
        metrics.outcome(doc.channel(), Outcome.DEAD_LETTERED, FailureClass.TRANSIENT);
    }
    dlq.send(doc.channel(), msg, FailureClass.TRANSIENT, h);
}
```

### Update condicional de resultado (molde do `markSent`)
```java
// fence igual ao markSent da Fase 1: status PROCESSING e retryCount = h exatos
Query fence = query(where(Fields.ID).is(id)
        .and(Fields.STATUS).is(NotificationStatus.PROCESSING)
        .and(Fields.RETRY_COUNT).is(retryCount));
Update retrying = new Update()
        .set(Fields.STATUS, NotificationStatus.RETRYING)            // retryCount NÃO muda (Padrão 2)
        .set(Fields.LAST_ERROR, new LastError(c.code(), c.message(), FailureClass.TRANSIENT, now))
        .set(Fields.UPDATED_AT, now)
        .unset(Fields.LEASE_UNTIL)
        .inc(Fields.ATTEMPT_COUNT, 1);
retrying.push(Fields.ATTEMPTS).slice(-50).each(attempt);           // $slice exige $each (nota da Fase 1)
boolean won = mongo.updateFirst(fence, retrying, NotificationDocument.class).getModifiedCount() == 1;
```
Campos e fence existentes `[VERIFIED: NotificationStateService.java:87-99; NotificationDocument.java:68-80]`. `LastError(code, message, failureClass, at)` `[VERIFIED: LastError.java:14]`.

### Métricas com pré-registro
```java
public DeliveryMetrics(MeterRegistry registry, TopologyProperties topology) {
    for (Channel ch : Channel.values()) {
        register(registry, ch, "sent", "none");
        register(registry, ch, "retrying", "transient");
        register(registry, ch, "dead_lettered", "transient");
        register(registry, ch, "failed", "permanent");
        register(registry, ch, "failed", "unknown");
        for (int level = 1; level <= topology.retryLevels().size(); level++) {
            Counter.builder("notify.delivery.retries").tag("channel", ch.id())
                    .tag("level", Integer.toString(level)).register(registry);
        }
        for (String outcome : List.of("success", "transient_failure", "permanent_failure", "unknown_failure")) {
            Timer.builder("notify.delivery.duration").tag("channel", ch.id()).tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .minimumExpectedValue(Duration.ofMillis(1)).maximumExpectedValue(Duration.ofSeconds(30))
                    .register(registry);
        }
    }
}
```
Testar com `SimpleMeterRegistry`: depois de construir, `registry.find("notify.delivery.outcomes").counters()` tem 15 séries com valor 0.

### Teste da config do Resilience4j sem Docker
```java
new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CircuitBreakerAutoConfiguration.class, BulkheadAutoConfiguration.class))
        .withPropertyValues(/* carregar o application.yml ou repetir as chaves */)
        .run(ctx -> {
            CircuitBreakerConfig cfg = ctx.getBean(CircuitBreakerRegistry.class)
                    .find("email").orElseThrow().getCircuitBreakerConfig();
            assertThat(cfg.getMinimumNumberOfCalls()).isEqualTo(10);
            assertThat(cfg.getWaitIntervalFunctionInOpenState().apply(1)).isLessThanOrEqualTo(firstLevelMs);
        });
```
Os nomes das classes de autoconfig existem em `io.github.resilience4j.springboot3.{circuitbreaker,bulkhead}.autoconfigure` `[VERIFIED: listagem dos sources 2.4.0]`. O getter exato da espera no estado aberto (`getWaitIntervalFunctionInOpenState`) é `[ASSUMED]`; conferir ao escrever o teste.

### Desligar o `MustacheAutoConfiguration` (deferred da Fase 1)
```yaml
spring:
  autoconfigure:
    exclude: org.springframework.boot.autoconfigure.mustache.MustacheAutoConfiguration
```
A classe existe no `spring-boot-autoconfigure-3.5.16.jar` `[VERIFIED: listagem do jar]`. Trivial e no mesmo arquivo que a fase já altera; recomendado incluir.

## Estado da arte

| Abordagem antiga | Abordagem atual | Quando mudou | Impacto |
|------------------|-----------------|--------------|---------|
| Contar retries pelo `x-death` | Header da aplicação (`notify-retry-count`) | RabbitMQ 3.13/4.0.1 deixou de interpretar `x-death` publicado por cliente | Já adotado na Fase 1 |
| Anotar mensagem ao dead-letterar | Só AMQP 1.0 + quorum queue (`modified` outcome); em 0-9-1, republicar | RabbitMQ 4.0 (2024) | Padrão 3 |
| `micrometer-registry-prometheus` com simpleclient 0.x | Cliente Prometheus 1.x (`io.micrometer.prometheusmetrics`) | Micrometer 1.13 | Boot 3.5 já usa o novo; nada a fazer |
| `Decorators` "sempre disponível" | Só no `resilience4j-all` | — | Usar os `decorate*` estáticos |
| Resilience4j 2.x para Boot 3 | 2.4.0 também traz `-spring-boot4`; 3.x exige Java 21 | 2026-03 | Ficar no `-spring-boot3` 2.4.0 |

## Registro de suposições

| # | Suposição | Seção | Risco se errada |
|---|-----------|-------|-----------------|
| A1 | Números do "giro" do bulkhead (40% com 8/10) vêm de um modelo com `Thread.sleep` no Windows, não do worker real | Padrão 6.1 | Demo com mais ou menos recusas; ajustar 8→9 |
| A2 | Com `wait = L1`, o lote recusado volta no half-open e queima o 2º nível; `L1/2` evita | Padrão 6.2 | Se errado, `L1/2` só abre o CB um pouco mais cedo; sem dano |
| A3 | `x-death` e preservação de `notify-retry-count` no 4.3.6 não foram observados (Docker desligado) | Padrão 6.4 | Baixo: a lógica não depende de `x-death`; o smoke confirma |
| A4 | O registry Prometheus novo (1.x) mantém a regra "mesmo nome = mesmas chaves de tag" | Padrão 10 | Nenhum se o plano seguir a regra |
| A5 | Nomes Prometheus `_total`/`_seconds_bucket` | Padrão 10 | Queries da Fase 4 com nome errado; o smoke confere |
| A6 | Health indicator do CB derrubaria `/actuator/health` | Padrão 5 | Só se alguém ligar `register-health-indicator` |
| A7 | Forma exata do BSON de `orOperator` encadeado | Padrão 4 | Teste do filtro precisa ajuste; o `MongoBson` mostra |
| A8 | `+55 00…` não é número real | Padrão 8 | Baixo; nenhum SMS sai da máquina |
| A9 | Cache de 50 canais no worker evita churn | Armadilha 10 | Só desempenho |
| A10 | Getter `getWaitIntervalFunctionInOpenState` no `CircuitBreakerConfig` | Exemplos | Compilação do teste; trocar pelo getter real |

## Perguntas em aberto

1. **DLV-06 vs letra do D-10 (precisa do usuário).**
   - Sabido: reject em AMQP 0-9-1 não carrega header (fonte primária da RabbitMQ). Sem publicar em `notify.dlx`, o critério 2 não passa.
   - Recomendação: publicar a cópia com `x-failure-class` e confirm; reject só como fallback (Padrão 3). O planner deve registrar isso como ajuste do D-10 e pedir confirmação antes de executar.
2. **Contador de recusas do bulkhead.** O Resilience4j não publica. Recomendação: `notify.delivery.rejections{channel, reason}` (aditivo, não muda os nomes travados do D-15); alternativa é provar só pelos códigos no Mongo.
3. **Valores da tag `outcome` do timer.** D-15 não fecha. Recomendação: `success|transient_failure|permanent_failure|unknown_failure`, medindo só a chamada ao adapter.
4. **`AttemptOutcome` para UNKNOWN.** Recomendação: acrescentar `UNKNOWN_FAILURE` (aditivo, simétrico à classe); alternativa: `PERMANENT_FAILURE` + `failureClass=UNKNOWN`.
5. **REPUBLISH_FAILED gera `AttemptEntry`?** A tentativa transitória já foi registrada no `markRetrying`. Recomendação: REPUBLISH_FAILED só em `lastError` (sem entrada nova), para `attempts[]` contar só tentativas de entrega.
6. **Endurecer o fence com `claimedAt`.** D-09 mantém o fence por `retryCount`. Opcional: incluir `claimedAt` devolvido pelo claim no filtro dos resultados, para que dois workers no mesmo nível (reentrega por blip) não gravem os dois. O critério 3 passa sem isso.
7. **Destinatário redelivered de documento terminal (Armadilha 12).** Confirmar (documento) ou rejeitar para a DLQ. Recomendação: documentar e não tratar nesta fase.

## Disponibilidade do ambiente

| Dependência | Usada por | Disponível | Versão | Alternativa |
|-------------|-----------|------------|--------|-------------|
| JDK | build e testes | ✓ | Oracle 21.0.10 | — |
| Maven Wrapper | build | ✓ | Maven 3.9.16 | — |
| Docker CLI | smoke (RabbitMQ/Mongo) | ✓ | 29.8.0 | — |
| Docker daemon | smoke | ✗ **no momento da pesquisa** (pipe `dockerDesktopLinuxEngine` ausente) | — | Abrir o Docker Desktop antes do smoke; testes unitários não precisam |
| curl | smoke (API, management HTTP, actuator) | ✓ | mingw64 | — |
| taskkill | smoke do WRK-03 (kill abrupto) | ✓ | Windows | — |
| Maven Central (rede) | dependências novas | ✓ | — | — |

**Bloqueia sem alternativa:** nada para o código; o smoke da fase exige o Docker Desktop ligado.

**Smoke da fase (sugestão para `scripts/smoke-fase2.sh`, reaproveitando funções do `smoke-fase1.sh`):** subir com profile `demo`; (1) `always-transient@fail.test` → poll até DEAD_LETTERED, 4 tentativas com `retryLevel` 0..3 e código `SIMULATED_TRANSIENT`; (2) `always-permanent`, template inexistente e variável faltando → FAILED com 1 tentativa e, via `POST /api/queues/%2F/notify.<canal>.dlq/get` (`ackmode: ack_requeue_true`), header `x-failure-class` presente; (3) `fail-2-then-ok` → SENT com 3 tentativas; (4) WRK-03: worker com `--notify.simulation.webhook.latency-min=8s --notify.simulation.webhook.latency-max=8s`, POST webhook, esperar PROCESSING, `taskkill //F` no java, subir o worker com latência normal, esperar SENT com exatamente 1 tentativa SUCCESS; (5) rajada de 12 `https://fail.test/always-transient` → `resilience4j_circuitbreaker_not_permitted_calls_total{name="webhook"}` > 0 e uma notificação normal com tentativa `CIRCUIT_OPEN` que termina SENT; (6) bulkhead: worker com `NOTIFY_BULKHEAD_MAX_CONCURRENT=2` e latência ~1s, 10 POSTs → alguma tentativa `BULKHEAD_FULL`, todas terminam SENT; (7) `/actuator/prometheus` contém as 15 séries de `notify_delivery_outcomes_total`; (8) rodar `smoke-fase1.sh` como regressão (a contagem da DLQ continua +1).

## Domínio de segurança

`security_enforcement: true`, ASVS nível 1.

### Categorias ASVS aplicáveis

| Categoria | Aplica | Controle |
|-----------|--------|----------|
| V2 Autenticação | não | Sem endpoints novos com auth nesta fase |
| V3 Sessão | não | — |
| V4 Controle de acesso | parcial | `/actuator/prometheus` do worker sem auth: aceitável para scrape interno; manter a porta 8081 só na rede local/compose (Fase 4) |
| V5 Validação/encoding de saída | **sim** | Escape por canal: HTML no e-mail (`Escapers.HTML`), JSON no webhook (`JsonStringEncoder` + parse estrito); templates só do seed, valores do cliente só como dado (T-01-10 da Fase 1); proibir `{{{`/`{{&` nos seeds |
| V6 Criptografia | não | — |
| V7 Erros e logs | **sim** | Mensagens de erro curtas e sem PII no Mongo/log/headers; destinatário sempre por `RecipientRules.mask`; nada de `notificationId`, destinatário ou variáveis como tag de métrica |
| V8 Proteção de dados | sim | A mensagem AMQP continua fina (só id/canal/template); a cópia na DLQ não leva variáveis nem destinatário; `x-failure-class` e `notify-retry-count` não são PII |
| V11 Lógica de negócio | sim | Destinatários mágicos são um recurso de demo: flag `notify.simulation.magic-recipients` para desligar fora da demo |

### Ameaças conhecidas para esta stack

| Padrão | STRIDE | Mitigação |
|--------|--------|-----------|
| Injeção de JSON no webhook via variável com aspas | Tampering | Escaper JSON + parse estrito; falha permanente `INVALID_WEBHOOK_PAYLOAD` em vez de sair corrompido |
| Injeção de HTML no e-mail | Tampering | Escape HTML padrão no corpo; assunto em texto puro |
| SSTI (template enviado pelo cliente) | Elevation | Só templates do seed são compilados (inalterado) |
| Retry storm / amplificação (bulkhead + CB giram o backlog) | DoS | Escada finita (3 níveis), sem requeue, DLQ terminal; números documentados (Padrão 6) |
| Mensagem venenosa em loop | DoS | `default-requeue-rejected: false` + publicação explícita na DLQ; fallback reject sem requeue |
| Vazamento de PII por exceção (`e.getMessage()` com destinatário) | Information disclosure | Mensagens próprias em pt-BR, truncadas; exceções do adapter nunca incluem destinatário/conteúdo |
| Cardinalidade de métricas (DoS no Prometheus) | DoS | Tags fechadas e pré-registradas; códigos de erro fora das tags |

## Fontes

### Primárias (ALTA confiança)
- Código do repositório lido nesta sessão: `notify-common` (`FailureClass`, `AttemptOutcome`, `NotificationStatus`, `NotifyHeaders`, `NotifyTopology`, `Channel`, `RecipientRules`, `TopologyProperties`, `AttemptEntry`, `LastError`, `NotificationDocument`), `notify-worker` (todas as classes de `main` + `application*.yml` + testes `MongoBson`, `TemplateRendererTest`, `DeliveryProcessorTest`, `DeliveryFlowTest`, `WorkerSettingsTest`), `notify-api` (`NotificationPublisher`, `PublishResult`, `NotificationView`), POMs, `docker-compose.yml`, `scripts/smoke-fase1.sh`
- Sources do Maven Central baixados e lidos: `resilience4j-{spring-boot3,spring6,micrometer,circuitbreaker,bulkhead,core,framework-common}-2.4.0-sources.jar`; POMs 2.4.0 (escopos); `spring-amqp`/`spring-rabbit` 3.2.12 sources (`AmqpHeaders`, `SimpleAmqpHeaderMapper`, `CorrelationData`, `PublisherCallbackChannelImpl`); `spring-data-mongodb-4.5.13` (`MongoExceptionTranslator`); `jmustache-1.16-sources.jar` (`Mustache`, `Escapers`)
- Probes executados com os jars reais: JMustache + `JsonStringEncoder` (escape, triple mustache, variável ausente/nula, seção estrita, parse error, escape HTML), Jackson `readTree` com e sem `FAIL_ON_TRAILING_TOKENS`, simulação de bulkhead
- `spring-boot-dependencies-3.5.16.pom` (Jackson 2.21.4, Micrometer 1.15.12, JMustache 1.16) e `spring-boot-autoconfigure-3.5.16.jar` (`MustacheAutoConfiguration`)

### Secundárias (MÉDIA confiança)
- [RabbitMQ — Dead Letter Exchanges](https://www.rabbitmq.com/docs/dlx) — headers adicionados no dead-letter, troca de routing key, detecção de ciclo, dead-letter sem confirms internos
- [RabbitMQ blog — AMQP 1.0 Modified Outcome](https://www.rabbitmq.com/blog/2024/10/11/modified-outcome) — "An AMQP 0.9.1 consumer cannot set custom headers before dead lettering a message"; só quorum queues
- [RabbitMQ 4.0.1 release notes](https://github.com/rabbitmq/rabbitmq-server/releases/tag/v4.0.1) — `x-death` publicado por cliente não é interpretado
- [Spring AMQP — RabbitTemplate / publisher confirms](https://docs.spring.io/spring-amqp/reference/amqp/template.html) — callbacks no executor (desde 2.2), devolução antes do ack, `usePublisherConnection`
- [Micrometer — Prometheus](https://docs.micrometer.io/micrometer/reference/implementations/prometheus) — mesmo nome exige mesmas chaves de tag (doc do registry legado)
- [Micrometer 1.13 Migration Guide](https://github.com/micrometer-metrics/micrometer/wiki/1.13-Migration-Guide) — cliente Prometheus 1.x
- RFC 2606 §2 — TLD `.test` reservado

### Terciárias (BAIXA confiança)
- Raciocínio de tempo do half-open e simulação do bulkhead (modelos desta sessão), marcados `[ASSUMED]`/"modelo"

## Metadados

**Detalhamento da confiança:**
- Stack: ALTA — versões e escopos lidos no Maven Central; nada novo além do Resilience4j e do registry Prometheus
- Arquitetura: ALTA — desenho sobre o código existente; ordem e fence verificados contra o filtro real do claim
- Dimensionamento CB/bulkhead: MÉDIA — semântica lida no código, números de modelo
- Armadilhas: ALTA — as principais (header no reject, `readTree`, `Decorators`, record/ignore, `CorrelationData`) foram verificadas em fonte primária ou probe

**Data da pesquisa:** 2026-10-14
**Válida até:** 2026-11-13 (stack congelada no Boot 3.5.16; Resilience4j 2.4.0 sem release nova desde março)
