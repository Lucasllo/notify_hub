package dev.notifyhub.common.persistence;

import dev.notifyhub.common.domain.AttemptOutcome;
import dev.notifyhub.common.domain.FailureClass;
import java.time.Instant;

/**
 * Uma tentativa de entrega, embutida em {@code notifications.attempts} (D-03).
 *
 * @param number       número monotônico da tentativa ({@code attemptCount + 1} no claim)
 * @param retryLevel   nível de retry em que a tentativa rodou (0 = fila principal)
 * @param startedAt    início da tentativa
 * @param durationMs   duração em milissegundos
 * @param outcome      resultado
 * @param errorCode    código do erro; nulo em sucesso
 * @param errorMessage mensagem do erro; nula em sucesso
 * @param failureClass classe da falha; nula em sucesso
 * @param thread       thread que executou a tentativa (prova de virtual thread)
 */
public record AttemptEntry(
        int number,
        int retryLevel,
        Instant startedAt,
        long durationMs,
        AttemptOutcome outcome,
        String errorCode,
        String errorMessage,
        FailureClass failureClass,
        String thread) {
}
