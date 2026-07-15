package github.luckygc.cap;

import java.nio.file.Files;
import java.nio.file.Path;

final class RepositoryPaths {
    private RepositoryPaths() {}

    static Path root() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(workingDirectory.resolve("tools"))) {
            return workingDirectory;
        }
        Path parent = workingDirectory.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("tools"))) {
            return parent;
        }
        throw new IllegalStateException("repository root unavailable");
    }
}
