package com.invo.coopr8.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.invo.coopr8.model.Shares;

/** Share purchases/withdrawals. Tenant-owned; see {@link UserRepository}. */
public interface SharesRepository extends JpaRepository<Shares, Long> {

    Optional<Shares> findByIdAndOrganizationId(Long id, Long organizationId);

    List<Shares> findAllByOrganizationIdOrderByIdDesc(Long organizationId);

    List<Shares> findByUser_IdAndOrganizationId(Long userId, Long organizationId);
}
