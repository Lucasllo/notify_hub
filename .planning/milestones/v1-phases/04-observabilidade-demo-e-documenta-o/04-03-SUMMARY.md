---
phase: 04-observabilidade-demo-e-documenta-o
plan: 03
subsystem: observability
tags: [grafana, prometheus, k6, docker-compose, dashboard, smoke]

requires:
  - phase: 04-01
    provides: stack no compose (api, worker, rabbitmq, mongo, prometheus), histograma HTTP e smoke-fase4.sh
  - phase: 04-02
    provides: contador notify_publish_outcomes_total{outcome, reason} com 7 séries
provides:
  - Grafana 12.4.12 provisionado, anônimo Viewer, home = dashboard Notify Hub (uid notify-hub)
  - Datasource Prometheus uid prometheus com timeInterval 5s
  - Dashboard clássico com 5 rows (D-01) e 21 expressões PromQL validadas no Prometheus real
  - Serviço k6 (profile bench) e k6/demo.js com 1000 notificações, mix mágico, thresholds e resumo
  - smoke-fase4.sh estendido (Grafana, 7 séries de publish, k6, reflexo da carga) com --sem-k6
affects: [04-04, 04-05, README, docs/demo.md, docs/trade-offs.md]

actuals:
  tokens: 10176
  tasks: 3
  commits: 3
plan_head_before: c808a54d5ea1983d64fed071afc42a1f52584bc1

tech-stack:
  added: [grafana/grafana:12.4.12, grafana/k6:2.3.0]
  patterns:
    - "Dashboard JSON escrito à mão, uid fixo, datasource {type: prometheus, uid: prometheus} em todo painel e target"
    - "k6 com command no compose (evita conversão de caminho do Git Bash) e resumo por diferença contra a linha de base do setup()"

key-files:
  created:
    - observability/grafana/provisioning/datasources/prometheus.yaml
    - observability/grafana/provisioning/dashboards/notify-hub.yaml
    - observability/grafana/provisioning/plugins/.gitkeep
    - observability/grafana/provisioning/alerting/.gitkeep
    - observability/grafana/dashboards/notify-hub.json
    - k6/demo.js
  modified:
    - docker-compose.yml
    - scripts/smoke-fase4.sh

key-decisions:
  - "Não terminais do resumo do k6 medidos como crescimento desde o setup() (max(0, agora - base)), não como valor absoluto: o volume do Mongo local tem 4 PROCESSING abandonados de 2026-10-12/13 que nunca drenam; o absoluto aparece numa linha extra quando difere de zero"
  - "Diretórios vazios provisioning/plugins e provisioning/alerting (.gitkeep) para o Grafana não logar level=error de provisionamento ao montar a pasta inteira"

patterns-established:
  - "Validador de dashboard: percorre o JSON, troca $__rate_interval/$__range e executa cada expressão no /api/v1/query do Prometheus"

requirements-completed: [OBS-03, DEM-02]

coverage:
  - id: D1
    description: "Grafana sobe anônimo (Viewer) direto no dashboard Notify Hub, com datasource provisionado respondendo pelo proxy"
    requirement: OBS-03
    verification:
      - kind: integration
        ref: "Task 1 verify: GET /api/dashboards/home anônimo com \"title\":\"Notify Hub\" e \"canEdit\":false; proxy uid/prometheus query=up com status success"
        status: pass
      - kind: integration
        ref: "bash scripts/smoke-fase4.sh, etapa 9"
        status: pass
    human_judgment: false
  - id: D2
    description: "Dashboard com 5 rows do D-01 e 21 expressões, todas válidas no Prometheus e com datasource uid prometheus"
    requirement: OBS-03
    verification:
      - kind: integration
        ref: "Task 2 verify (node): rows=5 exprs=21 erros=0 semDatasource=0; API do Grafana com 5 rows"
        status: pass
    human_judgment: false
  - id: D3
    description: "Renderização visual do dashboard durante a carga (CB mudando de cor, recusas, DLQs crescendo, sem No data)"
    requirement: OBS-03
    verification: []
    human_judgment: true
    rationale: "human-check da Task 2: a aparência dos painéis (cores da state timeline, legibilidade) não é verificável por API; checkpoint humano do D-10 no fim da fase"
  - id: D4
    description: "k6 dispara 1000 POST com mix determinístico, sai verde e imprime o resumo por status e canal"
    requirement: DEM-02
    verification:
      - kind: e2e
        ref: "bash scripts/smoke-fase4.sh, etapa 11 (target/smoke/k6.log com novas=1000 e PENDING=0 PROCESSING=0 RETRYING=0)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Prometheus reflete a carga: recusas circuit_open, retries, DLQs com mensagens, >= 1000 publishes confirmados"
    requirement: DEM-02
    verification:
      - kind: e2e
        ref: "bash scripts/smoke-fase4.sh, etapa 12"
        status: pass
    human_judgment: false

