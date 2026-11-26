# Family Hub: progress log

The working brief is [`docs/CLAUDE-PROMPT.md`](docs/CLAUDE-PROMPT.md). This file records what is finished, the test results, what is pending and the next step, so a new session can carry on without redoing work.

Status words:
- **Passed:** tested and works.
- **Failed:** tested and does not work.
- **Not tested.**
- **Needs my phone:** can only be checked on a real phone.

---

## Access check (26 Nov 2026, 19:45 IST)

| What | Result |
|---|---|
| Read the repository | Yes (public clone) |
| Push from this workspace | **No.** There is no GitHub sign-in here. Changes reach `main` as an uploaded `fhbundle-*.tgz`, which the **Apply code bundle** workflow commits. The upload goes through Jeet's Chrome, so it works only while that Chrome is open. |
| Pull requests | Not from here. The bundle flow commits to `main`. Unfinished code stays in the workspace and goes up only once it builds. |
| Actions runs | Yes, readable through Chrome. The repository is public, so standard GitHub-hosted runners cost nothing and have no minute limit. |
| Firebase deploy | **No CLI access.** Rules can be published only in the Firebase console through Chrome, and only after emulator tests pass. |
| Firestore emulator here | **No.** Its download host (storage.googleapis.com) is blocked in this workspace, so the rules tests run on GitHub Actions instead. |
| Firebase plan | Spark (free). Usage on 25–26 Nov: 6.7K reads and 2.6K writes in 24 h, against free daily limits of 50K reads and 20K writes. |
| Resume task | Created for 26 Nov 2026, 11:35 PM IST |

---

## Phase 1: audit and fixes

### Login failure on Jeet's phone: cause found and fixed

- **What happened:** Crashlytics shows the app **crashed right after sign-in**. It happened twice on 26 Nov at 6:57 AM, on a OnePlus CPH2661 running Android 16.
- **Error:** `IllegalStateException: Attempting to launch an unregistered ActivityResultLauncher … POST_NOTIFICATIONS`.
- **Cause:** after sign-in, the app checked the profile over the network and *then* asked for notification permission. The permission request had been set up inside the screen. If the screen was rebuilt in the meantime, the request no longer existed and the app crashed. A helper that should have stopped on screen close also swallowed the stop signal.
- **Fix:**
  - The notification request is now set up on the activity itself.
  - It is asked first, before any network call, and only while the screen is showing.
  - The profile check now stops properly when the screen closes.
- Google sign-in itself was fine: the build check reports that the signing SHA-1 and the Web client ID match Firebase.
- **Status:** fix built. Retest is **Needs my phone**.

### Security

1. **Families could be browsed:** any signed-in Google account could list every family that had an invite code. That exposed family names, budgets and invite codes.
   - **Fixed:** joining now looks up `/invites/{code}` directly. Families cannot be listed.
   - The rules allow only a single-code lookup.
   - An admin's app creates the invite entry for existing families the first time it opens.
   - **Status:** rules tests written (`firebase/rules-test`, 30 tests). They run in the emulator on GitHub before anything is published; the live rules have not been changed yet.
2. **Pictures stored inside Firestore records** broke the "no Base64 files in Firestore" rule. This covered receipts, payment proofs and profile photos.
   - **Fixed:** new pictures are saved as files in the app's private storage on the phone. The record keeps only a short reference.
   - Older pictures are untouched and still show.
   - Pictures are included in backup files.
   - On another phone, the screen says the picture is on the phone that added it.
   - The family now sees each member's Google photo.
3. **Correction to the earlier docs:** "Only me" entries live under `users/{uid}` and only their owner can read them, including against the Super Admin. A test checks this. Only the separate private documents section lets the Super Admin see them.

### Money accuracy

Each problem below was first reproduced by a test that failed, then fixed. Saved entries and balances were not changed.

| Problem | Cause | Fix | Test |
|---|---|---|---|
| A balance of 0.10 + 0.20 − 0.30 came out as 0.0000000000000000555 | Money was added up as floating-point numbers | All totals are now added in whole paise: balances, card bills, monthly totals and duplicate checks | Passed |
| A card bill paid in two parts (100.10 + 200.20 against 300.30) still showed a tiny amount due, and a "minimum due" of ₹0 | Same cause | Card statement, outstanding, amount due and minimum are worked out in paise | Passed |
| A refund SMS ("Rs 2,000 refunded…") was saved as **income**, so income and spending were both too high | Refund and reversal messages were read as ordinary credits | New refunds and reversals from SMS, email and statements are marked as refunds. They lower spending in the month they arrive and never count as income. Merged refunds are not counted twice. | Passed (3 tests) |

