-- =====================================================================
-- COOPR8 Phase 2 / V2 -- Tenant-isolation constraints and tenant-scoped OTP.
-- =====================================================================
--
-- WHAT THIS MIGRATION IS FOR
--   Phase 2 makes tenant isolation structural. Application code already
--   scopes every tenant-owned query by organization_id; this file adds the
--   database guarantees that code depends on, and that no amount of
--   application care can provide on its own:
--
--     1. organizations.ledger_prefix -- letters only, and GLOBALLY unique.
--        A bare membership number ('CBMC0001') is decomposed into prefix +
--        sequence to discover which tenant a login belongs to. That is only
--        sound if the prefix has one owner and one shape.       [decision D1]
--
--     2. organizations.slug -- unique ignoring case. The slug is what a
--        tenant URL carries and what every pre-authentication lookup
--        resolves; 'citadel' and 'Citadel' must not be two tenants.
--
--     3. users (organization_id, LOWER(email)) -- unique WITHIN a tenant.
--        An address may belong to a member of several cooperatives, but not
--        to two members of one. Password reset and the payment webhook both
--        resolve a member by (organization, email).             [decision D3]
--
--     4. otp.organization_id and otp.purpose -- NOT NULL. A one-time code
--        now belongs to exactly one tenant and one flow, so a code issued
--        at cooperative A cannot reset an account at cooperative B, and a
--        code obtained from the signup endpoint cannot be replayed against
--        password reset.
--
-- IT REFUSES RATHER THAN REPAIRS
--   Sections 1-5 are pre-flight guards. If existing rows already violate a
--   constraint, the guard RAISEs and names the rows, and the whole migration
--   rolls back (PostgreSQL DDL is transactional, and Flyway runs this file
--   in one transaction). Nothing is repaired on the way past: silently
--   rewriting production data -- upper-casing a prefix that then collides
--   with another tenant's, or picking which of two members keeps an email
--   address -- is a decision for a person, not for a migration.
--
--   Run docs/phase2-pre-migration-survey.sql FIRST. It is read-only and
--   reports exactly these conflicts, so they are found before a deployment
--   rather than during one.
--
-- MEMBER NUMBERS ARE NOT REWRITTEN
--   No statement here touches users.ledgerid. Existing CBMC0001-style
--   numbers stay exactly as they are and keep working, because the
--   organization that issued them keeps the prefix that generated them.
--
-- THE ONE DESTRUCTIVE STATEMENT, AND WHY
--   Section 6 deletes outstanding rows from `otp`. Their tenant and purpose
--   cannot be inferred -- an email address does not identify a tenant (that
--   ambiguity is the bug being fixed) and nothing recorded which flow asked
--   for the code. A code lives ten minutes, carries no history, and a member
--   mid-flow simply requests another. Every member re-authenticates after
--   this deployment anyway (decision D2, rotated signing key).
--
-- V1 IS NOT MODIFIED. Forward-only and additive, except as stated above.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. GUARD -- ledger_prefix must be letters only and non-blank.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    offending text;
    offenders integer;
BEGIN
    SELECT count(*),
           string_agg(format('id=%s slug=%s prefix=%L', id, slug, ledger_prefix), '; ' ORDER BY id)
      INTO offenders, offending
      FROM organizations
     WHERE ledger_prefix IS NULL
        OR btrim(ledger_prefix) = ''
        OR ledger_prefix !~ '^[A-Za-z]+$';

    IF offenders > 0 THEN
        RAISE EXCEPTION
            'COOPR8 V2 stopped: % organization(s) have a ledger_prefix that is not letters-only: %',
            offenders, offending
            USING HINT = 'A ledger prefix must match ^[A-Za-z]+$ so a membership number can be '
                         'split into prefix + sequence. Correct these organizations by hand, then '
                         're-run the migration. Do not change any member ledgerid.';
    END IF;
END $$;


-- ---------------------------------------------------------------------
-- 2. GUARD -- ledger_prefix must already be in canonical (upper-case) form.
-- ---------------------------------------------------------------------
-- Deliberately not fixed here: upper-casing is a write to production data
-- and can create the collision that section 3 checks for. The operator does
-- it explicitly, after confirming section 3 is clean.
DO $$
DECLARE
    offending text;
