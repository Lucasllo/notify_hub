package dev.notifyhub.worker.resilience;

import java.util.function.Predicate;

/** Esqueleto (RED): ainda não ignora nada. */
public class NonTransientFailurePredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        return false;
    }
}
