# Fase 3: Reprocessamento, API operacional e testes de integração - Contexto

**Levantado em:** 2026-10-28
**Status:** Pronto para planejamento

<domain>
## Limite da fase

Nenhuma notificação fica presa sem saída. O operador reprocessa a DLQ por canal (`POST /admin/dlq/{canal}/reprocess?max=N`) e republica as notificações em PUBLISH_FAILED (`POST /admin/notifications/republish-failed`), sempre com `X-API-Key`. O cliente lista com filtros e paginação, consulta estatísticas e reenvia o POST com `Idempotency-Key` sem duplicar. O Swagger UI documenta todos os endpoints. Uma suíte de ITs com Testcontainers, rodando via Failsafe, prova automaticamente:
- retry → DLQ → reprocessamento → SENT;
- mensagem venenosa chega à DLQ sem loop;
- publish não roteável vira PUBLISH_FAILED;
- entrega duplicada concorrente gera exatamente um envio.

`./mvnw test` continua sem Docker.

Requisitos: API-03, API-05, API-07, API-08, ADM-01, ADM-02, ADM-03, ADM-04, TST-02, TST-03, TST-04.

</domain>

<decisions>
## Decisões de implementação

### Reprocessamento da DLQ (ADM-01, ADM-02) e o CR-01
- **D-01:** Um documento **não terminal** cuja mensagem está na DLQ é reprocessado normalmente. Os estados são PENDING, RETRYING e PROCESSING com `leaseUntil < agora`, os casos do CR-01 em `02-.../deferred-items.md`. A tabela de `NotificationStatus` ganha as transições PENDING/RETRYING/PROCESSING → PENDING, **usadas só pelo reprocess**. O reprocess grava o evento REPROCESSED, zera o contador e republica. Não há sweeper de lease. Se a mensagem está na DLQ, nada mais vai entregá-la, então o reprocess é a saída. — **Reversibility:** costly — a tabela de transições é o contrato dos filtros condicionais da API e do worker (D-11 da Fase 2).
- **D-02:** Casos que não interrompem o lote:
  - **PROCESSING com lease válido** (um worker pode estar com ele): a mensagem é republicada no **fim da DLQ** com confirm, depois recebe ack, e conta como `skipped`.
  - **Documento SENT ou inexistente:** a mensagem recebe ack e sai da DLQ, contada como `discarded`, porque não há nada a entregar.

  Para não girar em círculo dentro de uma chamada, o lote termina quando N mensagens foram consumidas ou a DLQ esvazia. O planner garante que uma mensagem `skipped` não seja relida na mesma chamada, por exemplo limitando pelo tamanho da fila no início.
- **D-03:** Formato da resposta:
  - Lote concluído, inclusive quando a DLQ esvazia antes de N: **200** com `{requested, reprocessed, skipped, discarded}`.
  - **Parada na primeira falha** (Mongo ou republish com nack, timeout ou return): **503** em Problem Detail com as mesmas contagens parciais e o motivo, num conjunto fechado e sem texto livre do broker, como no T-02-01. A mensagem que falhou **volta para a DLQ** (nack com requeue / sem ack).
  - Sem API key ou com chave errada: nada é movido.
- **D-04:** `max` tem **padrão 100 e teto 1000**. Fora de 1..1000, ou canal inválido, devolve **400** em Problem Detail (ADM-04). Os dois valores são configuráveis por propriedade.

### Idempotency-Key (API-03)
- **D-05:** `idempotencyKey` e `requestHash` ficam **no próprio `NotificationDocument`**, com **índice único parcial** (só onde a chave existe) criado pelo `MongoIndexInitializer`. O insert é atômico. Numa corrida entre dois POSTs com a mesma chave, o perdedor recebe `DuplicateKeyException`, lê o vencedor e segue a regra de replay. Não há coleção extra nem TTL: a chave vale enquanto o documento existir. — **Reversibility:** costly — campo persistido e índice único no Mongo. Mudar exige migração ou `docker compose down -v`.
- **D-06:** "Mesmo payload" quer dizer o mesmo **SHA-256 do pedido canônico**: channel, recipient, templateCode e variables com chaves ordenadas, calculado depois do parse. A mesma chave com hash diferente devolve **422** em Problem Detail.
- **D-07:** O replay (mesma chave e mesmo hash) devolve **o mesmo 202**: `Location: /notifications/{id}`, corpo `{id, status, createdAt}` com o **status atual** e o header **`Idempotent-Replayed: true`**.
- **D-08:** Um replay de documento em **PUBLISH_FAILED tenta republicar**: PUBLISH_FAILED → PENDING (transição que já existe) e publish com confirm. Devolve 202 se der certo e 503 com `Retry-After` se falhar de novo. Esse caminho é **o mesmo do `republish-failed` do admin** (ADM-03), num serviço só.

