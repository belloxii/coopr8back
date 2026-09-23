package com.invo.coopr8.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.OrganizationStatus;

/**
 * Organizations are platform-global rather than tenant-owned, so -- unlike every other
 * repository here -- its finders are legitimately unscoped. It is the one place a lookup
 * that crosses tenants is correct, because it is how a tenant gets identified in the first
 * place.
 *
 * <p>All resolution nonetheless goes through {@code TenantResolver} rather than being called
 * directly from request handling, so that "is this organization active?" and "is this
 * identifier unambiguous?" are answered in exactly one place.
 */
public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    /**
     * The few fields request authentication needs about a tenant, so verifying a token does
     * not have to load and initialise a whole entity on every call.
     */
    interface TenantView {
        Long getId();

        String getSlug();

        OrganizationStatus getStatus();
    }

    Optional<TenantView> findTenantViewById(Long id);

    Optional<Organization> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /**
     * Returns a list rather than an {@code Optional} deliberately. The unique index on
     * {@code slug} is case-sensitive, so before V2 normalises it two rows could differ only
     * in case; a single-result query would then throw an opaque data-access error at
     * authentication time. Callers require exactly one match and fail closed otherwise.
     */
    List<Organization> findAllBySlugIgnoreCase(String slug);

    /** Safe public discovery only lists active organizations. */
    List<Organization> findAllByStatusOrderByNameAsc(OrganizationStatus status);
}
