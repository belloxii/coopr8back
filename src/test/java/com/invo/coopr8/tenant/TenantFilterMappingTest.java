package com.invo.coopr8.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.engine.spi.FilterDefinition;
import org.hibernate.internal.FilterConfiguration;
import org.hibernate.mapping.PersistentClass;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.invo.coopr8.model.Loan;
import com.invo.coopr8.model.Notification;
import com.invo.coopr8.model.OTP;
import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.OrganizationConfigAudit;
import com.invo.coopr8.model.OrganizationLoanConfig;
import com.invo.coopr8.model.OrganizationLoanType;
import com.invo.coopr8.model.OrganizationLoanTypeExclusion;
import com.invo.coopr8.model.OrganizationMembershipConfig;
import com.invo.coopr8.model.OrganizationRepaymentConfig;
import com.invo.coopr8.model.OrganizationSavingsPlan;
import com.invo.coopr8.model.OrganizationSharesConfig;
import com.invo.coopr8.model.Repay;
import com.invo.coopr8.model.Saving;
import com.invo.coopr8.model.Shares;
import com.invo.coopr8.model.User;

/**
 * OFFLINE verification of the tenant {@code @Filter} mapping. No database, no Docker, no Spring
 * context: it builds Hibernate {@code Metadata} straight from the annotated classes with an
 * explicit PostgreSQL dialect and JDBC metadata access switched off, exactly as
 * {@code SchemaGenTest} does, and inspects the result.
 *
 * <p>What it pins down, and why each one is worth a test:
 *
 * <ul>
 *   <li><strong>The filter definition is registered.</strong> {@code @FilterDef} lives on
 *       {@link Organization} while every {@code @Filter} that uses it lives on another class.
 *       That cross-class resolution either happens during metadata binding or the filter is
 *       silently absent -- there is no third outcome, and this asserts which one it is.
 *   <li><strong>It is NOT auto-enabled.</strong> Hibernate would otherwise append
 *       {@code organization_id = null} to every pre-authentication query -- login, signup,
 *       password reset, the payment webhook -- and each would match no rows. A locked-out
 *       platform is the worst possible outcome of a defence-in-depth measure, so the default is
 *       asserted rather than assumed.
 *   <li><strong>Every tenant-owned entity carries it, and only those.</strong> A new
 *       tenant-owned entity added without the annotation fails here instead of shipping
 *       unfiltered. {@link Organization} must stay unfiltered: it has no {@code organization_id}
 *       and tenant discovery has to read it before anyone is authenticated.
 *   <li><strong>The parameter name in the SQL matches the declared parameter.</strong> A
 *       mismatch is not a compile error; it is a runtime failure on the first filtered query.
 * </ul>
 *
 * <p>Run without Docker:
 * <pre>mvnw.cmd -Dtest=TenantFilterMappingTest -DfailIfNoTests=false test</pre>
 */
class TenantFilterMappingTest {

    /**
     * Entities that own tenant data and must be filtered.
     *
     * <p>The seven Phase 4 configuration entities are here for the same reason the transaction
     * entities are: one cooperative's interest rate, share price and loan products are its own
     * business, and the audit trail additionally discloses the dates its terms changed.
     */
    private static final List<Class<?>> TENANT_OWNED = List.of(
            User.class, Loan.class, Repay.class, Saving.class,
            Shares.class, Notification.class, OTP.class,
            OrganizationLoanConfig.class, OrganizationLoanType.class,
            OrganizationLoanTypeExclusion.class,
            OrganizationSavingsPlan.class, OrganizationSharesConfig.class,
            OrganizationRepaymentConfig.class, OrganizationMembershipConfig.class,
            OrganizationConfigAudit.class);

    private static Metadata metadata;

    @BeforeAll
    static void buildMetadataOffline() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        settings.put("hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        settings.put("hibernate.implicit_naming_strategy",
                "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy");
        // Guarantee no JDBC connection is attempted anywhere in the bootstrap.
        settings.put("hibernate.temp.use_jdbc_metadata_defaults", "false");
        settings.put("hibernate.boot.allow_jdbc_metadata_access", "false");
        // No schema action at all -- unlike SchemaGenTest, this writes nothing.
        settings.put("jakarta.persistence.schema-generation.database.action", "none");

        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySettings(settings)
                .build();

