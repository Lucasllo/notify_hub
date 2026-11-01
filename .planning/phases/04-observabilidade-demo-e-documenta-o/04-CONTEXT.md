# Phase 4: Observabilidade, demo e documentação - Context

**Gathered:** 2026-11-01
**Status:** Ready for planning

<domain>
## Limite da fase

Esta fase transforma o sistema pronto das Fases 1–3 numa vitrine reproduzível:

- Dockerfiles multi-stage para API e worker, e **um único `docker compose up --build`** que sobe API, worker, RabbitMQ, MongoDB, Prometheus e Grafana (DEM-01);
- Prometheus coletando API, worker e RabbitMQ (OBS-02);
- Grafana com datasource e dashboard provisionados (OBS-03);
- script k6 com 1000 notificações e resumo final (DEM-02);
- benchmark justo de virtual threads vs threads de plataforma (DEM-03);
- README e docs em pt-BR com diagramas Mermaid, índices com `explain()` e trade-offs (DOC-01..04).

Esta fase não cria capacidades novas de produto. A única mudança de código de aplicação prevista é expor `/actuator/prometheus` na API, mais um contador de resultado do publish (D-02).

</domain>

<decisions>
## Decisões de implementação

### Dashboard Grafana (OBS-03)
- **D-01:** O Grafana provisiona **um único dashboard "Notify Hub"**, organizado em linhas (rows):
  1. Visão geral: throughput, totais e taxa de sucesso;
  2. Entrega por canal: `notify_delivery_outcomes` por canal e outcome, p95 de `notify_delivery_duration`;
  3. Retry e DLQ: `notify_delivery_retries` por nível e profundidade das filas de delay e das DLQs vinda do `rabbitmq_prometheus`;
  4. Resiliência: CB e bulkhead;
  5. API e JVM: HTTP, publish, threads e heap.

  A demo e o benchmark usam o mesmo dashboard. A home do Grafana aponta para ele. — **Reversibility:** reversible.
- **D-02:** O dashboard mostra métricas da API. A API passa a expor `prometheus` no actuator (`micrometer-registry-prometheus`), o que o OBS-02 já exige, e o dashboard traz taxa e p95 de `POST /notifications` (`http_server_requests`). A API ganha **um contador novo de resultado do publish** (confirmado vs PUBLISH_FAILED, por motivo num conjunto fechado: nack/timeout/returned/unconfirmed-sweep, ou o que o código já distingue), pré-registrado e com tags de baixa cardinalidade, como no D-15 da Fase 2. Assim o 503 fica visível no gráfico. — **Reversibility:** costly — o nome e as tags da métrica nova passam a ser usados pelo dashboard e pelo README, como no D-15.
- **D-03:** O estado do circuit breaker aparece como **linha do tempo por canal** (state timeline: fechado verde, aberto vermelho, half-open amarelo), a partir de `resilience4j_circuitbreaker_state`.
- **D-04:** No profile `demo` o CB fica aberto por **500ms**, e esse valor **continua como está** (decisão da Fase 2). Como um scrape de 5s pode não pegar o gauge em OPEN, a linha de Resiliência traz **ao lado da linha do tempo um painel de taxa de recusas**: `notify_delivery_rejections{reason=circuit_open|bulkhead_full}` e/ou as chamadas `not_permitted` do Resilience4j. Contadores não perdem eventos entre scrapes, então a abertura sempre aparece. Não mudar o `wait-duration-in-open-state` do profile demo.
- **D-05:** O Prometheus coleta **a cada 5s**. O dashboard abre com a janela **"últimos 15 minutos"** e refresh automático de **5s**, granular o bastante para ver a escada 1s/5s/25s durante uma rodada de k6.
- **D-06:** O Grafana usa **acesso anônimo com papel Viewer** (abre direto no dashboard, sem tela de login) e mantém `admin/admin` para edição. Como o resto do compose, a porta só escuta em `127.0.0.1`. Use a imagem `grafana/grafana:12.4.12` com JSON clássico e `provisioning/dashboards/*.yaml`, conforme o CLAUDE.md. Não use o Grafana 13.

