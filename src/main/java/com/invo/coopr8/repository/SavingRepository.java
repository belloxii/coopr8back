package com.invo.coopr8.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.invo.coopr8.model.Saving;

/** Savings postings. Tenant-owned; see {@link UserRepository} for why nothing here is global. */
public interface SavingRepository extends JpaRepository<Saving, Long> {

    Optional<Saving> findByIdAndOrganizationId(Long id, Long organizationId);

    List<Saving> findAllByOrganizationIdOrderByIdDesc(Long organizationId);

    List<Saving> findByUser_IdAndOrganizationId(Long userId, Long organizationId);

    Optional<Saving> findTopByUser_IdAndOrganizationIdOrderByIdDesc(Long userId, Long organizationId);
}
