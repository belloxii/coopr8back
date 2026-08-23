package com.invo.coopr8.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.invo.coopr8.model.Notification;

/** In-app notifications. Tenant-owned; see {@link UserRepository}. */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByIdAndOrganizationId(Long id, Long organizationId);

    List<Notification> findAllByOrganizationIdOrderByTimestampDesc(Long organizationId);

    List<Notification> findByRecipientIdAndOrganizationIdOrderByTimestampDesc(
            Long recipientId, Long organizationId);

    List<Notification> findByRecipientIdAndOrganizationIdAndIsReadFalseOrderByTimestampDesc(
            Long recipientId, Long organizationId);

    /**
     * Marks every unread notification for one recipient as read, in one statement.
     *
     * <p>The organization id is part of the {@code WHERE} clause rather than checked
     * beforehand, because a bulk update is exactly where a missing scope would be silent:
     * without it, a recipient id belonging to another tenant would still be matched.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Notification n SET n.isRead = true "
            + "WHERE n.recipient.id = :recipientId AND n.organization.id = :organizationId "
            + "AND n.isRead = false")
    int markAllReadForRecipient(@Param("recipientId") Long recipientId,
            @Param("organizationId") Long organizationId);

    @Modifying
    @Transactional
    @Query("DELETE FROM Notification n "
            + "WHERE n.recipient.id = :recipientId AND n.organization.id = :organizationId")
    int deleteAllForRecipient(@Param("recipientId") Long recipientId,
            @Param("organizationId") Long organizationId);
}