### Estrutura do README e docs (DOC-01..04)
- **D-07:** **README vitrine + pasta `docs/`.**
  - O README tem cerca de uma tela e meia até a demo e traz: pitch, o que o projeto demonstra, diagrama Mermaid de arquitetura, "como rodar" em poucos comandos (`docker compose up --build` + k6), links para Grafana, RabbitMQ e Swagger, destaques, resumo do benchmark com números e links para os docs.
  - A pasta `docs/` traz, com nomes sugeridos que o planner pode ajustar:
    - `arquitetura.md`: componentes, topologia RabbitMQ em Mermaid e ciclo de vida do status em Mermaid (`stateDiagram`);
    - `modelagem-mongo.md`: modelo de documentos, índices, ESR e `explain()`;
    - `benchmark.md`: método completo, resultados e como reproduzir;
    - `trade-offs.md`;
    - `demo.md`: roteiro passo a passo com destinatários mágicos, reprocess e o que olhar no Grafana e no painel do RabbitMQ.

  — **Reversibility:** reversible.
- **D-08:** O `explain()` vem de **script versionado + saída colada**. Um script `mongosh` em `scripts/` roda contra o Mongo do compose, depois do k6, as consultas reais que os índices atendem:
  - listagem por status+canal ordenada por `createdAt` desc;
  - listagem só por canal e sem filtro;
  - lote do `republish-failed` (status + createdAt asc);
  - varredura de PENDING não confirmado;
  - busca por `idempotencyKey`.

  O `docs/modelagem-mongo.md` cola o trecho relevante (estágio IXSCAN, índice escolhido, `keysExamined` vs `nReturned`, ausência de SORT em memória) e justifica cada índice pela regra ESR (Equality, Sort, Range). Os índices de referência estão em `MongoIndexInitializer`.
- **D-09:** **Público:** o topo do README usa linguagem direta para recrutador (o que é, o que demonstra, como rodar). Os `docs/` são técnicos e densos, escritos para o revisor técnico, com o porquê de cada decisão e os riscos aceitos.
- **D-10:** **Screenshots** ficam em `docs/img/`: um print do dashboard durante o k6, com CB/retries/DLQ visíveis, e um das filas de delay/DLQ no painel do RabbitMQ. Eles são tirados na execução real, como **checkpoint humano** no fim da fase. O plano deixa os links para as imagens prontos e pede a captura ao usuário.
- **D-11:** O **`docs/` é independente do `.planning/`.** É escrito do zero para o leitor externo, sem citar fases, D-xx, WR-xx, CR-xx ou gap closures. O `.planning/` continua versionado como histórico do processo, com no máximo uma linha no README dizendo que ele existe.
- **D-12:** **Trade-offs** em tabela-resumo com as colunas limitação, impacto e o que faria em produção, seguida de **um parágrafo curto por item**. Itens obrigatórios:
  - sem outbox (escrita dupla Mongo → broker, mitigada pela varredura PUBLISH_UNCONFIRMED + republish);
  - at-least-once com possível duplicata após crash;
  - Spring Boot 3.5 fora do suporte OSS;
  - sem jitter;
  - os riscos aceitos do `03-SECURITY.md`: sondagem de `Idempotency-Key`, `recipient` na listagem, stats sem cache, API pública sem rate limit, `demo-admin-key` versionada;
  - as limitações conhecidas WR-05 (cópia extra na fila no replay de PENDING ainda não reivindicado, que o claim do worker descarta) e WR-07 (log de replay dizendo "PENDING" com o documento ainda PUBLISH_FAILED), em linguagem de leitor externo, sem os códigos internos.

  Fecha com "próximos passos" ligados ao v2: outbox, Boot 4, jitter, correlation ID.

