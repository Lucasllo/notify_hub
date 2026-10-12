package dev.notifyhub.worker.template;

/**
 * O template pedido não existe na coleção {@code templates}, ou existe sem variante para o canal.
 * A Fase 2 classifica esta falha como permanente; na Fase 1 qualquer exceção rejeita para a DLQ.
 */
public class TemplateNotFoundException extends RuntimeException {

    private final String templateCode;

    public TemplateNotFoundException(String templateCode, String message) {
        super(message);
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
