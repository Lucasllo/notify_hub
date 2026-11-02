---
status: resolved
trigger: "Painel \"Circuit breaker por canal\" (state-timeline) no dashboard Grafana mostra \"-∞+\" em vez dos estados mapeados Fechado/Aberto/Half-open"
created: 2026-11-02
updated: 2026-11-02T10:40
---

# Debug: grafana-cb-panel-inf

## Trigger

DATA_START
Painel "Circuit breaker por canal" (state-timeline) no dashboard Grafana observability/grafana/dashboards/notify-hub.json mostra "-∞+" nas três barras (email, sms, webhook), todas verdes, e na legenda, em vez dos estados mapeados Fechado/Aberto/Half-open. Esperado: vaivém CLOSED/HALF_OPEN do webhook durante o k6 (docs/demo.md seção 4). O painel "Recusas por segundo" mostra picos circuit_open na mesma rodada, então o CB abriu. Bloqueia o gap G-04-1 da UAT da fase 04.
DATA_END

## Symptoms

- expected: State-timeline shows per-channel CB state with mapped text/colors (0=Fechado green, 1=Aberto red, 2=Half-open yellow); webhook row alternates CLOSED/HALF_OPEN during the k6 demo run (docs/demo.md section 4).
- actual: All three rows (email, sms, webhook) are solid green with label "-∞+"; legend shows a single "-∞+" item. No Aberto/Half-open segments, even though "Recusas por segundo" shows circuit_open refusals in the same window. The CB panel's time axis also ends ~10:00:00 while sibling panels extend to ~10:01.
- errors: none reported in the UI.
- timeline: First observed 2026-11-02 while capturing docs/img/dashboard-k6.png for UAT gap G-04-1 (phase 04). Panel was introduced in plan 04-03; unknown whether it ever rendered mapped states.
- reproduction: `docker compose up --build -d`, open http://localhost:3000/d/notify-hub?kiosk&refresh=5s&from=now-5m&to=now, run `docker compose --profile bench run --rm k6`, look at row "Resiliência" → "Circuit breaker por canal".
- environment: Grafana 12.4.12 (classic JSON file provisioning), Prometheus v3.13.4 (scrape 5s), Resilience4j 2.4.0 via resilience4j-micrometer, Spring Boot 3.5.16, worker profile demo (CB wait-duration-in-open-state 500ms).
- panel config: type state-timeline; expr `max by (name) (resilience4j_circuitbreaker_state{state="open"}) + 2 * max by (name) (resilience4j_circuitbreaker_state{state="half_open"})`; legendFormat `{{name}}`; color mode thresholds with single green step (value null); value mappings type "value" for "0"/"1"/"2"; options mergeValues true, showValue auto.
- evidence: docs/img/dashboard-k6.png (uncommitted screenshot showing the symptom).

## Current Focus

- bug_class: Bohrbug (deterministic: same panel config always renders "-∞+")
- known_pattern_candidate: none (no knowledge-base.md yet; MemPalace not used)
- hypothesis: Grafana 12.4 state-timeline, when `mergeValues: true` AND `color.mode: "thresholds"`, replaces every numeric value with its threshold-bucket label (mergeThresholdValues) before value mappings run. With a single base step (value null = -Infinity) the only bucket label is "-∞+" (green), so mappings 0/1/2 are never applied and the legend lists "-∞+".
- test: render panel 15 (d-solo) with headless Edge before/after changing `color.mode` from "thresholds" to "fixed"; compare text/colors.
- expecting: before = "-∞+" green rows; after = Fechado/Half-open/Aberto segments with mapping colors.
- next_action: CONFIRMED + fix applied and self-verified. Awaiting human verify: user reloads http://localhost:3000/d/notify-hub, runs k6, confirms the CB row shows Fechado/Half-open/Aberto, then recaptures docs/img/dashboard-k6.png for G-04-1.
- reasoning_checkpoint:
    hypothesis: "Panel 15 shows '-∞+' because Grafana 12.4 state-timeline runs mergeThresholdValues whenever mergeValues=true AND color.mode=thresholds; that path turns each number into its threshold-bucket label (only bucket = '-∞+', green) and replaces field.display, so the 0/1/2 value mappings are never applied."
    confirming_evidence:
      - "Grafana 12.4.12 bundle 8706.*.js: prepareTimelineFields calls mergeThresholdValues on number fields when mergeValues && color.mode===Thresholds, with no mappings check; getThresholdItems labels a lone -Infinity step as '-∞+'."
      - "Prometheus returns exact 0/1/2 values including OPEN/HALF_OPEN for webhook in the window, yet the rendered panel (headless Edge, d-solo panelId=15) shows only '-∞+' green rows."
      - "Same panel JSON in a scratch dashboard with ONLY color.mode changed to fixed renders Fechado/Half-open/Aberto segments and legend; with ONLY mergeValues=false (thresholds kept) it also renders mapped colors and legend."
    falsification_test: "If the fixed-color variant still showed '-∞+' (or the mergeValues=false variant did), the threshold-merge path would not be the cause. Neither did."
    fix_rationale: "Switching color.mode away from 'thresholds' keeps the field numeric through prepareTimelineFields, so the standard display processor applies the value mappings (text+color); mergeValues stays true so consecutive equal states still merge into labelled segments. This removes the trigger condition instead of masking the label."
    blind_spots: "Not checked in Grafana 13 (project pins 12.4.12). Unmapped values (none possible from this expr) would render in fixedColor 'text'. FORCED_OPEN/DISABLED/METRICS_ONLY states map to 0 (Fechado) by the expr - pre-existing, out of scope."
    candidate_causes:
      - "config: dashboard JSON combination mergeValues=true + color.mode=thresholds (CONFIRMED)"
      - "data: PromQL returning non-integer/duplicated/missing series (ELIMINATED - exact 0/1/2, single instance)"
      - "environment: Grafana 12.4 behaviour change in value mapping (subsumed - the code path is by design, mappings simply not consulted in it)"
    and_gate: "yes - two conditions must hold simultaneously: mergeValues=true AND color.mode=thresholds. Removing either one fixes rendering (both variants verified). Root cause is the conjunction; fix removes the color-mode half to keep merged segments."

