---
phase: 04-observabilidade-demo-e-documenta-o
verified: 2026-11-02T00:35:00Z
status: human_needed
score: 38/38 must-haves verified (5/5 critérios do roadmap + 33/33 truths dos planos)
covered_files:
  - .dockerignore
  - .planning/REQUIREMENTS.md
  - .planning/phases/04-observabilidade-demo-e-documenta-o/04-01-PLAN.md
  - .planning/phases/04-observabilidade-demo-e-documenta-o/04-01-SUMMARY.md
  - .planning/phases/04-observabilidade-demo-e-documenta-o/04-02-PLAN.md
  - .planning/phases/04-observabilidade-demo-e-documenta-o/04-02-SUMMARY.md
  - .planning/phases/04-observabilidade-demo-e-documenta-o/04-03-PLAN.md
  - .planning/phases/04-observabilidade-demo-e-documenta-o/04-03-SUMMARY.md
  - .planning/phases/04-observabilidade-demo-e-documenta-o/04-04-PLAN.md
  - .planning/phases/04-observabilidade-demo-e-documenta-o/04-04-SUMMARY.md
  - .planning/phases/04-observabilidade-demo-e-documenta-o/04-05-PLAN.md
  - .planning/phases/04-observabilidade-demo-e-documenta-o/04-05-SUMMARY.md
  - README.md
  - docker-compose.bench.yml
  - docker-compose.yml
  - docs/arquitetura.md
  - docs/benchmark.md
  - docs/demo.md
  - docs/modelagem-mongo.md
  - docs/trade-offs.md
  - k6/bench-backlog.js
  - k6/demo.js
  - notify-api/Dockerfile
  - notify-api/pom.xml
  - notify-api/src/main/java/dev/notifyhub/api/application/NotificationRepublisher.java
  - notify-api/src/main/java/dev/notifyhub/api/metrics/PublishMetrics.java
  - notify-api/src/main/resources/application.yml
  - notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java
  - notify-api/src/test/java/dev/notifyhub/api/application/NotificationServiceTest.java
  - notify-api/src/test/java/dev/notifyhub/api/application/UnconfirmedPublishSweeperTest.java
  - notify-api/src/test/java/dev/notifyhub/api/metrics/PublishMetricsTest.java
  - notify-api/src/test/java/dev/notifyhub/api/web/NotificationApiFlowTest.java
  - notify-api/src/test/java/dev/notifyhub/api/web/NotificationValidationTest.java
  - notify-worker/Dockerfile
  - observability/grafana/dashboards/notify-hub.json
  - observability/grafana/provisioning/dashboards/notify-hub.yaml
  - observability/grafana/provisioning/datasources/prometheus.yaml
  - observability/prometheus/prometheus.yml
  - scripts/bench/drain-stats.js
  - scripts/bench/run-bench.sh
  - scripts/explain-indices.js
  - scripts/smoke-fase1.sh
  - scripts/smoke-fase2.sh
  - scripts/smoke-fase4.sh
