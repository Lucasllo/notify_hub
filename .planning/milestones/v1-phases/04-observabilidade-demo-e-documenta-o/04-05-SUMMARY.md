---
phase: 04-observabilidade-demo-e-documenta-o
plan: 05
subsystem: docs
tags: [readme, mermaid, mongodb, explain, esr, trade-offs, demo, docker-compose, pt-br]

# Dependency graph
requires:
  - phase: 04-observabilidade-demo-e-documenta-o (04-01..04-03)
    provides: compose stack, dashboard Notify Hub, k6 demo, smoke-fase4.sh
  - phase: 04-observabilidade-demo-e-documenta-o (04-04)
    provides: docs/benchmark.md with raw output (virtual mediana_msgs=744.4, plataforma mediana_msgs=786.2)
provides:
  - README.md vitrine em pt-BR (pitch, demonstra, Mermaid, como rodar, onde olhar, destaques, benchmark, docs, stack, testes)
  - docs/demo.md (roteiro, destinatários mágicos, reprocess, solução de problemas)
  - docs/arquitetura.md (componentes, fluxo, topologia flowchart LR, stateDiagram-v2 com as 16 transições)
  - docs/trade-offs.md (tabela de 12 itens, parágrafo por item, próximos passos)
  - docs/modelagem-mongo.md (coleções, ESR, planos reais colados)
  - scripts/explain-indices.js (explain executionStats, uma linha EXPLAIN por consulta)
  - prova do clone limpo (DEM-01) com os comandos do README
affects: [phase-04-verification, uat, gsd-ship]

# Actuals (#2632)
actuals:
  tokens: 15929
  tasks: 3
  commits: 3
plan_head_before: 3e7197d17557259aed07ea725f407ce59f5a2bfa

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Documentação pública em pt-BR, independente do histórico de planejamento (sem fases, códigos internos ou gap closures)"
    - "Planos do explain colados verbatim da saída de um script versionado e só leitura"
    - "Verificação de clone limpo com projeto compose próprio (COMPOSE_PROJECT_NAME) e teardown com down -v --rmi local"

key-files:
  created:
    - README.md
    - docs/demo.md
    - docs/img/.gitkeep
    - docs/arquitetura.md
    - docs/trade-offs.md
    - docs/modelagem-mongo.md
    - scripts/explain-indices.js
  modified: []

key-decisions:
  - "Clone limpo rodado com COMPOSE_PROJECT_NAME=notify-clean (não apaga os volumes do notify-hub, contrariando o down -v literal do verify, por instrução do orquestrador); notify-hub parado com stop e restaurado com up -d --wait"
  - "Uma notificação com Idempotency-Key criada pela API antes do explain, para a busca por chave mostrar um acerto real (1 chave, 1 documento) em vez de 0/0/0"
  - "Arestas pontilhadas do Mermaid no formato -.->|\"rótulo\"| para renderizar com segurança no GitHub"

patterns-established:
  - "README: topo para recrutador; docs/ densos para o revisor técnico"
  - "Toda garantia descrita como at-least-once, com link para docs/trade-offs.md"

requirements-completed: [DOC-01, DOC-02, DOC-03, DOC-04, DEM-01]

