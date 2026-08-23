package com.invo.coopr8.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.invo.coopr8.tenant.TenantFilter;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class Saving {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String  txnId;
    private String  status;

    private BigDecimal balance;
    private BigDecimal amount;
    private LocalDateTime month;
    private String channel;

    @ManyToOne
    // @JsonIgnore
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
    @UpdateTimestamp
    private LocalDateTime modifiedAt;

}
