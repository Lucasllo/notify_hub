package dev.notifyhub.worker.channel;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.persistence.RenderedContent;

/**
 * "Envio" de uma notificação renderizada por um canal. É o ponto em que a Fase 2 encaixa circuit
 * breaker e bulkhead.
 */
public interface ChannelAdapter {

    /** Canal atendido por este adapter. */
    Channel channel();

    /**
     * Envia o conteúdo ao destinatário. Bloqueia até o fim do envio.
     *
     * @throws RuntimeException se o envio falhar
     */
    void send(String recipient, RenderedContent content);
}
