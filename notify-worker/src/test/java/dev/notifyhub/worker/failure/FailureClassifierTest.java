package dev.notifyhub.worker.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.mongodb.MongoTimeoutException;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.MustacheException;
import dev.notifyhub.common.domain.AttemptOutcome;
import dev.notifyhub.common.domain.FailureClass;
import dev.notifyhub.worker.channel.SimulatedDeliveryException;
import dev.notifyhub.worker.failure.FailureClassifier.Classification;
import dev.notifyhub.worker.template.TemplateNotFoundException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;

/** Tabela do classificador (DLV-03, D-13, D-14), sem Spring. */
class FailureClassifierTest {

    private final FailureClassifier classifier = new FailureClassifier();

    // ------------------------------------------------------------ exceções próprias

    @Test
    void permanentDeliveryExceptionKeepsItsOwnClassAndCode() {
        Classification c = classifier.classify(new PermanentDeliveryException(ErrorCode.SIMULATED_PERMANENT, "x"));

        assertThat(c.failureClass()).isEqualTo(FailureClass.PERMANENT);
        assertThat(c.errorCode()).isEqualTo(ErrorCode.SIMULATED_PERMANENT);
        assertThat(c.outcome()).isEqualTo(AttemptOutcome.PERMANENT_FAILURE);
        assertThat(c.message()).isEqualTo("x");
    }

    @Test
    void simulatedAdapterFailureIsTransient() {
        Classification c = classifier.classify(new SimulatedDeliveryException("falha simulada no canal email"));

        assertThat(c.failureClass()).isEqualTo(FailureClass.TRANSIENT);
        assertThat(c.errorCode()).isEqualTo(ErrorCode.SIMULATED_TRANSIENT);
        assertThat(c.outcome()).isEqualTo(AttemptOutcome.TRANSIENT_FAILURE);
    }

    @Test
    void interruptedSendWrappedByTheAdapterStaysTransient() {
        Classification c = classifier.classify(new SimulatedDeliveryException(
                "envio interrompido no canal email", new InterruptedException()));

        assertThat(c.failureClass()).isEqualTo(FailureClass.TRANSIENT);
        assertThat(c.errorCode()).isEqualTo(ErrorCode.SIMULATED_TRANSIENT);
    }

    @Test
    void missingTemplateIsPermanentTemplateNotFound() {
        Classification c = classifier.classify(TemplateNotFoundException.missing("x"));

        assertThat(c.failureClass()).isEqualTo(FailureClass.PERMANENT);
        assertThat(c.errorCode()).isEqualTo(ErrorCode.TEMPLATE_NOT_FOUND);
    }

    // ------------------------------------------------------------ JMustache

    @Test
    void missingVariableIsPermanentAndNamesOnlyTheKey() {
        MustacheException.Context missing = catchThrowableOfType(MustacheException.Context.class,
                () -> Mustache.compiler().compile("{{nome}}").execute(Map.of()));

        Classification c = classifier.classify(missing);

        assertThat(c.failureClass()).isEqualTo(FailureClass.PERMANENT);
        assertThat(c.errorCode()).isEqualTo(ErrorCode.MISSING_VARIABLE);
        assertThat(c.message()).contains("nome");
    }

    @Test
    void unparseableTemplateIsPermanentTemplateInvalid() {
        MustacheException parse = catchThrowableOfType(MustacheException.class,
                () -> Mustache.compiler().compile("{{#a}}"));

        Classification c = classifier.classify(parse);

        assertThat(c.failureClass()).isEqualTo(FailureClass.PERMANENT);
        assertThat(c.errorCode()).isEqualTo(ErrorCode.TEMPLATE_INVALID);
    }

    // ------------------------------------------------------------ MongoDB

    @Test
    void dataAccessResourceFailureIsTransientStoreUnavailable() {
        Classification c = classifier.classify(new DataAccessResourceFailureException("x"));

        assertThat(c.failureClass()).isEqualTo(FailureClass.TRANSIENT);
        assertThat(c.errorCode()).isEqualTo(ErrorCode.STORE_UNAVAILABLE);
        assertThat(c.message()).isEqualTo("armazenamento indisponível: DataAccessResourceFailureException");
    }

    @Test
    void transientDataAccessIsTransientStoreUnavailable() {
        TransientDataAccessException timeout = new QueryTimeoutException("x");

        Classification c = classifier.classify(timeout);

        assertThat(c.failureClass()).isEqualTo(FailureClass.TRANSIENT);
        assertThat(c.errorCode()).isEqualTo(ErrorCode.STORE_UNAVAILABLE);
    }

    @Test
    void mongoTimeoutInTheCauseChainIsTransientStoreUnavailable() {
        Classification c = classifier.classify(new RuntimeException("embrulho", new MongoTimeoutException("x")));

        assertThat(c.failureClass()).isEqualTo(FailureClass.TRANSIENT);
        assertThat(c.errorCode()).isEqualTo(ErrorCode.STORE_UNAVAILABLE);
    }

    // ------------------------------------------------------------ desconhecida

    @Test
    void unknownExceptionIsUnknownUnexpectedWithoutItsMessage() {
        Classification c = classifier.classify(new IllegalStateException("boom ana@exemplo.com"));

        assertThat(c.failureClass()).isEqualTo(FailureClass.UNKNOWN);
        assertThat(c.errorCode()).isEqualTo(ErrorCode.UNEXPECTED);
        assertThat(c.outcome()).isEqualTo(AttemptOutcome.UNKNOWN_FAILURE);
        assertThat(c.message()).isEqualTo("erro inesperado: IllegalStateException");
        assertThat(c.message()).doesNotContain("ana@exemplo.com");
    }

    @Test
    void longDeliveryFailureMessageIsTruncatedTo300Characters() {
        String longMessage = "a".repeat(500);

        Classification c = classifier.classify(new PermanentDeliveryException(ErrorCode.SIMULATED_PERMANENT, longMessage));

        assertThat(c.message()).hasSize(300);
    }
}
