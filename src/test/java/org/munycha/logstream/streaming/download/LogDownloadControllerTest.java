package org.munycha.logstream.streaming.download;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.munycha.logstream.common.config.CorsConfig;
import org.munycha.logstream.common.config.LogstreamProperties;
import org.munycha.logstream.common.exception.GlobalExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LogDownloadController.class)
@AutoConfigureMockMvc(addFilters = false)  // bypass Spring Security filter chain — slice test focus is the controller
@Import({LogFileResolver.class, CorsConfig.class, GlobalExceptionHandler.class})
class LogDownloadControllerTest {

    @Autowired
    MockMvc mockMvc;

    // MockBean replaces LogstreamProperties in the context — needed by both
    // LogFileResolver and CorsConfig (loaded by @WebMvcTest as a WebMvcConfigurer).
    @MockitoBean
    LogstreamProperties properties;

    // New temp dir per test method — files written in one test don't bleed into another.
    @TempDir
    Path logDir;

    @BeforeEach
    void setUp() {
        when(properties.getChannels()).thenReturn(List.of("server-channel", "system-channel"));
        when(properties.getAllowedOrigins()).thenReturn(List.of("http://localhost:5173"));
        when(properties.getLogDir()).thenReturn(logDir.toString());
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void download_validChannelAndFile_returns200() throws Exception {
        Files.writeString(logDir.resolve("server-channel.log"), "line1\nline2\n");

        mockMvc.perform(get("/api/logs/download").param("channel", "server-channel"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"server-channel.log\""))
                .andExpect(content().string("line1\nline2\n"));
    }

    @Test
    void download_validChannelAndFile_contentLengthMatchesFileSize() throws Exception {
        Path file = logDir.resolve("system-channel.log");
        Files.writeString(file, "hello world");

        mockMvc.perform(get("/api/logs/download").param("channel", "system-channel"))
                .andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", Files.size(file)));
    }

    @Test
    void download_emptyFile_returns200WithEmptyBody() throws Exception {
        Files.createFile(logDir.resolve("server-channel.log"));

        mockMvc.perform(get("/api/logs/download").param("channel", "server-channel"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    // -------------------------------------------------------------------------
    // Allowlist validation
    // -------------------------------------------------------------------------

    @Test
    void download_channelNotInAllowlist_returns404() throws Exception {
        mockMvc.perform(get("/api/logs/download").param("channel", "unknown-channel"))
                .andExpect(status().isNotFound());
    }

    @Test
    void download_emptyChannel_returns404() throws Exception {
        mockMvc.perform(get("/api/logs/download").param("channel", ""))
                .andExpect(status().isNotFound());
    }

    @Test
    void download_nullChannelsList_returns404() throws Exception {
        when(properties.getChannels()).thenReturn(null);

        mockMvc.perform(get("/api/logs/download").param("channel", "server-channel"))
                .andExpect(status().isNotFound());
    }

    @Test
    void download_missingChannelParam_returns400() throws Exception {
        // Spring rejects missing required @RequestParam before the controller runs
        mockMvc.perform(get("/api/logs/download"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // logDir configuration
    // -------------------------------------------------------------------------

    @Test
    void download_logDirNull_returns404() throws Exception {
        when(properties.getLogDir()).thenReturn(null);

        mockMvc.perform(get("/api/logs/download").param("channel", "server-channel"))
                .andExpect(status().isNotFound());
    }

    @Test
    void download_logDirBlank_returns404() throws Exception {
        when(properties.getLogDir()).thenReturn("   ");

        mockMvc.perform(get("/api/logs/download").param("channel", "server-channel"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Path traversal — all fail the allowlist before reaching the filesystem.
    // -------------------------------------------------------------------------

    @Test
    void download_traversalDotDotForwardSlash_returns404() throws Exception {
        mockMvc.perform(get("/api/logs/download").param("channel", "../secret"))
                .andExpect(status().isNotFound());
    }

    @Test
    void download_traversalDotDotBackslash_returns404() throws Exception {
        mockMvc.perform(get("/api/logs/download").param("channel", "..\\secret"))
                .andExpect(status().isNotFound());
    }

    @Test
    void download_traversalAbsolutePath_returns404() throws Exception {
        mockMvc.perform(get("/api/logs/download").param("channel", "/etc/passwd"))
                .andExpect(status().isNotFound());
    }

    @Test
    void download_traversalMultipleSegments_returns404() throws Exception {
        mockMvc.perform(get("/api/logs/download").param("channel", "../../etc/shadow"))
                .andExpect(status().isNotFound());
    }

    @Test
    void download_traversalUrlEncodedSlash_returns404() throws Exception {
        // Spring decodes %2F before the controller receives the parameter
        mockMvc.perform(get("/api/logs/download?channel=..%2Fsecret"))
                .andExpect(status().isNotFound());
    }

    @Test
    void download_traversalDoubleEncoded_returns404() throws Exception {
        mockMvc.perform(get("/api/logs/download?channel=..%252Fsecret"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // File state
    // -------------------------------------------------------------------------

    @Test
    void download_validChannelFileMissing_returns404() throws Exception {
        // File not created — valid channel but no .log file on disk
        mockMvc.perform(get("/api/logs/download").param("channel", "server-channel"))
                .andExpect(status().isNotFound());
    }

    @Test
    void download_validChannelPathIsDirectory_returns404() throws Exception {
        // server-channel.log exists but is a directory, not a regular file
        Files.createDirectory(logDir.resolve("server-channel.log"));

        mockMvc.perform(get("/api/logs/download").param("channel", "server-channel"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Symlink escape — boundary re-check via toRealPath()
    // Disabled on Windows: creating symlinks there requires elevated privileges.
    // -------------------------------------------------------------------------

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void download_symlinkInsideLogDirPointingOutside_returns404() throws Exception {
        Path secretFile = Files.createTempFile("secret", ".txt");
        Files.writeString(secretFile, "sensitive data");

        try {
            Path link = logDir.resolve("server-channel.log");
            Files.createSymbolicLink(link, secretFile);

            mockMvc.perform(get("/api/logs/download").param("channel", "server-channel"))
                    .andExpect(status().isNotFound());
        } finally {
            Files.deleteIfExists(secretFile);
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void download_symlinkToDirectoryOutsideLogDir_returns404() throws Exception {
        Path outsideDir = Files.createTempDirectory("outside");

        try {
            Path link = logDir.resolve("server-channel.log");
            Files.createSymbolicLink(link, outsideDir);

            mockMvc.perform(get("/api/logs/download").param("channel", "server-channel"))
                    .andExpect(status().isNotFound());
        } finally {
            Files.deleteIfExists(outsideDir);
        }
    }

    // -------------------------------------------------------------------------
    // Information disclosure
    // -------------------------------------------------------------------------

    @Test
    void download_404Response_doesNotLeakServerPath() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/logs/download").param("channel", "unknown-channel"))
                .andExpect(status().isNotFound())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains(logDir.toString()),
                "404 response body must not contain the server filesystem path");
    }

    @Test
    void download_404Response_doesNotLeakFilename() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/logs/download").param("channel", "unknown-channel"))
                .andExpect(status().isNotFound())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("unknown-channel"),
                "404 response body must not echo back the channel name");
    }
}
