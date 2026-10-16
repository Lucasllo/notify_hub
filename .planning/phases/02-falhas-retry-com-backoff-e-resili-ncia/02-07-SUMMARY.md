---
phase: 02-falhas-retry-com-backoff-e-resili-ncia
plan: 07
subsystem: testing
tags: [smoke, rabbitmq, mongodb, dlq, retry, circuit-breaker, bulkhead, prometheus, bash, windows]

requires:
  - phase: 02-falhas-retry-com-backoff-e-resili-ncia
    provides: "02-01..02-06: FAILED/DEAD_LETTERED com cópia x-failure-class na DLQ, escada TTL+DLX, retomada por redelivered, renderer estrito, destinatários mágicos, CB/bulkhead por canal e métricas D-15"
  - phase: 01-entrega-ponta-a-ponta-no-caminho-feliz
    provides: "scripts/smoke-fase1.sh (helpers e estrutura), docker-compose com rabbitmq:4.3.6-management e mongo:8.0.32"
provides:
  - "scripts/smoke-lib.sh: helpers de smoke compartilhados (HTTP, RabbitMQ, Mongo, DLQ com x-failure-class, métricas Prometheus, iniciar_worker/iniciar_api no profile demo, parar_processo, matar_abruptamente)"
  - "scripts/smoke-fase2.sh: prova ponta a ponta dos 5 critérios da Fase 2 contra broker e banco reais, linha final SMOKE FASE 2: OK"
  - "Evidência de runtime das suposições A3 (notify-retry-count preservado no dead-letter do 4.3.6: cópia na DLQ com notify-retry-count 3) e A5 (nomes Prometheus notify_delivery_* e resilience4j_*)"
affects: [fase-3-reprocessamento, fase-4-demo, readme]

actuals:
  tokens: 7760
  tasks: 2
  commits: 2
plan_head_before: 5f83b0552c520f981616b8d9b3e2a579021c10a3

tech-stack:
  added: []
  patterns:
    - "Smoke em bash sem jq: campos JSON por grep -o/sed/tr; DLQ lida pela API de gerenciamento com ack_requeue_true, uma mensagem por linha cortando em \"payload_bytes\""
    - "Kill abrupto no Windows com taskkill //F //T no pid de /proc/<pid>/winpid (shutdown gracioso confirmaria a mensagem em voo)"
    - "Worker considerado pronto só depois do health UP e da linha 'Listeners iniciados' (listeners sobem depois do runner de índices e seed)"

key-files:
  created:
    - scripts/smoke-lib.sh
    - scripts/smoke-fase2.sh
  modified: []

key-decisions:
  - "id_da_resposta lê \"id\" do corpo do 202 (CreateNotificationResponse), com fallback para \"notificationId\" (503) e para o header Location; o plano citava notificationId, que o 202 não tem"
  - "Contagem de SIMULATED_TRANSIENT feita em \"errorCode\":\"SIMULATED_TRANSIENT\" (só as tentativas): o lastError repete o código em \"code\" e daria 5 em vez de 4"
  - "dlq_tem_header pede max(200, mensagens da fila + 100): como os volumes não são apagados, a DLQ acumula rodadas e um count fixo de 200 deixaria o id novo de fora depois de algumas execuções"
  - "smoke-fase2.sh não roda docker compose down (como o da Fase 1); a infra do smoke foi derrubada com docker compose down (volumes preservados) só no fim desta execução"

patterns-established:
  - "Novo smoke: source scripts/smoke-lib.sh depois do set -euo pipefail e do cd para a raiz; trap encerrar com parar_processo; SMOKE FASE N: OK como última linha"
  - "Cenários de resiliência checam o resultado observável (tentativas no GET + contador de recusas), não o instante exato em que o CB abre"

requirements-completed: [WRK-03, TPL-02, TPL-03, DLV-02, DLV-03, DLV-04, DLV-05, DLV-06, RES-01, RES-02, OBS-01]

