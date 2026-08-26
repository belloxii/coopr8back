package com.invo.coopr8.dto;

import lombok.Data;

/**
 * The answer to a payment initialization: either somewhere to send the payer, or why not.
 *
 * <p>The JSON shape -- {@code status}, {@code message}, {@code data.authorization_url},
 * {@code data.access_code}, {@code data.reference} -- is the one the existing frontend reads, and is
 * kept exactly. It happens to resemble the shape a particular provider returns; it is now built by
 * COOPR8 from a provider-neutral result rather than deserialized from a provider's body, so a second
 * provider produces the same response without the frontend knowing which one answered.
 */
@Data
public class PaymentInitializationResponse {

    private boolean status;
    private String message;
    private PaymentData data;

    @Data
    public static class PaymentData {
        private String authorization_url;
        private String access_code;
        private String reference;
    }

    /** A refusal. Carries no {@code data}, so there is nowhere for the frontend to redirect to. */
    public static PaymentInitializationResponse refused(String message) {
        PaymentInitializationResponse response = new PaymentInitializationResponse();
        response.setStatus(false);
        response.setMessage(message);
        return response;
    }

    /** A checkout the payer can be sent to. */
    public static PaymentInitializationResponse accepted(
            String authorizationUrl, String accessCode, String reference) {

        PaymentData data = new PaymentData();
        data.setAuthorization_url(authorizationUrl);
        data.setAccess_code(accessCode);
        data.setReference(reference);

        PaymentInitializationResponse response = new PaymentInitializationResponse();
        response.setStatus(true);
        response.setMessage("Authorization URL created");
        response.setData(data);
        return response;
    }
}
