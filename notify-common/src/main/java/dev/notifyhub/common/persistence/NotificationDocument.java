package dev.notifyhub.common.persistence;

import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.NotificationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Documento da coleção {@code notifications} (D-03). É a fonte da verdade da notificação; a
 * mensagem AMQP só carrega o id.
 *
 * <p>Transições de status são sempre updates condicionais ({@code findAndModify}/{@code updateFirst}
 * com {@code $set}/{@code $push}), nunca {@code save()}, que sobrescreveria o histórico. Os nomes de
 * campo para essas queries estão em {@link Fields}.
 *
 * <p>Sem anotações de índice: os índices são criados pelo {@link MongoIndexInitializer} (FND-04).
 *
 * @param id           id (UUID em string) gravado como {@code _id}
 * @param channel      canal, gravado em maiúsculo
 * @param recipient    destinatário
 * @param templateCode código do template
 * @param variables    variáveis do template (chaves validadas pela API: sem ponto)
 * @param status       status atual
 * @param retryCount   nível de retry atual; zerado no reprocessamento da DLQ
 * @param attemptCount total de tentativas, monotônico (a próxima é {@code attemptCount + 1})
 * @param rendered     conteúdo renderizado gravado junto com o resultado
 * @param lastError    último erro
 * @param attempts     histórico de tentativas, embutido
 * @param createdAt    criação
 * @param updatedAt    última atualização
 * @param claimedAt    momento do claim pelo worker
 * @param leaseUntil   fim do lease do claim
 * @param sentAt       momento do envio com sucesso
 */
@Document(Collections.NOTIFICATIONS)
public record NotificationDocument(
        @Id String id,
        Channel channel,
        String recipient,
        String templateCode,
        Map<String, Object> variables,
        NotificationStatus status,
        int retryCount,
        int attemptCount,
        RenderedContent rendered,
        LastError lastError,
        List<AttemptEntry> attempts,
        Instant createdAt,
        Instant updatedAt,
        Instant claimedAt,
        Instant leaseUntil,
        Instant sentAt) {

    /** Notificação recém-aceita: PENDING, contadores zerados e histórico vazio. */
    public static NotificationDocument newPending(String id, Channel channel, String recipient,
            String templateCode, Map<String, Object> variables, Instant now) {
        return new NotificationDocument(id, channel, recipient, templateCode, variables,
                NotificationStatus.PENDING, 0, 0, null, null, List.of(),
                now, now, null, null, null);
    }

    /** Nomes de campo gravados no Mongo, para {@code Criteria} e {@code Update}. */
    public static final class Fields {

        public static final String ID = "_id";
        public static final String CHANNEL = "channel";
        public static final String STATUS = "status";
        public static final String RETRY_COUNT = "retryCount";
        public static final String ATTEMPT_COUNT = "attemptCount";
        public static final String ATTEMPTS = "attempts";
        public static final String RENDERED = "rendered";
        public static final String LAST_ERROR = "lastError";
        public static final String CREATED_AT = "createdAt";
        public static final String UPDATED_AT = "updatedAt";
        public static final String CLAIMED_AT = "claimedAt";
        public static final String LEASE_UNTIL = "leaseUntil";
        public static final String SENT_AT = "sentAt";

        private Fields() {
        }
    }
}
