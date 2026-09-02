package com.invo.coopr8.tenant;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
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
 * the fourth, adding a {@code delete} call on the configuration audit trips the fifth, pointing the
 * audit-writer exemption at a class that writes nothing trips the sixth, and narrowing the excluded
 * package to {@code com.invo.coopr8.payment.paystack.none} trips the seventh (on
 * {@code PaystackPaymentProvider}'s use of {@code PaystackApiClient}). A green architecture test that
 * cannot go red is decoration.
 *
 * <p>No database, no Docker, no Spring context.
 */
@AnalyzeClasses(packages = "com.invo.coopr8", importOptions = ImportOption.DoNotIncludeTests.class)
class TenantIsolationArchitectureTest {

    /**
     * The append-only one. Named separately because it carries two restrictions the other six do
     * not, enforced by {@link #theConfigurationAuditIsAppendOnly} and
     * {@link #onlyTheAuditWriterRecordsConfigurationChanges}.
     */
    private static final String CONFIG_AUDIT_REPOSITORY =
            "com.invo.coopr8.repository.OrganizationConfigAuditRepository";

    /**
     * The one class permitted to write an audit record.
     *
     * <p>Its Javadoc states this rule; this constant is what makes the statement true. See
     * {@link #onlyTheAuditWriterRecordsConfigurationChanges}.
     */
    private static final String CONFIG_AUDIT_WRITER =
            "com.invo.coopr8.configuration.ConfigAuditWriter";

    /**
     * Every method on a Spring Data repository that persists a row.
     *
     * <p>{@code save} and {@code saveAll} are the two the audit is written through; the flush
     * variants are listed because they exist on {@code JpaRepository} and do the same thing. Deletes
     * are handled by {@link #theConfigurationAuditIsAppendOnly}, which forbids them to every class
     * including this one.
     */
    private static final Set<String> PERSISTING_SPRING_DATA_METHODS = Set.of(
            "save", "saveAll", "saveAndFlush", "saveAllAndFlush");

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
            "com.invo.coopr8.repository.OrganizationPaymentConfigRepository",
            "com.invo.coopr8.repository.PaymentTransactionRepository",
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
            "com.invo.coopr8.repository.OTPRepository.deleteExpired",

            // Tenant resolution for an unauthenticated provider callback, and the only one of its
            // kind. A callback carries a payment reference and nothing else COOPR8 can trust; this
            // query is what TURNS that reference into a cooperative, so it cannot be scoped by the
            // answer it is being asked for. It is safe for the same reason
            // TenantResolver.activeOrganizationBySlug is: the key is globally unique by database
            // constraint (uk_payment_transaction_provider_reference), so it resolves to exactly one
            // cooperative's payment or to none -- and none is a refusal, never a fallback. Every
            // financial mutation that follows is scoped to the organization it resolved to.
            "com.invo.coopr8.repository.PaymentTransactionRepository.findByProviderReference");

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
     * <p>Verified by mutation: aiming {@link #CONFIG_AUDIT_REPOSITORY} at
     * {@code NotificationRepository} makes it flag both {@code NotisController}'s
     * {@code delete(entity)} and that repository's {@code @Query} bulk delete, which are exactly the
     * two shapes that must never appear against the audit. The rule was committed while still
     * vacuous, before {@code ConfigAuditWriter} existed, so that the first person to write a delete
     * would find a red build rather than a code review.
     */
    @ArchTest
    static final ArchRule theConfigurationAuditIsAppendOnly = classes()
            .that().resideInAPackage("com.invo.coopr8..")
            .should(notDeleteConfigurationAuditRecords())
            .because("a financial audit trail an application bug can erase is not evidence of "
                    + "anything; the record of who changed an interest rate must outlive the "
                    + "administrator's ability to change their mind about it");

    /**
     * Only {@code ConfigAuditWriter} may write an audit record.
     *
     * <p>Append-only is not enough on its own. Four layers already stop a record being altered after
     * the fact -- no setters, every column {@code updatable = false}, a repository declaring no
     * {@code delete*}, and the {@code tr_organization_config_audit_append_only} trigger -- and none of
     * them says anything about what goes <em>into</em> a row. A second class calling {@code save}
     * would be free to attribute a change to the wrong actor, omit the reason a rate change requires,
     * or record a "change" whose old and new values are the same, and the trail would still be
     * perfectly immutable while being wrong.
     *
     * <p>{@code ConfigAuditWriter} is where those three rules live. Funnelling every write through it
     * is what makes them properties of the table rather than habits of the callers -- and it is why
     * the audit and the change it describes always share one transaction, because there is only one
     * place that could ever have arranged otherwise.
     *
     * <p>Verified by mutation: pointing {@link #CONFIG_AUDIT_WRITER} at a class that writes nothing
     * makes this rule flag {@code ConfigAuditWriter.record}'s own {@code save}.
     */
    @ArchTest
    static final ArchRule onlyTheAuditWriterRecordsConfigurationChanges = classes()
            .that().resideInAPackage("com.invo.coopr8..")
            .and().doNotHaveFullyQualifiedName(CONFIG_AUDIT_WRITER)
            .should(notWriteConfigurationAuditRecords())
            .because("the rules that make an audit record true -- an actor from the verified token, "
                    + "a reason for a rate change, and nothing recorded that did not change -- are "
                    + "enforced in one class, and a second writer would not be bound by them");

    /**
     * Paystack stays behind the {@code PaymentProvider} interface.
     *
     * <p>This is the rule that keeps "provider-neutral" from being a claim in a Javadoc. Paystack is
     * COOPR8's first payment provider, not its payment model: every Paystack URL, field name, HMAC
     * and envelope shape lives in {@code com.invo.coopr8.payment.paystack}, and the domain reaches it
     * only through {@code PaymentProvider} and the provider-neutral records beside it. So no class
     * outside that package may name a class inside it -- not {@code PaymentService}, not a controller,
     * not a loan or savings or shares service.
     *
     * <p>The one legitimate exception is the wiring: {@code PaymentProviderRegistry} receives the
     * implementations Spring found. It does that through the interface and a
     * {@code List<PaymentProvider>}, so it needs no exemption here, and the rule staying exemption-free
     * is the point -- a second provider is a new package under {@code payment}, and the day someone
     * shortcuts to a Paystack class from the domain, the build says so rather than a reviewer.
     */
    @ArchTest
    static final ArchRule onlyThePaystackAdapterKnowsAboutPaystack = noClasses()
            .that().resideInAPackage("com.invo.coopr8..")
            .and().resideOutsideOfPackage("com.invo.coopr8.payment.paystack..")
            .should().dependOnClassesThat().resideInAPackage("com.invo.coopr8.payment.paystack..")
            .because("the domain speaks to a PaymentProvider, never to a provider; a second "
                    + "provider must be a new implementation and a configuration row, not a "
                    + "change to PaymentService");

    /**
     * Guards the seven rules above against passing for the wrong reason.
     *
     * <p>ArchUnit reads bytecode with a bundled ASM. When ASM cannot parse a class file it logs a
     * warning and "falls back to simple import" -- the class is still listed, but its recorded
     * accesses are empty. On this machine that already happens for every {@code java.base} class,
     * because the JDK is newer than the bundled ASM understands. It is harmless today (the
     * project compiles to release 17 and the rules only inspect accesses <em>from</em> project
     * classes), but if the project's own target level ever outruns ArchUnit the rules above
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

        // The two audit rules inspect accesses to one repository, so the count above passing is not
        // enough for them: it would still be satisfied by the other sixteen. ConfigAuditWriter saves
        // and ConfigAuditService reads, so this is non-zero -- and if it ever returns to zero, both
        // audit rules have gone back to judging nothing.
        long accessesToTheConfigurationAudit = importedClasses.stream()
                .filter(javaClass -> javaClass.getPackageName().startsWith("com.invo.coopr8"))
                .flatMap(javaClass -> javaClass.getAccessesFromSelf().stream())
                .filter(access -> CONFIG_AUDIT_REPOSITORY.equals(
                        access.getTargetOwner().getFullName()))
                .count();

        assertThat(accessesToTheConfigurationAudit)
                .as("ConfigAuditWriter writes the audit and ConfigAuditService reads it; zero "
                        + "recorded accesses means theConfigurationAuditIsAppendOnly and "
                        + "onlyTheAuditWriterRecordsConfigurationChanges are vacuous")
                .isGreaterThan(0);

        assertThat(imported)
                .as("onlyTheAuditWriterRecordsConfigurationChanges exempts this class by name, so a "
                        + "rename would silently turn the exemption into a rule that exempts nobody "
                        + "-- or, worse, a name that matches nothing and a writer that is no longer "
                        + "checked")
                .contains(CONFIG_AUDIT_WRITER);
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

    private static ArchCondition<JavaClass> notWriteConfigurationAuditRecords() {
        return new ArchCondition<>("not write configuration audit records") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (CONFIG_AUDIT_REPOSITORY.equals(item.getFullName())) {
                    return; // the interface inherits save from CrudRepository; it calls nothing
                }
                for (JavaAccess<?> access : item.getAccessesFromSelf()) {
                    if (!CONFIG_AUDIT_REPOSITORY.equals(access.getTargetOwner().getFullName())) {
                        continue;
                    }
                    if (PERSISTING_SPRING_DATA_METHODS.contains(access.getTarget().getName())) {
                        events.add(SimpleConditionEvent.violated(access,
                                access.getDescription() + " -- only ConfigAuditWriter may record a "
                                        + "configuration change. Call it instead, so the actor, the "
                                        + "reason and the same-transaction guarantee still hold"));
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
