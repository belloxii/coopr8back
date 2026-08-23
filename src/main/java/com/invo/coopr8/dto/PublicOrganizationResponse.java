package com.invo.coopr8.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The only organization details an <em>unauthenticated</em> caller can see: enough to brand
 * a login or signup page for one cooperative, and nothing more.
 *
 * <p>This is deliberately smaller than {@link OrganizationResponse}, which is served to
 * authenticated members of the tenant. Contact details, postal address, ledger prefix and
 * internal ids are all omitted, because {@code /api/organization/public/{slug}} is readable
 * by anyone who can guess a slug -- a page that has to be public should hand out only what
 * a page needs to look right.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PublicOrganizationResponse {

    private String name;
    private String slug;
    private String logoUrl;
    private String primaryColor;
    private String secondaryColor;
}
