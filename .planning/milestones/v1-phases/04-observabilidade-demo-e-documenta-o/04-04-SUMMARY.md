---
phase: 04-observabilidade-demo-e-documenta-o
plan: 04
subsystem: benchmark
tags: [virtual-threads, k6, jfr, docker-compose, mongosh, benchmark]

requires:
  - phase: 04-03
    provides: "compose base com api, worker, rabbitmq, mongo, prometheus, grafana e k6 (profile bench); dashboard Notify Hub"
provides:
  - "docker-compose.bench.yml: override só de ENV, só VIRTUAL_THREADS varia entre os modos"
  - "k6/bench-backlog.js: backlog de W+N com destinatários de domínios reservados"
  - "scripts/bench/drain-stats.js: vazão de drenagem por sentAt com aquecimento descartado"
  - "scripts/bench/run-bench.sh: calibração, 5 rodadas intercaladas por modo, JFR, varredura C=50/500, resumo e sanidade do modo"
  - "docs/benchmark.md: método, ambiente, resultados medidos, leitura honesta e reprodução (pt-BR)"
affects: [04-05, README]

actuals:
  tokens: 9914
  tasks: 3
  commits: 3
plan_head_before: 929bd0bcc69a33ba827bca8250e76168a9ff9c58

tech-stack:
  added: []
  patterns:
    - "Benchmark em projeto compose separado (-p notify-bench) com override só de ENV; demo só recebe stop"
    - "Vazão medida no MongoDB (sentAt) com W conclusões de aquecimento descartadas, na mesma JVM"
    - "Rodadas intercaladas vt/pt com mediana, mín e máx; JFR e varredura rotulados fora da mediana"

key-files:
  created:
    - docker-compose.bench.yml
    - k6/bench-backlog.js
    - scripts/bench/run-bench.sh
    - scripts/bench/drain-stats.js
    - docs/benchmark.md
  modified: []

key-decisions:
  - "N=30000 e W=6000 como padrão: a calibração mediu no máximo 482.5 msg/s (cal-vt) e 460.6 msg/s na repetição, então o mínimo de 30000 já dá janela acima de 20s"
  - "BENCH_POOL fica em 200: espera no pool 0 na calibração e em 9 de 10 rodadas oficiais; só C=500 satura o pool (registrado no documento)"
  - "Vazão publicada como empate: virtual 744.4 msg/s (508.3-871.6) x plataforma 786.2 (603.7-929.1); a diferença das medianas (~5%) é bem menor que a dispersão"
  - "Container de outro projeto (link-pulse-control-plane, ~0.3-0.85 CPU e 3.1 GiB) não foi parado, por não ser deste projeto; a carga foi declarada no docs/benchmark.md e o script passou a registrar outros containers no log"

patterns-established:
  - "Bench scripts: MSYS_NO_PATHCONV=1 também nas ENVs passadas ao docker compose (o Git Bash converte /tmp em valores de ENV)"

requirements-completed: [DEM-03]

coverage:
  - id: D1
    description: "Rodada de calibração por modo, do backlog no k6 até a vazão medida no MongoDB, num projeto compose isolado"
    requirement: DEM-03
    verification:
      - kind: e2e
        ref: "bash scripts/bench/run-bench.sh --calibrar (2 execuções: RESULTADO cal-vt/cal-pt, CALIBRACAO, BENCH: OK)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Protocolo oficial: 10 rodadas intercaladas, JFR de pinning, varredura C=50/500, resumo.txt e sanidade do modo"
    requirement: DEM-03
    verification:
      - kind: e2e
        ref: "bash scripts/bench/run-bench.sh --varredura > target/bench/execucao.log (exit 0, 10 RESULTADO vtN/ptN, 4 linhas varredura, BENCH: OK)"
        status: pass
    human_judgment: false
  - id: D3
    description: "docs/benchmark.md em pt-BR com números que batem com o resumo.txt e conclusão que não vai além do medido"
    requirement: DEM-03
    verification:
      - kind: other
        ref: "verify da Task 3 (linhas do resumo verbatim, sem códigos internos, seções Como reproduzir e Saída bruta)"
        status: pass
    human_judgment: true
    rationale: "human-check do plano: conferir que toda afirmação de empate/mais rápido bate com medianas e dispersão (prohibition de transparência flagged)"