covered_digest: "v1:sha256:0943286ecfc7535fe327dd00456241b94765275d127714e5bbcfddb4495d1123"
behavior_unverified: 0
overrides_applied: 0
human_verification:
  - test: "Capturar docs/img/dashboard-k6.png (dashboard Notify Hub durante `docker compose --profile bench run --rm k6`, com CB/recusas/retries/DLQ visíveis) e docs/img/rabbitmq-filas.png (aba Queues and Streams com filas de delay e DLQs) e versionar os dois"
    expected: "As duas imagens existem em docs/img/ e renderizam no README (linha 81) e no docs/demo.md (linhas 72 e 86)"
    why_human: "Hoje docs/img/ só tem .gitkeep (git ls-files), então a vitrine mostra imagem quebrada no GitHub. A captura foi deixada de propósito para o checkpoint humano (D-10)"
  - test: "Abrir http://localhost:3000 sem login e acompanhar o dashboard durante uma rodada do k6"
    expected: "As 5 rows renderizam com dados; a state timeline do CB usa verde/vermelho/amarelo; recusas circuit_open, ondas de retry por nível e DLQs crescendo aparecem; nenhum painel fica 'No data'. Conferir também o stat 'Taxa de sucesso' num stack recém-subido (pode mostrar NaN antes do primeiro desfecho, IN-03), o que contradiz o 'nunca mostrar No data' do README"
    why_human: "Aparência, cores e legibilidade dos painéis não são verificáveis por API; as 21 expressões foram validadas no Prometheus, mas não a renderização"
  - test: "Abrir README.md e docs/arquitetura.md no GitHub (depois do push)"
    expected: "Os 3 diagramas Mermaid (flowchart de arquitetura, flowchart da topologia com arestas pontilhadas e stateDiagram-v2 com 16 transições) renderizam sem erro"
    why_human: "Não há renderizador Mermaid no ambiente de verificação"
  - test: "Leitura de um minuto do topo do README como recrutador"
    expected: "Pitch, o que demonstra e como rodar ficam claros sem rolar demais"
    why_human: "Tom e clareza são julgamento humano (D-09)"
  - test: "Conferir que toda afirmação de docs/benchmark.md e do README sobre o benchmark bate com target/bench/resumo.txt e resultados.csv (proibição de transparência, nível judgment)"
    expected: "Nenhuma conclusão além do medido. O verificador recalculou: medianas 744.4/786.2 msg/s, RSS 718/844 MiB, diferença de 41.8 msg/s (~5%), +126/+347 MiB, as 10 rodadas da tabela iguais ao CSV, 7 eventos JFR em basicAck; leitura de empate coerente com a dispersão. Veredito do LLM não é autoritativo"
    why_human: "Proibições de nível judgment exigem resolução humana explícita (ADR-550 D4)"
  - test: "Resolver as proibições de nível judgment marcadas como unresolved/flagged nos planos: privacidade (04-03 k6 demo, 04-04 backlog), transparência do benchmark (04-04) e garantias da documentação (04-05)"
    expected: "Humano confirma: só example.com, fail.test, +55119.../+5511... e +5500... nos scripts e docs; nenhuma afirmação de exactly-once/'nunca duplica'/'entrega garantida' sem a ressalva at-least-once; profile demo declarado como não pronto para produção (docs/trade-offs.md, parágrafo de abertura)"
    why_human: "Veredito do LLM (nenhuma violação encontrada por grep e leitura) é não autoritativo; a decisão final é humana"
  - test: "(Recomendado) Clonar o repositório numa máquina ou VM sem cache do BuildKit e rodar `docker compose up --build` e `docker compose --profile bench run --rm k6`"
    expected: "Sobe tudo sem JDK nem passo manual e o k6 imprime o resumo com novas=1000"
    why_human: "O clone limpo do executor foi local e compartilhou o cache `id=notify-m2` do BuildKit e as imagens base; não foi possível reproduzir sem mutar o estado da máquina"
---

# Phase 4: Observabilidade, demo e documentação — Verification Report

**Phase Goal:** Quem avalia o projeto roda um único `docker compose up --build` e o script k6, vê 1000 notificações fluindo no Grafana (retries, DLQs, circuit breaker) e lê no README em pt-BR a arquitetura, os trade-offs e os números do benchmark de virtual threads
**Verified:** 2026-11-02T00:35:00Z
**Status:** human_needed
**Re-verification:** No — verificação inicial

Nota de processo: a fase está marcada `Mode: mvp`, mas o Goal não está no formato de user story (`user-story.validate` → `false`). Segui o mesmo caminho das verificações anteriores (Fase 3): goal-backward padrão, sem a tabela "User Flow Coverage".

