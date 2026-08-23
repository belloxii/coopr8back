package com.invo.coopr8.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The safe, member-facing branding/identity configuration of ONE organization --
 * the tenant the authenticated caller belongs to.
 *
 * <p>This is the only shape in which organization data leaves the backend. It is
 * deliberately a projection, not the {@code Organization} entity, so that adding
 * provider-internal columns later (subscription state, billing, platform flags)
 * cannot leak to tenants by accident.
 *
 * <p>The caller never says which organization this is: the server resolves it from
 * the authenticated principal. See {@code OrganizationService}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizationResponse {

    private Long id;

    /** Display name used across the tenant UI, emails and documents. */
    private String name;

    /** Registered/legal name, for formal statements and receipts. May be null. */
    private String legalName;

    private String slug;

    /** Tenant logo. Null means "no logo" -- the client falls back to COOPR8 branding. */
    private String logoUrl;

    /** Prefix of this tenant's member ledger IDs (e.g. "ABC" -> ABC0001). */
    private String ledgerPrefix;

    private String primaryColor;
    private String secondaryColor;

    private String contactEmail;
    private String contactPhone;
    private String website;
    private String address;
}
