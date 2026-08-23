package com.invo.coopr8.tenant;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.Query;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Enforces, at build time, the rule that Phase 2's tenant isolation actually rests on: a
 * tenant-owned table is never read or written without an organization in the query.
 *
 * <p>This is the test {@code UserRepository}'s Javadoc promises by name. It exists because the
 * isolation guarantee is a <em>negative</em> one -- "no query anywhere omits the organization" --
 * and no integration test can cover a query nobody has written yet. Spring Data hands every
 * repository the whole {@code JpaRepository} surface for free, so {@code findAll()} and
 * {@code findById(id)} are always one keystroke away and read exactly like the scoped versions
 * at the call site. Deleting them from the interfaces is not possible; refusing to compile a
 * call to them is.
 *
 * <p>It analyses compiled main classes only ({@code DoNotIncludeTests}): test fixtures build
 * cross-tenant data on purpose and must stay free to use the unscoped methods.
 *
 * <p>Each rule was checked against a deliberate mutation to confirm it can actually fail --
 * declaring {@code OrganizationRepository} tenant-owned trips the first two rules (on
 * {@code OrganizationService}'s {@code findById} and on the repository's own unscoped finders),
 * emptying the allowlist trips the third, dropping the {@code JwtTokenValidator} exemption trips
 * the fourth, and adding a {@code delete} call on the configuration audit trips the fifth. A green
 * architecture test that cannot go red is decoration.
 *
 * <p>No database, no Docker, no Spring context.
 */
@AnalyzeClasses(packages = "com.invo.coopr8", importOptions = ImportOption.DoNotIncludeTests.class)
class TenantIsolationArchitectureTest {

    /**
     * The append-only one. Named separately because it carries a restriction the other six do
     * not, enforced by {@link #theConfigurationAuditIsAppendOnly}.
     */
    private static final String CONFIG_AUDIT_REPOSITORY =
            "com.invo.coopr8.repository.OrganizationConfigAuditRepository";

    /**
     * The repositories whose rows belong to one cooperative.
     *
     * <p>{@code OrganizationRepository} is deliberately absent: an organization is
     * platform-global, has no {@code organization_id} of its own, and pre-authentication tenant
     * discovery has to look one up before any tenant exists. Its unscoped access is correct.
     *
     * <p>The seven {@code Organization*Config}/{@code Plan}/{@code Type}/{@code Audit}
     * repositories are the Phase 4 business configuration. Their names begin with "Organization"
     * because the entity does, which is the one case where the naming convention this class
     * enforces reads confusingly -- {@code OrganizationLoanConfigRepository} is tenant-<em>owned</em>
     * (a cooperative's loan settings), not tenant-<em>defining</em> like
     * {@code OrganizationRepository}.
     */
    private static final Set<String> TENANT_OWNED_REPOSITORIES = Set.of(
            "com.invo.coopr8.repository.UserRepository",
            "com.invo.coopr8.repository.LoanRepository",
            "com.invo.coopr8.repository.RepayRepository",
            "com.invo.coopr8.repository.SavingRepository",
            "com.invo.coopr8.repository.SharesRepository",
            "com.invo.coopr8.repository.NotificationRepository",
            "com.invo.coopr8.repository.OTPRepository",
            "com.invo.coopr8.repository.OrganizationLoanConfigRepository",
            "com.invo.coopr8.repository.OrganizationLoanTypeRepository",
            "com.invo.coopr8.repository.OrganizationLoanTypeExclusionRepository",
            "com.invo.coopr8.repository.OrganizationSavingsPlanRepository",
            "com.invo.coopr8.repository.OrganizationSharesConfigRepository",
            "com.invo.coopr8.repository.OrganizationRepaymentConfigRepository",
            "com.invo.coopr8.repository.OrganizationMembershipConfigRepository",
            CONFIG_AUDIT_REPOSITORY);

    /**
     * Inherited {@code CrudRepository}/{@code JpaRepository} methods that address rows by id or
     * by nothing at all, and therefore cross tenants.
     *
     * <p>{@code save}/{@code saveAll} are absent on purpose -- a write carries the organization
     * on the entity -- and so is {@code delete(entity)}, which can only be reached with an
     * entity that some scoped query already returned.
     */
    private static final Set<String> UNSCOPED_SPRING_DATA_METHODS = Set.of(
            "findAll", "findAllById", "findById",
            "getById", "getOne", "getReferenceById",
            "existsById", "count",
            "deleteById", "deleteAll", "deleteAllById",
            "deleteAllInBatch", "deleteAllByIdInBatch");

