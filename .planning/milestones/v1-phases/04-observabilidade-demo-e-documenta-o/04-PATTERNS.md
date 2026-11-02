# Phase 4: Observabilidade, demo e documentação - Pattern Map

**Mapped:** 2026-11-01
**Files analyzed:** 24
**Analogs found:** 9 / 24 (o resto é infraestrutura ou docs novos: usar os esqueletos verificados do 04-RESEARCH.md)

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `notify-api/pom.xml` (mod) | config | build | `notify-worker/pom.xml` linhas 35-39 | exact |
| `notify-api/src/main/resources/application.yml` (mod) | config | — | o próprio arquivo, linhas 41-45 | exact |
| `notify-api/src/main/java/dev/notifyhub/api/metrics/PublishMetrics.java` (novo) | component (métricas) | event-driven (contador) | `notify-worker/src/main/java/dev/notifyhub/worker/metrics/DeliveryMetrics.java` | exact |
| `notify-api/src/test/java/dev/notifyhub/api/metrics/PublishMetricsTest.java` (novo) | test | — | `notify-worker/src/test/java/dev/notifyhub/worker/metrics/DeliveryMetricsTest.java` | exact |
| `notify-api/src/main/java/dev/notifyhub/api/application/NotificationRepublisher.java` (mod) | service | request-response | o próprio arquivo (`publishOrMarkFailed` linhas 161-191, `markUnconfirmedAsPublishFailed` linhas 301-315) | exact |
| `notify-api/src/test/java/dev/notifyhub/api/application/NotificationRepublisherTest.java` (mod) | test | — | o próprio arquivo | exact |
| `docker-compose.yml` (mod) | config | — | o próprio arquivo (healthcheck/ports/volumes) | exact |
| `docker-compose.bench.yml` (novo) | config | — | `docker-compose.yml` | role-match |
| `.dockerignore` (novo) | config | — | nenhum | none |
| `notify-api/Dockerfile`, `notify-worker/Dockerfile` (novos) | config (build) | batch | nenhum (RESEARCH Padrão 1) | none |
| `observability/prometheus/prometheus.yml` | config | — | nenhum (RESEARCH Padrão 3) | none |
| `observability/grafana/provisioning/datasources/prometheus.yaml`, `provisioning/dashboards/notify-hub.yaml`, `dashboards/notify-hub.json` | config | — | nenhum (RESEARCH Padrão 4 + catálogo PromQL) | none |
| `k6/demo.js` | script (carga) | request-response | `scripts/smoke-lib.sh` (`post_notificacao`, polling) — só para contrato/corpo | partial |
| `scripts/explain-indices.js` (mongosh) | script | batch | `scripts/smoke-lib.sh` linha 55 (`mongo_eval`) + `MongoIndexInitializer.java` linhas 52-72 | partial |
| `scripts/bench/run-bench.sh` | script | batch | `scripts/smoke-lib.sh` / `scripts/smoke-fase2.sh` | role-match |
| `scripts/bench/drain-stats.js` | script | batch | `mongo_eval` em `scripts/smoke-lib.sh` | partial |
| `README.md`, `docs/arquitetura.md`, `docs/modelagem-mongo.md`, `docs/benchmark.md`, `docs/trade-offs.md`, `docs/demo.md`, `docs/img/` | docs | — | nenhum (RESEARCH Padrões 7 e 8) | none |

## Pattern Assignments

### `notify-api/pom.xml` (config)

**Analog:** `notify-worker/pom.xml` linhas 35-39 — copiar verbatim, sem versão:
```xml
<!-- /actuator/prometheus: métricas de entrega (D-15) e do Resilience4j; versão gerenciada pelo Boot -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```
Inserir logo após `spring-boot-starter-actuator` (linha 37 do pom da API). Ajustar o comentário para "métricas HTTP e de publish".

### `notify-api/src/main/resources/application.yml` (config)

Bloco atual (linhas 41-45): `management.endpoints.web.exposure.include: health,info`. Trocar por `health,info,prometheus` e adicionar `management.metrics.distribution.percentiles-histogram.http.server.requests: true` (RESEARCH Padrão 2). Manter o estilo do arquivo: comentário pt-BR acima de cada chave explicando o porquê.

### `notify-api/.../metrics/PublishMetrics.java` (component, contador)

**Analog:** `notify-worker/src/main/java/dev/notifyhub/worker/metrics/DeliveryMetrics.java`

