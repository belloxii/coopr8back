package com.invo.coopr8.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.OrganizationStatus;
import com.invo.coopr8.repository.OrganizationRepository;

class TenantActivationLifecycleTest {

    private OrganizationRepository organizationRepository;
    private TenantResolver tenantResolver;

    @BeforeEach
    void setUp() {
        organizationRepository = mock(OrganizationRepository.class);
        tenantResolver = new TenantResolver(organizationRepository);
    }

    private OrganizationRepository.TenantView createView(Long id, String slug, OrganizationStatus status) {
        return new OrganizationRepository.TenantView() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public String getSlug() {
                return slug;
            }

            @Override
            public OrganizationStatus getStatus() {
                return status;
            }
        };
    }

    @Test
    void pendingActivationOrganizationIsRefusedByTenantResolver() {
        Organization org = Organization.builder()
                .id(15L)
                .slug("pending-coop")
                .ledgerPrefix("PEND")
                .status(OrganizationStatus.PENDING_ACTIVATION)
                .build();

        when(organizationRepository.findTenantViewById(15L))
                .thenReturn(Optional.of(createView(15L, "pending-coop", OrganizationStatus.PENDING_ACTIVATION)));
        when(organizationRepository.findAllBySlugIgnoreCase("pending-coop"))
                .thenReturn(List.of(org));
        when(organizationRepository.findAllByLedgerPrefixIgnoreCase("PEND"))
                .thenReturn(List.of(org));

        assertThat(tenantResolver.activeTenantById(15L)).isEmpty();
        assertThat(tenantResolver.activeOrganizationBySlug("pending-coop")).isEmpty();
        assertThat(tenantResolver.activeOrganizationByLedgerPrefix("PEND")).isEmpty();
    }

    @Test
    void suspendedOrganizationIsRefusedByTenantResolver() {
        Organization org = Organization.builder()
                .id(20L)
                .slug("suspended-coop")
                .ledgerPrefix("SUSP")
                .status(OrganizationStatus.SUSPENDED)
                .build();

        when(organizationRepository.findTenantViewById(20L))
                .thenReturn(Optional.of(createView(20L, "suspended-coop", OrganizationStatus.SUSPENDED)));
        when(organizationRepository.findAllBySlugIgnoreCase("suspended-coop"))
                .thenReturn(List.of(org));
        when(organizationRepository.findAllByLedgerPrefixIgnoreCase("SUSP"))
                .thenReturn(List.of(org));

        assertThat(tenantResolver.activeTenantById(20L)).isEmpty();
        assertThat(tenantResolver.activeOrganizationBySlug("suspended-coop")).isEmpty();
        assertThat(tenantResolver.activeOrganizationByLedgerPrefix("SUSP")).isEmpty();
    }

    @Test
    void activeOrganizationResolvesSuccessfully() {
        Organization org = Organization.builder()
                .id(1L)
                .slug("active-coop")
                .ledgerPrefix("ACTV")
                .status(OrganizationStatus.ACTIVE)
                .build();

        when(organizationRepository.findTenantViewById(1L))
                .thenReturn(Optional.of(createView(1L, "active-coop", OrganizationStatus.ACTIVE)));
        when(organizationRepository.findAllBySlugIgnoreCase("active-coop"))
                .thenReturn(List.of(org));
        when(organizationRepository.findAllByLedgerPrefixIgnoreCase("ACTV"))
                .thenReturn(List.of(org));

        Optional<ActiveTenant> tenant = tenantResolver.activeTenantById(1L);
        assertThat(tenant).isPresent();
        assertThat(tenant.get().id()).isEqualTo(1L);
        assertThat(tenant.get().slug()).isEqualTo("active-coop");

        assertThat(tenantResolver.activeOrganizationBySlug("active-coop")).isPresent();
        assertThat(tenantResolver.activeOrganizationByLedgerPrefix("ACTV")).isPresent();
    }
}
