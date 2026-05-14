package com.baek.viewer.controller;

import com.baek.viewer.service.AuthService;
import com.baek.viewer.service.SourceBlockService;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/source-block")
public class SourceBlockController {

    private final SourceBlockService sourceBlockService;
    private final AuthService authService;

    public SourceBlockController(SourceBlockService sourceBlockService, AuthService authService) {
        this.sourceBlockService = sourceBlockService;
        this.authService = authService;
    }

    @PostMapping(value = "/download")
    public ResponseEntity<StreamingResponseBody> download(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
            @RequestHeader(value = "X-Editor-Token", required = false) String editorToken,
            @RequestBody Map<String, Object> body
    ) {
        boolean authed = authService.isAdmin(adminToken) || authService.isEditor(editorToken);
        if (!authed) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "login_required");
        }

        String repoName = body.get("repoName") != null ? String.valueOf(body.get("repoName")).trim() : "";
        @SuppressWarnings("unchecked")
        List<Number> rawIds = (List<Number>) body.get("ids");
        String dateStr = body.get("blockedDate") != null ? String.valueOf(body.get("blockedDate")).trim() : "";
        String ticketNo = body.get("blockedTicketNo") != null ? String.valueOf(body.get("blockedTicketNo")).trim() : "";
        String ticketTitle = body.get("blockedTicketTitle") != null ? String.valueOf(body.get("blockedTicketTitle")).trim() : "";

        if (repoName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repoName_required");
        }
        if (rawIds == null || rawIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids_required");
        }
        if (rawIds.size() > SourceBlockService.MAX_IDS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "too_many_ids");
        }

        final LocalDate modalBlockedDate;
        if (dateStr.isBlank()) {
            modalBlockedDate = null;
        } else {
            try {
                modalBlockedDate = LocalDate.parse(dateStr);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "blockedDate_invalid");
            }
        }

        List<Long> ids = rawIds.stream().map(Number::longValue).toList();

        try {
            sourceBlockService.validateSourceBlockDownload(repoName, ids, modalBlockedDate, ticketNo, ticketTitle);
        } catch (IllegalArgumentException ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : "invalid_request";
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        }

        LocalDate zipLabelDate = modalBlockedDate != null ? modalBlockedDate : LocalDate.now();
        String fn = "url-block-" + repoName + "-" + zipLabelDate;
        String encoded = URLEncoder.encode(fn, StandardCharsets.UTF_8);

        StreamingResponseBody stream = out -> {
            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(out)) {
                sourceBlockService.writeBlockedSourcesZip(repoName, ids, modalBlockedDate, ticketNo, ticketTitle, zos);
                zos.finish();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encoded + ".zip\"")
                .body(stream);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleRse(ResponseStatusException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", e.getReason() != null ? e.getReason() : "error");
        if ("too_many_ids".equals(m.get("error"))) {
            m.put("limit", SourceBlockService.MAX_IDS);
        }
        return ResponseEntity.status(e.getStatusCode())
                .body(m);
    }
}

