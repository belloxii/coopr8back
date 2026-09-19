package com.invo.coopr8.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.invo.coopr8.dto.platform.PlanResponse;
import com.invo.coopr8.repository.PlanRepository;

import lombok.RequiredArgsConstructor;

/**
 * Public catalog endpoint for subscription plans.
 *
 * <p>Returns active commercial plans (excluding LEGACY) ordered by {@code sortOrder ASC}.
 * Reachable without authentication per {@code AppConfig}.
 */
@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
public class PlanController {

    private final PlanRepository planRepository;

    @GetMapping
    public List<PlanResponse> getPublicPlans() {
        return planRepository.findAllByActiveTrueOrderBySortOrderAsc()
                .stream()
                .filter(plan -> !"LEGACY".equalsIgnoreCase(plan.getCode()))
                .map(PlanResponse::from)
                .toList();
    }
}