Evidência própria do verificador (não narrativa dos SUMMARYs): sondagem só leitura do stack `notify-hub` no ar (Prometheus, Grafana, API, RabbitMQ, Mongo), execução das 21 expressões do dashboard no Prometheus, execução do `scripts/explain-indices.js` (só leitura), recálculo das medianas a partir de `target/bench/resultados.csv`, `./mvnw -pl notify-api -am -Dtest=PublishMetricsTest,NotificationRepublisherTest test` (13 + 25 testes, 0 falhas) e `gsd-tools verify.artifacts`/`verify.key-links` (28/28 artefatos, 16/16 links).

## Goal Achievement

### Observable Truths — critérios do roadmap

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Num clone limpo, um único `docker compose up --build` sobe API, worker, RabbitMQ, MongoDB, Prometheus e Grafana sem passo manual nem JDK local | ✓ VERIFIED | `docker-compose.yml` tem os 6 serviços + k6 (profile bench); api/worker com `build:` em Dockerfiles multi-stage `FROM maven:3.9-eclipse-temurin-21 AS build` → `eclipse-temurin:21-jre`; healthchecks de readiness e `depends_on: service_healthy`; todos os arquivos necessários estão no git (`git ls-files`), e o build só usa POMs + `src` (nada fora do contexto). Stack atual no ar: 6 containers, api/worker/rabbitmq/mongo `healthy`. Clone limpo local (`notify-clean`) relatado pelo executor e pelo orquestrador; reprodução numa máquina sem cache fica como item humano recomendado |
| 2 | O Grafana abre com datasource e dashboard provisionados, mostrando throughput, resultados por canal, retries por nível, profundidade das DLQs, p95 e estado do CB, alimentados pelo Prometheus coletando API, worker e RabbitMQ | ✓ VERIFIED | `GET /api/dashboards/home` anônimo → `Notify Hub`, uid `notify-hub`, `canEdit:false`, refresh 5s, `now-15m`; 5 rows na ordem do D-01; datasource uid `prometheus`, `http://prometheus:9090`, `timeInterval: 5s`; proxy do Grafana responde `success`. Prometheus `up`=1 para `api`, `worker`, `rabbitmq`, `rabbitmq-queues`. As 21 expressões rodaram no Prometheus: 21× `success`, todas com séries não vazias, 0 sem datasource. Mapeamento da state timeline: 0 verde, 1 vermelho, 2 amarelo. Renderização visual → item humano |
| 3 | O k6 dispara 1000 notificações com mix de canais e destinatários mágicos, o dashboard reflete a carga e ao final o script imprime resumo de métricas e contagens por status | ✓ VERIFIED | `k6/demo.js`: `shared-iterations`, 50 VUs, 1000 iterações, mix determinístico de 3 canais × 3 templates × destinatários mágicos, thresholds `http_req_failed<0.01` e `checks>0.99`, resumo por status e canal via `/notifications/stats`. `target/smoke/k6.log`: `novas=1000`, `PENDING=0 PROCESSING=0 RETRYING=0`, contagens por canal, checks 1000/1000, `http_req_failed` 0%. Prometheus ao vivo (mesma vida do container): `notify_publish_outcomes_total{confirmed,none}`=1001, 7 séries; `circuit_open`=3905; retries por nível 1351/1253/1109; DLQs 241/217/697; CB `open` atingiu 1 nos 3 canais nas últimas 3h |
| 4 | O README mostra os resultados do benchmark VT x plataforma (só o tipo de thread varia, concorrência alta igual, latência bloqueante, aquecimento, várias execuções) com o método para reproduzi-lo | ✓ VERIFIED | README seção "Benchmark" com medianas 744.4/786.2 msg/s, threads 40/636, memória 718/844 MiB, leitura de empate e link para `docs/benchmark.md` (método, ambiente, todas as rodadas, JFR, limitações, "Como reproduzir", saída bruta verbatim). Recalculado do CSV: medianas e RSS batem; `resumo.txt` colado idêntico. `docker-compose.bench.yml` só muda `VIRTUAL_THREADS` entre modos; o pico de 636 = 3×200+36 prova que a concorrência foi aplicada; `execucao.exit`=0 e `BENCH: OK` |
| 5 | README e toda a documentação em pt-BR, com Mermaid (arquitetura, topologia, ciclo de vida), passo a passo da demo, índices com `explain()` e trade-offs (sem outbox, at-least-once, Boot 3.5 fora do OSS, sem jitter) | ✓ VERIFIED | Estrutura "README vitrine + docs/" escolhida pelo usuário na discussão (D-07, DISCUSSION-LOG: "README vitrine + docs/ ✓" contra "README único e longo"). README: Mermaid de arquitetura + links. `docs/arquitetura.md`: topologia em flowchart e `stateDiagram-v2` com as 16 transições idênticas à tabela de `NotificationStatus`. `docs/demo.md`: roteiro de 8 passos. `docs/modelagem-mongo.md`: 5 índices com ESR iguais ao `MongoIndexInitializer`; saída do explain reexecutada pelo verificador e idêntica, linha a linha, à colada. `docs/trade-offs.md`: tabela de 12 itens incluindo os 4 obrigatórios + próximos passos. Grep sem fases, D-xx, WR/CR, gap closures; única menção a `.planning/` é a linha final do README |

