---
phase: 01-entrega-ponta-a-ponta-no-caminho-feliz
plan: 06
subsystem: testing
tags: [smoke, bash, git-bash, docker-compose, rabbitmq-management-api, mongosh, curl, end-to-end, virtual-threads]

requires:
  - phase: 01-04
    provides: "notify-api: POST /notifications (202 + Location, PUBLISH_FAILED/503 + Retry-After), GET /notifications/{id}, 400 problem+json com errors[]"
  - phase: 01-05
    provides: "notify-worker: listener por canal em virtual threads, claim/markSent, log 'duplicada/obsoleta', rejeição sem requeue para a DLQ, profile demo"
provides:
  - "scripts/smoke-fase1.sh: prova ponta a ponta do esqueleto contra RabbitMQ e MongoDB reais (opções --sem-build e --perfil-demo, logs em target/smoke/)"
  - "Evidência de runtime dos 5 critérios de sucesso da Fase 1: topologia + índices + seed, POST → SENT em VirtualThread nos 3 canais, 400 sem persistir, 503 PUBLISH_FAILED, duplicata sem reenvio, DLQ sem loop, profile demo 1s/5s/25s"
  - "Execução local documentada da stack completa exigida pelo SKELETON.md"
affects: [02, 03, 04]

actuals:
  tokens: 6008
  tasks: 2
  commits: 2
plan_head_before: c1494c66a065a66441427b21530912a984ab4537

tech-stack:
  added: []
  patterns:
    - "Smoke em bash portável para Git Bash: curl + grep/sed/cut (sem jq), mongosh via docker compose exec -T, API de gerenciamento do RabbitMQ para topologia, binding e publish"
    - "JVMs em segundo plano encerradas por trap; no Git Bash, taskkill //F //T com o pid de /proc/<pid>/winpid se o java sobreviver ao kill"
    - "Estado alterado pelo teste (binding removido) restaurado no fluxo normal e no trap de saída"
    - "O smoke não depende de volumes vazios: compara contagens antes/depois e segue ids próprios"

key-files:
  created:
    - scripts/smoke-fase1.sh
  modified: []

key-decisions:
  - "Porta livre = curl em 127.0.0.1 saindo com 7 (conexão recusada) e timeout de 10s: no Windows a recusa leva ~2s, e o timeout de 2s dava falso 'ocupada'"
  - "As apps são encerradas antes do echo final, para que 'SMOKE FASE 1: OK' seja a última linha da saída; o trap só age em falha"
  - "No modo --perfil-demo o script confere topologia 1s/5s/25s, índices/seed e o caminho feliz EMAIL, e pula SMS/WEBHOOK e os passos 10-13"
  - "A duplicata é confirmada pela linha 'duplicada/obsoleta descartada id=<id>' do próprio id, não só pela presença do texto no log"
  - "Não gerei 01-USER-SETUP.md: o único passo de user_setup (Docker Desktop ligado) já estava cumprido, como no 01-01"

patterns-established:
  - "Contagem de mensagens de fila por queues/%2F/<fila>?columns=messages; consumidores por polling de até 30s (estatísticas da API de gerenciamento atualizam a cada ~5s)"

requirements-completed: [FND-01, FND-02, FND-03, FND-04, MSG-01, MSG-02, MSG-03, MSG-04, MSG-05, API-01, API-02, API-04, API-06, WRK-01, WRK-02, WRK-04, WRK-05, TPL-01, DLV-01]