coverage:
  - id: D1
    description: "Num clone limpo, docker compose up --build e o k6 do README sobem e rodam a demo; Swagger, RabbitMQ e o reprocess com demo-admin-key respondem"
    requirement: DEM-01
    verification:
      - kind: e2e
        ref: "clone local + COMPOSE_PROJECT_NAME=notify-clean docker compose down -v && bash scripts/smoke-fase4.sh (SMOKE FASE 4: OK) + curls de swagger/overview/reprocess (CLONE LIMPO E README OK)"
        status: pass
    human_judgment: false
  - id: D2
    description: "README vitrine em pt-BR com as medianas copiadas de docs/benchmark.md, comandos, links e índice de docs"
    requirement: DOC-04
    verification:
      - kind: other
        ref: "greps de aceite da Task 1 (comandos, mermaid, links, at-least-once, medianas 744.4/786.2, <=1 .planning, sem códigos internos)"
        status: pass
    human_judgment: true
    rationale: "Leitura de um minuto como recrutador, tom e renderização do Mermaid no GitHub só podem ser julgados por humano"
  - id: D3
    description: "docs/arquitetura.md com topologia e stateDiagram-v2 fiéis ao código"
    requirement: DOC-01
    verification:
      - kind: other
        ref: "verify da Task 2 (DOCS OK): 15 strings de transição/topologia e 16 transições contadas"
        status: pass
    human_judgment: true
    rationale: "Renderização dos diagramas Mermaid no GitHub não tem verificação automática"
  - id: D4
    description: "docs/modelagem-mongo.md com ESR e saída real do scripts/explain-indices.js"
    requirement: DOC-02
    verification:
      - kind: integration
        ref: "MSYS_NO_PATHCONV=1 docker compose exec -T mongo mongosh notify --quiet --eval \"$(cat scripts/explain-indices.js)\" (EXPLAIN OK, 9 linhas, 5 índices, nenhum SORT)"
        status: pass
    human_judgment: false
  - id: D5
    description: "docs/trade-offs.md com 12 limitações, parágrafos e próximos passos, sem prometer mais que at-least-once"
    requirement: DOC-03
    verification:
      - kind: other
        ref: "verify da Task 2 (cabeçalho exato, 11 termos obrigatórios, 12 seções ###)"
        status: pass
    human_judgment: true
    rationale: "A honestidade da redação (prohibition de transparência) exige leitura humana"
  - id: D6
    description: "Screenshots docs/img/dashboard-k6.png e docs/img/rabbitmq-filas.png"
    verification: []
    human_judgment: true
    rationale: "Captura durante a carga real é checkpoint humano do fim da fase; os links já estão prontos"

# Metrics
duration: 14min
completed: 2026-11-02
status: complete
---

# Phase 4 Plan 05: Documentação pública em pt-BR e prova do clone limpo Summary

**README vitrine em pt-BR com Mermaid e as medianas reais do benchmark (744.4 x 786.2 msg/s), mais quatro docs técnicos (demo, arquitetura com stateDiagram-v2, trade-offs com 12 itens, modelagem com explain real) e a prova de que um clone limpo sobe tudo e passa o smoke da Fase 4 só com Docker**

## Performance

- **Duration:** 14 min
- **Started:** 2026-11-01T23:55:49Z
- **Completed:** 2026-11-02T00:09:59Z
- **Tasks:** 3
- **Files modified:** 7 (todos novos)

## Accomplishments

- Clone local do HEAD, num projeto compose próprio (`notify-clean`), rodou `docker compose up -d --build --wait` + k6 via `scripts/smoke-fase4.sh` e terminou com `SMOKE FASE 4: OK` (k6: 1000 novas, SENT=606 FAILED=40 DEAD_LETTERED=354). Swagger 200, `/api/overview` do RabbitMQ 200 e reprocess `{"requested":1000,"reprocessed":246,"skipped":0,"discarded":0}`. Depois veio o teardown completo do `notify-clean` e o `notify-hub` foi restaurado com os volumes intactos (os 4 PROCESSING antigos continuam lá)
- README com pitch, "O que este projeto demonstra", Mermaid, "Como rodar" (os dois comandos exatos), "Onde olhar", destaques, benchmark (medianas, pico de threads 40 x 636, memória 718 x 844 MiB e 1536 com C=500, tudo copiado do `docs/benchmark.md`), índice de docs, stack e testes
- `docs/arquitetura.md`: topologia (exchanges, 15 filas, dead-letter de volta com `notification.email`) e ciclo de vida com as 16 transições de `NotificationStatus`, mais `[*]`
- `docs/modelagem-mongo.md`: 9 planos reais colados verbatim (IXSCAN com 20/20/20 nas listagens, `status_createdAt` percorrido `backward` no lote do republish, EXPRESS_IXSCAN na chave, COUNT_SCAN sem ler documentos, GROUP > COLLSCAN no `/stats`)

## Task Commits

1. **Task 1: README e roteiro da demo, provados num clone limpo** - `35a925f` (docs)
2. **Task 2: docs/arquitetura.md e docs/trade-offs.md** - `4e757f5` (docs)
3. **Task 3: scripts/explain-indices.js e docs/modelagem-mongo.md** - `4533edc` (docs)

## Files Created/Modified

