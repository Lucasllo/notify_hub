package dev.notifyhub.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

class NotifyCommonConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(NotifyCommonConfiguration.class);

    private static List<String> queueNames(ApplicationContext context) {
        return context.getBean(Declarables.class).getDeclarablesByType(Queue.class).stream()
                .map(Queue::getName)
                .toList();
    }

    @Test
    void defaultLevelsAre5s25s125s() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(Declarables.class);
            assertThat(queueNames(context))
                    .contains("notify.webhook.retry.5s", "notify.webhook.retry.25s", "notify.webhook.retry.125s");
        });
    }

    @Test
    void demoLevelsComeFromProperty() {
        runner.withPropertyValues("notify.topology.retry-levels=1s,5s,25s").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(queueNames(context))
                    .contains("notify.sms.retry.1s", "notify.sms.retry.5s", "notify.sms.retry.25s")
                    .noneMatch(name -> name.endsWith(".125s"));
        });
    }

    @Test
    void millisecondLevelsProduceMsLabels() {
        runner.withPropertyValues("notify.topology.retry-levels=200ms,500ms,1s").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(queueNames(context))
                    .contains("notify.email.retry.200ms", "notify.email.retry.500ms", "notify.email.retry.1s");
        });
    }

    @Test
    void nonIncreasingLevelsFailStartup() {
        runner.withPropertyValues("notify.topology.retry-levels=25s,5s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .rootCause()
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("estritamente crescente");
                });
    }

    @Test
    void exposesExactlyOneJsonMessageConverter() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(MessageConverter.class);
            assertThat(context.getBean(MessageConverter.class)).isInstanceOf(Jackson2JsonMessageConverter.class);
        });
    }
}
