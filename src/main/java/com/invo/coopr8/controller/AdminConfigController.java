package com.invo.coopr8.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.invo.coopr8.configuration.AdminLoanConfigService;
import com.invo.coopr8.configuration.AdminPolicyConfigService;
import com.invo.coopr8.configuration.AdminSavingsPlanService;
import com.invo.coopr8.configuration.ConfigAuditService;
import com.invo.coopr8.dto.CoopResponse;
import com.invo.coopr8.dto.config.ConfigAuditResponse;
import com.invo.coopr8.dto.config.LoanConfigRequest;
import com.invo.coopr8.dto.config.LoanConfigResponse;
import com.invo.coopr8.dto.config.LoanTypeExclusionRequest;
import com.invo.coopr8.dto.config.LoanTypeExclusionResponse;
import com.invo.coopr8.dto.config.LoanTypeRequest;
import com.invo.coopr8.dto.config.LoanTypeResponse;
import com.invo.coopr8.dto.config.MembershipConfigRequest;
import com.invo.coopr8.dto.config.MembershipConfigResponse;
import com.invo.coopr8.dto.config.RepaymentConfigRequest;
import com.invo.coopr8.dto.config.RepaymentConfigResponse;
import com.invo.coopr8.dto.config.SavingsPlanRequest;
import com.invo.coopr8.dto.config.SavingsPlanResponse;
import com.invo.coopr8.dto.config.SharesConfigRequest;
import com.invo.coopr8.dto.config.SharesConfigResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * A cooperative administrator's settings screens.
 *
 * <h2>Authorisation</h2>
 * The whole tree is {@code hasRole('ADMIN')} by {@code AppConfig}'s
 * {@code /api/admin/**} rule -- these endpoints add no security of their own to the URL pattern, and
 * the services re-check with {@link com.invo.coopr8.security.CurrentAuth#requireAdmin()} so that a
 * later change to a path cannot silently open one. A member's token reaching any method here is a
 * {@code 403} from the filter chain before the controller is entered.
 *
 * <h2>The tenant is never in the request</h2>
 * No path variable, query parameter or request body on any method below names an organization. The
 * cooperative is the one in the caller's verified token, every time. That is what
 * {@code ConfigurationRequestSurfaceTest} enforces on the DTOs, and it is why an administrator of
 * one cooperative sending another's loan product id gets {@code 404}: the id is looked up scoped to
 * their own tenant and simply is not there.
 *
 * <h2>Nothing here changes what the platform does yet</h2>
 * Stage 2 of {@code docs/phase4-tenant-business-configuration.md}: "an admin can view and edit rules
 * that nothing consults". A cooperative can configure an interest rate today and every loan will
 * still be computed the way it was yesterday, until Stage 4 wires each rule in with its own tests.
 * A settings screen that quietly took effect the moment it was saved would have changed the terms of
 * live lending on the strength of an untested read.
 *
 * <h2>Why {@code 200} on create rather than {@code 201}</h2>
 * Consistency with every other POST in COOPR8, all of which answer {@code 200} with the created
 * object. A configuration screen reads the body it gets back; a lone {@code 201} on two endpoints
 * out of two dozen is a difference the frontend would have to special-case for nothing.
 */
@RestController
@RequestMapping("/api/admin/config")
@RequiredArgsConstructor
public class AdminConfigController {

    private final AdminLoanConfigService loanConfigService;
    private final AdminSavingsPlanService savingsPlanService;
    private final AdminPolicyConfigService policyConfigService;
    private final ConfigAuditService auditService;

    // ================================================================== loan configuration

    @GetMapping("/loan")
    public LoanConfigResponse loanConfig() {
        return loanConfigService.loanConfig();
    }

    @PutMapping("/loan")
    public LoanConfigResponse replaceLoanConfig(@Valid @RequestBody LoanConfigRequest request) {
        return loanConfigService.replaceLoanConfig(request);
    }

    // ======================================================================= loan products

    @GetMapping("/loan-types")
    public List<LoanTypeResponse> loanTypes() {
        return loanConfigService.loanTypes();
    }

    @GetMapping("/loan-types/{id}")
    public LoanTypeResponse loanType(@PathVariable Long id) {
        return loanConfigService.loanType(id);
    }

    @PostMapping("/loan-types")
    public LoanTypeResponse createLoanType(@Valid @RequestBody LoanTypeRequest request) {
        return loanConfigService.createLoanType(request);
    }

    @PutMapping("/loan-types/{id}")
    public LoanTypeResponse replaceLoanType(@PathVariable Long id,
            @Valid @RequestBody LoanTypeRequest request) {
        return loanConfigService.replaceLoanType(id, request);
    }

    /**
     * Withdraws a loan product from offer.
     *
     * <p>{@code DELETE} because that is what the screen's button means, and a withdrawal rather than
     * a deletion because loans and exclusion rules refer to the product -- see
     * {@link AdminLoanConfigService}. The response is the product in its withdrawn state, so the
     * caller can see {@code active: false} rather than infer it.
     */
    @DeleteMapping("/loan-types/{id}")
    public LoanTypeResponse withdrawLoanType(@PathVariable Long id,
            @RequestParam(required = false) String reason) {
        return loanConfigService.withdrawLoanType(id, reason);
    }

    // ==================================================================== exclusion rules

    @GetMapping("/loan-type-exclusions")
    public List<LoanTypeExclusionResponse> exclusions() {
        return loanConfigService.exclusions();
    }

    @PostMapping("/loan-type-exclusions")
    public LoanTypeExclusionResponse createExclusion(
            @Valid @RequestBody LoanTypeExclusionRequest request) {
        return loanConfigService.createExclusion(request);
    }

    /** Removes an exclusion rule. Genuinely deleted -- see {@link AdminLoanConfigService}. */
    @DeleteMapping("/loan-type-exclusions/{id}")
    public ResponseEntity<CoopResponse> deleteExclusion(@PathVariable Long id,
            @RequestParam(required = false) String reason) {
        loanConfigService.deleteExclusion(id, reason);
        return ResponseEntity.status(HttpStatus.OK).body(CoopResponse.builder()
                .responseCode("100")
                .responseMessage("These loan products are no longer mutually exclusive.")
                .build());
    }

    // ======================================================================= savings plans

    @GetMapping("/savings-plans")
    public List<SavingsPlanResponse> savingsPlans() {
        return savingsPlanService.plans();
    }

    @GetMapping("/savings-plans/{id}")
    public SavingsPlanResponse savingsPlan(@PathVariable Long id) {
        return savingsPlanService.plan(id);
    }

    @PostMapping("/savings-plans")
    public SavingsPlanResponse createSavingsPlan(@Valid @RequestBody SavingsPlanRequest request) {
        return savingsPlanService.createPlan(request);
    }

    @PutMapping("/savings-plans/{id}")
    public SavingsPlanResponse replaceSavingsPlan(@PathVariable Long id,
            @Valid @RequestBody SavingsPlanRequest request) {
        return savingsPlanService.replacePlan(id, request);
    }

    @DeleteMapping("/savings-plans/{id}")
    public SavingsPlanResponse withdrawSavingsPlan(@PathVariable Long id,
            @RequestParam(required = false) String reason) {
        return savingsPlanService.withdrawPlan(id, reason);
    }

    // ================================================================================ shares

    @GetMapping("/shares")
    public SharesConfigResponse sharesConfig() {
        return policyConfigService.sharesConfig();
    }

    @PutMapping("/shares")
    public SharesConfigResponse replaceSharesConfig(
            @Valid @RequestBody SharesConfigRequest request) {
        return policyConfigService.replaceSharesConfig(request);
    }

    // ============================================================================= repayment

    @GetMapping("/repayment")
    public RepaymentConfigResponse repaymentConfig() {
        return policyConfigService.repaymentConfig();
    }

    @PutMapping("/repayment")
    public RepaymentConfigResponse replaceRepaymentConfig(
            @Valid @RequestBody RepaymentConfigRequest request) {
        return policyConfigService.replaceRepaymentConfig(request);
    }

    // ============================================================================ membership

    @GetMapping("/membership")
    public MembershipConfigResponse membershipConfig() {
        return policyConfigService.membershipConfig();
    }

    @PutMapping("/membership")
    public MembershipConfigResponse replaceMembershipConfig(
            @Valid @RequestBody MembershipConfigRequest request) {
        return policyConfigService.replaceMembershipConfig(request);
    }

    // ================================================================================= audit

    /**
     * This cooperative's configuration history, newest first.
     *
     * @param domain optional {@link com.invo.coopr8.model.ConfigDomain} name to narrow to one area
     */
    @GetMapping("/audit")
    public List<ConfigAuditResponse> audit(@RequestParam(required = false) String domain) {
        return auditService.history(domain);
    }
}
