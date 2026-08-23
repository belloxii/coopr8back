package com.invo.coopr8.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.invo.coopr8.dto.PaystackInitializeRequest;
import com.invo.coopr8.dto.PaystackInitializeResponse;
import com.invo.coopr8.service.PaystackService;

import lombok.AllArgsConstructor;

/**
 * Card payments.
 *
 * <p>Both routes require authentication. {@code initializeTransaction} identifies the payer from
 * the token rather than from the request body -- see {@code PaystackService} for why that
 * matters to the webhook that follows.
 */
@RestController
@RequestMapping("/api/payments")
@AllArgsConstructor
public class PaystackController {

    private final PaystackService paystackService;

    @PostMapping("/initialize")
    public ResponseEntity<PaystackInitializeResponse> initializePayment(
            @RequestBody PaystackInitializeRequest request) {

        PaystackInitializeResponse response = paystackService.initializeTransaction(request);

        // A refusal (salary-deduction member) comes back with status=false.
        if (response == null || !response.isStatus()) {
            return ResponseEntity.badRequest().body(response);
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/verify/{reference}")
    public ResponseEntity<?> verifyTransaction(@PathVariable String reference) {
        Map<String, Object> verificationResult = paystackService.verifyTransaction(reference);
        return ResponseEntity.ok(verificationResult);
    }
}
