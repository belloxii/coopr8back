package com.invo.coopr8.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.invo.coopr8.dto.LoanDto;

/**
 * Stage 0, item 4 -- the transaction boundary itself, asserted on the metadata.
 *
 * <p>{@link LoanDecisionTransactionTest} proves the <em>behaviour</em> (a mid-decision failure
 * rolls everything back) and is the test that matters. This one states the requirement it rests on
 * in a form that cannot be satisfied by accident: the two administrative decision methods carry
 * {@code org.springframework.transaction.annotation.Transactional}, and they do not carry
 * {@code jakarta.transaction.Transactional}.
 *
 * <p>Both annotations "work" under Spring, which is exactly why this is worth pinning. They are not
 * interchangeable in the details -- only the Spring one understands {@code readOnly}, Spring's
 * {@code Propagation}/{@code Isolation} enums, {@code rollbackForClassName}, or a qualifier naming a
 * specific transaction manager -- and this application has a custom manager
 * ({@code TenantAwareJpaTransactionManager}) whose configuration surface is Spring's. A file that
 * mixes the two invites a future change to be written against whichever annotation happens to be
 * imported, with the difference showing up as a silent behavioural gap rather than a compile error.
 *
 * <p>Deliberately a plain JUnit test: no Spring context, no database, no Docker. It answers a
 * question about the source, so it should be answerable without starting anything.
 */
class LoanTransactionBoundaryTest {

    @Test
    @DisplayName("approveLoan is transactional, using Spring's annotation")
    void approveLoanIsSpringTransactional() throws Exception {
        assertSpringTransactional(LoanServiceImpl.class.getMethod("approveLoan", Long.class));
    }

    @Test
    @DisplayName("rejectLoan is transactional, using Spring's annotation")
    void rejectLoanIsSpringTransactional() throws Exception {
        assertSpringTransactional(
                LoanServiceImpl.class.getMethod("rejectLoan", Long.class, LoanDto.class));
    }

    @Test
    @DisplayName("repayNow is transactional, using Spring's annotation")
    void repayNowIsSpringTransactional() throws Exception {
        assertSpringTransactional(RepayServiceImpl.class.getMethod(
                "repayNow", com.invo.coopr8.model.User.class, Long.class,
                com.invo.coopr8.dto.RepayDto.class));
    }

    @Test
    @DisplayName("no method on the loan service uses the Jakarta transaction annotation")
    void noLoanServiceMethodUsesTheJakartaAnnotation() {
        for (Method method : LoanServiceImpl.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(jakarta.transaction.Transactional.class))
                    .as("%s must not use jakarta.transaction.Transactional", method.getName())
                    .isFalse();
        }
    }

    private void assertSpringTransactional(Method method) {
        assertThat(method.isAnnotationPresent(
                org.springframework.transaction.annotation.Transactional.class))
                .as("%s.%s must declare a Spring transaction boundary",
                        method.getDeclaringClass().getSimpleName(), method.getName())
                .isTrue();
        assertThat(method.isAnnotationPresent(jakarta.transaction.Transactional.class))
                .as("%s.%s must not use jakarta.transaction.Transactional",
                        method.getDeclaringClass().getSimpleName(), method.getName())
                .isFalse();
    }
}
