package ru.exiledproject.developments.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteStorageBackendTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void retainsDeletionTombstonesForPolling() throws Exception {
        UUID playerId = UUID.randomUUID();
        SqliteStorageBackend backend = new SqliteStorageBackend(temporaryDirectory.resolve("state.db"), "escore_values");
        backend.initialize();
        backend.writeBatch(List.of(new StorageWrite(playerId, "coins", 5D, 100L)));
        backend.writeBatch(List.of(new StorageWrite(playerId, "coins", null, 200L)));

        List<StorageWrite> updates = backend.pollUpdates(150L, Set.of());
        assertEquals(1, updates.size());
        assertEquals("coins", updates.getFirst().key());
        assertNull(updates.getFirst().value());
        assertTrue(backend.loadPlayer(playerId, Set.of()).containsKey("coins"));
        assertNull(backend.loadPlayer(playerId, Set.of()).get("coins").value());
    }

    @Test
    void rejectsUnsafeTableNames() {
        assertThrows(IllegalArgumentException.class,
                () -> new SqliteStorageBackend(temporaryDirectory.resolve("state.db"), "values; DROP TABLE users"));
    }

    @Test
    void doesNotOverwriteNewerRowsWithStaleWrites() throws Exception {
        UUID playerId = UUID.randomUUID();
        SqliteStorageBackend backend = new SqliteStorageBackend(temporaryDirectory.resolve("ordered.db"), "escore_values");
        backend.initialize();
        backend.writeBatch(List.of(new StorageWrite(playerId, "coins", 10D, 200L)));
        backend.writeBatch(List.of(new StorageWrite(playerId, "coins", 5D, 100L)));

        assertEquals(10D, backend.loadPlayer(playerId, Set.of()).get("coins").value());
        assertEquals(200L, backend.loadPlayer(playerId, Set.of()).get("coins").updatedAt());
    }

    @Test
    void migratesAndPersistsExpirationMetadata() throws Exception {
        UUID playerId = UUID.randomUUID();
        SqliteStorageBackend backend = new SqliteStorageBackend(temporaryDirectory.resolve("ttl.db"), "values_table");
        backend.initialize();
        backend.writeBatch(List.of(new StorageWrite(playerId, "temporary", "value", 100L, 500L)));
        assertEquals(500L, backend.loadPlayer(playerId, Set.of()).get("temporary").expiresAt());
    }
}
