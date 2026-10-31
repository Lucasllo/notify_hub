package dev.notifyhub.api.application;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import dev.notifyhub.api.web.dto.NotificationSummary;
import dev.notifyhub.api.web.dto.PageResponse;
import dev.notifyhub.common.domain.Channel;
import dev.notifyhub.common.domain.NotificationStatus;
import dev.notifyhub.common.persistence.Collections;
import dev.notifyhub.common.persistence.NotificationDocument;
import dev.notifyhub.common.persistence.NotificationDocument.Fields;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Consultas de leitura das notificações para a visão operacional (API-05).
 *
 * <p>A listagem filtra por igualdade em {@code status} e/ou {@code channel} e ordena por
 * {@code createdAt} desc com desempate por {@code _id} desc. Cada combinação de filtro tem um índice
 * que cobre filtro e ordenação ({@code status_channel_createdAt}, {@code status_createdAt},
 * {@code channel_createdAt} e {@code createdAt}, do {@code MongoIndexInitializer}), então o MongoDB
 * nunca precisa ordenar em memória (D-09, Armadilha 5).
 */
@Service
public class NotificationQueryService {

    private final MongoTemplate mongo;

    public NotificationQueryService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /**
     * Uma página de resumos (D-09, D-11). Os parâmetros já chegam validados pelo controller.
     *
     * <p>{@code totalElements} vem de um {@code count} do filtro inteiro, feito antes de skip/limit. O
     * skip é calculado em long, sem estouro de int. Uma página além do total sai vazia sem consultar o
     * Mongo: além de poupar a consulta, isso evita mandar ao driver um skip acima de
     * {@code Integer.MAX_VALUE}, que o Spring Data trunca para int.
     *
     * <p>A consulta projeta só os campos do resumo: {@code variables}, {@code rendered},
     * {@code attempts}, {@code idempotencyKey} e {@code requestHash} nem saem do banco. O resultado é
     * lido direto como {@link NotificationSummary}, cujos componentes têm os nomes dos campos gravados.
     * Ler como {@link NotificationDocument} não funciona com a projeção: o construtor do record exige o
     * {@code int attemptCount}, que fica de fora.
     *
     * @param status  filtro por status, ou nulo
     * @param channel filtro por canal, ou nulo
     * @param page    página, a partir de 0
     * @param size    tamanho da página, de 1 a 100
     */
    public PageResponse<NotificationSummary> list(NotificationStatus status, Channel channel, int page, int size) {
        Query query = new Query();
        if (status != null) {
            query.addCriteria(where(Fields.STATUS).is(status));
        }
        if (channel != null) {
            query.addCriteria(where(Fields.CHANNEL).is(channel));
        }
        long total = mongo.count(query, NotificationDocument.class);

        long skip = (long) page * size;
        query.with(Sort.by(Sort.Order.desc(Fields.CREATED_AT), Sort.Order.desc(Fields.ID)))
                .skip(skip)
                .limit(size);
        query.fields().include(Fields.STATUS, Fields.CHANNEL, Fields.RECIPIENT, Fields.TEMPLATE_CODE,
                Fields.RETRY_COUNT, Fields.LAST_ERROR + ".code", Fields.CREATED_AT, Fields.UPDATED_AT);

        List<NotificationSummary> content = skip >= total
                ? List.of()
                : mongo.find(query, NotificationSummary.class, Collections.NOTIFICATIONS);
        int totalPages = (int) ((total + size - 1) / size);
        return new PageResponse<>(content, page, size, total, totalPages);
    }
}
