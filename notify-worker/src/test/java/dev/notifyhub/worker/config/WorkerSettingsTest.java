package dev.notifyhub.worker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Configuração do listener e do profile demo lida direto dos YAMLs, sem subir contexto. Fixa as
 * mitigações de mensagem venenosa (T-01-22): sem requeue, sem retry em memória.
 */
class WorkerSettingsTest {

    private static PropertySource<?> base;
    private static PropertySource<?> demo;

    @BeforeAll
    static void loadYaml() throws IOException {
        base = load("application.yml");
        demo = load("application-demo.yml");
    }

    private static PropertySource<?> load(String name) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(name, new ClassPathResource(name));
        assertThat(sources).as(name).hasSize(1);
        return sources.get(0);
    }

    @Test
    void rejectedMessagesAreNeverRequeuedNorRetriedInMemory() {
        assertThat(base.getProperty("spring.rabbitmq.listener.simple.default-requeue-rejected")).isEqualTo(false);
        assertThat(base.getProperty("spring.rabbitmq.listener.simple.retry.enabled")).isEqualTo(false);
        assertThat(base.getProperty("spring.rabbitmq.listener.simple.acknowledge-mode")).isEqualTo("auto");
    }

    @Test
    void listenersStartOnlyFromTheStartupRunnerWithLowPrefetch() {
        assertThat(base.getProperty("spring.rabbitmq.listener.simple.auto-startup")).isEqualTo(false);
        assertThat(base.getProperty("spring.rabbitmq.listener.simple.prefetch")).isEqualTo(10);
    }

    @Test
    void virtualThreadsAreOnByDefaultAndToggledByEnvironment() {
        assertThat(base.getProperty("spring.threads.virtual.enabled")).isEqualTo("${VIRTUAL_THREADS:true}");
    }

    @Test
    void workerListensOnItsOwnPortWithPerChannelConcurrency() {
        assertThat(base.getProperty("server.port")).isEqualTo(8081);
        assertThat(base.getProperty("notify.worker.channels.email.concurrency")).isEqualTo(10);
        assertThat(base.getProperty("notify.worker.channels.sms.concurrency")).isEqualTo(10);
        assertThat(base.getProperty("notify.worker.channels.webhook.concurrency")).isEqualTo(10);
    }

    @Test
    void confirmedPublicationsWaitAtMostFiveSecondsAndShareACachedChannelPool() {
        assertThat(base.getProperty("notify.worker.confirm-timeout")).isEqualTo("5s");
        assertThat(base.getProperty("spring.rabbitmq.cache.channel.size")).isEqualTo(50);
    }

    @Test
    void mvcMustacheViewAutoConfigurationIsExcluded() {
        assertThat(String.valueOf(base.getProperty("spring.autoconfigure.exclude")))
                .contains("org.springframework.boot.autoconfigure.mustache.MustacheAutoConfiguration");
    }

    @Test
    void actuatorExposesThePrometheusScrapeEndpoint() {
        assertThat(base.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,info,prometheus");
    }

    @Test
    void demoProfileUsesTheSameShortRetryLevelsAsTheApi() {
        assertThat(demo.getProperty("notify.topology.retry-levels")).isEqualTo("1s,5s,25s");
    }
}
