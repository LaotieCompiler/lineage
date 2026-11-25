package com.laotie.model.metadata;

import java.util.function.Supplier;

/**
 * ThreadLocal-based implicit metadata context.
 *
 * <p>
 * Precedence in {@code DmlLineage}: an explicitly supplied context wins over this holder.
 */
public final class MetadataContextHolder {
    private static final ThreadLocal<MetadataContext> CURRENT = new ThreadLocal<>();

    private MetadataContextHolder() {
    }

    public static MetadataContext current() {
        return CURRENT.get();
    }

    public static void set(MetadataContext context) {
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static <T> T runWith(MetadataContext context, Supplier<T> action) {
        MetadataContext previous = CURRENT.get();
        CURRENT.set(context);
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
}
