package com.invo.coopr8.configuration;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.invo.coopr8.dto.config.MembershipConfigRequest;
import com.invo.coopr8.dto.config.MembershipConfigResponse;
import com.invo.coopr8.dto.config.RepaymentConfigRequest;
import com.invo.coopr8.dto.config.RepaymentConfigResponse;
import com.invo.coopr8.dto.config.SharesConfigRequest;
import com.invo.coopr8.dto.config.SharesConfigResponse;
import com.invo.coopr8.model.ConfigDomain;
import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.OrganizationMembershipConfig;
import com.invo.coopr8.model.OrganizationRepaymentConfig;
import com.invo.coopr8.model.OrganizationSharesConfig;
import com.invo.coopr8.security.CurrentAuth;
import com.invo.coopr8.service.OrganizationService;

import lombok.RequiredArgsConstructor;

/**
 * An administrator reading and editing their cooperative's shares, repayment and membership rules.
 *
 * <p>Three singleton configurations in one service because each is a single row read and written the
 * same way, and three near-identical classes would be three places to fix the same mistake. The loan
 * configuration is separate only because loan products and their exclusions travel with it.
 *
 * <h2>What is deliberately absent</h2>
 * <ul>
 *   <li><strong>No penalty setting.</strong> Decision 6 of
 *       {@code docs/phase4-tenant-business-configuration.md}: COOPR8 has no penalty column, field or
 *       setting anywhere, and two existing tests fail the build if one appears. A late-payment charge
 *       is a lending product decision with regulatory weight, and adding a column for one would
 *       amount to the platform deciding cooperatives should levy them.
 *   <li><strong>No Class C setting.</strong> Nothing here can move the password floor, the JWT
 *       lifetime, a batch limit, the subscription rules, or anything to do with tenant isolation. A
 *       cooperative cannot weaken the platform's security for its own members by editing a form.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AdminPolicyConfigService {

    private final OrganizationService organizationService;
    private final ConfigProvisioner provisioner;
    private final ConfigAuditWriter auditWriter;

    // ================================================================================ shares

    @Transactional
    public SharesConfigResponse sharesConfig() {
        return SharesConfigResponse.of(provisioner.sharesConfig(currentOrganization()));
    }

    @Transactional
    public SharesConfigResponse replaceSharesConfig(SharesConfigRequest request) {
        Organization organization = currentOrganization();
        OrganizationSharesConfig configuration = provisioner.sharesConfig(organization);

        ConfigRules.requireOrderedAmounts(request.minPurchaseAmount(), request.maxPurchaseAmount(),
                "share purchase");

        ConfigDiff diff = new ConfigDiff();
        diff.money("sharePrice", configuration.getSharePrice(), request.sharePrice(),
                configuration::setSharePrice);
        diff.money("minPurchaseAmount", configuration.getMinPurchaseAmount(),
                request.minPurchaseAmount(), configuration::setMinPurchaseAmount);
        diff.money("maxPurchaseAmount", configuration.getMaxPurchaseAmount(),
                request.maxPurchaseAmount(), configuration::setMaxPurchaseAmount);
        diff.flag("approvalRequired", configuration.getApprovalRequired(),
                request.approvalRequired(), configuration::setApprovalRequired);
        diff.flag("withdrawalAllowed", configuration.getWithdrawalAllowed(),
                request.withdrawalAllowed(), configuration::setWithdrawalAllowed);
        diff.money("minWithdrawalAmount", configuration.getMinWithdrawalAmount(),
                request.minWithdrawalAmount(), configuration::setMinWithdrawalAmount);

        auditWriter.record(organization, ConfigDomain.SHARES_CONFIG, diff, request.reason());
        return SharesConfigResponse.of(configuration);
    }

    // ============================================================================= repayment

    @Transactional
    public RepaymentConfigResponse repaymentConfig() {
        return RepaymentConfigResponse.of(provisioner.repaymentConfig(currentOrganization()));
    }

    @Transactional
    public RepaymentConfigResponse replaceRepaymentConfig(RepaymentConfigRequest request) {
        Organization organization = currentOrganization();
        OrganizationRepaymentConfig configuration = provisioner.repaymentConfig(organization);

        ConfigDiff diff = new ConfigDiff();
        diff.flag("allowPartialRepayment", configuration.getAllowPartialRepayment(),
                request.allowPartialRepayment(), configuration::setAllowPartialRepayment);
        diff.flag("allowOverpayment", configuration.getAllowOverpayment(),
                request.allowOverpayment(), configuration::setAllowOverpayment);
        diff.money("settlementTolerance", configuration.getSettlementTolerance(),
                request.settlementTolerance(), configuration::setSettlementTolerance);

        auditWriter.record(organization, ConfigDomain.REPAYMENT_CONFIG, diff, request.reason());
        return RepaymentConfigResponse.of(configuration);
    }

    // ============================================================================ membership

    @Transactional
    public MembershipConfigResponse membershipConfig() {
        return MembershipConfigResponse.of(provisioner.membershipConfig(currentOrganization()));
    }

    @Transactional
    public MembershipConfigResponse replaceMembershipConfig(MembershipConfigRequest request) {
        Organization organization = currentOrganization();
        OrganizationMembershipConfig configuration = provisioner.membershipConfig(organization);

        String defaultStatus = ConfigRules.requireMemberStatus(request.defaultMemberStatus());

        // Two settings that would contradict each other. "Activate new members automatically" and
        // "new members start PENDING" cannot both hold: whichever the code eventually consulted
        // first would silently win, and an administrator reading the form would have no way to
        // know which.
        if (Boolean.TRUE.equals(request.autoActivateMembers())
                && !OrganizationMembershipConfig.STATUS_ACTIVE.equals(defaultStatus)) {
            throw ConfigRules.refusal("Activating new members automatically requires their starting "
                    + "status to be " + OrganizationMembershipConfig.STATUS_ACTIVE
                    + ". Either set the starting status to "
                    + OrganizationMembershipConfig.STATUS_ACTIVE
                    + " or turn automatic activation off.");
        }

        ConfigDiff diff = new ConfigDiff();
        diff.flag("requireEmail", configuration.getRequireEmail(), request.requireEmail(),
                configuration::setRequireEmail);
        diff.flag("requirePhone", configuration.getRequirePhone(), request.requirePhone(),
                configuration::setRequirePhone);
        diff.flag("requirePsn", configuration.getRequirePsn(), request.requirePsn(),
                configuration::setRequirePsn);
        diff.flag("requirePassport", configuration.getRequirePassport(), request.requirePassport(),
                configuration::setRequirePassport);
        diff.flag("requireNextOfKin", configuration.getRequireNextOfKin(),
                request.requireNextOfKin(), configuration::setRequireNextOfKin);
        diff.flag("autoActivateMembers", configuration.getAutoActivateMembers(),
                request.autoActivateMembers(), configuration::setAutoActivateMembers);
        diff.text("defaultMemberStatus", configuration.getDefaultMemberStatus(), defaultStatus,
                configuration::setDefaultMemberStatus);

        auditWriter.record(organization, ConfigDomain.MEMBERSHIP_CONFIG, diff, request.reason());
        return MembershipConfigResponse.of(configuration);
    }

    // ============================================================================= internals

    private Organization currentOrganization() {
        CurrentAuth.requireAdmin();
        return organizationService.currentOrganizationEntity();
    }
}
