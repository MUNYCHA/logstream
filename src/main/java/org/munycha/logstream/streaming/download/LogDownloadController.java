package org.munycha.logstream.streaming.download;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/logs")
public class LogDownloadController {

    private final LogFileResolver resolver;

    public LogDownloadController(LogFileResolver resolver) {
        this.resolver = resolver;
    }

    @GetMapping("/download")
    public void download(@RequestParam String topic, HttpServletResponse response) throws IOException {
        Path file = resolver.resolve(topic);

        // Defense-in-depth: scrub the filename used in Content-Disposition
        String safeName = file.getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_");

        response.setContentType("text/plain");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + safeName + "\"");
        response.setContentLengthLong(Files.size(file));

        Files.copy(file, response.getOutputStream());
    }
}
