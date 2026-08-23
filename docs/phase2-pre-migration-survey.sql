-- =====================================================================
-- COOPR8 Phase 2 -- PRE-MIGRATION SURVEY (READ ONLY)
-- =====================================================================
--
-- RUN THIS BEFORE V2__phase2_tenant_isolation.sql, against the database
-- V2 will be applied to. Every statement below is a SELECT. There is no
-- INSERT, UPDATE, DELETE, ALTER, CREATE or DROP anywhere in this file, so
-- it is safe to run against production and it changes nothing.
--
-- WHY IT EXISTS
--   V2 adds constraints that existing rows may already violate:
--     * organizations.ledger_prefix -- letters only, and globally unique
--     * organizations.slug          -- unique ignoring case
--     * users (organization_id, LOWER(email)) -- unique within a tenant
--   A migration that "fixed" such rows on the way past would silently
--   rewrite production data. V2 therefore REFUSES to run and names the
--   offending rows instead. This survey shows you the same conflicts up
--   front, so nothing is discovered during a deployment.
--
-- HOW TO RUN
--   psql "$DB_URL" -f coopr8back/docs/phase2-pre-migration-survey.sql
--   (or paste section by section into any SQL console)
--
-- HOW TO READ THE RESULT
--   Sections 1-7 must each return ZERO rows, except the ones explicitly
--   marked INFORMATIONAL. Any row returned by a "CONFLICT" section is
--   something a person has to decide about: V2 will not start while it
--   exists, and this survey deliberately does not propose a fix that runs
--   itself. Bring the rows to the Phase 2 report and agree the resolution
--   before applying V2.
--
-- Section 0 is context, not a check.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 0. CONTEXT (informational) -- what is on this database at all.
-- ---------------------------------------------------------------------
-- Confirm this is the database you think it is before reading anything
-- else, and confirm the existing Citadel organization still carries
-- ledger_prefix = 'CBMC'. Existing CBMC member numbers are NOT rewritten
-- by Phase 2; they keep working because the organization keeps the prefix
-- that generated them.
SELECT current_database()          AS database_name,
       current_user               AS connected_as,
       inet_server_addr()         AS server_address,
       version()                  AS server_version;

SELECT id,
       name,
       slug,
       ledger_prefix,
       status,
       created_at
FROM organizations
ORDER BY id;

-- Row counts, so a survey that returns nothing can be told apart from a
-- survey run against an empty database.
SELECT 'organizations' AS table_name, count(*) AS rows FROM organizations
UNION ALL SELECT 'users',        count(*) FROM users
UNION ALL SELECT 'loan',         count(*) FROM loan
UNION ALL SELECT 'repay',        count(*) FROM repay
UNION ALL SELECT 'saving',       count(*) FROM saving
UNION ALL SELECT 'shares',       count(*) FROM shares
UNION ALL SELECT 'notification', count(*) FROM notification
UNION ALL SELECT 'otp',          count(*) FROM otp
ORDER BY table_name;


-- ---------------------------------------------------------------------
-- 1. CONFLICT -- ledger_prefix that is not letters-only, or is blank.
-- ---------------------------------------------------------------------
-- Decision D1: the prefix is letters only. That is what makes a member
-- number decomposable ('CBMC0001' -> prefix 'CBMC' + sequence '0001');
-- with digits allowed in the prefix there is no way to tell where the
-- prefix ends, and tenant discovery from a membership number becomes a
-- guess. V2 adds CHECK (ledger_prefix ~ '^[A-Z]+$').
--
-- MUST RETURN ZERO ROWS.
SELECT id,
       name,
       slug,
       ledger_prefix,
       CASE
           WHEN ledger_prefix IS NULL          THEN 'NULL prefix'
           WHEN btrim(ledger_prefix) = ''      THEN 'blank prefix'
           WHEN ledger_prefix <> btrim(ledger_prefix)
                                               THEN 'leading/trailing whitespace'
           WHEN ledger_prefix !~ '^[A-Za-z]+$' THEN 'contains non-letters'
           ELSE 'other'
       END AS problem
FROM organizations
WHERE ledger_prefix IS NULL
   OR btrim(ledger_prefix) = ''
   OR ledger_prefix !~ '^[A-Za-z]+$'
ORDER BY id;


-- ---------------------------------------------------------------------
-- 2. CONFLICT -- ledger_prefix not stored in canonical (upper-case) form.
-- ---------------------------------------------------------------------
-- Decision D1 requires one consistent normalisation, and the application
-- normalises to UPPER CASE (LedgerIDGen.normalizePrefix). V2 does NOT
-- upper-case existing values for you: that is a write to production data,
-- and it can collide with another organization's prefix ('abc' + 'ABC'
-- become the same prefix once folded). If this section returns rows,
-- check section 3 first, then upper-case them deliberately:
--
--     -- run ONLY after section 3 returns zero rows, and note it in the report
--     UPDATE organizations SET ledger_prefix = upper(btrim(ledger_prefix))
--     WHERE ledger_prefix <> upper(btrim(ledger_prefix));
--
-- Member ledger IDs are NOT touched by that update. 'CBMC0001' stays
-- 'CBMC0001'; only the organization's own prefix column is canonicalised,
-- and login resolves the prefix case-insensitively.
--
-- MUST RETURN ZERO ROWS.
SELECT id,
       name,
       slug,
       ledger_prefix                       AS stored_prefix,
       upper(btrim(ledger_prefix))         AS canonical_prefix
