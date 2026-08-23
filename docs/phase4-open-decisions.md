# Phase 4 — Six Business Decisions (APPROVED)

**Status: ALL SIX APPROVED, 2026-08-20.** The recommendation in each section below is the decision
that was taken. This document is kept as the record of *why* each was chosen and what the
alternatives cost — not as an open question. See the **Approved outcome** table immediately below for
the binding version of each answer, and §12 of the architecture document for how each one is
implemented.

## Approved outcomes

| # | Decision | Approved answer | Where it now lives |
|---|---|---|---|
| 1 | Interest method | `FLAT` only in the first implementation. The enum persists `NONE` / `FLAT` / `REDUCING_BALANCE`; `REDUCING_BALANCE` is **not** admin-selectable and **not** implemented. No migration of existing interest, because there is none. | `InterestMethod.adminSelectable`; `ck_organization_loan_config_interest_method` (V3) |
| 2 | Rate basis | **Per annum.** No configurable rate-basis column. `interest = principal × annual_rate × (tenure_months / 12)`. Admin UI must label it "Annual Interest Rate (% per year)"; the monthly equivalent may be shown read-only but is never the calculation basis. | `interest_rate numeric(6,3)` (V3); the label is a Stage 5 UI obligation |
| 3 | Residual / rounding | **The final installment absorbs the residual**, computed as `total_repayable − sum(previous installments)` — never `rounded + residual`. Platform-wide arithmetic rule, **not** organization-configurable. | Stage 4 interest engine; deliberately absent from every configuration table |
| 4 | Effective dating | **Immediate.** A change applies to loans/transactions created after it. Existing approved loans are never recalculated. No future-dating, no policy versioning. Every approved loan stores its own applied terms. | `effective_date` on the audit (record, not control); Class B columns in Stage 3 (V11) |
| 5 | Historical loan backfill | Approved as a **separate, surveyed, manually-executed** migration: read-only survey → reconcile `amount ≈ repay_amount × duration` within one kobo → backfill only what reconciles → leave the rest NULL → report every unreconciled loan. Never invents values. Never touches principal, balance, `repay_amount`, repayment history, savings, shares or member balances. | Stage 3; **not** in Stage 1 |
| 6 | Penalties | **Deferred.** No penalty charging, no penalty enforcement, and specifically no penalty setting that is displayed but not enforced. | Asserted absent by `OrganizationConfigurationModelTest` and `OrganizationConfigConstraintTest` |

Companion to [`phase4-tenant-business-configuration.md`](phase4-tenant-business-configuration.md)
§12. The architecture is approved; these six questions are the ones the architecture *depends on* and
could not answer for itself. Each is a business decision with a financial consequence, not a technical
preference.

The reasoning below is preserved unedited. A wrong answer here is not a refactor — decisions 1, 2 and
3 determine the numbers stored immutably in Class B columns on every loan the cooperative ever
approves, and those numbers cannot be recomputed later without breaking the §5 rule that history is
never silently rewritten.

Two facts frame all six, both verified in code:

- **There is no interest anywhere today.** `LoanServiceImpl.approveLoan` computes
  `repayAmount = amount.divide(duration, 2, HALF_UP)` and adds the bare principal to the member's loan
  balance. `Loan` has no rate, no interest, no total-repayable column. Every existing approved loan is
  a zero-interest loan.
- **The repayment rule cannot currently terminate on some loans.** `RepayServiceImpl` requires every
  repayment to be an exact multiple of `repayAmount`, and rounding leaves a residual that is not a
  multiple. Decision 3 is the fix, not an optimization.

---

## Decision 1 — Interest method

### 1. The exact question

Which interest calculation method(s) must the first implementation support, and which does an
organization admin select from?

### 2. Available options

| Option | Method | Definition |
|---|---|---|
| **1a** | `NONE` only | No interest. Configuration exists, rate is always 0. |
| **1b** | `FLAT` only | Interest charged on the **original principal** for the full tenure. |
| **1c** | `REDUCING_BALANCE` only | Interest charged on the **outstanding balance**, recomputed as principal is repaid. |
| **1d** | `FLAT` + `REDUCING_BALANCE` | Both shipped; admin chooses per organization (or per loan type). |

