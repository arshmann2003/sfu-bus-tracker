package ca.sfu.bustracker.collector;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveRetentionTest {

    @Test
    void deletesFilesOlderThanTheCutoff() throws Exception {
        Path root = Files.createTempDirectory("archive-retention");
        Path oldFile = root.resolve("trip_updates/old.pb");
        Path newFile = root.resolve("positions/new.pb");
        Files.createDirectories(oldFile.getParent());
        Files.createDirectories(newFile.getParent());
        Files.writeString(oldFile, "old");
        Files.writeString(newFile, "new");
        Files.setLastModifiedTime(oldFile, FileTime.from(Instant.parse("2026-10-01T00:00:00Z")));

        int removed = ArchiveRetention.deleteOlderThan(root, Instant.parse("2026-11-01T00:00:00Z"));

        assertEquals(1, removed);
        assertFalse(Files.exists(oldFile));
        assertTrue(Files.exists(newFile));
    }
}
