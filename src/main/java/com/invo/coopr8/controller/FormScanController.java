package com.invo.coopr8.controller;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.invo.coopr8.dto.FormScanResponse;
import com.invo.coopr8.service.FormExtractionService;

import lombok.AllArgsConstructor;

/**
 * Admin-only endpoint that scans a hardcopy membership form with Gemini vision
 * and returns the extracted fields plus a cropped, uploaded passport photo.
 * Sits under /api/admin/** so it inherits the ADMIN security rule.
 */
@RestController
@RequestMapping("/api/admin/forms")
@AllArgsConstructor
public class FormScanController {

    private final FormExtractionService formExtractionService;

    @PostMapping("/scan")
    public ResponseEntity<?> scan(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body("No form image was provided.");
        }
        try {
            FormScanResponse response = formExtractionService.scan(file);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException notConfigured) {
            // Missing API key — a server configuration problem, not a bad request.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(notConfigured.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body("Could not read the form: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Form scan failed: " + e.getMessage());
        }
    }
}