### 3. What the code does today

Effectively **1a**, unconfigurably. `approveLoan` divides principal by tenure. There is no rate to
relocate and no method to preserve — which is why the architecture document classifies this as new
financial functionality rather than a moved setting.

### 4. Recommendation

**Option 1b — `FLAT` only for the first implementation**, with the `applied_interest_method` column
and the enum admitting `NONE`, `FLAT` and `REDUCING_BALANCE` from day one.

The reasoning is about reversibility. Shipping the *column* with three values costs nothing now;
shipping the *engine* for reducing balance costs an amortization schedule, an early-repayment policy,
and an interaction with Decision 3 that doubles the test surface. If only the engine is deferred,
adding `REDUCING_BALANCE` later is a service change plus tests. If the column is deferred, adding it
later means a migration against a table full of loans whose method is unrecorded — and under §5 those
loans' terms cannot be retroactively reinterpreted.

`FLAT` is also the method most Nigerian cooperatives actually quote to members, and its arithmetic is
verifiable on paper by a member who wants to check it. That matters more than mathematical elegance
when the counterparty is a member of the cooperative.

### 5. Effects

| | 1a `NONE` | 1b `FLAT` | 1c `REDUCING_BALANCE` | 1d both |
|---|---|---|---|---|
| **Database schema** | Class B columns still needed (all zero) | Same columns; enum has 3 values | Same columns; may need a per-installment schedule table for interest/principal split | Same as 1c |
| **Loan calculation** | unchanged from today | closed form, one multiplication | annuity formula, per-installment split | both paths, selected by config |
| **Existing loans** | unaffected | unaffected — retain 0% (Decision 5) | unaffected | unaffected |
| **Future loans** | no interest ever | interest on original principal | interest on declining balance | depends on admin choice |
| **Admin config UI** | rate field pointless | rate field + method shown as fixed | rate field + method shown as fixed | rate field + **method selector**, needs explaining to admins |
| **Reporting / audit** | nothing to report | `applied_interest_amount` per loan | additionally an interest/principal split per installment | reports must group by method or totals mislead |

### 6. Worked example — ₦300,000 over 12 months at a nominal 12% per annum

| Method | Interest | Total repayable | Monthly installment |
|---|---|---|---|
| `NONE` | ₦0 | ₦300,000.00 | ₦25,000.00 |
| `FLAT` | ₦36,000.00 | ₦336,000.00 | ₦28,000.00 |
| `REDUCING_BALANCE` | ₦19,855.68 | ₦319,855.68 | ₦26,654.64 |

`FLAT` costs the member **about 1.8× as much interest as `REDUCING_BALANCE` at the identical headline
rate.** To charge the same total under reducing balance, the rate would need to be roughly 21.7%.

This is the whole reason the method is a business decision and not an implementation detail: "12%"
means two materially different prices, and the member cannot tell which they were quoted unless the
method is recorded on the loan. It is why `applied_interest_method` is a Class B column.

---

## Decision 2 — Rate basis

### 1. The exact question

When an admin enters the number `12` into the interest rate field, does it mean 12% **of the loan**
(one-off), 12% **per annum**, or 12% **per month**?

### 2. Available options

| Option | Basis | Interest under `FLAT` |
|---|---|---|
| **2a** | Per loan | `principal × rate` — tenure is irrelevant |
| **2b** | Per annum | `principal × rate × (tenure_months ÷ 12)` |
| **2c** | Per month | `principal × rate × tenure_months` |

### 3. What the code does today

No rate exists in any basis. There is no precedent in the codebase to follow, which means this must be
decided explicitly rather than inherited.

### 4. Recommendation

**Option 2b — per annum**, stored as an annual percent in `applied_interest_rate numeric(6,3)`, with
the engine converting to the tenure.

Two supporting recommendations that are part of this decision:

- **The admin UI must state the basis in the field label** — "Annual interest rate (% per year)" — and
  should display the monthly equivalent read-only beside it. Informal cooperative lending in Nigeria
  is frequently quoted per month, so an admin who types `3` meaning "3% monthly" into a per-annum
  field under-charges by a factor of 12. The label is the guard.
