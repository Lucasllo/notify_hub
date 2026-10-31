package dev.notifyhub.it.support;

import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.notifyhub.api.NotifyApiApplication;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.messaging.TopologyProperties;
import dev.notifyhub.common.persistence.Collections;
import dev.notifyhub.worker.NotifyWorkerApplication;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.lifecycle.Startables;

/**
 * Base dos testes de integração (D-13, D-16): sobe a API e o worker reais, na mesma JVM do Failsafe,
 * contra um único RabbitMQ e um único MongoDB do Testcontainers, e oferece helpers de HTTP, broker e
 * Mongo para os cenários.
 *
 * <p><b>Containers.</b> Ficam em campos estáticos de {@link Containers}, importados como beans pelo
 * {@code @ImportTestcontainers} nos dois contextos, com {@code @ServiceConnection}. Os dois contextos
 * recebem a mesma instância de cada container. A extensão JUnit do Testcontainers não é usada de
 * propósito: ela para os containers de campos estáticos ao fim de cada classe de teste, e os contextos
 * precisam sobreviver a todas as classes da JVM.
 *
 * <p><b>Subida única.</b> {@link #startApps()} é idempotente: na primeira chamada inicia os containers,
 * sobe o worker (que cria os índices e semeia os templates) e depois a API. Os contextos vivem até o
 * fim da JVM; o shutdown hook do Spring fecha os dois, e o Ryuk do Testcontainers remove os containers.
 *
 * <p><b>Configuração.</b> Cada contexto recebe pela linha de comando um {@code spring.config.location}
 * próprio: o diretório {@code src/main/resources} do módulo (o {@code application.yml} de cada app, sem
 * a colisão de {@code classpath:/application.yml}) e {@code optional:classpath:/it/}, onde está o
 * {@code application-it.yml} do profile {@code it} (D-15). A porta HTTP é aleatória nos dois. Os apps
 * não mudam: nem {@code main}, nem YAML (RESEARCH Padrão 9, Armadilha 10).
 *
 * <p><b>Limpeza.</b> {@link #resetState()} roda antes de cada teste: espera as filas principais e de
 * delay esvaziarem, purga as 15 filas, remove os documentos de {@code notifications} (sem dropar a
 * coleção, preservando índices e {@code templates}) e reseta os circuit breakers do worker
 * (Armadilha 13). Nenhuma pausa fixa: toda espera é com Awaitility.
 */
public abstract class EndToEndSupport {

    /** Chave administrativa do profile {@code it} ({@code notify.admin.api-key} no application-it.yml). */
    public static final String ADMIN_API_KEY = "it-admin-key";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static ConfigurableApplicationContext worker;
    private static ConfigurableApplicationContext api;
    private static RuntimeException startFailure;

    /**
     * Containers compartilhados pelos dois contextos. Imagens com as mesmas tags exatas do compose
     * (T-03-01), nunca {@code latest}.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @ImportTestcontainers
    public static class Containers {

        @ServiceConnection
        public static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3.6-management");

        @ServiceConnection
        public static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0.32");
    }

    protected EndToEndSupport() {
        startApps();
    }

    /**
     * Sobe containers, worker e API uma vez por JVM. Uma falha na subida é guardada e relançada para os
     * testes seguintes, em vez de tentar subir um segundo worker.
     */
    public static synchronized void startApps() {
        if (startFailure != null) {
            throw startFailure;
        }
        if (api != null) {
            return;
        }
        try {
            Startables.deepStart(Containers.RABBIT, Containers.MONGO).join();
            // o worker primeiro: cria os índices e semeia os templates antes de a API aceitar pedidos
            worker = SpringApplication.from(NotifyWorkerApplication::main)
                    .with(Containers.class)
                    .run(appArgs("notify.it.worker-config", "notify-worker"))
                    .getApplicationContext();
            api = SpringApplication.from(NotifyApiApplication::main)
                    .with(Containers.class)
                    .run(appArgs("notify.it.api-config", "notify-api"))
                    .getApplicationContext();
        } catch (RuntimeException e) {
            startFailure = e;
            throw e;
        }
    }

