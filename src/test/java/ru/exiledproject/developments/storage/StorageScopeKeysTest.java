package ru.exiledproject.developments.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageScopeKeysTest {
    @Test
    void normalizesLogicalAndServerKeysDeterministically() {
        assertEquals("coins", StorageScopeKeys.logicalKey(" Coins::srv::Lobby "));
        assertEquals("coins::srv::lobby", StorageScopeKeys.serverKey(" Coins ", " Lobby "));
        assertTrue(StorageScopeKeys.matchesLogicalKey("coins::srv::lobby", "COINS"));
        assertTrue(StorageScopeKeys.matchesServerKey("COINS::srv::LOBBY", "coins", "lobby"));
        assertFalse(StorageScopeKeys.matchesLogicalKey("coins-extra::srv::lobby", "coins"));
    }
}