- **Do not add an `interest_rate_basis` column in the first implementation.** Fixing the basis
  platform-wide removes the ambiguity entirely. If a future requirement genuinely needs per-month
  configuration, the column is added then and historical loans are backfilled `PER_ANNUM`, which is
  truthful because that is what they were priced under.

### 5. Effects

| | 2a Per loan | 2b Per annum | 2c Per month |
|---|---|---|---|
| **Database schema** | `numeric(6,3)`; tenure not used in pricing | `numeric(6,3)` | `numeric(6,3)`, but realistic values are small so `(6,3)` is coarse — a 2.5%/month rate has no room for basis points |
| **Loan calculation** | one multiplication, tenure ignored | multiply by `tenure ÷ 12` | multiply by `tenure` |
| **Existing loans** | unaffected under any option | unaffected | unaffected |
| **Future loans** | cost independent of tenure | cost proportional to tenure | cost proportional to tenure, 12× steeper per unit rate |
| **Admin config UI** | simplest to explain, easiest to misprice | needs the basis stated; monthly equivalent helps | matches informal quoting habits, but headline number looks small and compounds fast |
| **Reporting / audit** | not comparable to any external rate | comparable to bank APR — the standard for member disclosure | must be annualized before any external comparison |

### 6. Worked example — ₦300,000, `FLAT`, rate figure `12`

| Tenure | 2a Per loan | 2b Per annum | 2c Per month |
|---|---|---|---|
| 6 months | ₦36,000 interest → ₦56,000/mo | ₦18,000 interest → ₦53,000/mo | ₦216,000 interest → ₦86,000/mo |
| 12 months | ₦36,000 interest → ₦28,000/mo | ₦36,000 interest → ₦28,000/mo | ₦432,000 interest → ₦61,000/mo |
| 24 months | ₦36,000 interest → ₦14,000/mo | ₦72,000 interest → ₦15,500/mo | ₦864,000 interest → ₦48,500/mo |

Three things this table makes concrete:

- **The same stored `12` produces interest between ₦18,000 and ₦864,000.** The basis is not a
  presentational choice.
- **Per loan (2a) makes tenure free.** A member pays ₦36,000 whether they borrow for 6 months or 24,
  so the rational choice is always the longest tenure — the cooperative's money is tied up four times
  as long for the same fee. Some cooperatives do charge this way deliberately; it should be chosen
  knowingly.
- **Per month (2c) at a plausible-looking figure is usurious.** ₦864,000 interest on ₦300,000 is not a
  configuration error a validation range would catch, because `12` is inside every sane range for the
  other two bases. Only the basis distinguishes them.

---

## Decision 3 — Residual allocation

### 1. The exact question

Dividing the total repayable by the tenure rarely yields a figure that is exact to the kobo. Where does
the leftover go, so that the installments sum to exactly the agreed total?

### 2. Available options

| Option | Policy |
|---|---|
| **3a** | The **final** installment absorbs the residual; all earlier installments equal |
| **3b** | The **first** installment absorbs it |
| **3c** | Spread kobo-by-kobo across the earliest installments until exhausted |
| **3d** | Adjust the **total** so it divides evenly, and keep all installments identical |

### 3. What the code does today

Nothing handles it, and the omission is currently a live defect. `approveLoan` sets
`repayAmount = amount ÷ duration` rounded `HALF_UP` to 2 decimals, and `RepayServiceImpl` then requires
every repayment to be an exact multiple of that figure. A ₦100,000 loan over 3 months gives
₦33,333.33 × 3 = ₦99,999.99, leaving ₦0.01 outstanding that **cannot be paid**, because ₦0.01 is not a
multiple of ₦33,333.33. The loan can never reach `completed`.

Interest makes non-terminating division the normal case rather than an occasional one, so this must be
settled before the engine ships.

### 4. Recommendation

**Option 3a — the final installment absorbs the residual.**

With one implementation requirement that matters more than the choice itself: the final installment
must be computed as `total_repayable − sum(all earlier installments)`, **not** as
`installment + residual`. The residual can be negative — `HALF_UP` sometimes rounds the installment
*up*, making the naive sum exceed the total — so a policy expressed as "add the leftover to the last
one" overcharges in exactly the cases it was meant to fix. Subtraction is correct in both directions.

