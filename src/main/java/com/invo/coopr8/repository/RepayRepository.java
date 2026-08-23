package com.invo.coopr8.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.invo.coopr8.model.Repay;

/** Loan repayments. Tenant-owned; see {@link UserRepository} for why nothing here is global. */
public interface RepayRepository extends JpaRepository<Repay, Long> {

    Optional<Repay> findByIdAndOrganizationId(Long id, Long organizationId);

    List<Repay> findAllByOrganizationIdOrderByIdDesc(Long organizationId);

    List<Repay> findByUser_IdAndOrganizationId(Long userId, Long organizationId);
}
