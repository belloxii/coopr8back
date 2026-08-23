package com.invo.coopr8.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.invo.coopr8.tenant.TenantFilter;

import java.time.LocalDateTime;

@Entity
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "recipient_id")
    private User recipient;

    @ManyToOne
    @JoinColumn(name = "sender_id")
    private User sender;

    // Tenant owner (denormalized for direct, index-friendly tenant scoping).
    // NOT NULL with an FK in the V1 schema; set from the recipient's organization at
    // creation. Isolation is enforced by scoped repository queries against this column;
    // the @Filter on the class is a secondary net -- see TenantFilter.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    @JsonIgnore
    private Organization organization;

    @Enumerated(EnumType.STRING)
    private NotificationType type;

    private Long referenceId;

    private String message;

    @Builder.Default
    private boolean isRead = false;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}
