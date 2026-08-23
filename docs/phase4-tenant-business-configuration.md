# Phase 4 — Organization-Specific Business Configuration

**Status: APPROVED ARCHITECTURE. STAGE 1 IMPLEMENTED — STAGES 2-6 NOT STARTED.**

This document is the architecture; §11 is the staging it is built in. As of 2026-08-21 the repository
contains **Stage 1 and nothing beyond it**:

- the eight configuration entities and their eight repositories, registered in all four isolation
  registration points (§8.1–§8.4),
- migrations `V3`–`V10` — the eight tables, the append-only audit trigger, and the neutral seed row per
  organization (note the `V9`/`V10`/`V11` renumbering recorded in §9),
- the offline tests that pin the model and the constraints,
- **no service, controller, DTO or frontend file that reads any of it**, no interest calculation, no
  Class B columns on `loan`/`saving`/`shares`, and no production data change.

So §§1–4 and §§7–9 describe what exists; §§5–6 and §§10–11 from Stage 2 onward remain proposals, and
every column, class and endpoint named in them is still unbuilt. Where a section describes something not
yet built it says so.

**The database test gate is not yet met.** Every `AbstractIntegrationTest` subclass — including the only
test that executes the `CHECK` constraints, the composite foreign keys and the append-only trigger —
skips on the development machine, which has no container runtime. `./mvnw.cmd test
-Dcoopr8.test.require-docker=true` therefore fails on purpose: **201 run, 1 failure
(`DockerRequirementTest`), 60 skipped**, as of 2026-08-21. `.github/workflows/backend-tests.yml` exists
to close that gap on a Docker-capable runner. Stage 2 does not begin until it does.

## 0. Objective and the one rule everything else serves

Every organization must be able to configure its own cooperative business rules without affecting any
other tenant.

That sentence contains two obligations, and the second is the one that is easy to lose:

1. **Independence** — an organization's rules are its own to set.
2. **Non-interference** — setting them cannot reach another tenant, and cannot reach into this
   tenant's own past.

Phase 2 built the machinery for the first half of obligation 2 (tenant isolation: repository-level
scoping, a Hibernate filter as a safety net, and four architecture tests that fail the build when the
pattern is broken). Phase 4 inherits all of it and must extend it, not work around it.

The second half of obligation 2 is new and is the harder half. Configuration is mutable; financial
history is not. A cooperative that lowers its interest rate in March must not thereby rewrite what a
member agreed to in January. This is stated as a hard financial rule in §5 and it is the single most
consequential constraint in this document.

## 1. Why eight tables and not one `organization_settings`

A single wide settings table is explicitly rejected. The reasons are concrete, not stylistic:

- **Two of the domains are collections, not settings.** An organization has *many* loan types and
  *many* savings plans. Those are rows. Forcing them into a key/value or wide-column settings table
  means encoding a list inside a column, which puts them beyond the reach of foreign keys — and
  historical loans need to reference the loan type they were issued under (§5).
- **Types survive.** `interest_rate` as `numeric(6,3)` with a `CHECK` between 0 and 100 is a
  constraint the database enforces. The same value in a `settings.value TEXT` column is a string that
  is hoped to parse.
- **Foreign keys survive.** A loan referencing `organization_loan_type.id` cannot point at a type
  belonging to another organization if the FK is composite on `(organization_id, id)`. There is no
  equivalent guard for a slug stored in a text column.
- **Isolation tests attach to entities and repositories.** The Phase 2 architecture tests enumerate
  tenant-owned repositories and `@Filter`-annotated entities by name (§8). Eight small entities join
  that mechanism eight times. One giant entity joins it once and then hides seven domains' worth of
  access behind a single scoped read, where the tests can no longer distinguish them.
- **Concurrent edits.** Two administrators editing loan rules and savings plans in the same minute
  touch different rows in different tables. In one wide table they contend for one row, and the
  last write silently wins across domains the other admin never opened.

The cost of eight tables is eight migrations and eight repositories. That cost is paid once. The cost
of the wide table is paid on every subsequent change.

## 2. Domain map

Eight tables. `organization_id bigint NOT NULL REFERENCES organizations(id)` on all eight, without
exception.

| # | Domain | Table | Cardinality per org | Governs |
|---|--------|-------|--------------------|---------|
| 1 | Loan configuration | `organization_loan_config` | exactly one | rate, method, min/max amount, allowed tenures, guarantor requirement, eligibility |
| 2 | Loan types | `organization_loan_type` | many | the named products a member may apply for, and per-type overrides |
| 3 | Loan-type exclusions | `organization_loan_type_exclusion` | many | pairs of products a member may not hold at the same time (§10) |
| 4 | Savings plans | `organization_savings_plan` | many | named contribution plans, amounts, frequency, limits |
| 5 | Shares configuration | `organization_shares_config` | exactly one | share price, purchase limits, withdrawal rules, approval requirement |
| 6 | Repayment configuration | `organization_repayment_config` | exactly one | repayment granularity, tolerance, penalty rules, completion rules |
| 7 | Membership / onboarding | `organization_membership_config` | exactly one | required onboarding fields, documents, activation rules |
| 8 | Configuration audit | `organization_config_audit` | many, append-only | who changed what, from what, to what, when, and why |

**Eight tables, seven audited domains, and the arithmetic is not a mistake.** `ConfigDomain` has seven
constants — rows 1–7 — because the audit is row 8 and does not audit itself. Every other table is both a
table and an audited domain, exclusions included: `LOAN_TYPE_EXCLUSION` is its own constant rather than
part of `LOAN_TYPE`, since an exclusion is a rule about a *pair* and attributing a change to either half
alone would misstate what was altered. `OrganizationConfigurationModelTest` asserts exactly this
relationship — one constant per configuration entity, minus the audit — so the two can never drift.

**Cardinality is enforced, not assumed.** The four "exactly one" tables each carry
`UNIQUE (organization_id)`. Without it, a second row is a coin toss over which rule applies, and the
bug surfaces months later as an intermittent interest rate. A unique constraint is invisible to
`ddl-auto=validate` — Hibernate's validation checks tables and columns, not indexes or constraints —
so each one needs a test that asserts the second insert fails (§8.4).

**The two product collections are never hard-deleted from.** A loan type referenced by a historical
loan cannot be removed; a savings plan a member contributed under cannot be removed. Both carry
`active boolean NOT NULL DEFAULT true`, and "delete" in the admin UI means `active = false`.
Deactivation removes a product from the application form. It does not remove it from history.

**Exclusions are the one exception, and for the opposite reason.** Nothing historical points at an
exclusion row — it is a rule consulted at application time, never a term recorded on a loan — so a
cooperative that changes its mind genuinely does remove it, and it carries no `active` flag to soft-delete
with. What the repository refuses is `deleteById`: removal starts from `findByIdAndOrganizationId`, whose
result is already proven to belong to the caller's tenant, because an id taken from a request body and
passed to `deleteById` deletes another cooperative's rule.

## 3. The seven invariants every configuration entity must satisfy

These are mandatory. A configuration entity that misses any one of them is not acceptable, and §8
describes how each is made a build failure rather than a code-review request.

**3.1 `organization_id bigint NOT NULL`.** Nullable would mean "applies to everyone", which is a
cross-tenant rule wearing a tenant column.

**3.2 A foreign key to `organizations`.** `REFERENCES organizations(id)`. For the two collection
tables, additionally declare `UNIQUE (organization_id, id)` so that dependent tables (`loan`,
`saving`) can carry a **composite** foreign key `(organization_id, loan_type_id)` rather than a bare
`loan_type_id`. This is the difference between the database refusing a cross-tenant reference and the
application being trusted to never write one.

