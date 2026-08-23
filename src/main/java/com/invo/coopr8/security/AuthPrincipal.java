package com.invo.coopr8.security;

import java.security.Principal;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.Role;
import com.invo.coopr8.model.User;

/**
 * Who is making the current request, and which tenant they belong to.
 *
 * <p>This is the {@code principal} of the Spring {@code Authentication} for every
 * authenticated COOPR8 request, built by {@code JwtTokenValidator} from the verified token
 * and nothing else. Its {@link #organizationId()} is the single authoritative answer to
 * "which tenant is this?" -- request bodies, query parameters and headers are never
 * consulted, because any of those would let a caller name someone else's tenant.
 *
 * <p><strong>Why the subject is the user id.</strong> The JWT {@code sub} is the numeric
 * user id, not the ledger ID. Ledger IDs are only unique <em>within</em> an organization
 * ({@code UNIQUE (organization_id, ledgerid)}), so a ledger-ID subject would not identify a
 * user on a multi-tenant platform: two cooperatives can both have an {@code ABC0001}. The
 * ledger ID is still carried, because it is what members recognise and what audit trails
 * and support conversations use -- but it identifies a member only alongside the
 * organization.
 *
 * <p><strong>Slug provenance.</strong> {@link #organizationSlug()} is always the value read
 * back from the database for {@link #organizationId()} at request time, never the slug claim
 * as it arrived. A renamed or tampered slug therefore cannot influence anything.
 */
public record AuthPrincipal(
        Long userId,
        Long organizationId,
        String organizationSlug,
        String ledgerID,
        Set<String> roles,
        String tokenId) implements Principal {

    public AuthPrincipal {
        Objects.requireNonNull(userId, "userId is required: an authenticated request must identify a user");
        Objects.requireNonNull(organizationId,
                "organizationId is required: a request with no tenant must be rejected, never defaulted");
        roles = (roles == null) ? Set.of() : Set.copyOf(new LinkedHashSet<>(roles));
    }

    /**
     * Builds the principal for a user who has just authenticated.
     *
     * @param user         the authenticated member, already confirmed to belong to
     *                     {@code organization}
     * @param organization the member's tenant
     * @param tokenId      the {@code jti} minted for this session
     */
    public static AuthPrincipal of(User user, Organization organization, String tokenId) {
        Role role = user.getRole();
        return new AuthPrincipal(
                user.getId(),
                organization.getId(),
                organization.getSlug(),
                user.getLedgerID(),
                (role == null) ? Set.of() : Set.of(role.name()),
                tokenId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The user id, so {@code Authentication.getName()} agrees with the token subject.
     * Code that needs the member-facing identifier must ask for {@link #ledgerID()}
     * explicitly rather than relying on {@code getName()}.
     */
    @Override
    public String getName() {
        return String.valueOf(userId);
    }

    public Collection<? extends GrantedAuthority> authorities() {
        return roles.stream().map(SimpleGrantedAuthority::new).map(GrantedAuthority.class::cast).toList();
    }

    public boolean hasRole(Role role) {
        return role != null && roles.contains(role.name());
    }

    public boolean isAdmin() {
        return hasRole(Role.ROLE_ADMIN);
    }

    /** Roles as a stable, ordered list -- the form written into the {@code roles} claim. */
    public List<String> roleList() {
        return List.copyOf(roles);
    }
}
