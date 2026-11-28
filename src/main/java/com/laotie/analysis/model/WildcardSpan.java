package com.laotie.analysis.model;

public final class WildcardSpan {
    public final int start;
    public final int end;
    public final String source;
    public final boolean temporary;

    public WildcardSpan(int start, int end, String source, boolean temporary) {
        this.start = start;
        this.end = end;
        this.source = source;
        this.temporary = temporary;
    }

    @Override
    public String toString() {
        return "[" + start + "," + end + ")="
                + (temporary ? "temp:" : "") + source;
    }
}
