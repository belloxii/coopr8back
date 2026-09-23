package com.invo.coopr8.configuration;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.invo.coopr8.model.InterestMethod;
import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.OrganizationLoanConfig;
import com.invo.coopr8.model.OrganizationMembershipConfig;
import com.invo.coopr8.model.OrganizationRepaymentConfig;
import com.invo.coopr8.model.OrganizationSharesConfig;
import com.invo.coopr8.repository.OrganizationLoanConfigRepository;
import com.invo.coopr8.repository.OrganizationMembershipConfigRepository;
import com.invo.coopr8.repository.OrganizationRepaymentConfigRepository;
import com.invo.coopr8.repository.OrganizationSharesConfigRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Gives a cooperative its four singleton configuration rows if it does not have them yet.
 *
 * <h2>Why this class has to exist</h2>
 * {@code V9__phase4_seed_defaults.sql} seeded every organization that existed when it ran. It cannot
 * seed one created afterwards -- a migration runs once, at deploy time, and a cooperative onboarded
 * the following week arrives with no configuration at all. Every Stage 2 read would then be a 404
 * on a screen that is supposed to show a form.
 *
 * <p>{@code docs/phase4-tenant-business-configuration.md} §8.4 states the rule this implements:
 * provisioning a new cooperative is <strong>a code path, not a migration artefact</strong>. The
 * integration tests truncate the tenant tables, which removes V9's rows, so every test creates its
 * organizations in exactly the un-provisioned state a newly onboarded cooperative is in -- and that
 * is deliberate, because it is the state this class is for.
 *
 * <h2>The values are V9's, and are not a second opinion</h2>
 * Every default below is the same value {@code V9__phase4_seed_defaults.sql} writes, for the same
 * reason: they reproduce what the platform does today, so a cooperative that has never opened the
 * settings screen behaves exactly as it did before Phase 4. {@code ConfigProvisioningTest} asserts
 * the two agree, because two places holding the same defaults is a place for them to drift.
 *
 * <h2>No audit row is written</h2>
 * Same reasoning as V9's. {@code actor_user_id} is {@code NOT NULL} and a default nobody chose has
 * no actor; attributing it to the administrator who happened to open the screen first would be a
 * false record of a decision that was never made. The audit trail begins at the first change a human
 * makes.
 *
 * <h2>Two collections are deliberately not provisioned</h2>
 * No loan types and no savings plans are created. There is no such thing as a default loan product:
 * inventing one would put a cooperative on record as offering something it never agreed to, and
 * §9 of the architecture document rules it out for the migration on exactly those grounds. An empty
 * product list means "products are not configured", which every later stage must keep treating as
 * "do not enforce products" rather than "no borrowing".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigProvisioner {

    // --- V9's neutral defaults, named so the test can compare against them ----------------

    /** {@code LoanServiceImpl.approveLoan} computes {@code amount / duration}: no interest. */
    public static final InterestMethod DEFAULT_INTEREST_METHOD = InterestMethod.NONE;

    /** No interest, therefore no rate. Scale 3 to match {@code numeric(6,3)}. */
    public static final BigDecimal DEFAULT_INTEREST_RATE = new BigDecimal("0.000");

    /** {@code Loan} has exactly two guarantor columns, both used. */
    public static final int DEFAULT_REQUIRED_GUARANTORS = 2;

    /**
     * The {@code shares} table holds a naira amount and has no unit concept, so units are naira.
     * 1.00 is the only price that keeps that true.
     */
    public static final BigDecimal DEFAULT_SHARE_PRICE = new BigDecimal("1.00");

    /** {@code SharesServiceImpl} always requires approval. */
    public static final boolean DEFAULT_APPROVAL_REQUIRED = true;

    /** {@code SharesServiceImpl} permits withdrawal. */
    public static final boolean DEFAULT_WITHDRAWAL_ALLOWED = true;

    /** {@code RepayServiceImpl} requires an exact multiple of the instalment. */
    public static final boolean DEFAULT_ALLOW_PARTIAL_REPAYMENT = false;

    /** As above. */
    public static final boolean DEFAULT_ALLOW_OVERPAYMENT = false;

    /** As above: no tolerance at all. */
    public static final BigDecimal DEFAULT_SETTLEMENT_TOLERANCE = new BigDecimal("0.00");

    /** {@code @NotBlank} on {@code UserRequest.email}. */
    public static final boolean DEFAULT_REQUIRE_EMAIL = true;

    /** {@code @NotBlank} on {@code UserRequest.phone}. */
    public static final boolean DEFAULT_REQUIRE_PHONE = true;

    /** No validation on those fields today. */
    public static final boolean DEFAULT_REQUIRE_PSN = false;

    /** As above. */
    public static final boolean DEFAULT_REQUIRE_PASSPORT = false;

    /** As above. */
    public static final boolean DEFAULT_REQUIRE_NEXT_OF_KIN = false;

    /** Activation is a separate admin action. */
    public static final boolean DEFAULT_AUTO_ACTIVATE_MEMBERS = false;

    /** Tenants opt in to a forced first-login password change; default is off. */
    public static final boolean DEFAULT_REQUIRE_INITIAL_PASSWORD_CHANGE = false;

    /** {@code createAccount(request)} delegates to {@code createAccount(request, "PENDING")}. */
    public static final String DEFAULT_MEMBER_STATUS = "PENDING";

    private final OrganizationLoanConfigRepository loanConfigRepository;
    private final OrganizationSharesConfigRepository sharesConfigRepository;
    private final OrganizationRepaymentConfigRepository repaymentConfigRepository;
    private final OrganizationMembershipConfigRepository membershipConfigRepository;

    // ------------------------------------------------------------------ one at a time

    /**
     * This cooperative's loan configuration, creating the neutral default if it has none.
     *
     * <p>{@code @Transactional} without {@code readOnly}, because it may write. Callers that go on to
     * modify the row are themselves transactional, so the read and the change share one transaction
     * and the returned entity is managed.
     */
    @Transactional
    public OrganizationLoanConfig loanConfig(Organization organization) {
        return loanConfigRepository.findByOrganizationId(organization.getId())
                .orElseGet(() -> {
                    logProvisioning(organization, "loan");
                    return loanConfigRepository.save(OrganizationLoanConfig.builder()
                            .organization(organization)
                            .interestMethod(DEFAULT_INTEREST_METHOD)
                            .interestRate(DEFAULT_INTEREST_RATE)
                            .requiredGuarantors(DEFAULT_REQUIRED_GUARANTORS)
                            .build());
                });
    }

    /** This cooperative's shares configuration, creating the neutral default if it has none. */
    @Transactional
    public OrganizationSharesConfig sharesConfig(Organization organization) {
        return sharesConfigRepository.findByOrganizationId(organization.getId())
                .orElseGet(() -> {
                    logProvisioning(organization, "shares");
                    return sharesConfigRepository.save(OrganizationSharesConfig.builder()
                            .organization(organization)
                            .sharePrice(DEFAULT_SHARE_PRICE)
                            .approvalRequired(DEFAULT_APPROVAL_REQUIRED)
                            .withdrawalAllowed(DEFAULT_WITHDRAWAL_ALLOWED)
                            .build());
                });
    }

    /** This cooperative's repayment configuration, creating the neutral default if it has none. */
    @Transactional
    public OrganizationRepaymentConfig repaymentConfig(Organization organization) {
        return repaymentConfigRepository.findByOrganizationId(organization.getId())
                .orElseGet(() -> {
                    logProvisioning(organization, "repayment");
                    return repaymentConfigRepository.save(OrganizationRepaymentConfig.builder()
                            .organization(organization)
                            .allowPartialRepayment(DEFAULT_ALLOW_PARTIAL_REPAYMENT)
                            .allowOverpayment(DEFAULT_ALLOW_OVERPAYMENT)
                            .settlementTolerance(DEFAULT_SETTLEMENT_TOLERANCE)
                            .build());
                });
    }

    /** This cooperative's membership configuration, creating the neutral default if it has none. */
    @Transactional
    public OrganizationMembershipConfig membershipConfig(Organization organization) {
        return membershipConfigRepository.findByOrganizationId(organization.getId())
                .orElseGet(() -> {
                    logProvisioning(organization, "membership");
                    return membershipConfigRepository.save(OrganizationMembershipConfig.builder()
                            .organization(organization)
                            .requireEmail(DEFAULT_REQUIRE_EMAIL)
                            .requirePhone(DEFAULT_REQUIRE_PHONE)
                            .requirePsn(DEFAULT_REQUIRE_PSN)
                            .requirePassport(DEFAULT_REQUIRE_PASSPORT)
                            .requireNextOfKin(DEFAULT_REQUIRE_NEXT_OF_KIN)
                            .autoActivateMembers(DEFAULT_AUTO_ACTIVATE_MEMBERS)
                            .requireInitialPasswordChange(DEFAULT_REQUIRE_INITIAL_PASSWORD_CHANGE)
                            .defaultMemberStatus(DEFAULT_MEMBER_STATUS)
                            .build());
                });
    }

    /**
     * All four at once, for a cooperative being onboarded.
     *
     * <p>Not called by anything in Stage 2 -- each endpoint provisions the one configuration it
     * needs -- and provided because the onboarding path is where this belongs once there is one.
     * Idempotent, so calling it on a cooperative that already has configuration does nothing.
     */
    @Transactional
    public void provisionAll(Organization organization) {
        loanConfig(organization);
        sharesConfig(organization);
        repaymentConfig(organization);
        membershipConfig(organization);
    }

    private void logProvisioning(Organization organization, String domain) {
        log.info("Cooperative {} has no {} configuration; writing the neutral defaults. "
                        + "No audit record is written for a default nobody chose.",
                organization.getId(), domain);
    }
}
