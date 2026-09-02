package com.invo.coopr8.configuration;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.invo.coopr8.dto.config.ConfigAuditResponse;
import com.invo.coopr8.model.ConfigDomain;
import com.invo.coopr8.model.OrganizationConfigAudit;
import com.invo.coopr8.repository.OrganizationConfigAuditRepository;
import com.invo.coopr8.security.CurrentAuth;

import lombok.RequiredArgsConstructor;

/**
 * Reading a cooperative's configuration history.
 *
 * <p>Read-only by construction: this class holds the repository but calls only its two finders, and
 * {@link ConfigAuditWriter} remains the only class in COOPR8 that writes a row --
 * {@code TenantIsolationArchitectureTest.theConfigurationAuditIsAppendOnly} fails the build
 * otherwise.
 *
 * <h2>Newest first, and capped</h2>
 * Both queries order by id descending, so the newest change is the first element. The result is
 * truncated to {@link #MAX_ENTRIES}: a cooperative that has been running for years accumulates a row
 * per setting per change, and a settings screen asking for "the history" should not be able to pull
 * an unbounded result set into memory. The cap is applied after the ordering, so what is dropped is
 * always the oldest.
 *
 * <p>The truncation is visible rather than silent -- callers get at most {@code MAX_ENTRIES} entries
 * and the count tells them whether they may be looking at a window. A cursor-based endpoint is the
 * right answer when a cooperative outgrows this, and is not Stage 2's business.
 */
@Service
@RequiredArgsConstructor
public class ConfigAuditService {

    /**
     * The most entries one read returns.
     *
     * <p>Two hundred is roughly a year of ordinary settings activity for one cooperative: a handful
     * of products configured, a rate revised a few times, a membership rule adjusted. Large enough
     * that the screen is not usually a window, small enough that the response cannot become a
     * megabyte.
     */
    public static final int MAX_ENTRIES = 200;

    private final OrganizationConfigAuditRepository auditRepository;

    /**
     * This cooperative's configuration history, optionally narrowed to one domain.
     *
     * @param domain a {@link ConfigDomain} name, case-insensitive. Null or blank means every domain.
     * @throws org.springframework.web.server.ResponseStatusException {@code 400} when the domain is
     *         not a name the audit understands, rather than quietly returning everything -- a
     *         misspelled filter that answered with the full history would look like a domain with a
     *         suspiciously busy trail
     */
    @Transactional(readOnly = true)
    public List<ConfigAuditResponse> history(String domain) {
        CurrentAuth.requireAdmin();
        Long organizationId = CurrentAuth.requireOrganizationId();

        List<OrganizationConfigAudit> entries = isBlank(domain)
                ? auditRepository.findAllByOrganizationIdOrderByIdDesc(organizationId)
                : auditRepository.findAllByOrganizationIdAndConfigDomainOrderByIdDesc(
                        organizationId, requireDomain(domain));

        return entries.stream()
                .limit(MAX_ENTRIES)
                .map(ConfigAuditResponse::of)
                .toList();
    }

    // ============================================================================= internals

    private static ConfigDomain requireDomain(String raw) {
        String candidate = raw.trim();
        for (ConfigDomain domain : ConfigDomain.values()) {
            if (domain.name().equalsIgnoreCase(candidate)) {
                return domain;
            }
        }
        throw ConfigRules.refusal("\"" + candidate + "\" is not a configuration area. Choose "
                + Arrays.stream(ConfigDomain.values()).map(Enum::name)
                        .collect(Collectors.joining(", "))
                + ".");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
