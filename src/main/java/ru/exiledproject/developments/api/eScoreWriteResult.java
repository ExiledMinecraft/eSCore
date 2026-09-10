package ru.exiledproject.developments.api;

/** Result of a storage mutation. */
public enum eScoreWriteResult {
    CREATED,
    UPDATED,
    UNCHANGED,
    CONFLICT
}
