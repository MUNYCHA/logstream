package org.munycha.logstream.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.munycha.logstream.config.LogstreamProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api/logs")
public class LogDownloadController {

    private final LogstreamProperties properties;

    public LogDownloadController(LogstreamProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/download")
    public void download(@RequestParam String topic, HttpServletResponse response) throws IOException {
        // 1. Allowlist: reject topics not in config — closes path traversal at the semantic level
        if (properties.getTopics() == null || !properties.getTopics().contains(topic)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Log file not found");
        }

        String logDir = properties.getLogDir();

        if (logDir == null || logDir.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Log file not found");
        }

        // 2. Defense-in-depth: normalized path must stay inside logDir
        Path base;
        Path resolved;
        try {
            base = Paths.get(logDir).toAbsolutePath().normalize();
            resolved = base.resolve(topic + ".log").normalize();
        } catch (InvalidPathException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid topic");
        }

        if (!resolved.startsWith(base)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid topic");
        }

        // 3. Must exist and be a regular file (not a directory, device, or FIFO)
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Log file not found");
        }

        // 4. Symlink boundary check — toRealPath() resolves all symlinks before re-verifying
        Path realBase;
        Path realResolved;
        try {
            realBase     = base.toRealPath();
            realResolved = resolved.toRealPath();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Log file not found");
        }
        if (!realResolved.startsWith(realBase)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Log file not found");
        }

        // 5. Sanitize filename — prevent Content-Disposition header injection
        String safeName = realResolved.getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_");

        response.setContentType("text/plain");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + safeName + "\"");
        response.setContentLengthLong(Files.size(realResolved));

        Files.copy(realResolved, response.getOutputStream());
    }
}
