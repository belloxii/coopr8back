package com.invo.coopr8.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.invo.coopr8.model.Notification;
import com.invo.coopr8.repository.NotificationRepository;
import com.invo.coopr8.security.AuthPrincipal;
import com.invo.coopr8.security.CurrentAuth;

import lombok.AllArgsConstructor;

/**
 * A member's own notification inbox.
 *
 * <p>Every route here is scoped to the caller's own recipient id <em>and</em> their cooperative,
 * both taken from the verified token. The previous {@code mark-read/{id}} and {@code DELETE /{id}}
 * routes took an id and did nothing else with it: any authenticated member could mark another
 * member's notification as read, or delete it outright, anywhere on the platform.
 */
@RestController
@RequestMapping("/api/notis")
@AllArgsConstructor
public class NotisController {

    private final NotificationRepository notificationRepository;

    @GetMapping("/mynotis")
    public ResponseEntity<List<Notification>> getUserNotifications() {
        AuthPrincipal principal = CurrentAuth.require();
        return ResponseEntity.ok(notificationRepository
                .findByRecipientIdAndOrganizationIdOrderByTimestampDesc(
                        principal.userId(), principal.organizationId()));
    }

    @GetMapping("/user/unread")
    public ResponseEntity<List<Notification>> getUnreadNotifications() {
        AuthPrincipal principal = CurrentAuth.require();
        return ResponseEntity.ok(notificationRepository
                .findByRecipientIdAndOrganizationIdAndIsReadFalseOrderByTimestampDesc(
                        principal.userId(), principal.organizationId()));
    }

    /** Marks one of the caller's own notifications as read. */
    @PutMapping("/mark-read/{id}")
    public ResponseEntity<?> markAsRead(@PathVariable Long id) {
        Notification notification = requireOwn(id);
        notification.setRead(true);
        notificationRepository.save(notification);
        return ResponseEntity.ok("Notification marked as read");
    }

    @PutMapping("/mark-all-read")
    public ResponseEntity<?> markAllAsRead() {
        AuthPrincipal principal = CurrentAuth.require();
        notificationRepository.markAllReadForRecipient(
                principal.userId(), principal.organizationId());
        return ResponseEntity.ok("All notifications marked as read");
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteNotification(@PathVariable Long id) {
        notificationRepository.delete(requireOwn(id));
        return ResponseEntity.ok("Notification deleted");
    }

    @DeleteMapping("/user/delete")
    public ResponseEntity<?> deleteAllUserNotifications() {
        AuthPrincipal principal = CurrentAuth.require();
        notificationRepository.deleteAllForRecipient(
                principal.userId(), principal.organizationId());
        return ResponseEntity.ok("All user notifications deleted");
    }

    /**
     * Loads a notification that belongs to the caller, or reports it as absent.
     *
     * <p>Both the wrong-tenant and the wrong-recipient case answer {@code 404}: a member should
     * not be able to use this route to discover that notification 812 exists.
     */
    private Notification requireOwn(Long id) {
        AuthPrincipal principal = CurrentAuth.require();

        Notification notification = notificationRepository
                .findByIdAndOrganizationId(id, principal.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Notification not found."));

        if (notification.getRecipient() == null
                || !principal.userId().equals(notification.getRecipient().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found.");
        }
        return notification;
    }
}