coverage:
  - id: D1
    description: "Infra healthy com docker compose up -d --wait; build dos três módulos verde; broker com notify.exchange, notify.retry e notify.dlx e, por canal, fila principal, retry.5s/25s/125s e DLQ; notify.email com 10 consumidores; Mongo com status_channel_createdAt, channel_createdAt, createdAt e 3 templates"
    requirement: MSG-01
    verification:
      - kind: other
        ref: "./mvnw -q verify (exit 0)"
        status: pass
      - kind: e2e
        ref: "bash scripts/smoke-fase1.sh --sem-build (passos 2, 5 e 6)"
        status: pass
    human_judgment: false
  - id: D2
    description: "POST EMAIL (welcome), SMS (order-shipped) e WEBHOOK (password-reset) → 202 + Location + PENDING → SENT em até 15s, com texto pt-BR, envelope event/notificationId/data no WEBHOOK e attempts[0] com number 1, durationMs e thread VirtualThread; worker.log sem 'Consumer failed to start' e nenhum log com PRECONDITION_FAILED"
    requirement: WRK-05
    verification:
      - kind: e2e
        ref: "bash scripts/smoke-fase1.sh --sem-build (passos 7 e 8)"
        status: pass
    human_judgment: false
  - id: D3
    description: "400 application/problem+json para canal PUSH, destinatário ausente, SMS '123' e variável 'a.b' (fields channel, recipient, recipient, variables) sem mudar a contagem de notifications"
    requirement: API-06
    verification:
      - kind: e2e
        ref: "bash scripts/smoke-fase1.sh --sem-build (passo 10)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Sem o binding notify.exchange → notify.email, o POST EMAIL responde 503 com Retry-After: 5 e publish-failed e o GET mostra PUBLISH_FAILED; o binding volta (também no trap) e o POST seguinte chega a SENT"
    requirement: API-02
    verification:
      - kind: e2e
        ref: "bash scripts/smoke-fase1.sh --sem-build (passo 11) + binding conferido depois das duas execuções"
        status: pass
    human_judgment: false
  - id: D5
    description: "Duplicata publicada pela API de gerenciamento (routed) é descartada com log 'duplicada/obsoleta' e a notificação continua SENT com 1 tentativa; templateCode inexistente estaciona exatamente uma mensagem em notify.email.dlq, com notify.email vazia após 3s"
    requirement: WRK-02
    verification:
      - kind: e2e
        ref: "bash scripts/smoke-fase1.sh --sem-build (passos 12 e 13)"
        status: pass
    human_judgment: false
  - id: D6
    description: "Com --spring.profiles.active=demo em API e worker existem notify.<canal>.retry.1s/5s/25s nos três canais, o EMAIL chega a SENT e o restart sobre a topologia já declarada não gera PRECONDITION_FAILED"
    requirement: MSG-05
    verification:
      - kind: e2e
        ref: "bash scripts/smoke-fase1.sh --sem-build --perfil-demo"
        status: pass
    human_judgment: false
  - id: D7
    description: "Topologia visível no painel do RabbitMQ (exchanges, filas com DLX/DLK e TTL, DLQ com a mensagem do template inexistente)"
    requirement: MSG-04
    verification: []
    human_judgment: true
    rationale: "Critério 1 do ROADMAP pede a topologia 'visível no painel'; a leitura visual em http://localhost:15672 é o human-check da Task 2, harvested no UAT de fim de fase"
  - id: D8
    description: "GET no Location de uma notificação EMAIL autoexplicativo no navegador (SENT, rendered em pt-BR, attempts[0] com timestamp, durationMs e thread VirtualThread)"
    requirement: API-04
    verification: []
    human_judgment: true
    rationale: "Human-check da Task 2: confirma que a demo se explica sozinha para quem avalia o portfólio (CONTEXT, Specific Ideas)"

duration: 10min
completed: 2026-10-12
status: complete
---

# Phase 1 Plan 06: Smoke ponta a ponta da Fase 1 Summary

**`scripts/smoke-fase1.sh` sobe RabbitMQ e MongoDB com `docker compose up -d --wait`, inicia worker e API a partir dos jars e prova contra a infra real os 5 critérios da Fase 1: topologia, índices e seed; EMAIL, SMS e WEBHOOK de PENDING a SENT em pt-BR numa VirtualThread; 400 sem persistir; 503 PUBLISH_FAILED com `Retry-After: 5`; duplicata sem reenvio; DLQ sem loop; e o profile demo com 1s/5s/25s. Nenhum defeito de produção apareceu.**

## Performance

- **Duration:** 10 min
- **Started:** 2026-10-12T19:37:31Z
- **Completed:** 2026-10-12T19:47:40Z
- **Tasks:** 2
- **Files modified:** 1

## Accomplishments
- O caminho feliz atravessa broker e banco de verdade. Cada POST responde 202 com `Location: /notifications/<uuid>` e corpo PENDING, e o GET chega a SENT em menos de 15s. O GET traz `Boas-vindas ao Notify Hub, Ana!` (EMAIL), `PED-123` (SMS) e o envelope `event`/`notificationId`/`data` com o próprio id e `789012` (WEBHOOK). `attempts[0]` tem `"number":1`, `durationMs` e `"thread":"VirtualThread[...]"`.
- Topologia e dados conferidos por API. O broker tem `notify.exchange`, `notify.retry` e `notify.dlx`. Para cada canal existem a fila principal, a DLQ e `retry.5s`/`25s`/`125s` (ou `1s`/`5s`/`25s` no demo). A `notify.email` tem 10 consumidores. O Mongo tem os índices `_id_,status_channel_createdAt,channel_createdAt,createdAt` e 3 templates.
- Caminhos de falha:
  - Os quatro 400 saem como `application/problem+json`, com `errors[].field` `channel`, `recipient`, `recipient` e `variables`. A contagem de `notifications` não muda.
  - Sem o binding, o POST responde 503 com `Retry-After: 5` e `publish-failed`, e o documento fica PUBLISH_FAILED. Com o binding de volta, o POST seguinte chega a SENT.
  - A duplicata publicada pelo painel é roteada, o worker loga `duplicada/obsoleta descartada id=<id>` e a notificação continua SENT com 1 tentativa.
  - O template `nao-existe` põe exatamente uma mensagem a mais em `notify.email.dlq`, e a contagem fica estável por 3s com `notify.email` vazia. O status da notificação fica PROCESSING, a lacuna conhecida que a Fase 2 fecha.
