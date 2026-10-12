package dev.notifyhub.worker.channel;

import dev.notifyhub.common.domain.Channel;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Um {@link ChannelAdapter} simulado por canal, configurado por {@link SimulationProperties}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SimulationProperties.class)
public class SimulationConfiguration {

    @Bean
    ChannelAdapter emailAdapter(SimulationProperties props) {
        return adapter(Channel.EMAIL, props);
    }

    @Bean
    ChannelAdapter smsAdapter(SimulationProperties props) {
        return adapter(Channel.SMS, props);
    }

    @Bean
    ChannelAdapter webhookAdapter(SimulationProperties props) {
        return adapter(Channel.WEBHOOK, props);
    }

    private static ChannelAdapter adapter(Channel channel, SimulationProperties props) {
        return new SimulatedChannelAdapter(channel, props.forChannel(channel), ThreadLocalRandom::current);
    }
}