BEGIN
    SELECT string_agg(format('id=%s slug=%s stored=%L canonical=%L',
                             id, slug, ledger_prefix, upper(btrim(ledger_prefix))),
                      '; ' ORDER BY id)
      INTO offending
      FROM organizations
     WHERE ledger_prefix IS NOT NULL
       AND ledger_prefix <> upper(btrim(ledger_prefix));

    IF offending IS NOT NULL THEN
        RAISE EXCEPTION
            'COOPR8 V2 stopped: organization ledger_prefix values are not in canonical upper-case form: %',
            offending
            USING HINT = 'Confirm no two prefixes collide once folded (survey section 3), then run: '
                         'UPDATE organizations SET ledger_prefix = upper(btrim(ledger_prefix)) '
                         'WHERE ledger_prefix <> upper(btrim(ledger_prefix)); '
                         'Member ledgerid values must NOT be changed.';
    END IF;
END $$;


-- ---------------------------------------------------------------------
-- 3. GUARD -- ledger_prefix must be globally unique (ignoring case).
-- ---------------------------------------------------------------------
DO $$
DECLARE
    offending text;
BEGIN
    SELECT string_agg(line, '; ')
      INTO offending
      FROM (
            SELECT format('prefix=%L claimed by %s',
                          upper(btrim(ledger_prefix)),
                          string_agg(id::text || ':' || slug, ',' ORDER BY id)) AS line
              FROM organizations
             WHERE ledger_prefix IS NOT NULL
               AND btrim(ledger_prefix) <> ''
             GROUP BY upper(btrim(ledger_prefix))
            HAVING count(*) > 1
           ) AS duplicates;

    IF offending IS NOT NULL THEN
        RAISE EXCEPTION 'COOPR8 V2 stopped: ledger_prefix is claimed by more than one organization: %',
            offending
            USING HINT = 'The prefix resolves a bare membership number to a tenant, so it must have '
                         'exactly one owner. Decide which organization keeps it and give the other a '
                         'new prefix. Members already numbered under the reassigned prefix keep their '
                         'existing ledgerid and must sign in through their own /o/{slug}/login URL.';
    END IF;
END $$;


-- ---------------------------------------------------------------------
-- 4. GUARD -- slug must be unique ignoring case.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    offending text;
BEGIN
    SELECT string_agg(line, '; ')
      INTO offending
      FROM (
            SELECT format('slug=%L claimed by %s',
                          lower(btrim(slug)),
                          string_agg(id::text || ':' || slug, ',' ORDER BY id)) AS line
              FROM organizations
             GROUP BY lower(btrim(slug))
            HAVING count(*) > 1
           ) AS duplicates;

    IF offending IS NOT NULL THEN
        RAISE EXCEPTION 'COOPR8 V2 stopped: organization slugs differ only by case: %',
            offending
            USING HINT = 'Slug lookups are case-insensitive, so these tenants are not distinguishable '
                         'by URL. Rename one of them.';
    END IF;
END $$;


-- ---------------------------------------------------------------------
-- 5. GUARD -- email must be unique within an organization.  [decision D3]
-- ---------------------------------------------------------------------
-- Grouped exactly as the index in section 8 is defined, so this guard fails
-- precisely when that index would -- but with the member rows named, which
-- a bare unique-index violation would not tell you. (The read-only survey
-- groups on btrim(email) as well, deliberately reporting a superset.)
DO $$
DECLARE
    offending text;
BEGIN
    SELECT string_agg(line, '; ')
      INTO offending
      FROM (
            SELECT format('organization_id=%s email=%L held by member(s) %s',
                          organization_id,
                          lower(email),
                          string_agg(id::text || ':' || coalesce(ledgerid, 'no-ledger-id'),
                                     ',' ORDER BY id)) AS line
              FROM users
             WHERE email IS NOT NULL
               AND email <> ''
             GROUP BY organization_id, lower(email)
            HAVING count(*) > 1
           ) AS duplicates;

    IF offending IS NOT NULL THEN
        RAISE EXCEPTION
            'COOPR8 V2 stopped: the same email address is held by more than one member of the same organization: %',
            offending
            USING HINT = 'Password reset and the payment webhook both resolve a member by '
                         '(organization, email), so a duplicate sends a reset code or a card payment '
                         'to whichever row the database returns first. Decide which member keeps the '
                         'address and correct the others, then re-run. An address may still be reused '
                         'freely across DIFFERENT organizations.';
    END IF;
