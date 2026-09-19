package com.invo.coopr8.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Platform super-administration endpoints for tenant organizations.
 * Restricted to {@code ROLE_PLATFORM_ADMIN} by {@code AppConfig}.
 */
@Slf4j
@RestController
@RequestMapping("/api/platform/organizations")
@RequiredArgsConstructor
public class PlatformOrganizationController {

    private final OrganizationRepository organizationRepository;
    private final PlanRepository planRepository;
    private final UserRepository userRepository;
    private final EntitlementService entitlementService;
    private final EmailService emailService;
    private final OrganizationService organizationService;

    @GetMapping
    public List<PlatformOrganizationResponse> getAllOrganizations(
            @RequestParam(name = "expiringIn30Days", defaultValue = "false") boolean expiringIn30Days) {

        List<Organization> organizations = organizationRepository.findAll();
        LocalDate today = LocalDate.now();

        return organizations.stream()
                .map(this::toPlatformResponse)
                .filter(resp -> {
                    if (!expiringIn30Days) {
                        return true;
                    }
                    return resp.getStatus() == OrganizationStatus.ACTIVE
                            && resp.getDaysRemaining() != null
                            && resp.getDaysRemaining() >= 0
                            && resp.getDaysRemaining() <= 30;
                })
                .toList();
    }

    @PostMapping("/{id}/activate")
    @Transactional
    public PlatformOrganizationResponse activateOrganization(
            @PathVariable Long id,
            @RequestBody(required = false) OrganizationActivationRequest request) {

        Organization org = organizationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found"));

        org.setStatus(OrganizationStatus.ACTIVE);
        LocalDateTime now = LocalDateTime.now();
        org.setSubscriptionStartsAt(now);

        if (request != null && request.getSubscriptionEndsAt() != null) {
            org.setSubscriptionEndsAt(request.getSubscriptionEndsAt());
        } else {
            org.setSubscriptionEndsAt(now.plusYears(1));
        }

        if (request != null && request.getAgreedPrice() != null) {
            org.setAgreedPrice(request.getAgreedPrice());
        }

        if (org.getPlanCode() != null) {
            planRepository.findByCodeIgnoreCase(org.getPlanCode()).ifPresent(plan -> {
                if (org.getAgreedPrice() == null) {
                    org.setAgreedPrice(plan.getPrice());
                }
                if (org.getPerUserPriceSnapshot() == null) {
                    org.setPerUserPriceSnapshot(plan.getPerUserPrice());
                }
                if (org.getIncludedUsersSnapshot() == null) {
                    org.setIncludedUsersSnapshot(plan.getIncludedUsers());
                }
                if (org.getBillingPeriodSnapshot() == null) {
                    org.setBillingPeriodSnapshot(plan.getBillingPeriod());
                }
            });
        }

        Organization saved = organizationRepository.save(org);
        log.info("Organization {} ({}) activated by platform admin with subscription end {}",
                saved.getId(), saved.getSlug(), saved.getSubscriptionEndsAt());

        sendActivationEmail(saved);

        return toPlatformResponse(saved);
    }

    @PostMapping("/{id}/suspend")
    @Transactional
    public PlatformOrganizationResponse suspendOrganization(@PathVariable Long id) {
        Organization org = organizationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found"));

        org.setStatus(OrganizationStatus.SUSPENDED);
        Organization saved = organizationRepository.save(org);
        log.info("Organization {} ({}) suspended by platform admin", saved.getId(), saved.getSlug());

        return toPlatformResponse(saved);
    }

    @PostMapping("/{id}/reactivate")
    @Transactional
    public PlatformOrganizationResponse reactivateOrganization(@PathVariable Long id) {
        Organization org = organizationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found"));

        org.setStatus(OrganizationStatus.ACTIVE);
        Organization saved = organizationRepository.save(org);
        log.info("Organization {} ({}) reactivated by platform admin", saved.getId(), saved.getSlug());

        return toPlatformResponse(saved);
    }

    @PostMapping("/{id}/change-plan")
    @Transactional
    public PlatformOrganizationResponse changePlan(
            @PathVariable Long id,
            @Valid @RequestBody OrganizationChangePlanRequest request) {

        Organization org = organizationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found"));

        Plan newPlan = planRepository.findByCodeIgnoreCase(request.getPlanCode())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Plan not found"));

        org.setPlanCode(newPlan.getCode());
        org.setAgreedPrice(request.getAgreedPrice() != null ? request.getAgreedPrice() : newPlan.getPrice());
        org.setBillingPeriodSnapshot(newPlan.getBillingPeriod());
        org.setPerUserPriceSnapshot(newPlan.getPerUserPrice());
        org.setIncludedUsersSnapshot(newPlan.getIncludedUsers());

        if (request.getSubscriptionEndsAt() != null) {
            org.setSubscriptionEndsAt(request.getSubscriptionEndsAt());
        }

        Organization saved = organizationRepository.save(org);
        log.info("Organization {} ({}) plan changed to {} by platform admin",
                saved.getId(), saved.getSlug(), saved.getPlanCode());

        return toPlatformResponse(saved);
    }