duration: 51min
completed: 2026-11-01
status: complete
---

# Phase 04 Plan 04: Benchmark virtual threads x threads de plataforma Summary

**Protocolo automatizado de drenagem (projeto compose notify-bench, backlog k6 de 36000, vazão por sentAt no MongoDB) com 5 rodadas intercaladas por modo: empate de vazão (virtual 744.4 x plataforma 786.2 msg/s, intervalos sobrepostos) e 40 x 636 threads de plataforma em C=200 (1536 em C=500), 7 eventos de pinning no basicAck do amqp-client**

## Performance

- **Duration:** 51 min
- **Started:** 2026-11-01T23:00:53Z
- **Completed:** 2026-11-01T23:52:43Z
- **Tasks:** 3
- **Files modified:** 5 (todos novos)

## Accomplishments

- Um comando (`bash scripts/bench/run-bench.sh --varredura`) sobe o projeto isolado, roda 10 rodadas oficiais intercaladas, a rodada JFR e a varredura, e escreve `target/bench/resumo.txt`
- Medido nesta máquina (i7-4790, 8 vCPU, 7.7 GiB na VM do Docker Desktop, JDK 21.0.13):
  - virtual: mediana 744.4 msg/s (508.3-871.6), pico de threads 40, memória 718 MiB
  - plataforma: mediana 786.2 msg/s (603.7-929.1), pico de threads 636, memória 844 MiB
  - varredura: C=50 786.4/776 msg/s, 43/186 threads; C=500 807.5/807.6 msg/s, 41/1536 threads, 750/1097 MiB
  - JFR (limiar 1ms): 7 `jdk.VirtualThreadPinned`, de 1.31 a 23.5 ms, todos em `ChannelN.basicAck` do amqp-client
- `docs/benchmark.md` publica todas as 10 rodadas oficiais, o resumo verbatim e uma leitura de empate de vazão, com a diferença em threads e memória

## Calibração (Task 1)

| Execução | cal-vt msg/s | cal-pt msg/s | pico threads vt/pt | espera pool | Sugestão |
|----------|--------------|--------------|--------------------|-------------|----------|
| 1ª | 482.5 | 457.3 | 39 / 636 | 0 / 0 | N=30000 W=6000 |
| 2ª (gate do tracer) | 448.5 | 460.6 | 41 / 636 | 0 / 0 | N=30000 W=6000 |

N/W padrão ficaram em 30000/6000; `BENCH_POOL` ficou em 200, porque a espera no pool ficou em 0.

## Task Commits

1. **Task 1: Tracer: calibração por modo em projeto compose separado** - `93e6aec` (feat)
2. **Task 2: Protocolo oficial com rodadas intercaladas, JFR e varredura** - `53b114e` (feat)
3. **Task 3: docs/benchmark.md** - `439aa03` (docs)

## Files Created/Modified

- `docker-compose.bench.yml`: override só de ENV (profile bench inexistente = sem demo, sweep 1h na API, C/bulkhead/latência/pool por BENCH_*, CB neutralizado, logs WARN, JAVA_TOOL_OPTIONS, stop_grace_period 30s)
- `k6/bench-backlog.js`: ITERATIONS obrigatória, VUS 100, shared-iterations, `example.com` e `+5511…`
- `scripts/bench/drain-stats.js`: mongosh, `warmup` global, saída `{"total","medidas","janelaMs","msgPorSeg"}`
- `scripts/bench/run-bench.sh`: `rodada`, `--calibrar`, `--varredura`, `--sem-build`, `--manter`, guarda de C ≤ 650, resumo, sanidade e JFR
- `docs/benchmark.md`: documento do benchmark em pt-BR

## Decisions Made

- Leitura publicada: empate de vazão. A mediana do modo plataforma é numericamente 42 msg/s maior, e o documento diz isso, mas a dispersão de cada modo (~325 a 365 msg/s) é muito maior que essa diferença.
- O container `link-pulse-control-plane` (cluster Kubernetes local de outro projeto) estava no ar durante tudo, entre ~30% e 85% de uma CPU e 3.1 GiB. Parar recurso de outro projeto não é escopo deste executor. A carga foi declarada no Ambiente e nas Limitações do documento.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Build explícito de api e worker**
- **Found during:** Task 1
- **Issue:** `up -d --wait --build rabbitmq mongo api prometheus grafana` só reconstrói a api; a imagem do worker do projeto notify-bench nunca seria reconstruída, e uma imagem velha seria medida
- **Fix:** `"${COMPOSE[@]}" build api worker` (salvo com `--sem-build`) antes do `up` sem `--build`
- **Files modified:** scripts/bench/run-bench.sh
- **Committed in:** 93e6aec