### A critério do Claude
O usuário não escolheu discutir estas áreas. Os padrões abaixo são recomendações que o pesquisador e o planner podem refinar com evidência:

- **Desenho do benchmark (DEM-03):** fazer o spike curto que o roadmap pede antes do código.
  - Alvo recomendado: **tempo de drenagem do worker** como métrica principal. Publicar N mensagens com o worker parado ou listeners desligados, ligar e medir até todas ficarem SENT via Prometheus ou `/stats`. É onde o bloqueio simulado mais pesa. A latência da API sob `constant-arrival-rate` entra como medida secundária, se couber no prazo.
  - Só `VIRTUAL_THREADS` varia. Ficam iguais: concorrência alta (por exemplo 200 consumidores por canal), prefetch, latência do adapter e `maxPoolSize` do Mongo, com failure-rate 0, destinatários mágicos desligados, bulkhead ≥ concorrência e CB neutralizado.
  - Usar um profile ou override de ENV próprio, **sem o profile `demo`**, porque no demo o `wait-duration-in-open-state` é literal e não aceita ENV (blocker da Fase 2).
  - Aquecimento descartado e **várias execuções**, com mediana e dispersão. A varredura de concorrência é opcional e serve para mostrar onde as threads de plataforma saturam.
  - Captura JFR de `jdk.VirtualThreadPinned` como evidência.
  - O método e os números vão para `docs/benchmark.md`, com o resumo no README. Os números são da máquina do autor e o hardware é declarado.
- **Script k6 (DEM-02):** `grafana/k6:2.3.0` no compose sob `profiles: ["bench"]`, executor `shared-iterations` (50 VUs, 1000 iterações), mix dos três canais e dos três templates do seed com uma fração de destinatários mágicos (always-transient, always-permanent, fail-N-then-ok, fail-until-reprocess) suficiente para abrir o CB e povoar retries e DLQ. Thresholds como `http_req_failed` < 1%. No `handleSummary`/teardown, o script espera os estados terminais com limite de tempo, lê `GET /notifications/stats` (formato D-12 da Fase 3) e imprime o resumo. Chamar o reprocess da DLQ ao final é opcional e pode ficar no roteiro do `docs/demo.md`.
- **Compose:** as apps sobem com o **profile `demo`** por padrão (TTLs curtos, destinatários mágicos ligados, `demo-admin-key`). Os healthchecks e o `depends_on: service_healthy` seguem o padrão do compose atual, e as portas só escutam em `127.0.0.1`. Os Dockerfiles copiam `*-exec.jar`. Habilitar `rabbitmq_prometheus` e conferir se a imagem 4.3 já o liga por padrão. As tags de imagem são as do CLAUDE.md.
- O layout exato dos painéis, as queries PromQL, as cores e a organização dos arquivos de provisionamento.

</decisions>

<canonical_refs>
## Referências canônicas

**Os agentes seguintes DEVEM ler estes arquivos antes de planejar ou implementar.**

### Escopo e requisitos
- `.planning/ROADMAP.md` §Phase 4: objetivo, critérios de sucesso 1–5 e nota de pesquisa do benchmark
- `.planning/REQUIREMENTS.md`: OBS-02, OBS-03, DEM-01..03, DOC-01..04
- `.planning/STATE.md` §Blockers/Concerns: `*-exec.jar`, CB literal no profile demo, riscos do 03-SECURITY para o README, WR-05/WR-07

### Stack e imagens
- `.claude/CLAUDE.md`: versões e tags pinadas (Prometheus v3.13.4 LTS, Grafana 12.4.12, k6 2.3.0, temurin 21-jre, maven:3.9-eclipse-temurin-21), uso do k6, padrões do benchmark e o que não usar
- `.planning/research/STACK.md`, `.planning/research/ARCHITECTURE.md`, `.planning/research/PITFALLS.md`: pesquisa de projeto

