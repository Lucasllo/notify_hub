package dev.notifyhub.worker.template;

import dev.notifyhub.worker.failure.ErrorCode;
import dev.notifyhub.worker.failure.PermanentDeliveryException;

/**
 * O template pedido não existe na coleção {@code templates}, ou existe sem variante para o canal.
 * Falha permanente ({@link ErrorCode#TEMPLATE_NOT_FOUND}): retry não faz o template aparecer, então
 * a notificação vai direto para FAILED e para a DLQ do canal.
 */
public class TemplateNotFoundException extends PermanentDeliveryException {

    private final String templateCode;

    public TemplateNotFoundException(String templateCode, String message) {
        super(ErrorCode.TEMPLATE_NOT_FOUND, message);
        this.templateCode = templateCode;
    }

    /** Template ausente da coleção. */
    public static TemplateNotFoundException missing(String templateCode) {
        return new TemplateNotFoundException(templateCode, "template não encontrado: " + templateCode);
    }

    /** Template existe, mas não tem variante para o canal. */
    public static TemplateNotFoundException missingVariant(String templateCode, Object channel) {
        return new TemplateNotFoundException(templateCode,
                "template " + templateCode + " sem variante " + channel);
    }

    public String templateCode() {
        return templateCode;
    }
}