## Evidence

- timestamp: 2026-11-02T10:06
  checked: `docker compose ps` in project root
  found: all six services up (api, worker, mongo, rabbitmq healthy; grafana, prometheus up)
  implication: live Prometheus/Grafana available for evidence and verification

- timestamp: 2026-11-02T10:07
  checked: raw `resilience4j_circuitbreaker_state` instant query on Prometheus
  found: one series per (name, state) with labels instance="worker:8081", job="worker", name, state; states closed/disabled/forced_open/half_open/metrics_only/open; values 0/1. Single instance/job, no duplication.
  implication: `max by (name)` arithmetic has matching label sets on both sides; no instance duplication

- timestamp: 2026-11-02T10:08
  checked: panel expr via /api/v1/query_range, last 20 min, step 5s
  found: 3 series {name=email|sms|webhook}, 187 points each, values strictly in {"0","1","2"}. webhook transitions 09:55:30 ->2, 09:55:55 ->1, 09:56:00 ->2, 09:59:35 ->1, 09:59:45 ->0, 10:00:05 ->2 (131x half_open, 3x open, 53x closed). email/sms also hit half_open; email still at 2 now (idle CB stays HALF_OPEN without calls).
  implication: query returns exactly the integers the mappings expect; the data is correct and includes OPEN/HALF_OPEN states. Problem is in rendering, not in PromQL.

- timestamp: 2026-11-02T10:12
  checked: Grafana 12.4.12 frontend bundle public/build/8706.b5991decbfda6ecd85b1.js (prepareTimelineFields, minified `_t`, and mergeThresholdValues, minified `Xt`)
  found: `case number: if (mergeValues && field.config.color?.mode === Thresholds) { const G = mergeThresholdValues(field, theme); if (G) { push(G); continue } }` with NO check for value mappings. mergeThresholdValues builds labels via getThresholdItems (`It`): for a step with value -Infinity and no next step it emits `${format(-Infinity)}+` = "-∞+"; it rewrites the field to type string with values = threshold label and a custom `display` returning {text: label, color: thresholdColor} - mappings are bypassed.
  implication: config `mergeValues: true` + `color.mode: thresholds` + single green base step deterministically yields "-∞+" green for every value. Confirms hypothesis mechanism from source.

- timestamp: 2026-11-02T10:13
  checked: screenshot docs/img/dashboard-k6.png, CB panel axis vs "Recusas por segundo" axis
  found: CB panel ticks 09:56:00 (x~92, the plot left edge) ... 10:00:00 (x~384), 73 px/min, plot right edge x~457 = 10:01:00. "Recusas" panel: 10:00 at x~772, ~57 px/min, right edge x~828 = ~10:01:00. Rows of the CB panel fill to the right edge. Screenshot is a stitched composite (seam with "Powered by Grafana" mid-image; JVM row axes show ~09:55), so rows were captured at different moments.
  implication: the CB timeline does not end earlier; its window is 09:56:00-10:01:00 and the 10:01:00 tick sits exactly on the right edge so uPlot omits its label. Cosmetic, not a data truncation.

- timestamp: 2026-11-02T10:10
  checked: reproduction - headless Edge screenshot of http://localhost:3000/d-solo/notify-hub?panelId=15 over a fixed 15-min window containing the k6 run (anonymous Viewer enabled)
  found: three solid green rows labelled "-∞+", legend "-∞+" (scratchpad cb-before.png)
  implication: bug reproduced deterministically outside the user's browser