Option 3d must be rejected specifically: adjusting the total breaks the §5 invariant that
`applied_total_repayable` is the figure the cooperative and the member agreed to. Moving it by two
kobo to make the arithmetic tidy means the stored total is not the agreed total, which is the one thing
Class B columns exist to prevent.

### 5. Effects

| | 3a Final absorbs | 3b First absorbs | 3c Spread | 3d Adjust total |
|---|---|---|---|---|
| **Database schema** | none — derived at approval | none | may need a stored schedule to know which installments differ | none |
| **Loan calculation** | one subtraction for the last installment | one addition to the first | loop allocating kobo | rounds the total, then divides |
| **Existing loans** | unaffected; the ₦0.01 defect is fixed forward, and Decision 5 governs whether affected loans are repaired | same | same | same |
| **Future loans** | installments sum exactly to the total | sums exactly; member's first payment is the odd one | sums exactly; several installments differ by a kobo | installments identical, total is off by kobo |
| **Admin config UI** | nothing to configure — a platform arithmetic policy, not a tenant setting | same | same | same |
| **Reporting / audit** | `sum(installments) = applied_total_repayable` is a testable identity | same | same | identity holds only against the adjusted total |

Note that this is **not** an organization-configurable setting. It is one platform-wide arithmetic
policy, stated once. Letting tenants choose rounding behaviour would put the same loan at two different
prices depending on a setting no member ever sees.

### 6. Worked examples

**Case 1 — residual positive.** ₦100,000 over 3 months, `NONE` (the currently broken case):

| | Installments | Sum |
|---|---|---|
| Today | 33,333.33 / 33,333.33 / 33,333.33 | ₦99,999.99 — **₦0.01 unpayable** |
| 3a | 33,333.33 / 33,333.33 / **33,333.34** | ₦100,000.00 ✓ |

**Case 2 — residual negative, which the naive policy gets wrong.** ₦50,000 over 7 months, `NONE`:

`50,000 ÷ 7 = 7,142.857…` → `7,142.86` rounded `HALF_UP`, and `7,142.86 × 7 = ₦50,000.02` — the sum
**exceeds** the principal by ₦0.02.

| | Installments | Sum |
|---|---|---|
| "add the leftover to the last" | 7,142.86 × 6, then 7,142.88 | ₦50,000.04 — worse |
| 3a as specified (`total − sum(earlier)`) | 7,142.86 × 6 = 42,857.16, final **7,142.84** | ₦50,000.00 ✓ |

**Case 3 — with interest.** ₦100,000 over 3 months, `FLAT` 12% per annum: interest
`100,000 × 12% × (3÷12) = ₦3,000`, total `₦103,000`. `103,000 ÷ 3 = 34,333.333…` → installments
`34,333.33 / 34,333.33 / 34,333.34` = ₦103,000.00 exactly.

---

## Decision 4 — Effective dating

### 1. The exact question

When an admin saves a configuration change, when does it take effect — and does the system need to be
able to answer "what was the configuration on date X" for anything other than the audit trail?

### 2. Available options

| Option | Model | Config read |
|---|---|---|
| **4a** | **Immediate.** One mutable row per organization; the change applies to transactions created or approved after it is saved | read the current row |
| **4b** | **Future-dated.** Admin sets a commencement date; the change is pending until then | resolve "the row in effect at time T" |
| **4c** | **Full policy versioning.** Every change creates a new immutable version row; transactions reference the version used | resolve by version or by time, always |

### 3. What the code does today

There is no configuration at all, so no model is in place. But the approved architecture already
contains a partial answer: every loan carries `terms_config_audit_id`, a foreign key to the audit row
that produced its terms. That gives full traceability from any loan back to the configuration change
that priced it — **without** requiring config reads to be time-parameterized.

### 4. Recommendation

**Option 4a — immediate**, with `effective_date` retained on the audit row (equal to the change date
under this model, and available for a future 4b without a schema change).

