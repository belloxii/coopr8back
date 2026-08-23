package com.invo.coopr8.security;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reads the {@link AuthPrincipal} that {@code JwtTokenValidator} published for the current
 * request.
 *
 * <p>This exists so that no service has to re-parse the {@code Authorization} header or look a
 * member up by name to find out who is calling. The token is verified exactly once, in the
 * filter; everything downstream asks here.
 *
 * <p>It deliberately offers no way to obtain a principal for anyone other than the current
 * caller, and no way to construct one from request data.
 */
public final class CurrentAuth {

    private CurrentAuth() {
    }

    /** The caller, or empty when the request is unauthenticated. */
    public static Optional<AuthPrincipal> principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        return (principal instanceof AuthPrincipal authPrincipal)
                ? Optional.of(authPrincipal)
                : Optional.empty();
    }

    /**
     * The caller, for code that cannot run without one.
     *
     * @throws ResponseStatusException {@code 401} when the request is unauthenticated. The
     *                                message is deliberately generic.
     */
    public static AuthPrincipal require() {
        return principal().orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated."));
    }

    /**
     * The caller's tenant id.
     *
     * <p>Equivalent to {@code TenantContext.requireOrganizationId()} -- both come from the same
     * verified token -- but reads more naturally where the surrounding code is already working
     * with the principal.
     */
    public static Long requireOrganizationId() {
        return require().organizationId();
    }

    /**
     * The caller, who must be an administrator of their cooperative.
     *
     * <p>{@code AppConfig} already restricts {@code /api/admin/**} to {@code ROLE_ADMIN}, so this
     * is a second gate rather than the only one. It exists for the handful of service methods
     * that <em>move money or change a member's standing</em> -- approving a loan, approving a
     * share withdrawal -- because those must not become member-callable as a side effect of a URL
     * being moved or a new route being pointed at the same service method. A share withdrawal was
     * approvable by any {@code ROLE_MEMBER} before Phase 2 for exactly that reason: the check
     * lived only in a path pattern, and the path pattern was in the member namespace.
     *
     * @throws ResponseStatusException {@code 401} unauthenticated, {@code 403} not an admin
     */
    public static AuthPrincipal requireAdmin() {
        AuthPrincipal principal = require();
        if (!principal.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This action requires a cooperative administrator.");
        }
        return principal;
    }

    /**
     * Asserts that a {@code userId} taken from a path or query refers to the caller themselves.
     *
     * <p>Several member-facing endpoints are shaped as {@code /api/user/id/{userId}} or
     * {@code /api/savings/user/{userId}} because the frontend happens to know its own id. That
     * shape invites substituting somebody else's id, and before Phase 2 doing so worked: any
     * member could read another member's profile, savings history or repayments by editing the
     * URL. This is the one check that closes all of them.
     *
     * <p><strong>404, not 403.</strong> A distinct "forbidden" would confirm that the id exists
     * and belongs to someone -- and, on an id from another cooperative, that the other
     * cooperative has a member with it. Absent and not-yours are deliberately the same answer.
     *
     * @throws ResponseStatusException {@code 401} unauthenticated, {@code 404} any other id
     */
    public static AuthPrincipal requireSelf(Long userId) {
        AuthPrincipal principal = require();
        if (userId == null || !userId.equals(principal.userId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found.");
        }
        return principal;
    }
}
