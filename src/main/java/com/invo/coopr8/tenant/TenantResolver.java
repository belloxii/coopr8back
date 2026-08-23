package com.invo.coopr8.tenant;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.OrganizationStatus;
import com.invo.coopr8.repository.OrganizationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The one place an organization is turned into a tenant the request may act as.
 *
 * <p>Every method here fails closed: an unknown, suspended or <em>ambiguous</em> identifier
 * yields {@link Optional#empty()}, never a guess. In particular there is no
 * "if there is only one organization, use it" branch and no environment-specific default --
 * not Citadel, not COOPR8, not the onboarding slug. A request that cannot name its tenant
 * is refused, because the alternative is serving one cooperative's data to another.
 *
 * <p>Ambiguity is treated as failure rather than resolved by picking the first match. Two
 * organizations sharing a ledger prefix (or a slug differing only in case) means tenant
 * discovery has no answer, and silently choosing one would send a member to the wrong
 * cooperative's login.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantResolver {

    private final OrganizationRepository organizationRepository;

    /**
     * Confirms that the tenant named by a verified token still exists and is active, and
     * returns the slug as currently stored.
     *
     * <p>Called on every authenticated request. A token remains cryptographically valid until
     * it expires, so this is what makes suspending an organization take effect immediately
     * instead of hours later: the signature still verifies, but no tenant resolves, and the
     * request is treated as unauthenticated.
     */
    public Optional<ActiveTenant> activeTenantById(Long organizationId) {
        if (organizationId == null) {
            return Optional.empty();
        }
        return organizationRepository.findTenantViewById(organizationId)
                .filter(view -> view.getStatus() == OrganizationStatus.ACTIVE)
                .map(view -> new ActiveTenant(view.getId(), view.getSlug()));
    }

    /**
     * Resolves the organization a login or signup request names, by slug -- normally taken
     * from the tenant's own frontend URL (e.g. {@code /o/citadel/login}).
     *
     * <p>The slug arrives from an unauthenticated caller, so it is treated purely as a
     * <em>lookup key</em>: it selects which tenant's user table to search, and confers no
     * access by itself. Knowing a slug is not knowing a password.
     */
    public Optional<Organization> activeOrganizationBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return exactlyOneActive(
                organizationRepository.findAllBySlugIgnoreCase(slug.trim()),
                "slug '" + slug.trim() + "'");
    }

    /**
     * Resolves an organization from the prefix of a membership number, e.g. {@code CBMC0001}
     * to the organization whose ledger prefix is {@code CBMC}.
     *
     * <p><strong>Why this exists.</strong> Members issued ledger IDs before COOPR8 was
     * multi-tenant type only that number to sign in, and those IDs must keep working. This is
     * the compatibility path, and it is deliberately the weakest one: it resolves only when
     * the prefix matches exactly one organization, so it stops working -- rather than
     * becoming a cross-tenant guess -- the moment two cooperatives could share a prefix.
     * V2's globally unique {@code ledger_prefix} constraint is what keeps that guarantee.
     *
     * <p>It never performs a global user lookup, and callers must never let it override an
     * organization the request supplied explicitly.
     */
    public Optional<Organization> activeOrganizationByLedgerPrefix(String ledgerPrefix) {
        if (ledgerPrefix == null || ledgerPrefix.isBlank()) {
            return Optional.empty();
        }
        return exactlyOneActive(
                organizationRepository.findAllByLedgerPrefixIgnoreCase(ledgerPrefix.trim()),
                "ledger prefix '" + ledgerPrefix.trim() + "'");
    }

    private Optional<Organization> exactlyOneActive(List<Organization> matches, String describedBy) {
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() > 1) {
            // Ambiguity is a data problem, and it is worth being loud about: it means tenant
            // discovery has silently stopped working for these members. The organization
            // names are safe to log (they are not member data) and are what an operator
            // needs to fix it.
            log.error("Tenant discovery is ambiguous for {}: {} organizations match ({}). "
                            + "Refusing to resolve a tenant. Ledger prefixes and slugs must be unique.",
                    describedBy, matches.size(), matches.stream().map(Organization::getSlug).toList());
            return Optional.empty();
        }
        Organization organization = matches.get(0);
        if (organization.getStatus() != OrganizationStatus.ACTIVE) {
            log.info("Refusing tenant resolution for {}: organization is {}.",
                    describedBy, organization.getStatus());
            return Optional.empty();
        }
        return Optional.of(organization);
    }
}
