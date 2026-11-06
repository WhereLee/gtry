package com.wherelee.helpdesk.tenant;

import java.util.function.Supplier;

/**
 * Request-scoped tenant identity. It feeds the datasource wrapper, which sets the
 * PostgreSQL session variables used by row-level security.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantScope> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(TenantScope scope) {
        CURRENT.set(scope);
    }

    public static TenantScope get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static <T> T callWith(TenantScope scope, Supplier<T> action) {
        TenantScope previous = CURRENT.get();
        CURRENT.set(scope);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runWith(TenantScope scope, Runnable action) {
        callWith(scope, () -> {
            action.run();
            return null;
        });
    }
}