- `README.md` - vitrine pt-BR
- `docs/demo.md` - roteiro, destinatários mágicos, reprocess, solução de problemas
- `docs/img/.gitkeep` - pasta das screenshots
- `docs/arquitetura.md` - componentes, fluxo, topologia, ciclo de vida, resiliência, métricas, virtual threads
- `docs/trade-offs.md` - tabela `| Limitação | Impacto | O que faria em produção |`, 12 parágrafos, próximos passos
- `docs/modelagem-mongo.md` - coleções, tentativas embutidas, índices ESR, planos reais, o que ficou sem índice
- `scripts/explain-indices.js` - mongosh só leitura, linhas `EXPLAIN {...}`

## Decisions Made

- O clone limpo usou `COMPOSE_PROJECT_NAME=notify-clean`. O `docker compose down -v` do verify só apagou volumes desse projeto, e os volumes do `notify-hub` ficaram intactos (instrução do orquestrador). O estado limpo de verdade vem de um projeto sem volumes prévios.
- Antes do explain, criei uma notificação pela API com `Idempotency-Key: explain-demo-0001`, para a busca por chave mostrar um acerto real.
- No Mermaid, usei arestas pontilhadas com rótulo `-.->|"..."|`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking/Safety] Clone limpo isolado em projeto compose próprio**
- **Found during:** Task 1 (verify)
- **Issue:** O `name: notify-hub` do compose faria o `docker compose down -v` dentro do clone apagar os volumes do stack do usuário, que guardam dados que não podem ser apagados.
- **Fix:** Script em scratchpad com `docker compose -p notify-hub stop`, clone local, `COMPOSE_PROJECT_NAME=notify-clean` para `down -v` + `smoke-fase4.sh` + curls, teardown com `docker compose --profile bench down -v --rmi local --remove-orphans` e restauração com `docker compose -p notify-hub up -d --wait`. A checagem das medianas no README ficou igual.
- **Files modified:** nenhum no repositório
- **Verification:** `SMOKE FASE 4: OK` e `CLONE LIMPO E README OK`, exit 0
- **Committed in:** n/a (só verificação)

**2. [Rule 1 - Bug] Comando do explain descrito como válido no PowerShell**
- **Found during:** Task 3
- **Issue:** O rascunho do cabeçalho dizia que o prefixo `MSYS_NO_PATHCONV=1` era inofensivo no PowerShell, mas `VAR=1 cmd` é sintaxe de bash.
- **Fix:** Cabeçalho do script e o "Como reproduzir" agora dizem que o comando é para bash (Git Bash no Windows).
- **Files modified:** scripts/explain-indices.js, docs/modelagem-mongo.md
- **Committed in:** 4533edc

---

**Total deviations:** 2 auto-fixed (1 blocking/safety, 1 bug)
**Impact on plan:** Nenhuma mudança de escopo. O DEM-01 foi provado sem tocar nos dados do usuário.

## Issues Encountered

- O build do clone reaproveitou o cache BuildKit do Maven (`id=notify-m2`), que é global no host. Os módulos foram compilados do zero dentro do Docker, mas o download de dependências de um host realmente novo não foi cronometrado. O README avisa que o primeiro build leva alguns minutos.

## Known Stubs

| Arquivo | Motivo |
|---------|--------|
| `docs/img/dashboard-k6.png` (linkado em README.md e docs/demo.md) | Screenshot do checkpoint humano do fim da fase; registrado em `.planning/WINDOWS.md` |
| `docs/img/rabbitmq-filas.png` (linkado em docs/demo.md) | Idem |

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Documentação completa. Falta só o human-check do fim da fase: (1) com o stack no ar, rodar `docker compose --profile bench run --rm k6` e capturar o dashboard durante a carga em `docs/img/dashboard-k6.png`; (2) capturar a aba Queues do RabbitMQ (http://localhost:15672, notify/notify) com filas de delay e DLQs em `docs/img/rabbitmq-filas.png`; (3) commitar as imagens; (4) conferir a renderização do Mermaid no GitHub; (5) ler o topo do README como recrutador e conferir pt-BR, at-least-once e os riscos listados.
- O stack `notify-hub` está no ar, com +1001 documentos desta execução (k6 e uma notificação com chave) e a DLQ do webhook reprocessada.

---
*Phase: 04-observabilidade-demo-e-documenta-o*
*Completed: 2026-11-02*

## Self-Check: PASSED
