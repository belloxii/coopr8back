package com.invo.coopr8.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.invo.coopr8.dto.OrganizationResponse;
import com.invo.coopr8.dto.PublicOrganizationResponse;
import com.invo.coopr8.service.OrganizationService;

import lombok.AllArgsConstructor;

/**
 * Read-only branding/identity of the caller's own organization, for the tenant UI
 * (sidebar name and logo, page title, document headers, theme colours).
 *
 * <p>There is intentionally no {@code /api/organization/{id}} and no way to name an
 * organization in the request: the tenant is derived from the authenticated principal
 * server-side, so a client cannot ask for another cooperative's identity by changing an
 * id. Only the safe {@link OrganizationResponse} projection is returned -- no
 * provider-internal or cross-tenant data.
 */
@RestController
@RequestMapping("/api/organization")
@AllArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;

    /** Branding for the organization the authenticated member belongs to. */
    @GetMapping("/current")
    public OrganizationResponse getCurrentOrganization() {
        return organizationService.currentOrganization();
    }

    /**
     * Branding for one cooperative's own login/signup page, by slug.
     *
     * <p>This is the only unauthenticated organization endpoint, and it exists because a
     * member arriving at {@code /o/citadel/login} has to see whose login page they are on
     * before they have any credential to present. It returns the deliberately minimal
     * {@link PublicOrganizationResponse} -- name, slug, logo and theme colours -- so a caller
     * enumerating slugs learns nothing beyond what a login page already displays. Unknown and
     * suspended organizations both return 404.
     */
    @GetMapping("/public/{slug}")
    public PublicOrganizationResponse getPublicBranding(@PathVariable String slug) {
        return organizationService.publicBranding(slug);
    }
}
