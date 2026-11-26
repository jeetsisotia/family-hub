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
| Latest bundle | p38: Finance category reassignment and duplicates (see below) |
| How code reaches GitHub | Bundles through the upload page and `apply-bundle.yml` (see PROGRESS.md). Workflow files can only be changed on the upload page itself, not through a bundle |

## Tasks

| # | Task | Status |
|---|---|---|
| 1 | Inventory of every module, screen, route and integration (`docs/INVENTORY.md`) | **Done** |
| 2 | Finance: move existing transactions to another category (multi-select in Transactions, "Apply to existing" in Categories, rename and delete carry past entries along), with a count in every confirmation, Undo, and a permission re-check | **Code done, tests pass** (ledger-test 138). Build: pending |
| 3 | Finance: account or card filter in Transactions | **Code done**. Build: pending |
| 4 | Finance → Duplicates: reasons, confidence, which to keep, "Not duplicates", confirmation with a count, Undo; the AI uses the same finder and the same safe removal | **Code done, tests pass**. Build: pending |
| 5 | Home and More: one place for each feature; Apple-style grouped lists | Not started |
| 6 | CI: remove the hanging Gemini check; name screenshots by real screens, light and dark | Not started (must be done on the upload page) |
| 7 | AI action registry: permission, confirm, audit, undo for every data-changing action | Partly: category changes and duplicate removal follow it |
| 8 | Docs: AI-SETUP, LIMITATIONS, TESTING reconciled | Not started |
| 9 | Final report with PASS / FAIL / NOT TESTED / NEEDS MY PHONE / NEEDS MY INPUT / BLOCKED | Not started |

## Next exact step

Upload bundle p38. Check the APK build and the smoke test. Fix any compile errors. Then do task 6 (CI), then task 5.
