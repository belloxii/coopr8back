package com.invo.coopr8.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.invo.coopr8.tenant.TenantFilter;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Table(name = "users")
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String firstName;
    private String middleName;
    private String lastName;
    private String address;
    private String psn;

    // How the member funds savings/repayments: GOVERNMENT (salary deduction) or SELF_PAY (online/transfer)
    // Persisted as STRING (converted from the legacy integer ordinal by Flyway V7). May be null (no default).
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentType paymentType;

    private String occupation;
    private String verNo;
    private String station;
    private String marital;
    private String gender;
    private String phone;
    private String homeTown;
    private String lga;
    private String state;

    private BigDecimal savingsBalance;
    private BigDecimal loanBalance;
    private BigDecimal sharesBalance;
    private BigDecimal totalPurchase;
    private BigDecimal savingPlan;
    private BigDecimal specialSavingPlan;
    private BigDecimal sharePlan;
    private BigDecimal profit;

    private String email;
    private String passport;

    /**
     * BCrypt hash of the member's password.
     *
     * <p>{@code @JsonIgnore} on both the field and {@link #getPassword()} keeps the hash
     * out of every JSON representation of a {@code User} -- the profile response, the
     * admin member list, signup responses, and the nested {@code user} inside a loan,
     * saving, share or notification. Before this, any endpoint that returned a
     * {@code User} handed the caller its own (and other members') password hashes.
     *
     * <p>It is ignored for <em>deserialization</em> too, which is deliberate: no endpoint
     * accepts a {@code User} entity as a request body any more. Passwords are set through
     * {@code PasswordDto} / the reset flow, never by binding a request onto the entity.
     */
    @JsonIgnore
    private String password;
    private String ledgerID;
    private int ledgerNumber;
    private String status;

    // Persisted as STRING (converted from the legacy integer ordinal by Flyway V7).
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Role role;

    private String nextOfKin;
    private String nextOfKinRelationship;
    private String nextOfKinAddress;
    private String nextOfKinPhone;

    @CreationTimestamp
    private LocalDateTime createdAt;
    @UpdateTimestamp
    private LocalDateTime modifiedAt;

    // Tenant this member belongs to (COOPR8 multi-tenancy) -- the canonical tenant
    // reference for User, NOT NULL with an FK in the V1 schema. Set server-side at
    // account creation from the authenticated actor's organization, never from request
    // input, and never assignable through a profile or admin update. @JsonIgnore keeps
    // existing API responses byte-identical; branding is exposed separately via
    // OrganizationResponse. Isolation is enforced by scoped repository queries against
    // this column -- see UserRepository -- with the class-level @Filter as a secondary
    // net (see TenantFilter for what that does and does not cover).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    @JsonIgnore
    private Organization organization;

    @JsonIgnore
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
    @Builder.Default
    private List<Loan> loans = new ArrayList<>();  // Default initialization

    @JsonIgnore
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
    @Builder.Default
    private List<Saving> savings = new ArrayList<>();  // Default initialization

    @JsonIgnore
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
    @Builder.Default
    private List<Repay> repays = new ArrayList<>();  // Default initialization
    
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.name()));
    }

    @Override
    @JsonIgnore
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return ledgerID;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
