package org.munycha.logstream.streaming.download;

import org.munycha.logstream.common.config.LogstreamProperties;
import org.munycha.logstream.common.exception.InvalidTopicException;
import org.munycha.logstream.common.exception.LogFileNotFoundException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves a topic name to its on-disk log file, layering:
 *  1. Allowlist — topic must be in configured topics (semantic guard)
 *  2. Lexical containment — resolved path must start with the base dir
 *  3. Filesystem state — must exist and be a regular file
 *  4. Symlink boundary — real path (symlinks resolved) must still start with the real base
 *
 * All "not found" cases collapse to the same exception to avoid leaking which check failed.
 */
@Component
public class LogFileResolver {

    private final LogstreamProperties properties;

    public LogFileResolver(LogstreamProperties properties) {
        this.properties = properties;
    }

    public Path resolve(String topic) {
        if (properties.getTopics() == null || !properties.getTopics().contains(topic)) {
            throw new LogFileNotFoundException();
        }

        String logDir = properties.getLogDir();
        if (logDir == null || logDir.isBlank()) {
            throw new LogFileNotFoundException();
        }

        Path base;
        Path resolved;
        try {
            base = Paths.get(logDir).toAbsolutePath().normalize();
            resolved = base.resolve(topic + ".log").normalize();
        } catch (InvalidPathException e) {
            throw new InvalidTopicException();
        }

        if (!resolved.startsWith(base)) {
            throw new InvalidTopicException();
        }

        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            throw new LogFileNotFoundException();
        }

        Path realBase;
        Path realResolved;
        try {
            realBase = base.toRealPath();
            realResolved = resolved.toRealPath();
        } catch (IOException e) {
            throw new LogFileNotFoundException();
        }
        if (!realResolved.startsWith(realBase)) {
            throw new LogFileNotFoundException();
        }
        return realResolved;
    }
}
