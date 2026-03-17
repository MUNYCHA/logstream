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
        String filePath = properties.getLogFiles() != null ? properties.getLogFiles().get(topic) : null;

        if (filePath == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No log file configured for topic: " + topic);
        }

        Path path = Paths.get(filePath);

        if (!Files.exists(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Log file not found: " + filePath);
        }

        String filename = path.getFileName().toString();

        response.setContentType("text/plain");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.setContentLengthLong(Files.size(path));

        Files.copy(path, response.getOutputStream());
    }
}
