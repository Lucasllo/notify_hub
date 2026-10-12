package dev.notifyhub.common.messaging;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Níveis de delay do retry ({@code notify.topology.retry-levels}). Cada nível vira uma fila
 * {@code notify.<canal>.retry.<rótulo>} com {@code x-message-ttl} igual ao nível.
 *
 * <p>Padrão 5s, 25s, 125s (backoff de fator 5, MSG-03). O profile {@code demo} usa 1s, 5s, 25s e os
 * testes usam valores em milissegundos. API e worker precisam usar os mesmos níveis.
 *
 * <p>Uma lista inválida lança {@link IllegalArgumentException} no bind, e o contexto não sobe.
 *
 * @param retryLevels níveis estritamente crescentes, cada um entre 1ms e {@link Integer#MAX_VALUE} ms
 */
@ConfigurationProperties("notify.topology")
public record TopologyProperties(@DefaultValue({"5s", "25s", "125s"}) List<Duration> retryLevels) {

    public TopologyProperties {
        if (retryLevels == null || retryLevels.isEmpty()) {
            throw new IllegalArgumentException("notify.topology.retry-levels precisa ter pelo menos um nível");
        }
        Set<String> labels = new HashSet<>();
        Duration previous = null;
        for (Duration level : retryLevels) {
            if (level == null || level.toMillis() <= 0) {
                throw new IllegalArgumentException(
                        "notify.topology.retry-levels: cada nível precisa ter pelo menos 1ms, recebido " + level);
            }
            if (level.toMillis() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "notify.topology.retry-levels: nível acima de " + Integer.MAX_VALUE + "ms: " + level);
            }
            if (previous != null && level.compareTo(previous) <= 0) {
                throw new IllegalArgumentException(
                        "notify.topology.retry-levels precisa ser estritamente crescente: " + retryLevels);
            }
            if (!labels.add(TtlLabel.of(level))) {
                throw new IllegalArgumentException(
                        "notify.topology.retry-levels: dois níveis geram o mesmo rótulo " + TtlLabel.of(level));
            }
            previous = level;
        }
        retryLevels = List.copyOf(retryLevels);
    }
}
