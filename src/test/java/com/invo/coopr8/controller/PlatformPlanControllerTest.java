package com.invo.coopr8.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.invo.coopr8.dto.platform.PlanPriceAuditResponse;
import com.invo.coopr8.dto.platform.PlanResponse;
import com.invo.coopr8.dto.platform.PlanUpdateRequest;
import com.invo.coopr8.model.Plan;
import com.invo.coopr8.model.PlanPriceAudit;
import com.invo.coopr8.repository.PlanPriceAuditRepository;
import com.invo.coopr8.repository.PlanRepository;
import com.invo.coopr8.security.PlatformAdminPrincipal;

class PlatformPlanControllerTest {

    private PlanRepository planRepository;
    private PlanPriceAuditRepository planPriceAuditRepository;
    private PlatformPlanController controller;

    @BeforeEach
    void setUp() {
        planRepository = mock(PlanRepository.class);
        planPriceAuditRepository = mock(PlanPriceAuditRepository.class);
        controller = new PlatformPlanController(planRepository, planPriceAuditRepository);

        PlatformAdminPrincipal admin = new PlatformAdminPrincipal(42L, "platform-admin@coopr8.com", "tok-123");
        var auth = new UsernamePasswordAuthenticationToken(
                admin, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getAllPlansReturnsAllPlansOrdered() {
        Plan starter = Plan.builder().id(1L).code("STARTER").name("Starter").price(new BigDecimal("150000.00")).sortOrder(1).active(true).build();
        Plan pro = Plan.builder().id(2L).code("PRO").name("Pro").price(new BigDecimal("350000.00")).sortOrder(2).active(true).build();

        when(planRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(starter, pro));

        List<PlanResponse> result = controller.getAllPlans();
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getCode()).isEqualTo("STARTER");
        assertThat(result.get(1).getCode()).isEqualTo("PRO");
    }

    @Test
    void updatePlanWithPriceChangeWritesAuditRow() {
        Plan existing = Plan.builder()
                .id(2L)
                .code("PRO")
                .name("Pro Plan")
                .price(new BigDecimal("350000.00"))
                .perUserPrice(new BigDecimal("120.00"))
                .sortOrder(2)
                .active(true)
                .build();

        when(planRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(planRepository.save(any(Plan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PlanUpdateRequest request = PlanUpdateRequest.builder()
                .name("Pro Plan (Updated)")
                .price(new BigDecimal("400000.00"))
                .perUserPrice(new BigDecimal("150.00"))
                .sortOrder(2)
                .active(true)
                .build();

        PlanResponse updated = controller.updatePlan(2L, request);

        assertThat(updated.getPrice()).isEqualByComparingTo("400000.00");

        ArgumentCaptor<PlanPriceAudit> auditCaptor = ArgumentCaptor.forClass(PlanPriceAudit.class);
        verify(planPriceAuditRepository).save(auditCaptor.capture());

        PlanPriceAudit audit = auditCaptor.getValue();
        assertThat(audit.getPlanId()).isEqualTo(2L);
        assertThat(audit.getOldPrice()).isEqualByComparingTo("350000.00");
        assertThat(audit.getNewPrice()).isEqualByComparingTo("400000.00");
        assertThat(audit.getOldPerUserPrice()).isEqualByComparingTo("120.00");
        assertThat(audit.getNewPerUserPrice()).isEqualByComparingTo("150.00");
        assertThat(audit.getChangedBy()).isEqualTo(42L);
    }

    @Test
    void getPlanPriceAuditReturnsHistory() {
        when(planRepository.existsById(2L)).thenReturn(true);
        PlanPriceAudit audit1 = PlanPriceAudit.builder()
                .id(100L)
                .planId(2L)
                .oldPrice(new BigDecimal("300000.00"))
                .newPrice(new BigDecimal("350000.00"))
                .changedBy(1L)
                .build();

        when(planPriceAuditRepository.findAllByPlanIdOrderByChangedAtDesc(2L)).thenReturn(List.of(audit1));

        List<PlanPriceAuditResponse> auditList = controller.getPlanPriceAudit(2L);
        assertThat(auditList).hasSize(1);
        assertThat(auditList.get(0).getNewPrice()).isEqualByComparingTo("350000.00");
    }
}