FROM organizations
WHERE ledger_prefix IS NOT NULL
  AND ledger_prefix <> upper(btrim(ledger_prefix))
ORDER BY id;


-- ---------------------------------------------------------------------
-- 3. CONFLICT -- two organizations claiming the same ledger prefix.
-- ---------------------------------------------------------------------
-- Decision D1: the prefix is GLOBALLY unique, because it is what resolves
-- a bare membership number to a tenant at login. Two organizations both
-- claiming 'ABC' would make 'ABC0001' ambiguous -- and an ambiguous prefix
-- fails closed today (TenantResolver logs and resolves nothing), so those
-- members simply cannot sign in without their cooperative's own URL.
-- Compared case-insensitively, since section 2's normalisation folds case.
--
-- MUST RETURN ZERO ROWS.
SELECT upper(btrim(ledger_prefix))                     AS canonical_prefix,
       count(*)                                        AS organizations_claiming_it,
       string_agg(id::text || ':' || slug, ', ' ORDER BY id) AS organization_id_and_slug
FROM organizations
WHERE ledger_prefix IS NOT NULL
  AND btrim(ledger_prefix) <> ''
GROUP BY upper(btrim(ledger_prefix))
HAVING count(*) > 1
ORDER BY canonical_prefix;


-- ---------------------------------------------------------------------
-- 4. CONFLICT -- two organizations whose slugs differ only by case.
-- ---------------------------------------------------------------------
-- The slug is what a tenant URL carries ('/o/citadel/login') and what
-- pre-authentication lookups resolve. V1 already has UNIQUE (slug), but
-- that lets 'citadel' and 'Citadel' coexist as two tenants while the
-- lookup is case-insensitive -- so one of them becomes unreachable and
-- which one is unpredictable. V2 adds UNIQUE (LOWER(slug)).
--
-- MUST RETURN ZERO ROWS.
SELECT lower(btrim(slug))                              AS canonical_slug,
       count(*)                                        AS organizations_claiming_it,
       string_agg(id::text || ':' || slug, ', ' ORDER BY id) AS organization_id_and_slug
FROM organizations
GROUP BY lower(btrim(slug))
HAVING count(*) > 1
ORDER BY canonical_slug;


-- ---------------------------------------------------------------------
-- 5. CONFLICT -- duplicate email within one organization  [DECISION D3]
-- ---------------------------------------------------------------------
-- Decision D3: an email address may exist in several organizations, but
-- must be unique WITHIN one. This is the survey the decision made a
-- precondition ("find duplicates, report them, agree the resolution, only
-- then apply the constraint").
--
-- Why it matters beyond tidiness: password reset and the Paystack webhook
-- both find a member by (organization, email). With two matching rows the
-- lookup returns whichever the database offers first, so a reset code or a
-- card payment can land on the wrong member of the same cooperative.
--
-- Blank and NULL addresses are excluded here and by the V2 index -- they
-- are missing data, not a claim on an identity. Section 6 counts them.
--
-- MUST RETURN ZERO ROWS. Each row is a decision: which of these members
-- keeps the address, and what the others get instead.
SELECT u.organization_id,
       o.slug                                          AS organization_slug,
       lower(btrim(u.email))                           AS canonical_email,
       count(*)                                        AS member_rows,
       string_agg(u.id::text || ':' || coalesce(u.ledgerid, '(no ledger id)')
                  || ':' || coalesce(u.status, '(no status)'),
                  ', ' ORDER BY u.id)                  AS member_id_ledger_status
FROM users u
JOIN organizations o ON o.id = u.organization_id
WHERE u.email IS NOT NULL
  AND btrim(u.email) <> ''
GROUP BY u.organization_id, o.slug, lower(btrim(u.email))
HAVING count(*) > 1
ORDER BY u.organization_id, canonical_email;


-- ---------------------------------------------------------------------
-- 6. INFORMATIONAL -- members with no usable email address.
-- ---------------------------------------------------------------------
-- These rows do NOT block V2: the unique index is partial
-- (WHERE email IS NOT NULL AND email <> ''), so missing addresses never
-- collide with each other. They are listed because such a member cannot
-- use the forgot-password flow at all -- there is nowhere to send the
-- code -- and an administrator has to add an address first.
SELECT u.organization_id,
       o.slug          AS organization_slug,
       count(*)        AS members_without_email
FROM users u
JOIN organizations o ON o.id = u.organization_id
WHERE u.email IS NULL
   OR btrim(u.email) = ''
GROUP BY u.organization_id, o.slug
ORDER BY u.organization_id;


-- ---------------------------------------------------------------------
-- 7. INFORMATIONAL -- member numbers that do not match their own
--    organization's prefix.
-- ---------------------------------------------------------------------
-- Nothing in V2 enforces this, and no member number is rewritten. It is
-- surveyed because the login fallback for a bare membership number reads
-- the prefix out of the number itself and resolves the tenant from it: a
-- member whose number carries a different cooperative's prefix (or no
-- prefix at all) can only sign in through their own /o/{slug}/login URL.
-- Worth knowing before the frontend cuts over to per-tenant URLs.
SELECT u.organization_id,
       o.slug                     AS organization_slug,
       o.ledger_prefix            AS organization_prefix,
       u.id                       AS member_id,
       u.ledgerid                 AS member_number,
       CASE
           WHEN u.ledgerid IS NULL OR btrim(u.ledgerid) = ''       THEN 'no member number yet'
           WHEN u.ledgerid !~ '^[A-Za-z]+[0-9]+$'                  THEN 'not LETTERS+DIGITS'
           ELSE 'prefix belongs to another organization'
       END                        AS problem
