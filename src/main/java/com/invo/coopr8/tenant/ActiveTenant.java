package com.invo.coopr8.tenant;

/**
 * A tenant that exists and is currently allowed to serve requests.
 *
 * <p>Obtaining one is the only way to bind {@link TenantContext}, so "the tenant is active"
 * is established once, at resolution, instead of being re-checked (or forgotten) by each
 * caller. A suspended organization simply never produces one of these.
 */
public record ActiveTenant(Long id, String slug) {
}
