package com.invo.coopr8.tenant;

import java.util.concurrent.atomic.AtomicBoolean;

import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * The standard JPA transaction manager, plus one thing: as each transaction begins, if a tenant
 * is bound to the thread, the Hibernate tenant filter is switched on for that transaction's
 * session with the bound organization's id.
 *
 * <p><strong>Why here and not an aspect around the repositories.</strong> An around-advice on
 * {@code com.invo.coopr8.repository} runs <em>outside</em> Spring Data's own transaction
 * interceptor, so at that moment there is no transactional {@code EntityManager} to unwrap --
 * the shared proxy either throws or hands back a throwaway session that the repository call
 * will not use, and the filter is enabled on nothing. Transaction begin is the first point at
 * which the session that will actually run the query exists.
 *
 * <p><strong>Why only when a tenant is bound.</strong> Several flows legitimately run with no
 * tenant: login and signup (the tenant is what they are still working out), password reset, and
 * the Paystack webhook (an unauthenticated call from Paystack, which resolves its cooperative
 * from the verified transaction's metadata). Enabling the filter there would append
 * {@code organization_id = null} and quietly match nothing -- login would fail for everyone.
 * Those paths are protected by scoped repository queries, which is the primary mechanism
 * everywhere; this is the secondary net. Hence also {@code autoEnabled = false} on the
 * {@code @FilterDef}: Hibernate must never enable it on its own.
 *
 * <p><strong>A failure here does not fail the request.</strong> If the filter cannot be enabled
 * the transaction proceeds without it: isolation does not depend on it (see
 * {@link TenantFilter}), and taking the platform down to protect a defence-in-depth layer would
 * trade a real outage for a hypothetical leak. It is logged at ERROR the first time so the
 * misconfiguration is visible rather than silent.
 *
 * @see TenantFilter
 */
@Slf4j
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    /**
     * Mirrors {@code coopr8.tenant.hibernate-filter.enabled}. When false this class behaves
     * exactly like {@link JpaTransactionManager}, which is what
     * {@code TenantFilterDisabledIsolationTest} runs against to prove isolation survives
     * without the filter.
     */
    private final transient boolean filterEnabled;

    /** So a persistent misconfiguration is reported once at ERROR, not once per transaction. */
    private final transient AtomicBoolean reportedFailure = new AtomicBoolean();

    public TenantAwareJpaTransactionManager(boolean filterEnabled) {
        this.filterEnabled = filterEnabled;
    }

    public boolean isTenantFilterEnabled() {
        return filterEnabled;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);

        if (!filterEnabled) {
            return;
        }

        Long organizationId = TenantContext.getOrganizationId();
        if (organizationId == null) {
            return;
        }

        try {
            Session session = currentSession();
            if (session != null) {
                session.enableFilter(TenantFilter.NAME)
                        .setParameter(TenantFilter.PARAMETER, organizationId);
            }
        } catch (RuntimeException e) {
            // Deliberately swallowed -- see the class comment. The organization id is safe to
            // log (it is not a secret and it identifies which tenant was affected); nothing
            // else from the transaction is.
            String message = "Could not enable the Hibernate tenant filter for organizationId={}."
                    + " The transaction continues WITHOUT the secondary filter; tenant isolation"
                    + " still rests on the scoped repository queries.";
            if (reportedFailure.compareAndSet(false, true)) {
                log.error(message, organizationId, e);
            } else {
                log.debug(message, organizationId, e);
            }
        }
    }

    /**
     * The Hibernate session backing the transaction {@code super.doBegin} has just started.
     *
     * <p>Read from the thread-bound resource rather than from the transaction object, because
     * {@code JpaTransactionObject} is private to {@link JpaTransactionManager}. By the end of
     * {@code doBegin} the holder is bound in both cases that matter: a session created for this
     * transaction, and one already opened for the request by {@code OpenEntityManagerInView}
     * and joined by it.
     */
    private Session currentSession() {
        EntityManagerFactory factory = getEntityManagerFactory();
        if (factory == null) {
            return null;
        }
        Object resource = TransactionSynchronizationManager.getResource(factory);
        if (!(resource instanceof EntityManagerHolder holder)) {
            return null;
        }
        // A real EntityManager, not Spring's shared proxy, so unwrap is straightforward.
        return holder.getEntityManager().unwrap(Session.class);
    }
}
