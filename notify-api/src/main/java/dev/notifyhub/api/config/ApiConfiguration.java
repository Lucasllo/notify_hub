package dev.notifyhub.api.config;

import dev.notifyhub.api.messaging.PublishProperties;
import dev.notifyhub.common.NotifyCommonConfiguration;
import dev.notifyhub.common.persistence.MongoIndexInitializer;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;

/**
 * Configuração da API. Fica fora de {@code NotifyApiApplication} para que os testes de fatia
 * ({@code @WebMvcTest}) não carreguem o contrato compartilhado inteiro.
 *
 * <p>Os runners rodam depois do refresh do contexto: primeiro os índices do Mongo (FND-04), depois a
 * declaração da topologia. Sem o segundo, o {@code RabbitAdmin} só declara na primeira conexão; com
 * ele, a topologia aparece no painel ao subir e a API falha cedo se o broker estiver fora.
 */
@Configuration(proxyBeanMethods = false)
@Import(NotifyCommonConfiguration.class)
@EnableConfigurationProperties(PublishProperties.class)
public class ApiConfiguration {

    @Bean
    @Order(1)
    ApplicationRunner createMongoIndexes(MongoIndexInitializer initializer) {
        return args -> initializer.createIndexes();
    }

    @Bean
    @Order(2)
    ApplicationRunner declareTopology(AmqpAdmin amqpAdmin) {
        return args -> amqpAdmin.initialize();
    }
}
