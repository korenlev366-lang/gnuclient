package gnu.client.runtime.packet;

/**
 * Priority for inbound packet delay modules (highest wins).
 * raven-bS KnockbackDelay &gt; target Backtrack; neither queues with the other active.
 */
public final class InboundLagCoordinator {

    public enum Owner {
        NONE,
        KNOCKBACK_DELAY,
        BACKTRACK
    }

    private static volatile Owner owner = Owner.NONE;

    private InboundLagCoordinator() {}

    public static Owner getOwner() {
        return owner;
    }

    public static boolean knockbackDelayOwns() {
        return owner == Owner.KNOCKBACK_DELAY;
    }

    public static boolean backtrackOwns() {
        return owner == Owner.BACKTRACK;
    }

    /** Knockback delay preempts backtrack. */
    public static boolean tryAcquire(Owner requested) {
        if (requested == Owner.NONE)
            return false;
        if (requested == Owner.KNOCKBACK_DELAY) {
            owner = Owner.KNOCKBACK_DELAY;
            return true;
        }
        if (owner == Owner.NONE || owner == Owner.BACKTRACK) {
            owner = Owner.BACKTRACK;
            return true;
        }
        return false;
    }

    public static void release(Owner releasing) {
        if (owner == releasing)
            owner = Owner.NONE;
    }

    public static void forceReleaseAll() {
        owner = Owner.NONE;
    }
}
