---
phase: 04-observabilidade-demo-e-documenta-o
verified: 2026-11-02T14:10:00Z
status: passed
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
  - docs/img/dashboard-k6.png
  - docs/img/rabbitmq-filas.png
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
covered_digest: "v1:sha256:f58cc739b0989d337f146502cfd1eb8ecf88e69893b89033cd399985f5e4c6f8"
behavior_unverified: 0
overrides_applied: 0
re_verification:
  previous_status: human_needed
  previous_score: 38/38
  gaps_closed:
    - "G-04-1: docs/img/dashboard-k6.png e docs/img/rabbitmq-filas.png existem, versionadas, e renderizam no README (linha 81) e no docs/demo.md (linhas 72 e 86) — 68cbb38"
    - "7 itens de verificação humana do relatório anterior confirmados na UAT (04-UAT.md, status complete, 7/7 pass)"
  gaps_remaining: []
  regressions: []
---

# Phase 4: Observabilidade, demo e documentação — Verification Report

**Phase Goal:** Quem avalia o projeto roda um único `docker compose up --build` e o script k6, vê 1000 notificações fluindo no Grafana (retries, DLQs, circuit breaker) e lê no README em pt-BR a arquitetura, os trade-offs e os números do benchmark de virtual threads
**Verified:** 2026-11-02T14:10:00Z
**Status:** passed
**Re-verification:** Sim — o relatório anterior (2026-11-02T00:35:00Z, `human_needed`, 38/38) ficou desatualizado porque os commits efa89d3 e 68cbb38 mexeram em arquivos cobertos, e a UAT humana foi concluída depois dele.

Nota de processo: a fase está marcada `Mode: mvp`, mas o Goal não está no formato de user story. Segui o mesmo caminho das verificações anteriores: goal-backward padrão, sem a tabela "User Flow Coverage".

## Escopo da reverificação

`git diff --stat ad2754e HEAD -- . ':!.planning'` mostra que, desde o fim da execução da fase, só 4 arquivos fora de `.planning/` mudaram:

| Arquivo | Commit | Mudança |
|---------|--------|---------|
| `observability/grafana/dashboards/notify-hub.json` | efa89d3 | Painel "Circuit breaker por canal" (id 15): `color.mode` de `thresholds` para `fixed` + `fixedColor: text`, para o Grafana 12.4 não trocar o valor pelo rótulo da faixa ("-∞+") antes dos value mappings |
| `docs/demo.md` | efa89d3 | Parágrafo do CB ganhou a explicação do Half-open que persiste sem tráfego |
| `docs/img/dashboard-k6.png` | 68cbb38 | Nova (PNG 1920x2653) |
| `docs/img/rabbitmq-filas.png` | 68cbb38 | Nova (PNG 1106x473) |

Os demais 42 arquivos cobertos não têm diff desde a verificação anterior, então as evidências dela continuam válidas para eles (checagem de regressão: sem alteração). Os 4 arquivos alterados receberam verificação completa abaixo.

## Goal Achievement