The important argument is that **4c is largely redundant given Class B.** The usual reason to version
configuration is "so we can tell what a historical transaction was priced under" — and the Class B
columns answer that better than versioning does, because they record the *computed outcome* (rate,
method, interest, total, installment) rather than the inputs a reader would have to re-derive. Adding
4c would additionally answer "what could a member have borrowed on 3 March", which is a question no
requirement has raised.

**When 4b would become genuinely necessary:** if a general meeting approves a rate change with a
stated commencement date and there is a compliance need for the *system* to hold the change until then,
rather than trusting an admin to log in that morning. That is a real scenario, and it is the only one
that justifies 4b — so the question to answer is simply whether it applies here.

### 5. Effects

| | 4a Immediate | 4b Future-dated | 4c Full versioning |
|---|---|---|---|
| **Database schema** | one row per org, `UNIQUE (organization_id)` holds | **`UNIQUE (organization_id)` must be dropped** or a separate pending-change table added | many rows per org, all immutable; every transaction gains a version FK |
| **Loan calculation** | read current config | read config as of the approval instant — needs a defined timezone and a rule for a loan approved at 23:59 | read the referenced version |
| **Existing loans** | unaffected (Class B) | unaffected | unaffected, but pre-versioning loans have no version to reference |
| **Future loans** | priced by whatever is current at approval | priced by the schedule, enforced | priced by the current version |
| **Admin config UI** | save = live; needs a clear confirmation because it is immediate | date picker, a "pending changes" view, and a cancel-before-commencement flow | version history browser, diffing, possibly rollback |
| **Reporting / audit** | audit row per change; loan → audit via `terms_config_audit_id` | same, plus scheduled-vs-applied reporting | richest, and the only one answering "what was possible on date X" |

The cost asymmetry is the point: 4a is one row and one audit insert. 4b adds a scheduling mechanism,
loses the cardinality constraint that §2 relies on, and introduces a boundary instant that must be
defined in a timezone. 4c adds a resolution step to every configuration read in the system.

### 6. Worked example — rate changes from 12% to 18%

An admin edits the rate on **15 September**, intending it to commence **1 October**.

| Loan | 4a Immediate | 4b Future-dated | 4c Versioning |
|---|---|---|---|
| Approved 10 Sep (before the edit) | 12% | 12% | 12% (v1) |
| Approved 20 Sep (after the edit, before the intended date) | **18%** | **12%** | **18%** (v2) |
| Approved 2 Oct | 18% | 18% | 18% |

Under **4a** the cooperative's intent is not enforced: the 20 September loan is priced at 18% because
that is what the configuration says, and the only remedy is operational — the admin should not save the
change until 1 October. The audit row shows exactly when it was saved, so the discrepancy is visible
after the fact rather than hidden.

Under **4b** the system enforces the intent, and the 20 September borrower gets the rate the meeting
agreed was in force.

On ₦300,000 over 12 months, `FLAT`, that difference is ₦36,000 versus ₦54,000 of interest — **₦18,000
to one member.** That is the size of the question. It is not a reason to choose 4b by itself, because
4a plus admin discipline reaches the same outcome; it is the reason the choice should be deliberate.

**Under all three options, the 10 September loan keeps 12% forever.** That guarantee comes from the
Class B columns in §5, not from the dating model — which is precisely why 4a is sufficient for the
stated financial rule.

---

## Decision 5 — Historical loan backfill

### 1. The exact question

When the Class B columns are added to `loan`, should already-approved loans have them populated with the
zero-interest terms those loans were actually issued under, or left `NULL`?

### 2. Available options

| Option | Policy |
|---|---|
| **5a** | Backfill every approved loan: rate `0.000`, method `NONE`, interest `0.00`, total = `amount`, tenure = `duration`, installment = `repay_amount` |
| **5b** | Leave `NULL` for all pre-Phase-4 rows; readers treat `NULL` as "issued before configuration existed" |
| **5c** | Backfill only rows whose existing arithmetic verifies; leave the rest `NULL` and **report them** |

### 3. What the code does today