duration: 12min
completed: 2026-11-01
status: complete
---

# Phase 4 Plan 03: Grafana provisionado e demo k6 Summary

**Grafana 12.4.12 anônimo abrindo direto no dashboard "Notify Hub" (5 rows, 21 PromQL validadas, CB em state timeline com recusas ao lado) e k6 2.3.0 disparando 1000 notificações com mix mágico, com o reflexo da carga provado no Prometheus pelo smoke da Fase 4**

## Performance

- **Duration:** 12 min
- **Started:** 2026-11-01T22:46:21Z
- **Completed:** 2026-11-01T22:58:08Z
- **Tasks:** 3
- **Files modified:** 8

## Accomplishments

- `http://localhost:3000` abre sem login no dashboard Notify Hub, somente leitura (`canEdit: false`); `admin/admin` só local
- Dashboard com as rows Visão geral, Entrega por canal, Retry e DLQ, Resiliência e API e JVM; as 21 expressões executam no Prometheus real com `status: success`
- `docker compose --profile bench run --rm k6`: 1000/1000 checks 202, `http_req_failed` 0%, resumo `novas=1000`, drenagem em cerca de 36s
- Smoke da rodada final: `circuit_open` +2156 em 5m, retries +2737, 839 mensagens nas DLQs, 2001 publishes confirmados, `count(notify_publish_outcomes_total) = 7` (o contador do 04-02 agora está confirmado no container)

## Task Commits

1. **Task 1: Tracer do Grafana anônimo com a row Visão geral** - `a4d2226` (feat)
2. **Task 2: Dashboard completo com 5 rows e 21 expressões** - `146bea2` (feat)
3. **Task 3: k6 demo e smoke estendido** - `925d915` (feat)

## Files Created/Modified

- `docker-compose.yml` - serviços `grafana` (127.0.0.1:3000, anônimo Viewer, home path, analytics off) e `k6` (profile bench, command no YAML)
- `observability/grafana/provisioning/datasources/prometheus.yaml` - datasource uid `prometheus`, `timeInterval: 5s`
- `observability/grafana/provisioning/dashboards/notify-hub.yaml` - provider de arquivo somente leitura
- `observability/grafana/provisioning/{plugins,alerting}/.gitkeep` - diretórios vazios para o Grafana não logar erro
- `observability/grafana/dashboards/notify-hub.json` - dashboard clássico `notify-hub`
- `k6/demo.js` - cenário shared-iterations, mix por `iterationInTest`, thresholds, setup/teardown com `/notifications/stats`
- `scripts/smoke-fase4.sh` - etapas 9 a 12 e flag `--sem-k6`

## Decisions Made