### Observable Truths — critérios do roadmap

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Num clone limpo, um único `docker compose up --build` sobe API, worker, RabbitMQ, MongoDB, Prometheus e Grafana sem passo manual nem JDK local | ✓ VERIFIED | Sem diff em `docker-compose.yml`, Dockerfiles e `.dockerignore` desde a verificação anterior (compose com 6 serviços + k6 em profile bench, multi-stage `maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre`, healthchecks + `service_healthy`). O item humano "clone numa máquina sem cache do BuildKit" foi confirmado na UAT (teste 7: pass, k6 com `novas=1000`) |
| 2 | O Grafana abre com datasource e dashboard provisionados, mostrando throughput, resultados por canal, retries por nível, profundidade das DLQs, p95 e estado do CB, alimentados pelo Prometheus coletando API, worker e RabbitMQ | ✓ VERIFIED | Dashboard revalidado depois de efa89d3: `JSON.parse` OK, título `Notify Hub`, uid `notify-hub`, 24 painéis, 5 rows (Visão geral, Entrega por canal, Retry e DLQ, Resiliência, API e JVM), 21 targets, 0 sem datasource `prometheus`. Painel do CB: `state-timeline`, `mergeValues: true`, mappings 0→Fechado/verde, 1→Aberto/vermelho, 2→Half-open/amarelo intactos, expr inalterada. A screenshot versionada `docs/img/dashboard-k6.png` mostra as 5 rows com dados e a state timeline com os rótulos "Fechado", "Half-open" e "Aberto" nas cores certas (a correção funciona). UAT teste 2: pass |
| 3 | O k6 dispara 1000 notificações com mix de canais e destinatários mágicos, o dashboard reflete a carga e ao final o script imprime resumo de métricas e contagens por status | ✓ VERIFIED | `k6/demo.js` sem diff (shared-iterations, 50 VUs, 1000 iterações, thresholds, resumo via `/notifications/stats`). Screenshot: "Aceitas na janela" 1K, ondas em retries por nível, DLQs crescendo, recusas `circuit_open`, publish `confirmed`. `docs/img/rabbitmq-filas.png` mostra as 15 filas (principais, `retry.1s/5s/25s`, `.dlq`) com mensagens nas DLQs. UAT testes 1, 2 e 7: pass |
| 4 | O README mostra os resultados do benchmark VT x plataforma com o método para reproduzi-lo | ✓ VERIFIED | README e `docs/benchmark.md` sem diff. Números já recalculados do CSV no relatório anterior (744.4/786.2 msg/s, 718/844 MiB). UAT teste 5 (afirmações batem com o medido): pass |
| 5 | README e toda a documentação em pt-BR, com Mermaid, passo a passo da demo, índices com `explain()` e trade-offs | ✓ VERIFIED | Docs sem diff exceto um parágrafo de `docs/demo.md` (pt-BR, coerente com a config: `permitted-number-of-calls-in-half-open-state: 3` em `notify-worker/src/main/resources/application.yml:105` e `wait-duration-in-open-state: 500ms` no `application-demo.yml`). As 3 referências de imagem agora resolvem: `README.md:81` → `docs/img/dashboard-k6.png`, `docs/demo.md:72` → `img/dashboard-k6.png`, `docs/demo.md:86` → `img/rabbitmq-filas.png`, as duas em `git ls-files`. UAT testes 3 (Mermaid no GitHub) e 4 (leitura do README): pass |

**Score:** 5/5 critérios do roadmap; 33/33 truths dos planos (0 presentes sem comportamento verificado)

### Truths dos planos (resumo)

| Plano | Truths | Status | Notas |
|-------|--------|--------|-------|
| 04-01 | 7 | ✓ 7/7 | Sem diff nos artefatos; evidência anterior mantida |
| 04-02 | 6 | ✓ 6/6 | Sem diff; testes PublishMetricsTest 13/13 e NotificationRepublisherTest 25/25 da verificação anterior continuam válidos para o mesmo código |
| 04-03 | 7 | ✓ 7/7 | Dashboard revalidado depois de efa89d3 (ver critério 2) |
| 04-04 | 6 | ✓ 6/6 | Sem diff |
| 04-05 | 7 | ✓ 7/7 | Screenshots agora versionadas; links resolvem |

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `notify-worker/Dockerfile`, `notify-api/Dockerfile`, `.dockerignore`, `docker-compose.yml` | Build multi-stage, stack completo | ✓ VERIFIED | Sem diff |
| `observability/prometheus/prometheus.yml`, `observability/grafana/provisioning/**` | 4 jobs, datasource, provider read-only | ✓ VERIFIED | Sem diff |
| `observability/grafana/dashboards/notify-hub.json` | Dashboard 5 rows, 21 targets | ✓ VERIFIED | JSON válido depois de efa89d3; só a cor do painel 15 mudou |
| `PublishMetrics.java` (+ testes), `NotificationRepublisher.java`, `application.yml`, `pom.xml` da API | Contador de publish, Prometheus | ✓ VERIFIED | Sem diff |
| `k6/demo.js`, `k6/bench-backlog.js`, `scripts/**`, `docker-compose.bench.yml` | Demo e benchmark | ✓ VERIFIED | Sem diff |
| `README.md`, `docs/*.md`, `scripts/explain-indices.js` | Documentação pt-BR | ✓ VERIFIED | `docs/demo.md` com um parágrafo novo, ver achados menores |
| `docs/img/dashboard-k6.png` | Dashboard durante o k6 | ✓ VERIFIED | PNG 1920x2653, conteúdo conferido visualmente: 5 rows com dados, CB com Fechado/Half-open/Aberto |
| `docs/img/rabbitmq-filas.png` | Filas de delay e DLQs | ✓ VERIFIED | PNG 1106x473, 15 filas classic com features TTL/DLX/DLK, DLQs com 91/84/265 mensagens |