`loan` has none of these columns. Every approved loan was issued at zero interest by construction —
`approveLoan` has no rate — so a backfill of zeroes is a statement of fact rather than an assumption.
That is what makes 5a defensible here and would not make it defensible for `shares`, where no share
price ever existed and any value would be invented.

### 4. Recommendation

**Option 5c** — 5a with a verification gate, executed as a separate approved data step after a
read-only survey, in the pattern of [`phase2-pre-migration-survey.sql`](phase2-pre-migration-survey.sql).

The gate is `repay_amount × duration ≈ amount`, within a tolerance of one kobo per installment. Rows
that verify are backfilled. Rows that do not are left `NULL` and listed, because a loan whose stored
installment does not reconcile with its principal is a finding worth a human decision, not a row to
paper over with a computed guess.

This falls under the standing constraint that existing production data is not silently modified: it
needs explicit approval, and the survey runs first.

### 5. Effects

| | 5a Backfill all | 5b Leave NULL | 5c Verified backfill |
|---|---|---|---|
| **Database schema** | columns nullable either way | columns nullable, `NULL` is permanent for old rows | columns nullable; `NULL` means "did not reconcile" |
| **Loan calculation** | unaffected — no recalculation, terms are recorded not recomputed | unaffected | unaffected |
| **Existing loans** | every approved loan gains complete terms | old loans show blank interest fields forever | most gain complete terms; exceptions are known and listed |
| **Future loans** | unaffected | unaffected | unaffected |
| **Admin config UI** | nothing | member statements need a "not applicable" state | same as 5b for the exceptions only |
| **Reporting / audit** | uniform reports; `SUM(applied_interest_amount)` is correct across all time | every report must branch on `NULL`; totals silently exclude old loans | uniform except for a listed, countable set |

The reporting row is the practical argument. Under 5b, `SUM(applied_total_repayable)` across the loan
book omits every pre-Phase-4 loan and returns a number that looks complete and is not.

### 6. Worked examples

| Loan | `amount` | `duration` | `repay_amount` | `repay × duration` | Outcome under 5c |
|---|---|---|---|---|---|
| A | ₦150,000 | 5 | ₦30,000.00 | ₦150,000.00 | ✓ backfilled: rate 0, interest 0, total 150,000, installment 30,000 |
| B | ₦100,000 | 3 | ₦33,333.33 | ₦99,999.99 | ✓ backfilled (off by ₦0.01, inside tolerance): total ₦100,000. **Also flagged** — this is a Decision 3 loan that cannot currently be completed |
| C | ₦200,000 | 6 | ₦30,000.00 | ₦180,000.00 | ✗ left `NULL` and reported — ₦20,000 unaccounted for; needs a human, not a default |

Loan B is the case that shows why 5c is worth the extra step: the same query that decides the backfill
also enumerates exactly which live loans are affected by the residual defect, which is information
needed for Decision 3 regardless.

---

## Decision 6 — Penalty rules: in scope or deferred

### 1. The exact question

Does Phase 4 include late-repayment penalty rules — stored, and charged — or does
`organization_repayment_config` ship without them?

### 2. Available options

| Option | Scope |
|---|---|
| **6a** | Out of scope. No penalty columns in Phase 4 |
| **6b** | Configuration only: rules stored and displayed, never charged |
| **6c** | Full: rules stored, lateness detected, a penalty charge generated |

### 3. What the code does today

No penalties anywhere. `RepayServiceImpl` has no concept of lateness; `Loan` holds `installmentsPaid`
and `EndDate` but nothing compares them to a schedule, and there is no scheduled job that could.

### 4. Recommendation

**Option 6a — defer to its own phase.**

A penalty is a **new financial event, not a setting.** Charging one requires: a row of its own so it can
be audited, waived and reported; a mechanism to detect lateness (a scheduler, or lazy evaluation on
read, each with different consequences for when a member sees the charge); a decision on whether
penalties compound; a waiver flow with its own authorization; and a rule for how the charge relates to
`applied_total_repayable` — which is immutable under §5 and therefore **cannot absorb it**. That is
comparable in size to the interest engine itself.

**6b is the worst option and should be rejected explicitly.** Rules that are displayed to members but
never charged are a promise the system does not keep. Members will read "1% per week late" as a fact
about their loan, and the first time it is not charged, the rule is discovered to be decorative — or
worse, someone later switches on enforcement and members are charged under a rule they were shown
months earlier.

