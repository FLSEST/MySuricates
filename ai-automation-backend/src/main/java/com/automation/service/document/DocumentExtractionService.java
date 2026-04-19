package com.automation.service.document;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.WriteOutContentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Extracts plain-text content from documents (PDF, DOCX, XLSX, HTML, TXT, EPUB, …)
 * using Apache Tika. Pure-Java, no native dependencies. Designed to run outside
 * any {@code @Transactional} scope — parsing is CPU-bound and can be slow for
 * large files (multi-megabyte PDFs take seconds).
 *
 * <p>Resolves relative paths against a base project path and prevents path-traversal
 * out of the base directory. Enforces a configurable max-byte limit to avoid OOM
 * on huge files.
 */
@Service
@Slf4j
public class DocumentExtractionService {

    private final long maxBytes;
    private final int maxCharacters;

    public DocumentExtractionService(
            @Value("${automation.document.max-bytes:52428800}") long maxBytes,
            @Value("${automation.document.max-characters:5000000}") int maxCharacters
    ) {
        this.maxBytes = maxBytes;
        this.maxCharacters = maxCharacters;
    }

    public ExtractionResult extract(String filePathOrRelative, String projectPath) {
        Path resolved = resolvePath(filePathOrRelative, projectPath);

        if (!Files.exists(resolved)) {
            throw new DocumentExtractionException("File not found: " + resolved);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new DocumentExtractionException("Not a regular file: " + resolved);
        }

        long size;
        try {
            size = Files.size(resolved);
        } catch (IOException e) {
            throw new DocumentExtractionException("Cannot read file size: " + resolved, e);
        }
        if (size > maxBytes) {
            throw new DocumentExtractionException(
                    "File exceeds size limit: " + size + " bytes > " + maxBytes + " bytes"
            );
        }

        String detectedType;
        try {
            detectedType = new Tika().detect(resolved);
        } catch (IOException e) {
            throw new DocumentExtractionException("Cannot detect MIME type: " + resolved, e);
        }

        WriteOutContentHandler writeHandler = new WriteOutContentHandler(maxCharacters);
        BodyContentHandler handler = new BodyContentHandler(writeHandler);
        Metadata metadata = new Metadata();
        try (InputStream in = Files.newInputStream(resolved)) {
            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(in, handler, metadata, new ParseContext());
        } catch (WriteLimitReachedException e) {
            log.debug("Character limit {} reached for {}", maxCharacters, resolved);
        } catch (org.xml.sax.SAXException e) {
            throw new DocumentExtractionException(
                    "Extraction failed for " + resolved + ": " + e.getMessage(), e);
        } catch (IOException | TikaException e) {
            throw new DocumentExtractionException(
                    "Extraction failed for " + resolved + ": " + e.getMessage(), e);
        }

        String text = writeHandler.toString();
        log.debug("Extracted {} chars from {} ({} bytes, type={})",
                text.length(), resolved.getFileName(), size, detectedType);

        return new ExtractionResult(
                text,
                detectedType,
                size,
                resolved.toString(),
                metadata.get("dc:title"),
                safeInt(metadata.get("xmpTPg:NPages"))
        );
    }

    private Path resolvePath(String filePath, String projectPath) {
        if (filePath == null || filePath.isBlank()) {
            throw new DocumentExtractionException("file_path is required");
        }
        Path candidate = Path.of(filePath);
        Path base = projectPath != null ? Path.of(projectPath).toAbsolutePath().normalize() : null;
        Path resolved = candidate.isAbsolute() || base == null
                ? candidate.normalize()
                : base.resolve(candidate).normalize();

        if (base != null && !candidate.isAbsolute() && !resolved.startsWith(base)) {
            throw new DocumentExtractionException(
                    "Path traversal not allowed: " + filePath + " escapes project path"
            );
        }
        return resolved;
    }

    private static Integer safeInt(String value) {
        if (value == null) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record ExtractionResult(
            String text,
            String contentType,
            long sizeBytes,
            String resolvedPath,
            String title,
            Integer pageCount
    ) {
        public int characterCount() {
            return text != null ? text.length() : 0;
        }
    }
}
