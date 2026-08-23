package com.invo.coopr8.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.invo.coopr8.model.ConfigDomain;
import com.invo.coopr8.model.OrganizationConfigAudit;

/**
 * The record of one cooperative's configuration changes. Tenant-owned; see
 * {@link UserRepository} for why nothing here is global — an audit log readable across tenants
 * would disclose other cooperatives' business rules and the dates they changed them.
 *
 * <p><strong>Reads and inserts only.</strong> No mutating method is declared here, and
 * {@code TenantIsolationArchitectureTest.theConfigurationAuditIsAppendOnly} fails the build if
 * any class calls an inherited {@code delete*} on this interface — which is what makes the
 * absence a guarantee rather than a convention. The database refuses {@code UPDATE} and
 * {@code DELETE} independently, via {@code tr_organization_config_audit_append_only}.
 *
 * <p>Ordering is by {@code id} descending rather than by {@code created_at}: two changes made
 * in one transaction share a timestamp to the microsecond, and identity order is the only
 * unambiguous answer to "which came second".
 */
public interface OrganizationConfigAuditRepository extends JpaRepository<OrganizationConfigAudit, Long> {

    Optional<OrganizationConfigAudit> findByIdAndOrganizationId(Long id, Long organizationId);

    /** This cooperative's whole configuration history, newest first. */
    List<OrganizationConfigAudit> findAllByOrganizationIdOrderByIdDesc(Long organizationId);

    /** The read this table exists for: one domain's history, newest first. */
    List<OrganizationConfigAudit> findAllByOrganizationIdAndConfigDomainOrderByIdDesc(
            Long organizationId, ConfigDomain configDomain);
}