### Key Link Verification

Links da verificação anterior (16/16 WIRED) sem regressão, porque os arquivos de origem e destino não mudaram, com duas exceções reconferidas:

| From | To | Via | Status |
|------|----|-----|--------|
| Painel 15 do dashboard | datasource uid `prometheus` | `datasource` do painel e do target | ✓ WIRED |
| `README.md:81`, `docs/demo.md:72,86` | `docs/img/*.png` | Markdown image links | ✓ WIRED (antes quebrado, G-04-1) |

### Data-Flow Trace (Level 4)

| Artifact | Data | Source | Produz dado real | Status |
|----------|------|--------|------------------|--------|
| Dashboard Notify Hub (21 targets) | séries PromQL | Prometheus ← api/worker/rabbitmq | Sim: queries sem mudança desde a execução no Prometheus (21/21 com séries); screenshot mostra todos os painéis com dados | ✓ FLOWING |
| Painel do CB | `resilience4j_circuitbreaker_state` | worker (instâncias email, sms, webhook) | Sim: screenshot mostra as 3 séries | ✓ FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Dashboard é JSON válido e consistente | `node -e JSON.parse(...)` + contagem de rows/targets/datasource | válido, 5 rows, 21 targets, 0 sem datasource | ✓ PASS |
| Imagens versionadas e válidas | `git ls-files docs/img` + `file docs/img/*.png` | 2 PNGs válidos + `.gitkeep` | ✓ PASS |
| Grafana ao vivo com o painel corrigido | `curl :3000/api/dashboards/uid/notify-hub` | stack desligado nesta máquina | ? SKIP (coberto pela screenshot e pela UAT teste 2) |

### Probe Execution

Step 7c: não se aplica. A fase não declara `scripts/*/tests/probe-*.sh`.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| OBS-02 | 04-01, 04-02 | Prometheus coleta API, worker e RabbitMQ | ✓ SATISFIED | Sem regressão |
| OBS-03 | 04-03 | Grafana provisionado com throughput, resultados, retries, DLQs, p95, CB | ✓ SATISFIED | Critério 2, painel do CB corrigido e confirmado |
| DEM-01 | 04-01, 04-05 | Dockerfiles multi-stage e um único `docker compose up --build` | ✓ SATISFIED | Critério 1, UAT teste 7 |
| DEM-02 | 04-03 | k6 com 1000 notificações e resumo | ✓ SATISFIED | Critério 3 |
| DEM-03 | 04-04 | Benchmark VT x plataforma, resultados no README | ✓ SATISFIED | Critério 4, UAT teste 5 |
| DOC-01 | 04-05 | Visão geral, Mermaid, passo a passo | ✓ SATISFIED | UAT teste 3; screenshots presentes |
| DOC-02 | 04-05 | Índices com ESR e `explain()` | ✓ SATISFIED | Sem regressão |
| DOC-03 | 04-05 | Trade-offs obrigatórios | ✓ SATISFIED | Sem regressão |
| DOC-04 | 04-05 | Toda a documentação em pt-BR | ✓ SATISFIED | Parágrafo novo em pt-BR |

Os 9 IDs aparecem nos `requirements` dos planos e estão marcados `Complete` para a Phase 4 no REQUIREMENTS.md. Nenhum requisito órfão.

### Prohibitions (nível judgment)

