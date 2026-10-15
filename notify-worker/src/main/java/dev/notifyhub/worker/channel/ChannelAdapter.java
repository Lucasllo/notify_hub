package dev.notifyhub.worker.channel;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.persistence.RenderedContent;

/**
 * "Envio" de uma notificação renderizada por um canal. É o ponto em que a Fase 2 encaixa circuit
 * breaker e bulkhead: tudo o que acontece dentro de {@link #send} passa por eles, inclusive as falhas
 * dos destinatários mágicos (D-04).
 */
public interface ChannelAdapter {

    /** Canal atendido por este adapter. */
    Channel channel();

    /**
     * Envia o conteúdo ao destinatário. Bloqueia até o fim do envio.
     *
     * @param context nível de retry e número da tentativa corrente; usado pelas falhas
     *                determinísticas dos destinatários mágicos (D-03)
     * @throws RuntimeException se o envio falhar
     */
    void send(String recipient, RenderedContent content, AttemptContext context);
}
