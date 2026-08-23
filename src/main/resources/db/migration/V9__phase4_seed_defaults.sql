-- =====================================================================
-- COOPR8 Phase 4 / V9 -- Neutral configuration defaults for existing organizations.
-- =====================================================================
--
-- MIGRATION NUMBERING -- A DELIBERATE SWAP FROM THE ARCHITECTURE DOCUMENT
--   docs/phase4-tenant-business-configuration.md §9 planned V9 = historical
--   terms (Class B columns) and V10 = seed defaults. Those two numbers are
--   SWAPPED here, and the reason is Flyway rather than taste: Class B columns
--   belong to Stage 3, seeding belongs to Stage 1, and Flyway runs with
--   out-of-order disabled. Numbering the Stage 1 seed V10 would make the
--   later Stage 3 file V9 -- a version below the applied high-water mark,
--   which Flyway refuses. Class B therefore becomes V10.
--
-- WHAT THIS MIGRATION DOES
--   Gives every EXISTING organization exactly one row in each of the four
--   singleton configuration tables, carrying the values that reproduce what
--   the platform does today. Applying it changes no behaviour, because the
--   defaults ARE the current behaviour and because nothing reads them yet.
--
-- IT INVENTS NO BUSINESS RULES
--   Every value below was read off the current code, not chosen:
--
--     interest_method 'NONE', interest_rate 0.000
--         LoanServiceImpl.approveLoan computes repay_amount = amount /
--         duration. No interest, therefore no rate.
--     required_guarantors 2
--         Loan has exactly two guarantor columns, both used.
--     share_price 1.00
--         `shares` holds a naira amount and no unit concept, so units ==
--         naira. 1.00 is the only price that keeps that true.
--     approval_required true
--         SharesServiceImpl always requires approval.
--     withdrawal_allowed true
--         SharesServiceImpl permits withdrawal (amount > 0, balance >= amount).
--     allow_partial_repayment false, allow_overpayment false,
--     settlement_tolerance 0.00
--         RepayServiceImpl requires an exact multiple of the instalment.
--     require_email true, require_phone true
--         @NotBlank on UserRequest.email and UserRequest.phone.
--     require_psn false, require_passport false, require_next_of_kin false
--         No validation on those fields today.
--     auto_activate_members false, default_member_status 'PENDING'
--         createAccount(request) delegates to createAccount(request,
--         "PENDING"); activation is a separate admin action.
--
--   Every NULL bound (min/max loan amount, tenure, purchase, withdrawal) is
--   left NULL because no such bound exists today. NULL means "no bound"; a
--   zero would mean a bound of zero, which forbids the transaction outright.
--
-- NO LOAN TYPES AND NO SAVINGS PLANS ARE SEEDED
--   The two collection tables are left EMPTY, deliberately. Seeding
--   {real, soft, material} for every cooperative on the platform would invent
--   three products for organizations that never offered them -- and those
--   three names are Citadel's history, not a platform default. There is no
--   default Citadel organization and no Citadel seed data.
--
--   Deriving each cooperative's real product list from the DISTINCT loan.type
--   values it has actually issued is the right way to populate these, and it
--   is a DATA migration, not a schema one: it belongs in the same controlled,
--   surveyed, explicitly-approved step as the historical-terms backfill, and
--   must never run automatically against production.
--
--   Consequence for Stage 2, stated here so it is not discovered later: a
--   configuration read must treat "this tenant has no loan types" as "type
--   enforcement is not configured yet" and keep accepting today's free-text
--   Loan.type. Enforcement begins when an administrator defines products, not
--   when this migration runs. A Stage 2 that hard-requires a configured type
--   would stop every member of every cooperative from borrowing.
--
-- NO AUDIT ROWS ARE WRITTEN FOR THIS SEED
--   organization_config_audit.actor_user_id is NOT NULL and a migration has
--   no actor. Attributing these values to a real administrator would be a
--   false audit record, which is worse than an absent one. The record that
--   these defaults arrived is this file, in version control. The audit table
--   begins recording at the first change a human makes.
--
-- IT MODIFIES NO EXISTING ROW
--   Every statement is an INSERT into a table created in V3-V8. No UPDATE, no
--   DELETE, and nothing reads or writes users, loan, saving, shares or repay.
--   Member balances, loan balances, repayment history, savings and shares are
--   untouched.
--
-- IF THERE ARE NO ORGANIZATIONS, THIS FILE DOES NOTHING
--   Every statement is INSERT ... SELECT over `organizations`. On an empty
--   database it inserts zero rows and succeeds.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. Loan configuration -- one row per organization.
-- ---------------------------------------------------------------------
-- NOT EXISTS rather than ON CONFLICT: it is idempotent without depending on
-- the unique constraint's name, and it makes the intent ("organizations that
-- do not have one yet") readable.
INSERT INTO organization_loan_config (organization_id,
                                      interest_method,
                                      interest_rate,
                                      required_guarantors,
                                      created_at,
                                      updated_at)
