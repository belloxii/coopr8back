package com.invo.coopr8.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.invo.coopr8.config.AsyncConfig;
import com.invo.coopr8.tenant.TenantContext.TenantContextMissingException;

/**
 * Unit tests for the thread-bound tenant: what it refuses, and where it must not travel.
 *
 * <p>The failure this guards against has no visible symptom. A tenant left behind on a pooled
 * servlet thread becomes the <em>next</em> request's tenant, and that request may belong to a
 * different cooperative -- it would be served that other cooperative's data, successfully, with
 * a 200. So the two properties tested here are the ones that turn a silent leak into a loud
 * failure: {@code bind} refuses to overwrite a different tenant, and nothing is inherited by a
 * background thread.
 *
 * <p>No database, no Docker, no Spring context.
 */
class TenantContextTest {

    private static final ActiveTenant CITADEL = new ActiveTenant(1L, "citadel");
    private static final ActiveTenant OTHER_COOP = new ActiveTenant(2L, "other-coop");

    @BeforeEach
    @AfterEach
    void noTenantLeaksBetweenTests() {
        TenantContext.clear();
    }

    @Test
    void nothingIsBoundUntilSomethingBindsIt() {
        assertThat(TenantContext.isBound()).isFalse();
        assertThat(TenantContext.getTenant()).isNull();
        assertThat(TenantContext.getOrganizationId()).isNull();
        assertThat(TenantContext.getOrganizationSlug()).isNull();
    }

    @Test
    void bindingMakesTheTenantReadable() {
        TenantContext.bind(CITADEL);

        assertThat(TenantContext.isBound()).isTrue();
        assertThat(TenantContext.getTenant()).isEqualTo(CITADEL);
        assertThat(TenantContext.getOrganizationId()).isEqualTo(1L);
        assertThat(TenantContext.getOrganizationSlug()).isEqualTo("citadel");
        assertThat(TenantContext.requireOrganizationId()).isEqualTo(1L);
    }

    @Test
    void clearingRemovesIt() {
        TenantContext.bind(CITADEL);
        TenantContext.clear();

        assertThat(TenantContext.isBound()).isFalse();
        assertThat(TenantContext.getOrganizationId()).isNull();
    }

    @Test
    void bindingTheSameTenantAgainIsAllowed() {
        TenantContext.bind(CITADEL);

        assertThat(TenantContext.getOrganizationId()).isEqualTo(1L);
        TenantContext.bind(new ActiveTenant(1L, "citadel"));
        assertThat(TenantContext.getOrganizationId()).isEqualTo(1L);
    }

    @Test
    void rebindingToADifferentTenantIsRefusedAndNamesBoth() {
        TenantContext.bind(CITADEL);

        assertThatThrownBy(() -> TenantContext.bind(OTHER_COOP))
                .as("a missed clear or a mid-request tenant switch must fail the request, not "
                        + "quietly serve the other cooperative's data")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1")
                .hasMessageContaining("2");

        assertThat(TenantContext.getOrganizationId())
                .as("the refusal must not have replaced the tenant either")
                .isEqualTo(1L);
    }

    @Test
    void bindingNullIsRefused() {
        assertThatThrownBy(() -> TenantContext.bind(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("refused");
    }

    @Test
    void requiringATenantWithNoneBoundFailsClosedAsUnauthorised() {
        TenantContextMissingException thrown = (TenantContextMissingException)
                org.assertj.core.api.Assertions.catchThrowable(TenantContext::requireOrganizationId);

        assertThat(thrown)
                .as("it must never default to the only organization, or a configured one")
                .isNotNull();
        assertThat(thrown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(thrown.getReason())
                .as("an unauthenticated caller learns nothing about why resolution failed")
                .isEqualTo("Not authenticated.");
    }

    // ---------------------------------------------------------------- thread boundaries

    @Test
    void aBoundTenantIsInvisibleToAnotherThread() throws Exception {
        TenantContext.bind(CITADEL);
        AtomicReference<Long> seen = new AtomicReference<>(-1L);

        Thread other = new Thread(() -> seen.set(TenantContext.getOrganizationId()));
        other.start();
        other.join();

        assertThat(seen.get())
                .as("the holder must be a plain ThreadLocal: an InheritableThreadLocal would "
                        + "hand this tenant to every thread the request spawns, including pooled "
                        + "ones that outlive it")
                .isNull();
    }

    @Test
    void theAsyncExecutorDoesNotInheritTheTenant() {
        // The real bean, not a stand-in.
        Executor executor = new AsyncConfig().taskExecutor();
        TenantContext.bind(CITADEL);
        List<Long> seen = new CopyOnWriteArrayList<>();

        CompletableFuture.runAsync(
                () -> seen.add(orSentinel(TenantContext.getOrganizationId())), executor).join();

        assertThat(seen)
                .as("background work receives what it needs as parameters -- an inherited "
                        + "binding in a pool thread has no request boundary to clear it")
                .containsExactly(-1L);
    }

    @Test
    void nothingAccumulatesOnAReusedPoolThread() {
        Executor executor = new AsyncConfig().taskExecutor();
        TenantContext.bind(CITADEL);
        List<Long> seen = new CopyOnWriteArrayList<>();
        List<String> threads = new ArrayList<>();

        for (int i = 0; i < 12; i++) {
            CompletableFuture.runAsync(() -> {
                seen.add(orSentinel(TenantContext.getOrganizationId()));
                threads.add(Thread.currentThread().getName());
            }, executor).join();
        }

        assertThat(seen).as("every task on every pool thread must see no tenant")
                .containsOnly(-1L);
        assertThat(threads.stream().distinct().count())
                .as("at least one pool thread must have been reused, or this proves nothing "
                        + "about accumulation across tasks")
                .isLessThan(threads.size());
    }

    /** {@code null} is the expected reading, and a list cannot hold it distinguishably. */
    private static Long orSentinel(Long organizationId) {
        return organizationId == null ? -1L : organizationId;
    }
}