    @PutMapping("/{id}/overrides")
    @Transactional
    public PlatformOrganizationResponse updateOverrides(
            @PathVariable Long id,
            @RequestBody OrganizationOverridesRequest request) {

        Organization org = organizationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found"));

        org.setAiScanningOverride(request.getAiScanningOverride());
        org.setEcommerceOverride(request.getEcommerceOverride());

        Organization saved = organizationRepository.save(org);
        log.info("Organization {} ({}) overrides updated: ai={}, ecommerce={}",
                saved.getId(), saved.getSlug(), saved.getAiScanningOverride(), saved.getEcommerceOverride());

        return toPlatformResponse(saved);
    }

    private PlatformOrganizationResponse toPlatformResponse(Organization org) {
        Long daysRemaining = null;
        boolean complimentary = false;

        if (org.getSubscriptionEndsAt() == null) {
            complimentary = true;
        } else {
            LocalDate today = LocalDate.now();
            LocalDate endDate = org.getSubscriptionEndsAt().toLocalDate();
            daysRemaining = ChronoUnit.DAYS.between(today, endDate);
        }

        String planName = org.getPlanCode();
        if (org.getPlanCode() != null) {
            planName = planRepository.findByCodeIgnoreCase(org.getPlanCode())
                    .map(Plan::getName)
                    .orElse(org.getPlanCode());
        }

        long activeMemberCount = userRepository.countByOrganizationIdAndStatusIgnoreCase(org.getId(), "ACTIVE");

        return PlatformOrganizationResponse.builder()
                .id(org.getId())
                .name(org.getName())
                .legalName(org.getLegalName())
                .slug(org.getSlug())
                .email(org.getEmail())
                .phone(org.getPhone())
                .ledgerPrefix(org.getLedgerPrefix())
                .status(org.getStatus())
                .planCode(org.getPlanCode())
                .planName(planName)
                .agreedPrice(org.getAgreedPrice())
                .agreedPerUserPrice(org.getPerUserPriceSnapshot())
                .includedUsers(org.getIncludedUsersSnapshot())
                .subscriptionStartsAt(org.getSubscriptionStartsAt())
                .subscriptionEndsAt(org.getSubscriptionEndsAt())
                .daysRemaining(daysRemaining)
                .complimentary(complimentary)
                .aiScanningOverride(org.getAiScanningOverride())
                .ecommerceOverride(org.getEcommerceOverride())
                .effectiveAiScanning(entitlementService.isAiScanningEntitled(org))
                .effectiveEcommerce(entitlementService.isEcommerceEntitled(org))
                .activeMemberCount(activeMemberCount)
                .createdAt(org.getCreatedAt())
                .build();
    }

    private void sendActivationEmail(Organization org) {
        try {
            Optional<User> adminUser = userRepository.findFirstByOrganizationIdAndRoleOrderByIdAsc(org.getId(), Role.ROLE_ADMIN);
            String recipient = adminUser.map(User::getEmail).filter(StringUtils::hasText).orElse(org.getEmail());

            if (!StringUtils.hasText(recipient)) {
                log.warn("Cannot send activation email for organization {} ({}): no recipient email found",
                        org.getId(), org.getSlug());
                return;
            }

            String baseUrl = organizationService.applicationUrl();
            String loginUrl = (StringUtils.hasText(baseUrl) ? baseUrl : "") + "/o/" + org.getSlug() + "/login";

            String message = "Hello,\n\n"
                    + "Your cooperative account for " + org.getName() + " on the COOPR8 platform has been activated.\n\n"
                    + "You can now sign in at:\n"
                    + loginUrl + "\n\n"
                    + "Regards,\nCOOPR8 Platform";

            emailService.sendEmail(EmailDetails.builder()
                    .recipient(recipient)
                    .subject("Your " + org.getName() + " account is active")
                    .message(message)
                    .senderName("COOPR8 Platform")
                    .build());
            log.info("Activation email sent to {} for organization {}", recipient, org.getSlug());
        } catch (Exception e) {
            log.error("Failed to send activation email for organization {}: {}", org.getSlug(), e.getMessage());
        }
    }
}
