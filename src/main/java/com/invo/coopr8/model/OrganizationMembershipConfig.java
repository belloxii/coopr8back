package com.invo.coopr8.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.invo.coopr8.tenant.TenantFilter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One cooperative's onboarding rules. <strong>Exactly one row per organization</strong>,
 * enforced by {@code uk_organization_membership_config_organization} in the V7 migration.
 *
 * <p>Which fields a signup must supply is currently fixed in bean validation on
 * {@code UserRequest} — {@code @NotBlank} on first name, last name, gender, email, phone and
 * address, nothing on PSN or passport — and a self-service signup always lands as
 * {@code PENDING} awaiting admin activation. Different cooperatives collect different things.
 *
 * <p><strong>No security control is configurable here, and that is the most important thing
 * about this entity.</strong> There is no password regex, no password minimum length, no
 * token lifetime, and no one-time-code length or expiry. A tenant-editable password rule is a
 * tenant-editable security control: an organization admin who sets the regex to {@code .*}
 * has disabled password strength for every member of that cooperative, from a settings
 * screen, with no platform review. Those are platform constants, owned by the platform, and
 * {@code OrganizationConfigClassCTest} asserts that no field matching those names exists on
 * any configuration entity — so a future well-meaning addition fails the build.
 *
 * <p>The fields below that look security-adjacent are not. The {@code require*} flags are
 * data-completeness rules. {@link #autoActivateMembers} decides whether a new member waits
 * for a human, which is a membership policy every cooperative genuinely owns; it cannot grant
 * a role, because role assignment is fixed in {@code UserServiceImpl} and {@code UserRequest}
 * has no role field to carry one.
 */
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "organization_membership_config")
public class OrganizationMembershipConfig {

    /** The only member statuses {@code UserServiceImpl.refuseByStatus} understands as new. */
    public static final String STATUS_NEW = "NEW";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Tenant owner. Isolation is enforced by scoped repository queries against this column;
     * the {@code @Filter} on the class is a secondary net -- see {@link TenantFilter}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    @JsonIgnore
    private Organization organization;

    /** True is today's behaviour ({@code @NotBlank} on {@code UserRequest.email}). */
    @Column(name = "require_email", nullable = false)
    private Boolean requireEmail;

    /** True is today's behaviour ({@code @NotBlank} on {@code UserRequest.phone}). */
    @Column(name = "require_phone", nullable = false)
    private Boolean requirePhone;

    /** False is today's behaviour: nothing validates PSN. */
    @Column(name = "require_psn", nullable = false)
    private Boolean requirePsn;

    /** False is today's behaviour: nothing validates the passport photograph. */
    @Column(name = "require_passport", nullable = false)
    private Boolean requirePassport;

    /** False is today's behaviour: nothing validates next-of-kin details. */
    @Column(name = "require_next_of_kin", nullable = false)
    private Boolean requireNextOfKin;

    /**
     * Whether a new member is usable immediately or waits for an administrator. False is
     * today's behaviour.
     *
     * <p>Must agree with {@link #defaultMemberStatus}: the V7 CHECK requires this to be true
     * exactly when the default status is {@code ACTIVE}. Auto-activation that lands a member
     * in {@code PENDING} would activate nobody, and a default of {@code ACTIVE} without
     * auto-activation would bypass the approval step the cooperative said it wanted.
     */
    @Column(name = "auto_activate_members", nullable = false)
    private Boolean autoActivateMembers;

    /**
     * The status a newly created member starts in — one of {@code NEW}, {@code PENDING} or
     * {@code ACTIVE}. {@code PENDING} is today's behaviour.
     *
     * <p>Constrained because a free-form status would create members whose accounts no code
     * path can ever unlock. Kept as a {@code String} rather than an enum to match
     * {@code User.status}, which is a {@code String} column this value is copied into.
     */
    @Column(name = "default_member_status", nullable = false, length = 32)
    private String defaultMemberStatus;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