**3.3 Covered by tenant isolation.** Each entity is annotated
`@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)` and holds

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "organization_id", nullable = false)
@JsonIgnore
private Organization organization;
```

`@JsonIgnore` is not decoration. Without it, serializing a config entity walks into `Organization` and
returns another tenant's cooperative record shape to the caller. Config responses should in any case
be DTOs (§3.5), and the annotation is the backstop for when someone returns the entity directly.

**3.4 The verified JWT organization is the only source of tenant identity.** Configuration reads and
writes resolve their organization through the existing mechanism in `OrganizationService`:

```java
// OrganizationService.currentOrganizationEntity()
CurrentAuth.requireOrganizationId()   // from the validated token, not the request
  -> organizationRepository.findById(...)   // 401 if the organization is gone
```

For authenticated admin configuration writes this is the whole story: `CurrentAuth.requireAdmin()
.organizationId()`, exactly as `LoanServiceImpl.requireAdministrableLoan` and
`SharesServiceImpl.requireApprovableShare` already do.

Onboarding configuration (domain 6) is the one genuine complication and it must be designed
deliberately, not discovered later. Public signup has no token, so there is no JWT organization to
read. It resolves its tenant through `OrganizationService.resolveRequestedOrganization(slug)`, which
prefers the authenticated organization when a principal exists and only falls back to the requested
slug when there is none. Two consequences:

- **The Hibernate filter is not active on that path.** `TenantContext` is bound only by
  `JwtTokenValidator` — an architecture test enforces that nothing else may call `bind`/`clear` — so
  an unauthenticated request has no bound tenant and `TenantAwareJpaTransactionManager` enables no
  filter. Repository-level scoping is therefore the *only* protection on the onboarding read. This is
  consistent with the existing design (the filter is documented as "a safety net, not the isolation
  mechanism", and Hibernate does not apply filters to primary-key loads at all), but it means the
  onboarding config repository method must take the resolved organization id as an explicit argument
  and must never be a bare `findById`.
- **Phase 4 must not try to bind `TenantContext` during signup** to make the filter engage. That
  would require weakening the architecture rule that only `JwtTokenValidator` may bind, and the rule
  is worth more than the convenience.

**3.5 `organization_id` supplied by the frontend is never trusted.** The enforceable form of this rule
is that the field does not exist to be sent: no configuration request DTO declares an
`organizationId`, `organization`, or `tenant` field of any kind. A request body that cannot carry a
tenant cannot smuggle one. §8.5 proposes a test that asserts this over every `*ConfigRequest` type,
because "we remembered not to read it" is a habit and "the field is not on the class" is a fact.

**3.6 Repository-level tenant scoping.** Every configuration repository follows the established
pattern — `findByOrganizationId`, `findByIdAndOrganizationId`, `findByOrganizationIdAndActiveTrue` —
and declares no unscoped finder. A miss returns `Optional.empty()` and the service answers **404**,
never a message distinguishing "not yours" from "does not exist", per the existing cross-tenant
convention.

**3.7 Participation in the existing tenant-isolation architecture tests.** Registration in three
allowlists, described in §8. Two of those three registrations are *forced* — the build fails if they
are forgotten — and that is by design.

## 4. The A / B / C classification

Every setting Phase 4 touches belongs to exactly one of three classes. The classification is not
documentation; it decides where the value is stored and who may write it.

### Class A — settings that affect NEW transactions

Live in the configuration tables of §2. Writable by an organization admin. Read at the moment a new
transaction is created or approved, and **never** read again for that transaction afterwards.

Loan rate and method; min and max loan amount; allowed tenures; required guarantor count; eligibility
requirements; loan types; savings plans, amounts and frequency; savings limits; share price; share
purchase limits; withdrawal rules; repayment rules; penalty rules; onboarding requirements;
activation rules.

The defining property of a Class A value is that reading it is a *decision*, made once, at a
timestamp. What that decision produced becomes Class B.

### Class B — historical terms already applied to EXISTING transactions

Live as columns **on the transaction row itself** — `loan`, `saving`, `shares` — written once when the
transaction is created or approved, and thereafter immutable.

Class B is not a copy of configuration for convenience. It is the authoritative record of the
agreement. Once a loan is approved, its interest rate is a property of that loan and no longer a
property of the organization. §5 specifies the columns.

The test of whether the boundary is drawn correctly: **changing every value in all eight config
tables must change no number displayed against any existing approved loan, saving or share.** If any
screen would move, a Class B value is being read from Class A storage.

### Class C — platform and security settings organization admins must never control

Live in code, in environment configuration, or in the platform's own tables. **No column for any of
these may appear in any of the eight tables**, and no admin endpoint may accept them.

| Class C item | Where it lives today | Why it can never be tenant-configurable |
|---|---|---|
| Tenant isolation | `TenantFilter`, repository scoping, `TenantAwareJpaTransactionManager` | An organization that can weaken isolation can read other tenants |
| `organization_id` assignment | `JwtTokenValidator` → `TenantContext`, `CurrentAuth` | It is the identity being enforced; it cannot be an input |
| JWT and security rules | `JwtProvider`, `JwtConstant`, `AppConfig` | Signing, expiry and route authorization are platform trust |
| Password security floor | `UserServiceImpl.STRONG_PASSWORD` | A tenant lowering it lowers it for real members' real money |
| Platform subscription enforcement | platform concern, not yet built | A tenant must not configure its own billing entitlement |
| Platform batch-security limits | `BatchUserUploadService.MAX_ROWS = 200` | A resource-exhaustion bound protects every tenant, not one |
| Any other tenant's data | tenant isolation | — |

A note on the password floor, because it will be asked for: an organization may legitimately want a
*stricter* policy than the platform's. That is a future feature and it is still Class C-adjacent —
the floor may be raised by configuration but never lowered, and the platform regex remains the
minimum. It is out of scope for Phase 4 and should not be quietly added to
`organization_membership_config` as `password_regex`. A tenant-editable regex is a tenant-editable
security control.

## 5. The financial rule: configuration must never silently change history

> Changing current organization configuration must NEVER silently change historical transactions.

This is the constraint that decides whether the phase is trustworthy. Today it is not satisfied,
because there is nothing to satisfy it with: `Loan` has no interest field at all, and `repayAmount` is
derived at approval by dividing the principal by the tenure. Once a rate exists and is editable, any
figure recomputed from current configuration becomes retroactive.

### 5.1 The rule in operational terms

- A transaction's financial terms are **written once**, at the moment the organization commits to
  them, and are never recalculated.
- The commit moment for a loan is **approval**, not application. An application records what was
  requested; approval is when the cooperative agrees to terms. A loan sitting in `pending` when the
  rate changes is quoted the new rate, and that is correct — nothing was agreed yet.
- Every displayed figure for an existing transaction is read from the transaction row, not computed
  from config.
- Class B columns are `@Column(updatable = false)` and have no setters on the entity.

### 5.2 Proposed Class B columns on `loan`

`Loan` currently holds `amount`, `repayAmount`, `balance`, `status`, `type` (a `String`), `duration`,
`installmentsPaid`, `startDate`, `EndDate`. There is no interest, no rate, no total repayable, and no
reference to a loan type row. The additions:

| Column | Type | Meaning |
|---|---|---|
| `applied_interest_rate` | `numeric(6,3)` | annual percent actually used, e.g. `12.500` |
| `applied_interest_method` | `varchar(32)` | `NONE` / `FLAT` / `REDUCING_BALANCE` — the method actually used |
| `applied_principal` | `numeric(38,2)` | principal as approved; `amount` may be the *requested* figure |
| `applied_interest_amount` | `numeric(38,2)` | total interest charged over the life of the loan |
| `applied_total_repayable` | `numeric(38,2)` | principal + interest |
| `applied_tenure_months` | `integer` | tenure as approved; `duration` remains the requested figure |
| `applied_installment_amount` | `numeric(38,2)` | the per-installment figure — what `repayAmount` becomes |
| `applied_loan_type_id` | `bigint` | composite FK `(organization_id, applied_loan_type_id)` → `organization_loan_type` |
| `applied_loan_type_name` | `varchar(255)` | the type's name *as it read at approval*, denormalized on purpose |
| `terms_applied_at` | `timestamp` | when terms were fixed |
| `terms_config_audit_id` | `bigint` | FK → `organization_config_audit`, the config generation used |

**Types follow the V1 conventions, verified against `V1__initial_coopr8_schema.sql`, not invented
here.** Every money column in V1 is `numeric(38,2)` — `loan.amount`, `loan.balance`,
`loan.repay_amount`, and the same in `saving`, `shares` and `repay` — so every Class B money column is
`numeric(38,2)` too. Two precisions for the same quantity inside one table is a defect waiting to be
discovered as a rounding difference. Primary keys are `bigint GENERATED BY DEFAULT AS IDENTITY`, not
`bigserial`. Text columns default to `varchar(255)`.

`applied_interest_rate` is the deliberate exception at `numeric(6,3)`: a *rate* is not money, and
three decimals on a percentage is a decision rather than a default.

Two of these deserve their reason stated:

- **`applied_loan_type_name` is denormalized deliberately.** An admin may rename "Soft Loan" to
  "Short-Term Facility". The FK keeps the reference; the stored name keeps the statement truthful
  about what the member signed for. Both are wanted.
- **`terms_config_audit_id` makes the terms auditable, not just recorded.** It answers "which
  configuration generation produced this?" without inferring from timestamps. It is the join between
  §5 and §7.

### 5.3 `saving` and `shares`

`saving` gains `applied_savings_plan_id` (`bigint`, composite FK), `applied_plan_name`
(`varchar(255)`) and `applied_plan_amount` (`numeric(38,2)`). `shares` gains `applied_share_price`
(`numeric(38,2)`) and `applied_units` (`numeric(38,4)` — units may be fractional, and this is the one
new quantity with no V1 precedent to follow). `Shares` today holds only a naira `amount` and has no
concept of a unit or a price, so units are genuinely new.

### 5.4 Backfilling existing rows — and where it is not possible

This is a change to production data and therefore falls under the standing constraint that existing
production data is not silently modified. It requires an explicit go-ahead and a read-only pre-flight
survey first, in the pattern of `docs/phase2-pre-migration-survey.sql`.

The three transaction types are **not** in the same position, and this distinction matters:

- **`loan` — backfill is truthful.** Every already-approved loan was issued at zero interest, because
  interest does not exist in the code. Writing `applied_interest_rate = 0.000`,
  `applied_interest_method = 'NONE'`, `applied_interest_amount = 0.00`,
  `applied_total_repayable = amount`, `applied_principal = amount`,
  `applied_tenure_months = duration`, `applied_installment_amount = repayAmount` records what actually
  happened. This is a statement of fact, not an assumption, and it can be verified against the
  approval arithmetic in `LoanServiceImpl.approveLoan` before it is run.
- **`saving` — partially truthful.** Plan *amounts* exist today as numbers on `User`
  (`savingPlan`, `specialSavingPlan`, `sharePlan`), so an amount can be carried across. There were no
  named plans, so `applied_savings_plan_id` and `applied_plan_name` must stay `NULL`. Do not invent
  plan rows and attach history to them.
- **`shares` — backfill is NOT possible and must not be attempted.** There was never a share price or
  a unit count. Any value written into `applied_share_price` would be fabricated, and a fabricated
  number in a financial column is worse than a null one. These columns stay `NULL` for all pre-Phase-4
  rows, `NULL` means "issued before shares were unitized", and every reader must handle it.

Consequence for the schema: Class B columns are **nullable**, with `NULL` carrying the specific
meaning "pre-configuration era". They are populated unconditionally for transactions created after
Phase 4 ships, and a test should assert that a newly approved loan has none of them null.

### 5.5 Prerequisite defect

Introducing interest makes an existing repayment defect materially worse, so it must be fixed as part
of this work rather than alongside it. `RepayServiceImpl` requires every repayment to be an exact
multiple of `repayAmount`. Because `repayAmount` is rounded `HALF_UP` to 2 decimals at approval, a
₦100,000 loan over 3 months yields ₦33,333.33 × 3 = ₦99,999.99, leaving ₦0.01 that is not a multiple
of ₦33,333.33 — so the loan cannot be completed. Interest makes non-terminating division the normal
case rather than the occasional one. §10 sequences the fix ahead of the interest engine.

## 6. Interest is new financial functionality

> Interest calculation is NEW financial functionality. There is currently no configurable interest
> system. Do not treat it as simply moving an existing setting.

Confirmed against the code. `Loan` has no interest field. `LoanServiceImpl.approveLoan` computes
`repayAmount = amount.divide(BigDecimal.valueOf(durationInMonths), 2, RoundingMode.HALF_UP)` and adds
the bare principal to the member's loan balance. There is no rate to relocate. This is a new financial
engine and carries a new engine's obligations.

### 6.1 What must be specified before any code

- **Methods.** `NONE`, `FLAT` (interest on the original principal for the full tenure), and
  `REDUCING_BALANCE` (interest on the outstanding balance). These produce materially different totals
  for the same nominal rate; the enum is a financial decision, not a technical one.
- **Rate basis.** Annual percent, stated as such, converted to a monthly figure inside the engine.
  Stored as `numeric(6,3)` with `CHECK (applied_interest_rate >= 0 AND applied_interest_rate <= 100)`.
  Note that `ddl-auto=validate` does not verify `CHECK` constraints, so the constraint needs a test
  that asserts the out-of-range insert is rejected.
- **Rounding.** One policy, stated once and applied everywhere: `BigDecimal`, scale 2, `HALF_UP`,
  matching the existing convention in `LoanServiceImpl` and `RepayServiceImpl`. Never `double`.
- **Residual allocation.** Rounding leaves a remainder across installments. The policy must be
  explicit — the recommendation is that all installments are equal and the **final** installment
  absorbs the residual, so `sum(installments) == applied_total_repayable` exactly. This is what makes
  §5.5 fixable rather than perpetually off by a kobo.
- **Penalties.** `organization_repayment_config` governs them, but a penalty is a *new charge event*,
  not a mutation of agreed terms. It belongs in its own row (a `repay`-side or dedicated penalty
  record) and must not be folded into `applied_total_repayable`, which is immutable by §5.1.

### 6.2 Verification standard

The engine is where money is decided, so it is tested independently of Spring, of the database and of
Docker — the pattern `LedgerIDGenTest` (40 tests) and `TenantFilterMappingTest` already follow, and
the reason those suites run today while 38 integration tests skip for want of Docker.

Required coverage: a hand-computed fixture per method; a zero-rate case that must equal the principal
exactly; the `sum(installments) == total` identity across a range of awkward principal/tenure pairs
including the ₦100,000/3 case from §5.5; boundary tenures; rejection of negative and >100 rates; and a
regression asserting that mutating `organization_loan_config` after approval changes nothing on the
approved loan — that last one is the executable form of the §5 rule.

## 7. Configuration audit — append-only

`organization_config_audit` records every configuration change. Required columns:

| Column | Type | Notes |
|---|---|---|
| `id` | `bigint GENERATED BY DEFAULT AS IDENTITY` | matching the V1 convention, not `bigserial` |
| `organization_id` | `bigint NOT NULL` | FK → `organizations`, and the tenant column |
| `actor_user_id` | `bigint NOT NULL` | FK → `users`; the admin who made the change |
| `actor_ledger_id` | `varchar(255)` | denormalized, so the record survives the account being renamed; width matches `users.ledgerid` exactly — a narrower column here would truncate a membership number inside an audit record |
| `config_domain` | `varchar(64) NOT NULL` | which of the seven audited domains, e.g. `LOAN_CONFIG` |
| `setting_key` | `varchar(128) NOT NULL` | the field changed, e.g. `interestRate` |
| `old_value` | `text` | `NULL` on first-ever set, which is meaningful |
| `new_value` | `text` | |
| `effective_date` | `date NOT NULL` | when the change takes effect for new transactions |
| `reason` | `varchar(512)` | required for rate and price changes, optional elsewhere |
| `created_at` | `timestamp NOT NULL` | when it was recorded — distinct from `effective_date` |

`old_value`/`new_value` are text because the table spans domains with different types. That is the one
place text is the right choice: this table is a *record of* values, not a source of them. Nothing reads
these columns to make a decision.

**Append-only is enforced, not intended.** Four layers:

1. The entity has no setters and every `@Column` is `updatable = false`.
2. The repository declares no `delete*` or `save`-then-modify path — only an insert and scoped reads.
3. The ArchUnit suite gains a rule (§8.3) that no class outside the audit writer may call a mutating
   method on the audit repository.
4. Ideally a database trigger rejecting `UPDATE` and `DELETE`. This is the only layer that survives a
   future developer who never reads this document, and it is worth the migration.

**Audit is written in the same transaction as the change.** A configuration change that commits
without its audit row is an unexplained rate change in a financial system. Same transaction, no
best-effort logging, no async queue.

**The audit table is tenant-owned** and joins all the mechanisms in §8 like any other tenant entity.
An audit log readable across tenants would disclose other cooperatives' business rules.

**Values are recorded, secrets are not.** No Class C value is ever written here, so no token, secret
or password material can reach it — which follows from Class C having no representation in
configuration at all.

## 8. Integration with the existing isolation machinery

This is the part that must be got exactly right, because the Phase 2 suite is designed to fail when
this pattern is broken — and two of its checks will fail the moment a Phase 4 entity is added,
*before* any wiring is done. That is the intended behaviour and Phase 4 must satisfy them rather than
relax them.

**All four registrations below were completed in Stage 1.** The code snippets in §8.1 and §8.4 show the
*pre-Phase-4* lists, kept because the point of each subsection is what breaks if the registration is
skipped; each now carries the eight new entries.

### 8.1 `TenantFilterMappingTest.TENANT_OWNED` — a forced registration

`src/test/java/com/invo/coopr8/tenant/TenantFilterMappingTest.java` holds

```java
private static final List<Class<?>> TENANT_OWNED = List.of(
        User.class, Loan.class, Repay.class, Saving.class,
        Shares.class, Notification.class, OTP.class);
