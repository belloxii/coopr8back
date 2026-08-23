package com.invo.coopr8.tenant;

import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The tenant whose data the current thread is permitted to touch.
 *
 * <p><strong>Populated from the verified JWT, and nothing else.</strong>
 * {@code JwtTokenValidator} resolves the token's {@code organizationId} against the database,
 * confirms the organization is active, and binds the result. No request body, query
 * parameter, path variable or header ever reaches this holder -- any of those would let a
 * caller nominate someone else's tenant.
 *
 * <p><strong>Bindable only from a resolved tenant.</strong> {@link #bind(ActiveTenant)} takes
 * an {@link ActiveTenant}, which can only be produced by {@code TenantResolver}. So "this id
 * was checked against the database and the organization is active" is guaranteed by the type,
 * not by remembering to check.
 *
 * <p><strong>Leakage.</strong> Servlet containers reuse threads, so a value left behind by one
 * request would silently become the next request's tenant -- and the next request could belong
 * to a different cooperative. Two things prevent that: the filter clears in a
 * {@code finally}, and {@link #bind} refuses to overwrite a different tenant, so a missed
 * clear surfaces as a loud failure rather than as cross-tenant data access.
 *
 * <p><strong>Not propagated across threads.</strong> {@code @Async} work (email sending) does
 * not inherit this binding, and that is deliberate: an inherited-but-never-cleared value in a
 * pooled executor thread is exactly the leak above, with no request boundary to bound it.
 * Background work must therefore receive everything it needs as parameters. See
 * {@code AsyncConfig}.
 */
public final class TenantContext {

    private static final ThreadLocal<ActiveTenant> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
        // utility holder; not instantiable
    }

    /**
     * Binds the resolved tenant to the current thread.
     *
     * @throws IllegalStateException if a <em>different</em> tenant is already bound. That can
     *                               only mean a previous request failed to clear, or something
     *                               is attempting to switch tenants mid-request; both are bugs
     *                               whose safe outcome is a failed request rather than data
     *                               served under the wrong tenant.
     */
    public static void bind(ActiveTenant tenant) {
        Objects.requireNonNull(tenant, "tenant is required; a request with no tenant must be refused");
        ActiveTenant existing = CURRENT_TENANT.get();
        if (existing != null && !existing.id().equals(tenant.id())) {
            throw new IllegalStateException(
                    "Refusing to rebind the tenant context from organization " + existing.id()
                            + " to " + tenant.id() + " on thread '" + Thread.currentThread().getName()
                            + "'. Either a previous request did not clear the context, or something is "
                            + "switching tenants mid-request.");
        }
        CURRENT_TENANT.set(tenant);
    }

    /** @return the current thread's tenant, or {@code null} if none is bound. */
    public static ActiveTenant getTenant() {
        return CURRENT_TENANT.get();
    }

    /** @return the current thread's tenant id, or {@code null} if none is bound. */
    public static Long getOrganizationId() {
        ActiveTenant tenant = CURRENT_TENANT.get();
        return (tenant == null) ? null : tenant.id();
    }

    /** @return the current thread's tenant slug, or {@code null} if none is bound. */
    public static String getOrganizationSlug() {
        ActiveTenant tenant = CURRENT_TENANT.get();
        return (tenant == null) ? null : tenant.slug();
    }

    public static boolean isBound() {
        return CURRENT_TENANT.get() != null;
    }

    /**
     * The current tenant id, for code that cannot proceed without one.
     *
     * @throws TenantContextMissingException always, when no tenant is bound. It never returns a
     *                                       default -- not the only organization, not a
     *                                       configured one. Absence of a tenant means the
     *                                       caller has not established who they are, and the
     *                                       only safe response is to refuse.
     */
    public static Long requireOrganizationId() {
        ActiveTenant tenant = CURRENT_TENANT.get();
        if (tenant == null) {
            throw new TenantContextMissingException();
        }
        return tenant.id();
    }

    /** Removes the binding. MUST be called in a {@code finally} block by whatever bound it. */
    public static void clear() {
        CURRENT_TENANT.remove();
    }

    /**
     * Raised when an operation needs a tenant and none is bound.
     *
     * <p>Presented as {@code 401} with a generic message: telling an unauthenticated caller
     * anything more specific about why tenant resolution failed only helps them probe.
     */
    public static class TenantContextMissingException extends ResponseStatusException {

        public TenantContextMissingException() {
            super(HttpStatus.UNAUTHORIZED, "Not authenticated.");
        }
    }
}