### 5. Effects

| | 6a Deferred | 6b Config only | 6c Full |
|---|---|---|---|
| **Database schema** | no penalty columns | penalty columns on `organization_repayment_config`, unread by any service | those columns **plus** a penalty charge table with its own tenant column and audit |
| **Loan calculation** | unchanged | unchanged | outstanding balance becomes principal + interest + accrued penalties, from separate sources |
| **Existing loans** | unaffected | unaffected | must define whether already-late loans are charged retroactively — a live financial decision on real members |
| **Future loans** | no penalties | no penalties, but rules shown | penalties accrue |
| **Admin config UI** | nothing | fields that do nothing | fields, plus a waiver screen and an authorization rule for who may waive |
| **Reporting / audit** | nothing | nothing to report, rules recorded in config audit | penalty income becomes a reportable line; waivers must be audited as financial events |

### 6. Worked example

Installment of ₦28,000 falls due 1 September. The member pays on 20 September. Rule: "1% of the overdue
installment per week late."

- 19 days late is **2 complete weeks** → `1% × 28,000 × 2 = ₦560`
- 19 days late is **2.714 weeks** → `1% × 28,000 × 2.714 = ₦760.00`

The granularity is undefined by the rule as stated, and the two readings differ by ₦200 on a single
installment. That ambiguity is a whole decision of its own, before the harder question:

**Where does the ₦560 live?** It cannot go into `applied_total_repayable`, which is ₦336,000 and
immutable. It cannot be quietly added to `loan.balance`, which is the principal ledger and would make
the penalty indistinguishable from unpaid principal in every report. It needs its own row, with its own
timestamp, actor and audit trail — which is the argument for deferring it to a phase that can design
that properly.

---

## Summary

| # | Decision | Approved | Reversible later? |
|---|---|---|---|
| 1 | Interest method | **`FLAT` only**, enum carries all three values | Yes — adding `REDUCING_BALANCE` is a service change if the column ships now |
| 2 | Rate basis | **Per annum**, no basis column | Yes — a basis column can be added, with historical rows backfilled `PER_ANNUM` |
| 3 | Residual allocation | **Final installment absorbs**, computed as `total − sum(earlier)` | Partially — changing it later re-prices new loans only; already-approved schedules stand |
| 4 | Effective dating | **Immediate**, `effective_date` kept on the audit row | Yes for 4b, expensively — it drops `UNIQUE (organization_id)`. 4c is effectively one-way |
| 5 | Historical backfill | **Verified backfill**, survey first, explicit approval | Yes — a backfill can be run at any later date |
| 6 | Penalties | **Deferred** to their own phase | Yes — nothing depends on them |

Decisions 1, 2 and 3 are the ones that must be right before the first loan is approved under the new
engine, because together they determine the immutable Class B figures on every subsequent loan. 4, 5
and 6 can be revisited with less cost.

### What has been implemented since these were approved

Stage 1 only: the schema, the entities, the repositories and the tests. Concretely, migrations
`V3`–`V10` (the exclusion table added mid-Stage-1 pushed historical terms from `V10` to `V11` — see
`phase4-tenant-business-configuration.md` §9), the eight configuration entities and their repositories,
and the offline tests that pin the decisions above — `OrganizationConfigurationModelTest` for the model
invariants and `OrganizationConfigConstraintTest` for the database constraints.

Decisions 1, 2 and 3 are not yet *executed* anywhere, because nothing calculates interest yet: they are
recorded in the schema (the `InterestMethod` enum, the `numeric(6,3)` per-annum rate columns) and
enforced by tests, and the arithmetic they describe arrives with the Stage 4 interest engine. Decision
3 in particular has no column and never will — it is platform arithmetic, not configuration.

**No production row has been created or modified, and no service, controller, DTO or frontend file
reads any of this yet.** Decision 5's backfill has not been run and Decision 5's survey has not been
executed against production. Decision 6 is enforced by absence: the two tests above fail the build if a
penalty column or field appears before the enforcement does.