        MetadataSources sources = new MetadataSources(registry).addAnnotatedClass(Organization.class);
        TENANT_OWNED.forEach(sources::addAnnotatedClass);
        sources.addAnnotatedClass(com.invo.coopr8.model.Plan.class);
        sources.addAnnotatedClass(com.invo.coopr8.model.PlanPriceAudit.class);
        sources.addAnnotatedClass(com.invo.coopr8.model.PlatformAdmin.class);

        metadata = sources.buildMetadata();

        // Building the factory is the strictest check available offline: an entity referring to
        // a filter that was never defined does not survive it.
        try (SessionFactory sessionFactory = metadata.buildSessionFactory()) {
            assertThat(sessionFactory).isNotNull();
        }
    }

    @Test
    void filterDefinitionIsRegisteredWithTheDeclaredParameterAndCondition() {
        FilterDefinition definition = metadata.getFilterDefinition(TenantFilter.NAME);

        assertThat(definition)
                .as("@FilterDef '%s' is declared on Organization and must be registered globally",
                        TenantFilter.NAME)
                .isNotNull();
        assertThat(definition.getParameterNames()).containsExactly(TenantFilter.PARAMETER);
        assertThat(definition.getDefaultFilterCondition()).isEqualTo(TenantFilter.CONDITION);
    }

    @Test
    void filterIsNeverAutoEnabled() {
        assertThat(metadata.getFilterDefinition(TenantFilter.NAME).isAutoEnabled())
                .as("auto-enabling would put 'organization_id = null' into every "
                        + "pre-authentication query and lock every member out")
                .isFalse();
    }

    @Test
    void everyTenantOwnedEntityIsFiltered() {
        for (Class<?> entity : TENANT_OWNED) {
            List<FilterConfiguration> filters = bindingOf(entity).getFilters();

            assertThat(filters)
                    .as("%s owns tenant data, so it must carry @Filter(%s)",
                            entity.getSimpleName(), TenantFilter.NAME)
                    .extracting(FilterConfiguration::getName)
                    .contains(TenantFilter.NAME);

            String condition = filters.stream()
                    .filter(filter -> TenantFilter.NAME.equals(filter.getName()))
                    .map(FilterConfiguration::getCondition)
                    .findFirst()
                    .orElseThrow();

            assertThat(condition)
                    .as("the filter on %s must restrict the tenant discriminator column, and the "
                            + "placeholder must match the declared parameter",
                            entity.getSimpleName())
                    .isEqualTo(TenantFilter.CONDITION)
                    .contains("organization_id")
                    .contains(":" + TenantFilter.PARAMETER);
        }
    }

    @Test
    void theOrganizationItselfIsNotFiltered() {
        assertThat(bindingOf(Organization.class).getFilters())
                .as("Organization is platform-global: it has no organization_id column, and "
                        + "login, signup and public branding all resolve it before any tenant "
                        + "is bound")
                .isEmpty();
    }

    @Test
    void platformEntitiesAreNotFiltered() {
        for (Class<?> entity : List.of(com.invo.coopr8.model.Plan.class,
                com.invo.coopr8.model.PlanPriceAudit.class,
                com.invo.coopr8.model.PlatformAdmin.class)) {
            assertThat(bindingOf(entity).getFilters())
                    .as("%s is platform-global: it has no organization_id column and must not carry @Filter",
                            entity.getSimpleName())
                    .isEmpty();
        }
    }

    @Test
    void noOtherEntityIsSilentlyLeftUnfiltered() {
        Set<String> expected = TENANT_OWNED.stream()
                .map(Class::getName)
                .collect(Collectors.toSet());

        Set<String> filtered = metadata.getEntityBindings().stream()
                .filter(binding -> binding.getFilters().stream()
                        .anyMatch(filter -> TenantFilter.NAME.equals(filter.getName())))
                .map(PersistentClass::getClassName)
                .collect(Collectors.toSet());

        assertThat(filtered)
                .as("the set of filtered entities must be exactly the set of tenant-owned ones; "
                        + "a new entity with an organization_id and no @Filter belongs in "
                        + "TENANT_OWNED and on the entity")
                .isEqualTo(expected);
    }

    private static PersistentClass bindingOf(Class<?> entity) {
        return metadata.getEntityBindings().stream()
                .filter(binding -> entity.getName().equals(binding.getClassName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No entity binding for " + entity.getName()));
    }
}