- Rodar as duas execuções seguidas, padrão e depois `--perfil-demo`, reinicia API e worker sobre a topologia já declarada. Não aparece PRECONDITION_FAILED nem "Consumer failed to start", e o binding `notification.email` continua no lugar depois das duas.

## Task Commits

1. **Task 1: Smoke do caminho feliz (tracer)**: `346b008` (feat)
2. **Task 2: Smoke dos caminhos de falha e profile demo**: `c39bc6e` (feat)

**Plan metadata:** commit do SUMMARY (docs)

## Files Created/Modified
- `scripts/smoke-fase1.sh` (100755, LF): o smoke da Fase 1.
  - Opções `--sem-build` e `--perfil-demo`.
  - Helpers `ok`, `falhou`, `esperar_saude`, `rabbit_api`, `mongo_eval`, `post_notificacao`, `esperar_status`, `restaurar_binding` e `mensagens_na_fila`.
  - Um trap que restaura o binding e encerra as JVMs.
  - Logs e respostas em `target/smoke/` (ignorado pelo git).

## Decisions Made
- **Porta livre:** a checagem usa curl em `127.0.0.1` com timeout de 10s e espera o código 7 (conexão recusada). No Windows uma conexão recusada leva cerca de 2s, porque o TCP repete o SYN. Com 2s de timeout o curl saía com 28, e o script acusava a porta 8080 como ocupada sem nada ouvindo.
- **Última linha:** as apps são encerradas explicitamente antes do `echo "SMOKE FASE 1: OK"`. Assim essa é a última linha da saída, como pede o `<fails_when>`. Em caso de falha, o trap encerra as apps.
- **Modo demo:** pula SMS/WEBHOOK e os passos 10-13, como o plano pede. Mantive as checagens de índices e seed, que são baratas e não afrouxam nada.
- **Duplicata:** a prova é a linha de log com o id da própria notificação (`duplicada/obsoleta descartada id=<id>`), não só a presença do texto no log.
- **USER-SETUP:** não gerei `01-USER-SETUP.md`. O único passo de `user_setup` era ligar o Docker Desktop, e ele já estava cumprido (a precondição `docker info` passou). O 01-01 seguiu o mesmo precedente.

## Deviations from Plan

None - plan executed exactly as written. O smoke não revelou nenhum defeito no código de produção dos planos 01-01 a 01-05. Os dois ajustes acima (timeout da checagem de porta e ordem da linha final) foram feitos no próprio script, antes do commit da Task 1, e deixam o script mais estrito, não mais frouxo.

## Issues Encountered
- A primeira execução falhou na checagem de porta (curl com exit 28 no Windows, ver Decisions). Corrigi antes do commit da Task 1.
- Há um `java.exe` (pid 6408) que continua rodando: é o language server do VS Code (`redhat.java`), não uma JVM do smoke. As JVMs do smoke foram encerradas pelo próprio script (winpids registrados na saída), e 8080/8081 ficaram livres (curl saindo com 7).
- O `worker.log` ainda mostra o aviso do `MustacheAutoConfiguration`. É o item aberto em `deferred-items.md` e fica fora do escopo deste plano.
- A infra (rabbitmq e mongo) continua no ar de propósito, para o human-check do painel. O `notify.email.dlq` tem a mensagem do template inexistente da última execução padrão.

## User Setup Required

None - o Docker Desktop precisa estar ligado para o smoke (passo de `user_setup` do plano, já cumprido nesta execução).

## Next Phase Readiness
- O esqueleto da Fase 1 está provado em runtime. Para reproduzir: `./mvnw -q verify && bash scripts/smoke-fase1.sh --sem-build`, e depois `bash scripts/smoke-fase1.sh --sem-build --perfil-demo`.
- Human-checks para o UAT de fim de fase:
  1. Painel http://localhost:15672 (notify/notify), abas Exchanges e Queues. Devem aparecer as 3 exchanges, as filas com DLX/DLK e TTL e a mensagem em `notify.email.dlq`.
  2. GET de uma notificação EMAIL no navegador, por exemplo `http://localhost:8080/notifications/<id>`. A API precisa estar no ar: rode o smoke ou `java -jar notify-api/target/notify-api-0.1.0-SNAPSHOT.jar`.
- A Fase 2 pode estender o mesmo script (ou criar um `smoke-fase2.sh`) para retry → DEAD_LETTERED/FAILED. O passo 13 hoje só imprime o status PROCESSING da notificação rejeitada.

---
*Phase: 01-entrega-ponta-a-ponta-no-caminho-feliz*
*Completed: 2026-10-12*

## Self-Check: PASSED
- `scripts/smoke-fase1.sh` existe, com modo 100755 no índice do git
- Os commits 346b008 e c39bc6e estão no histórico
- `./mvnw -q verify` terminou com exit 0. `bash scripts/smoke-fase1.sh --sem-build` e `--sem-build --perfil-demo` terminaram com `SMOKE FASE 1: OK`, exit 0 e nenhuma linha `FALHOU:`
- Todos os critérios de aceitação das Tasks 1 e 2 passaram (bash -n, greps, binding `notification.email` presente depois das duas execuções)
