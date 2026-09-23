package com.invo.coopr8.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.invo.coopr8.tenant.TenantFilter;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Version;
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
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Detects concurrent repayments and loan decisions. */
    @Version
    private Long version;

    private BigDecimal balance;
    private BigDecimal amount;
    private BigDecimal repayAmount;
    private String status;
    private String remark;
    private String purpose;
    private String type;

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

    @ManyToOne
    private User guarantor1;

    @ManyToOne
    private User guarantor2;

    @Builder.Default
    private String guarantor1Status = "PENDING";
    @Builder.Default
    private String guarantor2Status = "PENDING";

    private String accountDetails;
    private LocalDate startDate;
    private LocalDate EndDate;
    private Integer duration;
    private Integer installmentsPaid;

    @Builder.Default
    // private List<Repay> repays = new ArrayList<>();
    @OneToMany(mappedBy = "loan", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference   // Controls serialization
    private List<Repay> repays = new ArrayList<>();
    
    @CreationTimestamp
    private LocalDateTime createdAt;
    @UpdateTimestamp
    private LocalDateTime modifiedAt;
    
}