**Score:** 5/5 critérios do roadmap; 33/33 truths dos planos (0 presentes sem comportamento verificado)

### Truths dos planos (resumo)

| Plano | Truths | Status | Notas |
|-------|--------|--------|-------|
| 04-01 | 7 | ✓ 7/7 | Ao vivo: `up`=1 nos 4 jobs; `rabbitmq_detailed_queue_messages` com rótulo `queue` (18 filas); `/actuator/prometheus` 200 com 69 linhas `http_server_requests_seconds_bucket ... uri="/notifications"`; exposição `health,info,prometheus`; uid 999 e PID 1 = `java -XX:MaxRAMPercentage=75 -jar /app/app.jar` nos dois containers, `/app` só com `app.jar`; todas as portas em `127.0.0.1`. Smokes 1/2: lógica `compose stop api worker` + `up rabbitmq mongo` conferida no código, não reexecutados (parariam a demo); `./mvnw verify` verde pelo gate do orquestrador |
| 04-02 | 6 | ✓ 6/6 | `PublishMetrics` pré-registra 7 séries; switch exaustivo de `PublishResult.Outcome`; chamadas em `NotificationRepublisher` linhas 175/181/199/204/329; `NotificationPublisher` e `DlqReprocessService` sem diff desde o início da fase. Comportamento coberto por testes executados pelo verificador (PublishMetricsTest 13/13, NotificationRepublisherTest 25/25), incluindo Mongo fora → nenhum incremento |
| 04-03 | 7 | ✓ 7/7 | Ver critérios 2 e 3. `application-demo.yml` do worker sem diff (CB 500ms) |
| 04-04 | 6 | ✓ 6/6 | Override só de ENV, projeto `-p notify-bench`, backlog validado (0 PUBLISH_FAILED, `run-bench.sh:177-181`), aquecimento W descartado em `drain-stats.js`, 5 rodadas intercaladas, sanidade do modo com `falhou` (`run-bench.sh:344-345`), `docs/benchmark.md` com as seções exigidas |
| 04-05 | 7 | ✓ 7/7 | Ver critérios 1 e 5. Links das screenshots prontos, imagens pendentes (D-10, item humano) |

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `notify-worker/Dockerfile`, `notify-api/Dockerfile` | Multi-stage, `*-exec.jar`, usuário app | ✓ VERIFIED | Conteúdo e containers ao vivo conferidos |
| `.dockerignore` | Exclui .git/.planning/.gsd/target | ✓ VERIFIED | |
| `docker-compose.yml` | api, worker, prometheus, grafana, k6 (bench), loopback | ✓ VERIFIED | |
| `observability/prometheus/prometheus.yml` | 4 jobs, 5s, `/metrics/detailed` + `queue_coarse_metrics` | ✓ VERIFIED | |
| `notify-api/src/main/resources/application.yml`, `notify-api/pom.xml` | Prometheus + histograma HTTP | ✓ VERIFIED | |
| `PublishMetrics.java` (+ teste) | 7 séries fechadas | ✓ VERIFIED | WR-01 afeta a semântica de `unconfirmed`, não o contrato do plano |
| `observability/grafana/**` | Datasource, provider read-only, dashboard 5 rows | ✓ VERIFIED | |
| `k6/demo.js`, `k6/bench-backlog.js` | 1000 POSTs / backlog W+N | ✓ VERIFIED | |
| `scripts/smoke-fase4.sh` | 12 etapas, `SMOKE FASE 4: OK` | ✓ VERIFIED | |
| `docker-compose.bench.yml`, `scripts/bench/*` | Protocolo do benchmark | ✓ VERIFIED | |
| `README.md`, `docs/*.md`, `scripts/explain-indices.js` | Documentação pt-BR | ✓ VERIFIED | `docs/img/` só com `.gitkeep` (por desenho) |

