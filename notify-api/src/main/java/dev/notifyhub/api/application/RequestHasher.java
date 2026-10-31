package dev.notifyhub.api.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.notifyhub.api.web.dto.CreateNotificationRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Hash do pedido canônico do {@code POST /notifications}, comparado com o {@code requestHash} gravado
 * junto da {@code Idempotency-Key} (D-06).
 *
 * <p>O pedido canônico é um mapa ordenado com {@code channel} (nome do enum), {@code recipient},
 * {@code templateCode} e {@code variables} (também com as chaves ordenadas), serializado em JSON e
 * passado por SHA-256. O resultado é hexadecimal minúsculo, com 64 caracteres.
 *
 * <p>O hash é calculado <b>depois do parse</b> do corpo:
 * <ul>
 *   <li>a ordem das variáveis no JSON não importa;</li>
 *   <li>{@code 1} (inteiro) e {@code 1.0} (decimal) são diferentes, porque viram tipos diferentes;</li>
 *   <li>{@code 1.0} e {@code 1.00} são iguais, porque viram o mesmo decimal.</li>
 * </ul>
 * Mudar a canonicalização invalida os hashes já gravados: a mesma chave com o mesmo payload passaria a
 * responder 422.
 */
public final class RequestHasher {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private RequestHasher() {
    }

    /** SHA-256 em hexadecimal minúsculo do pedido canônico. */
    public static String hash(CreateNotificationRequest request) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("channel", request.channel() == null ? null : request.channel().name());
        canonical.put("recipient", request.recipient());
        canonical.put("templateCode", request.templateCode());
        canonical.put("variables", request.variables() == null ? null : new TreeMap<>(request.variables()));
        try {
            byte[] json = JSON.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("não foi possível serializar o pedido canônico", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }
}
