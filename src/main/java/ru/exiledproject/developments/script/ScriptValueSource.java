package ru.exiledproject.developments.script;

import ru.exiledproject.developments.api.eScoreScope;

public record ScriptValueSource(Type type, String key, Object value, eScoreScope scope) {
    public enum Type {
        PLAYER_DATA,
        PLACEHOLDER_API,
        WSCORE,
        HOOK,
        CONSTANT
    }
}
