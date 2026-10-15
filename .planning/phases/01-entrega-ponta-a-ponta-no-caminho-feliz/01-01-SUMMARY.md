---
phase: 01-entrega-ponta-a-ponta-no-caminho-feliz
plan: 01
subsystem: infra
tags: [maven, maven-wrapper, spring-boot-3.5.16, java-21, docker-compose, rabbitmq-4.3, mongo-8.0, contract, junit5, assertj]

requires: []
provides:
  - "Build Maven multi-módulo (dev.notifyhub:notify-hub-parent:0.1.0-SNAPSHOT) com wrapper 3.9.16"
  - "Jars executáveis do Boot para notify-api e notify-worker; notify-common como biblioteca"
  - "docker-compose.yml com rabbitmq:4.3.6-management e mongo:8.0.32 (healthchecks, volumes nomeados, loopback)"
  - "Contrato de domínio: Channel (conversões D-05), NotificationStatus (tabela de transições, sourcesOf, claimableForDelivery), AttemptOutcome, FailureClass"
  - "Contrato de mensagem: NotificationMessage (4 campos), NotifyHeaders, TtlLabel"
  - "Persistência compartilhada: Collections, RenderedContent"
  - "RecipientRules: isValid/describe/mask por canal"
  - "notify-common 0.1.0-SNAPSHOT instalado em ~/.m2 para os planos da wave 2"
affects: [01-02, 01-03, 01-04, 01-05, 01-06]

actuals:
  tokens: 12936
  tasks: 3
  commits: 5
plan_head_before: e071d3a59505e6a09150c277d9de92618e58b68a

tech-stack:
  added: [spring-boot-starter-parent 3.5.16, maven-wrapper 3.3.4 (Maven 3.9.16), spring-rabbit (managed), spring-data-mongodb (managed), jmustache (managed), rabbitmq:4.3.6-management, mongo:8.0.32]
  patterns:
    - "Enum Channel como único ponto de conversão maiúsculo↔minúsculo para filas e routing keys"
    - "Filtros de status derivados da tabela ALLOWED (nunca escritos à mão)"
    - "Nome de fila de retry com rótulo do TTL (TtlLabel), nunca ordinal"
    - "Mensagem AMQP fina (token) sem PII; documento no Mongo é a fonte da verdade"
    - "Records e enums, sem Lombok; testes JUnit 5 + AssertJ"

key-files:
  created:
    - pom.xml
    - notify-common/pom.xml
    - notify-api/pom.xml
    - notify-worker/pom.xml
    - mvnw
    - mvnw.cmd
    - .mvn/wrapper/maven-wrapper.properties
    - .gitignore
    - .gitattributes
    - docker-compose.yml
    - notify-api/src/main/java/dev/notifyhub/api/NotifyApiApplication.java
    - notify-worker/src/main/java/dev/notifyhub/worker/NotifyWorkerApplication.java
    - notify-common/src/main/java/dev/notifyhub/common/domain/Channel.java
    - notify-common/src/main/java/dev/notifyhub/common/domain/NotificationStatus.java
    - notify-common/src/main/java/dev/notifyhub/common/domain/AttemptOutcome.java
    - notify-common/src/main/java/dev/notifyhub/common/domain/FailureClass.java
    - notify-common/src/main/java/dev/notifyhub/common/domain/RecipientRules.java
    - notify-common/src/main/java/dev/notifyhub/common/messaging/TtlLabel.java
    - notify-common/src/main/java/dev/notifyhub/common/messaging/NotificationMessage.java
    - notify-common/src/main/java/dev/notifyhub/common/messaging/NotifyHeaders.java
    - notify-common/src/main/java/dev/notifyhub/common/persistence/Collections.java
    - notify-common/src/main/java/dev/notifyhub/common/persistence/RenderedContent.java
    - notify-common/src/test/java/dev/notifyhub/common/domain/ChannelTest.java
    - notify-common/src/test/java/dev/notifyhub/common/domain/NotificationStatusTest.java
    - notify-common/src/test/java/dev/notifyhub/common/domain/RecipientRulesTest.java
    - notify-common/src/test/java/dev/notifyhub/common/messaging/TtlLabelTest.java
    - notify-common/src/test/java/dev/notifyhub/common/messaging/NotificationMessageJsonTest.java
  modified: []

