package com.invo.coopr8;

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
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * OFFLINE schema-DDL generator (NOT a runtime test, NOT a Spring test).
 *
 * <p>Purpose: emit the EXACT CREATE-TABLE DDL that Hibernate expects for the
 * current JPA entities, so the hand-authored Flyway {@code V1__initial_coopr8_schema.sql}
 * can be built to match it and {@code spring.jpa.hibernate.ddl-auto=validate} will
 * pass. It resolves physical column-name ambiguities (e.g. {@code ledgerID},
 * {@code guarantor1Status}) authoritatively instead of by guessing.
 *
 * <p>SAFETY: this bootstraps a bare Hibernate {@code Metadata} with an EXPLICIT
 * PostgreSQL dialect and drives the JPA-standard schema-<em>script</em> generation
 * ({@code jakarta.persistence.schema-generation.scripts.action=create}). The
 * database action is left at its default of {@code none}, and
 * {@code use_jdbc_metadata_defaults=false} plus the explicit dialect mean Hibernate
 * never requests a JDBC connection: it writes the script purely from metadata + the
 * dialect. It touches NO database. It uses the same Spring Boot naming strategies the
 * running application uses, so the generated names match runtime exactly.
 *
 * <p>NOTE: {@code org.hibernate.tool.hbm2ddl.SchemaExport} was removed in Hibernate
 * ORM 6.x; this JPA-standard script-generation path is its supported replacement.
 *
 * <p>Run in isolation (never the full suite, which would boot the app against the
 * remote DB):
 * <pre>mvnw.cmd -Dtest=SchemaGenTest -DfailIfNoTests=false test</pre>
 * Output: {@code target/generated-schema.sql}.
 */
class SchemaGenTest {

    @Test
    void generateCanonicalDdl() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        // Match the running Spring Boot 3.3.5 app's naming EXACTLY. Spring Boot 3.x
        // removed SpringPhysicalNamingStrategy; HibernateProperties$Naming defaults the
        // physical strategy to Hibernate's own CamelCaseToUnderscoresNamingStrategy and
        // the implicit strategy to SpringImplicitNamingStrategy (verified against the
        // spring-boot-autoconfigure-3.3.5 bytecode). The app sets no override, so these
        // are the runtime defaults and the generated column names will match validate.
        settings.put("hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        settings.put("hibernate.implicit_naming_strategy",
                "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy");
        // Guarantee no JDBC connection is attempted anywhere in the bootstrap.
        settings.put("hibernate.temp.use_jdbc_metadata_defaults", "false");
        settings.put("hibernate.boot.allow_jdbc_metadata_access", "false");
        // JPA-standard schema SCRIPT generation -> writes a file, no DB action.
        settings.put("jakarta.persistence.schema-generation.database.action", "none");
        settings.put("jakarta.persistence.schema-generation.scripts.action", "create");
        settings.put("jakarta.persistence.schema-generation.scripts.create-target",
                "target/generated-schema.sql");

        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySettings(settings)
                .build();

        try {
            Metadata metadata = new MetadataSources(registry)
                    .addAnnotatedClass(Organization.class)
                    .addAnnotatedClass(User.class)
                    .addAnnotatedClass(Loan.class)
                    .addAnnotatedClass(Repay.class)
                    .addAnnotatedClass(Saving.class)
                    .addAnnotatedClass(Shares.class)
                    .addAnnotatedClass(Notification.class)
                    .addAnnotatedClass(OTP.class)
                    // Phase 4 business configuration. Added here for the same reason the
                    // Phase 1 entities are: the generated script is how V3-V8 were checked
                    // to be exactly what Hibernate expects, so `validate` passes on boot.
                    // The rate columns are the interesting case -- Hibernate 6 maps
                    // BigDecimal to numeric(38,2) by default, so numeric(6,3) only appears
                    // here if the entity declares precision/scale explicitly.
                    .addAnnotatedClass(OrganizationLoanConfig.class)
                    .addAnnotatedClass(OrganizationLoanType.class)
                    // V10. Its two loan-type columns are plain bigints on purpose -- the
                    // foreign keys are composite, on (organization_id, <type>), which no
                    // @ManyToOne can express without mapping organization_id three times.
                    // So `validate` checks the columns and the composite FK is V10's alone;
                    // OrganizationConfigConstraintTest is what proves it exists.
                    .addAnnotatedClass(OrganizationLoanTypeExclusion.class)
                    .addAnnotatedClass(OrganizationSavingsPlan.class)
                    .addAnnotatedClass(OrganizationSharesConfig.class)
                    .addAnnotatedClass(OrganizationRepaymentConfig.class)
                    .addAnnotatedClass(OrganizationMembershipConfig.class)
                    .addAnnotatedClass(OrganizationConfigAudit.class)
                    // Global / Platform entities (Phase 1). Added so their DDL is validated offline.
                    .addAnnotatedClass(com.invo.coopr8.model.Plan.class)
                    .addAnnotatedClass(com.invo.coopr8.model.PlanPriceAudit.class)
                    .addAnnotatedClass(com.invo.coopr8.model.PlatformAdmin.class)
                    .buildMetadata();

            // Building the SessionFactory triggers the JPA script generation
            // configured above. With database.action=none it only writes the file.
            try (SessionFactory sf = metadata.buildSessionFactory()) {
                // no-op: the script is emitted during factory construction.
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
