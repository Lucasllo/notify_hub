# Stack Research

**Domain:** Multichannel notification service (Spring AMQP / RabbitMQ retry+DLQ, MongoDB persistence, Resilience4j, Java 21 virtual threads, Prometheus/Grafana observability), portfolio backend
**Researched:** 2026-10-09
**Confidence:** HIGH for versions, MEDIUM for the virtual-thread runtime behavior and Grafana 13 notes

> **Confidence tiers.** HIGH means checked directly against a primary artifact: Maven Central `maven-metadata.xml`, the Spring Boot 3.5.16 BOM POM, Spring Boot source at tag `v3.5.16`, the Docker Hub tag API, GitHub release notes, or endoflife.date. MEDIUM means more than one web source agrees. LOW means a single web source (the GSD `classify-confidence` seam rates an unverified websearch/webfetch result as LOW, and a cross-checked one as MEDIUM).

---

## Headline decision: Spring Boot 3.5.16 (the final 3.x OSS release)

**You can still use Spring Boot 3.x, but 3.5 is out of OSS support.** Facts (HIGH, from endoflife.date and Maven Central):

| Line | Status on 2026-10-09 | Latest |
|------|----------------------|--------|
| **3.5** | **OSS support ended 2026-06-30.** Commercial (Tanzu) support runs until 2032-06-30 | **3.5.16** (2026-06-25), the last OSS patch |
| 3.4 | OSS ended 2025-12-31 | 3.4.13 |
| 4.0 | OSS until 2026-12-31 | 4.0.8 |
| 4.1 | Current, OSS until 2027-07-31 | 4.1.1 (4.2.0-M2 in milestones) |

**Recommendation: pin `spring-boot-starter-parent` 3.5.16, because the user constraint is Boot 3.x.** No newer 3.x release will come out. Do not use 3.4.x or older, which have been out of OSS support even longer. Write a short "Por que Spring Boot 3.5 e não 4.x" note in the README. It should say this was a deliberate choice, that 3.5.16 is the final OSS 3.x release, and that moving to Boot 4.x is a planned next step (Spring AMQP 4, Jackson 3, Testcontainers 2, `resilience4j-spring-boot4`, `spring-boot-starter-aop` renamed). A reviewer will see this as awareness of the situation, not as an outdated choice.

**Rule: let the Boot 3.5.16 BOM manage every version it covers.** Do not set your own versions for Spring AMQP, the Mongo driver, Micrometer, Testcontainers, JMustache, JUnit or Awaitility. The BOM versions were tested together. Overriding them is the most common way to break a Boot app.

---

## Recommended Stack

### Core Technologies