key-decisions:
  - "groupId e pacote base dev.notifyhub; artifactId do pai notify-hub-parent; versão 0.1.0-SNAPSHOT"
  - "Tabela de transições guardada em EnumMap imutável; sourcesOf/claimableForDelivery devolvem conjuntos imutáveis"
  - "RecipientRules confere tamanho antes da regex também para SMS (máx. 16 = '+' + 15 dígitos E.164)"
  - "mask(WEBHOOK) mantém só esquema e host (sem porta, caminho ou query)"

patterns-established:
  - "Channel.retryQueue/retryKey(Duration) via TtlLabel.of: nomes de fila de delay derivados do TTL"
  - "NotificationStatus.claimableForDelivery() é o filtro do claim do worker na Fase 1"
  - "RecipientRules.mask(channel, recipient) é a única forma de logar destinatário"

requirements-completed: [FND-01, FND-02, FND-03]

coverage:
  - id: D1
    description: "Build multi-módulo pelo wrapper com Java 21 e Boot 3.5.16; api/worker como jars executáveis e common como biblioteca"
    requirement: FND-01
    verification:
      - kind: other
        ref: "./mvnw -q install (exit 0)"
        status: pass
      - kind: other
        ref: "jar tf notify-api/target/notify-api-0.1.0-SNAPSHOT.jar | grep BOOT-INF/classes/dev/notifyhub/api/NotifyApiApplication.class; jar tf notify-common jar | grep -c BOOT-INF == 0"
        status: pass
    human_judgment: false
  - id: D2
    description: "Contrato de domínio em notify-common (Channel D-05, NotificationStatus com tabela de transições, TtlLabel)"
    requirement: FND-02
    verification:
      - kind: unit
        ref: "notify-common/src/test/java/dev/notifyhub/common/domain/ChannelTest.java"
        status: pass
      - kind: unit
        ref: "notify-common/src/test/java/dev/notifyhub/common/domain/NotificationStatusTest.java"
        status: pass
      - kind: unit
        ref: "notify-common/src/test/java/dev/notifyhub/common/messaging/TtlLabelTest.java"
        status: pass
    human_judgment: false
  - id: D3
    description: "Contrato de mensagem fina (4 campos, sem PII) e regras de destinatário por canal com mascaramento"
    requirement: FND-02
    verification:
      - kind: unit
        ref: "notify-common/src/test/java/dev/notifyhub/common/messaging/NotificationMessageJsonTest.java"
        status: pass
      - kind: unit
        ref: "notify-common/src/test/java/dev/notifyhub/common/domain/RecipientRulesTest.java"
        status: pass
    human_judgment: false
  - id: D4
    description: "Compose com rabbitmq:4.3.6-management e mongo:8.0.32 saudáveis, painel aceitando notify/notify"
    requirement: FND-03
    verification:
      - kind: other
        ref: "docker compose config --quiet (exit 0)"
        status: pass
      - kind: manual_procedural
        ref: "docker compose up -d --wait && docker compose ps; login http://localhost:15672 notify/notify (aprovado pelo usuário no checkpoint do tracer)"
        status: pass
    human_judgment: true
    rationale: "A saúde real dos containers depende do Docker Desktop do usuário; verificada por humano no checkpoint do tracer e de novo no smoke do plano 01-06"

duration: 37min
completed: 2026-10-12
status: complete
---

# Phase 1 Plan 01: Esqueleto de build e contrato compartilhado Summary

**Maven multi-módulo (Boot 3.5.16, Java 21, wrapper 3.9.16), compose com RabbitMQ 4.3.6 e Mongo 8.0.32, e notify-common com Channel, máquina de estados, mensagem AMQP de 4 campos e RecipientRules, coberto por 45 testes unitários**

## Performance

- **Duration:** 37 min (inclui a espera no checkpoint do tracer)
- **Started:** 2026-10-12T18:04:25Z
- **Completed:** 2026-10-12T18:41:08Z
- **Tasks:** 3
- **Files modified:** 27

## Accomplishments
- `./mvnw -q install` compila e instala os três módulos sem `mvn` no PATH. API e worker saem como jars executáveis do Boot; o common sai como jar simples, sem `BOOT-INF`.
- `docker-compose.yml` com RabbitMQ 4.3.6 (hostname fixo `notify-rabbit`) e Mongo 8.0.32, healthchecks, volumes nomeados e portas só em 127.0.0.1. Saúde confirmada pelo usuário no checkpoint do tracer.
- `Channel` concentra as conversões de nome (D-05). `NotificationStatus` tem a tabela de transições completa (incluindo reclaim e reprocessamento) e deriva `sourcesOf` e `claimableForDelivery` = {PENDING, RETRYING, PUBLISH_FAILED}.
- `NotificationMessage` serializa exatamente notificationId, channel, templateCode e createdAt. Um teste falha se aparecer um campo novo (mitigação T-01-04).
- `RecipientRules` aceita os destinatários mágicos da Fase 2, confere o tamanho antes da regex (T-01-05) e oferece `mask` para logs.

