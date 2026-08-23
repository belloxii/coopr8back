package com.invo.coopr8.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.invo.coopr8.service.PaystackService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Payment-gateway callbacks.
 *
 * <p>This is the one unauthenticated write path in the application, so the body is treated as a
 * hint rather than as fact: {@code PaystackService} re-fetches the transaction from Paystack and
 * acts only on what comes back. See that class for how the cooperative is established.
 *
 * <p>The payload is no longer logged. It carries the payer's email address, amount and reference,
 * and this log line is written on every payment for every cooperative on the platform.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/webhook")
public class WebhookController {

    private final PaystackService paystackService;

    @PostMapping("/paystack")
    public ResponseEntity<String> receivePaystackWebhook(@RequestBody Map<String, Object> payload) {
        log.info("Received Paystack webhook: event={}", payload.get("event"));

        try {
            paystackService.handleWebhook(payload);
            return ResponseEntity.ok("Webhook processed");
        } catch (Exception e) {
            log.error("Error processing Paystack webhook", e);
            return ResponseEntity.status(500).body("Webhook processing failed");
        }
    }
}