| Technology | Version | Purpose | Why Recommended | Conf. |
|------------|---------|---------|-----------------|-------|
| Java (Eclipse Temurin) | **21** (LTS; latest GA 21.0.12) | Language/runtime, virtual threads (JEP 444) | Required by the user. Boot 3.5 supports Java 17–25. Set `<java.version>21</java.version>` (compiles with `--release 21`). | HIGH |
| Spring Boot | **3.5.16** | Application framework, BOM, autoconfiguration | The final 3.x OSS release (see above). Includes VT-aware autoconfiguration for Tomcat, `@RabbitListener` containers, `@Async` and `@Scheduled`. | HIGH |
| Spring Framework | 6.2.19 (managed) | Core | Comes with Boot 3.5.16. Do not override. | HIGH |
| Spring AMQP (`spring-rabbit`) | **3.2.12** (managed) | RabbitTemplate with publisher confirms/returns, `@RabbitListener`, `Declarables` topology | The standard for RabbitMQ on Spring. Supports declaring the topology in code, correlated publisher confirms and `RabbitTemplate.receive()` for DLQ draining. | HIGH |
| RabbitMQ Java client (`amqp-client`) | 5.25.0 (managed) | AMQP 0-9-1 wire client | Pulled in by spring-rabbit. Works with RabbitMQ 4.x over AMQP 0-9-1. | HIGH |
| Spring Data MongoDB | **4.5.13** (managed, Spring Data 2025.0.13) | Repositories, `MongoTemplate`, index creation, auditing | The standard Spring Mongo layer. Use `MongoTemplate` for atomic `findAndModify`/`updateFirst` (status transitions plus `$push` of attempt history). Use repositories for simple reads. | HIGH |
| MongoDB Java sync driver | **5.5.2** (managed) | Wire driver | Supports MongoDB 8.0 server. Since 4.11 the driver avoids needless virtual-thread pinning internally, so it is VT-friendly. | HIGH (version) / MEDIUM (VT claim) |
| Resilience4j | **2.4.0** (`resilience4j-spring-boot3`) | Circuit breaker and bulkhead per channel adapter | The de facto fault-tolerance library for Spring Boot 3 (Hystrix is dead, and Spring Cloud CircuitBreaker would only add a wrapper). Released 2026-03-14. Bytecode targets Java 17 (class version 61, verified), so it runs fine on 21. Pulls in `resilience4j-micrometer` for metrics automatically. | HIGH |
| Micrometer | **1.15.12** (managed) + `micrometer-registry-prometheus` | Counters, timers, Prometheus endpoint | Built into Actuator. Boot 3.5 uses the Prometheus Java client 1.x (`prometheus-client` 1.3.10), which works with Prometheus 3.x scraping. | HIGH |
| JMustache (`com.samskivert:jmustache`) | **1.16** (managed by Boot) | Rendering Mustache templates stored in Mongo | Already in Boot's BOM, so add it without a version. It has no dependencies. Compiled `Template` objects are thread-safe and cacheable. By default it **throws `MustacheException` on a missing variable**, which maps directly to the "variável faltando → falha permanente" requirement. | HIGH (version) / MEDIUM (default missing-var behavior; check in the renderer unit test) |
| Maven | **3.9.x** (latest 3.9.16) via Maven Wrapper 3.3.4 | Multi-module build | Maven 4 is still RC (4.0.0-rc-7), so do not use it. Use the wrapper (`mvnw`) so reviewers do not need Maven installed. | HIGH |

### Infrastructure (Docker images, pinned for reproducibility)

| Image | Tag (pin this) | Purpose | Why | Conf. |
|-------|----------------|---------|-----|-------|
| RabbitMQ | **`rabbitmq:4.3.6-management`** (floating: `rabbitmq:4.3-management`) | Broker plus management UI on 15672 | 4.3 is the only RabbitMQ line still in community support (4.2 OSS ended 2026-07-31). TTL plus dead-letter exchanges are native, so the delayed-message plugin is not needed. The management UI shows the per-level retry queues, as the demo requires. | HIGH |
| MongoDB | **`mongo:8.0.32`** (floating: `mongo:8.0`) | Document store | 8.0 is the long-support major (EOL 2029-10). Do **not** use `mongo:9.0`: it came out 2026-09-30, after the driver Boot 3.5 manages (5.5.2), so that pairing is not in the official compatibility matrix. Avoid 8.2/8.3 too: 8.2 was a Rapid Release and is now EOL, and 8.3 adds nothing this project needs. | HIGH |
| Prometheus | **`prom/prometheus:v3.13.4`** | Scrapes `/actuator/prometheus` from api and worker | v3.13 is the current **LTS** (EOL 2027-07-31). v3.15 is newer but its support window is only about 6 weeks. Use LTS for a demo that has to keep working. | HIGH |
| Grafana | **`grafana/grafana:12.4.12`** | Provisioned dashboard | **Avoid Grafana 13 for this project.** Grafana 13 changed the default dashboard format to the v2 schema and dropped "Classic export". Several issues report that file-provisioned or GUI-exported dashboards fail to load (`grafana/grafana#123607`, `#122663`). 12.4 still accepts classic JSON provisioning without workarounds and gets security fixes until 2027-05-24. | HIGH (tags/EOL) / MEDIUM (Grafana 13 provisioning breakage, from issue reports) |
| Java runtime base | **`eclipse-temurin:21-jre`** | Runtime image for api/worker | Official Temurin image, smaller JRE. | HIGH |
| Build stage | **`maven:3.9-eclipse-temurin-21`** | Multi-stage Dockerfile build | Lets `docker compose up --build` work on a clean machine with no local JDK or Maven. This is a core reproducibility requirement. | HIGH |
| k6 | **`grafana/k6:2.3.0`** | Load generation and VT-vs-platform benchmark | k6 2.x is current (2.0.0 shipped 2026-05-11, 2.3.0 on 2026-09-21). Run it as a container on the compose network so reviewers do not need to install it. | HIGH |

### Supporting Libraries