| Plano | Proibição | Status |
|-------|-----------|--------|
| 04-03 | Sem dados de contato reais no k6/demo | Resolvida por humano (UAT teste 6: pass) |
| 04-04 | Sem números/conclusões fora do protocolo | Resolvida por humano (UAT testes 5 e 6: pass) |
| 04-04 | Sem dados reais no backlog | Resolvida por humano (UAT teste 6: pass) |
| 04-05 | Sem garantias acima do implementado | Resolvida por humano (UAT teste 6: pass) |

Nenhuma proibição de nível test. O parágrafo novo de `docs/demo.md` não traz dados de contato nem garantias novas.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `docs/demo.md`, `notify-hub.json` | — | TBD/FIXME/XXX/TODO/HACK | — | Nenhum marcador |
| `docs/demo.md` | 67 | Texto diz que "Circuit breaker por canal" mostra "o estado do CB do webhook", mas a query do painel (`max by (name) (resilience4j_circuitbreaker_state{state="open"}) + 2 * max by (name) (...{state="half_open"})`) não filtra por `name` e mostra os 3 canais. A screenshot e a `description` do painel ("Estado do circuit breaker ... de cada canal") confirmam: email, sms e webhook | ⚠️ Warning (menor) | Inconsistência no texto do roteiro; não afeta o dashboard nem a truth. Correção sugerida: "mostra o estado do CB de cada canal (email, sms e webhook)" |
| `docs/demo.md` | 57 | "O dashboard tem quatro rows", mas o dashboard e a lista logo abaixo têm 5 (já apontado no relatório anterior, ainda não corrigido) | ⚠️ Warning (menor) | Correção de uma palavra |
| `docs/demo.md` | 5, 104-106 | `curl.exe -d '{"..."}'` quebra no Windows PowerShell 5.1 (WR-04, carregado) | ⚠️ Warning | Usabilidade do roteiro |
| `NotificationRepublisher.java` | 316-330 | Semântica de `publish_failed/unconfirmed` (WR-01, carregado) | ⚠️ Warning | Fora do fluxo nominal da demo |
| `k6/demo.js` | 123-150 | Delta global pode declarar "drenado" cedo após rodada interrompida (WR-03, carregado) | ⚠️ Warning | Caso de borda |
| `notify-hub.json` | 143 | "Taxa de sucesso" = NaN num stack novo (IN-03, carregado) | ℹ️ Info | Até o primeiro desfecho |
| `README.md` / `docs/demo.md` | 74 / 25 | "admin/admin para editar" com dashboard read-only (IN-05, carregado) | ℹ️ Info | Só "Save as" |

O WR-02 (imagens quebradas) do relatório anterior foi resolvido por 68cbb38. Nenhum dos warnings invalida uma truth.

### Human Verification Required

Nenhum item pendente. Os 7 itens do relatório anterior foram confirmados pelo usuário na UAT (`04-UAT.md`, status `complete`, 7/7 pass, 0 issues; G-04-1 resolvido por 68cbb38). A correção de efa89d3 (cor do painel do CB) foi feita antes da captura das screenshots e da conclusão da UAT, e a screenshot versionada mostra o painel renderizando os estados corretamente, então não precisa de nova checagem visual.

### Gaps Summary

Nenhum gap. Os 5 critérios do roadmap e as 33 truths dos planos continuam com evidência. Só 4 arquivos mudaram desde a verificação anterior: o dashboard continua JSON válido e consistente (só a cor do painel do CB mudou, e os value mappings continuam definindo texto e cor), o parágrafo novo do `docs/demo.md` bate com a config do Resilience4j, e as duas screenshots existem e resolvem os 3 links. A UAT humana fechou todos os itens visuais e de julgamento. Achados menores que não bloqueiam e podem ser corrigidos num `/gsd-quick`: `docs/demo.md:67` diz "o estado do CB do webhook", mas o painel mostra os 3 canais, e `docs/demo.md:57` ainda diz "quatro rows" em vez de cinco.

---

_Verified: 2026-11-02T14:10:00Z_
_Verifier: Claude (gsd-verifier)_
