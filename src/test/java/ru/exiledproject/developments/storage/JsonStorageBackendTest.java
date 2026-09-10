package ru.exiledproject.developments.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JsonStorageBackendTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsUpdatesAndDeletesWithoutLeavingTemporaryFiles() throws Exception {
        Path file = temporaryDirectory.resolve("nested/player-data.json");
        UUID playerId = UUID.randomUUID();
        JsonStorageBackend backend = new JsonStorageBackend(file);
        backend.initialize();

        backend.writeBatch(List.of(new StorageWrite(playerId, "coins", 12D, 100L)));
        assertEquals(12D, backend.loadPlayer(playerId, Set.of()).get("coins").value());

        backend.writeBatch(List.of(new StorageWrite(playerId, "coins", null, 200L)));
        assertFalse(backend.loadPlayer(playerId, Set.of()).containsKey("coins"));

        JsonStorageBackend reopened = new JsonStorageBackend(file);
        reopened.initialize();
        assertEquals(Map.of(), reopened.loadAll(Set.of()));
        try (var files = java.nio.file.Files.list(file.getParent())) {
            assertEquals(List.of(file), files.toList());
        }
    }

    @Test
    void preservesExpirationMetadata() throws Exception {
        Path file = temporaryDirectory.resolve("ttl.json");
        UUID playerId = UUID.randomUUID();
        JsonStorageBackend backend = new JsonStorageBackend(file);
        backend.initialize();
        backend.writeBatch(List.of(new StorageWrite(playerId, "temporary", "value", 100L, 500L)));
        assertEquals(500L, backend.loadPlayer(playerId, Set.of()).get("temporary").expiresAt());
    }
}