```

Its test `noOtherEntityIsSilentlyLeftUnfiltered` asserts that the set of `@Filter`-annotated entities
in the Hibernate metadata **equals** this list — an exact set equality, not a containment check.
Adding a `@Filter` to `OrganizationLoanConfig` without adding the class here **fails the build
immediately**. All eight new entities must be appended, and the suite runs offline (no Spring, no
database, no Docker):

```
mvnw.cmd -Dtest=TenantFilterMappingTest -DfailIfNoTests=false test
```

Also verify `theOrganizationItselfIsNotFiltered` still holds: `Organization` carries the `@FilterDef`
and must remain unfiltered — it is platform-global and is read before authentication.

### 8.2 `TenantIsolationArchitectureTest.TENANT_OWNED_REPOSITORIES`

`src/test/java/com/invo/coopr8/tenant/TenantIsolationArchitectureTest.java` lists seven repositories
by fully-qualified name. The eight new configuration repositories must be added. Doing so subjects
them to four rules, each of which constrains the API they may expose:

1. **No class outside `repository..` may call an unscoped Spring Data method** on a tenant-owned
   repository. The banned set includes `findById`, `findAll`, `existsById`, `count`, `deleteById`,
   `getReferenceById` and their siblings. A config service therefore cannot load its config by
   primary key — which is exactly right, since Hibernate does not apply `@Filter` to primary-key
   loads.
2. **Every declared method must contain `"Organization"` in its name, or be `@Query`-annotated.** So
   the API is `findByOrganizationId`, `findByIdAndOrganizationId`,
   `findByOrganizationIdAndActiveTrueOrderByNameAsc`.
3. **Every `@Query` must mention `organization`** (case-insensitive) unless allowlisted in
   `DELIBERATELY_CROSS_TENANT_QUERIES`. Phase 4 added **nothing** to that allowlist; it still holds one
   entry, `OTPRepository.deleteExpired`. The two symmetric exclusion reads are `@Query` methods — they
   have to be, see §8.3 — and both name `e.organization.id` explicitly, which is what this rule checks.
4. **Only `JwtTokenValidator` may call `TenantContext.bind`/`clear`.** Unchanged by Phase 4 — see
   §3.4 for why the onboarding path must not seek an exception.

Stage 1 added a fifth rule, `theConfigurationAuditIsAppendOnly`: no class anywhere may call a method
beginning with `delete` on `OrganizationConfigAuditRepository`. It is narrower than it sounds and wider
than rule 1 — rule 1 permits `delete(entity)` on a tenant-owned repository, because deleting a loaded
entity is scoped by definition, and the audit is the one repository where that is still forbidden. The
rule is **currently vacuous**: nothing calls the audit repository until the Stage 2 writer exists, so it
passes by having nothing to judge. It was verified by mutation instead — aimed at
`NotificationRepository` it flagged both of `NotisController`'s deleting call sites, which are the two
shapes it must catch. Committing it now means the first person to write a delete against the audit finds
a red build rather than a code review.

Note the vacuity guard, `theAnalysisActuallySawTheCodeItClaimsToCheck`: it asserts the named
repositories are present in the imported classes and that at least one call into them was seen. It
exists because ArchUnit's bundled ASM cannot parse JDK 26 class files and would otherwise pass by
analysing nothing. Adding repositories to the allowlist without adding them to that guard's
expectations is a way to be green and prove nothing — "a green architecture test that cannot go red is
decoration."

### 8.3 `RepositoryQueryDerivationTest` — the strictest of the three

`src/test/java/com/invo/coopr8/repository/RepositoryQueryDerivationTest.java` resolves each derived
query name through Spring Data's `PartTree` and asserts the resulting predicates include the path
`organization.id`. It checks what the method name *actually resolves to*, not what it looks like — so
a method named `findByOrganizationName` would pass §8.2's naming rule and fail here.

Every derived finder on the eight new repositories must resolve to a predicate on `organization.id`.

**One repository cannot satisfy this test by derivation at all, and the reason is a security finding
rather than an inconvenience.** A symmetric exclusion read has to mean *organization AND (this column OR
that column)*, and Spring Data has no grammar for it. A name ending
`...AndLoanTypeIdOrExcludedLoanTypeId` parses as `(organization AND loanType) OR excluded`, because `Or`
splits at the top level. The second disjunct carries no tenant predicate, so the derived method would
return rows from every cooperative on the platform. Both symmetric reads are therefore `@Query` with the
parentheses written out, which moves them from this test to §8.2's rule 3. A future derived name of that
shape must be refused for the same reason, not renamed.

The audit's append-only rule (§7, layer 3) was originally sketched here; in Stage 1 it went to
`TenantIsolationArchitectureTest` instead, because it is a statement about *who may call what* rather
than about what a query name resolves to, and ArchUnit can see call sites that a `PartTree` cannot.

### 8.4 `AbstractIntegrationTest.TRUNCATE_TENANT_TABLES`

```java
private static final String TRUNCATE_TENANT_TABLES = """
        TRUNCATE TABLE notification, otp, repay, saving, shares, loan, users, organizations
        RESTART IDENTITY CASCADE
        """;
