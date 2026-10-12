package dev.notifyhub.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;

class MongoIndexInitializerTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final IndexOperations ops = mock(IndexOperations.class);
    private final MongoIndexInitializer initializer = new MongoIndexInitializer(mongo);

    @BeforeEach
    void setUp() {
        when(mongo.indexOps("notifications")).thenReturn(ops);
        when(ops.createIndex(any(IndexDefinition.class))).thenAnswer(inv ->
                ((IndexDefinition) inv.getArgument(0)).getIndexOptions().getString("name"));
    }

    private List<IndexDefinition> captureCreated(int expectedCalls) {
        ArgumentCaptor<IndexDefinition> captor = ArgumentCaptor.forClass(IndexDefinition.class);
        verify(ops, times(expectedCalls)).createIndex(captor.capture());
        return captor.getAllValues();
    }

    private static List<String> names(List<IndexDefinition> defs) {
        return defs.stream().map(d -> d.getIndexOptions().getString("name")).toList();
    }

    private static List<String> keyOrder(IndexDefinition def) {
        return new ArrayList<>(def.getIndexKeys().keySet());
    }

    @Test
    void createsThreeNamedIndexesOnNotifications() {
        initializer.createIndexes();

        verify(mongo).indexOps("notifications");
        assertThat(names(captureCreated(3)))
                .containsExactly("status_channel_createdAt", "channel_createdAt", "createdAt");
    }

    @Test
    void statusChannelIndexFollowsEsrWithIdTieBreaker() {
        initializer.createIndexes();

        IndexDefinition first = captureCreated(3).get(0);
        Document keys = first.getIndexKeys();
        assertThat(keyOrder(first)).containsExactly("status", "channel", "createdAt", "_id");
        assertThat(keys.get("status")).isEqualTo(1);
        assertThat(keys.get("channel")).isEqualTo(1);
        assertThat(keys.get("createdAt")).isEqualTo(-1);
        assertThat(keys.get("_id")).isEqualTo(-1);
    }

    @Test
    void channelAndCreatedAtIndexesHaveExpectedKeys() {
        initializer.createIndexes();

        List<IndexDefinition> defs = captureCreated(3);
        assertThat(keyOrder(defs.get(1))).containsExactly("channel", "createdAt", "_id");
        assertThat(defs.get(1).getIndexKeys().get("channel")).isEqualTo(1);
        assertThat(defs.get(1).getIndexKeys().get("createdAt")).isEqualTo(-1);
        assertThat(keyOrder(defs.get(2))).containsExactly("createdAt", "_id");
        assertThat(defs.get(2).getIndexKeys().get("createdAt")).isEqualTo(-1);
        assertThat(defs.get(2).getIndexKeys().get("_id")).isEqualTo(-1);
    }

    @Test
    void callingTwiceRepeatsTheSameDefinitions() {
        initializer.createIndexes();
        initializer.createIndexes();

        List<IndexDefinition> defs = captureCreated(6);
        for (int i = 0; i < 3; i++) {
            assertThat(defs.get(i + 3).getIndexKeys()).isEqualTo(defs.get(i).getIndexKeys());
            assertThat(defs.get(i + 3).getIndexOptions()).isEqualTo(defs.get(i).getIndexOptions());
        }
    }
}
