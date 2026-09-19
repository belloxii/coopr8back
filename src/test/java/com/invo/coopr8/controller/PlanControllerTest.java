package com.invo.coopr8.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.invo.coopr8.dto.platform.PlanResponse;
import com.invo.coopr8.model.Plan;
import com.invo.coopr8.repository.PlanRepository;

class PlanControllerTest {

    private PlanRepository planRepository;
    private PlanController controller;

    @BeforeEach
    void setUp() {
        planRepository = mock(PlanRepository.class);
        controller = new PlanController(planRepository);
    }

    @Test
    void getPublicPlansReturnsActivePlansExcludingLegacy() {
        Plan starter = Plan.builder().id(1L).code("STARTER").name("Starter").price(new BigDecimal("150000.00")).sortOrder(1).active(true).build();
        Plan pro = Plan.builder().id(2L).code("PRO").name("Pro").price(new BigDecimal("350000.00")).sortOrder(2).active(true).build();
        Plan legacy = Plan.builder().id(3L).code("LEGACY").name("Legacy Plan").price(BigDecimal.ZERO).sortOrder(99).active(true).build();

        when(planRepository.findAllByActiveTrueOrderBySortOrderAsc()).thenReturn(List.of(starter, pro, legacy));

        List<PlanResponse> publicPlans = controller.getPublicPlans();

        assertThat(publicPlans).hasSize(2);
        assertThat(publicPlans).extracting(PlanResponse::getCode).containsExactly("STARTER", "PRO");
    }
}
