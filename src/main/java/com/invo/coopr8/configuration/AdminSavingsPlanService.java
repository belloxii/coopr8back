package com.invo.coopr8.configuration;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.invo.coopr8.dto.config.SavingsPlanRequest;
import com.invo.coopr8.dto.config.SavingsPlanResponse;
import com.invo.coopr8.model.ConfigDomain;
import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.OrganizationSavingsPlan;
import com.invo.coopr8.model.SavingsFrequency;
import com.invo.coopr8.repository.OrganizationSavingsPlanRepository;
import com.invo.coopr8.security.CurrentAuth;
import com.invo.coopr8.service.OrganizationService;

import lombok.RequiredArgsConstructor;

/**
 * An administrator reading and editing their own cooperative's savings plans.
 *
 * <h2>No plan is provisioned by default</h2>
 * Unlike the four singleton configurations, a cooperative starts with an empty plan list and that is
 * the correct state, not a missing one. There is no such thing as a neutral default savings plan: a
 * seeded "Monthly ₦5,000" would put a cooperative on record as offering terms it never agreed to.
 * An empty list means "plans are not configured", which every later stage must keep treating as "do
 * not enforce plans" rather than "no saving" -- members contribute today with no plan concept at all,
 * and Stage 2 must not change that.
 *
 * <h2>Plans are withdrawn, not deleted</h2>
 * Same reasoning as loan products: contributions already recorded were made under a plan, and erasing
 * the row would leave the trail describing a plan nobody can look up.
 */
@Service
@RequiredArgsConstructor
public class AdminSavingsPlanService {

    private final OrganizationService organizationService;
    private final ConfigAuditWriter auditWriter;
    private final OrganizationSavingsPlanRepository planRepository;

    /** Every savings plan this cooperative has configured, withdrawn ones included. */
    @Transactional(readOnly = true)
    public List<SavingsPlanResponse> plans() {
        return planRepository
                .findAllByOrganizationIdOrderByNameAsc(CurrentAuth.requireOrganizationId())
                .stream()
                .map(SavingsPlanResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public SavingsPlanResponse plan(Long id) {
        return SavingsPlanResponse.of(requirePlan(CurrentAuth.requireOrganizationId(), id));
    }

    /** Adds a savings plan. */
    @Transactional
    public SavingsPlanResponse createPlan(SavingsPlanRequest request) {
        Organization organization = currentOrganization();

        String name = ConfigRules.normalizedPlanName(request.name());
        SavingsFrequency frequency = ConfigRules.requireFrequency(request.frequency());
        ConfigRules.requireOrderedAmounts(request.minAmount(), request.maxAmount(),
                "contribution");
        requirePlanNameIsFree(organization.getId(), name, null);

        boolean active = request.active() == null || request.active();

        OrganizationSavingsPlan plan = planRepository.save(OrganizationSavingsPlan.builder()
                .organization(organization)
                .name(name)
                .description(trimToNull(request.description()))
                .planAmount(request.planAmount())
                .frequency(frequency)
                .minAmount(request.minAmount())
                .maxAmount(request.maxAmount())
                .active(active)
                .build());

        ConfigDiff diff = new ConfigDiff(plan.getId());
        diff.text("name", null, plan.getName(), ignored -> { });
        diff.text("description", null, plan.getDescription(), ignored -> { });
        diff.money("planAmount", null, plan.getPlanAmount(), ignored -> { });
        diff.enumValue("frequency", null, plan.getFrequency(), ignored -> { });
        diff.money("minAmount", null, plan.getMinAmount(), ignored -> { });
        diff.money("maxAmount", null, plan.getMaxAmount(), ignored -> { });
        diff.flag("active", null, plan.getActive(), ignored -> { });

        auditWriter.record(organization, ConfigDomain.SAVINGS_PLAN, diff, request.reason());
        return SavingsPlanResponse.of(plan);
    }

    /** Replaces one savings plan's terms. */
    @Transactional
    public SavingsPlanResponse replacePlan(Long id, SavingsPlanRequest request) {
        Organization organization = currentOrganization();
        OrganizationSavingsPlan plan = requirePlan(organization.getId(), id);

        String name = ConfigRules.normalizedPlanName(request.name());
        SavingsFrequency frequency = ConfigRules.requireFrequency(request.frequency());
        ConfigRules.requireOrderedAmounts(request.minAmount(), request.maxAmount(),
                "contribution");
        requirePlanNameIsFree(organization.getId(), name, id);

        ConfigDiff diff = new ConfigDiff(plan.getId());
        diff.text("name", plan.getName(), name, plan::setName);
        diff.text("description", plan.getDescription(), request.description(),
                plan::setDescription);
        diff.money("planAmount", plan.getPlanAmount(), request.planAmount(), plan::setPlanAmount);
        diff.enumValue("frequency", plan.getFrequency(), frequency, plan::setFrequency);
        diff.money("minAmount", plan.getMinAmount(), request.minAmount(), plan::setMinAmount);
        diff.money("maxAmount", plan.getMaxAmount(), request.maxAmount(), plan::setMaxAmount);
        if (request.active() != null) {
            diff.flag("active", plan.getActive(), request.active(), plan::setActive);
        }

        auditWriter.record(organization, ConfigDomain.SAVINGS_PLAN, diff, request.reason());
        return SavingsPlanResponse.of(plan);
    }

    /** Withdraws a savings plan from offer. Idempotent; see {@code withdrawLoanType}. */
    @Transactional
    public SavingsPlanResponse withdrawPlan(Long id, String reason) {
        Organization organization = currentOrganization();
        OrganizationSavingsPlan plan = requirePlan(organization.getId(), id);

        ConfigDiff diff = new ConfigDiff(plan.getId());
        diff.flag("active", plan.getActive(), false, plan::setActive);

        auditWriter.record(organization, ConfigDomain.SAVINGS_PLAN, diff, reason);
        return SavingsPlanResponse.of(plan);
    }

    // ========================================================================== internals

    private Organization currentOrganization() {
        CurrentAuth.requireAdmin();
        return organizationService.currentOrganizationEntity();
    }

    private OrganizationSavingsPlan requirePlan(Long organizationId, Long id) {
        if (id == null) {
            throw ConfigRules.notFound();
        }
        return planRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(ConfigRules::notFound);
    }

    /**
     * Refuses a plan name another plan in this cooperative already holds.
     *
     * <p>Mirrors {@code ux_organization_savings_plan_org_name}, which is unique on
     * {@code lower(btrim(name))}. See {@code AdminLoanConfigService.requireLoanProductNameIsFree} for
     * why the check exists when the index would refuse it anyway.
     */
    private void requirePlanNameIsFree(Long organizationId, String name, Long exemptId) {
        planRepository.findByOrganizationIdAndNameIgnoreCase(organizationId, name)
                .filter(existing -> !existing.getId().equals(exemptId))
                .ifPresent(existing -> {
                    throw ConfigRules.conflict("This cooperative already offers a savings plan "
                            + "called \"" + existing.getName() + "\".");
                });
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