**Imports** (linhas 8-20): `io.micrometer.core.instrument.Counter`, `MeterRegistry`, `java.util.Locale`, `java.util.EnumMap`/`Map`, `org.springframework.stereotype.Component`.

**Javadoc de contrato** (linhas 22-45): listar nome, tags e número de séries; dizer que id/destinatário nunca viram tag; dizer que todas as séries têm o mesmo conjunto de chaves.

**Constantes de nome** (linhas 49-52):
```java
public static final String OUTCOMES = "notify.delivery.outcomes";
```
→ `public static final String OUTCOMES = "notify.publish.outcomes";`

**Pré-registro no construtor** (linhas 70-77 e 118-125):
```java
private static Counter outcome(MeterRegistry registry, Channel channel, String outcome, String failureClass) {
    return Counter.builder(OUTCOMES)
            .description("Resultados de entrega gravados no documento, por canal")
            .tag("channel", channel.id())
            .tag("outcome", outcome)
            .tag("failure_class", failureClass)
            .register(registry);
}
```
Para o publish: tags `outcome` + `reason`, pares fechados `confirmed/none`, `confirmed/claimed`, `publish_failed/{nack,returned,timeout,broker_unavailable,unconfirmed}` (RESEARCH Padrão 2; valores `[ASSUMED]`, viram contrato). Mapear `PublishResult` (enum `ACK, NACK, RETURNED, TIMEOUT, BROKER_UNAVAILABLE`, `notify-api/.../messaging/PublishResult.java` linhas 12-18) com `switch` exaustivo, como `failed(...)` nas linhas 157-164 (rejeitar `ACK` como falha com `IllegalArgumentException`).

**Normalização de tag** (linhas 127-129): `value.name().toLowerCase(Locale.ROOT)`.

**Métodos públicos** (linhas 131-174): um método por evento real (`confirmed()`, `confirmedByClaim()`, `failed(PublishResult.Status)`, `unconfirmed(long n)` com `increment(n)`), cada um com javadoc curto dizendo quando é chamado.

### `PublishMetricsTest.java` (test)

**Analog:** `DeliveryMetricsTest.java` linhas 1-60.
- `SimpleMeterRegistry` no campo (linha 37), construtor direto, sem Spring.
- Teste de pré-registro: `registry.find("notify.publish.outcomes").counters()` → `hasSize(N)`, `allSatisfy(count isZero)`, conjunto de combinações via `getId().getTag(...)` (linhas 45-60).
- O analog também importa `PrometheusMeterRegistry`/`PrometheusConfig` (linhas 18-19) para conferir o nome exportado `notify_publish_outcomes_total` e que nenhum meter é descartado por chaves diferentes — repetir.

### `NotificationRepublisher.java` (service, mod)

Pontos de contagem (não contar em `NotificationPublisher.publish`, ver RESEARCH: também serve DLQ reprocess e `copyToDlqTail`):
- linhas 165-167 `if (result.ok()) return Optional.empty();` → `confirmed()`;
- linha 170-171 `if (markPublishFailed(...)) return Optional.of(result);` → `failed(result.status)`;
- linhas 183-186 ramo `wasClaimedByWorker` → `confirmedByClaim()`;
- linhas 187-190 warn final → `failed(result.status)`;
- ramo `catch (DataAccessException)` (linhas 175-181) relança: decidir se conta (recomendação: não contar, o resultado não foi gravado).
- `markUnconfirmedAsPublishFailed` linhas 301-315: `long n = update.getModifiedCount(); metrics.unconfirmed(n); return n;`.
Injetar `PublishMetrics` no construtor (linha 75, mesmo estilo de campos `private final` nas linhas 71-73). Atualizar `NotificationRepublisherTest` passando `new PublishMetrics(new SimpleMeterRegistry())`.

### `docker-compose.yml` (config, mod)

**Analog:** o próprio arquivo. Padrões a copiar nos serviços novos:
- portas só em loopback (linhas 12-15): `- "127.0.0.1:8080:8080"`;
- healthcheck em forma exec array com `interval/timeout/retries/start_period` (linhas 18-23, 31-36);
- comentário pt-BR explicando decisões não óbvias (linhas 6-7);
- `name: notify-hub` no topo, sem `version:`; volumes nomeados no fim (linhas 38-40).
Serviços novos: copiar o trecho do RESEARCH Padrão 1 (api, worker, prometheus, grafana, k6 em `profiles: ["bench"]`). A porta 15692 já está mapeada e `rabbitmq_prometheus` já vem habilitado na imagem 4.3.6 (não montar `enabled_plugins`). Credenciais `notify/notify` (linhas 10-11) — passar `RABBITMQ_USERNAME/PASSWORD` se o `application.yml` das apps não usar esse default (conferir).

