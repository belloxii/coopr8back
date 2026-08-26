package com.invo.coopr8.dto;

import lombok.Data;

/**
 * Whether a payment went through -- the whole of what the browser is told.
 *
 * <p>This replaces handing the frontend the provider's entire verification body, which carried the
 * payer's name and email, the card's type, its last four digits, its issuing bank and country, the
 * provider's fee split and the full authorization object. The page that reads this checks exactly two
 * things, {@code status} and {@code data.status}, so exactly two things are sent.
 *
 * <p>Nothing here decides anything financial. This route exists so the page the payer lands on after
 * checkout can say "done" or "not done"; balances move only on a signature-verified callback.
 */
@Data
public class PaymentVerificationResponse {

    private boolean status;
    private VerificationData data;

    @Data
    public static class VerificationData {
        /** {@code "success"} when the money was taken, {@code "failed"} otherwise. */
        private String status;
    }

    public static PaymentVerificationResponse of(boolean paid) {
        VerificationData data = new VerificationData();
        data.setStatus(paid ? "success" : "failed");

        PaymentVerificationResponse response = new PaymentVerificationResponse();
        // The outer flag reports that COOPR8 answered the question, not that the payment succeeded --
        // the same two-level shape the frontend already expects.
        response.setStatus(true);
        response.setData(data);
        return response;
    }
}
