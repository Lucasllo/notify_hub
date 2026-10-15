package dev.notifyhub.worker.resilience;

import dev.notifyhub.worker.failure.TransientDeliveryException;
import java.util.function.Predicate;

/**
 * Predicate de ignore do circuit breaker de cada canal
 * ({@code resilience4j.circuitbreaker.configs.default.ignore-exception-predicate}).
 *
 * <p>No Resilience4j, uma exceção que não é registrada ({@code record-exceptions}) nem ignorada conta
 * como <b>sucesso</b> na janela. Com só {@code record-exceptions: [TransientDeliveryException]}, um
 * bug inesperado dentro do adapter "melhoraria" a taxa de falha do canal. Este predicate ignora tudo
 * o que não é transitório: falhas permanentes (destinatário inválido não indica provedor doente,
 * D-06), exceções desconhecidas e recusas de bulkhead ficam fora da janela, e só
 * {@link TransientDeliveryException} conta como falha.
 *
 * <p>Classe pública com construtor sem argumentos: o Resilience4j a instancia por reflexão a partir
 * do nome no YAML.
 */
public class NonTransientFailurePredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        return !(throwable instanceof TransientDeliveryException);
    }
}
