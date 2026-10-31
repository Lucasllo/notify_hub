package dev.notifyhub.common.domain;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Ciclo de vida de uma notificação. A tabela {@link #ALLOWED} é o contrato: os filtros dos updates
 * condicionais no Mongo (claim, resultado, reprocessamento) são derivados dela, nunca escritos à mão.
 *
 * <p>O reset do reprocessamento da DLQ (Fase 3) só age quando {@code statusAtual.canTransitionTo(PENDING)}.
 * Além de FAILED e DEAD_LETTERED, a tabela aceita PENDING, RETRYING e PROCESSING → PENDING (D-01,
 * CR-01 da Fase 2): documentos não terminais cuja mensagem foi parar na DLQ (falha do Mongo no claim
 * ou no update de resultado) são retomados pelo operador, em vez de travar a cabeça da DLQ.
 * PROCESSING só é resetado com lease vencido ou ausente, regra aplicada pelo reset. Nenhuma dessas
 * transições termina em PROCESSING, então {@link #claimableForDelivery()} e
 * {@link #reclaimableWhenStuck()} não mudam.
 */
public enum NotificationStatus {
    PENDING,
    PROCESSING,
    SENT,
    RETRYING,
    FAILED,
    DEAD_LETTERED,
    PUBLISH_FAILED;

    private static final Map<NotificationStatus, Set<NotificationStatus>> ALLOWED;

    static {
        Map<NotificationStatus, Set<NotificationStatus>> table = new EnumMap<>(NotificationStatus.class);
        // PENDING -> PENDING: reset do reprocessamento da DLQ (Fase 3, D-01/CR-01), usado só pelo reset do reprocess
        table.put(PENDING, EnumSet.of(PROCESSING, PUBLISH_FAILED, PENDING));
        // PENDING: republicação administrativa (Fase 3)
        table.put(PUBLISH_FAILED, EnumSet.of(PROCESSING, PENDING));
        // PROCESSING -> PROCESSING: reclaim de uma entrega presa (Fase 2)
        // PROCESSING -> PENDING: reset do reprocessamento da DLQ (Fase 3, D-01/CR-01), usado só pelo reset do
        // reprocess; PROCESSING só com lease vencido
        table.put(PROCESSING, EnumSet.of(SENT, RETRYING, FAILED, DEAD_LETTERED, PROCESSING, PENDING));
        // DEAD_LETTERED: republicação para o próximo nível falhou (Fase 2, D-11)
        // RETRYING -> PENDING: reset do reprocessamento da DLQ (Fase 3, D-01/CR-01), usado só pelo reset do reprocess
        table.put(RETRYING, EnumSet.of(PROCESSING, DEAD_LETTERED, PENDING));
        // FAILED/DEAD_LETTERED -> PENDING: reprocessamento (Fase 3)
        table.put(FAILED, EnumSet.of(PENDING));
        table.put(DEAD_LETTERED, EnumSet.of(PENDING));
        table.put(SENT, EnumSet.noneOf(NotificationStatus.class));

        table.replaceAll((from, targets) -> Collections.unmodifiableSet(targets));
        ALLOWED = Collections.unmodifiableMap(table);
    }

    /** Diz se a tabela permite sair deste status para {@code target}. */
    public boolean canTransitionTo(NotificationStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    /** Todos os status que podem transitar para {@code target}, derivados da tabela. Conjunto imutável. */
    public static Set<NotificationStatus> sourcesOf(NotificationStatus target) {
        EnumSet<NotificationStatus> sources = EnumSet.noneOf(NotificationStatus.class);
        Arrays.stream(values())
                .filter(from -> from.canTransitionTo(target))
                .forEach(sources::add);
        return Collections.unmodifiableSet(sources);
    }

    /**
     * Status que o worker só reivindica sob condição (D-09): os de {@code sourcesOf(PROCESSING)} que
     * são o próprio PROCESSING (transição PROCESSING → PROCESSING da tabela). Uma entrega presa em
     * PROCESSING por um worker derrubado só é retomada quando a mensagem chega com
     * {@code redelivered=true} ou quando {@code leaseUntil < agora}. Conjunto imutável.
     */
    public static Set<NotificationStatus> reclaimableWhenStuck() {
        EnumSet<NotificationStatus> stuck = EnumSet.noneOf(NotificationStatus.class);
        sourcesOf(PROCESSING).stream()
                .filter(from -> from == PROCESSING)
                .forEach(stuck::add);
        return Collections.unmodifiableSet(stuck);
    }

    /**
     * Status a partir dos quais o worker reivindica uma entrega sem condição:
     * {@code sourcesOf(PROCESSING)} sem o próprio PROCESSING. O reclaim de PROCESSING é condicional
     * (redelivered ou lease vencido, D-09) e fica num ramo separado do filtro do claim, derivado de
     * {@link #reclaimableWhenStuck()}.
     */
    public static Set<NotificationStatus> claimableForDelivery() {
        EnumSet<NotificationStatus> claimable = EnumSet.copyOf(sourcesOf(PROCESSING));
        claimable.remove(PROCESSING);
        return Collections.unmodifiableSet(claimable);
    }
}
