package dev.notifyhub.worker.template;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.persistence.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Documento da coleção {@code templates}: um por template, com as variantes por canal embutidas.
 * O mesmo record é lido do JSON do seed (Jackson) e gravado no Mongo; as chaves do mapa ficam
 * como {@code "EMAIL"}, {@code "SMS"} e {@code "WEBHOOK"}.
 *
 * @param id                código do template (ex.: {@code welcome}), usado como {@code _id}
 * @param version           versão do seed; o seeder só sobrescreve quando ela é maior (D-08)
 * @param requiredVariables variáveis que o cliente precisa enviar; nunca inclui {@code _notificationId}
 * @param variants          variante por canal
 */
@Document(Collections.TEMPLATES)
public record TemplateDocument(
        @Id String id,
        int version,
        List<String> requiredVariables,
        Map<Channel, TemplateVariant> variants) {
}
