package com.invo.coopr8.payment;

/**
 * Where a cooperative wants its members' payments to land.
 *
 * <p><strong>Not a credential.</strong> Every field here is a destination -- the bank and account a
 * cooperative already owns. COOPR8 never asks a cooperative for a provider secret key, and there is
 * nowhere to put one if it were offered: the platform's provider secret is a server-side application
 * secret, and a tenant supplying its own would make every payment route through credentials COOPR8
 * cannot vouch for.
 *
 * <p>Which cooperative this belongs to is deliberately absent. It comes from the authenticated
 * administrator, never from the request -- see {@link TenantPaymentSetupService}.
 *
 * @param bankCode      the settlement bank's code, as the provider enumerates banks.
 * @param accountNumber the cooperative's own account number at that bank.
 * @param accountName   the account name, for display and reconciliation. Optional.
 */
public record TenantSettlementDetails(String bankCode, String accountNumber, String accountName) {
}