- O resumo do k6 mede os não terminais como crescimento desde o `setup()`, não como valor absoluto (ver Deviation 1).
- Layout: Visão geral com throughput e 3 stats; Retry e DLQ com 4 painéis lado a lado (delay, DLQs, filas principais); Resiliência com CB (10 col), recusas (8) e bulkhead (6); API e JVM em duas linhas de 3.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Não terminais do resumo do k6 contados por diferença contra a linha de base**
- **Found during:** Task 3
- **Issue:** o `/notifications/stats` deste ambiente tem 4 documentos em PROCESSING de 2026-10-12/13 (`ana@exemplo.com`, `attemptCount: 0`, lease antigo), restos de rodadas anteriores à Fase 2 no volume `mongo-data`. Eles nunca drenam sozinhos (PROCESSING só é retomado por redelivery ou reprocess), então a condição do plano com valores absolutos faria toda rodada terminar em `RESUMO INCOMPLETO` nesta máquina. É o mesmo problema da Armadilha 10 (volume persistente, `/stats` global), aplicado aos não terminais.
- **Fix:** a parada e a linha `não terminais: PENDING=.. PROCESSING=.. RETRYING=..` usam `max(0, agora - base)` por status. Quando o absoluto difere de zero, uma linha extra mostra `(no banco inteiro, contando rodadas anteriores: PENDING=0 PROCESSING=4 RETRYING=0)`. Os dados antigos não foram apagados.
- **Files modified:** k6/demo.js
- **Verification:** o smoke passou com `novas=1000` e `PENDING=0 PROCESSING=0 RETRYING=0`
- **Committed in:** 925d915

**2. [Rule 1 - Bug] Diretórios de provisionamento vazios para plugins e alerting**
- **Found during:** Task 1
- **Issue:** montar `./observability/grafana/provisioning` inteiro esconde os subdiretórios da imagem, e o Grafana logava `level=error` para `provisioning/plugins` e `provisioning/alerting`. Esses logs bateriam no critério da Task 2 (`level=error.*(provision|dashboard)` sem saída).
- **Fix:** `.gitkeep` nos dois diretórios. O Grafana só lê arquivos yaml/yml/json ali.
- **Files modified:** observability/grafana/provisioning/plugins/.gitkeep, observability/grafana/provisioning/alerting/.gitkeep
- **Verification:** container recriado e `docker compose logs grafana | grep -iE 'level=error.*(provision|dashboard)'` sem saída
- **Committed in:** a4d2226

---

**Total deviations:** 2 auto-fixed (1 blocking, 1 bug)
**Impact on plan:** as duas eram necessárias para o verify passar. O comportamento visível continua o mesmo. Sem aumento de escopo.

## Issues Encountered

- O Grafana loga `level=error msg="plugin route is not covered by RBAC..."` em cada chamada de `/api/datasources/proxy/uid/prometheus/...`, mas a resposta é 200 com `"status":"success"`. A linha não contém `provision|dashboard`, então é ruído conhecido do 12.4.
- O broker local tem filas `notify.<canal>.retry.125s` sobrando de uma escada antiga. Elas aparecem no painel "Filas de delay" com valor 0. Num volume novo não existem. Ficaram fora do escopo.

## Known Stubs

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- O 04-05 (README/docs/demo.md) pode narrar o comando do k6, o resumo e a ligação entre DEAD_LETTERED de destinatários válidos no webhook e o CB aberto (Armadilha 11), além de registrar `admin/admin` em `docs/trade-offs.md` (T-04-11).
- Fica pendente o human-check visual do dashboard durante uma rodada do k6 (checkpoint do D-10 no fim da fase).
- Opcional: os 4 PROCESSING antigos podem ser apagados do volume local, ou o volume recriado com `docker compose down -v`. Não precisa fazer isso para a demo funcionar.

## Self-Check: PASSED

- FOUND: os 6 arquivos de `files_modified` e os 2 `.gitkeep`
- FOUND: commits a4d2226, 146bea2, 925d915
- Verificação do plano: home anônima OK; validador `rows=5 exprs=21 erros=0 semDatasource=0`; `SMOKE FASE 4: OK`; `application-demo.yml` sem diff

---
*Phase: 04-observabilidade-demo-e-documenta-o*
*Completed: 2026-11-01*