END $$;


-- =====================================================================
-- Pre-flight passed. Everything below changes the schema.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 6. otp -- becomes tenant-scoped and purpose-scoped.
-- ---------------------------------------------------------------------
-- Outstanding codes are removed first: neither column can be back-filled
-- (see the header). The table is then empty, so both columns can be added
-- NOT NULL directly. ACCESS EXCLUSIVE is held throughout, so nothing can
-- insert a tenant-less row in between.
DO $$
DECLARE
    removed bigint;
BEGIN
    DELETE FROM otp;
    GET DIAGNOSTICS removed = ROW_COUNT;
    RAISE NOTICE 'COOPR8 V2: deleted % outstanding one-time code(s); they cannot be assigned a '
                 'tenant or a purpose. Members mid-flow request a new code.', removed;
END $$;

ALTER TABLE otp ADD COLUMN organization_id bigint      NOT NULL;
ALTER TABLE otp ADD COLUMN purpose         varchar(32) NOT NULL;

ALTER TABLE otp
    ADD CONSTRAINT fk_otp_organization FOREIGN KEY (organization_id) REFERENCES organizations (id);

-- Mirrors the OtpPurpose enum. Adding a constant later requires altering
-- this constraint -- Hibernate `validate` ignores CHECKs and will not warn.
ALTER TABLE otp
    ADD CONSTRAINT ck_otp_purpose CHECK (purpose IN ('SIGNUP', 'PASSWORD_RESET', 'PASSWORD_CHANGE'));

-- Tenant discriminator index, matching every other tenant-owned table.
CREATE INDEX ix_otp_organization_id ON otp (organization_id);

-- At most one live code per (tenant, address, flow). OTPServiceImpl deletes
-- the previous code before issuing a new one; this makes that structural
-- rather than a convention, because a second surviving code is a second
-- valid credential, and the single-result lookup would fail on two rows.
CREATE UNIQUE INDEX ux_otp_org_email_purpose
    ON otp (organization_id, lower(email), purpose)
    WHERE email IS NOT NULL AND email <> '';


-- ---------------------------------------------------------------------
-- 7. organizations -- prefix shape, prefix uniqueness, slug uniqueness.
-- ---------------------------------------------------------------------
-- Letters only, upper-case: the canonical form the application writes
-- (LedgerIDGen.normalizePrefix) and the only form that makes a membership
-- number splittable.
ALTER TABLE organizations
    ADD CONSTRAINT ck_organizations_ledger_prefix CHECK (ledger_prefix ~ '^[A-Z]+$');

-- GLOBAL uniqueness. Expressed on upper(...) rather than on the bare column
-- so it still holds if the CHECK above is ever relaxed to allow mixed case.
CREATE UNIQUE INDEX ux_organizations_ledger_prefix ON organizations (upper(ledger_prefix));

-- V1 already has UNIQUE (slug); this adds the case-insensitive form, which
-- is how the slug is actually looked up.
CREATE UNIQUE INDEX ux_organizations_slug_lower ON organizations (lower(slug));


-- ---------------------------------------------------------------------
-- 8. users -- per-tenant email uniqueness.  [decision D3]
-- ---------------------------------------------------------------------
-- Partial: a NULL or blank address is missing data, not a claim on an
-- identity, and several members may legitimately have none. (PostgreSQL
-- already treats NULLs as distinct; the `<> ''` half is what stops blank
-- strings from colliding.) Same shape as V1's ux_users_org_psn.
--
-- Scoped to the organization, NOT global: the same person may be a member
-- of two cooperatives on the platform, and a global constraint would make
-- the second membership impossible.
CREATE UNIQUE INDEX ux_users_org_email_lower
    ON users (organization_id, lower(email))
    WHERE email IS NOT NULL AND email <> '';

-- Non-unique, for the tenant-scoped phone lookups: guarantor nomination
-- resolves a member by phone within one cooperative, and the duplicate
-- checks on create/update query the same pair. No unique constraint is
-- added -- existing data may contain duplicates (survey section 8) and
-- de-duplicating members is not a migration's decision to make.
CREATE INDEX ix_users_org_phone ON users (organization_id, phone);
