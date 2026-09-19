package com.invo.coopr8.security;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Authenticated identity for a Platform Super Admin operating across the COOPR8 platform.
 *
 * <p>Deliberately distinct from tenant-level {@link AuthPrincipal}. Contains no organizationId
 * and holds ROLE_PLATFORM_ADMIN.
 */
public record PlatformAdminPrincipal(
        Long id,
        String email,
        String tokenId
) implements UserDetails, Serializable {

    public static final String ROLE = "ROLE_PLATFORM_ADMIN";
    private static final GrantedAuthority AUTHORITY = new SimpleGrantedAuthority(ROLE);

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(AUTHORITY);
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}

