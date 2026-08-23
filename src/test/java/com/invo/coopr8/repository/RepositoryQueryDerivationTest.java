package com.invo.coopr8.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.parser.Part;
import org.springframework.data.repository.query.parser.PartTree;

import com.invo.coopr8.model.User;

/**
 * Proves that every derived query on a tenant-owned repository really does restrict by
 * {@code organization.id} -- by asking Spring Data's own parser, rather than by reading the
 * method name and trusting it.
 *
 * <p>{@code TenantIsolationArchitectureTest} checks that each method name mentions the
 * organization. That is a useful tripwire but it is not proof: a name is only a promise about
 * the SQL, and {@code findByOrganizationId} would read exactly the same whether Spring Data
 * resolved it to {@code organization.id}, to a literal {@code organizationId} property, or to
 * nothing at all. {@link PartTree} is the class that actually turns these names into predicates,
 * so running it here answers the question the name only raises.
 *
 * <p>It catches, offline, three things that otherwise surface only when the application context
 * starts against a real database:
 *
 * <ul>
 *   <li>A name that does not parse at all -- a typo in a property, or a keyword combination
 *       Spring Data cannot split ({@code IN} with {@code IgnoreCase}, for one, which is why
 *       {@code findByOrganizationIdAndStatusIn} is spelled as a {@code @Query}).
 *   <li>A name that parses into the wrong path -- resolving to some other property that merely
 *       starts with the same letters.
 *   <li>An {@code OrderBy} clause naming a property that does not exist, which {@link PartTree}
 *       validates along with the predicates.
 * </ul>
 *
 * <p>Methods carrying {@code @Query} are skipped: their SQL is written out, the method name is
 * then decorative, and {@code TenantIsolationArchitectureTest} checks that JPQL instead.
 *
 * <p>No database, no Docker, no Spring context.
 */
class RepositoryQueryDerivationTest {

    /** The tenant-owned repositories -- the same set the architecture test enforces. */
    private static final List<Class<?>> TENANT_OWNED_REPOSITORIES = List.of(
            UserRepository.class,
            LoanRepository.class,
            RepayRepository.class,
            SavingRepository.class,
            SharesRepository.class,
            NotificationRepository.class,
            OTPRepository.class,
            OrganizationLoanConfigRepository.class,
            OrganizationLoanTypeRepository.class,
            OrganizationLoanTypeExclusionRepository.class,
            OrganizationSavingsPlanRepository.class,
            OrganizationSharesConfigRepository.class,
            OrganizationRepaymentConfigRepository.class,
            OrganizationMembershipConfigRepository.class,
            OrganizationConfigAuditRepository.class);

    /** The property path a tenant-scoped predicate must contain. */
    private static final String TENANT_PATH = "organization.id";

    @Test
    void everyDerivedQueryOnATenantOwnedRepositoryRestrictsByOrganizationId() {
        List<String> checked = new ArrayList<>();

        for (Class<?> repository : TENANT_OWNED_REPOSITORIES) {
            Class<?> domainClass = domainClassOf(repository);

            for (Method method : repository.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Query.class)) {
                    continue;
                }

                String name = repository.getSimpleName() + "." + method.getName();

                // Construction is itself an assertion: an unparseable name, an unknown
                // property, or an OrderBy on a property that does not exist all throw here.
                PartTree tree = new PartTree(method.getName(), domainClass);

                List<String> paths = StreamSupport.stream(tree.getParts().spliterator(), false)
                        .map(Part::getProperty)
                        .map(property -> property.toDotPath())
                        .toList();

                assertThat(paths)
                        .as("%s is a derived query, so its predicates are exactly what its name "
                                + "resolves to -- and one of them must be the tenant", name)
                        .contains(TENANT_PATH);

                checked.add(name);
            }
        }

        assertThat(checked)
                .as("the derived queries are the isolation boundary; finding none of them means "
                        + "this test resolved no methods and proved nothing")
                .isNotEmpty();
    }

    @Test
    void organizationRepositoryQueriesResolveEvenThoughTheyAreNotTenantScoped() {
        Class<?> domainClass = domainClassOf(OrganizationRepository.class);
        List<String> checked = new ArrayList<>();

        for (Method method : OrganizationRepository.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Query.class)) {
                continue;
            }
            // Unscoped on purpose -- an organization has no organization_id, and login has to
            // find one before any tenant exists. Still worth parsing: the ledger-prefix and
            // slug lookups behind tenant discovery and the D1 uniqueness rules are derived
            // names, and a typo in one of them is a boot failure in production otherwise.
            assertThat(new PartTree(method.getName(), domainClass).getParts())
                    .as("%s must resolve to at least one predicate", method.getName())
                    .isNotEmpty();
            checked.add(method.getName());
        }

        assertThat(checked).isNotEmpty();
    }

    @Test
    void theAssertionWouldFailForAnUnscopedName() {
        // Negative control. `findByStatus` is a name nobody declared and nobody may: it parses
        // cleanly, which is the point -- the check above passes because of the organization in
        // the predicate list, not because every name happens to satisfy it.
        List<String> paths = StreamSupport.stream(
                        new PartTree("findByStatus", User.class).getParts().spliterator(), false)
                .map(Part::getProperty)
                .map(property -> property.toDotPath())
                .toList();

        assertThat(paths).containsExactly("status").doesNotContain(TENANT_PATH);
    }

    /** The {@code T} of {@code JpaRepository<T, ID>}, so a new repository needs no wiring here. */
    private static Class<?> domainClassOf(Class<?> repository) {
        for (Type type : repository.getGenericInterfaces()) {
            if (type instanceof ParameterizedType parameterized
                    && parameterized.getRawType().equals(JpaRepository.class)) {
                return (Class<?>) parameterized.getActualTypeArguments()[0];
            }
        }
        throw new AssertionError(repository.getSimpleName()
                + " does not extend JpaRepository<T, ID> directly, so its domain class cannot be "
                + "resolved -- teach this method how to find it");
    }
}
