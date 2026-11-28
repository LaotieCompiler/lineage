package com.laotie.analysis.model;

import java.util.List;

public final class StarOutput {
    public final List<StarColumn> sources;
    public final boolean nameKnown;
    public final boolean stable;
    public final StarOutput origin;
    /**
     * True when the position or source identity of this output was guessed, so the
     * metadata-free contract must keep the projection at table level.
     */
    public final boolean ambiguous;

    public StarOutput(List<StarColumn> sources) {
        this(sources, false);
    }

    public StarOutput(List<StarColumn> sources, boolean nameKnown) {
        this(sources, nameKnown, true);
    }

    public StarOutput(List<StarColumn> sources, boolean nameKnown, boolean stable) {
        this(sources, nameKnown, stable, null);
    }

    public StarOutput(List<StarColumn> sources, boolean nameKnown, boolean stable,
            StarOutput origin) {
        this(sources, nameKnown, stable, origin, false);
    }

    public StarOutput(List<StarColumn> sources, boolean nameKnown, boolean stable,
            StarOutput origin, boolean ambiguous) {
        this.sources = sources;
        this.nameKnown = nameKnown;
        this.stable = stable;
        this.origin = origin == null ? this : origin;
        this.ambiguous = ambiguous;
    }

    public StarOutput withStable(boolean value) {
        return new StarOutput(sources, nameKnown, value, origin, ambiguous);
    }

    public StarOutput withAmbiguous(boolean value) {
        return new StarOutput(sources, nameKnown, stable, origin, value);
    }
}
