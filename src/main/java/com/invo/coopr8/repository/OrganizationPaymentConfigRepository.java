package com.invo.coopr8.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.invo.coopr8.model.OrganizationPaymentConfig;

/**
 * One cooperative's payment configuration, addressed only within that cooperative.
 *
 * <p>There is deliberately no finder by provider account reference. "Which cooperative owns
 * {@code ACCT_xxxxxxxx}?" is not a question this application needs to ask, and a method that could
 * answer it is a method that could be called with another tenant's account reference. The database
 * enforces that no two cooperatives share one -- {@code uk_organization_payment_config_provider_account}
 * -- so uniqueness needs no query to defend it.
 */
@Repository
public interface OrganizationPaymentConfigRepository
        extends JpaRepository<OrganizationPaymentConfig, Long> {

    /** This cooperative's payment configuration, or empty when it has never configured one. */
    Optional<OrganizationPaymentConfig> findByOrganizationId(Long organizationId);
}
