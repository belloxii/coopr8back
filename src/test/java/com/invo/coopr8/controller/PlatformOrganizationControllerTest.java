package com.invo.coopr8.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.invo.coopr8.dto.EmailDetails;
import com.invo.coopr8.dto.platform.OrganizationActivationRequest;
import com.invo.coopr8.dto.platform.OrganizationChangePlanRequest;
import com.invo.coopr8.dto.platform.OrganizationOverridesRequest;
import com.invo.coopr8.dto.platform.PlatformOrganizationResponse;
import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.OrganizationStatus;
import com.invo.coopr8.model.Plan;
import com.invo.coopr8.model.Role;
import com.invo.coopr8.model.User;
import com.invo.coopr8.repository.OrganizationRepository;
import com.invo.coopr8.repository.PlanRepository;
import com.invo.coopr8.repository.UserRepository;
import com.invo.coopr8.service.EmailService;
import com.invo.coopr8.service.EntitlementService;
import com.invo.coopr8.service.OrganizationService;

class PlatformOrganizationControllerTest {

    private OrganizationRepository organizationRepository;
    private PlanRepository planRepository;
    private UserRepository userRepository;
    private EntitlementService entitlementService;
    private EmailService emailService;
    private OrganizationService organizationService;
    private PlatformOrganizationController controller;

    static class FakeOrganizationService extends OrganizationService {
        FakeOrganizationService() {
            super(null, null, null);
        }

        @Override
        public String applicationUrl() {
            return "https://app.coopr8.com";
        }
    }

    @BeforeEach
    void setUp() {
        organizationRepository = mock(OrganizationRepository.class);
        planRepository = mock(PlanRepository.class);
        userRepository = mock(UserRepository.class);
        entitlementService = mock(EntitlementService.class);
        emailService = mock(EmailService.class);
        organizationService = new FakeOrganizationService();

        controller = new PlatformOrganizationController(
                organizationRepository, planRepository, userRepository,
                entitlementService, emailService, organizationService);
    }

    @Test
    void activateOrganizationSetsActiveStatusAndSendsEmail() {
        Organization org = Organization.builder()
                .id(10L)
                .name("Alpha Coop")
                .slug("alpha")
                .email("info@alphacoop.com")
                .status(OrganizationStatus.PENDING_ACTIVATION)
                .planCode("PRO")
                .build();

        Plan plan = Plan.builder()
                .code("PRO")
                .name("Pro")
                .price(new BigDecimal("350000.00"))
                .perUserPrice(new BigDecimal("120.00"))
                .includedUsers(25)
                .billingPeriod("ANNUAL")
                .build();

        User adminUser = User.builder()
                .id(1L)
                .email("admin@alphacoop.com")
                .role(Role.ROLE_ADMIN)
                .build();

        when(organizationRepository.findById(10L)).thenReturn(Optional.of(org));
        when(planRepository.findByCodeIgnoreCase("PRO")).thenReturn(Optional.of(plan));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(i -> i.getArgument(0));
        when(userRepository.findFirstByOrganizationIdAndRoleOrderByIdAsc(10L, Role.ROLE_ADMIN)).thenReturn(Optional.of(adminUser));

        PlatformOrganizationResponse response = controller.activateOrganization(10L, null);

        assertThat(response.getStatus()).isEqualTo(OrganizationStatus.ACTIVE);
        assertThat(response.getSubscriptionStartsAt()).isNotNull();
        assertThat(response.getSubscriptionEndsAt()).isNotNull();
        assertThat(response.getAgreedPrice()).isEqualByComparingTo("350000.00");

        ArgumentCaptor<EmailDetails> emailCaptor = ArgumentCaptor.forClass(EmailDetails.class);
        verify(emailService).sendEmail(emailCaptor.capture());

        EmailDetails sentEmail = emailCaptor.getValue();
        assertThat(sentEmail.getRecipient()).isEqualTo("admin@alphacoop.com");
        assertThat(sentEmail.getMessage()).contains("https://app.coopr8.com/o/alpha/login");
    }

    @Test
    void suspendAndReactivateOrganization() {
        Organization org = Organization.builder()
                .id(10L)
                .name("Alpha Coop")
                .slug("alpha")
                .status(OrganizationStatus.ACTIVE)
                .build();

        when(organizationRepository.findById(10L)).thenReturn(Optional.of(org));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(i -> i.getArgument(0));

        PlatformOrganizationResponse suspended = controller.suspendOrganization(10L);
        assertThat(suspended.getStatus()).isEqualTo(OrganizationStatus.SUSPENDED);

        PlatformOrganizationResponse reactivated = controller.reactivateOrganization(10L);
        assertThat(reactivated.getStatus()).isEqualTo(OrganizationStatus.ACTIVE);
    }

    @Test
    void changePlanUpdatesPlanCodeAndSnapshots() {
        Organization org = Organization.builder()
                .id(10L)
                .name("Alpha Coop")
                .slug("alpha")
                .planCode("STARTER")
                .agreedPrice(new BigDecimal("150000.00"))
                .build();

        Plan enterprisePlan = Plan.builder()
                .code("ENTERPRISE")
                .name("Enterprise")
                .price(new BigDecimal("800000.00"))
                .perUserPrice(new BigDecimal("200.00"))
                .includedUsers(100)
                .billingPeriod("ANNUAL")
                .build();

        when(organizationRepository.findById(10L)).thenReturn(Optional.of(org));
        when(planRepository.findByCodeIgnoreCase("ENTERPRISE")).thenReturn(Optional.of(enterprisePlan));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(i -> i.getArgument(0));

        OrganizationChangePlanRequest request = OrganizationChangePlanRequest.builder()
                .planCode("ENTERPRISE")
                .build();

        PlatformOrganizationResponse response = controller.changePlan(10L, request);
        assertThat(response.getPlanCode()).isEqualTo("ENTERPRISE");
        assertThat(response.getAgreedPrice()).isEqualByComparingTo("800000.00");
        assertThat(response.getAgreedPerUserPrice()).isEqualByComparingTo("200.00");
        assertThat(response.getIncludedUsers()).isEqualTo(100);
    }

    @Test
    void updateOverridesPersistsFlags() {
        Organization org = Organization.builder()
                .id(10L)
                .name("Alpha Coop")
                .slug("alpha")
                .build();

        when(organizationRepository.findById(10L)).thenReturn(Optional.of(org));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(i -> i.getArgument(0));

        OrganizationOverridesRequest request = OrganizationOverridesRequest.builder()
                .aiScanningOverride(true)
                .ecommerceOverride(false)
                .build();

        PlatformOrganizationResponse response = controller.updateOverrides(10L, request);
        assertThat(response.getAiScanningOverride()).isTrue();
        assertThat(response.getEcommerceOverride()).isFalse();
    }

    @Test
    void legacyOrganizationWithoutEndDateIsComplimentary() {
        Organization org = Organization.builder()
                .id(10L)
                .name("Legacy Coop")
                .slug("legacy")
                .status(OrganizationStatus.ACTIVE)
                .planCode("LEGACY")
                .subscriptionStartsAt(LocalDateTime.now().minusYears(1))
                .subscriptionEndsAt(null)
                .build();

        when(organizationRepository.findAll()).thenReturn(List.of(org));

        List<PlatformOrganizationResponse> list = controller.getAllOrganizations(false);
        assertThat(list).hasSize(1);
        PlatformOrganizationResponse resp = list.get(0);
        assertThat(resp.isComplimentary()).isTrue();
        assertThat(resp.getDaysRemaining()).isNull();
    }
}