    /**
     * {@code @Query} methods on a tenant-owned repository that legitimately span tenants, with
     * the reason. Adding an entry here is a deliberate, reviewable act; forgetting the
     * organization in a new query is not.
     */
    private static final Set<String> DELIBERATELY_CROSS_TENANT_QUERIES = Set.of(
            // Housekeeping: deletes one-time codes whose ten minutes are up, for every tenant.
            // A code past its expiry is not tenant data anyone can act on, and expiring one
            // tenant's dead codes but not another's would be the odd behaviour.
            "com.invo.coopr8.repository.OTPRepository.deleteExpired");

    @ArchTest
    static final ArchRule tenantOwnedRepositoriesAreOnlyCalledThroughScopedMethods = classes()
            .that().resideInAPackage("com.invo.coopr8..")
            .and().resideOutsideOfPackage("com.invo.coopr8.repository..")
            .should(notCallUnscopedSpringDataMethods())
            .because("an unscoped finder returns rows from every cooperative on the platform, "
                    + "and reads identically to the scoped one at the call site");

    @ArchTest
    static final ArchRule everyFinderOnATenantOwnedRepositoryNamesTheOrganization = classes()
            .that(areTenantOwnedRepositories())
            .should(declareOnlyTenantScopedMethods())
            .because("a derived query is built from its own name, so a name that does not "
                    + "mention the organization cannot be filtering by it");

    @ArchTest
    static final ArchRule everyQueryOnATenantOwnedRepositoryFiltersByOrganization = classes()
            .that(areTenantOwnedRepositories())
            .should(writeOnlyTenantScopedJpql())
            .because("hand-written JPQL bypasses the naming convention entirely, and a bulk "
                    + "UPDATE or DELETE that omits the organization is silent when it is wrong");

    @ArchTest
    static final ArchRule onlyTheAuthenticationFilterBindsTheTenant = classes()
            .that().resideInAPackage("com.invo.coopr8..")
            .and().resideOutsideOfPackage("com.invo.coopr8.tenant..")
            .and().doNotHaveFullyQualifiedName("com.invo.coopr8.config.JwtTokenValidator")
            .should(notBindOrClearTheTenantContext())
            .because("the tenant is established once, from the verified token, and cleared once, "
                    + "in a finally block on the way out; a second place that binds or clears it "
                    + "is how a request ends up running under the wrong tenant or none");

    /**
     * The audit trail may be appended to and read, never rewritten.
     *
     * <p><strong>Currently vacuous, and deliberately committed anyway.</strong> Nothing calls
     * {@code OrganizationConfigAuditRepository} yet -- the writer arrives with the Stage 2 admin
     * endpoints -- so there are zero accesses for this rule to inspect and it passes by having
     * nothing to judge. It was verified by mutation instead: aiming
     * {@link #CONFIG_AUDIT_REPOSITORY} at {@code NotificationRepository} made it flag both
     * {@code NotisController}'s {@code delete(entity)} and its {@code @Query} bulk delete, which
     * are exactly the two shapes that must never appear against the audit. Committing the rule
     * before the code it constrains means the first person to write a delete finds a red build
     * rather than a code review.
     */
    @ArchTest
    static final ArchRule theConfigurationAuditIsAppendOnly = classes()
            .that().resideInAPackage("com.invo.coopr8..")
            .should(notDeleteConfigurationAuditRecords())
            .because("a financial audit trail an application bug can erase is not evidence of "
                    + "anything; the record of who changed an interest rate must outlive the "
                    + "administrator's ability to change their mind about it");

    /**
     * Guards the five rules above against passing for the wrong reason.
     *
     * <p>ArchUnit reads bytecode with a bundled ASM. When ASM cannot parse a class file it logs a
     * warning and "falls back to simple import" -- the class is still listed, but its recorded
     * accesses are empty. On this machine that already happens for every {@code java.base} class,
     * because the JDK is newer than the bundled ASM understands. It is harmless today (the
     * project compiles to release 17 and the rules only inspect accesses <em>from</em> project
     * classes), but if the project's own target level ever outruns ArchUnit the five rules above
     * would report green while inspecting nothing at all.
     *
     * <p>So: assert that the repositories are present, and that the accesses really were parsed.
     * A rule that can silently stop checking is worse than no rule.
     */
    @ArchTest
    static void theAnalysisActuallySawTheCodeItClaimsToCheck(JavaClasses importedClasses) {
        Set<String> imported = importedClasses.stream()
                .map(JavaClass::getFullName)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(imported)
                .as("every tenant-owned repository must be in the imported set, or the rules "
                        + "above checked nothing")
                .containsAll(TENANT_OWNED_REPOSITORIES);

        long callsIntoTenantOwnedRepositories = importedClasses.stream()
                .filter(javaClass -> javaClass.getPackageName().startsWith("com.invo.coopr8"))
                .flatMap(javaClass -> javaClass.getAccessesFromSelf().stream())
                .filter(access -> TENANT_OWNED_REPOSITORIES.contains(
                        access.getTargetOwner().getFullName()))
                .count();

        assertThat(callsIntoTenantOwnedRepositories)
                .as("the services call these repositories constantly; zero recorded accesses "
                        + "means ASM fell back to a simple import and "
                        + "tenantOwnedRepositoriesAreOnlyCalledThroughScopedMethods is vacuous")
                .isGreaterThan(0);
    }

