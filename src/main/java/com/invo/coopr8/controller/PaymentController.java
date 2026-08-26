package com.invo.coopr8.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.invo.coopr8.dto.PaymentInitializationRequest;
import com.invo.coopr8.dto.PaymentInitializationResponse;
import com.invo.coopr8.dto.PaymentVerificationResponse;
import com.invo.coopr8.payment.PaymentService;

import lombok.RequiredArgsConstructor;

/**
 * Online payments, from the browser's point of view.
 *
 * <p>The URLs and the JSON shapes are the ones the existing frontend already uses. What changed is
 * underneath: nothing here names a payment provider, and neither route accepts a payment destination.
 * The cooperative whose account a payment settles into is derived from the caller's token --
 * see {@link PaymentService} -- so a request body naming an organization, a subaccount or a provider
 * carries fields that are simply not read.
 *
 * <p>Both routes require authentication. The provider's own callback arrives at
 * {@link WebhookController} instead, and that is the only path that moves money.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * Starts a payment and returns somewhere to send the payer.
     *
     * <p>A refusal comes back as {@code 400} with {@code status: false} and a message -- the shape the
     * frontend already handles. Refusals are ordinary here: a salary-deduction member, a cooperative
     * that has not finished payment setup, an amount that does not match a savings plan.
     */
    @PostMapping("/initialize")
    public ResponseEntity<PaymentInitializationResponse> initializePayment(
            @RequestBody PaymentInitializationRequest request) {

        PaymentInitializationResponse response = paymentService.initializePayment(request);

        return response.isStatus()
                ? ResponseEntity.ok(response)
                : ResponseEntity.badRequest().body(response);
    }

    /**
     * Whether one of the caller's own payments went through, for the page the payer lands on after
     * checkout.
     *
     * <p>Scoped to the caller: somebody else's reference answers {@code 404}, the same as one that does
     * not exist. Nothing financial happens here -- balances move only on a signature-verified callback.
     */
    @GetMapping("/verify/{reference}")
    public ResponseEntity<PaymentVerificationResponse> verifyPayment(@PathVariable String reference) {
        return ResponseEntity.ok(paymentService.verifyPayment(reference));
    }
}