### Listagem e stats (API-05, API-07)
- **D-09:** O `GET /notifications?status=&channel=&page=&size=` usa paginação **page/size com total**, envelope `{content, page, size, totalElements, totalPages}`, ordenado por `createdAt` desc. Precisa de um índice composto que cubra filtro e ordenação, como (status, channel, createdAt desc). O planner confere quais índices o `MongoIndexInitializer` já cria.
- **D-10:** A página tem **padrão 20 e máximo 100**. Fora do intervalo devolve **400** em Problem Detail, sem limitar em silêncio. Status e canal inválidos também dão 400.
- **D-11:** Cada item da lista é um **resumo**: `id, status, channel, recipient, templateCode, retryCount, lastError.code, createdAt, updatedAt`. Não traz `attempts[]`, `rendered` nem `variables`, que continuam no `GET /{id}` (D-03 da Fase 1).
- **D-12:** O `GET /notifications/stats` devolve `{total, byStatus:{...}, byChannel:{...}, byChannelAndStatus:{EMAIL:{SENT:n,...},...}}`, com **todos os canais e status presentes (zeros inclusive)** e calculado numa agregação `$group`. — **Reversibility:** costly — o README e possivelmente o k6 (Fase 4) leem esse formato.

### Testes de integração (TST-02, TST-03, TST-04)
- **D-13:** Os ITs ponta a ponta ficam num **módulo novo `notify-it`**, só de teste, que depende de `notify-api` e `notify-worker` e sobe **os dois contextos Spring na mesma JVM** contra os mesmos containers. O fluxo real é HTTP → broker → worker → DLQ → reprocess HTTP → SENT. **Ponto de pesquisa:** os dois `application.yml` colidem no classpath (e os pacotes `dev.notifyhub.api`/`.worker` não podem se escanear mutuamente). Resolver com `spring.config.name` próprio por contexto, propriedades explícitas no `SpringApplicationBuilder` ou outro mecanismo, sem quebrar a execução normal dos apps. O POM pai ganha o módulo, e o Failsafe roda `*IT` no `verify`. — **Reversibility:** reversible.
- **D-14:** A escada usa **TTLs curtos só de teste**, por exemplo `notify.topology.retry-levels=200ms,500ms,1s`, e o Awaitility substitui qualquer `Thread.sleep`. Cada execução usa um broker novo, então os argumentos de fila diferentes não conflitam.
- **D-15:** Há um **profile próprio `it`** (`application-it.yml` no `notify-it`) que:
  - liga `notify.simulation.magic-recipients=true` (no profile base ele está desligado desde a quick 261020-tm3);
  - usa TTLs curtos, latência baixa e failure-rate 0;
  - configura o CB para não abrir por acidente nos testes que não testam o CB.

  Os ITs não ativam o profile `demo`.
- **D-16:** Um único par RabbitMQ e Mongo fica **estático por suíte**, com `@ServiceConnection` e as tags `rabbitmq:4.3.6-management` e `mongo:8.0.32`, iguais às do compose. Entre testes, as filas são esvaziadas e as coleções de dados limpas, preservando os templates do seed.

### A critério do Claude
- **API key (ADM-04):** um valor único por ENV (por exemplo `NOTIFY_ADMIN_API_KEY` → `notify.admin.api-key`), comparado em tempo constante (`MessageDigest.isEqual`). Sem chave ou com chave errada devolve 401 em Problem Detail. No compose/demo, um valor padrão documentado. Sem chave configurada, os endpoints admin recusam tudo (fail closed).
- **`republish-failed` (ADM-03):** processa em lote com limite (`max`, os mesmos padrão e teto do D-04), do mais antigo para o mais novo, usando o serviço do D-08. A resposta segue o padrão do D-03 (`{requested, republished}` e 503 parcial na primeira falha).
- Formato exato do evento REPROCESSED no `attempts[]` (ou num campo de eventos) e a mensagem republicada com `notify-retry-count=0`. O ADM-02 exige manter o histórico.
- Como provar "exatamente um envio" na duplicata concorrente: por exemplo um contador no adapter simulado exposto ao teste, ou a métrica `notify.delivery.outcomes{outcome=sent}` mais a contagem de `attempts` SUCCESS.
- Swagger (API-08): springdoc 2.8.17, com o esquema de segurança `X-API-Key` declarado nos endpoints admin.
- Corrigir o Javadoc de `RecipientRules` (deferred item da Fase 2, exemplo de SMS mágico antigo) e o de `MagicRecipients.java:15` (novo padrão desligado) se tocar nesses arquivos.

</decisions>

<canonical_refs>
## Referências canônicas

**Os agentes seguintes PRECISAM ler estas referências antes de planejar ou implementar.**