```

All eight new tables must be added, ahead of `organizations`. Leaving them out means configuration
rows leak between test methods, and a cross-tenant test that passes because of stale state is worse
than no test.

**One consequence of doing this is worth knowing.** `V9` seeds a configuration row per organization
*once, at migration time*. Truncating removes those rows, so an organization a test creates afterwards
has no configuration — which is precisely the state a cooperative onboarded after Stage 1 will be in. Any
test needing configuration must insert it, and that is the correct thing to exercise: provisioning a new
cooperative is a code path, not a migration artefact. `OrganizationConfigConstraintTest` asserts the
truncation actually happened, so this stays a known property rather than a surprise.

`organization_config_audit` refuses `UPDATE` and `DELETE` via its trigger, but the trigger does not fire
on `TRUNCATE` — deliberately. Blocking truncation would force every test to disable the trigger, and a
safeguard the suite routinely switches off is worse than none. `TRUNCATE` needs table ownership the
application role does not hold.

These integration suites are the 38 skipped by `@Testcontainers(disabledWithoutDocker = true)` before
Phase 4 on a machine with no Docker — **60 after Stage 1**, since `OrganizationConfigConstraintTest`
adds 23 and is the only test that executes the `CHECK` constraints, the composite foreign keys and the
append-only trigger. **Phase 4 must not ship with its isolation tests unexecuted.**
Docker must be available for the Phase 4 verification run, and the existing gate
(`-Dcoopr8.test.require-docker=true`, which turns the skip into a hard failure via
`DockerRequirementTest`) is the mechanism that proves they ran.

Measured on the development machine, 2026-08-21:

| Command | Result |
|---|---|
| `./mvnw.cmd test` | 201 run, 0 failures, 0 errors, **61 skipped** — green, and green is misleading |
| `./mvnw.cmd test -Dcoopr8.test.require-docker=true` | 201 run, **1 failure**, 0 errors, 60 skipped — `DockerRequirementTest.dockerIsAvailableWhenTheBuildRequiresIt` |

The second is the honest number and the reason Stage 2 is blocked. The failure text is
*"Docker daemon must be reachable: coopr8.test.require-docker is set, which means this build claims to
verify tenant isolation. Without Docker the Testcontainers-backed integration, authorization and
cross-tenant (IDOR) tests do not execute at all."* — preceded by Testcontainers' own
*"Could not find a valid Docker environment."* There is no Docker, Podman, WSL or container service on
this machine, so the gate cannot be met here at all; `.github/workflows/backend-tests.yml` (§8.6) runs it
on `ubuntu-latest`, which has a daemon.

### 8.5 New tests Phase 4 should add

Seven were planned. Stage 1 folded five of them into two classes rather than creating five, because the
split that matters in practice is not one-concern-per-file but **what a test needs in order to run**:
reflection over annotated classes needs nothing, and a `CHECK` constraint needs a live PostgreSQL. Five
Docker-gated files would have meant five classes that all skip together on a machine without Docker, and
the two that do not need Docker would have skipped with them.

| Planned test | Asserts | Where it landed |
|---|---|---|
| `ConfigurationCardinalityTest` | a second config row per organization is rejected — `UNIQUE (organization_id)` is invisible to `ddl-auto=validate` | `OrganizationConfigConstraintTest` (needs a database) |
| `ConfigurationClassCTest` | no configuration entity declares a field matching the Class C deny-list (password, jwt, secret, regex, session, signing key) — and no penalty field either, per Decision 6 | `OrganizationConfigurationModelTest` for fields; `OrganizationConfigConstraintTest` additionally checks `information_schema.columns`, which catches a column with no Java field behind it |
| `ConfigAuditAppendOnlyTest` | `UPDATE`/`DELETE` against `organization_config_audit` is refused | `OrganizationConfigConstraintTest` for the trigger; `OrganizationConfigurationModelTest` for the absence of any setter; and `TenantIsolationArchitectureTest.theConfigurationAuditIsAppendOnly` for any `delete*` call on the repository |
| `CrossTenantConfigIsolationTest` | tenant A's admin reading or writing tenant B's config receives **404** | the read half is in `OrganizationConfigConstraintTest`; the **404** half needs endpoints and belongs to Stage 2 |
| `ConfigurationRequestSurfaceTest` | no `*ConfigRequest` DTO declares an `organizationId`/`organization`/`tenant` field (§3.5) | Stage 2 — there are no DTOs yet, and a test over an empty set of classes passes by finding nothing |
| `HistoricalTermsImmutabilityTest` | changing every config value leaves an approved loan's stored terms and displayed figures unchanged (§5) | Stage 3 |
| `InterestEngineTest` | §6.2 — hand-computed fixtures, the installment-sum identity, boundaries | Stage 4 |
| *(added 2026-08-21)* exclusion pairing | one row per unordered pair, symmetric resolution, no duplicate in either order, no self-exclusion, no cross-tenant reference | five in `OrganizationConfigurationModelTest` (no database), six in `OrganizationConfigConstraintTest` (needs a database) — see below |

The exclusion table was approved with nine tests named. Six are written, and three are not, because they
cannot be:

| Requested | Where it is |
|---|---|
| A excludes B | `theExclusionResolvesFromEitherDirection` |
| B excludes A | same test — that is the assertion |
| the same pair cannot be inserted twice as A/B | `theSamePairCannotBeDeclaredTwiceInTheStoredOrder` |
| the same pair cannot be inserted twice as B/A | `theSamePairCannotBeDeclaredTwiceInTheReverseOrder` |
| A cannot exclude itself | `aLoanTypeCannotExcludeItself`, at both layers — the entity refuses it with a legible message, the `CHECK` refuses it unconditionally |
| tenant A cannot reference tenant B's loan type | `aCooperativeCannotExcludeAnotherCooperativesLoanType`, tried in both directions |
| a member cannot modify exclusions | **deferred to Stage 2** |
| an organization admin can modify its own exclusions | **deferred to Stage 2** |
| an organization admin cannot modify another organization's exclusions | **deferred to Stage 2** |

The last three are authorization tests, and authorization is a property of an endpoint. There is no
endpoint: Stage 2 is where writes are built, and the same approval that asked for these tests forbids
building configuration controllers, services or DTOs before the database gate passes. Written now they
would assert over an empty set of request mappings and pass by finding nothing — the exact failure mode
§8.2's vacuity guard exists to prevent, and the reason `ConfigurationRequestSurfaceTest` is also parked
at Stage 2 in the table above. They are recorded here as Stage 2 exit criteria, not as work skipped.

The Stage 1 classes also carry assertions that were not on this list and came out of the decisions:
that a rate column is `numeric(6,3)` and not Hibernate's `numeric(38,2)` money default, that no
configuration field is a `double`, that every enum persists by name rather than ordinal, that
`REDUCING_BALANCE` exists in the enum but is selectable by nobody, and — new with the exclusion table —
that there is no public setter, builder or constructor by which an unnormalized pair could be
constructed at all, checked by reflection rather than by reading the class.

### 8.6 CI, because the gate cannot be met on the development machine

`.github/workflows/backend-tests.yml` runs `./mvnw test -Dcoopr8.test.require-docker=true` on
`ubuntu-latest`, which ships a running Docker daemon. Three things about it are deliberate:

- **No secret is referenced, and none may be added.** `application-test.properties` supplies every
  `${ENV_VAR}` the context needs at boot with test-only values, `application.properties` imports
  `optional:file:.env` so a missing `.env` is not an error, and `.env` is gitignored. A workflow that
  needed a production credential to run the tests would be a reason not to run the tests.
- **The database is a container this job throws away.** `TestDatabase` starts `postgres:16-alpine`;
  `TestDatasourceGuard` aborts context startup if the resolved URL is anything else. Nothing points at
  the Neon instance, and nothing may.
- **A second, independent skip check.** After the suite, a step parses the surefire XML and fails if the
  skipped count is anything but zero. `DockerRequirementTest` catches an absent daemon; this catches
  every other way a test can quietly not run — a leftover `@Disabled`, a failed assumption, one class
  that could not pull its image.

It pins **Temurin 17**, matching `<java.version>`, which also closes a real blind spot: ArchUnit 1.3.0's
bundled ASM cannot parse the `java.base` of the JDK 26 installed locally and falls back to an import with
no recorded accesses, which is why `theAnalysisActuallySawTheCodeItClaimsToCheck` had to be written. On 17
the tenant-isolation rules inspect the bytecode they claim to inspect.

**Placement caveat.** GitHub reads workflows only from `.github/workflows` at the *repository* root. The
file is at `coopr8back/.github/workflows/`, which is correct if `coopr8back` is that root. If the root
turns out to be the monorepo parent, the file moves up one level and the job gains
`defaults: run: working-directory: coopr8back`; the header says so. Note also that the runner invokes
`./mvnw`, the POSIX wrapper — `./mvnw.cmd` in the approval text is the Windows spelling of the same
command.

## 9. Migrations

Flyway owns all DDL; `ddl-auto=validate` only checks that the mapping matches what Flyway produced.
Phase 2 ended the directory at `V2__phase2_tenant_isolation.sql`, so **Phase 4 begins at V3**.

`V1` and `V2` are established and are not edited. If a Phase 4 requirement appears to need a change to
either, that is reported rather than applied.

One migration per concern, in dependency order. `V3`–`V10` exist; `V11` is Stage 3 work:

```
V3__phase4_loan_configuration.sql        -- organization_loan_config, organization_loan_type
V4__phase4_savings_plans.sql             -- organization_savings_plan
V5__phase4_shares_configuration.sql      -- organization_shares_config
V6__phase4_repayment_configuration.sql   -- organization_repayment_config
V7__phase4_membership_configuration.sql  -- organization_membership_config
V8__phase4_config_audit.sql              -- organization_config_audit + append-only trigger
V9__phase4_seed_defaults.sql             -- one config row per existing organization  [Stage 1]
V10__phase4_loan_type_exclusion.sql      -- organization_loan_type_exclusion          [Stage 1]
V11__phase4_historical_terms.sql         -- Class B columns on loan, saving, shares    [Stage 3]
```

**Two renumberings happened, and neither is cosmetic.**

*First:* seed defaults were planned as `V10` and historical terms as `V9`; as written, seeding is `V9`.
The reason is that `spring.flyway.out-of-order` is **false**, so Flyway refuses a migration whose version
is lower than one already applied. Stage 1 ships the tables and must seed them — an organization with no
configuration row breaks on the first read — while the Class B columns belong to Stage 3, two checkpoints
later. Had seeding kept `V10`, Stage 3's `V9` would be rejected by every database that had already run
Stage 1, and the fix would be renaming a migration that had executed in production. Stage 1's own
migration takes the lower number because Stage 1 runs first.

*Second:* the exclusion table, approved mid-Stage-1, took `V10`, which pushed historical terms to `V11`
for exactly the same reason — it is Stage 1 work and must sort below Stage 3's. **This renumbering was
safe only because §9.1 established that no database anywhere has a `flyway_schema_history` row for any of
these files.** Renumbering an applied migration is a different and much worse problem. If `V3`–`V10` have
been applied anywhere by the time a further Stage 1 table is approved, it takes the next free number
instead and this list stops being ordered by stage.

Notes on the three:

- **The exclusion table (`V10`) is created empty and read by nothing.** Its consequence for Stage 2 is in
  its own header: a cooperative with no exclusion rows has no exclusions, which must not be confused with
  "not configured yet". It also carries the only `ConfigDomain` addition Phase 4 made after `V8` was
  written; `V8` was edited in place rather than corrected by a follow-up `ALTER`, because a migration that
  exists only to fix an unapplied `CHECK` is history that never happened. That edit is legitimate for
  exactly as long as §9.1 stays true.
- **Historical terms (`V11`) is additive and nullable only.** No column on `loan`, `saving` or `shares`
  is dropped, renamed or made `NOT NULL`. The backfill of §5.4 is **not** in it — it is a separate,
  explicitly approved data step preceded by a read-only survey.
- **Seeding (`V9`) must not invent business rules.** Every existing organization needs a config row or
  the application breaks on first read, but a seeded interest rate is a business decision. It seeds the
  neutral values that match current behaviour — `interest_method = 'NONE'`, `interest_rate = 0.000`,
  no min/max, `required_guarantors = 2` — so that shipping Phase 4 changes no organization's rules
  until an admin changes them deliberately. There is no default Citadel organization and no Citadel
  seed data; `V9` iterates whatever rows exist in `organizations`. The two product collections and the
  exclusion table are left empty: `{real, soft, material}` are Citadel's product names, not a platform
  default, and seeding them would invent three products for cooperatives that never offered them —
  along with an exclusion between two of them.

Seven of the eight tables need an index on `organization_id` (the FK is queried on every read), which
`ddl-auto=validate` will not verify. The exception is `organization_loan_type_exclusion`, whose
`UNIQUE (organization_id, loan_type_id, excluded_loan_type_id)` is already organization-leading and so
serves the same reads; it adds a second index on `(organization_id, excluded_loan_type_id)` instead,
because the symmetric read of §8.3 probes both columns and only one of them is the uniqueness prefix.

### 9.1 Read-only preflight survey, 2026-08-20

Run at the Stage 1 checkpoint to establish whether the renumberings above were safe — renumbering an
applied migration is a different and much worse problem than renumbering an unapplied one, and editing an
applied `V8` would be worse still. Strictly read-only: JDBC `readOnly`, `SET TRANSACTION READ ONLY`,
`SELECT` statements only, no Flyway class loaded, rolled back rather than committed.

Against the database `DB_URL` resolves to (PostgreSQL 18.6):

- **`flyway_schema_history` does not exist.** Flyway has never run there — not V1, not V2, not V3–V10.
- **`public` contains zero tables.** None of the eight V1 core tables, none of the eight Phase 4 tables.
- **`uk_users_org_id` is absent**, as expected, since `V8` has never been applied.

So both renumberings are safe, and so is the in-place edit to `V8`'s domain `CHECK`: they touch files that
no database has a record of. It is also the only database this repository can reach — there is exactly one
JDBC target in the whole tree besides the deliberately unusable test one, and no second `DataSource` bean.

**This finding has a shelf life.** Every conclusion above is a statement about one database at one moment.
The moment `V3`–`V10` are applied anywhere that persists, `V8` becomes uneditable and the numbers become
fixed; a further Stage 1 table then takes the next free version rather than the one that sorts nicely. The
survey must be re-run, not remembered, before any later migration decision leans on it.

## 10. Hardcoded rules this replaces

For reference, the rules currently compiled into services, and what Phase 4 does with each:

| Currently hardcoded | Location | Phase 4 |
|---|---|---|
| No interest at all | `LoanServiceImpl.approveLoan` | `organization_loan_config` + new engine (§6) |
| Exactly 2 guarantors | `Loan.guarantor1` / `guarantor2` | `required_guarantors ∈ {0,1,2}` — see caveat below |
| `"real"` + `"material"` mutual exclusion | `LoanServiceImpl.applyLoan` literal | `organization_loan_type_exclusion` — see caveat below |
| One active loan per type | `LoanServiceImpl.applyLoan` | per-type concurrency rule |
| No min/max loan amount | absent | `organization_loan_config` |
| No eligibility check | absent | `organization_loan_config` |
| Tenure validated only as `> 0` | `LoanServiceImpl.applyLoan` | allowed-tenure list |
| `LoanType` enum `{real, soft, material}` | `model/LoanType.java` — was dead code: its only reference anywhere was a commented-out import in `dto/LoanDto.java`, and `Loan.type` is a `String` | **done in Stage 1** — the enum and the stale import are deleted; types become rows |
| Repayment must be an exact multiple | `RepayServiceImpl` | `organization_repayment_config` + the §5.5 fix |
| No penalty rules | absent | `organization_repayment_config` |
| Share approval always required | `SharesServiceImpl` | `organization_shares_config` |
| No share price or unit concept | `Shares` holds naira `amount` only | `share_price` + `applied_units` (§5.3) |
| Withdrawal rules: `amount > 0`, `balance >= amount` | `SharesServiceImpl` | `organization_shares_config` |
| Savings plans as numbers on `User` | `savingPlan`, `specialSavingPlan`, `sharePlan` | `organization_savings_plan` rows; keep the numeric columns, add a nullable plan reference — rewriting member plan data is a production data change |

**Guarantor caveat.** `required_guarantors` above 2 is **not** a configuration change. `Loan` has two
fixed guarantor columns with two fixed status columns; supporting three or more requires a
`loan_guarantor` child table and rework of the guarantor approval flow in `GuarantorController` and the
frontend. Phase 4 should constrain the setting to `{0, 1, 2}` with a `CHECK`, and a variable-guarantor
table should be scoped as separate work rather than smuggled in as a config field that silently caps.

**Exclusion caveat, discovered in Stage 1 — an eighth table, approved and built 2026-08-20/21.** The
`"real"` + `"material"` exclusion row above cannot be expressed on `organization_loan_type` as designed.
`LoanServiceImpl.applyLoan` hardcodes one specific *pair* of type names, and a pair is a relationship
between two rows, not a property of one. A boolean `exclusive` flag was explicitly ruled out and is the
wrong shape regardless: it cannot say *with what*.

`organization_loan_type_exclusion` (`V10`) carries it:

```sql
id                    bigint GENERATED BY DEFAULT AS IDENTITY  PRIMARY KEY
organization_id       bigint NOT NULL  -> organizations (id)
loan_type_id          bigint NOT NULL  -- the LOWER of the two ids
excluded_loan_type_id bigint NOT NULL  -- the HIGHER
created_at            timestamp(6)

