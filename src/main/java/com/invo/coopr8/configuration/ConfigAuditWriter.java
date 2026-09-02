package com.invo.coopr8.configuration;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.invo.coopr8.model.ConfigDomain;
import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.OrganizationConfigAudit;
import com.invo.coopr8.repository.OrganizationConfigAuditRepository;
import com.invo.coopr8.security.AuthPrincipal;
import com.invo.coopr8.security.CurrentAuth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The one class in COOPR8 that writes a configuration audit record.
 *
 * <h2>Why "the one class" is enforced rather than intended</h2>
 * {@code TenantIsolationArchitectureTest.theConfigurationAuditIsAppendOnly} fails the build if any
 * other class calls a mutating method on {@link OrganizationConfigAuditRepository}. Four layers
 * already make the table append-only -- no setters on the entity, every column
 * {@code updatable = false}, a repository that declares no {@code delete*}, and the database trigger
 * {@code tr_organization_config_audit_append_only} -- and this is the fifth: a single writer means
 * the rules below (an actor is required, a rate change needs a reason, nothing is recorded that did
 * not change) cannot be bypassed by a second code path that writes rows its own way.
 *
 * <h2>Same transaction, no exceptions</h2>
 * This method takes no transaction of its own. It runs inside the caller's, which is the caller's
 * obligation and the point: a configuration change and the record of it commit together or not at
 * all. There is no best-effort logging and no asynchronous queue, because a financial rule that
 * changed without a trace is indistinguishable from one that was never changed -- and if the audit
 * write fails, the right outcome is that the change fails too.
 *
 * <p>The reason check runs <em>before</em> any row is written, and throws. Since the caller has
 * already applied the change to a managed entity by then, the throw rolls the change back with it.
 * {@code AdminConfigurationEndpointTest} asserts exactly that: a rate change submitted without a
 * reason leaves the stored rate untouched.
 *
 * <h2>The actor</h2>
 * Read from the verified token, never from the request. {@code actor_user_id} is
 * {@code NOT NULL} and carries a composite foreign key to
 * {@code users (organization_id, id)}, so the database itself refuses a record attributing a change
 * to somebody outside the cooperative whose configuration changed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigAuditWriter {

    /**
     * Settings that may not be changed without a stated reason.
     *
     * <p>Rates and prices, per {@code docs/phase4-tenant-business-configuration.md} §7: "reason is
     * required for rate and price changes, optional elsewhere". These three are what that sentence
     * denotes in this schema -- the loan interest rate (cooperative-wide or per product), the price
     * of a share, and the contribution a savings plan charges. Every one of them changes what a
     * member pays.
     *
     * <p>Matched on {@link ConfigChange#field()}, so {@code 7.interestRate} counts as much as
     * {@code interestRate}.
     *
     * <p>Only a change to a value that already had one is caught -- see
     * {@link #requireReasonWhereMandatory}.
     */
    static final Set<String> REASON_REQUIRED_FIELDS = Set.of(
            "interestRate", "sharePrice", "planAmount");

    /**
     * The width of {@code organization_config_audit.reason}.
     *
     * <p>Checked here rather than only by {@code @Size} on the request DTOs, because a reason also
     * arrives as a query parameter on the two {@code DELETE} endpoints, where no bean validation runs.
     * Without this, a long reason would reach PostgreSQL as a value-too-long error -- a 500 on a
     * change the administrator described too thoroughly.
     */
    static final int MAX_REASON_LENGTH = 512;

    private final OrganizationConfigAuditRepository auditRepository;

    /**
     * Records everything a submission changed, one row per setting.
     *
     * <p>An empty diff writes nothing and is not an error: an administrator may open a form and save
     * it unchanged, and a trail of "nothing happened" entries would bury the entries that matter.
     *
     * @param organization the cooperative whose configuration changed, taken from the caller's
     *                     token by the calling service and never from a request body
     * @param reason       the administrator's stated reason. Required when any changed setting is a
     *                     rate or a price; kept when supplied for anything else.
     * @throws ResponseStatusException {@code 400} when a rate or price changed and no reason was
     *                                given, {@code 401}/{@code 403} when the caller is not an
     *                                administrator
     */
    public void record(Organization organization, ConfigDomain domain, ConfigDiff diff,
            String reason) {
        record(organization, domain, diff.changes(), reason);
    }

    /** As {@link #record(Organization, ConfigDomain, ConfigDiff, String)}, for a prepared list. */
    public void record(Organization organization, ConfigDomain domain, List<ConfigChange> changes,
            String reason) {

        if (changes.isEmpty()) {
            return;
        }

        String statedReason = trimToNull(reason);
        requireReasonFits(statedReason);
        requireReasonWhereMandatory(changes, statedReason);

        AuthPrincipal actor = CurrentAuth.requireAdmin();

        // One date for the whole submission, so two settings changed together cannot end up dated a
        // midnight apart. Decision 4: this column records when the change was made and does not
        // schedule it -- configuration takes effect immediately.
        LocalDate effectiveDate = LocalDate.now();

        for (ConfigChange change : changes) {
            auditRepository.save(OrganizationConfigAudit.builder()
                    .organization(organization)
                    .actorUserId(actor.userId())
                    .actorLedgerId(actor.ledgerID())
                    .configDomain(domain)
                    .settingKey(change.settingKey())
                    .oldValue(change.oldValue())
                    .newValue(change.newValue())
                    .effectiveDate(effectiveDate)
                    .reason(statedReason)
                    .build());
        }

        log.info("Cooperative {} configuration changed by {}: domain={} settings={}",
                organization.getId(), actor.ledgerID(), domain,
                changes.stream().map(ConfigChange::settingKey).toList());
    }

    // --------------------------------------------------------------------- internals

    private void requireReasonFits(String statedReason) {
        if (statedReason != null && statedReason.length() > MAX_REASON_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Keep the reason under " + MAX_REASON_LENGTH + " characters.");
        }
    }

    /**
     * Refuses a rate or price change that came with no stated reason.
     *
     * <h2>Why only a value that already had one</h2>
     * "Say why the rate is changing" presupposes a rate to change from. A row whose
     * {@code oldValue} is null is a setting being given its first value, which for a loan product or
     * a savings plan is part of creating it -- demanding a justification for the rate on a product
     * that did not exist a moment ago asks the administrator to explain a change nobody experienced.
     * Once the product exists, every later move of that rate needs a reason, which is the case the
     * requirement is for: a member's cost of borrowing changing under a rule they had already
     * accepted.
     */
    private void requireReasonWhereMandatory(List<ConfigChange> changes, String statedReason) {
        if (statedReason != null) {
            return;
        }

        List<String> needReason = changes.stream()
                .filter(change -> REASON_REQUIRED_FIELDS.contains(change.field()))
                .filter(change -> change.oldValue() != null)
                .map(ConfigChange::settingKey)
                .toList();

        if (needReason.isEmpty()) {
            return;
        }

        // Names the settings rather than saying "a reason is required", so an administrator editing
        // eight fields knows which one is being objected to.
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Changing a rate or a price requires a reason. Please say why "
                        + String.join(", ", needReason) + " is changing.");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