| Library | Version | Purpose | When to Use | Conf. |
|---------|---------|---------|-------------|-------|
| `spring-boot-starter-web` | managed | REST API (Tomcat) in `notify-api` | API module only. With VT enabled, Tomcat runs each request on a virtual thread. | HIGH |
| `spring-boot-starter-amqp` | managed | Spring AMQP plus Boot autoconfig | Both api (publisher) and worker (consumer). | HIGH |
| `spring-boot-starter-data-mongodb` | managed | Spring Data MongoDB (sync, **not** reactive) | Both modules. Stay blocking because the point of the demo is blocking code on virtual threads. | HIGH |
| `spring-boot-starter-validation` | managed | Bean Validation on `POST /notifications` DTOs | API module. Invalid channel or missing recipient should return 400 before anything is persisted. | HIGH |
| `spring-boot-starter-actuator` | managed | `/actuator/health`, `/actuator/prometheus` | Both modules. Also required by Resilience4j health indicators and metrics. | HIGH |
| `micrometer-registry-prometheus` | managed | Prometheus exposition | Both modules. | HIGH |
| `spring-boot-starter-aop` | managed | Proxies for the `@CircuitBreaker`/`@Bulkhead` annotations | Worker. `resilience4j-spring-boot3` does **not** pull in AOP transitively, and without it the annotations are silently ignored. You can skip it only if you use the programmatic `CircuitBreakerRegistry`/`Decorators` API. | HIGH |
| `io.github.resilience4j:resilience4j-spring-boot3` | **2.4.0** (not in Boot BOM, so pin it, ideally via `resilience4j-bom` 2.4.0) | CB and bulkhead config from `application.yml`, registries, metrics, health | Worker. | HIGH |
| `com.samskivert:jmustache` | managed (1.16) | Template rendering | Worker, or `notify-common` if the API validates templates too. Do **not** use `spring-boot-starter-mustache`, which brings MVC view-resolver wiring you do not need. | HIGH |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | **2.8.17** | Swagger UI for the API | Optional but cheap, and good for a portfolio piece. **Only the 2.8.x line works with Boot 3.5.** springdoc 3.x targets Boot 4. | HIGH |
| `spring-boot-starter-test` | managed (JUnit 5.12.2, AssertJ 3.27.7, Mockito 5.17.0, Awaitility via test dep) | Unit and integration tests | All modules. | HIGH |
| `org.awaitility:awaitility` | managed (4.2.2) | Asserting async outcomes (retry → DLQ → reprocess) | Integration tests. **Do not use `Thread.sleep`** to wait for TTLs. Use `await().atMost(...)`. | HIGH |
| `spring-boot-testcontainers` | managed | `@ServiceConnection` for containers | Integration tests. Wires Rabbit/Mongo connection details automatically, so `@DynamicPropertySource` is not needed. | HIGH |
| `org.testcontainers:junit-jupiter`, `org.testcontainers:rabbitmq`, `org.testcontainers:mongodb` | **1.21.4** (managed by Boot 3.5.16) | Real RabbitMQ and MongoDB in tests | Integration tests. 1.21.4 also fixes the **Docker Engine 29** handshake failure ("client version 1.32 is too old") that broke 1.21.3 and earlier. Docker Desktop on Windows ships Engine 29+, so this matters here. | HIGH (version) / MEDIUM (Docker 29 fix, from multiple reports) |

### Development Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| Maven Wrapper 3.3.4 | Reproducible builds | `mvn wrapper:wrapper -Dmaven=3.9.16`. Commit `.mvn/` and `mvnw`/`mvnw.cmd` (the author develops on Windows). |
| `maven-surefire-plugin` / `maven-failsafe-plugin` 3.5.6 (managed) | Unit vs integration tests | Name integration tests `*IT.java` and run them with failsafe in `verify`. Keep `mvn test` fast and Docker-free so the renderer unit test always runs. |
| Docker Compose v2 (`docker compose`) | Single-command demo | No top-level `version:` key (it is obsolete). Use `healthcheck` + `depends_on: condition: service_healthy` for rabbitmq and mongo so api/worker never start against a broker that is not ready. Put k6 under `profiles: ["bench"]` so a plain `docker compose up` does not start a load test. |
| RabbitMQ management UI (:15672) | Showing the topology (retry levels, DLQs) | Enable `rabbitmq_prometheus` (port 15692) too, by mounting an `enabled_plugins` file `[rabbitmq_management,rabbitmq_prometheus].`. Broker-side queue-depth metrics in Grafana are a strong visual. |
| JFR / `-Djdk.tracePinnedThreads=full` | Detecting virtual-thread pinning on JDK 21 | Boot docs explicitly recommend JFR (`jdk.VirtualThreadPinned` event) or `jcmd`. Good evidence for the benchmark README section. |

