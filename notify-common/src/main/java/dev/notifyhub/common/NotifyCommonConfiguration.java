package dev.notifyhub.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.notifyhub.common.messaging.NotifyTopology;
import dev.notifyhub.common.messaging.TopologyProperties;
import dev.notifyhub.common.persistence.MongoIndexInitializer;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Configuração compartilhada, importada por {@code notify-api} e {@code notify-worker} via
 * {@code @Import}. Nenhum dos dois apps declara filas por conta própria.
 *
 * <p>Não declara {@code RabbitAdmin}, {@code RabbitTemplate} nem factory de listener: o Boot cria
 * todos, aplica o {@link MessageConverter} único abaixo ao template e à factory, e mantém o
 * executor de virtual threads da factory.
 *
 * <p>O {@link MongoIndexInitializer} só é registrado aqui; quem chama {@code createIndexes()} são os
 * runners de startup de cada app.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TopologyProperties.class)
public class NotifyCommonConfiguration {

    /** Topologia inteira num único bean; o {@code RabbitAdmin} do Boot declara na primeira conexão. */
    @Bean
    Declarables notifyDeclarables(TopologyProperties topologyProperties) {
        return NotifyTopology.declarables(topologyProperties);
    }

    /** Conversor JSON único, com o {@code ObjectMapper} do Boot (mesmo tratamento de {@code Instant}). */
    @Bean
    MessageConverter notifyMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /** Índices de {@code notifications}; os runners de startup da API e do worker chamam {@code createIndexes()}. */
    @Bean
    MongoIndexInitializer mongoIndexInitializer(MongoTemplate mongoTemplate) {
        return new MongoIndexInitializer(mongoTemplate);
    }
}