SELECT o.id, 'NONE', 0.000, 2, now(), now()
  FROM organizations o
 WHERE NOT EXISTS (SELECT 1
                     FROM organization_loan_config c
                    WHERE c.organization_id = o.id);


-- ---------------------------------------------------------------------
-- 2. Shares configuration -- one row per organization.
-- ---------------------------------------------------------------------
INSERT INTO organization_shares_config (organization_id,
                                        share_price,
                                        approval_required,
                                        withdrawal_allowed,
                                        created_at,
                                        updated_at)
SELECT o.id, 1.00, true, true, now(), now()
  FROM organizations o
 WHERE NOT EXISTS (SELECT 1
                     FROM organization_shares_config c
                    WHERE c.organization_id = o.id);


-- ---------------------------------------------------------------------
-- 3. Repayment configuration -- one row per organization.
-- ---------------------------------------------------------------------
INSERT INTO organization_repayment_config (organization_id,
                                           allow_partial_repayment,
                                           allow_overpayment,
                                           settlement_tolerance,
                                           created_at,
                                           updated_at)
SELECT o.id, false, false, 0.00, now(), now()
  FROM organizations o
 WHERE NOT EXISTS (SELECT 1
                     FROM organization_repayment_config c
                    WHERE c.organization_id = o.id);


-- ---------------------------------------------------------------------
-- 4. Membership configuration -- one row per organization.
-- ---------------------------------------------------------------------
INSERT INTO organization_membership_config (organization_id,
                                            require_email,
                                            require_phone,
                                            require_psn,
                                            require_passport,
                                            require_next_of_kin,
                                            auto_activate_members,
                                            default_member_status,
                                            created_at,
                                            updated_at)
SELECT o.id, true, true, false, false, false, false, 'PENDING', now(), now()
  FROM organizations o
 WHERE NOT EXISTS (SELECT 1
                     FROM organization_membership_config c
                    WHERE c.organization_id = o.id);


-- ---------------------------------------------------------------------
-- 5. VERIFY -- the cardinality invariant, checked rather than assumed.
-- ---------------------------------------------------------------------
-- "Exactly one row per organization in each singleton table" is the invariant
-- every Stage 2 read will depend on. The UNIQUE constraints stop a SECOND
-- row; nothing above stops ZERO rows if one of these INSERTs were later
-- edited into being wrong. This asserts it, and Flyway runs the file in one
-- transaction, so a failure rolls the whole seed back.
DO $$
DECLARE
    organizations_total   bigint;
    missing_loan          bigint;
    missing_shares        bigint;
    missing_repayment     bigint;
    missing_membership    bigint;
BEGIN
    SELECT count(*) INTO organizations_total FROM organizations;

    SELECT count(*) INTO missing_loan
      FROM organizations o
     WHERE NOT EXISTS (SELECT 1 FROM organization_loan_config c
                        WHERE c.organization_id = o.id);
    SELECT count(*) INTO missing_shares
      FROM organizations o
     WHERE NOT EXISTS (SELECT 1 FROM organization_shares_config c
                        WHERE c.organization_id = o.id);
    SELECT count(*) INTO missing_repayment
      FROM organizations o
     WHERE NOT EXISTS (SELECT 1 FROM organization_repayment_config c
                        WHERE c.organization_id = o.id);
    SELECT count(*) INTO missing_membership
      FROM organizations o
     WHERE NOT EXISTS (SELECT 1 FROM organization_membership_config c
                        WHERE c.organization_id = o.id);

    IF missing_loan + missing_shares + missing_repayment + missing_membership > 0 THEN
        RAISE EXCEPTION
            'COOPR8 V9 stopped: configuration rows are missing after seeding '
            '(loan=%, shares=%, repayment=%, membership=%, organizations=%).',
            missing_loan, missing_shares, missing_repayment, missing_membership,
            organizations_total
            USING HINT = 'Every organization must have exactly one row in each singleton '
                         'configuration table before any code reads configuration. The seed '
                         'has been rolled back.';
    END IF;

    RAISE NOTICE 'COOPR8 V9: % organization(s) now hold neutral configuration defaults in all '
                 'four singleton tables. No loan types or savings plans were seeded, and no '
                 'existing row was modified.', organizations_total;
END $$;