coverage:
  - id: D1
    description: "always-transient@fail.test (EMAIL, profile demo) fica RETRYING, deixa mensagem em notify.email.retry.25s e termina DEAD_LETTERED em até 60s com 4 tentativas (níveis 0..3, SIMULATED_TRANSIENT, TRANSIENT); notify.email.dlq tem a cópia com x-failure-class TRANSIENT"
    requirement: DLV-05
    verification:
      - kind: e2e
        ref: "bash scripts/smoke-fase2.sh --sem-build (passo 3, critério 1)"
        status: pass
    human_judgment: false
  - id: D2
    description: "always-permanent, templateCode inexistente e SMS order-shipped sem orderId terminam FAILED com 1 tentativa no nível 0 (SIMULATED_PERMANENT, TEMPLATE_NOT_FOUND, MISSING_VARIABLE) e cópia na DLQ do canal com x-failure-class PERMANENT"
    requirement: DLV-06
    verification:
      - kind: e2e
        ref: "bash scripts/smoke-fase2.sh --sem-build (passo 4, critério 2)"
        status: pass
    human_judgment: false
  - id: D3
    description: "WEBHOOK password-reset com aspas e barra invertida no name chega a SENT (JSON válido em runtime)"
    requirement: TPL-03
    verification:
      - kind: e2e
        ref: "bash scripts/smoke-fase2.sh --sem-build (passo 5, critério 5)"
        status: pass
    human_judgment: false
  - id: D4
    description: "fail-2-then-ok@fail.test termina SENT com 3 tentativas, 2 SIMULATED_TRANSIENT e a última SUCCESS no retryLevel 2"
    requirement: DLV-02
    verification:
      - kind: e2e
        ref: "bash scripts/smoke-fase2.sh --sem-build (passo 7, critério 3a)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Worker com webhook de 8s derrubado com taskkill //F enquanto a notificação está PROCESSING; o novo worker registra 'reentrega retomada id=<id>', a notificação chega a SENT com 1 tentativa SUCCESS e os dois logs somam exatamente uma linha 'Notificação entregue id=<id>'"
    requirement: WRK-03
    verification:
      - kind: e2e
        ref: "bash scripts/smoke-fase2.sh --sem-build (passo 8, critério 3b)"
        status: pass
    human_judgment: false
  - id: D6
    description: "Rajada de 20 WEBHOOK https://fail.test/always-transient: notify_delivery_rejections_total{channel=\"webhook\",reason=\"circuit_open\"} > 0 (16.0 na leitura), 29 tentativas CIRCUIT_OPEN, todas DEAD_LETTERED, nenhuma FAILED; 5 webhooks comuns depois chegam a SENT"
    requirement: RES-01
    verification:
      - kind: e2e
        ref: "bash scripts/smoke-fase2.sh --sem-build (passo 9, critério 4a)"
        status: pass
    human_judgment: false
  - id: D7
    description: "Worker com NOTIFY_BULKHEAD_MAX_CONCURRENT=2 e SMS de 1s: 6 SMS simultâneos geram 4 tentativas BULKHEAD_FULL, rejections{sms,bulkhead_full} = 4.0 e todos terminam SENT, nenhum FAILED"
    requirement: RES-02
    verification:
      - kind: e2e
        ref: "bash scripts/smoke-fase2.sh --sem-build (passo 10, critério 4b)"
        status: pass
    human_judgment: false
  - id: D8
    description: "http://localhost:8081/actuator/prometheus tem as 15 séries de notify_delivery_outcomes_total, notify_delivery_retries_total com level 1/2/3, notify_delivery_duration_seconds_bucket, notify_delivery_rejections_total, resilience4j_circuitbreaker_state e resilience4j_bulkhead_available_concurrent_calls; logs dos workers sem Consumer failed to start, PRECONDITION_FAILED e Cannot find template location"
    requirement: OBS-01
    verification:
      - kind: e2e
        ref: "bash scripts/smoke-fase2.sh --sem-build (passos 11 e 12)"
        status: pass
    human_judgment: false
  - id: D9
    description: "Regressão da Fase 1: smoke-fase1.sh --sem-build e --sem-build --perfil-demo terminam com SMOKE FASE 1: OK; o template inexistente continua +1 na DLQ e agora o documento termina FAILED"
    verification:
      - kind: e2e
        ref: "bash scripts/smoke-fase1.sh --sem-build && bash scripts/smoke-fase1.sh --sem-build --perfil-demo"
        status: pass
      - kind: other
        ref: "./mvnw -q verify"
        status: pass
    human_judgment: false
  - id: D10
    description: "Passagem visível pelas filas de delay no painel do RabbitMQ (notify.email.retry.1s/.5s/.25s sobem e descem em sequência) e a mensagem final em notify.email.dlq com o header x-failure-class na aba Get messages"
    requirement: DLV-04
    verification:
      - kind: e2e
        ref: "bash scripts/smoke-fase2.sh --sem-build (retry.25s com mensagem vista pela API de gerenciamento; x-failure-class conferido via /api/queues/.../get)"
        status: pass
    human_judgment: true
    rationale: "O critério 1 do ROADMAP pede a passagem visível pelas filas de delay; o painel em http://localhost:15672 é a evidência que a demo mostra e só uma pessoa avalia se ela está clara (human-check da Tarefa 2)"
  - id: D11
    description: "GET /notifications/<id> de uma always-transient mostra 4 tentativas legíveis (nível, código, classe) sem abrir o Mongo, e o scrape do worker mostra notify_delivery_* por canal e o estado do CB de forma autoexplicativa"
    requirement: DLV-03
    verification:
      - kind: e2e
        ref: "bash scripts/smoke-fase2.sh --sem-build (conteúdo do GET e do scrape conferido por grep)"
        status: pass
    human_judgment: true
    rationale: "Legibilidade para quem avalia o portfólio é julgamento humano (human-check da Tarefa 2, CONTEXT ideias específicas)"

