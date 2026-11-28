package com.laotie.analysis.model;

public final class CorrelatedOwner {
    public final SourceTable source;
    public final SourceNode binding;

    public CorrelatedOwner(SourceTable source, SourceNode binding) {
        this.source = source;
        this.binding = binding;
    }
}