---

## Installation

Parent POM (root `pom.xml`, `<packaging>pom</packaging>`, modules `notify-common`, `notify-api`, `notify-worker`):

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.5.16</version>
  <relativePath/>
</parent>

<properties>
  <java.version>21</java.version>
  <resilience4j.version>2.4.0</resilience4j.version>
  <springdoc.version>2.8.17</springdoc.version>
  <!-- Do NOT override: spring-amqp, mongodb, micrometer, testcontainers, jmustache -->
</properties>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.resilience4j</groupId>
      <artifactId>resilience4j-bom</artifactId>
      <version>${resilience4j.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
    <dependency>
      <groupId>${project.groupId}</groupId>
      <artifactId>notify-common</artifactId>
      <version>${project.version}</version>
    </dependency>
  </dependencies>
</dependencyManagement>
```

`notify-common` (plain jar: DTOs/records, event payloads, topology constants such as exchange/queue/routing-key names and TTL levels, and error classification enums). **It must not apply the `spring-boot-maven-plugin` repackage goal**, because a repackaged fat jar cannot be used as a dependency. Keep its Spring dependencies minimal (at most `spring-amqp` for the `Declarables` bean definitions if you share topology declaration code).

`notify-api`:
```
spring-boot-starter-web, spring-boot-starter-validation, spring-boot-starter-amqp,
spring-boot-starter-data-mongodb, spring-boot-starter-actuator,
micrometer-registry-prometheus, springdoc-openapi-starter-webmvc-ui (2.8.17), notify-common
test: spring-boot-starter-test, spring-boot-testcontainers,
      org.testcontainers:junit-jupiter, org.testcontainers:rabbitmq, org.testcontainers:mongodb, awaitility
```

`notify-worker`:
```
spring-boot-starter-amqp, spring-boot-starter-data-mongodb, spring-boot-starter-actuator,
spring-boot-starter-aop, io.github.resilience4j:resilience4j-spring-boot3,
micrometer-registry-prometheus, com.samskivert:jmustache, notify-common,
spring-boot-starter-web (only to expose /actuator/prometheus over HTTP)
test: same as api
```

> Note: the worker **does need an HTTP server** for Prometheus to scrape `/actuator/prometheus`. Add `spring-boot-starter-web` and run it on a separate port (for example 8081). This is easy to miss.

Key `application.yml` settings (verified property names in Boot 3.5.16 `RabbitProperties`):

```yaml
spring:
  threads.virtual.enabled: ${VIRTUAL_THREADS:true}   # toggled by env var for the benchmark
  main.keep-alive: true                               # VTs are daemon threads (Boot docs)
  rabbitmq:
    publisher-confirm-type: correlated                # api
    publisher-returns: true                           # api
    template.mandatory: true                          # api: unroutable -> returned, not dropped
    listener.simple:
      acknowledge-mode: auto                          # or manual; see ARCHITECTURE
      default-requeue-rejected: false                 # failures must NOT hot-loop requeue
      prefetch: 10
      concurrency: ${WORKER_CONCURRENCY:50}           # still applies with VTs (consumer count)
      max-concurrency: ${WORKER_CONCURRENCY:50}
      observation-enabled: true
management:
  endpoints.web.exposure.include: health,info,prometheus,metrics
  metrics.tags.application: ${spring.application.name}
```

---

## Virtual threads: what `spring.threads.virtual.enabled=true` actually does (Boot 3.5)

Verified in the Boot 3.5.16 reference docs and source at tag `v3.5.16` (HIGH):

1. **`@RabbitListener` containers.** `RabbitAnnotationDrivenConfiguration` registers a `SimpleRabbitListenerContainerFactoryConfigurer` with `new VirtualThreadTaskExecutor("rabbit-simple-")` (and `"rabbit-direct-"` for the direct container) under `@ConditionalOnThreading(Threading.VIRTUAL)`. Each **consumer** then runs on a virtual thread.
   - **Important consequence for the benchmark:** the number of concurrent message handlers is still `listener.simple.concurrency` (one long-lived consumer thread per consumer). With a simple container, VTs do **not** create extra parallelism by themselves. They make a **high** consumer count (hundreds) cheap. A fair benchmark keeps `concurrency` identical (and high, for example 200–500) in both runs with a blocking simulated adapter latency (for example 100–300 ms), then compares drain time, `jvm_threads_live_threads`, memory and p95 latency. Platform threads at concurrency 500 means 500 OS threads. VTs at 500 means a handful of carrier threads. Comparing VT at concurrency 500 against platform threads at a "default" of 1 would prove nothing and a reviewer would notice.
   - If you declare your **own** `SimpleRabbitListenerContainerFactory` bean (for example per-channel factories), build it with the injected `SimpleRabbitListenerContainerFactoryConfigurer.configure(factory, connectionFactory)`. Otherwise you **lose the VT executor** without any warning.
2. **Tomcat (api).** Requests run on virtual threads, and `server.tomcat.threads.max` **stops having any effect** (Boot docs: "properties which configure thread pools don't have an effect anymore"). The API is a second good benchmark target: k6 at 1000+ VUs against `POST /notifications`, where each request blocks on a Mongo insert and a publisher-confirm wait, compared with the 200-thread platform pool.
3. **`@Async` / `@Scheduled` / `SimpleAsyncTaskExecutor`** use VTs. VTs are daemon threads, so set `spring.main.keep-alive=true`. This is recommended in the Boot docs and matters if the worker has no non-daemon threads.
4. **MongoDB driver.** It has no Spring executor. Blocking calls run on the caller's virtual thread. Driver 4.11+ avoids unnecessary pinning (MEDIUM). Under VTs, **the connection pool (`maxPoolSize`, default 100) becomes the real concurrency limiter**. Raise it via the connection string (`?maxPoolSize=...`) if consumer concurrency goes above about 100. Otherwise the benchmark measures pool wait instead of thread cost.
5. **Pinning on JDK 21 (MEDIUM).** On Java 21–23, blocking inside `synchronized` pins the carrier thread. JEP 491 fixed this in **JDK 24**, which is why the Boot docs "strongly recommend Java 24 or later". A known amqp-client issue (rabbitmq-java-client#1276) shows pinning in the frame-write path with VTs. Mitigation: stay on Java 21 as required, run the benchmark with JFR enabled, and report any `jdk.VirtualThreadPinned` events honestly in the README. Optionally add a footnote that Java 25 LTS would remove `synchronized` pinning. This shows understanding of the topic.
6. **Resilience4j with VTs.** Use the **semaphore `Bulkhead`**, not `ThreadPoolBulkhead`. A thread-pool bulkhead pushes work onto a fixed platform-thread pool, which defeats VTs and changes the semantics. The semaphore bulkhead limits concurrent calls per adapter, so "bulkhead full → `BulkheadFullException` → transient failure → retry" maps directly onto the requirements.

---

## k6 usage (v2.3.0)

- **Demo script (1000 notifications):** `executor: 'shared-iterations', vus: 50, iterations: 1000` POSTing random channels and templates. Run it with `docker compose --profile bench run --rm k6 run /scripts/demo.js` against `http://notify-api:8080`. Add `thresholds` (for example `http_req_failed: ['rate<0.01']`) so the script fails clearly when something is wrong.
- **Benchmark:** use `constant-arrival-rate` (open model: fixed RPS regardless of latency) for the API comparison. The closed-model `constant-vus` hides saturation. For the worker comparison the load is the queue backlog, not HTTP. Publish N messages, then measure drain time via a Prometheus query on the sent counter, or poll `GET /notifications?status=SENT`.
- **k6 2.x breaking changes do not affect this use.** They removed old cloud/login commands, the `externally-controlled` executor and `k6/experimental/redis`, and the HTTP API server no longer starts by default. Plain `k6 run` scripts with standard executors work unchanged. Do not follow 1.x-era blog posts that use `externally-controlled` (HIGH, from the v2.0.0 release notes).
- Optional: push k6 metrics to Prometheus via `-o experimental-prometheus-rw`. This requires `--web.enable-remote-write-receiver` on Prometheus. Nice to have, but defer it.

---

## Alternatives Considered

| Recommended | Alternative | When to Use Alternative |
|-------------|-------------|-------------------------|
| Spring Boot 3.5.16 | Spring Boot 4.1.1 | Any project without the "Boot 3.x" constraint. 4.x is the supported line. |
| TTL+DLX delay queues per level | `rabbitmq_delayed_message_exchange` plugin | Out of scope by decision. The plugin also has known scalability limits and is not replicated. |
| TTL+DLX delay queues per level | Spring AMQP `RetryInterceptorBuilder` (in-memory stateless retry) | Only for very short in-process retries. It blocks the consumer during backoff, is invisible in the management UI and loses state on crash. That is the opposite of what this project wants to show. |
| Classic durable queues (single-node demo) | Quorum queues (`x-queue-type: quorum`) | Production clusters. Quorum queues support TTL and DLX, but add a default delivery limit (20 in 4.x) and at-most-once dead-lettering by default. That is extra semantics to explain for little gain on one node. |
| Resilience4j 2.4.0 | Spring Cloud CircuitBreaker | When you need a vendor-neutral abstraction. Here it only adds a layer. |
| JMustache 1.16 | `mustache.java` (Spullara) | When you need partials from a filesystem or very large-scale compilation. JMustache is simpler, in Boot's BOM, and handles string templates from Mongo well. |
| Testcontainers 1.21.4 | Testcontainers 2.0.5 | With Boot 4.x (which manages 2.x). In 2.x the artifacts are renamed (`testcontainers-rabbitmq`, `testcontainers-mongodb`) and the classes moved to `org.testcontainers.rabbitmq`/`.mongodb` (the old packages are only kept as deprecated). Boot 3.5's `@ServiceConnection` factories import the 1.x classes `org.testcontainers.containers.RabbitMQContainer`/`MongoDBContainer`. |
| k6 | Gatling / JMeter | Gatling if you want a JVM-native load tool with HTML reports. k6 is lighter, scriptable in JS and runs from a container in one line. |
| Multi-stage Dockerfile | `spring-boot:build-image` (Paketo buildpacks) | When CI builds images. It needs a Maven run before `compose up`, which breaks the "only `docker compose up`" requirement. |
| Sync Spring Data MongoDB | Reactive (`spring-boot-starter-data-mongodb-reactive`, WebFlux) | Never here. Reactive would make the virtual-thread story pointless. |

---

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| Spring Boot ≤ 3.4.x | OSS support ended earlier. 3.5.16 is the newest and last 3.x OSS release | 3.5.16 |
| Overriding `spring-amqp.version`, `mongodb.version`, `testcontainers.version`, `micrometer.version` | Breaks the tested BOM matrix and leads to strange autoconfig failures | Boot-managed versions |
| `mongo:9.0` image | Newer than Boot 3.5's driver (5.5.2), so not a validated pairing | `mongo:8.0.32` |
| `grafana/grafana:13.x` (for now) | v2 dashboard schema plus removed Classic export leads to file-provisioning failures reported in 13.0.x | `grafana/grafana:12.4.12` with classic JSON + `provisioning/dashboards/*.yaml` |
| `prom/prometheus:latest` / non-LTS 3.15 | Floating tag gives a non-reproducible demo, and the short support window | `prom/prometheus:v3.13.4` (LTS) |
| `:latest` tags anywhere | The demo must run the same way months from now | Exact tags in the table above |
| `ThreadPoolBulkhead` | Moves work to a platform pool, which defeats VTs | Semaphore `Bulkhead` |
| `RetryInterceptorBuilder` / `spring.rabbitmq.listener.simple.retry.enabled=true` | In-memory blocking retry, competes with the DLX retry design and hides attempts | Explicit routing to delay queues `retry.5s` → `retry.30s` → `retry.2m` → DLQ |
| Requeue on failure (`default-requeue-rejected: true`, `basicNack(requeue=true)`) | Hot-loops a failing message at full speed and starves the queue | `default-requeue-rejected: false` and publish explicitly to the next delay level |
| Per-message TTL (`expiration` property) in one shared delay queue | Head-of-line blocking: RabbitMQ only expires messages at the head | Queue-level `x-message-ttl` per level (already decided) |
| `x-queue-mode: lazy` / `x-queue-version: 1` arguments | **RabbitMQ 4.3 rejects these declarations** (CQv1 removed) | Omit them. CQv2 is the default |
| Non-durable, non-exclusive queues (`new Queue(name, false)`) | **Rejected by default in RabbitMQ 4.3** (`transient_nonexcl_queues` denied) | `QueueBuilder.durable(name)` |
| `spring.rabbitmq.listener.simple.global-qos=true` | `global_qos` is denied by default in RabbitMQ 4.3 | Default per-consumer prefetch |
| Testcontainers 2.x with Boot 3.5 | Not what Boot 3.5 manages or `@ServiceConnection` targets | Boot-managed 1.21.4 |
| Testcontainers ≤ 1.21.3 | Fails against Docker Engine 29 (API version handshake) | 1.21.4 (managed by 3.5.16) |
| springdoc 3.x | Built for Spring Boot 4 / Framework 7 | springdoc 2.8.17 |
| `spring-boot-starter-mustache` | Pulls in MVC view resolution, which a string-template renderer does not need | `com.samskivert:jmustache` directly |
| Lombok | Not needed on Java 21. Records cover DTOs and events, and it is one less annotation processor in a multi-module build | Java records |
| `Thread.sleep` in integration tests | Flaky with TTL timing | Awaitility, plus short test-only TTLs (for example 200ms/500ms/1s via properties) so the retry → DLQ test runs in seconds |
| Maven 4.0.0-rc | Still a release candidate | Maven 3.9.16 via wrapper |

---

## Stack Patterns by Variant

**If running the virtual-vs-platform benchmark:**
- Toggle only `VIRTUAL_THREADS=true|false` (env → `spring.threads.virtual.enabled`). Keep `WORKER_CONCURRENCY`, prefetch, adapter latency and failure rate (set it to 0% for the benchmark) and Mongo `maxPoolSize` the same.
- Because changing anything else turns the result into a comparison of configurations, not threading models.

**If the integration test (retry → DLQ → reprocess) must be fast:**
- Make the delay TTLs configurable (`notify.retry.levels=200ms,500ms,1s` in the test profile, `5s,30s,2m` in the demo). Use `rabbitmq:4.3.6-management` and `mongo:8.0.32` in Testcontainers, the same tags as compose.
- Because 2m TTLs would make CI slow, and using the same images means tests and demo exercise the same broker behavior.

**If a reviewer runs on Apple Silicon or ARM:**
- Every recommended image (rabbitmq, mongo 8.0, prometheus, grafana, eclipse-temurin, grafana/k6) is multi-arch, so no `platform:` overrides are needed. (MEDIUM: these official images publish arm64 variants; not checked per tag.)

---

## Version Compatibility

| Package A | Compatible With | Notes |
|-----------|-----------------|-------|
| spring-boot 3.5.16 | Java 17–25 | Java 21 required by user. Docs recommend 24+ for VTs (pinning). |
| spring-boot 3.5.16 | spring-amqp 3.2.12, amqp-client 5.25.0 | BOM-managed. |
| spring-amqp 3.2.12 (AMQP 0-9-1) | RabbitMQ 4.3.6 | Works. Avoid features 4.3 denies by default (transient non-exclusive queues, global QoS, CQv1 args). |
| spring-boot 3.5.16 | spring-data-mongodb 4.5.13, mongodb-driver-sync 5.5.2 | BOM-managed. |
| mongodb-driver 5.5.2 | MongoDB server 8.0.x | Supported. 9.0 is not validated for this driver. |
| resilience4j-spring-boot3 2.4.0 | Spring Boot 3.x, Java 17+ bytecode | Needs `spring-boot-starter-aop` for annotations and actuator for health/metrics. 2.4.0 also adds a separate `resilience4j-spring-boot4` module for Boot 4. |
| testcontainers 1.21.4 | spring-boot-testcontainers 3.5.16, Docker Engine 29+ | 1.21.4 fixed the Docker 29 API-version handshake. |
| micrometer 1.15.12 + prometheus-client 1.3.10 | Prometheus 3.13 LTS | Prometheus 3 is strict about scrape Content-Type. Actuator's endpoint is compliant. |
| springdoc 2.8.17 | Spring Boot 3.5 | 3.x needs Boot 4. |
| jmustache 1.16 | Java 21 | Zero-dependency. Last release Dec 2023, so the library is stable and finished rather than abandoned. |
| grafana 12.4.12 | Prometheus 3.13 datasource | Classic dashboard JSON provisioning works. |
| k6 2.3.0 | Plain HTTP scripts | 2.x removals do not affect `k6 run` with standard executors. |

### Known CVE note (for the README's "limitations" section)

Scanners will flag `mongodb-driver-sync` 5.5.2 for **CVE-2026-18710** (SOCKS5 proxy credentials in `toString()` logs, fixed in 5.9.2) and **CVE-2026-88033** (GridFS filter injection, fixed in 5.11.1). **Neither path is used here** (no SOCKS5 proxy, no GridFS). Keep the Boot-managed driver. Overriding `mongodb.version` to 5.13.0 would probably work, because 5.x minors are backward compatible, but Spring Data 4.5 was not tested against it. This is the practical cost of an EOL Boot line, and it is worth one honest sentence in the README. (MEDIUM: from OSV, the Presto PR and MongoDB release notes.)

---

## Sources

- Maven Central `maven-metadata.xml` for spring-boot-dependencies, spring-rabbit, mongodb-driver-sync, micrometer-core, resilience4j-spring-boot3/bom, testcontainers (1.x and 2.x artifacts), jmustache, springdoc, maven-core, maven-wrapper, awaitility. Exact versions verified (HIGH)
- `spring-boot-dependencies-3.5.16.pom`. Managed versions: spring-amqp 3.2.12, mongodb 5.5.2, micrometer 1.15.12, testcontainers 1.21.4, jmustache 1.16, spring-data 2025.0.13, Framework 6.2.19, JUnit 5.12.2, prometheus-client 1.3.10 (HIGH)
- Spring Boot source `v3.5.16`: `RabbitAnnotationDrivenConfiguration.java` (VirtualThreadTaskExecutor "rabbit-simple-"/"rabbit-direct-"), `RabbitProperties.java` (prefetch, concurrency, observation-enabled), `RabbitContainerConnectionDetailsFactory`/`MongoContainerConnectionDetailsFactory` (TC 1.x imports) (HIGH)
- https://docs.spring.io/spring-boot/3.5/reference/features/spring-application.html. Virtual threads section: Java 24+ recommended, thread-pool properties ignored, `spring.main.keep-alive` (HIGH)
- https://endoflife.date/api/spring-boot.json, rabbitmq.json, mongodb.json, prometheus.json, grafana.json. Support windows and latest patches (HIGH)
- Docker Hub tag API. Confirmed existence of rabbitmq:4.3.6-management, mongo:8.0.32, prom/prometheus:v3.13.4, grafana/grafana:12.4.12 and 13.2.3, grafana/k6:2.3.0, eclipse-temurin:21-jre, maven:3.9-eclipse-temurin-21 (HIGH)
- https://github.com/rabbitmq/rabbitmq-server/releases/tag/v4.3.0. CQv1 removed, transient non-exclusive queues and global_qos denied by default, Khepri only (HIGH)
- https://github.com/resilience4j/resilience4j/releases/tag/v2.4.0. Boot 4 support added, JDK target set back to 17 (class version 61 verified in jar) (HIGH)
- https://github.com/grafana/k6/releases/tag/v2.0.0. k6 2.x breaking changes (HIGH)
- https://github.com/grafana/grafana/issues/123607, https://github.com/grafana/grafana/issues/122663, https://community.grafana.com/t/provisioning-in-grafana-13-with-v2-dashboards/163029. Grafana 13 provisioning breakage (MEDIUM)
- https://github.com/testcontainers/testcontainers-java/issues/11235, https://www.coffeesprout.nl/en/testcontainers-docker29-api-too-old.html. Docker 29 vs Testcontainers ≤1.21.3 (MEDIUM)
- https://github.com/rabbitmq/rabbitmq-java-client/issues/1276. amqp-client pinning with VTs (MEDIUM)
- https://www.mongodb.com/community/forums/t/mongodb-java-driver-4-11-released/248657, https://mongodb.github.io/mongo-java-driver/4.11/whats-new/. Driver VT support (MEDIUM)
- https://osv.dev/vulnerability/CVE-2026-18710, https://github.com/prestodb/presto/pull/28537. Mongo driver CVEs (MEDIUM)
- https://api.adoptium.net. Temurin 21.0.12 is the latest 21 GA (HIGH)

---
*Stack research for: multichannel notification service (RabbitMQ retry/DLQ + MongoDB + Resilience4j + Java 21 VTs)*
*Researched: 2026-10-09*
