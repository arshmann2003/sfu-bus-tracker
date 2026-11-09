package ca.sfu.bustracker.collector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

final class ArchiveRetention {
    private ArchiveRetention() {}

    /** Deletes archived feed files last modified before {@code cutoff}. Returns how many were removed. */
    static int deleteOlderThan(Path root, Instant cutoff) throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        List<Path> stale = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                    stale.add(file);
                }
            }
        }
        int removed = 0;
        for (Path file : stale) {
            if (Files.deleteIfExists(file)) {
                removed++;
            }
        }
        return removed;
    }
}
