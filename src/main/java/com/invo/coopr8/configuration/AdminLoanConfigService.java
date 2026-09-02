package com.invo.coopr8.configuration;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.invo.coopr8.dto.config.LoanConfigRequest;
import com.invo.coopr8.dto.config.LoanConfigResponse;
import com.invo.coopr8.dto.config.LoanTypeExclusionRequest;
import com.invo.coopr8.dto.config.LoanTypeExclusionResponse;
import com.invo.coopr8.dto.config.LoanTypeRequest;
import com.invo.coopr8.dto.config.LoanTypeResponse;
import com.invo.coopr8.model.ConfigDomain;
import com.invo.coopr8.model.InterestMethod;
import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.OrganizationLoanConfig;
import com.invo.coopr8.model.OrganizationLoanType;
import com.invo.coopr8.model.OrganizationLoanTypeExclusion;
import com.invo.coopr8.repository.OrganizationLoanTypeExclusionRepository;
import com.invo.coopr8.repository.OrganizationLoanTypeRepository;
import com.invo.coopr8.security.CurrentAuth;
import com.invo.coopr8.service.OrganizationService;

import lombok.RequiredArgsConstructor;

/**
 * An administrator reading and editing their own cooperative's loan rules.
 *
 * <h2>Nothing here changes what a loan does yet</h2>
 * Stage 2 of {@code docs/phase4-tenant-business-configuration.md} is deliberately inert: these rows
 * are written and read back, and no lending code consults them. {@code LoanServiceImpl} still
 * computes {@code amount / duration} with two guarantors and no bounds. That separation is the point
 * -- the settings screens can be built, reviewed and corrected while every existing member keeps
 * borrowing exactly as before, and Stage 4 turns each rule on one at a time with its own tests.
 *
 * <h2>Where the tenant comes from</h2>
 * {@link CurrentAuth#requireAdmin()} and then
 * {@link OrganizationService#currentOrganizationEntity()}, on every single call. No method here
 * accepts an organization id, no request DTO carries one, and nothing is read by id alone: every
 * single-row lookup goes through a {@code findByIdAndOrganizationId}. A neighbouring cooperative's
 * loan product id therefore returns {@code 404} rather than {@code 403}, because confirming that the
 * id exists somewhere on the platform is itself a leak.
 *
 * <h2>Products are withdrawn, not deleted</h2>
 * {@code DELETE /loan-types/{id}} clears {@code active}. A cooperative that has been offering a
 * product has loans referring to it by name and possibly exclusion rules pointing at its id; erasing
 * the row would leave the audit trail describing a product nobody can look up, and the exclusion
 * rows dangling. Withdrawing it stops new applications and keeps the history readable.
 */
@Service
@RequiredArgsConstructor
public class AdminLoanConfigService {

    private final OrganizationService organizationService;
    private final ConfigProvisioner provisioner;
    private final ConfigAuditWriter auditWriter;
    private final OrganizationLoanTypeRepository loanTypeRepository;
    private final OrganizationLoanTypeExclusionRepository exclusionRepository;

    // ================================================================ loan configuration

    /**
     * This cooperative's loan rules, provisioned with the neutral defaults if it has none.
     *
     * <p>Not {@code readOnly}, because the first read of a cooperative onboarded after V9 ran writes
     * the defaults. See {@link ConfigProvisioner}.
     */
    @Transactional
    public LoanConfigResponse loanConfig() {
        return LoanConfigResponse.of(provisioner.loanConfig(currentOrganization()));
    }

