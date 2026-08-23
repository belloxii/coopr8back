package com.invo.coopr8.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.invo.coopr8.tenant.TenantFilter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Shares {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String txnId;         
    private String type;         
    private String status;   
    private String remark;   
    private String accountDetails;
    private String channel;

    private BigDecimal amount;
    private BigDecimal balance;

    @ManyToOne
    // @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    // Tenant owner (denormalized from user for direct, index-friendly tenant scoping).
    // NOT NULL with an FK in the V1 schema; set from the owning member's organization
    // at creation. Isolation is enforced by scoped repository queries against this
    // column; the @Filter on the class is a secondary net -- see TenantFilter.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    @JsonIgnore
    private Organization organization;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
