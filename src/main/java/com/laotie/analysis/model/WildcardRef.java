package com.laotie.analysis.model;

public final class WildcardRef {
    public final String name;
    public final boolean temporary;

    public WildcardRef(String name, boolean temporary) {
        this.name = name;
        this.temporary = temporary;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof WildcardRef)) {
            return false;
        }
        WildcardRef ref = (WildcardRef) other;
        return temporary == ref.temporary
                && (name == null ? ref.name == null : name.equals(ref.name));
    }

    @Override
    public int hashCode() {
        return (name == null ? 0 : name.hashCode()) * 31 + (temporary ? 1 : 0);
    }
}