`gsd-tools verify.artifacts`: 28/28 aprovados nos 5 planos.

### Key Link Verification

`gsd-tools verify.key-links`: 16/16 WIRED. Conferidos à mão: dashboard → datasource uid `prometheus` (todos os painéis e targets); compose → `GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH` (home anônima é o Notify Hub); dashboard → `notify_publish_outcomes_total` (7 séries ao vivo); `run-bench.sh` → `docker-compose.bench.yml` e `drain-stats.js`; README → medianas de `docs/benchmark.md`; `docs/arquitetura.md` → transições de `NotificationStatus` (16/16); `docs/modelagem-mongo.md` → saída de `explain-indices.js` (idêntica na reexecução).

### Data-Flow Trace (Level 4)

| Artifact | Data | Source | Produz dado real | Status |
|----------|------|--------|------------------|--------|
| Dashboard Notify Hub (21 targets) | séries PromQL | Prometheus ← api/worker/rabbitmq | Sim: as 21 queries devolvem séries não vazias | ✓ FLOWING |
| Painel "Resultado do publish" | `notify_publish_outcomes_total` | `PublishMetrics` ← `NotificationRepublisher` | Sim: confirmed/none=1001 depois do k6 | ✓ FLOWING |
| Resumo do k6 | `/notifications/stats` | `$group` no Mongo | Sim: `novas=1000` e contagens por canal | ✓ FLOWING |
| README/benchmark.md | números | `target/bench/resumo.txt` / CSV | Sim: recalculado | ✓ FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Prometheus coleta os 4 alvos | `curl :9090/api/v1/query?query=up` | 4 séries com valor 1 | ✓ PASS |
| Grafana home anônima | `curl :3000/api/dashboards/home` | Notify Hub, canEdit=false, 5 rows, 21 targets | ✓ PASS |
| Expressões do dashboard válidas | script node que executa cada expr no Prometheus | exprs 21, bad 0, noDatasource 0 | ✓ PASS |
| Contador de publish | PublishMetricsTest + NotificationRepublisherTest | 13/13 e 25/25 | ✓ PASS |
| Explain dos índices | `docker compose exec -T mongo mongosh ... explain-indices.js` | 9 linhas EXPLAIN idênticas às do doc, nenhum SORT | ✓ PASS |
| Endpoints do README | curl Swagger, RabbitMQ, Prometheus ready, Grafana health | 200 em todos | ✓ PASS |
| Containers não root | `docker compose exec api/worker id -u` + `/proc/1/cmdline` | 999, java como PID 1 | ✓ PASS |
| k6 + smoke completo | não reexecutado (mutaria o estado) | evidência: `target/smoke/k6.log` + contadores ao vivo | ? SKIP (evidência indireta forte) |
| Benchmark | proibido pelo orquestrador | evidência: `target/bench/*` | ? SKIP |