    /**
     * Replaces this cooperative's loan rules.
     *
     * <p>A PUT of the whole configuration rather than a PATCH of one field: the bounds constrain each
     * other, so a screen that could move the minimum without restating the maximum would let an
     * administrator produce a configuration neither of the two submissions was.
     */
    @Transactional
    public LoanConfigResponse replaceLoanConfig(LoanConfigRequest request) {
        Organization organization = currentOrganization();
        OrganizationLoanConfig configuration = provisioner.loanConfig(organization);

        InterestMethod method = ConfigRules.requireSelectableInterestMethod(request.interestMethod());
        ConfigRules.requireOrderedAmounts(request.minLoanAmount(), request.maxLoanAmount(),
                "loan amount");
        ConfigRules.requireOrderedMonths(request.minTenureMonths(), request.maxTenureMonths(),
                "tenure");

        ConfigDiff diff = new ConfigDiff();
        diff.enumValue("interestMethod", configuration.getInterestMethod(), method,
                configuration::setInterestMethod);
        diff.money("interestRate", configuration.getInterestRate(), request.interestRate(),
                configuration::setInterestRate);
        diff.money("minLoanAmount", configuration.getMinLoanAmount(), request.minLoanAmount(),
                configuration::setMinLoanAmount);
        diff.money("maxLoanAmount", configuration.getMaxLoanAmount(), request.maxLoanAmount(),
                configuration::setMaxLoanAmount);
        diff.number("minTenureMonths", configuration.getMinTenureMonths(),
                request.minTenureMonths(), configuration::setMinTenureMonths);
        diff.number("maxTenureMonths", configuration.getMaxTenureMonths(),
                request.maxTenureMonths(), configuration::setMaxTenureMonths);
        diff.number("requiredGuarantors", configuration.getRequiredGuarantors(),
                request.requiredGuarantors(), configuration::setRequiredGuarantors);

        auditWriter.record(organization, ConfigDomain.LOAN_CONFIG, diff, request.reason());
        return LoanConfigResponse.of(configuration);
    }

    // ======================================================================= loan products

