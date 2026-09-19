package com.invo.coopr8.controller;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.invo.coopr8.dto.platform.PlanPriceAuditResponse;
import com.invo.coopr8.dto.platform.PlanResponse;
import com.invo.coopr8.dto.platform.PlanUpdateRequest;
import com.invo.coopr8.model.Plan;
import com.invo.coopr8.model.PlanPriceAudit;
import com.invo.coopr8.repository.PlanPriceAuditRepository;
import com.invo.coopr8.repository.PlanRepository;
import com.invo.coopr8.security.PlatformAdminPrincipal;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Platform super-administration endpoints for commercial subscription plans.
 * Restricted to {@code ROLE_PLATFORM_ADMIN} by {@code AppConfig}.
 */
@Slf4j
@RestController
@RequestMapping("/api/platform/plans")
@RequiredArgsConstructor
public class PlatformPlanController {

    private final PlanRepository planRepository;
    private final PlanPriceAuditRepository planPriceAuditRepository;

    @GetMapping
    public List<PlanResponse> getAllPlans() {
        return planRepository.findAllByOrderBySortOrderAsc()
                .stream()
                .map(PlanResponse::from)
                .toList();
    }

    @PutMapping("/{id}")
    @Transactional
    public PlanResponse updatePlan(@PathVariable Long id, @Valid @RequestBody PlanUpdateRequest request) {
        Plan plan = planRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found"));

        boolean priceChanged = plan.getPrice().compareTo(request.getPrice()) != 0;
        boolean perUserPriceChanged = isPerUserPriceChanged(plan.getPerUserPrice(), request.getPerUserPrice());

        if (priceChanged || perUserPriceChanged) {
            Long adminId = getCurrentAdminId();
            PlanPriceAudit audit = PlanPriceAudit.builder()
                    .planId(plan.getId())
                    .oldPrice(plan.getPrice())
                    .newPrice(request.getPrice())
                    .oldPerUserPrice(plan.getPerUserPrice())
                    .newPerUserPrice(request.getPerUserPrice())
                    .changedBy(adminId)
                    .build();
            planPriceAuditRepository.save(audit);
            log.info("Plan {} ({}) price updated by platform admin id {}: old price={}, new price={}",
                    plan.getId(), plan.getCode(), adminId, plan.getPrice(), request.getPrice());
        }

        plan.setName(request.getName());
        plan.setPrice(request.getPrice());
        if (request.getPerUserPrice() != null) {
            plan.setPerUserPrice(request.getPerUserPrice());
        }
        if (request.getIncludedUsers() != null) {
            plan.setIncludedUsers(request.getIncludedUsers());
        }
        if (request.getAiScanningEnabled() != null) {
            plan.setAiScanningEnabled(request.getAiScanningEnabled());
        }
        if (request.getEcommerceEnabled() != null) {
            plan.setEcommerceEnabled(request.getEcommerceEnabled());
        }
        if (request.getActive() != null) {
            plan.setActive(request.getActive());
        }
        if (request.getSortOrder() != null) {
            plan.setSortOrder(request.getSortOrder());
        }

        Plan saved = planRepository.save(plan);
        return PlanResponse.from(saved);
    }

    @GetMapping("/{id}/audit")
    public List<PlanPriceAuditResponse> getPlanPriceAudit(@PathVariable Long id) {
        if (!planRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found");
        }
        return planPriceAuditRepository.findAllByPlanIdOrderByChangedAtDesc(id)
                .stream()
                .map(PlanPriceAuditResponse::from)
                .toList();
    }

    private boolean isPerUserPriceChanged(BigDecimal current, BigDecimal updated) {
        if (current == null && updated == null) {
            return false;
        }
        if (current == null || updated == null) {
            return true;
        }
        return current.compareTo(updated) != 0;
    }

    private Long getCurrentAdminId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof PlatformAdminPrincipal principal) {
            return principal.id();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated as platform admin");
    }
}