**2. [Rule 2 - Correctness] `--force-recreate` no up do worker de cada rodada**
- **Found during:** Task 1
- **Issue:** quando a config da rodada é igual à anterior (ex.: varredura depois da JFR), o compose só reinicia o container parado em vez de criar um novo
- **Fix:** `up -d --wait --no-deps --force-recreate worker`, garantindo container e JVM novos por rodada
- **Committed in:** 93e6aec

**3. [Rule 1 - Bug] MSYS_NO_PATHCONV=1 no up do worker**
- **Found during:** Task 2 (teste com PowerShell antes da rodada JFR)
- **Issue:** o Git Bash converte `/tmp/worker.jfr` dentro do valor da ENV `BENCH_JAVA_TOOL_OPTIONS` para `C:/Users/.../Temp/worker.jfr`
- **Fix:** `MSYS_NO_PATHCONV=1` também no `up` do worker
- **Committed in:** 53b114e

**4. [Rule 2 - Transparência] Registro da carga concorrente e mais contexto no JFR**
- **Found during:** Task 2
- **Issue:** havia outro container pesado na mesma VM, e o `jfr print` padrão corta a pilha em 5 frames, o que esconde o ponto de pinning
- **Fix:** linha `outros containers no ar (carga concorrente)` no log; `jfr print --stack-depth 32`
- **Committed in:** 53b114e

**5. [Rule 2 - Correctness] Coluna `execucao` no resultados.csv e saída do k6 por rodada**
- **Found during:** Task 1
- **Issue:** o CSV acumula execuções, e a mediana não pode misturar rodadas de outra execução; a saída do k6 inflaria o execucao.log
- **Fix:** o CSV tem a coluna `execucao` e a mediana filtra pela execução corrente; a saída do k6 vai para `target/bench/k6-<tag>.log`
- **Committed in:** 93e6aec / 53b114e

---

**Total deviations:** 5 auto-fixed (1 blocking, 1 bug, 3 correctness/transparência)
**Impact on plan:** todas necessárias para medir o que o plano pede. Escopo igual ao planejado.

## Issues Encountered

- Antes da rodada oficial houve um ensaio curto (`BENCH_REPETICOES=1 BENCH_N=3000 BENCH_W=600 --varredura --sem-build`) para validar JFR, resumo e sanidade sem arriscar 40 minutos. As linhas dele ficam no `resultados.csv` com outro valor de `execucao` e não entram no resumo nem no documento.
- Dispersão alta entre rodadas do mesmo modo (vt5 508.3 msg/s, pt2 603.7 msg/s), atribuída em parte à carga oscilante do outro container. Rodar de novo num host dedicado deve estreitar os intervalos; o documento registra isso.
- A demo (notify-hub) foi parada pelo script e religada no fim com `docker compose -p notify-hub start` (volumes intactos).

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- O 04-05 (README) pode copiar os números de `docs/benchmark.md`: empate de vazão, 40 x 636 threads de plataforma em C=200 (1536 em C=500), +126 MiB / +347 MiB de memória e 7 eventos de pinning no amqp-client.
- Para números mais limpos, o autor pode parar o cluster local do outro projeto e rodar de novo `bash scripts/bench/run-bench.sh --varredura`, depois atualizar o documento com o novo resumo.

## Self-Check: PASSED

- FOUND: docker-compose.bench.yml, k6/bench-backlog.js, scripts/bench/run-bench.sh, scripts/bench/drain-stats.js, docs/benchmark.md
- FOUND: 93e6aec, 53b114e, 439aa03
- `git diff --quiet HEAD -- docker-compose.yml` sai com 0; `git status --porcelain target/` vazio

---
*Phase: 04-observabilidade-demo-e-documenta-o*
*Completed: 2026-11-01*
