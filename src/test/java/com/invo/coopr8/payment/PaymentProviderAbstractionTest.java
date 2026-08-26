package com.invo.coopr8.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.invo.coopr8.controller.WebhookController;
import com.invo.coopr8.model.PaymentProviderName;
import com.invo.coopr8.payment.paystack.PaystackApiClient;
import com.invo.coopr8.payment.paystack.PaystackPaymentProvider;
import com.invo.coopr8.support.AbstractIntegrationTest;

/**
 * Paystack is an implementation detail, and these tests are what keep it one.
 *
 * <h2>Why this matters beyond tidiness</h2>
 * COOPR8 will not settle every cooperative through Paystack forever, and the moment a second provider
 * is added the question "which provider is this payment going through" has to have an answer that is a
 * <em>cooperative's configuration</em> rather than a hardcoded class name. A domain that names Paystack
 * types cannot answer it. So the boundary is not stylistic: it is what makes provider choice a data
 * question, and it is also what stops a Paystack-shaped assumption -- kobo, {@code ACCT_} codes,
 * {@code status: false} under HTTP 200 -- from leaking into code that would then be wrong for the next
 * provider.
 *
 * <h2>What each test covers</h2>
 * <ul>
 *   <li>{@link #theDomainDependsOnTheAbstractionAndNotOnPaystack()} -- negatively, that no
 *       payment-domain class names a type in {@code com.invo.coopr8.payment.paystack} anywhere in its
 *       own declarations; positively, that what it holds instead is {@link PaymentProvider} and
 *       {@link PaymentProviderRegistry}. The negative half alone would be satisfied by a domain that
 *       talked to no provider at all.</li>
 *   <li>{@link #paystackIsTheConcreteImplementationInUse()} -- that the abstraction is not an empty
 *       gesture: exactly one implementation is wired, it is {@link PaystackPaymentProvider}, and it is
 *       what the registry hands out for {@code PAYSTACK}.</li>
 * </ul>
 *
 * <p>{@code TenantIsolationArchitectureTest} states the package rule for the whole application; this
 * states it for the classes that would actually be tempted, and adds the positive half, which an
 * architecture rule cannot express.
 */
class PaymentProviderAbstractionTest extends AbstractIntegrationTest {

    /** The one package allowed to know Paystack exists. */
    private static final String PAYSTACK_PACKAGE = "com.invo.coopr8.payment.paystack";

    /**
     * The classes that take money, record it, and set up where it lands -- the ones a provider-specific
     * shortcut would be written into.
     */
    private static final List<Class<?>> PAYMENT_DOMAIN = List.of(
            PaymentProvider.class,
            PaymentProviderRegistry.class,
            PaymentService.class,
            PaymentPostingService.class,
            TenantPaymentSetupService.class,
            TenantPaymentConfigStore.class,
            WebhookController.class);

    @Autowired
    private PaymentProviderRegistry providerRegistry;

    @Autowired
    private List<PaymentProvider> wiredProviders;

    @Test
    @DisplayName("the payment domain depends on PaymentProvider, never on a Paystack type")
    void theDomainDependsOnTheAbstractionAndNotOnPaystack() throws Exception {
        // The needle is findable: if the adapter ever moved out of that package, the scan below would
        // pass for the wrong reason.
        assertThat(PaystackPaymentProvider.class.getName()).startsWith(PAYSTACK_PACKAGE);
        assertThat(PaystackApiClient.class.getName()).startsWith(PAYSTACK_PACKAGE);

        for (Class<?> domainClass : PAYMENT_DOMAIN) {
            for (String declaration : declarationsOf(domainClass)) {
                assertThat(declaration)
                        .as("%s must not name a Paystack type", domainClass.getSimpleName())
                        .doesNotContain(PAYSTACK_PACKAGE);
            }
        }

        // And the positive half: the domain does talk to a provider, through the interface.
        assertThat(dependencyTypesOf(PaymentService.class))
                .as("PaymentService resolves providers through the registry")
                .contains(PaymentProviderRegistry.class);
        assertThat(dependencyTypesOf(TenantPaymentSetupService.class))
                .as("so does payment setup")
                .contains(PaymentProviderRegistry.class);
        assertThat(PaymentProviderRegistry.class.getDeclaredMethod("forProvider", PaymentProviderName.class)
                .getReturnType())
                .as("what the registry hands back is the interface, so a caller cannot accidentally "
                        + "be handed -- or come to depend on -- a concrete provider")
                .isEqualTo(PaymentProvider.class);
    }

    @Test
    @DisplayName("PaystackPaymentProvider is the concrete implementation actually wired")
    void paystackIsTheConcreteImplementationInUse() {
        assertThat(PaymentProvider.class)
                .as("the boundary is an interface, so a second provider is a sibling and not a "
                        + "subclass of Paystack")
                .isInterface();
        assertThat(PaymentProvider.class.isAssignableFrom(PaystackPaymentProvider.class)).isTrue();

        assertThat(wiredProviders)
                .as("exactly one provider is implemented at this stage, and it is Paystack -- "
                        + "PaymentProviderName listing FLUTTERWAVE does not make it supported")
                .hasSize(1);
        assertThat(wiredProviders.get(0)).isInstanceOf(PaystackPaymentProvider.class);

        PaymentProvider resolved = providerRegistry.forProvider(PaymentProviderName.PAYSTACK);
        assertThat(resolved)
                .as("a cooperative configured for PAYSTACK is served by the Paystack adapter")
                .isInstanceOf(PaystackPaymentProvider.class);
        assertThat(resolved.providerName()).isEqualTo(PaymentProviderName.PAYSTACK);

        assertThat(providerRegistry.supports(PaymentProviderName.PAYSTACK)).isTrue();
        assertThat(providerRegistry.supports(PaymentProviderName.FLUTTERWAVE))
                .as("Flutterwave is deliberately not implemented")
                .isFalse();

        // The fail-closed half of the registry: an unimplemented provider is refused, not quietly
        // settled through the one that does exist.
        Throwable unsupported = catchThrowable(
                () -> providerRegistry.forProvider(PaymentProviderName.FLUTTERWAVE));

        assertThat(unsupported)
                .as("naming a provider COOPR8 cannot speak to must fail rather than fall back")
                .isInstanceOf(PaymentProviderException.class)
                .hasMessageContaining("FLUTTERWAVE");
    }

    // ------------------------------------------------------------------ reflection helpers

    /**
     * Every type this class names in its own declarations, as generic type names.
     *
     * <p>Generic names rather than raw classes so that a {@code List<PaystackSomething>} is caught as
     * well as a bare field -- a container is exactly how a provider type would first slip across the
     * boundary.
     */
    private static List<String> declarationsOf(Class<?> type) {
        List<String> declarations = new ArrayList<>();

        for (Field field : type.getDeclaredFields()) {
            declarations.add(field.getGenericType().getTypeName());
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            addAll(declarations, constructor.getGenericParameterTypes());
        }
        for (Method method : type.getDeclaredMethods()) {
            declarations.add(method.getGenericReturnType().getTypeName());
            addAll(declarations, method.getGenericParameterTypes());
        }
        assertThat(declarations)
                .as("%s must have declarations to inspect", type.getSimpleName())
                .isNotEmpty();
        return declarations;
    }

    /** The collaborator types a class holds, as fields. */
    private static List<Class<?>> dependencyTypesOf(Class<?> type) {
        List<Class<?>> dependencies = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            dependencies.add(field.getType());
        }
        return dependencies;
    }

    private static void addAll(List<String> declarations, Type[] types) {
        for (Type type : types) {
            declarations.add(type.getTypeName());
        }
    }
}