### Probe Execution

Step 7c: não aplicável — a fase não declara `scripts/*/tests/probe-*.sh`. Os smokes (`scripts/smoke-fase4.sh`) não foram reexecutados porque sobem/derrubam containers e disparam carga.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| OBS-02 | 04-01, 04-02 | Prometheus coleta API, worker e RabbitMQ | ✓ SATISFIED | 4 jobs com up=1, métricas por fila, contador de publish |
| OBS-03 | 04-03 | Grafana provisionado com throughput, resultados, retries, DLQs, p95, CB | ✓ SATISFIED | Critério 2 |
| DEM-01 | 04-01, 04-05 | Dockerfiles multi-stage e um único `docker compose up --build` | ✓ SATISFIED | Critério 1 |
| DEM-02 | 04-03 | k6 com 1000 notificações e resumo | ✓ SATISFIED | Critério 3 |
| DEM-03 | 04-04 | Benchmark VT x plataforma, resultados no README | ✓ SATISFIED | Critério 4 |
| DOC-01 | 04-05 | Visão geral, Mermaid (arquitetura, topologia, ciclo de vida), passo a passo | ✓ SATISFIED | README + `docs/arquitetura.md` + `docs/demo.md` (estrutura D-07 escolhida pelo usuário) |
| DOC-02 | 04-05 | Índices com ESR e `explain()`, modelo de documentos | ✓ SATISFIED | `docs/modelagem-mongo.md`, explain reproduzido |
| DOC-03 | 04-05 | Trade-offs obrigatórios | ✓ SATISFIED | `docs/trade-offs.md` |
| DOC-04 | 04-05 | Toda a documentação em pt-BR | ✓ SATISFIED | README e 5 docs em pt-BR |

Nenhum requisito órfão: os 9 IDs do REQUIREMENTS.md mapeados para a Fase 4 aparecem nos `requirements` dos planos.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| (todos os 37 arquivos alterados) | — | TBD/FIXME/XXX/TODO/HACK | — | Nenhum marcador encontrado |
| `README.md` / `docs/demo.md` | 81 / 72, 86 | Imagens linkadas inexistentes (WR-02) | ⚠️ Warning | Imagem quebrada na vitrine até a captura humana (D-10) |
| `NotificationRepublisher.java` | 316-330 | `publish_failed/unconfirmed` também conta publishes confirmados quando o worker fica >2 min sem reivindicar (WR-01) | ⚠️ Warning | Painel "Resultado do publish" engana durante queda do worker; não afeta a demo nominal nem o contrato do plano |
| `k6/demo.js` | 123-150 | Resumo por delta global pode declarar "drenado" cedo se uma rodada anterior interrompida ainda estiver drenando (WR-03) | ⚠️ Warning | Caso de borda; o smoke poderia passar com rodada não drenada. Fluxo nominal correto |
| `docs/demo.md` | 5, 104-106 | `curl.exe -d '{"..."}'` quebra no Windows PowerShell 5.1 (WR-04) | ⚠️ Warning | Passo 6 do roteiro falha para quem usa PowerShell 5.1 |
| `docs/demo.md` | 57 | "O dashboard tem quatro rows", mas lista e o dashboard têm 5 | ⚠️ Warning | Inconsistência no roteiro (correção de uma palavra) |
| `observability/grafana/dashboards/notify-hub.json` | 143 | "Taxa de sucesso" = 0/0 = NaN num stack novo (IN-03) | ℹ️ Info | Contradiz "nunca mostrar No data" do README até o primeiro desfecho |
| `README.md` / `docs/demo.md` | 74 / 25 | "admin/admin para editar", mas o dashboard é read-only (IN-05) | ℹ️ Info | Só "Save as" |
| Volume local do Mongo | — | 4 documentos PROCESSING abandonados (2026-10-12/13, de antes da recuperação por lease) | ℹ️ Info | Só nesta máquina; o k6 já desconta pela linha de base; não existe num clone limpo |
| Volume local do RabbitMQ | — | 18 filas (inclui `retry.125s` deixadas pelo smoke 1 no perfil padrão, IN-11) contra as 15 documentadas | ℹ️ Info | Só nesta máquina; some com `down -v` |