### Escopo e requisitos
- `.planning/ROADMAP.md` §Phase 3 — objetivo, critérios de sucesso e notas de ordem de build (reset no Mongo antes do republish, `basicGet` com ack manual)
- `.planning/REQUIREMENTS.md` — API-03, API-05, API-07, API-08, ADM-01..04, TST-02..04
- `.planning/PROJECT.md` — valor central, decisões-chave (sem outbox, publish falho → 503 + PUBLISH_FAILED + republish admin)

### Herança das fases anteriores
- `.planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/deferred-items.md` — **CR-01: entrada obrigatória** (documentos não terminais com mensagem na DLQ)
- `.planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/02-CONTEXT.md` — D-01..D-03 (destinatários mágicos, `fail-until-reprocess`), D-09..D-11 (claim/lease/fence, ack AUTO + cópia na DLQ com `x-failure-class`), D-13..D-15 (FailureClass, códigos de erro, métricas)
- `.planning/phases/02-falhas-retry-com-backoff-e-resili-ncia/02-SECURITY.md` — T-02-01 (nada de texto livre do broker em `lastError`/respostas), T-02-35 (CR-01 transferido para cá)
- `.planning/phases/01-entrega-ponta-a-ponta-no-caminho-feliz/01-CONTEXT.md` — D-01..D-05 (contrato do POST/202/GET, confirm de 5s + 503 + Retry-After, canal maiúsculo)
- `.planning/quick/261020-tm3-corrigir-wr-05-t-02-01-lasterror-de-repu/261020-tm3-SUMMARY.md` — destinatários mágicos desligados no profile base (ITs precisam ligar)

### Stack
- `.claude/CLAUDE.md` — versões fixadas: Testcontainers 1.21.4 gerenciado (não usar 2.x), springdoc 2.8.17, Awaitility, Failsafe `*IT`, nada de `Thread.sleep`, imagens iguais às do compose

</canonical_refs>

<code_context>
## O que já existe no código

### Recursos reaproveitáveis
- `notify-api/.../messaging/NotificationPublisher` + `PublishResult` + `PublishProperties`: publish confirmado com timeout (D-04 da Fase 1). Base para o republish do reprocess, do replay e do `republish-failed`.
- `notify-api/.../application/NotificationService`: fluxo de criação (PENDING → publish → PUBLISH_FAILED/503). Ponto de entrada da idempotência (D-05..D-08).
- `notify-api/.../web/ApiExceptionHandler` + `ProblemTypes`: Problem Details padronizados. Os novos 400/401/422/503 entram aqui.
- `notify-common/.../domain/NotificationStatus`: tabela de transições. Recebe as transições do D-01.
- `notify-common/.../persistence/MongoIndexInitializer`: índices criados no startup da API e do worker. Recebe o índice único parcial (D-05) e o composto da listagem (D-09).
- `notify-common/.../messaging/NotifyTopology` + `TopologyProperties` (`notify.topology.retry-levels`): nomes de fila/DLQ por canal e TTLs configuráveis (D-14).
- `notify-worker/.../channel/MagicRecipients` + `fail-until-reprocess`: cenário determinístico do IT retry → DLQ → reprocess → SENT.
- `notify-worker/.../messaging/DeliveryRoutingPublisher.PublishOutcome`: motivos enumerados de falha de publish. Modelo para os motivos do 503 parcial (D-03).

### Padrões estabelecidos
- Updates condicionais no Mongo com fence de status + `retryCount` (`NotificationStateService`). O reset do reprocess precisa usar o mesmo estilo atômico.
- Records para DTOs, sem Lombok; documentação e mensagens em pt-BR.
- Testes unitários sem Docker (`MongoBson`, mocks). Os ITs ficam isolados em `*IT` + Failsafe.

### Pontos de integração
- `notify-api/.../web/NotificationController` (`/notifications`): ganha a listagem, o stats e o header `Idempotency-Key`.
- Novo controller admin na API (`/admin/...`) com filtro ou interceptor de API key.
- `pom.xml` pai: novo módulo `notify-it`, Failsafe e dependências de Testcontainers gerenciadas pelo Boot.
- A API ainda não tem `springdoc` no POM (API-08).

</code_context>

<specifics>
## Ideias específicas

- As contagens `{requested, reprocessed, skipped, discarded}` aparecem na demo e no README. Devem ser legíveis para quem avalia o portfólio.
- O IT principal usa `fail-until-reprocess@fail.test`: falha até esgotar a escada, vai para a DLQ, `POST /admin/dlq/email/reprocess` e termina SENT, com `attempts[]` preservado mais o evento REPROCESSED.

</specifics>

<deferred>
## Ideias adiadas

Nenhuma. A discussão ficou dentro do escopo da fase.

</deferred>

---

*Fase: 03-reprocessamento-api-operacional-e-testes-de-integra-o*
*Contexto levantado em: 2026-10-28*
