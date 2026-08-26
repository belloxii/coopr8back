package com.invo.coopr8.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.invo.coopr8.model.PaymentProviderName;
import com.invo.coopr8.payment.PaymentService;
import com.invo.coopr8.payment.WebhookOutcome;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Payment-provider callbacks. The one unauthenticated write path in the application.
 *
 * <h2>Why the body is bytes</h2>
 * The signature a provider sends covers <strong>the exact bytes it sent</strong>. Binding this
 * parameter as a {@code Map} -- as it was -- would mean Spring parsed the JSON first, and verifying a
 * signature against a re-serialized body is not verification: key order, number formatting and string
 * escaping can all differ from what was signed. So the body arrives as {@code byte[]}, is verified as
 * received, and is only then parsed. Nothing about it is trusted before that.
 *
 * <h2>What the response codes mean to a provider</h2>
 * Providers retry on anything that is not a {@code 2xx}, so the mapping is deliberate:
 * <ul>
 *   <li><strong>401</strong> for an unsigned or wrongly signed body. Nothing was read and nothing will
 *       be; a retry of the same forgery deserves the same answer.</li>
 *   <li><strong>200</strong> for every authentic callback -- processed, duplicate, an event COOPR8 does
 *       not act on, or a reference COOPR8 has no payment for. All four are settled: retrying them would
 *       produce the same outcome, so asking for a retry would just be noise.</li>
 *   <li><strong>500</strong>, by exception, for a genuine failure to record a payment that did happen.
 *       That is the one case where a provider's retry is useful, so it is the one case that is allowed
 *       to propagate.</li>
 * </ul>
 *
 * <h2>What is not logged</h2>
 * Not the payload, not the reference, not the amount, not the payer. This endpoint is hit for every
 * payment made by every member of every cooperative on the platform, and a log line per payment is a
 * financial record of the whole platform sitting in the application log. The outcome is logged; the
 * money is not.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/webhook")
public class WebhookController {

    /** Paystack's signature header. The provider's own spelling, so it belongs at this edge. */
    private static final String PAYSTACK_SIGNATURE_HEADER = "x-paystack-signature";

    private final PaymentService paymentService;

    @PostMapping("/paystack")
    public ResponseEntity<String> receivePaystackWebhook(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(value = PAYSTACK_SIGNATURE_HEADER, required = false) String signature) {

        WebhookOutcome outcome = paymentService.handleProviderCallback(
                PaymentProviderName.PAYSTACK, rawBody, signature);

        if (outcome == WebhookOutcome.REJECTED) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Rejected");
        }

        log.info("Paystack callback handled: {}", outcome);
        return ResponseEntity.ok("Received");
    }
}