### `docker-compose.bench.yml` (config)

Override só com `environment:` de `worker` (e `api`): `VIRTUAL_THREADS`, concorrência, failure-rate 0, magic recipients off, bulkhead ≥ concorrência, `NOTIFY_CB_OPEN_WAIT` reduzido, sem profile `demo`. Mesmo estilo de chaves do compose base; rodar com `-p notify-bench`.

### Scripts (`scripts/explain-indices.js`, `scripts/bench/*`)

**Analog:** `scripts/smoke-lib.sh`
- Cabeçalho (linhas 1-14): shebang `#!/usr/bin/env bash`, comentário pt-BR com uso, pré-requisitos, "sem jq".
- Variáveis de raiz (linha 16): `RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"` (em `scripts/bench/` usar `../..`).
- Saída (linhas 35-40): `ok()`, `falhou()`, `info()`.
- Execução mongosh (linha 55):
```bash
mongo_eval() { MSYS_NO_PATHCONV=1 docker compose exec -T mongo mongosh notify --quiet --eval "$1" | tr -d '\r'; }
```
Para o explain: `mongo_eval "$(cat scripts/explain-indices.js)"`. As consultas devem refletir os 5 índices de `notify-common/.../persistence/MongoIndexInitializer.java` linhas 55-70 (status+channel+createdAt desc+_id desc; channel+createdAt+_id; createdAt+_id; idempotencyKey parcial único; status+createdAt+_id) e a query da varredura (`status: PENDING, updatedAt < cutoff, claimedAt: null`, `NotificationRepublisher.java` linhas 304-306).
- Polling com limite (linhas 126-129 `esperar_status`): `limite=$((SECONDS + timeout))` — usar no `run-bench.sh` para esperar a drenagem.

### `k6/demo.js`

Sem analog JS. Usar o esqueleto do RESEARCH Padrão 5. Contrato do corpo igual ao `post_notificacao` (`scripts/smoke-lib.sh` linhas 86-89: `POST $API_URL/notifications` com `Content-Type: application/json`). Sem imports remotos.

## Shared Patterns

### Métricas com tags fechadas e pré-registro
**Source:** `DeliveryMetrics.java` linhas 22-45, 70-129
**Apply to:** `PublishMetrics`, dashboard JSON, README (os nomes viram contrato)

### Configuração por ENV com default
**Source:** `notify-api/src/main/resources/application.yml` (`${MONGODB_URI:...}`, `${RABBITMQ_HOST:...}`)
**Apply to:** compose, override do benchmark — nunca hardcodar hosts no código.

### Loopback + healthcheck + `service_healthy`
**Source:** `docker-compose.yml` linhas 12-23
**Apply to:** api, worker, prometheus, grafana.

### Scripts bash portáveis Git Bash
**Source:** `scripts/smoke-lib.sh` linhas 1-55 (`MSYS_NO_PATHCONV=1`, `tr -d '\r'`, sem jq)
**Apply to:** `scripts/bench/run-bench.sh`, comandos do `docs/demo.md`.

### Idioma
Comentários e docs em pt-BR; identificadores, nomes de métrica e tags em inglês/minúsculo. `docs/` não cita fases, D-xx, WR-xx (D-11).

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `notify-*/Dockerfile`, `.dockerignore` | build config | batch | Primeiros Dockerfiles do projeto — RESEARCH Padrão 1 (copiar os 4 POMs, inclusive `notify-it/pom.xml`) |
| `observability/prometheus/prometheus.yml` | config | — | RESEARCH Padrão 3 (2 jobs RabbitMQ, `family=queue_coarse_metrics`) |
| `observability/grafana/**` | config | — | RESEARCH Padrão 4 + catálogo PromQL (datasource `uid: prometheus`, dashboard `uid: notify-hub`) |
| `k6/demo.js` | script | request-response | Sem JS no repo — RESEARCH Padrão 5 |
| `README.md`, `docs/*.md` | docs | — | Sem docs externos — RESEARCH Padrões 7/8 |

## Metadata

**Analog search scope:** `git ls-files` completo (notify-api, notify-worker, notify-common, scripts, raiz)
**Files scanned:** ~12
**Pattern extraction date:** 2026-11-01
