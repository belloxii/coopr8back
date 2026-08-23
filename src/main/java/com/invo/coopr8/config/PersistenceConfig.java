package com.invo.coopr8.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.invo.coopr8.tenant.TenantAwareJpaTransactionManager;
import com.invo.coopr8.tenant.TenantFilter;

/**
 * Replaces Spring Boot's auto-configured JPA transaction manager with the tenant-aware one, so
 * that every transaction opened while a tenant is bound has the Hibernate tenant filter switched
 * on for its session.
 *
 * <p>Boot's own {@code JpaBaseConfiguration#transactionManager} is
 * {@code @ConditionalOnMissingBean(TransactionManager.class)}, so declaring this bean backs it
 * off rather than competing with it. The {@link TransactionManagerCustomizers} hook is applied
 * exactly as Boot applies it, so {@code spring.transaction.*} properties keep working.
 *
 * @see TenantFilter
 * @see TenantAwareJpaTransactionManager
 */
@Configuration
public class PersistenceConfig {

    @Bean
    public PlatformTransactionManager transactionManager(
            ObjectProvider<TransactionManagerCustomizers> transactionManagerCustomizers,
            @Value("${coopr8.tenant.hibernate-filter.enabled:true}") boolean tenantFilterEnabled) {

        TenantAwareJpaTransactionManager transactionManager =
                new TenantAwareJpaTransactionManager(tenantFilterEnabled);

        // No EntityManagerFactory is passed: JpaTransactionManager.afterPropertiesSet() finds
        // the single EntityManagerFactory bean in the context, which is what Boot relies on too.
        transactionManagerCustomizers.ifAvailable(
                customizers -> customizers.customize(transactionManager));

        return transactionManager;
    }
}