    /** Every loan product this cooperative has configured, withdrawn ones included. */
    @Transactional(readOnly = true)
    public List<LoanTypeResponse> loanTypes() {
        return loanTypeRepository
                .findAllByOrganizationIdOrderByNameAsc(CurrentAuth.requireOrganizationId())
                .stream()
                .map(LoanTypeResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public LoanTypeResponse loanType(Long id) {
        return LoanTypeResponse.of(requireLoanType(CurrentAuth.requireOrganizationId(), id));
    }

    /**
     * Adds a loan product.
     *
     * <p>Every field is recorded as {@code null -> value}, which is how the trail says a product came
     * into existence. There is no separate "created" entry, because a row of the trail that carries
     * no value would say less than the values do.
     */
    @Transactional
    public LoanTypeResponse createLoanType(LoanTypeRequest request) {
        Organization organization = currentOrganization();

        String name = ConfigRules.normalizedProductName(request.name());
        InterestMethod method =
                ConfigRules.optionalSelectableInterestMethod(request.interestMethod());
        ConfigRules.requireOrderedAmounts(request.minLoanAmount(), request.maxLoanAmount(),
                "loan amount");
        ConfigRules.requireOrderedMonths(request.minTenureMonths(), request.maxTenureMonths(),
                "tenure");
        requireLoanProductNameIsFree(organization.getId(), name, null);

        // A product with no explicit answer is offered: withholding it would create products that
        // exist and cannot be applied for, with nothing on the screen explaining why.
        boolean active = request.active() == null || request.active();

        OrganizationLoanType loanType = loanTypeRepository.save(OrganizationLoanType.builder()
                .organization(organization)
                .name(name)
                .description(trimToNull(request.description()))
                .interestMethod(method)
                .interestRate(request.interestRate())
                .minLoanAmount(request.minLoanAmount())
                .maxLoanAmount(request.maxLoanAmount())
                .minTenureMonths(request.minTenureMonths())
                .maxTenureMonths(request.maxTenureMonths())
                .maxActiveLoans(request.maxActiveLoans())
                .active(active)
                .build());

        ConfigDiff diff = new ConfigDiff(loanType.getId());
        diff.text("name", null, loanType.getName(), ignored -> { });
        diff.text("description", null, loanType.getDescription(), ignored -> { });
        diff.enumValue("interestMethod", null, loanType.getInterestMethod(), ignored -> { });
        diff.money("interestRate", null, loanType.getInterestRate(), ignored -> { });
        diff.money("minLoanAmount", null, loanType.getMinLoanAmount(), ignored -> { });
        diff.money("maxLoanAmount", null, loanType.getMaxLoanAmount(), ignored -> { });
        diff.number("minTenureMonths", null, loanType.getMinTenureMonths(), ignored -> { });
        diff.number("maxTenureMonths", null, loanType.getMaxTenureMonths(), ignored -> { });
        diff.number("maxActiveLoans", null, loanType.getMaxActiveLoans(), ignored -> { });
        diff.flag("active", null, loanType.getActive(), ignored -> { });

        auditWriter.record(organization, ConfigDomain.LOAN_TYPE, diff, request.reason());
        return LoanTypeResponse.of(loanType);
    }

    /** Replaces one loan product's rules. */
    @Transactional
    public LoanTypeResponse replaceLoanType(Long id, LoanTypeRequest request) {
        Organization organization = currentOrganization();
        OrganizationLoanType loanType = requireLoanType(organization.getId(), id);

        String name = ConfigRules.normalizedProductName(request.name());
        InterestMethod method =
                ConfigRules.optionalSelectableInterestMethod(request.interestMethod());
        ConfigRules.requireOrderedAmounts(request.minLoanAmount(), request.maxLoanAmount(),
                "loan amount");
        ConfigRules.requireOrderedMonths(request.minTenureMonths(), request.maxTenureMonths(),
                "tenure");
        requireLoanProductNameIsFree(organization.getId(), name, id);

        ConfigDiff diff = new ConfigDiff(loanType.getId());
        diff.text("name", loanType.getName(), name, loanType::setName);
        diff.text("description", loanType.getDescription(), request.description(),
                loanType::setDescription);
        diff.enumValue("interestMethod", loanType.getInterestMethod(), method,
                loanType::setInterestMethod);
        diff.money("interestRate", loanType.getInterestRate(), request.interestRate(),
                loanType::setInterestRate);
        diff.money("minLoanAmount", loanType.getMinLoanAmount(), request.minLoanAmount(),
                loanType::setMinLoanAmount);
        diff.money("maxLoanAmount", loanType.getMaxLoanAmount(), request.maxLoanAmount(),
                loanType::setMaxLoanAmount);
        diff.number("minTenureMonths", loanType.getMinTenureMonths(), request.minTenureMonths(),
                loanType::setMinTenureMonths);
        diff.number("maxTenureMonths", loanType.getMaxTenureMonths(), request.maxTenureMonths(),
                loanType::setMaxTenureMonths);
        diff.number("maxActiveLoans", loanType.getMaxActiveLoans(), request.maxActiveLoans(),
                loanType::setMaxActiveLoans);
        if (request.active() != null) {
            diff.flag("active", loanType.getActive(), request.active(), loanType::setActive);
        }

        auditWriter.record(organization, ConfigDomain.LOAN_TYPE, diff, request.reason());
        return LoanTypeResponse.of(loanType);
    }

    /**
     * Withdraws a loan product from offer.
     *
     * <p>Idempotent: withdrawing one that is already withdrawn changes nothing and writes no audit
     * row, because {@code ck_organization_config_audit_change} refuses a row whose old and new values
     * are the same, and because a trail of repeated no-ops buries the entries that matter.
     */
    @Transactional
    public LoanTypeResponse withdrawLoanType(Long id, String reason) {
        Organization organization = currentOrganization();
        OrganizationLoanType loanType = requireLoanType(organization.getId(), id);

        ConfigDiff diff = new ConfigDiff(loanType.getId());
        diff.flag("active", loanType.getActive(), false, loanType::setActive);

        auditWriter.record(organization, ConfigDomain.LOAN_TYPE, diff, reason);
        return LoanTypeResponse.of(loanType);
    }

    // ==================================================================== exclusion rules

    /** Every "a member may not hold both of these" rule this cooperative has configured. */
    @Transactional(readOnly = true)
    public List<LoanTypeExclusionResponse> exclusions() {
        Long organizationId = CurrentAuth.requireOrganizationId();
        Map<Long, String> names = loanProductNames(organizationId);
        return exclusionRepository
                .findAllByOrganizationIdOrderByLoanTypeIdAscExcludedLoanTypeIdAsc(organizationId)
                .stream()
                .map(exclusion -> LoanTypeExclusionResponse.of(exclusion,
                        names.get(exclusion.getLoanTypeId()),
                        names.get(exclusion.getExcludedLoanTypeId())))
                .toList();
    }

    /**
     * Declares two loan products mutually exclusive.
     *
     * <p>Both ids are checked against this cooperative before the rule is written, so a submission
     * naming another cooperative's product is a {@code 404} and cannot create a rule referring across
     * the tenant boundary. The pair is order-insensitive:
     * {@link OrganizationLoanTypeExclusion#between} normalizes it, and
     * {@code existsBetween} finds it either way round, so submitting {@code {7,3}} when {@code {3,7}}
     * exists is a conflict rather than a second row.
     */
    @Transactional
    public LoanTypeExclusionResponse createExclusion(LoanTypeExclusionRequest request) {
        Organization organization = currentOrganization();
        Long organizationId = organization.getId();

        OrganizationLoanType first = requireLoanType(organizationId, request.loanTypeId());
        // Checked even though the ids are compared below, so that "the other product does not exist"
        // is answered as a 404 rather than as the self-exclusion refusal.
        OrganizationLoanType second = requireLoanType(organizationId, request.excludedLoanTypeId());

        if (first.getId().equals(second.getId())) {
            throw ConfigRules.refusal("A loan product cannot exclude itself. To limit how many of "
                    + "one product a member may hold at a time, set that product's maximum "
                    + "concurrent loans instead.");
        }
        if (exclusionRepository.existsBetween(organizationId, first.getId(), second.getId())) {
            throw ConfigRules.conflict(first.getName() + " and " + second.getName()
                    + " are already mutually exclusive.");
        }

        OrganizationLoanTypeExclusion exclusion = exclusionRepository.save(
                OrganizationLoanTypeExclusion.between(organization, first.getId(), second.getId()));

        // An exclusion has no mutable columns: the only things that happen to one are coming into
        // existence and ceasing to. The trail records the pair, so a later removal can be read
        // against it.
        ConfigDiff diff = new ConfigDiff(exclusion.getId());
        diff.event("pair", null, pairValue(exclusion));

        auditWriter.record(organization, ConfigDomain.LOAN_TYPE_EXCLUSION, diff, request.reason());
        return LoanTypeExclusionResponse.of(exclusion, first.getName(), second.getName());
    }

    /**
     * Removes an exclusion rule.
     *
     * <p>Deleted outright rather than deactivated. There is no history to preserve in the row itself
     * -- it holds two ids and a timestamp -- and the audit entry recording its removal keeps the
     * record of what the rule was.
     */
    @Transactional
    public void deleteExclusion(Long id, String reason) {
        Organization organization = currentOrganization();
        OrganizationLoanTypeExclusion exclusion = exclusionRepository
                .findByIdAndOrganizationId(id, organization.getId())
                .orElseThrow(ConfigRules::notFound);

        ConfigDiff diff = new ConfigDiff(exclusion.getId());
        diff.event("pair", pairValue(exclusion), null);

        // Audited before the delete, so the reason check refuses the submission while the row is
        // still there rather than after it is gone.
        auditWriter.record(organization, ConfigDomain.LOAN_TYPE_EXCLUSION, diff, reason);
        exclusionRepository.delete(exclusion);
    }

    // ========================================================================== internals

    private Organization currentOrganization() {
        CurrentAuth.requireAdmin();
        return organizationService.currentOrganizationEntity();
    }

    private OrganizationLoanType requireLoanType(Long organizationId, Long id) {
        if (id == null) {
            throw ConfigRules.notFound();
        }
        return loanTypeRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(ConfigRules::notFound);
    }

    /**
     * Refuses a product name another product in this cooperative already holds.
     *
     * <p>{@code ux_organization_loan_type_org_name} would refuse it anyway, as a constraint violation
     * that reaches the administrator as an error naming a database index. Checking first is what turns
     * that into a sentence they can act on. The index remains the authority -- two administrators
     * submitting the same new name at the same moment can both pass this check, and
     * {@code DomainExceptionHandler} turns the loser's constraint violation into the same {@code 409}.
     *
     * @param exemptId the product being renamed, which is allowed to keep its own name
     */
    private void requireLoanProductNameIsFree(Long organizationId, String name, Long exemptId) {
        loanTypeRepository.findByOrganizationIdAndNameIgnoreCase(organizationId, name)
                .filter(existing -> !existing.getId().equals(exemptId))
                .ifPresent(existing -> {
                    throw ConfigRules.conflict("This cooperative already offers a loan product "
                            + "called \"" + existing.getName() + "\".");
                });
    }

    private Map<Long, String> loanProductNames(Long organizationId) {
        return loanTypeRepository.findAllByOrganizationIdOrderByNameAsc(organizationId).stream()
                .collect(Collectors.toMap(OrganizationLoanType::getId,
                        OrganizationLoanType::getName, (first, second) -> first));
    }

    /** The audited value of an exclusion: the two product ids, smaller first. */
    private static String pairValue(OrganizationLoanTypeExclusion exclusion) {
        return exclusion.getLoanTypeId() + "," + exclusion.getExcludedLoanTypeId();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
