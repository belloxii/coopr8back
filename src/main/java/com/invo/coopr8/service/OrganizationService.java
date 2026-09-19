package com.invo.coopr8.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.invo.coopr8.dto.OrganizationResponse;
import com.invo.coopr8.dto.PublicOrganizationResponse;
import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.User;
import com.invo.coopr8.repository.OrganizationRepository;
import com.invo.coopr8.security.CurrentAuth;
import com.invo.coopr8.tenant.TenantResolver;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves the tenant (organization) whose identity the current operation must use, and
 * exposes it as branding values for emails, notifications and documents.
 *
 * <p><strong>The backend is authoritative.</strong> For an authenticated request the tenant
 * comes from the verified JWT via {@link CurrentAuth} -- never from a body, query parameter
 * or header, any of which would let a caller nominate someone else's cooperative.
 *
 * <p><strong>The one input that is read is a slug, and only pre-authentication.</strong>
 * Login and self-service signup have to be able to say <em>which</em> cooperative they mean
 * before any credential exists, and the tenant's own URL ({@code /o/citadel/login}) is where
 * that comes from. A slug is a lookup key: it selects which member table to search and
 * grants nothing, since the password still has to match and a new signup still lands as
 * PENDING awaiting that tenant's own administrator.
 *
 * <p><strong>No defaults, ever.</strong> When the tenant cannot be determined the operation
 * fails. There is no "if there is only one organization use it" branch, no Citadel fallback
 * and no COOPR8 fallback -- guessing would file a member under, or expose data from, the
 * wrong cooperative.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationService {

    /** Platform (SaaS product) name. Never used as a tenant's own name. */
    public static final String PLATFORM_NAME = "COOPR8";

    /** Attribution line for tenant-facing emails and documents. */
    public static final String PLATFORM_ATTRIBUTION = "Powered by " + PLATFORM_NAME;

    /** Neutral stand-in when a tenant name is genuinely unavailable. Never "Citadel". */
    public static final String NEUTRAL_ORGANIZATION_NAME = "Your cooperative";

    private final OrganizationRepository organizationRepository;
    private final TenantResolver tenantResolver;
    private final EntitlementService entitlementService;

    /**
     * Slug of the organization that unauthenticated self-service signups join when the
     * request does not name one.
     *
     * <p>This is the single-tenant development convenience, not the architecture: a signup
     * that arrives through {@code /o/{slug}/signup} carries its own slug and ignores this
     * setting entirely. It exists so an existing deployment whose frontend has not yet moved
     * to per-tenant URLs keeps working, and it is explicit server-side configuration rather
     * than a guess, so a signup can never be silently attributed to the wrong tenant.
     */
    @Value("${coopr8.onboarding.organization-slug:}")
    private String onboardingOrganizationSlug;

    /** Public application URL, used in email links instead of a hardcoded tenant domain. */
    @Value("${frontend.url:}")
    private String frontendUrl;

    // ------------------------------------------------------------------ resolution

    /**
     * The authenticated caller's own organization, as an entity.
     *
     * <p>The id comes from the JWT and was already checked against the database (and
     * confirmed active) by {@code JwtTokenValidator} before the request reached any handler,
     * so this load cannot cross a tenant boundary.
     *
     * @throws ResponseStatusException 401 when the request is not authenticated
     */
    public Organization currentOrganizationEntity() {
        Long organizationId = CurrentAuth.requireOrganizationId();
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> {
                    // The token verified and the tenant resolved moments ago, so this means the
                    // organization was deleted mid-request. Refuse rather than continue untenanted.
                    log.error("Authenticated request carries organization id {} which no longer exists.",
                            organizationId);
                    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated.");
                });
    }

    /**
     * The organization a pre-authentication request is for: signup, login-adjacent flows, and
     * one-time codes.
     *
     * <p>Order of precedence, and the reasoning for it:
     * <ol>
     *   <li><b>The authenticated caller's own tenant.</b> An administrator onboarding a
     *       member creates that member inside their own cooperative. {@code requestedSlug} is
     *       ignored here, so an administrator cannot plant a member in another tenant by
     *       editing the request body.</li>
     *   <li><b>The slug the anonymous request names.</b> Self-service signup or password reset
     *       from {@code /o/{slug}/...}. Must resolve to exactly one active organization.</li>
     *   <li><b>The configured onboarding slug.</b> Compatibility for a frontend still posting
     *       to the tenant-less URLs.</li>
     * </ol>
     * Anything else is an error.
     */
    public Organization resolveRequestedOrganization(String requestedSlug) {
        if (CurrentAuth.principal().isPresent()) {
            return currentOrganizationEntity();
        }

        if (StringUtils.hasText(requestedSlug)) {
            return tenantResolver.activeOrganizationBySlug(requestedSlug)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "That cooperative could not be found."));
        }

        if (StringUtils.hasText(onboardingOrganizationSlug)) {
            return tenantResolver.activeOrganizationBySlug(onboardingOrganizationSlug.trim())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "The configured onboarding organization is missing or inactive."));
        }

        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This request did not say which cooperative it is for.");
    }

    /** The organization owning a domain entity, resolved through its member. */
    public Organization requireForUser(User user) {
        if (user == null || user.getOrganization() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This member is not attached to an organization.");
        }
        return user.getOrganization();
    }

    /** The authenticated caller's organization, as a safe response projection. */
    public OrganizationResponse currentOrganization() {
        return toResponse(currentOrganizationEntity());
    }

    /**
     * Branding for one cooperative's login/signup page, for callers who are by definition not
     * yet authenticated.
     *
     * <p>Returns 404 for an unknown <em>or suspended</em> organization: a suspended tenant
     * should not have a working front door, and the same response for both means the endpoint
     * cannot be used to tell which of the two a slug is.
     */
    public PublicOrganizationResponse publicBranding(String slug) {
        Organization organization = tenantResolver.activeOrganizationBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "That cooperative could not be found."));

        return PublicOrganizationResponse.builder()
                .name(organization.getName())
                .slug(organization.getSlug())
                .logoUrl(organization.getLogoUrl())
                .primaryColor(organization.getPrimaryColor())
                .secondaryColor(organization.getSecondaryColor())
                .build();
    }

    // -------------------------------------------------------------------- branding

    /** Tenant display name, or a neutral fallback. Never a hardcoded cooperative name. */
    public String displayName(Organization organization) {
        if (organization == null || !StringUtils.hasText(organization.getName())) {
            return NEUTRAL_ORGANIZATION_NAME;
        }
        return organization.getName();
    }

    /** Display name to show as the email sender, e.g. {@code ABC Cooperative <no-reply@...>}. */
    public String emailSenderName(Organization organization) {
        return displayName(organization);
    }

    /**
     * Brand label for a flow that may run before any tenant is known. Uses the organization
     * when it is resolvable and the COOPR8 platform otherwise, so a pre-authentication email
     * never names a tenant that may not be the right one.
     */
    public String displayNameOrPlatform(Organization organization) {
        if (organization == null || !StringUtils.hasText(organization.getName())) {
            return PLATFORM_NAME;
        }
        return organization.getName();
    }

    /** Sign-off matching {@link #displayNameOrPlatform(Organization)}. */
    public String signatureOrPlatform(Organization organization) {
        if (organization == null || !StringUtils.hasText(organization.getName())) {
            return "Regards,\n" + PLATFORM_NAME;
        }
        return emailSignature(organization);
    }

    /**
     * Standard tenant email sign-off: the organization signs the message, and COOPR8 is
     * credited as the platform underneath it.
     */
    public String emailSignature(Organization organization) {
        return "Regards,\n" + displayName(organization) + "\n\n" + PLATFORM_ATTRIBUTION;
    }

    /** Application URL for email links; empty when not configured. */
    public String applicationUrl() {
        return frontendUrl == null ? "" : frontendUrl.trim();
    }

    /** {@code "\n\nhttps://app.example.com"}, or empty when no URL is configured. */
    public String applicationUrlBlock() {
        String url = applicationUrl();
        return url.isEmpty() ? "" : "\n\n" + url;
    }

    /**
     * Tenant-specific sign-in link for emails, e.g. {@code https://app.example.com/o/citadel/login}.
     *
     * <p>Sending a member to their own cooperative's door rather than a generic one keeps the
     * membership number resolvable without the ledger-prefix fallback.
     */
    public String signInUrlBlock(Organization organization) {
        String url = applicationUrl();
        if (url.isEmpty() || organization == null || !StringUtils.hasText(organization.getSlug())) {
            return applicationUrlBlock();
        }
        return "\n\n" + url + "/o/" + organization.getSlug() + "/login";
    }

    // --------------------------------------------------------------------- mapping

    public OrganizationResponse toResponse(Organization organization) {
        return OrganizationResponse.builder()
                .id(organization.getId())
                .name(organization.getName())
                .legalName(organization.getLegalName())
                .slug(organization.getSlug())
                .logoUrl(organization.getLogoUrl())
                .ledgerPrefix(organization.getLedgerPrefix())
                .primaryColor(organization.getPrimaryColor())
                .secondaryColor(organization.getSecondaryColor())
                .contactEmail(organization.getEmail())
                .contactPhone(organization.getPhone())
                .website(organization.getWebsite())
                .address(organization.getAddress())
                .planCode(organization.getPlanCode())
                .aiScanningEntitled(entitlementService.isAiScanningEntitled(organization))
                .ecommerceEntitled(entitlementService.isEcommerceEntitled(organization))
                .build();
    }
}