## Task Commits

1. **Task 1: Esqueleto de build e infra (tracer)** - `e1ca112` (feat)
2. **Task 2: Contrato de domínio** - `8d77044` (test, RED) e `4a7759e` (feat, GREEN)
3. **Task 3: Contrato de mensagem e RecipientRules** - `8a9b2cf` (test, RED) e `9d0fd39` (feat, GREEN)

**Plan metadata:** commit do SUMMARY (docs)

## Files Created/Modified
- `pom.xml`: POM pai (starter-parent 3.5.16, java 21, módulos, dependencyManagement do common)
- `notify-common/pom.xml`: biblioteca sem plugin de repackage
- `notify-api/pom.xml`, `notify-worker/pom.xml`: starters + notify-common + spring-boot-maven-plugin (worker com jmustache)
- `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`: wrapper 3.3.4 → Maven 3.9.16 (only-script)
- `.gitattributes`, `.gitignore`: LF para mvnw/*.sh, CRLF para *.cmd; ignora target/ e arquivos de IDE
- `docker-compose.yml`: infra da Fase 1
- `NotifyApiApplication`, `NotifyWorkerApplication`: `@SpringBootApplication` sem `@Import`
- `common/domain`: Channel, NotificationStatus, AttemptOutcome, FailureClass, RecipientRules
- `common/messaging`: TtlLabel, NotificationMessage, NotifyHeaders
- `common/persistence`: Collections, RenderedContent
- 5 classes de teste (45 testes no total)

## Decisions Made
- `NotificationStatus` guarda a tabela num `EnumMap` imutável. Um teste confere que todos os status têm entrada e que os conjuntos devolvidos são imutáveis.
- `RecipientRules` também limita SMS a 16 caracteres antes da regex (o máximo E.164 é '+' e 15 dígitos), na mesma linha da mitigação T-01-05.
- `mask(WEBHOOK)` devolve `esquema://host/***`. Porta, caminho e query são descartados.

## Deviations from Plan

None - plan executed exactly as written.

Nota de processo TDD: nas Tasks 2 e 3 o RED foi falha de compilação por falta das classes (`cannot find symbol`), exatamente como o plano pede ("devem falhar por falta das classes"). Não houve falha de asserção antes do GREEN. Commits `test(01-01)` precedem os `feat(01-01)` nas duas tarefas.

## Issues Encountered
- O guard do worktree bloqueia comandos compostos de git e caminhos calculados. A verificação foi refeita com comandos simples, sem efeito no resultado.
- `jar` não está no PATH padrão. A checagem de layout dos jars usou `export PATH="/c/Program Files/Java/jdk-21.0.10/bin:$PATH"`.
- O tracer parou no gate de feedback (end-of-phase + `<human-check>`) porque o daemon do Docker estava desligado. O usuário aprovou depois de confirmar os containers saudáveis e o login no painel.
- O REQUIREMENTS.md não foi alterado neste worktree. FND-02 também é declarado pelo plano 01-02, e o orquestrador marca os requisitos depois do merge da wave.

## User Setup Required

None - no external service configuration required (o Docker Desktop precisa estar ligado para subir o compose).

## Next Phase Readiness
- `notify-common` 0.1.0-SNAPSHOT está em `~/.m2`. Os planos da wave 2 podem compilar com `-pl <módulo>` sem `-am`.
- O pacote `dev.notifyhub.common` ainda não tem `NotifyCommonConfiguration`, `TopologyProperties`, `NotifyTopology` nem documentos Mongo. Eles chegam no plano 01-02.

---
*Phase: 01-entrega-ponta-a-ponta-no-caminho-feliz*
*Completed: 2026-10-12*

## Self-Check: PASSED
- Todos os 27 arquivos de key-files.created existem no disco
- Commits e1ca112, 8d77044, 4a7759e, 8a9b2cf e 9d0fd39 estão no histórico da branch
- `./mvnw -q install` verde; 45 testes do notify-common passam; `docker compose config --quiet` verde