duration: 15min
completed: 2026-10-16
status: complete
---

# Fase 2 Plano 07: Smoke de runtime da Fase 2 - Resumo

**`scripts/smoke-fase2.sh` sobe RabbitMQ 4.3.6 e MongoDB 8.0.32 reais, roda API e worker no profile demo e prova os cinco critérios da Fase 2: escada até DEAD_LETTERED com x-failure-class TRANSIENT, permanentes direto para FAILED com x-failure-class PERMANENT, fail-2-then-ok até SENT, worker derrubado com taskkill //F antes do ack sem envio duplicado, CB e bulkhead recusando sem nenhum FAILED, e métricas D-15/Resilience4j no /actuator/prometheus. Os helpers ficam em `scripts/smoke-lib.sh`.**

## Desempenho

- **Duração:** ~15 min
- **Início:** 2026-10-15T23:57:21Z
- **Fim:** 2026-10-16T00:12:30Z
- **Tarefas:** 2
- **Arquivos:** 2 criados

## Entregas

- `smoke-lib.sh` reúne os helpers do smoke da Fase 1 (copiados sem mudar o comportamento) e acrescenta `esperar_status` com timeout, `matar_abruptamente`, `iniciar_worker`/`iniciar_api` no profile demo, `id_da_resposta`, `contar`, `dlq_tem_header`, `metrica`, `valor_metrica`, `maior_que_zero` e `esperar_porta_livre`.
- Critério 1: `always-transient@fail.test` mostrou RETRYING, deixou mensagem em `notify.email.retry.25s` e terminou DEAD_LETTERED com 4 tentativas (níveis 0..3). A cópia na DLQ tem `x-failure-class: TRANSIENT`.
- Critério 2: always-permanent, template inexistente e variável obrigatória faltando terminam FAILED com 1 tentativa e cópia `x-failure-class: PERMANENT` na DLQ do canal.
- Critério 3: `fail-2-then-ok` chega a SENT na 3ª tentativa (nível 2). Com o worker derrubado à força durante a latência de 8s, a mensagem voltou com redelivered, o novo worker registrou `reentrega retomada` e entregou uma única vez.
- Critério 4: a rajada de 20 always-transient abriu o CB do webhook (16 recusas `circuit_open` na primeira leitura positiva, 29 tentativas CIRCUIT_OPEN no total). As 20 terminaram DEAD_LETTERED e nenhuma FAILED. Com bulkhead 2, os 6 SMS tiveram 4 recusas `BULKHEAD_FULL` e todos chegaram a SENT. O scrape tem todas as séries exigidas.
- Critério 5: webhook com aspas e barra invertida no `name` chega a SENT.
- Regressão: `smoke-fase1.sh --sem-build` e `--perfil-demo` terminam com `SMOKE FASE 1: OK`. A notificação de template inexistente agora termina FAILED; antes ficava presa em PROCESSING, a lacuna conhecida da Fase 1.
- Suposições da pesquisa vistas em runtime. A3: a cópia de always-transient na DLQ traz `notify-retry-count: 3`, então o header sobreviveu aos três dead-letters das filas de delay do 4.3.6. A5: os nomes Prometheus batem com o que o 02-06 previu.

