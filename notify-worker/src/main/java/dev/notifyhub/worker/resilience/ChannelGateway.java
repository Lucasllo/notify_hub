package dev.notifyhub.worker.resilience;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.messaging.TopologyProperties;
import dev.notifyhub.common.messaging.TtlLabel;
import dev.notifyhub.common.persistence.RenderedContent;
import dev.notifyhub.worker.channel.AttemptContext;
import dev.notifyhub.worker.channel.ChannelAdapter;
import dev.notifyhub.worker.metrics.DeliveryMetrics;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Envio por canal protegido por bulkhead e circuit breaker (RES-01, RES-02, D-04).
 *
 * <p>Ordem fixa: <b>bulkhead por fora → circuit breaker no meio → adapter por dentro</b>, montada com a
 * API programática do Resilience4j (sem AOP nem anotações). Com o bulkhead por fora, o CB nunca vê
 * {@code BulkheadFullException}. A falha mágica do adapter (D-04) passa pelos dois, então uma rajada
 * de {@code always-transient} abre o CB do canal.
 *
 * <p>As instâncias {@code email}, {@code sms} e {@code webhook} de cada registry são resolvidas uma vez,
 * no construtor, com {@code registry.find(channel.id())}. Instância ausente impede o worker de subir:
 * {@code registry.circuitBreaker(name)} criaria em silêncio uma instância com os padrões do
 * Resilience4j (100 chamadas mínimas, 60s aberto), que nunca abriria na demo e queimaria a escada.
 *
 * <p>Também no construtor: o tempo aberto de cada CB ({@code wait-duration-in-open-state}) não pode
 * passar do 1º nível de {@code notify.topology.retry-levels} (D-06). Se passasse, o lote recusado
 * voltaria do delay com o CB ainda aberto e queimaria o 2º nível; o padrão usa metade do nível.
 *
 * <p>Recusas ({@code CallNotPermittedException}, {@code BulkheadFullException}) saem daqui em
 * microssegundos e o classificador as trata como transitórias que consomem um nível (D-05).
 *
 * <p><b>"Giro" de recusas sob backlog (RESEARCH Padrão 6.1):</b> com {@code max-wait-duration} 0, o
 * consumidor recusado grava RETRYING, publica no delay e pega a próxima mensagem, que também pode ser
 * recusada. No modelo, 8 permissões para 10 consumidores recusam ~40% das mensagens na 1ª passagem
 * (9 de 10: ~17%), e raramente uma notificação chega a DEAD_LETTERED só por bulkhead. Se o k6 da
 * Fase 4 mostrar DEAD_LETTERED demais, o ajuste é subir para 9 permissões, nunca mudar a regra
 * (requeue ou espera fora do bulkhead).
 */
@Component
public class ChannelGateway {

    private final Map<Channel, Route> routes;

    /**
     * @param topology níveis da escada; o 1º limita o tempo aberto de cada CB
     * @throws IllegalStateException se faltar adapter, circuit breaker ou bulkhead para algum canal,
     *                               houver dois adapters para o mesmo canal ou o tempo aberto de algum
     *                               CB for maior que o 1º nível de retry
     */
    public ChannelGateway(List<ChannelAdapter> adapters, CircuitBreakerRegistry circuitBreakerRegistry,
                          BulkheadRegistry bulkheadRegistry, TopologyProperties topology,
                          DeliveryMetrics metrics) {
        Map<Channel, ChannelAdapter> byChannel = indexByChannel(adapters);
        Duration firstLevel = topology.retryLevels().get(0);
        Map<Channel, Route> resolved = new EnumMap<>(Channel.class);
        for (Channel channel : Channel.values()) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.find(channel.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "instância de circuit breaker ausente para o canal " + channel.id()));
            Bulkhead bulkhead = bulkheadRegistry.find(channel.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "instância de bulkhead ausente para o canal " + channel.id()));
            requireOpenWaitWithinFirstLevel(channel, circuitBreaker, firstLevel);
            resolved.put(channel, new Route(byChannel.get(channel), circuitBreaker, bulkhead));
        }
        this.routes = resolved;
    }

    private static void requireOpenWaitWithinFirstLevel(Channel channel, CircuitBreaker circuitBreaker,
                                                        Duration firstLevel) {
        // espera da 1ª abertura (sem backoff configurado, todas as aberturas esperam o mesmo)
        long waitMs = circuitBreaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1);
        if (waitMs > firstLevel.toMillis()) {
            throw new IllegalStateException("wait-duration-in-open-state do canal " + channel.id()
                    + " (" + waitMs + "ms) maior que o 1º nível de retry (" + TtlLabel.of(firstLevel) + ")");
        }
    }

    private static Map<Channel, ChannelAdapter> indexByChannel(List<ChannelAdapter> adapters) {
        Map<Channel, ChannelAdapter> byChannel = new EnumMap<>(Channel.class);
        for (ChannelAdapter adapter : adapters) {
            if (byChannel.putIfAbsent(adapter.channel(), adapter) != null) {
                throw new IllegalStateException("mais de um adapter para o canal " + adapter.channel());
            }
        }
        Set<Channel> missing = EnumSet.allOf(Channel.class);
        missing.removeAll(byChannel.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("canais sem adapter: " + missing);
        }
        return byChannel;
    }

    /**
     * Envia pelo adapter do canal, dentro do bulkhead e do circuit breaker do canal. Bloqueia até o fim
     * do envio, na própria thread (virtual) do listener.
     *
     * @throws io.github.resilience4j.circuitbreaker.CallNotPermittedException se o CB do canal estiver
     *                                                                         aberto
     * @throws io.github.resilience4j.bulkhead.BulkheadFullException            se o bulkhead do canal
     *                                                                         estiver cheio
     * @throws RuntimeException                                                 falha do próprio adapter
     */
    public void send(Channel channel, String recipient, RenderedContent content, AttemptContext context) {
        Route route = routes.get(channel);
        Runnable call = () -> route.adapter().send(recipient, content, context);
        Bulkhead.decorateRunnable(route.bulkhead(),
                CircuitBreaker.decorateRunnable(route.circuitBreaker(), call)).run();
    }

    private record Route(ChannelAdapter adapter, CircuitBreaker circuitBreaker, Bulkhead bulkhead) {
    }
}
