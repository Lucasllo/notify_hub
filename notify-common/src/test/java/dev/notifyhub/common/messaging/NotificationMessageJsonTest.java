package dev.notifyhub.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.notifyhub.common.domain.Channel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationMessageJsonTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final NotificationMessage message =
            new NotificationMessage("id-1", Channel.EMAIL, "welcome", Instant.parse("2026-10-12T10:15:30Z"));

    @Test
    void serializesExactlyTheFourTokenFields() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(message));

        List<String> fields = new ArrayList<>();
        json.fieldNames().forEachRemaining(fields::add);

        // Payload fino: destinatário, variáveis e conteúdo renderizado (PII) nunca entram no broker
        assertThat(fields).containsExactlyInAnyOrder("notificationId", "channel", "templateCode", "createdAt");
        assertThat(json.get("channel").asText()).isEqualTo("EMAIL");
        assertThat(json.get("createdAt").asText()).isEqualTo("2026-10-12T10:15:30Z");
    }

    @Test
    void roundTripsToAnEqualRecord() throws Exception {
        String json = mapper.writeValueAsString(message);

        assertThat(mapper.readValue(json, NotificationMessage.class)).isEqualTo(message);
    }
}