**Proposed corrections for you to review.** Refunds saved before this fix still count as income. The assistant's "Merge refunds" finds them, and nothing is changed without your tap. No automatic data change was made.

### Automated tests (plain Kotlin, run 26 Nov 2026)

| Suite | Checks | Result |
|---|---|---|
| Money engine | 43 | Passed |
| SMS and email alerts | 15 | Passed |
| Statement import | 22 | Passed |
| Assistant questions | 22 | Passed |
| Firestore rules (emulator, on GitHub) | 30 | Not run yet |

### Feature inventory

How each status is known:
- **(T)** a test, the CI build or the emulator screenshots
- **(C)** reading the code only
- **(P)** Needs my phone

| Area | Status |
|---|---|
| Google sign-in, fingerprint lock, invite code, QR join, approval, roles | Working (C, P). The crash after sign-in is fixed (C). |
| Accounts, cash, wallets, transfers, reconciliation | Working (T) |
| Credit cards (statement, due, minimum, partial payments) and debit cards | Working (T) |
| Loans and EMI schedule | Working (T) |
| Due calendar and its actions | Working (T for screens, C for actions) |
| Income, spending, savings and refunds | Working (T). Old refunds need merging. |
| SMS, Gmail and statement/Axio import, duplicates | Working (T for parsing, P for real inbox). Gmail needs test users. |
| Health: Health Connect, rings, trends, manual records | Working (C, P) |
| Backup, restore, recently deleted, resets | Working (C) |
| Assistant (local money answers, Gemini for the rest) | Working (T for matching, P for Gemini) |
| Light and dark theme, reduced motion | Working (T screenshots, C) |
| Glass UI redesign | Not implemented (Phase 2) |
| Family chat | Not implemented (Phase 3) |
| Video and voice calling | Not implemented (Phase 5) |
| Push notifications when the app is closed (chat and calls) | Blocked by cost. Sending FCM needs a trusted server, and Cloud Functions needs the paid plan. |

---

### Phase 1 results so far

- **APK build #47:** passed. It compiled with all Phase 1 changes. The emulator check was still running when this was written.
- **Rules tests, first run:** failed on setup, not on the rules. The emulator refused a rules file outside its folder. `firebase.json` now sits next to `firestore.rules`.

---

## Phase 3: family chat (built, not yet tested on phones)

### What is in it

- **Chats tab.** The bottom bar now reads: Home, Finance, Health, Chats, Family, More.
- **Chat types:** the family group chat, plus one-to-one chats with any approved member.
- **Messages:**
  - Sending works offline. A message shows a clock while sending, and a refused message shows "Not sent. Retry".
  - The latest 60 messages load, with "Load earlier messages" for older ones.
  - Unread counts use a count query, which costs 1 read per chat.
- **Actions on a message:**
  - Reply by swiping or from the long-press menu.
  - Edit, delete for me, delete for everyone, copy and forward.
  - Pin in the chat, and star.
  - Emoji reactions, with 6 quick ones and the full picker.
- **Look:**
  - Grouped bubbles and date separators.
  - Large emoji-only messages.
  - A jump-to-latest button.
  - "Seen" or "Seen by N" under your last message.
- **Kept on this phone only:** pinned, muted and archived chats, drafts and recent emojis.
- **Search:** works within loaded messages. The screen says so.
- **Notifications:** best-effort. A background check runs about every 15 minutes while online and notifies about new messages in chats that are not muted. Android can delay it in Doze or battery saver.

### Not in it, and why

| Item | Reason |
|---|---|
| Typing indicators and "delivered" ticks | They would write to the database every few seconds and use up free quota. "Seen" is built instead. |
| Mentions, polls, stickers | Not built yet |
| Photos, files and voice notes | No free, secure storage. Firebase Storage needs the paid plan, and storing files inside Firestore is not allowed. |
| Instant push for a closed app | Needs a trusted server (Cloud Functions or the paid plan) |

### Privacy

- Direct chats can be read only by their two people. Admins and the Super Admin cannot read them, and rules tests check this.
- Messages are **not end-to-end encrypted.** They are protected by the rules and by Google's encryption in transit and at rest. The app says so on the Chats screen.

### Rules

- The new chat rules come with 16 more emulator tests, 46 in total.
- They are published only after the tests pass on GitHub.

---

## Next step

1. Upload Phase 1 (bundle p21) and build. Add the rules-test workflow and run it.
2. If the rules tests pass, back up the live rules and publish the new ones in the console. Retest the playground cases.
3. Then start Phase 2: the design system and glass UI on Home, Finance and the chat screen.

## Pending, and anything only Jeet can do

- **Needs my phone:**
  - Install the new APK over the old one.
  - Sign in.
  - Allow notifications when asked.
  - Confirm the app no longer closes after sign-in.