    // ------------------------------------------------------------------ conditions

    private static ArchCondition<JavaClass> notCallUnscopedSpringDataMethods() {
        return new ArchCondition<>("not call an unscoped Spring Data method on a tenant-owned "
                + "repository") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                // getAccessesFromSelf() rather than getMethodCallsFromSelf() so a method
                // *reference* (repository::findAll) is caught as well as a call.
                //
                // The owner in the bytecode is the receiver's *static* type, so this catches
                // calls made through a variable typed as one of the repositories -- which is
                // every call in this codebase. A caller that deliberately widened the variable
                // to JpaRepository<User, Long> first would slip past; nothing does, and doing
                // so would be a conspicuous thing to write.
                for (JavaAccess<?> access : item.getAccessesFromSelf()) {
                    String owner = access.getTargetOwner().getFullName();
                    String member = access.getTarget().getName();
                    if (TENANT_OWNED_REPOSITORIES.contains(owner)
                            && UNSCOPED_SPRING_DATA_METHODS.contains(member)) {
                        events.add(SimpleConditionEvent.violated(access,
                                access.getDescription() + " -- " + member
                                        + "(...) is not tenant-scoped; use the "
                                        + "...AndOrganizationId form"));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> declareOnlyTenantScopedMethods() {
        return new ArchCondition<>("declare only methods that scope by organization") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaMethod method : item.getMethods()) {
                    boolean namesTheOrganization = method.getName().contains("Organization");
                    boolean isHandWrittenQuery = method.isAnnotatedWith(Query.class);
                    if (!namesTheOrganization && !isHandWrittenQuery) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " is a derived query whose name does not "
                                        + "mention the organization, so it queries across "
                                        + "tenants"));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> writeOnlyTenantScopedJpql() {
        return new ArchCondition<>("restrict every hand-written query by organization") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaMethod method : item.getMethods()) {
                    if (!method.isAnnotatedWith(Query.class)) {
                        continue;
                    }
                    String signature = item.getName() + "." + method.getName();
                    if (DELIBERATELY_CROSS_TENANT_QUERIES.contains(
                            item.getFullName() + "." + method.getName())) {
                        continue;
                    }
                    String jpql = method.getAnnotationOfType(Query.class).value();
                    if (!jpql.toLowerCase().contains("organization")) {
                        events.add(SimpleConditionEvent.violated(method,
                                signature + " has a @Query that never mentions the organization. "
                                        + "Add the predicate, or list it in "
                                        + "DELIBERATELY_CROSS_TENANT_QUERIES with the reason"));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notBindOrClearTheTenantContext() {
        return new ArchCondition<>("not bind or clear TenantContext") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaAccess<?> access : item.getAccessesFromSelf()) {
                    boolean onTenantContext = "com.invo.coopr8.tenant.TenantContext"
                            .equals(access.getTargetOwner().getFullName());
                    if (onTenantContext
                            && List.of("bind", "clear").contains(access.getTarget().getName())) {
                        events.add(SimpleConditionEvent.violated(access,
                                access.getDescription() + " -- only JwtTokenValidator may "
                                        + "bind or clear the tenant"));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notDeleteConfigurationAuditRecords() {
        return new ArchCondition<>("not delete configuration audit records") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (CONFIG_AUDIT_REPOSITORY.equals(item.getFullName())) {
                    return; // the interface itself declares none; see its Javadoc
                }
                for (JavaAccess<?> access : item.getAccessesFromSelf()) {
                    if (!CONFIG_AUDIT_REPOSITORY.equals(access.getTargetOwner().getFullName())) {
                        continue;
                    }
                    // Every deleting method Spring Data offers begins with "delete", including
                    // delete(entity) -- which the rule above deliberately permits on the other
                    // six repositories and must not permit here.
                    if (access.getTarget().getName().startsWith("delete")) {
                        events.add(SimpleConditionEvent.violated(access,
                                access.getDescription() + " -- the configuration audit is "
                                        + "append-only. Nothing may delete a record of who "
                                        + "changed a cooperative's financial terms"));
                    }
                }
            }
        };
    }

    private static DescribedPredicate<JavaClass> areTenantOwnedRepositories() {
        return DescribedPredicate.describe(
                "tenant-owned repositories",
                javaClass -> TENANT_OWNED_REPOSITORIES.contains(javaClass.getFullName()));
    }
}
