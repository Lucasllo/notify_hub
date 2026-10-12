# Roadmap: Notify Hub

## Overview

O Notify Hub é construído em quatro fatias verticais, cada uma demonstrável de ponta a ponta. A Fase 1 entrega o esqueleto completo: contrato compartilhado, topologia RabbitMQ inteira (filas principais, de delay e DLQs, com TTLs configuráveis), API que aceita e publica com publisher confirms e worker que entrega pelo adapter simulado até SENT. A Fase 2 dá destino previsível a toda falha: classificação transitória/permanente, escada de delay com backoff exponencial, DLQ, circuit breaker e bulkhead, tudo demonstrável de forma determinística com destinatários mágicos e já instrumentado com métricas nos pontos de resultado. A Fase 3 fecha o ciclo operacional: reprocessamento seguro da DLQ, republish de PUBLISH_FAILED, API de consulta completa com idempotência, e a suíte de integração com Testcontainers que prova retry → DLQ → reprocessamento → SENT. A Fase 4 transforma tudo em vitrine: um único `docker compose up --build`, Prometheus e Grafana provisionados, script k6 com 1000 notificações, benchmark justo de virtual threads e README completo em pt-BR.

## Phases

**Phase Numbering:**

- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [ ] **Phase 1: Entrega ponta a ponta no caminho feliz** - Contrato, topologia completa e compose de infra; a API aceita e publica com confirms e o worker entrega até SENT
- [ ] **Phase 2: Falhas, retry com backoff e resiliência** - Toda falha tem destino previsível (retry por nível, DEAD_LETTERED, FAILED), com circuit breaker, bulkhead, destinatários mágicos e métricas
- [ ] **Phase 3: Reprocessamento, API operacional e testes de integração** - Operador recupera DLQ e PUBLISH_FAILED, cliente consulta e reenvia com idempotência, e ITs provam o fluxo completo
- [ ] **Phase 4: Observabilidade, demo e documentação** - Um comando sobe tudo, o Grafana mostra 1000 notificações do k6, e o README traz arquitetura, trade-offs e benchmark

## Phase Details

### Phase 1: Entrega ponta a ponta no caminho feliz

**Goal**: Um cliente cria uma notificação via `POST /notifications` e o worker a entrega pelo adapter simulado até SENT, sobre um contrato compartilhado e uma topologia RabbitMQ completa (filas principais, de delay e DLQs) já declarados e visíveis no painel
**Mode:** mvp
**Depends on**: Nothing (first phase)
**Requirements**: FND-01, FND-02, FND-03, FND-04, MSG-01, MSG-02, MSG-03, MSG-04, MSG-05, API-01, API-02, API-04, API-06, WRK-01, WRK-02, WRK-04, WRK-05, TPL-01, DLV-01
**Success Criteria** (what must be TRUE):

  1. Com `docker compose up`, RabbitMQ 4.3 e MongoDB 8.0 sobem saudáveis e `./mvnw verify` compila os três módulos; ao iniciar API e worker, o painel do RabbitMQ mostra `notify.exchange`, `notify.retry`, `notify.dlx` e, para cada canal (email, sms, webhook), a fila principal, três filas de delay com o TTL no nome (5s/25s/125s) e a DLQ, e os índices já existem no MongoDB
  2. `POST /notifications` com canal, destinatário, templateCode e variáveis responde 202 com `Location`; seguindo o `Location`, o cliente vê a notificação passar de PENDING para SENT, renderizada a partir do template do seed e com uma tentativa registrada no histórico (timestamp e duração)
  3. Payload inválido (canal desconhecido, campo ausente, destinatário fora do formato do canal) recebe 400 com Problem Details; quando o publish não é confirmado ou volta como não roteável, a API responde 503 com Problem Detail e `Retry-After` e a notificação fica PUBLISH_FAILED, nunca PENDING para sempre
  4. Uma mensagem duplicada para a mesma notificação não gera segundo envio, e uma mensagem rejeitada pelo worker vai para a DLQ do canal em vez de voltar à fila em loop
  5. Subindo com o profile `demo`, as filas de delay aparecem com TTLs curtos (ex.: 1s/5s/25s) sem mudança de código, e o worker processa as mensagens em virtual threads