    private static String[] appArgs(String configProperty, String module) {
        return new String[] {
                "--spring.config.location=" + moduleConfigUri(configProperty, module) + ",optional:classpath:/it/",
                "--spring.profiles.active=it",
                "--server.port=0"
        };
    }

    /**
     * URI {@code file:} do diretório de recursos do módulo, terminada em {@code /}. O caminho vem da
     * system property do Failsafe; sem ela (IDE), de {@code user.dir/../<módulo>/src/main/resources}.
     * A forma URI codifica espaços ({@code %20}) do caminho.
     */
    private static String moduleConfigUri(String configProperty, String module) {
        String configured = System.getProperty(configProperty);
        Path dir = configured != null && !configured.isBlank()
                ? Path.of(configured)
                : Path.of(System.getProperty("user.dir"), "..", module, "src", "main", "resources");
        Path normalized = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalStateException("diretório de configuração do " + module + " não existe: " + normalized);
        }
        String uri = normalized.toUri().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }

    // ---------------------------------------------------------------------------------------------
    // Acessores
    // ---------------------------------------------------------------------------------------------

    /** Contexto da API. */
    public static ConfigurableApplicationContext api() {
        startApps();
        return api;
    }

    /** Contexto do worker. */
    public static ConfigurableApplicationContext worker() {
        startApps();
        return worker;
    }

    /** URL absoluta de um caminho da API, na porta aleatória do contexto. */
    public static String apiUrl(String path) {
        Integer port = api().getEnvironment().getProperty("local.server.port", Integer.class);
        return "http://localhost:" + port + path;
    }

    // ---------------------------------------------------------------------------------------------
    // HTTP (cliente do JDK: um 401 ou 503 não vira exceção do cliente)
    // ---------------------------------------------------------------------------------------------

    /**
     * Resposta HTTP. {@code body} é o JSON lido de {@code raw}; corpo vazio ou não JSON vira nulo.
     */
    public record HttpResult(int status, HttpHeaders headers, String raw, JsonNode body) {

        /** Primeiro valor de um header, ou nulo. */
        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    /** POST com corpo JSON. {@code headerPairs} alterna nome e valor. */
    public static HttpResult post(String path, String json, String... headerPairs) {
        HttpRequest.Builder request = request(path, headerPairs)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        return send(request.build());
    }

    /** GET. */
    public static HttpResult get(String path) {
        return send(request(path).GET().build());
    }

    /** POST administrativo sem corpo, com {@code X-API-Key: ADMIN_API_KEY}. */
    public static HttpResult adminPost(String path) {
        return send(request(path, "X-API-Key", ADMIN_API_KEY)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
    }

    private static HttpRequest.Builder request(String path, String... headerPairs) {
        if (headerPairs.length % 2 != 0) {
            throw new IllegalArgumentException("headers precisam vir em pares nome, valor");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiUrl(path))).timeout(REQUEST_TIMEOUT);
        for (int i = 0; i < headerPairs.length; i += 2) {
            builder.header(headerPairs[i], headerPairs[i + 1]);
        }
        return builder;
    }

    private static HttpResult send(HttpRequest request) {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpResult(response.statusCode(), response.headers(), response.body(), parse(response.body()));
        } catch (IOException e) {
            throw new UncheckedIOException("falha no HTTP " + request.method() + " " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP interrompido: " + request.uri(), e);
        }
    }

    private static JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(raw);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Broker
    // ---------------------------------------------------------------------------------------------

    private static AmqpAdmin amqpAdmin() {
        return api().getBean(AmqpAdmin.class);
    }

    /** Mensagens prontas na fila (AMQP passivo, valor imediato). Fila inexistente lança. */
    public static int readyCount(String queue) {
        QueueInformation info = amqpAdmin().getQueueInfo(queue);
        if (info == null) {
            throw new IllegalStateException("fila não existe no broker: " + queue);
        }
        return info.getMessageCount();
    }

    /**
     * Mensagens entregues e ainda sem ack, pela API HTTP de gerenciamento do container. Essas
     * estatísticas são agregadas pelo broker a cada ~5s, então quem usa precisa esperar com
     * Awaitility, nunca ler uma vez só.
     */
    public static int unackedCount(String queue) {
        String url = Containers.RABBIT.getHttpUrl() + "/api/queues/%2F/" + URLEncoder.encode(queue, StandardCharsets.UTF_8);
        String credentials = Containers.RABBIT.getAdminUsername() + ":" + Containers.RABBIT.getAdminPassword();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .GET()
                .build();
        HttpResult result = send(request);
        if (result.status() != 200 || result.body() == null) {
            throw new IllegalStateException("API de gerenciamento respondeu " + result.status() + " para " + queue);
        }
        JsonNode unacked = result.body().get("messages_unacknowledged");
        return unacked == null || unacked.isNull() ? 0 : unacked.asInt();
    }

    /** Filas principais e de delay dos três canais (sem as DLQs). */
    public static List<String> mainAndDelayQueues() {
        TopologyProperties topology = api().getBean(TopologyProperties.class);
        List<String> queues = new ArrayList<>();
        for (Channel channel : Channel.values()) {
            queues.add(channel.queue());
            topology.retryLevels().forEach(level -> queues.add(channel.retryQueue(level)));
        }
        return queues;
    }

    /** As 15 filas da topologia: 3 principais, 9 de delay e 3 DLQs. */
    public static List<String> allQueues() {
        List<String> queues = new ArrayList<>(mainAndDelayQueues());
        for (Channel channel : Channel.values()) {
            queues.add(channel.dlq());
        }
        return queues;
    }

    // ---------------------------------------------------------------------------------------------
    // Mongo
    // ---------------------------------------------------------------------------------------------

    /** {@code MongoTemplate} da API (mesmo banco do worker). */
    public static MongoTemplate mongo() {
        return api().getBean(MongoTemplate.class);
    }

    /**
     * Faz polling de {@code GET /notifications/{id}} até o status pedido e devolve o corpo dessa
     * resposta. Estoura com a última resposta na mensagem se o prazo acabar.
     */
    public static JsonNode awaitStatus(String id, NotificationStatus status, Duration atMost) {
        HttpResult result = await("notificação " + id + " em " + status)
                .atMost(atMost)
                .pollInterval(Duration.ofMillis(50))
                .until(() -> get("/notifications/" + id),
                        r -> r.status() == 200 && r.body() != null
                                && status.name().equals(r.body().path("status").asText()));
        return result.body();
    }

    // ---------------------------------------------------------------------------------------------
    // Limpeza entre testes (D-16, Armadilha 13)
    // ---------------------------------------------------------------------------------------------

    /**
     * Isola cada teste: (1) espera as filas principais e de delay ficarem sem mensagens prontas por
     * 300ms, para nenhum token em voo cair depois do purge; (2) purga as 15 filas; (3) remove os
     * documentos de {@code notifications} sem dropar a coleção; (4) reseta os circuit breakers do worker.
     */
    @BeforeEach
    protected void resetState() {
        List<String> drained = mainAndDelayQueues();
        await("filas principais e de delay vazias")
                .atMost(Duration.ofSeconds(15))
                .during(Duration.ofMillis(300))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> drained.stream().allMatch(queue -> readyCount(queue) == 0));

        AmqpAdmin admin = amqpAdmin();
        allQueues().forEach(queue -> admin.purgeQueue(queue, false));

        mongo().remove(new Query(), Collections.NOTIFICATIONS);

        worker().getBean(CircuitBreakerRegistry.class).getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }
}
