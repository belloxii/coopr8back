package com.invo.coopr8.payment.paystack;

import java.time.Duration;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * The HTTP client COOPR8 uses to talk to Paystack, and nothing else.
 *
 * <p>A dedicated, named {@link RestTemplate} rather than a shared one or a
 * {@code new RestTemplate()} inside a service, for three reasons:
 *
 * <ol>
 *   <li><strong>Timeouts.</strong> {@code new RestTemplate()} waits forever. A provider that stops
 *       answering mid-request would hold a COOPR8 request thread indefinitely, and -- worse -- an
 *       initialization call is made with a payment row already committed, so a hung call is a payment
 *       nobody can resolve. Both timeouts are set here so no Paystack call can outlive them.</li>
 *   <li><strong>Test doubles.</strong> A bean is something a test can bind
 *       {@code MockRestServiceServer} to, which is how the Paystack contract is exercised without
 *       calling Paystack. A client constructed inside a service cannot be substituted, which is how
 *       test suites end up making real calls to a payment provider.</li>
 *   <li><strong>Blast radius.</strong> Interceptors or message converters added for Paystack apply to
 *       Paystack only.</li>
 * </ol>
 *
 * <p>No credentials live here. The platform secret is applied per request by
 * {@link PaystackApiClient} from configuration, so it is never captured into a shared, injectable,
 * inspectable object.
 */
@Configuration
public class PaystackRestConfig {

    /** Bean name of the Paystack client, so injection points and tests agree on one spelling. */
    public static final String REST_TEMPLATE = "paystackRestTemplate";

    /** Long enough for a TLS handshake to a healthy provider, short enough to fail a dead one. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Paystack's transaction endpoints answer well inside this. The ceiling exists so that a provider
     * that accepts a connection and then stalls fails the request instead of the request thread.
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    @Bean(REST_TEMPLATE)
    public RestTemplate paystackRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(CONNECT_TIMEOUT)
                .setReadTimeout(READ_TIMEOUT)
                .build();
    }
}