## Commits das tarefas

1. **Tarefa 1 (tracer): escada até DEAD_LETTERED e permanentes na DLQ com x-failure-class**: `c6f6443` (feat)
2. **Tarefa 2: recuperação, kill antes do ack, CB, bulkhead, métricas e regressão da Fase 1**: `5c2e4a5` (feat)

**Metadados do plano:** commit final de docs (este SUMMARY + STATE/ROADMAP/REQUIREMENTS)

Gate do tracer (Tarefa 1): modo interativo, `human_verify_mode` end-of-phase, `<verify>` só automatizado. O verify foi rodado de novo depois do commit (`./mvnw -q verify && bash scripts/smoke-fase2.sh --sem-build`, saída 0, `SMOKE FASE 2: OK`) antes da expansão.

## Arquivos criados/modificados

- `scripts/smoke-lib.sh` (100755): helpers compartilhados, sem `set -e` próprio, LF, comentários pt-BR
- `scripts/smoke-fase2.sh` (100755): smoke da Fase 2 com `--sem-build`, logs em `target/smoke/fase2-worker-{1..5}.log` e `fase2-api.log`

## Decisões

- O id vem do campo `"id"` do 202. O plano falava em `notificationId`, que só existe no 503. Fica `notificationId` e depois `Location` como fallback.
- A contagem de `SIMULATED_TRANSIENT` usa `"errorCode":"SIMULATED_TRANSIENT"`, para não contar o `lastError.code`.
- `dlq_tem_header` lê a fila inteira (mínimo de 200 mensagens), porque a DLQ acumula rodadas sem apagar volumes.
- O log do worker sai em UTF-8 no Windows (conferido com `od`). Por isso o grep literal de `Notificação entregue id=` funciona.

## Desvios do plano

### Correções automáticas

**1. [Regra 1 - Bug no contrato do plano] Campo do id na resposta do POST**
- **Encontrado em:** Tarefa 1
- **Problema:** o plano mandava extrair `notificationId` do corpo do último POST, mas o 202 devolve `CreateNotificationResponse(id, status, createdAt)`.
- **Correção:** `id_da_resposta` lê `"id"`, com fallback para `notificationId` e `Location`.
- **Arquivo:** scripts/smoke-lib.sh
- **Verificação:** smoke verde
- **Commit:** `c6f6443`

**2. [Regra 1 - Bug no contrato do plano] Contagem de SIMULATED_TRANSIENT**
- **Encontrado em:** Tarefa 1
- **Problema:** "4 ocorrências de SIMULATED_TRANSIENT" no corpo inteiro daria 5, porque `lastError.code` repete o código.
- **Correção:** conta só `"errorCode":"SIMULATED_TRANSIENT"` (4 na escada, 2 no fail-2-then-ok).
- **Arquivo:** scripts/smoke-fase2.sh
- **Commit:** `c6f6443`

