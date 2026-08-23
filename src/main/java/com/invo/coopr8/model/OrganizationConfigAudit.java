package com.invo.coopr8.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.invo.coopr8.tenant.TenantFilter;

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
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A record that one cooperative's business configuration changed: who changed it, which
 * field, from what, to what, when, and why. <strong>Append-only.</strong>
 *
 * <p>A configuration change that commits without this row is an unexplained interest-rate
 * change in a system that lends people money. The audit is written in the <em>same
 * transaction</em> as the change it describes — no best-effort logging, no async queue.
 *
 * <p><strong>Append-only is enforced, not intended.</strong> Four independent layers:
 * <ol>
 *   <li>This class has no setters and every {@code @Column} is {@code updatable = false},
 *       so there is no Java path to a mutation.</li>
 *   <li>{@code OrganizationConfigAuditRepository} declares no {@code delete*} method.</li>
 *   <li>An ArchUnit rule keeps the repository out of reach of anything but the audit writer
 *       (added in Stage 2, alongside the writer).</li>
 *   <li>{@code tr_organization_config_audit_append_only} in the V8 migration refuses
 *       {@code UPDATE} and {@code DELETE} at the database. This is the only layer that
 *       survives a developer who never reads any of this.</li>
 * </ol>
 *
 * <p><strong>{@link #oldValue} and {@link #newValue} are text.</strong> This table spans six
 * domains whose values are numerics, booleans, enums and strings, and text is correct here
 * for the one reason it is usually wrong elsewhere: nothing reads these columns to make a
 * decision. It is a record <em>of</em> values, not a source of them. A null
 * {@link #oldValue} is meaningful — it is a first-ever set.
 *
 * <p><strong>No secret can reach this table.</strong> No password rule, token lifetime or
 * key is representable in COOPR8 configuration at all, so none can be recorded here. The
 * audit cannot leak what it has no way to be given.
 *
 * <p>The actor is stored as a bare {@code Long} rather than an association, because the
 * database constraint that matters is composite —
 * {@code (organization_id, actor_user_id) REFERENCES users (organization_id, id)} — which
 * makes "the admin who changed this cooperative's rate belongs to a different cooperative"
 * a row PostgreSQL refuses. Mapping the actor as a second {@code @ManyToOne} over the same
 * {@code organization_id} column would buy navigation the audit writer does not need at the
 * cost of an insertable/updatable=false trick.
 */
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@Entity
@Table(name = "organization_config_audit")
public class OrganizationConfigAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Tenant owner. An audit log readable across tenants would disclose other cooperatives'
     * business rules, so this entity joins every isolation mechanism the tenant-owned
     * entities use -- scoped repository queries first, {@link TenantFilter} as the net.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    @JsonIgnore
    private Organization organization;

    /** The administrator who made the change. Constrained to this organization by the FK. */
    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private Long actorUserId;

    /**
     * The actor's membership number, denormalized so the record survives the account being
     * renamed or the member being removed. Width matches {@code users.ledgerid} exactly: a
     * narrower column here would truncate a membership number inside an audit record.
     */
    @Column(name = "actor_ledger_id", updatable = false)
    private String actorLedgerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "config_domain", nullable = false, updatable = false, length = 64)
    private ConfigDomain configDomain;

    /** The field that changed, in its Java form, e.g. {@code interestRate}. */
    @Column(name = "setting_key", nullable = false, updatable = false, length = 128)
    private String settingKey;

    /** {@code null} on a first-ever set, which is itself information. */
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "old_value", updatable = false)
    private String oldValue;

    /** {@code null} when a bound is being cleared. */
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "new_value", updatable = false)
    private String newValue;

    /**
     * When the change begins applying to new transactions.
     *
     * <p>Configuration takes effect immediately, so this always equals the date the change
     * was recorded. It is kept as a record of when the terms began applying, and so that
     * reporting need not infer it from {@link #createdAt} if future-dated configuration is
     * ever introduced. It is <strong>not</strong> a control: no code reads it to decide which
     * configuration is live.
     */
    @Column(name = "effective_date", nullable = false, updatable = false)
    private LocalDate effectiveDate;

    /** Required for rate and price changes, optional elsewhere. */
    @Column(name = "reason", updatable = false, length = 512)
    private String reason;

    /** When the change was recorded — distinct from {@link #effectiveDate}. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
