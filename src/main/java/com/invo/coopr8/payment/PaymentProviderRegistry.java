package com.invo.coopr8.payment;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.invo.coopr8.model.PaymentProviderName;

/**
 * Resolves a provider name to the implementation that speaks to it.
 *
 * <p><strong>This class is what makes {@code PaymentProviderName.FLUTTERWAVE} harmless.</strong>
 * Support is not what the enum says; support is which beans exist. A configuration naming a provider
 * with no implementation is refused here, at the point of use, with a message that says exactly that.
 * It does not fall back to Paystack -- silently settling one provider's payment through another is
 * the kind of helpfulness that moves money to the wrong place.
 *
 * <p>Adding a provider means adding an implementation bean. This class needs no change, and neither
 * does anything that calls it.
 */
@Component
public class PaymentProviderRegistry {

    private final Map<PaymentProviderName, PaymentProvider> providers =
            new EnumMap<>(PaymentProviderName.class);

    /**
     * @param discovered every {@link PaymentProvider} bean in the context. Empty is allowed at
     *                   construction -- it is a deployment with no provider configured, which fails
     *                   when a payment is attempted rather than at startup.
     */
    public PaymentProviderRegistry(List<PaymentProvider> discovered) {
        for (PaymentProvider provider : discovered) {
            PaymentProvider previous = providers.put(provider.providerName(), provider);
            if (previous != null) {
                // Two beans claiming one provider means payments would route by bean-ordering
                // accident. Fail at startup, where it is cheap to notice.
                throw new IllegalStateException("Two PaymentProvider beans both claim "
                        + provider.providerName() + ": " + previous.getClass().getName()
                        + " and " + provider.getClass().getName());
            }
        }
    }

    /**
     * The implementation for {@code provider}.
     *
     * @throws PaymentProviderException when nothing implements it. This is the fail-closed path for a
     *                                  provider COOPR8 names but does not support.
     */
    public PaymentProvider forProvider(PaymentProviderName provider) {
        PaymentProvider implementation = providers.get(provider);
        if (implementation == null) {
            throw new PaymentProviderException(
                    "No payment provider implementation is available for " + provider
                            + ". COOPR8 will not route this payment through a different provider.");
        }
        return implementation;
    }

    /** Whether {@code provider} has an implementation. For diagnostics, not for routing. */
    public boolean supports(PaymentProviderName provider) {
        return providers.containsKey(provider);
    }
}
