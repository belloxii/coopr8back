package com.invo.coopr8.security;

import java.util.Set;

/**
 * The contents of a token whose signature, issuer and expiry have been verified.
 *
 * <p>Distinct from {@link AuthPrincipal} on purpose: verifying a signature proves the claims
 * were minted by this application and not altered, but it does not prove they are still
 * <em>true</em>. Between issue and use, an organization can be suspended, a member can be
 * deactivated, a slug can be renamed. So a verified token is an input to authentication, not
 * its conclusion -- {@code JwtTokenValidator} re-checks the tenant against the database and
 * only then builds an {@link AuthPrincipal}.
 */
public record VerifiedToken(
        Long userId,
        Long organizationId,
        String ledgerID,
        Set<String> roles,
        String tokenId,
        /**
         * The {@code organizationSlug} claim as it arrived. Informational only -- kept for
         * diagnostics and never used to resolve a tenant, because {@link #organizationId()}
         * is the authoritative tenant reference and the slug is re-read from the database.
         */
        String organizationSlugClaim) {
}
