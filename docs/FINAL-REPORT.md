# Family Hub: final report

27 November 2026. This covers all 8 phases of `docs/CLAUDE-PROMPT.md`. Details for each phase are in `PROGRESS.md`.

**Status labels:** **Passed** (tested and works), **Failed**, **Not tested**, **Needs my phone** (can only be checked on a real phone).

---

## 1. What worked, what was fixed, what is new

### Already working (kept and retested where tests exist)

- Finance: accounts, cash, wallets, cards, loans, EMIs, budgets, due calendar, SMS, Gmail and statement imports, backup and restore.
- Health: Health Connect, steps, vitals and medicines.
- Family: profiles, roles, invites and QR joining.
- The assistant's local money answers.
- Shopping lists, chores, notices, goals, memories (text) and the step goal.

### Fixed

| Problem | Phase | Status |
|---|---|---|
| The app closed right after Google sign-in (Android 16) | 1 | Fix built. Retest: **Needs my phone** |
| Any signed-in Google account could list every family and its invite code | 1 | **Passed** (rules tests), live since 26 Nov |
| Money added as decimals gave tiny wrong balances (for example 0.0000000000000000555) | 1 | **Passed** |
| Refund SMS counted as income | 1 | **Passed** |
| Pictures stored inside database records (against the rules) | 1 | **Passed** (new pictures are saved as files on the phone) |
| Four screens worked out "spent" differently | 4 | **Passed** |
| "Find duplicates" grouped different shops | 4 | **Passed** |
| Repeats on the 31st and on 29 February drifted to the 28th for good (reminders, events, birthdays, renewals, insurance premiums) | 7 | **Passed** |

### New

| Phase | What | Status |
|---|---|---|
| 2 | iPhone-style glass design: large titles, grouped Settings, segmented controls, swipe actions, Reduce transparency, dark mode | Builds and screens open: **Passed**. Look and feel: **Needs my phone** |
| 3 | Family chat: group and one-to-one chats, offline send, reply, edit, delete, reactions, pins, stars, search, @mentions, polls, "Seen" | Rules: **Passed**. Two phones: **Needs my phone** |
| 4 | Finance: one set of totals everywhere, a better duplicate finder, refunds in reports | **Passed** |
| 5 | Voice and video calls (**Beta**) | Build and rules: **Passed**. Calls: **Needs my phone** |
| 6 | Family safety (more below) | Logic and rules: **Passed**. SMS and location: **Needs my phone** |
| 7 | Reminders and calendar (more below) | Logic: **Passed**. Alarms: **Needs my phone** |
| 8 | Food diary, medicine adherence, AI request limit, private reminders kept out of AI | Logic: **Passed**. Screens: **Needs my phone** |

**Phase 6, family safety:** emergency contacts, low-battery SMS, live location sharing, SOS with a cancellable countdown, check-ins, a safe-arrival timer, an emergency card, and a Quick Settings tile and app-icon shortcut for SOS.

**Phase 7, reminders and calendar:** a time picker with "All day", times shown everywhere, "every few days" repeats, early alerts, a Done button on notifications, event alerts, and a Home "Today" card.

---

## 2. Left out, and why

| Item | Why |
|---|---|
| Photos, files and voice notes in chat; photo memories | Firebase Storage needs the paid plan, and files inside the database are not allowed |
| Instant chat notifications and call ringing when the app is closed | Sending a push needs a trusted server (Cloud Functions are paid). Chat checks about every 15 minutes; calls ring only while the app is open |
| Typing indicators and "delivered" ticks | Too many database writes for the free quota. "Seen" is built instead |
| Image stickers | No free storage. Large emoji cover this |
| Group calls; a relay server (TURN) for calls | No reliable free option. Some networks (often mobile data) cannot connect |
| Arrive and leave place alerts | Would need location access all the time. Location stays off unless you share |
| Fall or crash detection | Cannot be made reliable |
| Nutrition breakdown | Would need a food database or invented numbers |
| Rebuilt reconciliation screen, new report types | The existing ones had no known errors; left as they are |
| In-depth data-reliability work (sync conflicts, interrupted restores) | Reviewed only, not changed. Backup and restore work as before |

---

## 3. Test results

