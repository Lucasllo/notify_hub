package dev.notifyhub.worker.config;

import dev.notifyhub.common.NotifyCommonConfiguration;
import dev.notifyhub.common.persistence.MongoIndexInitializer;
import dev.notifyhub.worker.template.TemplateSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;

/**
 * Configuração do worker. Fica fora de {@code NotifyWorkerApplication} para que testes de fatia não
 * carreguem o contrato compartilhado inteiro.
 *
 * <p>Ordem de inicialização (RESEARCH Armadilha 1; D-08): os containers de listener estão com
 * {@code auto-startup: false}; os runners rodam depois do refresh e, nesta ordem, criam os índices
 * (FND-04), aplicam o seed de templates e só então declaram a topologia e iniciam os listeners.
 * Assim, um backlog na fila nunca é consumido antes de o template existir no banco.
 *
 * <p>Não declara factory de listener: o Boot instala o executor de virtual threads na configurer, e
 * uma factory de container montada à mão (fora da configurer) o perderia sem aviso.
 */
@Configuration(proxyBeanMethods = false)
@Import(NotifyCommonConfiguration.class)
public class WorkerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WorkerConfiguration.class);

    @Bean
    @Order(1)
    ApplicationRunner createMongoIndexes(MongoIndexInitializer initializer) {
        return args -> initializer.createIndexes();
    }

    @Bean
    @Order(2)
    ApplicationRunner seedTemplates(TemplateSeeder templateSeeder) {
        return args -> templateSeeder.seed();
    }

    @Bean
    @Order(3)
    ApplicationRunner startListeners(AmqpAdmin amqpAdmin, RabbitListenerEndpointRegistry registry) {
        return args -> {
            amqpAdmin.initialize();
            registry.start();
            log.info("Listeners iniciados: {}", registry.getListenerContainerIds());
        };
    }
}