**3. [Regra 2 - Robustez] Leitura da DLQ sem limite fixo de 200**
- **Encontrado em:** Tarefa 1
- **Problema:** como os volumes não são apagados, com `count: 200` fixo o id novo ficaria fora da janela depois de algumas rodadas.
- **Correção:** `count = max(200, mensagens da fila + 100)`, ainda com `ack_requeue_true` (T-02-29).
- **Arquivo:** scripts/smoke-lib.sh
- **Commit:** `c6f6443`

**4. [Regra 3 - Bloqueio] Worker pronto só com os listeners de pé**
- **Encontrado em:** Tarefa 1
- **Problema:** o health UP do worker pode chegar antes do runner iniciar os listeners (`auto-startup: false`).
- **Correção:** `iniciar_worker` também espera `Listeners iniciados` no log e a porta livre antes de subir (o worker é reiniciado 4 vezes).
- **Arquivo:** scripts/smoke-lib.sh
- **Commit:** `c6f6443`

---

**Total de desvios:** 4 correções automáticas (2 de contrato do plano, 1 de robustez, 1 de bloqueio), todas no script. Nenhum código de produção mudou: o smoke não revelou defeito nos planos 02-01..02-06.
**Impacto:** nenhum no escopo. As checagens ficaram iguais ou mais estritas que as do plano.

## Problemas encontrados

- Na primeira execução, o `docker compose up -d --wait` recriou os containers (`Recreate`) com os volumes preservados. O smoke seguiu normalmente.
- Ao final, a infra foi derrubada com `docker compose down` (sem `-v`, volumes preservados), para não deixar containers rodando. O script continua sem `down`, como o da Fase 1.

## Verificação

- `./mvnw -q verify`: verde (duas vezes)
- `bash scripts/smoke-fase2.sh --sem-build`: `SMOKE FASE 2: OK` em 3 execuções (2 só com a Tarefa 1, 1 com o script completo)
- `bash scripts/smoke-fase1.sh --sem-build`: `SMOKE FASE 1: OK`
- `bash scripts/smoke-fase1.sh --sem-build --perfil-demo`: `SMOKE FASE 1: OK`
- Critérios de aceite das duas tarefas: todos PASS (`bash -n`, greps, `git ls-files -s` com `100755` nos dois arquivos)

## Verificação humana pendente (fim de fase)

- Painel do RabbitMQ (http://localhost:15672, notify/notify → Queues) durante o critério 1: `notify.email.retry.1s`, `.5s` e `.25s` recebendo a mensagem em sequência, e `notify.email.dlq` com o header `x-failure-class` em "Get messages".
- `http://localhost:8080/notifications/<id>` de uma always-transient (4 tentativas legíveis) e `http://localhost:8081/actuator/prometheus` (séries `notify_delivery_*` e estado do CB).
- Para rodar: `bash scripts/smoke-fase2.sh --sem-build`. O script sobe a infra com `docker compose up -d --wait`.

## Setup do usuário

Docker Desktop ligado (já estava em execução nesta sessão; a pré-condição `docker info` passou).

## Prontidão para a próxima fase

- A Fase 2 está provada em runtime. A Fase 3 (reprocessamento da DLQ) pode contar com cópias na DLQ que têm `messageId` igual ao id, `x-failure-class` e `notify-retry-count`. O smoke lê com `ack_requeue_true`, então as mensagens continuam lá.
- `smoke-lib.sh` está pronto para o smoke da Fase 3 (`fail-until-reprocess` → DLQ → reprocess → SENT).
- As cópias na DLQ levam também os headers `spring_listener_return_correlation`/`spring_returned_message_correlation` do RabbitTemplate. São inofensivos, mas o reprocessamento da Fase 3 não deve copiá-los de volta.

---
*Fase: 02-falhas-retry-com-backoff-e-resili-ncia*
*Concluído: 2026-10-16*

## Self-Check: PASSED

- FOUND: scripts/smoke-lib.sh, scripts/smoke-fase2.sh (100755)
- FOUND: commits c6f6443, 5c2e4a5
- plan_head_before 5f83b05 → HEAD: 2 commits medidos
