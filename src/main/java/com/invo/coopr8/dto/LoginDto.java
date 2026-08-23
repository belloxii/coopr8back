package com.invo.coopr8.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Login credentials.
 *
 * <p>Authentication is a three-part question -- <em>which cooperative</em>, <em>which
 * member of it</em>, <em>what password</em> -- because a membership number is only unique
 * within one cooperative. {@code CBMC0001} may exist at several tenants.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class LoginDto {

    /**
     * Slug of the cooperative to authenticate against, e.g. {@code "citadel"} taken from
     * the {@code /o/citadel/login} URL the member visited.
     *
     * <p>This narrows the lookup; it does not grant anything. When omitted, the backend
     * falls back to deriving the tenant from the membership number's ledger prefix, which
     * succeeds only when exactly one organization claims that prefix.
     */
    private String organization;

    private String ledgerID;

    private String password;
}
