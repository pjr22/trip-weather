package com.pjr22.tripweather.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pjr22.tripweather.service.ExportService;
import com.pjr22.tripweather.service.ExportService.ExportException;
import com.pjr22.tripweather.service.ExportService.ExportResult;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/routes")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    // The dot before {format} is matched literally; URLs look like
    // /api/routes/{uuid}/export.gpx. Spring 6+ doesn't strip path extensions
    // by default, so the dot survives into the path variable separator.
    @GetMapping("/{id}/export.{format}")
    public ResponseEntity<?> export(@PathVariable UUID id, @PathVariable String format) {
        try {
            ExportResult result = exportService.export(id, format);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(result.getContentType()));
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(result.getFilename())
                    .build());
            headers.setContentLength(result.getContent().length);
            return new ResponseEntity<>(result.getContent(), headers, 200);
        } catch (ExportException e) {
            log.warn("Export failed: status={} format={} routeId={} message={}",
                    e.getStatus(), format, id, e.getMessage());
            return errorResponse(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error exporting route {} as {}", id, format, e);
            return errorResponse(500, "Internal error during export");
        }
    }

    private ResponseEntity<Map<String, Object>> errorResponse(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
