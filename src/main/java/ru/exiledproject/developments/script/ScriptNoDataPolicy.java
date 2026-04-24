package ru.exiledproject.developments.script;

import org.graalvm.polyglot.Value;

public record ScriptNoDataPolicy(
        String zeroDataPlaceholder,
        Value hasDataFunction,
        Value noDataWhenFunction
) {
    public String placeholderOrEmpty() {
        return zeroDataPlaceholder == null ? "" : zeroDataPlaceholder;
    }
}