| Tests | Checks | Result |
|---|---|---|
| Money engine (balances, cards, loans, totals, duplicates, month ends) | 78 | **Passed** |
| SMS and email alerts, statement import | 15 + 22 | **Passed** |
| Chat mentions | 14 | **Passed** |
| Assistant money questions | 22 | **Passed** |
| Safety logic | 64 | **Passed** |
| Reminder rules | 40 | **Passed** |
| Health and AI limit | 18 | **Passed** |
| Firestore rules in the emulator (on GitHub) | 79 | **Passed** (run #7) |
| APK builds #52 (calls), #53 (safety), #55 (reminders) | — | **Passed** |
| Emulator screen check (Android 14, 21 screens with sample data) | — | **Passed**: no crash. The screenshots were not reviewed by eye. |
| Anything on a real phone | — | **Needs my phone** (checklist in section 6) |

Every result above was run. Nothing is claimed that was not run.

---

## 4. Security and privacy

- **Families can no longer be browsed.** Joining looks up one invite code. Fixed and live.
- **Private data stays private, admins included** (rules tests check each one):
  - one-to-one chats and calls (only the two people)
  - "Only me" records
  - shared locations (only the people chosen, only until the share expires; nobody can list who is sharing)
  - safety settings
- **Live location:** off by default, and each person turns it on on their own phone. A notification stays visible while sharing, there is no admin override, and no trail of old points is kept.
- **Chat and calls are not end-to-end encrypted.**
  - Chat is protected by the rules and Google's encryption.
  - Call audio and video go directly between the phones, scrambled by WebRTC.
  - A free Google STUN server sees both phones' internet addresses.
- **AI:**
  - Health records, medicines, the food diary, ID numbers, private notes, the emergency card and "Only me" reminders are never sent.
  - Limited to 8 questions a minute and 150 a day per phone.
  - The Gemini key is inside the APK, so someone with the APK could extract it and use its free quota. This cannot be avoided without a server. If it happens, change the key and rebuild.
- **Permissions** (SMS, location, camera, microphone) are asked only when a feature is turned on. The permission to place calls was removed: 112 and contacts open the dialer.
- The repository is public. No keys, passwords or service-account files are in it.

---

## 5. Free-tier limits and technical limitations

- **Firebase:** Spark (free) plan only, with no billing. Recent use was about 6,700 reads and 2,600 writes a day, against free limits of 50,000 and 20,000. New features were designed for few writes:
  - live location: at most 1 write a minute
  - chat: listeners only on open screens
  - the Today card: no extra reads
- **SMS:** sent from your own SIM. **Carrier charges may apply.** There is no SMS gateway.
- **Background work:** Android can delay these by minutes in Doze or battery saver, and stops them if the app is force-stopped:
  - the low-battery check (about every 15 minutes)
  - chat checks
  - the daily reminder refresh
- **Exact-time reminders** are scheduled 36 hours ahead and survive a restart and a time zone change.
- **Calls:** Beta. They need the app open on both phones, and some networks cannot connect without a relay server.
- **Gemini:** free tier only; Google may use prompts to improve its products.
- **GitHub Actions:** free for a public repository. **Known problem:** the old "Check Gemini API key" build step can hang (3 times in the last 4 builds). See section 7.

---

## 6. APK, install and phone checklist

**Build status:** the newest code builds (#55 passed; the Phase 8 build follows this report).

**Download on your phone:**
1. Open github.com/jeetsisotia/family-hub and sign in.
2. Go to **Actions → Build APK**, then the newest run with a green tick.
3. Under **Artifacts**, download **family-hub-apk** and unzip it.

**Update without losing data:**
1. In the current app, open **Settings → Data & backup → Back up**. This is for admins, so the family admin should do it.
2. Install the new APK over the old one. Allow "Install unknown apps" for your browser or Files app if asked. It is signed with the same key, so it updates in place.
3. Open the app, sign in if asked, and allow notifications.

**Phone checklist (Needs my phone):**
1. **Login:** the app no longer closes after sign-in. Fingerprint lock still works.
2. **Family admin:** open the app once, which turns on the invite code. Ask family members to update.
3. **Design:** check light, dark and Reduce transparency (Settings → Appearance) for easy-to-read text.
4. **Chat between two phones:** messages, unread counts, Retry when offline, a poll, an @mention, and the notification within about 15 minutes.
5. **Merchant category:** change one transaction's category, choose "Apply to all", then import an SMS from the same shop.
6. **Calls with the app open on both phones:** same Wi-Fi, different Wi-Fi, mobile data, Bluetooth, and leaving the app during video.
7. **Safety:** use only a willing family member's number for SMS tests. The steps are in `PROGRESS.md` → Phase 6 checklist:
   - test SMS, low battery with the app closed
   - live location to one person
   - safe-arrival after a restart
   - SOS: cancel, then once for real
   - the tile and the shortcut
8. **Reminders** (steps in `PROGRESS.md` → Phase 7 checklist):
   - on time with the app closed
   - Done and Snooze
   - after a restart
   - an event alert
   - Home → Today
9. **Food diary and Medicines:** add a meal with calories, then mark a dose Taken and check the 7-day figure.

---

## 7. Pending, and what only you can do

1. **Fix the build step that hangs** (changing CI files was refused from Claude's side). On github.com, edit `.github/workflows/build-apk.yml`:
   - add `timeout-minutes: 2` and `continue-on-error: true` under `- name: Check Gemini API key`
   - add `--max-time 20` to its two `curl` lines

   Until then, a stuck build can be cancelled and re-run.
2. **Check the Gemini key's project in Google AI Studio:** no billing account linked.
3. The phone checks in section 6.
4. **Scheduled resume task:** only the first Claude account's task exists (27 Nov, 11:35 PM IST). Everything in the 8 phases is done, so that task should find nothing to change. Pause it if you prefer.
5. **Old refunds:** refunds saved before the Phase 1 fix still count as income. Use the assistant's "Merge refunds" to review them. Nothing changes without your tap.

---

## Document storage decision

**Decision: no file storage for documents, in the cloud or on the phone.**

- **Documents and Vault** keep their details (name, number, expiry and reminders), shared through Firestore as before. Photos (for example of an ID or a receipt) are saved as files in the app's private storage on the phone that added them, with only a short reference in the record.
- **Why no cloud files:** Firebase Storage and similar services need a paid plan, and storing files inside the database is not allowed.
- **Why no local document manager** (opening PDFs with Android's file picker): files would sit only on one phone, be lost on uninstall or phone change, and could not safely be included in backups without making them very large. That fails the "reliable" test, so it was left out. Photos already work this way and are clearly labelled "on the phone that added it".
- **What this means for you:**
  - Keep originals in Google Drive or DigiLocker.
  - Use Family Hub for the details and reminders.
  - Before changing phones, export a backup: details and on-phone photos are included.
