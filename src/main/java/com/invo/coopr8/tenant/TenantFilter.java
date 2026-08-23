package com.invo.coopr8.tenant;

/**
 * Names and SQL of the Hibernate filter that restricts every tenant-owned table to the
 * organization bound in {@link TenantContext}.
 *
 * <p><strong>This is a safety net, not the isolation mechanism.</strong> Tenant isolation is
 * enforced primarily by explicitly scoped repository queries -- {@code findByIdAndOrganizationId},
 * {@code findAllByOrganizationId}, and so on -- and that must stay true: the filter can be
 * switched off with {@code coopr8.tenant.hibernate-filter.enabled=false} and nothing leaks.
 * {@code TenantFilterDisabledIsolationTest} exists to prove it. What the filter buys is the
 * case nobody thought of: a query added later that forgets the organization predicate comes
 * back empty rather than cross-tenant.
 *
 * <p><strong>What it does not cover.</strong> Hibernate does not apply filters to a load by
 * primary key ({@code EntityManager.find}, lazy to-one resolution), only to queries and
 * collection loads. So {@code findById(...)} on a tenant-owned repository is <em>not</em>
 * protected by this and never was -- which is why the architecture test forbids it and every
 * repository offers the {@code ...AndOrganizationId} form instead.
 *
 * <p><strong>The filter is never auto-enabled.</strong> {@code @FilterDef(autoEnabled = ...)}
 * stays at its default of {@code false} and the filter is turned on per transaction, and only
 * when a tenant is actually bound (see {@link TenantAwareJpaTransactionManager}). Auto-enabling
 * would inject {@code organization_id = null} into every pre-authentication query -- login,
 * signup, password reset, the payment webhook -- and each of those would silently match no
 * rows. Locking every member out of the platform is not a defence.
 */
public final class TenantFilter {

    /** Hibernate filter name, referenced from {@code @FilterDef} and {@code @Filter}. */
    public static final String NAME = "coopr8TenantFilter";

    /** Filter parameter carrying the bound organization's id. */
    public static final String PARAMETER = "tenantOrganizationId";

    /**
     * The predicate Hibernate appends. Every tenant-owned table names its discriminator column
     * {@code organization_id}, so one condition serves all of them. Built by concatenating
     * {@link #PARAMETER} so the placeholder and the declared parameter cannot drift apart --
     * still a compile-time constant, so it is usable in an annotation.
     */
    public static final String CONDITION = "organization_id = :" + PARAMETER;

    private TenantFilter() {
    }
}