UNIQUE (organization_id, loan_type_id, excluded_loan_type_id)
FOREIGN KEY (organization_id, loan_type_id)          -> organization_loan_type (organization_id, id)
FOREIGN KEY (organization_id, excluded_loan_type_id) -> organization_loan_type (organization_id, id)
CHECK  (loan_type_id < excluded_loan_type_id)
```

**The symmetry question is settled: one row per unordered pair, and every read is symmetric.** Real ↔
Material is one database row, not two. Two rows would mean the two halves of one rule could disagree —
delete one and the exclusion applies in one direction, which is not a state the domain has a meaning for.

What makes one row safe rather than merely smaller is that the ordering is not left to callers. The pair is
normalized before persistence, smaller id first, and:

- **The `CHECK` is strict inequality, not `<>`.** `loan_type_id < excluded_loan_type_id` does the work of
  both constraints: it rejects an unnormalized pair *and* rejects self-exclusion, since nothing is less
  than itself. A separate `<>` check was considered and left out — it could never fire while the strict
  inequality holds, and PostgreSQL does not promise which of two violated `CHECK`s it names, which would
  make the self-exclusion test's error assertion a coin toss.
- **`UNIQUE` on the normalized triple is therefore a uniqueness constraint on the unordered pair.** The
  reverse duplicate cannot reach it, because the `CHECK` refuses the reversed row first. Both paths are
  tested.
- **Normalization cannot be bypassed.** The entity has no setters, no builder and no public constructor —
  the only way to make one is `OrganizationLoanTypeExclusion.between(org, a, b)`, which sorts the ids and
  rejects `a == b` with a message naming `maxActiveLoans` as the thing the caller probably wanted. A
  reflection test fails the build if a setter or builder ever appears. Without that, a caller would meet a
  `CHECK` violation naming a constraint they have never heard of.
- **Reads test both columns.** `existsBetween(org, real, material)` and `existsBetween(org, material,
  real)` resolve to the same row; `findAllInvolvingLoanType` finds a rule from either side and
  `counterpartOf` returns the other half. Both are `@Query`, of necessity — see §8.3, where the derived
  form turns out to drop the tenant predicate rather than merely read awkwardly.
- **Cross-tenant exclusion is impossible, not unlikely.** The composite FKs mean a cooperative naming
  another's loan type is refused by PostgreSQL before any service code runs, in either direction. This
  works because `organization_loan_type` already carries `UNIQUE (organization_id, id)` from `V3`, so no
  change to `V3` was needed.
- **The audit domain is `LOAN_TYPE_EXCLUSION`,** its own `ConfigDomain` constant rather than part of
  `LOAN_TYPE`, added by editing `V8` in place (§9).

**Nothing reads this table yet.** The literal in `LoanServiceImpl.applyLoan` is untouched: it works exactly
as it does today, for every tenant, and no configuration overrides it. Stage 5 replaces it — and must
handle the empty case deliberately, because a cooperative with no exclusion rows has no exclusions, which
is a different thing from "not configured yet". The three authorization tests the approval asked for are
Stage 2 work and are tracked in §8.5.

## 11. Sequencing

Six stages. Each ends with a green build; none begins before the previous one is verified.

**Stage 0 — prerequisites (not Phase 4 work, but Phase 4 is unsafe without them).**
Docker available so the integration tests execute — 38 of them before Phase 4, 60 after Stage 1; the Phase
2 deployment blockers closed
(`JWT_SECRET` present, the Paystack webhook idempotency and signature gap, the `rejectLoan` balance
mutation); and the §5.5 repayment rounding defect fixed. Interest arithmetic layered on top of a
repayment rule that cannot terminate produces loans that cannot be closed.

**Stage 1 — schema and isolation, no behaviour. IMPLEMENTED.** `V3`–`V10`, the eight entities, the eight
repositories, registered in all four places (§8.1–§8.4). No service reads configuration. This stage is
where the isolation guarantee is established, and nothing depends on it yet, so a mistake here is cheap.

The stage was specified as complete only when the full suite is green *including* the integration tests,
and **that condition is not met**: there is no Docker on the development machine, so every
`AbstractIntegrationTest` subclass — including `OrganizationConfigConstraintTest`, which is the
only test that executes the `CHECK` constraints, the composite foreign keys and the append-only trigger —
skips rather than runs. `./mvnw.cmd test -Dcoopr8.test.require-docker=true` fails with 1 failure and 60
skips (§8.5). What was done instead is offline: Hibernate's own DDL generation
(`SchemaGenTest`) diffed against the hand-written migrations, which proves the tables and column types
agree and proves nothing about the constraints. Those tests are written and committed; they need a
Docker host or the CI workflow of §8.6 to execute. `TestDatasourceGuard` must not be relaxed to point them
at a live database, and the empty Neon instance is not a substitute for a container.

**Stage 2 — admin read and write. BLOCKED until the database test gate passes.** Endpoints under
`/api/admin/config/**` (already `hasRole('ADMIN')` via `AppConfig`), DTOs with no tenant field (§3.5),
audit written in the same transaction (§7). Still no business behaviour change: an admin can view and edit
rules that nothing consults. Cross-tenant tests return 404. Note that the codebase has **no
`@ControllerAdvice`**, so refusals must be `ResponseStatusException` to get a chosen status, and the
existing `"100"` / `"419"` / `"409"` response convention applies.

It carries three inherited exit criteria from the exclusion approval, which could not be written in Stage 1
because they are assertions about endpoints that do not exist yet (§8.5): a member cannot modify
exclusions; an organization admin can modify its own; an organization admin cannot modify another
organization's. Also inherited: `ConfigurationRequestSurfaceTest`, which needs at least one DTO to have
anything to inspect.

**Stage 3 — historical terms.** `V11` (see §9 for why it is not `V9` or `V10`), the Class B columns,
`updatable = false`, and population at approval using the *current* zero-interest arithmetic.
`HistoricalTermsImmutabilityTest` passes before any rate exists. This deliberately proves the
immutability mechanism while there is still nothing at stake.

**Stage 4 — the interest engine.** `NONE` and `FLAT` only — Decision 1 keeps `REDUCING_BALANCE` in the
enum and out of the engine. `InterestEngineTest` first, then wire into `approveLoan`. `NONE` remains
every organization's seeded default, so shipping this stage changes no organization's numbers until an
admin sets a rate.

**Stage 5 — remaining domains consume configuration.** Loan types, savings plans, shares config,
repayment config, membership config read by their services. Each domain is a separate, independently
verifiable change.

**Stage 6 — frontend.** Admin configuration screens, and member-facing screens reading available loan
types, plans and share price from per-tenant projections. Member and public reads must be minimal DTOs,
never entities.

The staging is arranged so that every stage before 4 is behaviour-preserving. If Phase 4 has to stop
early, it stops on a system that is no different from today's rather than half-configurable.

## 12. The six business decisions, as approved

All six were approved on 2026-08-20. The reasoning behind each, and what the rejected alternatives
would have cost, is in [`phase4-open-decisions.md`](phase4-open-decisions.md); what follows is the
binding form and where each one is enforced.

**1 — Interest method: `FLAT` only.** The persisted enum keeps all three constants (`NONE`, `FLAT`,
`REDUCING_BALANCE`) so the schema does not need redesigning later, but reducing-balance is not
calculated and is not offered to administrators. `InterestMethod.isAdminSelectable()` is the single
place that distinguishes them, and `OrganizationConfigurationModelTest` fails the build if
`REDUCING_BALANCE` ever becomes selectable before an engine can compute it. Interest is new
functionality; there is no existing interest to migrate.

**2 — Rate basis: per annum.** The configured `interest_rate` is an annual percentage. No rate-basis
column exists, at this stage or in the schema — adding one later means backfilling existing rows as
`PER_ANNUM`, which is a cheap change; interpreting today's rate ambiguously is not. Flat interest is
`principal × annual_rate × (tenure_months / 12)`. The admin UI must label the field **"Annual Interest
Rate (% per year)"** and may show the monthly equivalent read-only, never as the basis.

**3 — Residual: absorbed by the final installment, computed by subtraction.**
`final_installment = total_repayable − sum(all_previous_installments)`, never
`rounded_installment + residual`, which can exceed the agreed total. This is platform arithmetic, not
configuration: it has no column in any of the eight tables and must not acquire one. The stored
`total_repayable` is authoritative and is never adjusted to make the division tidy. Enforced in Stage 4
by the interest-engine tests.

**4 — Effective dating: immediate.** A configuration change applies to loans and transactions created
after it. Existing approved loans are never recalculated. No future-dated configuration and no policy
versioning at this stage — the audit row's `effective_date` records when a change took effect, and the
Class B columns of §5.2 record the terms each loan was actually priced under. Those two together are
what make "changing today's rate cannot change yesterday's loan" a property of the data rather than a
promise about the code.

**5 — Historical backfill: approved, in six steps, not automatic.** Read-only survey first; reconcile
each approved loan against `amount ≈ repay_amount × duration` within the one-kobo tolerance; backfill
only what reconciles; leave the Class B fields `NULL` on everything that does not; report every
unreconciled loan explicitly; invent nothing. The backfill must never touch `principal`, `balance`,
`repay_amount`, repayment history, savings, shares or member balances. It is a controlled data
migration, run deliberately, never as part of a Flyway migration.

**6 — Penalties: deferred.** No charging, no enforcement, and specifically no penalty *setting* in
Phase 4 — a column an administrator can fill in that nothing acts on is a rule the platform advertises
to members and does not apply. Enforced by absence:
`OrganizationConfigurationModelTest.noSecurityControlAndNoPenaltyIsRepresentableAsConfiguration` fails
on a penalty field, and `OrganizationConfigConstraintTest` fails on a penalty *column*, which catches a
migration that adds one with no Java field behind it.

## 13. What Stage 1 deliberately did not do

Everything below is still absent from the repository, and each absence is a decision rather than an
omission:

- **No service, controller or DTO reads configuration.** The eight repositories have no callers. That is
  what makes Stage 1 behaviour-preserving: every rule the application applies today it still applies,
  from the same hardcoded literals, for every tenant — including the `"real"`/`"material"` exclusion,
  which `organization_loan_type_exclusion` is built to carry and does not yet carry.
- **No Class B columns** on `loan`, `saving` or `shares` — Stage 3, `V11`.
- **No interest calculation** anywhere — Stage 4. The `interest_rate` column exists, is seeded `0.000`,
  and nothing multiplies by it.
- **No frontend change** — Stages 5–6.
- **No production data change, and no backfill.** Decision 5's survey has not been run against
  production. `V9` inserts one neutral configuration row per existing organization and touches no
  member, loan, saving, share or balance row. `V10` creates its table empty.
- **No penalty column, field or setting** — Decision 6, enforced by two tests.
- **Flyway `V2` has not been run, and Phase 3 has not started.** Neither has `V3`–`V10`: no migration in
  this phase has been applied to any persistent database (§9.1).

`ddl-auto=validate` will now check the eight new tables on every boot, which is a change in what boot
fails on: a database that has run `V1` and `V2` but not `V3`–`V10` will refuse to start. That is the
intended behaviour — the alternative is an application that boots against a schema it cannot use.