FROM users u
JOIN organizations o ON o.id = u.organization_id
WHERE u.ledgerid IS NULL
   OR btrim(u.ledgerid) = ''
   OR u.ledgerid !~ '^[A-Za-z]+[0-9]+$'
   OR upper((regexp_match(btrim(u.ledgerid), '^([A-Za-z]+)[0-9]+$'))[1])
      IS DISTINCT FROM upper(btrim(o.ledger_prefix))
ORDER BY u.organization_id, u.id;


-- ---------------------------------------------------------------------
-- 8. INFORMATIONAL -- duplicate phone numbers within one organization.
-- ---------------------------------------------------------------------
-- V2 adds no constraint here, so these do not block the migration. They
-- are surveyed because a loan applicant nominates guarantors BY PHONE
-- NUMBER: with two members of one cooperative sharing a number, which of
-- them is put on the hook is decided by row order. The application already
-- rejects a new duplicate; these are pre-existing rows. Cleaning them up
-- is a separate, non-security change -- raise it in the Phase 2 report
-- rather than fixing it inside a migration.
SELECT u.organization_id,
       o.slug                                          AS organization_slug,
       btrim(u.phone)                                  AS phone,
       count(*)                                        AS member_rows,
       string_agg(u.id::text || ':' || coalesce(u.ledgerid, '(no ledger id)'),
                  ', ' ORDER BY u.id)                  AS member_id_and_ledger
FROM users u
JOIN organizations o ON o.id = u.organization_id
WHERE u.phone IS NOT NULL
  AND btrim(u.phone) <> ''
GROUP BY u.organization_id, o.slug, btrim(u.phone)
HAVING count(*) > 1
ORDER BY u.organization_id, phone;


-- ---------------------------------------------------------------------
-- 9. INFORMATIONAL -- outstanding one-time codes that V2 will DELETE.
-- ---------------------------------------------------------------------
-- V2 makes otp.organization_id and otp.purpose NOT NULL. Existing rows
-- have neither, and neither can be inferred: the email address on an OTP
-- row does not identify a tenant (the same address may be a member of two
-- cooperatives), and nothing records which flow asked for the code.
-- Guessing either would be exactly the cross-tenant confusion Phase 2 is
-- closing, so V2 deletes the outstanding rows.
--
-- This is the one destructive step in V2 and it is safe: a code lives ten
-- minutes, carries no history, and a member who was mid-flow simply
-- requests another. Every member has to sign in again after this
-- deployment anyway (decision D2).
--
-- The codes themselves are NOT selected -- a live OTP is a credential and
-- does not belong in a survey output.
SELECT count(*)                                              AS codes_to_be_deleted,
       count(*) FILTER (WHERE expired_at < now())            AS already_expired,
       count(*) FILTER (WHERE expired_at >= now())           AS still_valid,
       min(created_at)                                       AS oldest,
       max(created_at)                                       AS newest
FROM otp;


-- ---------------------------------------------------------------------
-- 10. CONTEXT -- what V2 will add, so it can be confirmed afterwards.
-- ---------------------------------------------------------------------
-- Before V2 this lists V1's objects only. Re-run it after V2 to confirm
-- ux_organizations_ledger_prefix, ux_organizations_slug_lower,
-- ux_users_org_email_lower, ix_users_org_phone, ix_otp_organization_id and
-- ux_otp_org_email_purpose exist, and that otp now has organization_id and
-- purpose.
SELECT tablename, indexname, indexdef
FROM pg_indexes
WHERE schemaname = current_schema()
  AND tablename IN ('organizations', 'users', 'otp')
ORDER BY tablename, indexname;

SELECT table_name, column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = current_schema()
  AND table_name = 'otp'
ORDER BY ordinal_position;

SELECT version, description, success, installed_on
FROM flyway_schema_history
ORDER BY installed_rank;
