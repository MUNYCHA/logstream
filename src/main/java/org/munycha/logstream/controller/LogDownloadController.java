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
        String logDir = properties.getLogDir();

        if (logDir == null || logDir.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Log directory not configured");
        }

        Path path = Paths.get(logDir, topic + ".log");

        if (!Files.exists(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Log file not found: " + path);
        }

        String filename = path.getFileName().toString();

        response.setContentType("text/plain");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.setContentLengthLong(Files.size(path));

        Files.copy(path, response.getOutputStream());
    }
}
