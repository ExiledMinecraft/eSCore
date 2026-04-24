package ru.exiledproject.developments.script;

public record ScriptValueSource(Type type, String key, Object value) {
    public enum Type {
        PLAYER_DATA,
        PLACEHOLDER_API,
        WSCORE,
        HOOK,
        CONSTANT
    }
}