- timestamp: 2026-11-02T10:12
  checked: scratch dashboard uid cb-debug-scratch (created via API, deleted afterwards) with two copies of panel 15 over the same window: A = color.mode fixed (fixedColor text), mergeValues true; B = color.mode thresholds, mergeValues false
  found: A renders merged labelled segments Fechado (green) / Half-open (yellow) / Aberto (red) and legend Fechado/Half-open/Aberto; webhook shows closed -> half-open with red OPEN slivers ~09:56:00 and ~09:59:40. B renders the same colors/legend without merged labels.
  implication: removing either condition fixes rendering -> AND-gate of mergeValues=true and color.mode=thresholds confirmed. Variant A preferred (keeps merged labelled segments).

- timestamp: 2026-11-02T10:12
  checked: idle behaviour visible in variant A after the k6 run
  found: webhook and email stay HALF_OPEN (yellow) after traffic stops; sms returned to CLOSED at 09:59:35
  implication: expected Resilience4j behaviour (HALF_OPEN waits for permitted trial calls; no maxWaitDurationInHalfOpenState). Not part of this bug; worth noting for whoever interprets the screenshot.

## Eliminated

- hypothesis: (a) `max by (name)` arithmetic yields empty/NaN because label sets mismatch across instances
  evidence: query_range returns 3 series with full 187-point coverage; single instance/job
  timestamp: 2026-11-02T10:08

- hypothesis: (b) values are not exactly 0/1/2 (instance duplication / float noise), so mappings miss
  evidence: all returned values are exactly "0", "1" or "2"; and per bundle source, mappings are never consulted in the threshold-merge path anyway
  timestamp: 2026-11-02T10:12

## Resolution

- root_cause: Dois fatores juntos no painel 15 ("Circuit breaker por canal", state-timeline) de observability/grafana/dashboards/notify-hub.json: `options.mergeValues: true` E `fieldConfig.defaults.color.mode: "thresholds"`. No Grafana 12.4.12 essa combinação ativa mergeThresholdValues em prepareTimelineFields, que troca cada valor numérico pelo rótulo da faixa de threshold antes de qualquer value mapping, e não verifica se há mappings. Com um único step base (value null = -Infinity), o único rótulo é "-∞+" (verde). Por isso as três linhas e a legenda mostram "-∞+" e os mappings 0/1/2 (Fechado/Aberto/Half-open) nunca são aplicados. O PromQL está correto: devolve exatamente 0/1/2, incluindo OPEN e HALF_OPEN do webhook durante o k6.
- secondary_finding: A sensação de que o eixo "termina ~10:00" é só posicionamento dos ticks. A janela do painel CB no screenshot era 09:56:00–10:01:00 e o tick 10:01:00 cai bem na borda direita, então o uPlot não desenha o rótulo; as barras vão até a borda. O screenshot também é uma composição de capturas feitas em momentos diferentes. Não houve truncamento de dados e não precisou de mudança.
- fix: Painel 15: `"color": { "mode": "thresholds" }` virou `"color": { "mode": "fixed", "fixedColor": "text" }`. O campo continua numérico, os value mappings voltam a definir texto e cor de cada estado, e `mergeValues: true` continua juntando estados iguais consecutivos em segmentos com rótulo. A description do painel continua correta, então docs/demo.md não mudou.
- oracle_type: specified (o painel tem que mostrar o texto e a cor dos mappings Fechado/Aberto/Half-open; visto na renderização real do painel provisionado)
- verification:
    target_test: { result: pass, note: "repro manual: Edge headless em http://localhost:3000/d-solo/notify-hub?panelId=15 numa janela fixa de 15 min com o k6. Depois da correção: segmentos Fechado/Half-open/Aberto e legenda Fechado/Half-open/Aberto; webhook com fatias vermelhas de OPEN por volta de 09:56:00 e 09:59:40, batendo com o query_range" }
    mutation_check: { result: skipped, reason_if_skipped: "a mudança é config JSON do Grafana; não há Stryker nem teste automatizado lendo o dashboard" }
    no_op_deletion: { result: pass, deletion_justified_by_rca: n/a, note: "troca de uma linha (modo de cor); nada de comportamento foi removido" }
    adjacent_tests: { result: skipped, suites_run: [], reason: "nenhum teste ou script referencia notify-hub.json (grep). O JSON é válido (python json.load) e o Grafana provisionou o dashboard sem erro (GET /api/dashboards/uid/notify-hub devolve color.mode fixed)" }
    revert_and_reconfirm: { result: pass, bug_returned_on_revert: true, fixed_on_reapply: true, note: "git stash do arquivo + restart do grafana: '-∞+' voltou na mesma janela; stash pop + restart: estados mapeados de novo; janela ao vivo now-5m também mostra Half-open/Fechado" }
    guardrail_verdict: accepted
- human_verify: confirmado pelo usuário em 2026-11-02 (k6 + dashboard ao vivo mostram Fechado/Half-open/Aberto, sem "-∞+")
- follow_up: usuário escolheu documentar o Half-open persistente sem tráfego (opção a) em docs/demo.md seção 4, em vez de configurar max-wait-duration-in-half-open-state
- files_changed: [observability/grafana/dashboards/notify-hub.json, docs/demo.md]
