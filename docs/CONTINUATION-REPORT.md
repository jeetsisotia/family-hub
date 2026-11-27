# Continuation report

For picking up the work after a usage limit or a new session. Read this file and `PROGRESS.md`, fetch `main`, and continue from the first unfinished task. Do not repeat finished work.

## Current brief

`FamilyHub — Master development & release prompt` (27 Nov 2026), plus the "Final completeness requirement":
- make an inventory first
- verify, don't assume
- keep all working features and data
- Finance needs historical category reassignment, date ranges, attachments, account and card details, and accurate totals
- one shared AI layer
- backup and restore stay backward-compatible
- show evidence for every claim

## State (updated with each bundle)

| | |
|---|---|
| Starting commit | `6e8cdbb` (build #63 passed) |
| Latest bundle | p43 (report). Code: p42 `ca703ec`; p41 `6663968`; p40 `d18184c`; p39 `b86ccef`; CI `cbd9555` |
| How code reaches GitHub | Bundles through the upload page and `apply-bundle.yml` (see PROGRESS.md). Workflow files can only be changed on the upload page itself, not through a bundle |

## Tasks

| # | Task | Status |
|---|---|---|
| 1 | Inventory of every module, screen, route and integration (`docs/INVENTORY.md`) | **Done** |
| 2 | Finance: move existing transactions to another category (multi-select in Transactions, "Apply to existing" in Categories, rename and delete carry past entries along), with a count in every confirmation, Undo, and a permission re-check | **Code done, tests pass** (ledger-test 138; Unit tests run #1 on GitHub). Build #64: running |
| 3 | Finance: account or card filter in Transactions | **Code done**. Build: pending |
| 4 | Finance → Duplicates: reasons, confidence, which to keep, "Not duplicates", confirmation with a count, Undo; the AI uses the same finder and the same safe removal | **Code done, tests pass**. Build: pending |
| 5 | Home and More: one place for each feature; Apple-style grouped lists | **Code done**: More is a grouped list without the repeated Loans, Accounts and SOS; Home without the repeated money card, shortcut row and task and document cards, plus Recent; hub screens and Finance → Manage as grouped lists. Screenshots: pending |
| 6 | CI: remove the hanging Gemini check; name screenshots by real screens, light and dark; unit tests on every push | **Done** (commit `cbd9555`; Unit tests run #1 passed) |
| 7 | AI action registry: permission, confirm, audit, undo for every data-changing action | **Code done**: every AI action re-checks membership and Finance permission just before writing; a local log of AI actions with success or failure is shown in AI settings; duplicate removal can be undone. Undo for records the AI adds: not built (they can be deleted normally) |
| 8 | Docs: AI-SETUP, LIMITATIONS, TESTING, INSTALL reconciled | **Done** |
| 9 | Final report with PASS / FAIL / NOT TESTED / NEEDS MY PHONE / NEEDS MY INPUT / BLOCKED | **Done**: `docs/FINAL-REPORT-3.md` |
| 10 | Shared module list screen as one inset grouped list | **Done**: build #67 passed, Reminders screenshot checked |

## Next exact step

Brief 3 is reported in `docs/FINAL-REPORT-3.md`. Remaining, in order:
1. Apple-style pass on the Insights header card and the detail sheets.
2. Tailored AI suggestions for every page.
3. Undo for records the AI adds.

Also the phone checks listed in the report.