Os 4 warnings do 04-REVIEW foram avaliados contra os must-haves: nenhum invalida uma truth. WR-01 é semântica do rótulo (o plano fixou exatamente esse comportamento), WR-02 é a captura humana prevista, WR-03 é caso de borda fora do fluxo nominal e WR-04 é usabilidade do roteiro no PowerShell 5.1.

### Prohibitions (nível judgment)

| Plano | Proibição | Veredito do LLM (não autoritativo) | Status |
|-------|-----------|------------------------------------|--------|
| 04-03 | Sem dados de contato reais no k6/demo | Só `example.com`, `fail.test`, `+55119…` e `+5500…` | unverified-prohibition — human review recommended |
| 04-04 | Sem números/conclusões fora do protocolo | Todos os números batem com CSV/resumo; empate declarado | unverified-prohibition — human review recommended |
| 04-04 | Sem dados reais no backlog | `bench{i}@example.com`, `+5511{9…}`, `https://example.com/bench/{i}` | unverified-prohibition — human review recommended |
| 04-05 | Sem garantias acima do implementado | at-least-once explícito no README e trade-offs; "exactly-once" só citado como não oferecido; profile demo declarado não pronto para produção | unverified-prohibition — human review recommended |

### Human Verification Required

1. **Screenshots** — capturar e versionar `docs/img/dashboard-k6.png` e `docs/img/rabbitmq-filas.png`. Esperado: renderizam no README e no `docs/demo.md`. Por que humano: captura visual prevista no checkpoint D-10; hoje a vitrine tem imagem quebrada.
2. **Dashboard durante o k6** — abrir http://localhost:3000 e acompanhar uma rodada. Esperado: 5 rows com dados, cores do CB corretas, recusas/retries/DLQs reagindo; conferir o NaN da "Taxa de sucesso" num stack novo. Por que humano: renderização não é verificável por API.
3. **Mermaid no GitHub** — README e `docs/arquitetura.md`. Esperado: 3 diagramas renderizam. Por que humano: sem renderizador no ambiente.
4. **Leitura do topo do README como recrutador.** Por que humano: tom e clareza.
5. **Benchmark x medianas** — confirmar a leitura de empate e os números (o verificador já recalculou tudo e não achou divergência). Por que humano: proibição de transparência de nível judgment.
6. **Proibições judgment** — confirmar os 4 vereditos da tabela acima.
7. **(Recomendado) Clone numa máquina sem cache do BuildKit** — `docker compose up --build` + k6. Por que humano: o clone limpo do executor compartilhou o cache local.

### Gaps Summary

Nenhum gap bloqueante. Os 5 critérios do roadmap e as 33 truths dos planos têm evidência no código e no stack em execução: compose com build multi-stage, Prometheus coletando os 4 alvos, Grafana anônimo com as 21 expressões válidas e com dados, k6 com 1000 notificações e resumo, benchmark com números reproduzíveis a partir do CSV, e documentação pt-BR com Mermaid, explain real e trade-offs. O status é `human_needed` por causa dos itens visuais e de julgamento (screenshots, renderização do dashboard e do Mermaid, leitura do README e as proibições de nível judgment). Recomenda-se corrigir antes do ship os warnings baratos: "quatro rows" → "cinco rows" em `docs/demo.md`, a nota de PowerShell 5.1 (WR-04) e, se quiser, a guarda de linha de base instável no k6 (WR-03) e o rótulo de `unconfirmed` (WR-01).

---

_Verified: 2026-11-02T00:35:00Z_
_Verifier: Claude (gsd-verifier)_