### Contratos que o dashboard, o k6 e o README consomem
- `.planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/02-CONTEXT.md` D-15: nomes e tags das métricas do worker; D-01..D-03: destinatários mágicos; D-06..D-08: CB/bulkhead configuráveis
- `.planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-CONTEXT.md` D-09..D-12: listagem e formato do `/stats`; D-03/D-04: reprocess
- `.planning/phases/01-entrega-ponta-a-ponta-no-caminho-feliz/01-CONTEXT.md` D-01/D-02: corpo do POST e resposta 202; D-06: templates do seed
- `.planning/phases/03-reprocessamento-api-operacional-e-testes-de-integra-o/03-SECURITY.md`: riscos aceitos que vão para os trade-offs

</canonical_refs>

<code_context>
## O que o código já tem

### Ativos reutilizáveis
- `docker-compose.yml`: RabbitMQ 4.3.6-management e Mongo 8.0.32 com healthcheck, volumes e portas em 127.0.0.1 (15692 já mapeada para o `rabbitmq_prometheus`). Serve de base para estender com api, worker, prometheus, grafana e k6.
- `notify-worker` já expõe `health,info,prometheus`, com `DeliveryMetrics` pré-registrando as séries do D-15 e as métricas do Resilience4j.
- `notify-common/.../persistence/MongoIndexInitializer.java`: os 5 índices a justificar no `explain()`: (status, channel, createdAt desc, _id desc), (channel, createdAt desc, _id desc), (createdAt desc, _id desc), parcial único `idempotencyKey` e (status, createdAt desc, _id desc).
- `scripts/smoke-lib.sh`, `smoke-fase1.sh` e `smoke-fase2.sh`: helpers em bash portável para Git Bash (curl/grep, sem jq). Servem de padrão se a fase precisar de script de verificação.
- `notify-api/.../messaging/NotificationPublisher.java`, `PublishResult` e `application/UnconfirmedPublishSweeper.java`: pontos para o contador de resultado do publish (D-02).

### Padrões estabelecidos
- Métricas com tags de conjunto fechado e pré-registradas no startup (D-15 da Fase 2). O contador novo da API segue o mesmo padrão.
- Configuração por ENV com padrão no `application.yml` (`VIRTUAL_THREADS`, `NOTIFY_BULKHEAD_MAX_CONCURRENT`, `NOTIFY_CB_*`, `NOTIFY_SIMULATION_*`, `NOTIFY_ADMIN_API_KEY`).
- O profile `demo` existe nos dois módulos. Os níveis de retry da API e do worker precisam ser iguais, ou surgem filas extras no broker.
- Os jars executáveis têm classifier `exec` (`notify-<app>-0.1.0-SNAPSHOT-exec.jar`).
- Documentação em pt-BR, identificadores em inglês.

### Pontos de integração
- `notify-api/pom.xml` + `application.yml`: incluir `micrometer-registry-prometheus` e expor `prometheus` (hoje expõe só `health,info`).
- `docker-compose.yml`: novos serviços. Hosts internos via ENV (`MONGODB_URI`, `RABBITMQ_HOST`) já suportados.
- Mudar argumentos de fila exige `docker compose down -v`, o que deve constar no `docs/demo.md` como troubleshooting.

</code_context>

<specifics>
## Ideias específicas

- O "momento forte" da demo é ver no Grafana, durante o k6, o CB abrindo (recusas + linha do tempo), as filas de delay enchendo e esvaziando por nível e a DLQ crescendo. Depois, ver o reprocess esvaziar a DLQ.
- O README precisa funcionar em dois níveis: um recrutador entende em 1 minuto e um revisor técnico encontra o porquê nos `docs/`.

</specifics>

<deferred>
## Ideias adiadas

Nenhuma. A discussão ficou dentro do escopo da fase.

</deferred>

---

*Phase: 04-observabilidade-demo-e-documentacao*
*Context gathered: 2026-11-01*
