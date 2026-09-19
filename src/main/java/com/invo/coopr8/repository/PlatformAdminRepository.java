package com.invo.coopr8.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.invo.coopr8.model.PlatformAdmin;

/**
 * Global repository for platform super-administrators.
 * Platform-global; see {@link TenantIsolationArchitectureTest} for exclusion.
 */
public interface PlatformAdminRepository extends JpaRepository<PlatformAdmin, Long> {

    Optional<PlatformAdmin> findByEmailIgnoreCase(String email);
}

