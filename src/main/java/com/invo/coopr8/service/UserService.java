package com.invo.coopr8.service;

import java.util.List;

import com.invo.coopr8.dto.AdminUserUpdateRequest;
import com.invo.coopr8.dto.AuthResponse;
import com.invo.coopr8.dto.CoopResponse;
import com.invo.coopr8.dto.ForgotPasswordRequest;
import com.invo.coopr8.dto.LoginDto;
import com.invo.coopr8.dto.PasswordDto;
import com.invo.coopr8.dto.ResetPasswordRequest;
import com.invo.coopr8.dto.UserProfileUpdateRequest;
import com.invo.coopr8.dto.UserRequest;
import com.invo.coopr8.exception.UserException;
import com.invo.coopr8.model.User;

/**
 * Membership: joining a cooperative, signing in, and maintaining a member record.
 *
 * <p>Every operation below is answered inside exactly one organization. Two consequences show
 * up in the signatures:
 *
 * <ul>
 *   <li><b>No method takes an organization id.</b> For authenticated calls the tenant comes
 *       from the verified JWT via {@code CurrentAuth}; a parameter would be an invitation to
 *       pass someone else's.</li>
 *   <li><b>Updating a profile and administering a member are different methods</b>
 *       ({@link #updateOwnProfile} and {@link #adminUpdateUser}), taking different request
 *       types. The single {@code updateUser(User)} they replace let the request body set
 *       {@code role}, {@code status}, {@code ledgerID} and the password hash, so a member
 *       could make themselves an administrator with one extra JSON field.</li>
 * </ul>
 */
public interface UserService {

    // --------------------------------------------------------------------- membership

    /** Self-service signup: creates a PENDING member awaiting that tenant's approval. */
    AuthResponse createAccount(UserRequest request);

    /** Creates a member with an explicit initial status. Used by administrative onboarding. */
    AuthResponse createAccount(UserRequest request, String status);

    List<AuthResponse> addMultiUsers(List<UserRequest> requests) throws UserException;

    // ----------------------------------------------------------------- authentication

    /**
     * Authenticates {@code organization + membership number + password} and issues a token.
     *
     * <p>The organization is resolved <em>before</em> the member is looked up, so there is no
     * point at which a membership number is searched for platform-wide.
     */
    AuthResponse login(LoginDto loginDto);

    /**
     * The authenticated caller's own profile.
     *
     * <p>Replaces {@code findUserProfileByJwt(String)}: the token has already been verified by
     * the security filter, so re-parsing it in the service layer only created a second,
     * weaker code path to the same answer.
     */
    AuthResponse currentUserProfile();

    /** The authenticated caller's member record, loaded within their own organization. */
    User requireCurrentUser();

    // ---------------------------------------------------------------- record management

    /** A member editing their own profile. Always targets the caller; ignores privileged fields. */
    User updateOwnProfile(UserProfileUpdateRequest request);

    /** An administrator editing a member of their own cooperative. */
    User adminUpdateUser(AdminUserUpdateRequest request);

    /** An administrator activating a pending member of their own cooperative. */
    User activateUser(Long userId) throws UserException;

    // ------------------------------------------------------------------------ passwords

    /** OTP-confirmed password change for an authenticated member. */
    CoopResponse changePassword(User user, PasswordDto passwordDto);

    /** First-login password change: proves identity with the current password, no OTP. */
    CoopResponse changePasswordSimple(User user, String oldPassword, String newPassword);

    /** Step one of a forgotten-password reset: emails a tenant-scoped one-time code. */
    CoopResponse requestPasswordReset(ForgotPasswordRequest request);

    /** Step two: redeems the code, inside the same tenant that issued it, and sets the password. */
    CoopResponse resetPassword(ResetPasswordRequest request);
}