**Plans**: TBD

- [x] 01-01-PLAN.md
- [x] 01-02-PLAN.md
- [x] 01-03-PLAN.md
- [ ] 01-04-PLAN.md
- [ ] 01-05-PLAN.md
- [ ] 01-06-PLAN.md

**Notas de build order**: a topologia inteira (incluindo filas de delay e DLQs) entra já nesta fase porque mudar argumentos de fila depois causa 406 PRECONDITION_FAILED; os níveis de TTL configuráveis (MSG-05) também, porque os ITs da Fase 3 dependem de TTLs em milissegundos. A atualização atômica de resultado (WRK-04) nasce aqui no SENT e é reutilizada pelos resultados da Fase 2.

### Phase 2: Falhas, retry com backoff e resiliência

**Goal**: Toda falha de entrega tem destino previsível e visível: transitórias percorrem a escada de delay com backoff exponencial e, esgotadas, terminam DEAD_LETTERED na DLQ; permanentes vão direto para FAILED na DLQ; circuit breaker e bulkhead protegem cada canal; e tudo é demonstrável de forma determinística com destinatários mágicos e mensurável por métricas
**Mode:** mvp
**Depends on**: Phase 1
**Requirements**: WRK-03, TPL-02, TPL-03, DLV-02, DLV-03, DLV-04, DLV-05, DLV-06, RES-01, RES-02, OBS-01, TST-01
**Success Criteria** (what must be TRUE):

  1. Enviando para o destinatário mágico "sempre transitória", a notificação passa visivelmente pelas três filas de delay do canal, fica RETRYING entre as tentativas e termina DEAD_LETTERED na DLQ, com uma tentativa por nível (erro e classe da falha) no `GET /notifications/{id}`
  2. Destinatário mágico "sempre permanente", template inexistente ou variável obrigatória ausente levam a notificação direto a FAILED, sem passar por filas de delay, e a mensagem chega à DLQ com o header `x-failure-class: PERMANENT`
  3. O destinatário mágico "falha N vezes e depois sucede" termina SENT após N retries, e uma notificação deixada em PROCESSING por um worker derrubado antes do ack é retomada e concluída na redelivery, sem envio duplicado
  4. Com taxa de falha alta, o circuit breaker do canal abre durante a demo e o bulkhead recusa o excesso de concorrência, e em ambos os casos a notificação vira RETRYING (transitória), nunca FAILED; o `/actuator/prometheus` do worker mostra contadores por canal de enviadas, falhas e retries e o timer de latência de envio
  5. O e-mail sai com HTML escapado, o SMS sem escape HTML e o webhook com JSON válido mesmo quando as variáveis contêm aspas; `./mvnw test` roda os testes unitários do renderizador sem precisar de Docker

**Plans**: TBD
**Notas de pesquisa**: fase sinalizada para pesquisa. Dimensionar circuit breaker e bulkhead em relação à concorrência do listener e à escada de delay (janela, mínimo de chamadas, tempo de abertura não maior que o primeiro nível); decidir se `CallNotPermitted` consome um nível; confirmar com teste o comportamento de DLX/`x-death` no RabbitMQ 4.3. Os destinatários mágicos (DLV-02) são pré-requisito dos ITs da Fase 3; os contadores entram nos pontos de resultado conforme cada resultado é construído.

### Phase 3: Reprocessamento, API operacional e testes de integração

**Goal**: Nenhuma notificação fica presa sem saída: o operador reprocessa a DLQ e republica PUBLISH_FAILED com segurança, o cliente consulta, filtra e reenvia com idempotência, e uma suíte de integração com Testcontainers prova automaticamente o fluxo retry → DLQ → reprocessamento → SENT
**Mode:** mvp
**Depends on**: Phase 2
**Requirements**: API-03, API-05, API-07, API-08, ADM-01, ADM-02, ADM-03, ADM-04, TST-02, TST-03, TST-04
**Success Criteria** (what must be TRUE):

  1. Com notificações na DLQ, o operador chama `POST /admin/dlq/{canal}/reprocess?max=N` com `X-API-Key` válida e até N mensagens voltam à fila principal; cada notificação recomeça com o contador zerado, mantém o `attempts[]` anterior com um evento REPROCESSED e chega a SENT quando a falha não se repete
  2. Chamadas admin sem a API key ou com chave errada são recusadas sem mover nenhuma mensagem, canal inválido ou `max` fora do limite são recusados com Problem Details, e `POST /admin/notifications/republish-failed` reenvia as notificações em PUBLISH_FAILED, que seguem até SENT
  3. O cliente que repete o POST com o mesmo `Idempotency-Key` e o mesmo payload recebe a notificação original sem duplicar, e com payload diferente recebe 422; `GET /notifications?status=&channel=` pagina por data de criação decrescente, `GET /notifications/stats` traz contagens por status e canal, e o Swagger UI documenta todos os endpoints
  4. `./mvnw verify` roda via Failsafe os ITs com Testcontainers (RabbitMQ + MongoDB) que provam retry → DLQ → reprocessamento → SENT, mensagem venenosa na DLQ sem loop, publish não roteável virando PUBLISH_FAILED e entrega duplicada concorrente com exatamente um envio; `./mvnw test` continua rodando sem Docker

**Plans**: TBD
**Notas de build order**: o reprocessamento reaproveita o publisher confirmado da Fase 1 e depende de uma DLQ populada pela Fase 2; o reset no Mongo acontece antes do republish para não ser engolido pela guarda de idempotência, e o `basicGet` usa ack manual.

### Phase 4: Observabilidade, demo e documentação

**Goal**: Quem avalia o projeto roda um único `docker compose up --build` e o script k6, vê 1000 notificações fluindo no Grafana (retries, DLQs, circuit breaker) e lê no README em pt-BR a arquitetura, os trade-offs e os números do benchmark de virtual threads
**Mode:** mvp
**Depends on**: Phase 3
**Requirements**: OBS-02, OBS-03, DEM-01, DEM-02, DEM-03, DOC-01, DOC-02, DOC-03, DOC-04
**Success Criteria** (what must be TRUE):

  1. Num clone limpo, um único `docker compose up --build` sobe API, worker, RabbitMQ, MongoDB, Prometheus e Grafana sem nenhum passo manual nem JDK local
  2. O Grafana abre com datasource e dashboard já provisionados, mostrando throughput, resultados por canal, retries por nível, profundidade das DLQs, latência p95 e estado do circuit breaker, alimentados pelo Prometheus coletando API, worker e RabbitMQ
  3. O script k6 dispara 1000 notificações com mix de canais e destinatários mágicos, o dashboard reflete a carga enquanto ela roda, e ao final o script imprime um resumo das métricas e das contagens por status
  4. O README mostra os resultados do benchmark virtual threads vs threads de plataforma (só o tipo de thread varia, mesma concorrência alta, latência bloqueante simulada, aquecimento e várias execuções), com o método para reproduzi-lo
  5. O README e toda a documentação estão em pt-BR, com diagramas Mermaid (arquitetura, topologia, ciclo de vida do status), passo a passo da demo, justificativa dos índices compostos com `explain()` e os trade-offs (sem outbox, at-least-once com possível duplicata após crash, Spring Boot 3.5 fora do suporte OSS, sem jitter)

**Plans**: TBD
**Notas de pesquisa**: o benchmark (DEM-03) precisa de um spike curto de desenho antes do código: varredura de concorrência, alvo (tempo de drenagem do worker, taxa de chegada na API ou ambos), executor k6 e captura JFR de `jdk.VirtualThreadPinned`.

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Entrega ponta a ponta no caminho feliz | 3/6 | In Progress|  |
| 2. Falhas, retry com backoff e resiliência | 0/TBD | Not started | - |
| 3. Reprocessamento, API operacional e testes de integração | 0/TBD | Not started | - |
| 4. Observabilidade, demo e documentação | 0/TBD | Not started | - |
