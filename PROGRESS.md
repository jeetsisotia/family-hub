# Family Hub: progress log

The working brief is [`docs/CLAUDE-PROMPT.md`](docs/CLAUDE-PROMPT.md) (updated 26 Nov 23:39 to 8 phases: Phase 6 is now family safety, live location and emergency alerts). This file records what is finished, the test results, what is pending and the next step, so a new session can carry on without redoing work.

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

**Second account (27 Nov 2026, 1:20 AM IST).** The work moved to another Claude account, using a handoff note.
- Reading the repository and the Actions runs works.
- Pushing from the workspace is refused, so code still goes up as an uploaded bundle through Jeet's Chrome, which works.
- The scheduled resume task could not be created from this account (refused by a permission check). The first account's task (27 Nov, 11:35 PM IST) is the only one.

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
| Money engine, merchant rules, totals and duplicates | 74 | Passed |
| SMS and email alerts | 15 | Passed |
| Statement import | 22 | Passed |
| Chat mentions | 14 | Passed |
| Assistant questions | 22 | Passed |
| Firestore rules (emulator, on GitHub) | 49 | Passed (run #4); 66 of 66 with calls (run #6) |
| Safety logic | 64 | Passed (27 Nov) |
| Reminder rules (repeats, times, early alerts) | 40 | Passed (27 Nov) |
| Health and AI limit | 18 | Passed (27 Nov) |

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

### Phase 1 results

- **APK build #47:** passed, including the emulator screen check.
- **Rules tests, first run:** failed on setup, not on the rules. The emulator refused a rules file outside its folder. `firebase.json` now sits next to `firestore.rules`.
- **Rules tests, run #2 (26 Nov):** passed, 46 of 46 in the Firestore emulator on GitHub Actions.
- **Rules published (26 Nov, 11:34 PM IST)** to `family-hub-1465f` after the tests passed. The live text was checked afterwards and matches the tested file exactly.
  - Rollback copy of the previous live rules: `firebase/firestore.rules.backup-2026-11-26-live`, also kept in the console's rules history ("Today, 2:59 PM").
  - What changed: invite codes (`/invites`), chats, and families can no longer be listed or searched. Nothing became weaker.
  - Rules Playground retest: **Not tested** (the 46 emulator tests cover the same cases).
- **Poll rules published (27 Nov, 12:23 AM IST)** after 49 of 49 emulator tests passed (run #4). The live text matches the tested file exactly. Rollback copy: `firebase/firestore.rules.backup-2026-11-27-before-polls`.

---

## Phase 2: iPhone-style design (in progress)

### What is in it

- **One design system** (`core/theme/DesignSystem.kt`): spacing and corner sizes, plus shared parts used across screens:
  - segmented control
  - grouped list sections and rows, as in iPhone Settings
  - search field
  - loading, empty and error states
  - red count badge
  - light haptics
- **Reduce transparency** (Settings → Appearance): cards and bars turn solid and the moving background stops. It is kept on this phone.
- **Large titles shrink** into a slim frosted bar as a page scrolls. This works on every page that uses the shared title bar. Conversations keep a slim title.
- **Settings** now uses iPhone-style grouped sections, with a segmented Automatic / Light / Dark control.
- **Bottom bar:** a light tap feel when switching tabs, and a red count on Chats for chats with new messages. The count uses the same listener as the chat list, so it costs no extra reads.
- **Chats list:**
  - swipe right to pin, swipe left to archive
  - new search field
  - proper empty and loading states
- **Money and titles** use equal-width digits, so amounts line up.
- **Reduced motion** is respected: the background glows and tab icon bounce stay still when Android animations are off.

### Choices and why

- **No blur library.** The free blur library (Haze) needs a newer Kotlin than the app uses, and a failed build costs a whole cycle. Glass stays translucent, with the solid fallback above. This is the plain fallback the brief allows.
- **Fonts and icons are Android's own** (Roboto and Material icons, free licences). No Apple fonts, symbols, emoji or names.

### Screenshots and checks

- **Build #49:** passed. It compiled the Phase 2 design, the chat build fix and merchant categories, and the emulator photographed every screen.
- **Review of the #49 screenshots found 2 problems, both fixed in bundle p24:**
  1. In dark mode, text without its own colour was black and nearly invisible: page titles, "Welcome", list titles. The theme now sets the default text colour for light and dark.
  2. The chat list's swipe background ("Archive") showed through the see-through rows. It now appears only while a row is being swiped.
- **Looked right in the review:** Settings (grouped sections, segmented theme control), Home with Reduce transparency (solid cards), the chat list, and a conversation (bubbles, reactions, reply, pinned message, "Seen by").

The automatic emulator check now photographs:
- the chat list and a conversation (sample messages, no account)
- dark Home, Finance, Health, chat list and conversation
- Home with Reduce transparency
- the new Settings

---

## Finance: one category for the same merchant (asked 26 Nov 23:39)

- **What it does:** change a transaction's category and the app asks: "Use Dining for Swiggy?" It shows how many other Swiggy transactions have a different category. You can choose:
  - **Apply to all:** changes those transactions too, up to 300 at a time.
  - **Only this one.**
  - A tick box, on by default, to also use the category for new Swiggy transactions from SMS and statement imports.
- **Remembered rules** are your own private records, so they are included in your backup and cost no extra reads.
- **Matching:**
  - "SWIGGY", "swiggy@icici" and "Swiggy order from Meghana Foods" count as one merchant.
  - "Indian Oil" and "Indian Bank" stay apart.
  - Transfers, card bill payments, deleted entries and refunds are never changed.
- **Tests:** 17 new checks in `tools/ledger-test`; all 60 ledger checks pass. Trying it in the app: **Needs my phone**.

---

## Phase 4: finance improvements (bundle p25)

The Phase 1 finance fixes were kept and retested (all 60 earlier ledger checks still pass). New in this phase:

- **One set of totals everywhere.** Before, four screens worked out "spent" and "income" in slightly different ways, so the same month could show different figures. Finance, Money Insights, the Finance header on the Finance list, the Home "Monthly Family Expenses" card, the assistant's money summary and the budget alerts now all use `Ledger.totals`:
  - amounts are added in whole paise
  - "Not an expense" entries, transfers, card bill payments, deleted entries and unpaid bills are left out
  - a refund that arrives lowers spending in the month it arrives, and is never income
  - a refund merged into a purchase lowers that purchase
- **Insights:** a note under the category chart says how much was refunded, and the CSV export now includes refunds. "Top places" groups "SWIGGY", "swiggy@icici" and "Swiggy order" as one shop. The totals are worked out once per period instead of on every redraw, which keeps scrolling smooth with a long history.
- **Duplicates:** "Find duplicate transactions" used to group any two entries with the same type, amount and day, so two real payments at different shops were offered for removal. It now uses the payment matcher (same direction and amount within a day and a half, then the same reference number, card ending or shop name). Nothing is removed without your tap on each card.
- **Tests:** 13 new checks in `tools/ledger-test` (74 in all): totals, refunds, filters, the paise drift case, and duplicate groups including a 5,000-entry speed check. **Passed.**
- **Not done in this phase:** a rebuilt statement-reconciliation screen, new report types and new charts. The existing ones were checked and left as they are, since they had no known errors. Real Gmail and SMS inbox imports remain **Needs my phone**.

---

## Phase 5: voice and video calls (Beta, bundle p26)

**Can it be done for free? Yes, with limits.** The calls use WebRTC (`io.github.webrtc-sdk:android`, free, BSD licence, kept up to date, no account or sign-up). Firestore carries only the call setup, so it stays inside the Spark plan.

What is in it:
- A phone and a camera button at the top of every one-to-one chat, and a **Calls** screen (phone icon at the top of Chats) with everyone in the family and the call history (missed calls in red, with length).
- Incoming call screen with Answer and Decline; outgoing "Calling…", then "Connecting…", then a running timer.
- Controls: mute, speaker, Bluetooth headset (when one is connected), camera on/off, front/rear camera, end call. Your own video is a small window you can drag.
- Leaving the app during a video call keeps it in a floating window (picture-in-picture).
- A "Call in progress" notification while a call runs, as Android requires.
- No answer after 45 seconds: "No answer", and it shows as a missed call for the other person. On another call: "busy".
- If the connection drops: "Reconnecting…" for up to 15 seconds, then a clear message.

**How it connects, and the limits (said in the app too):**
- A free public Google STUN server (`stun.l.google.com`) helps each phone find its own internet address. It does not carry the call, has no guarantee, and sees both phones' internet addresses.
- There is **no relay (TURN) server**, because every reliable one costs money. Where both phones are behind strict networks (common on mobile data), the call cannot connect. The app then says: try both phones on the same Wi-Fi.
- **Calls only ring while Family Hub is open** on the other phone. Ringing a closed app needs a server push (Cloud Functions or a paid service), which is not allowed here.
- Audio and video go directly between the two phones and are scrambled by WebRTC (DTLS-SRTP). The app does **not** call this end-to-end encrypted.

**Privacy and rules:** only the two people on a call can read its record or its connection details, not admins and not other members (17 new rules tests). A call can only be started to an active member of the same family. Connection details are deleted when the call ends; the short record (who, when, how long) stays for the history.

**Status:**
- **APK build #52: Passed**, including the emulator screen check. It was the first real compile of the calls code, and it needed no fixes.
- **Rules tests run #6: Passed**, 66 of 66.
- **Call rules published** (27 Nov, 1:00 AM IST). On 27 Nov at 1:25 AM the live text was checked against the tested file: they match exactly (same SHA-256). The rollback copy is `firebase/firestore.rules.backup-2026-11-27-before-calls`, also kept in the console's rules history ("12:23 AM").
- Two-phone tests are **Needs my phone**: same Wi-Fi, different Wi-Fi, mobile data, Bluetooth headset, picture-in-picture.

---

## Phase 6: family safety, live location and emergency alerts (bundle p27)

### Free-cost check

| Part | How it works | Cost |
|---|---|---|
| SMS alerts | The phone's own SIM (Android `SmsManager`). No SMS gateway or service. | Free from Family Hub. **Carrier SMS charges may apply**, and the app says so. |
| Live location | Android's own `LocationManager` (no Google Play location library, no maps SDK). One small Firestore document per person. | Spark plan. 8 hours standing still is about 96 writes; 1 hour moving is at most 61 writes (tested). The free limit is 20,000 writes a day. |
| Maps | Opens the installed maps app with a standard `geo:` link, or a plain `maps.google.com/?q=` link in SMS. No map tiles or API key. | Free |
| Background checks | Android WorkManager (already in the app). | Free |

### What is in it

- **Family safety screen** (More → Family safety; also from SOS). Everything is **off by default**, and each person turns it on on their own phone. Nobody, admins included, can turn on someone else's sharing.
- **Emergency contacts:**
  - Up to 10, in your chosen order.
  - For each one, choose which alerts they get: SOS, low battery, safe arrival.
  - They do not need the app.
  - The first time, the two emergency contacts from My Profile are offered. The profile is not changed.
- **Low-battery SMS:**
  - Default 15%, adjustable from 5% to 50%. It sends only when the phone is not charging.
  - Sends **once per low-battery episode**. The episode ends when the phone charges or goes 5 points above the level. There is also a **6-hour cooldown**, and all automatic SMS are capped at **10 an hour**.
  - The message includes your name, battery level, time, and your last known location with its time and accuracy, as a map link.
  - **It works with the app closed:** a WorkManager check runs about every 15 minutes and reads the battery directly. The low-battery broadcast is not used, because Android does not wake closed apps for it. Android can delay the check in Doze or battery saver; the app says so.
  - "Test (no SMS is sent)" writes the exact message to the log without sending it.
- **Alert log** (on this phone): every attempt with its result:
  - Sent
  - Delivered, if the network reports it
  - Failed (no signal, airplane mode, network refused)
  - Not sent (no SIM, SIM locked, permission off, hourly limit)
  - Test only
- **Live location:**
  - Share with the family members you choose for 15 minutes, 1 hour, 8 hours or until you stop.
  - A notification stays visible while sharing, with **Stop sharing**.
  - Updates at most once a minute (and only after moving 25 m), plus a sign of life every 5 minutes.
  - Viewers see the last update time, accuracy, battery level, a "Not updating" (stale) label after 12 minutes, and an Open in maps button.
  - **No trail is kept:** one document per person, overwritten each time and deleted when sharing stops. Expired shares cannot be read. "Until I stop" shares expire 30 minutes after the last update, so a dead phone's share ends by itself.
  - Uses no background-location permission. Sharing runs as a foreground service started from the app. If Android stops it (restart, app swiped away), it resumes the next time the app opens, or is removed if its time is over.
- **People without the app** get one location link by SMS, never live tracking or repeated location SMS.
- **SOS:**
  1. Big button, then a **5-second countdown**. Tap the button or Cancel to stop it.
  2. SMS to your SOS contacts. If SMS cannot be sent automatically, your SMS app opens with the message ready to send.
  3. A message in the family chat.
  4. If location features are on, live sharing with the family for 1 hour.
  5. Buttons to call your contacts, and **Call 112, which only opens the dialer**.
  - Emergency services are never called or messaged automatically.
  - The old SOS called your contact directly with the call permission. That is removed, and the permission to place calls is gone from the app.
- **Check-ins:** "I'm safe" posts in the family chat (with a location link only if location is on). "Ask to check in" posts an @mention of the person you choose.
- **Safe-arrival timer:**
  - Choose 15 minutes, 30 minutes, 1 hour or 2 hours, and optionally where you are going.
  - At the time, a notification asks "Did you arrive safely?" with an "I'm safe" button.
  - With no answer in 5 minutes, your chosen contacts get an SMS and the family chat is told.
  - It survives a phone restart.
- **Emergency card (opt-in):** blood group, allergies and notes. **Never sent to the AI assistant.** It is added to SOS SMS only if you turn that on (off by default).
- **Quick SOS:** a "Family SOS" Quick Settings tile, and a long-press "SOS" shortcut on the app icon. Both only open the SOS screen.
- **Privacy:**
  - Contacts, the card and settings are kept on the phone (so alerts work offline), with a private copy in `users/{uid}/safety/settings` that only you can read, so they come back on a new phone.
  - Alerts and location stay off on a new phone until you turn them on there.
  - The alert log and locations are not copied anywhere.
- **Permissions** (SMS, location, notifications) are asked only when that feature is turned on. The screen explains:
  - how to allow "restricted settings" for an app installed from an APK
  - battery optimisation
  - what happens without a SIM, without signal, with location off or with a permission denied

### Left out, and why

- **Arrive and leave place alerts:** reliable geofencing needs background location all the time or the Google Play location library. Left out to keep location off unless you share. (The brief marks this optional.)
- **Fall or crash detection:** cannot be shown to be reliable. Skipped, as the brief says.
- **Instant alerts to family phones that are closed:** these need a server push (paid). The family chat message arrives with the usual chat notification (about every 15 minutes).

### Firestore rules (`locations` block)

- **Who can read:** only the person sharing, and the people they chose while the share has not expired. Admins and the Super Admin get no extra access, and nobody can list who is sharing.
- **Who can write:** only the person, for themselves.
- **Checks on each write:**
  - valid coordinates
  - 1 to 20 viewers, not themselves
  - an end time no more than 9 hours ahead
  - the server's own time
  - no extra fields
- **13 new emulator tests**, 79 in all.
- Rollback copy of the live rules: `firebase/firestore.rules.backup-2026-11-27-before-safety`, also kept in the console's rules history ("1:00 AM").
- **Published 27 Nov, 1:38 AM IST**, after 79 of 79 passed. The live text was checked afterwards and matches the tested file exactly (same SHA-256). Existing data was not touched.

### Tests

| What | Result |
|---|---|
| `tools/safety-test` (64 checks): battery trigger and simulated discharges, cooldown, SMS limit, SMS wording, medical card off by default, upload limits, stale and expired, arrival timer | **Passed** (run in this workspace, 27 Nov) |
| Firestore rules (79, emulator on GitHub, run #7) | **Passed**, 79 of 79, on the first run |
| APK build #53 (commit `1cddcb2`) | **Passed.** The Phase 6 code compiled on its first build. Attempt 1 was cancelled after the old "Check Gemini API key" step hung for 20 minutes on a network call without a time limit (not Phase 6 code); attempt 2 passed every step. |
| Emulator screen check (Android 14): Family safety (light and dark), SOS, and the 18 other screens | **Passed**: no crash on any screen. The screenshots were not reviewed: their download host is blocked from this workspace. |
| Real SMS, delivery reports, low battery with the app closed, live location on two phones, Quick Settings tile, shortcut, safe-arrival after a restart | **Needs my phone** (checklist below) |

### Phone checklist for safety (Needs my phone)

Use a family member's number who knows it is a test.

1. In Family safety, add yourself or a willing family member as a contact. Turn on Low-battery SMS and allow SMS.
2. Tap "Test (no SMS is sent)" and check the log shows the message as "Test only".
3. Set the level to 50% while the battery is below 50% and the phone is unplugged. Close the app. Within about 15–30 minutes, one SMS should arrive, and the log should say Sent or Delivered. Plug in and unplug: no second SMS within 6 hours. Set the level back to 15%.
4. Turn on Location features and share with one family member for 15 minutes. On their phone, Family safety → Shared with you shows you, and Map opens. Tap Stop sharing: you disappear from their list.
5. Start a 15-minute safe-arrival timer, restart the phone, and wait: the "Did you arrive safely?" notification appears; tap "I'm safe".
6. SOS: press, then Cancel during the countdown; nothing is sent. Then let it run once, with only a willing contact chosen for SOS.
7. Add the "Family SOS" tile in Quick Settings, and try the long-press SOS shortcut on the app icon.

---

## Phase 7: reminders, calendar, Home summary and date fixes (bundle p29)

### Errors found and fixed (each reproduced by a failing test first)

| Problem | Cause | Fix | Test |
|---|---|---|---|
| A monthly reminder or event on the 31st moved to the 28th after February **and stayed there**. A 29 February birthday stayed on the 28th even in leap years. | Repeats added one month or year at a time, so each short month cut the day for good. | Every repeat is counted from the original date. The 31st falls on the last day of shorter months and returns to the 31st. | Passed (`tools/reminder-test`, with the old code kept to show the drift) |
| A subscription renewing on the 31st showed as due on the 28th in March, April and May | Same cause, in the money engine (`Ledger`) | Same fix | Passed (`tools/ledger-test`) |
| Paying a monthly insurance premium due on the 31st moved the next due date to the 28th for good | Same cause, when a paid premium moves to the next period | The original day is kept (`premiumDueDay`) and used for each new period | Passed (`tools/ledger-test`) |

No saved dates were changed. Only the next dates worked out from them are now right.

### Reminders

- **Time picker:** a clock replaces typing "18:30". "All day" clears the time.
  - Existing reminders keep their time. Those without one are all-day: they notify at 9:00 as before, and now say "All day".
  - A typed time that cannot be read is refused, instead of being saved and ignored.
- **Times are shown everywhere:** in the reminder and calendar lists (next to the date and in the summary line), on Home, and in notifications.
- **Repeats:** None, Daily, Weekly, Monthly, Yearly, and **Every few days** (with the number of days).
- **Early alert:** 5 minutes, 15 minutes, 30 minutes, 1 hour or 1 day before, as well as the alert at the time.
- **Done from the notification:** a one-time reminder is marked done; a repeating one skips only that day. **Snooze 10 min** was already there.
- **Shared reminders, "For":** on a shared reminder, only that person's phone rings. Reminders with nobody chosen (all older ones) and private ones ring as before.
- **Closed app and restart:** exact alarms for the next 36 hours, re-planned:
  - when the app opens
  - each day
  - after a restart or app update
  - **after a time zone or clock change** (new)
- **No duplicate alerts:** each occurrence has a fixed key. Re-planning every hour for 10 days gave exactly one alarm per day (tested).
- **Medicines:** several times a day, Taken and Snooze were already there and are unchanged.

### Calendar

- Events can now have a **time** and an **alert**: at the time, 15 or 30 minutes before, 1 hour before, or 1 day before.
- Older events have no alert, so they stay silent as before.
- Events copied from the phone's calendar keep their time.
- Birthdays, anniversaries, school, office, doctor and function categories were already there. EMI, card, utility and insurance dues are in the Due calendar.

### Home: "Today" card

- Today's reminders and events with their times ("All day" when there is none). A repeating reminder marked done today is left out.
- Missed calls today (only when calls are used).
- **Safety status** (sharing location, arrival check, low-battery SMS) with an **SOS** button.
- A warning with a "Fix in Settings" button when notifications are off, or when on-time alarms are not allowed (Android 12).
- It uses the records and call history already loaded, so it costs **no extra Firestore reads**.
- Finance, dues, unread chats and health were already on Home.

### Not done in this phase

- **Data reliability** (offline use, sync conflicts, backup and restore, export and import): reviewed only. No changes were made in this bundle.
- **A shared reminder made on another phone:** it reaches your phone's alarms the next time your app opens, or at the daily background check (Android may delay that). There is no instant push, because that needs a paid server.

### Tests

| What | Result |
|---|---|
| `tools/reminder-test` (40 checks) | **Passed** |
| `tools/ledger-test` (78 checks, 2 new for month ends and 2 for premiums) | **Passed** |
| `tools/safety-test` (64 checks) | **Passed** (unchanged) |
| APK build #55 (commit `7185b5f`) | **Passed.** It compiled on its first build. Attempt 1 was cancelled when the "Check Gemini API key" step hung again; attempt 2 passed. |
| Emulator screen check: Home with the Today card, the reminder list, and the other 19 screens | **Passed**: no crash. The screenshots were not reviewed (their download host is blocked here). |
| Alarms on a real phone: closed app, restart, time zone change, Done and Snooze buttons, early alerts | **Needs my phone** |

### Phone checklist for reminders (Needs my phone)

1. Add a reminder for 3 minutes from now with "5 minutes before" off, close the app. The notification comes on time. Tap **Done**: it shows as done in Reminders.
2. Add a daily reminder for 2 minutes from now. When it rings, tap **Snooze 10 min**. It rings again 10 minutes later. Tomorrow it rings again.
3. Restart the phone before a reminder's time: it still rings.
4. Add a calendar event today with a time 20 minutes ahead and "15 minutes before": an alert comes 15 minutes early.
5. Check Home → Today shows them with their times.

---

## Phase 8: more free features (bundle p31)

### Ranked list (value against effort; all pass the zero-cost check)

| # | Feature | Value | Effort | Decision |
|---|---|---|---|---|
| 1 | AI: request limit, and keep "Only me" reminders out of AI | High (cost, privacy) | Small | **Built** |
| 2 | Food and calorie diary | High | Small | **Built** |
| 3 | Medicine adherence (last 7 days) | High | Small | **Built** |
| 4 | Shopping lists, household chores, announcements (Notices), shared goals, memories (text) | High | — | **Already in the app** (checked, unchanged) |
| 5 | Fitness goal (daily steps) and trends | Medium | — | **Already in the app** |
| 6 | Local documents with Android's file picker | Medium | Medium | **Not built** (see the document storage decision) |
| 7 | Nutrition breakdown (protein, carbs and so on) | Low | Medium | **Not built**: would need a food database or invented numbers |
| 8 | Photo memories | Medium | — | **Not possible free**: needs file storage (paid plan) |

### What is new

- **Food diary** (Health & Fitness → Food diary):
  - Fields: what you ate, the meal, optional calories, date, whose, and notes.
  - "Only me" by default.
  - The top shows today's calories and the 7-day average of the calories you entered, and says how many items today have no calories.
  - No calorie guessing, and no diet or medical advice.
- **Medicine adherence** (top of Medicines): the share of doses marked Taken in the last 7 days, and how many were not marked.
  - Course start and end dates are respected.
  - A medicine with no start date counts only from today, so past days are never shown as missed.
  - A dose counts after an hour.
- **AI assistant:**
  - **Request limit:** at most 8 questions a minute and 150 a day per phone, with a clear message. Money questions still answer offline.
  - "Only me" reminders and the food diary are now left out of what is sent to Gemini, like health records, medicines, vitals, periods, ID numbers, private notes and the emergency card.

### AI review (honest status)

- **Setup:** unchanged. Gemini's free tier, called directly with the key from the build. No Firebase AI Logic, and no paid backend.
- **No paid fallback:** the app tries free "flash" models. A model with no free quota is skipped, never paid for. No billing is linked to the key; **Jeet can confirm in Google AI Studio that the key's project has no billing account.**
- **Data use:** on the free tier, Google may use prompts to improve its products. The app sends only non-private family and money summaries.
- **Remaining risk:** the key is inside the APK (lightly scrambled). Someone who has the APK could extract it and use its free quota. This cannot be avoided without a server, which is not free. If that happens, Jeet can change the key in GitHub secrets and rebuild.

### Tests

| What | Result |
|---|---|
| `tools/health-test` (18 checks: adherence, food totals, AI limit) | **Passed** |
| All other plain-Kotlin suites (money 78, reminders 40, safety 64, chat 14, assistant 22, SMS and statement import) | **Passed** (27 Nov) |
| APK build #57 (commit `6fa5ef9`) and emulator screens, including the food diary | **Passed**: compiled on its first build, no crash |
| Food diary and adherence on a phone | **Needs my phone** |

---

## Phases 2–4 (27 Nov 2026, afternoon)

**Phase 2**
- Shopping lists: several lists, quantity, price, who buys it, not available, history, search, estimate.
- Home and vehicle maintenance with service history.
- Calendar month, week and agenda views with clash warnings and filters.
- Subscription manager with status and totals.

**Phase 3**
- Savings goals with contributions and a notification when reached.
- Budget planner with category limits, trend and month on month.
- Money insights with unusual spends and suggestions.
- Reports as CSV and PDF.

**Phase 4**
- The assistant answers planner questions on the phone: schedule, bills, week summary, "Suggest reminders", shopping list from one sentence, category increase, recurring, weekly brief.
- "Find tasks and dates" from Gmail or an attached document.
- Weekly brief screen.

Evidence: `docs/VERIFICATION-REPORT.md` §9. Every phone test is listed in `docs/CONTINUATION-REPORT.md` and in the final report.

**Audit against the master prompt (27 Nov, 17:40)**
- The assistant now answers "How much did we spend on groceries this month?" for that one category, with last month alongside.
- Due soon is a notification centre: overdue first, filters for Overdue and each section.
- Subscriptions show "Paid recently" from real Finance entries.
- Maintenance and vehicles have a warranty start date and a receipt or bill photo, which stays on the phone.

## Brief 5: Priority Zero and Phase 1 (27 Nov 2026)

You asked for Priority Zero and Phase 1 only; Phases 2–4 wait for your go-ahead.

**Priority Zero**
- **AI matching:** "Change the category of Mukul to Shopping" works in 14 English and Hinglish forms. Words the AI copies from the request are removed from the search. When nothing matches exactly, the closest entries are offered as cards. With several matches the chat now shows the question first.
- **AI screen:** the chips no longer wrap; labels are shorter; checked at 1.3× text and 360dp width.
- **Due Summary:** tap to fold and open, checked on the emulator.
- **Forms:** Health, Documents and Reminders forms checked in light and dark; one label shortened.
- **Gemini errors:** each kind of failure has its own plain message.

**Phase 1**
- **Family activity** (More): who added, changed or finished what, filtered by type and person. Never shows "Only me", health or ID records.
- **Document expiry tracker:** a "Remind me" window per document, renewal status and notes, and a summary card on Documents. Notifications say only "A document".
- **Recurring payments** (Finance): suggestions from your transactions. Track saves a Subscription or a weekly reminder after you confirm. Nothing is paid and no transactions are created.

Evidence: `docs/VERIFICATION-REPORT.md` §8.

## AI Finance action fix (27 Nov 2026, 11:09 brief)

Your phone test showed that the AI could add a task but could not change a transaction's category.
- **Why:** there was no "change category" action at all. The Gemini chat was told it cannot change data, and the model only sees a 10-day summary without record ids.
- **Now:**
  - Both AI screens use one shared path.
  - The request is read on the phone and the real transaction is found in Finance. Several matches are listed to pick from.
  - The card shows the real entry and the new category. Nothing changes until you tap **Change**.
  - The write goes through the same code as Finance, and the card says **Changed ✓** only after the server confirms it.
  - **Undo** puts it back, also confirmed by the server.
  - None of this needs web search or the AI.
- **Evidence:** emulator check with a real local database (Build APK #80): 10 of 11 logged checks PASS, and the 11th is shown by its screenshot. Also 157 money checks and the rules tests. Details in `docs/VERIFICATION-REPORT.md` §7 and `docs/AI-SETUP.md` §6a.

## Brief 4: phone feedback, Due Summary, AI checks (27 Nov 2026)

**Your phone tests (confirmed by you, 27 Nov):**
1. The newest APK installed over the old app: **done**.
2. Bulk category change and Undo: **passed**.
3. Removing a suggested duplicate and Undo: **passed**.
4. Home and More checked in light and dark: **done**.
5. Gemini key added in AI settings: **done**.

Live AI answer quality, web search and agent actions are **not** marked as passed until there is test evidence or your confirmation.

### Home: Due Summary (bundle p48)

- **Before:** a "Due soon" strip sat fixed above Home and never scrolled away, and Home's "To pay" list repeated the same card bills further down.
- **Now:** one compact **Due Summary** at the top of Home.
  - It shows how many things are due, the total to pay and how many are overdue.
  - It opens near the top of Home and folds away as you scroll down. A gap between the two limits stops it flickering.
  - Tap it to open or fold it yourself; the automatic choice resumes the next time Home reaches or leaves the top.
  - When folded, overdue items (up to two) stay visible.
  - Each money due opens the same pay sheet as before (Mark paid, Snooze, Reschedule and so on). Other alerts open their screens.
  - **All alerts** and **Due calendar** are one tap away.
- **No repeats:** bills, card bills, EMIs, renewals and premiums come from the same ledger as before. Other alerts (documents, birthdays, medicines, period) are added only when they are not money alerts, so a card bill appears once.
- **Unchanged:** reminders, notifications and the data behind them.
- **Fix (p52):** the first screenshot showed "14 due ·" with the total missing; the line wrapped after "·". It now stays on one line and shows the total short (₹37.4 k, ₹1.2 L); exact amounts are on each line (p52, p54).

### AI checks (p49–p51)

- A manual **AI live check** workflow calls Gemini with the repository secret, never printing it.
- Results:
  - Invalid key error: pass.
  - General answer: pass.
  - Follow-up context: pass.
  - Web search: refused by the free quota (HTTP 429).
- The app now says so plainly instead of a vague error.
- Your own key on the phone still needs your check.

### Evidence

All results with labels: `docs/VERIFICATION-REPORT.md`.

## Brief 3: completeness, Finance categories and duplicates (bundle p38)

Brief of 27 Nov 2026 (master development and release prompt plus the completeness requirement). The inventory came first: `docs/INVENTORY.md` lists every module, screen, route and integration, where each one lives, and what is missing or duplicated. It is checked against the code, not against earlier reports. Ongoing status: `docs/CONTINUATION-REPORT.md`.

### Moving existing transactions to another category

Before this bundle, a new or renamed category never reached past transactions, and there was no way to move several entries at once. Now:
- **Transactions:**
  - touch and hold an entry to start selecting
  - tap others to add them, or tap **All** to select everything shown
  - **Category** moves the selection to another category, including a new one made on the spot
- **Categories → ✓ (Apply to existing transactions)** on any category, built-in or custom:
  - search, a period (All time, Last 3 months, This month) and "Only uncategorised"
  - pick entries one by one or with **Select all shown**
- **Rename** a category: it offers to rename it on past transactions too, with the count.
- **Delete** a category:
  - Its transactions move to a category you choose ("Other" by default).
  - Or "Delete only" keeps them as they are.
- **Every bulk change:**
  - First says how many entries change and how many are left alone (already in that category, deleted, or a transfer or card payment).
  - Changes only the category and subcategory. Amount, date, account, card, notes, receipts and refunds stay, and so does the record itself (same id, no copies).
  - Re-checks that Finance editing is on for you.
  - Goes into **Recent changes** on this phone with **Undo**. Undo only puts back entries nobody has changed since.
  - Totals, budgets and Insights follow at once.
- **Transactions** also has an **account or card filter** (chips for the accounts and cards used in the period).
- New categories appear in both add and edit forms straight away. This was already so, and has been checked in the code.

### Duplicates (Finance → Duplicates)

- **Matching:**
  - It uses the existing payment matcher: same direction and amount within a day and a half, then the same reference, card ending or shop. Amount alone is never enough.
  - Each group shows **why** it looks like one payment (same reference, card ending, shop, saved from SMS and email, minutes or hours apart) and **how sure** it is ("Very likely" or "Possible").
  - It also shows **warnings**: added by different people, typed in at different times, a regular payment at that shop, different reference numbers.
- **Removing:**
  - The suggested entry to keep is the one with the most detail. You can pick another, and untick any entry you want to keep.
  - **Not duplicates** hides a group while it has the same entries.
  - **Remove N** confirms the count. The others go to **Recently deleted** with a note of which entry was kept, and **Undo** puts them back.
- **AI:** "Find duplicate transactions" uses the same finder, shows the same reasons and warnings, and removes through the same safe path.

### Tests

- `tools/ledger-test`: **138 checks, all passed** (27 Nov). There are 36 new ones.
- Category changes:
  - only the category changes, and no record is copied or lost
  - totals follow the change, and total spending stays the same
  - transfers, deleted entries and missing ids are skipped and counted
  - rename and delete carry entries along
  - the "eligible" filter
  - the account filter
- Duplicates:
  - SMS, email and a typed entry of one payment are found together
  - a different shop, a transfer against rent, monthly payments, entries by different people, exclusions and deleted entries are handled
- **Builds:**
  - APK build #64 (p39, commit `b86ccef`): **passed**, including the emulator smoke test.
  - Build #65 (p40, commit `d18184c`): **passed**, with the new screenshot list.
  - Unit tests runs #1 and #2 on GitHub: **passed** (all 8 suites).
- **Screenshots (build #65, inspected by eye):**
  - Home and More in light, dark and Reduce transparency
  - Finance, Transactions (light and dark), Categories and Duplicates (light and dark)
  - Settings (light and dark), Family safety, Health, Family, Chats, AI settings, Reminders, Insights, Cards and SOS
- **Defects found in them, fixed in p41:**
  - the Transactions date chips wrapped ("Cust/om") and pushed the Family chip off screen
  - Duplicates cut entry names short and said "typed in and sms"
  - Home's Recent list was empty for entries without a saved-at time
  - the Insights filter chips wrapped onto three rows
  - the Family tab opened with a large solid-blue invite banner
- **Forms and sheets (p46 and p47):**
  - CI now photographs the add and edit transaction form and the transaction details sheet, in light and dark.
  - The form sheet let the screen behind show through slightly (bottom sheets were about 96% opaque). Sheets, menus and dialogs are now fully opaque in both themes.

---

## Brief 2, Part A: Finance redesign (bundle p33)

The second brief (Finance redesign and a universal AI assistant) is being done in phases. **Audit first** (27 Nov, from the code, not from earlier reports):

| Brief item | Before this bundle |
|---|---|
| Dashboard: month balance, income and expenses, vs last month, top 5, latest 5, month arrows | Partly: one long overview card (bank, cash, cards, loans, dues) above the full list, current month only |
| One Transactions screen with Expenses and Income tabs | **Not implemented** (one mixed list) |
| Custom categories | Partly: name, parent, kind and budget. No icon, preview or duplicate check |
| Cards and accounts | **Already wallet-style**: last 4 digits only, no CVV or full numbers, delete asks first |
| Budget and insights | **Already there**: Insights, budget dialog, CSV export |

### New in this bundle

- **Finance tab is now a compact dashboard:**
  - Mine or Family, with month arrows to go back to any month
  - the month's **balance** (income minus spending)
  - income and expenses, each with **▲/▼ % against last month**
  - refunds noted
  - **budget** progress with the amount left or over
  - the **top 5 categories** with bars
  - the **latest 5 entries**, with View all
  - an **Add transaction** button and the existing Finance tiles (accounts, cards, loans, dues, imports and more)
  - **Nothing is lost:** "All entries, bills, EMIs and transfers" opens the full list as before.
- **Transactions screen:**
  - Expenses | Income tabs
  - This month, Last month and Custom range (date-range picker); the arrows move by month
  - Mine or Family, and the total for the type and period
  - search by name, shop or amount
  - a category breakdown with share and amount; tap a category to filter by it
  - history grouped by day, with each day's total
  - Tapping an entry opens the existing detail sheet: refunds, "use this category for this shop", Ask AI and so on.
  - Deleting asks first and moves the entry to Recently deleted.
- **One shared add and edit form** (bottom sheet):
  - Expense or Income, amount, category chips with icons, an optional description, date, paid from or received in (account or card, by name), and Only me
  - a receipt, collapsed by default
  - Bills, EMIs and transfers keep their type when edited here.
  - It saves the same fields through the same rules as before (`FinanceModule.postProcess`), so balances, card bills and reports are unchanged.
- **Custom categories with icons** (Finance → Categories, or "New" in the form):
  - name, an emoji icon with a live **preview**, and Spending, Income or Both
  - **duplicates are refused** within the same type, built-in or custom, ignoring case and spaces
  - edit, and **delete after a confirmation** that says how many past transactions use it
  - saved as family records, so every member sees them
  - **Past transactions are never changed:** they keep the category name they were saved with.
  - All built-in categories remain. Income now has its own built-ins (Salary, Business, Interest, Rent received, Gift, Other).
- All figures come from the existing money engine (`Ledger.totals`, in paise), so the dashboard, Transactions, Insights and Home agree.

### Tests

| What | Result |
|---|---|
| `tools/ledger-test` (102 checks, 24 new) | **Passed** (27 Nov). New checks cover: the month balance, spending after refunds, transfers, deleted entries and unpaid bills left out, the top 5 in order, the % against last month, the latest 5, month ranges across years, Expenses and Income lists, search by text and amount, the category filter and breakdown, grouping by day, and duplicate custom categories |
| APK build #59 (commit `3f9b2c9`) and emulator screens: dashboard, Transactions (dark), Categories | **Passed**: compiled on its first build, no crash. The screenshots were not inspected (not downloadable from here) |
| On a phone: adding and editing, the custom range picker, how it looks | **Needs my phone** |

### Not changed in this bundle

- Cards and accounts screens (they already meet the brief).
- The compact header for every Finance screen: the new screens use the compact title bar. Older finance screens keep theirs. Their spacing could not be judged without looking at screenshots.

---

## Brief 2, Part B: universal AI assistant (bundle p34)

Full setup, security and cost notes are in `docs/AI-SETUP.md`.

- **No AI key in the APK any more.** The shared key was built into every APK (lightly scrambled). The brief forbids a shared secret on the client, so it was removed from the build. The assistant now uses each person's **own Gemini key (BYOK)**:
  - connect, test, update and disconnect, with Google AI Studio setup steps
  - encrypted with the Android Keystore, per signed-in person
  - excluded from Android backup and device transfer, never synced or logged
  - **What changes for the family:** Gemini answers need each person's own key. Money questions still work offline.
- **Shared family key: not implemented. NEEDS EXTERNAL SETUP.** It needs a secure server (Cloud Functions on the paid plan, or a similar host). AI settings explains this.
- **Provider layer:** an `AiProvider` interface (Gemini now, others later).
  - Model choice: Automatic picks the cheapest free flash model and never switches to a paid one; or any model from the key's own list.
  - A connection test, clear errors (bad key, limit used up, model not available, no internet, timeouts), and a 60-second read timeout.
- **Usage and cost controls:**
  - daily and monthly limits you set (100 and 1,500 by default) and at most 8 requests a minute
  - requests, tokens and web searches counted for today and this month
  - only the last 14 turns are sent, to keep requests small
  - notes on possible charges
- **Universal chat** (AI from More and Home):
  - suggested prompts to start
  - any topic, with follow-ups
  - Markdown (headings, lists, quotes, bold and italic, links) and code blocks with Copy
  - copy, regenerate, and Stop (immediate; a late reply is dropped)
  - attach an image, PDF or text file (7 MB at most)
  - the provider and model shown
  - conversations kept on the phone only, with delete one, delete all, and "don't save"
  - loading, empty, not-connected and error states
- **Web search:**
  - Uses Gemini's Google Search grounding. It must be allowed in settings, then turned on per question.
  - Sources come only from Google's grounding data and are shown as links. Each answer is labelled "From web search" or "From the model's own knowledge".
  - Where the key cannot use search (Google's free tier for Gemini 3 models), the app says so.
- **Gmail, Drive and Calendar** (Google button in the chat):
  - read-only scopes, asked on Google's consent screen only when used, separate from the Family Hub sign-in and from the Gemini key
  - tokens kept in memory only
  - no send, edit or delete access
  - fetched content marked as data (prompt-injection guard)
  - **NEEDS EXTERNAL SETUP:** turn on the Drive and Calendar APIs, add the scopes on the consent screen, and add test users.
- **Follow-up (bundle p36):** answers now **stream** as they are written (Gemini's streaming API), and **Stop** closes the connection and keeps the text so far. Every Finance screen now uses the **compact header** (Accounts, Cards, Loans, Due calendar, Transfer, Insights, SMS and Gmail scan, Statement import).
- **FamilyHub actions:** the existing family assistant (now "FamilyHub actions", which page AI buttons open). Every action needs a tap to confirm, and it says "Added ✓" only after the save succeeds. The universal chat cannot change data, and its FamilyHub data summary leaves out health records, ID numbers, private notes and "Only me" reminders.

### Tests

| What | Result |
|---|---|
| `tools/health-test` (21 checks; 3 new for the usage limits) | **Passed** |
| APK build #60 (commit `e5d6693`) and emulator screens (universal chat not connected, AI settings) | **Passed**: compiled on its first build, no crash (attempt 2; attempt 1 hit the old Gemini-check hang) |
| A real Gemini key, a real conversation, attachments, web search, Google consent | **Not tested here** (no key or Google account in this environment). **Needs my phone** with your own key |

---

## Data reliability review (bundle p37)

This was the item left open in Phase 7. These are the backup, restore and offline-saving paths, read in the code.

### Problems found and fixed

| Problem | Risk | Fix |
|---|---|---|
| **Backups were built from the screen's live data.** A failed or offline load became an empty list without any warning. | A backup could be silently missing entries, and **"Back up, then reset" would then delete data that was not in the backup file**. | Backups (and backup-before-reset) now read every collection **fresh from the server**. If that fails, nothing is saved or reset, the empty file is removed, and the app says to connect and try again. The reset then deletes exactly what the backup holds. |
| **Restore decided "missing" from the same live data**, then wrote with merge. | An entry that only *looked* missing would be **overwritten with its older backup copy**, losing later edits. | Restore checks what exists **on the server** first and adds only truly missing entries. If the server cannot be reached, restore does not start. |
| **The new transaction form and category dialog waited for the server before closing.** | Offline, the sheet stayed open, and tapping Save again created **duplicates**. | A new entry gets its id when the form opens, so repeated saves update the same record. The sheet closes at once: Firestore keeps the write on the phone and sends it when online. A refused save shows a message. |

### Checked and already sound

- An interrupted restore can simply be run again: it adds only entries that are missing, by id.
- A reset runs only after its backup file is saved. Deletion uses batches of 400, and a stopped reset can be repeated from a new backup.
- Backup files are checked to be Family Hub files before restoring, and include pictures kept on the phone.

### Not covered by backup files, by design

Chats, calls, shared locations, safety settings and AI conversations or keys are not in backup files.
- Safety settings have their own private cloud copy.
- Chats and calls stay in Firestore.
- AI keys and conversations stay on the phone only.

### Tests

These paths need Firestore and a phone. They are **NOT TESTED** here beyond compiling, and they are **Needs my phone**:
1. Back up while online: it works.
2. Back up in airplane mode: it says it could not reach the server, and nothing is reset.
3. Restore an older backup: existing entries are unchanged and only deleted ones return.
4. Add a transaction offline, then tap Save twice: only one entry appears.

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

### Added later (bundle p24)

- **@mentions:** typing "@" in the family chat suggests members, and the name shows in bold. The mentioned person is notified even if the chat is muted. Mentions are plain text, so they cost nothing extra. 14 checks in `tools/chat-test`: **Passed**.
- **Polls:**
  - 2 to 6 answers. Tap an answer to vote; tap it again to undo.
  - Bars show the counts, and chat members can see who voted for what.
  - Each vote is one write.
  - The rules let each person set only their own vote, and only for a real answer. 3 new emulator tests, 49 in total.
- **Home:** a "chats have new messages" card above the money summary. It uses the same count as the Chats tab, so it costs no extra reads.

### Not in it, and why

| Item | Reason |
|---|---|
| Typing indicators and "delivered" ticks | They would write to the database every few seconds and use up free quota. "Seen" is built instead. |
| Stickers | Not built. Large emoji-only messages cover this without image files. Image stickers would need storage or bundled artwork. |
| Photos, files and voice notes | No free, secure storage. Firebase Storage needs the paid plan, and storing files inside Firestore is not allowed. |
| Instant push for a closed app | Needs a trusted server (Cloud Functions or the paid plan) |

### Privacy

- Direct chats can be read only by their two people. Admins and the Super Admin cannot read them, and rules tests check this.
- Messages are **not end-to-end encrypted.** They are protected by the rules and by Google's encryption in transit and at rest. The app says so on the Chats screen.

### Rules

- The new chat rules come with 16 more emulator tests, 46 in total. **Passed**, and published (see Phase 1).

### Build

- **APK build #48: failed** on 3 compile errors:
  - two helper names in the chat notification worker
  - a scope clash on the "jump to latest" button
- All three are fixed in the next bundle, with Phase 2.

---

## Next step

1. All 8 phases are done. The combined report is `docs/FINAL-REPORT.md`. Still open: an in-depth review of data reliability (backup, restore, sync conflicts) and the phone checks.
2. **Workflow fix needed (not applied: changing CI files from here was refused).** The "Check Gemini API key" step hung in 3 of the last 4 builds (#53, #54, #55), and each time the run had to be cancelled and re-run. In `.github/workflows/build-apk.yml`, the "Check Gemini API key" step should get `timeout-minutes: 2` and `continue-on-error: true`, and its two `curl` calls `--max-time 20`. Then a slow Gemini reply can never hold up the APK again. Jeet can make this edit on github.com.
3. A resume task is set for **27 Nov 2026, 11:35 PM IST**, in case a usage limit stops the work.

### Restore points (git tags)

- `restore-before-phase-1`: the app before this work started (commit 3cb30fb).
- `restore-phase-1`: Phase 1 finished, the last version whose build fully passed before chat (commit 0b4ecc3).
- `restore-phase-2-design` (c5c7dde), `restore-phase-3-chat-polls` (f17f14e), `restore-phase-4-finance` (the version before calls), `restore-phase-5-calls` (b70c9d0, the version before safety).

From bundle p23, the bundle workflow can tag the version before each bundle (`.fhbundle/tag.txt`). To restore, download that tag's code from GitHub, or ask for a bundle that brings it back. Nothing is force-pushed.

## Pending, and anything only Jeet can do

- **Needs my phone:**
  - Install the new APK over the old one.
  - Sign in.
  - Allow notifications when asked.
  - Confirm the app no longer closes after sign-in.
- **Because the new rules are live:**
  - Open the new app once as the family admin. This turns on your invite code; until then, joining by code says the code was not found.
  - Anyone joining needs the new app. Older versions cannot look up invite codes.
  - Family members should update the app. Older versions keep working for everyday use, but deleting shared records for good is admin-only.
- **Chats (Needs my phone):** send a message between two phones, then check unread counts, Retry when offline, and the notification within about 15 minutes.
- **Reduce transparency and dark mode (Needs my phone):** switch them in Settings → Appearance and check that the text is easy to read.
- **Merchant categories (Needs my phone):** change a transaction's category, pick "Apply to all", then import an SMS from the same shop.
- **Reminders (Needs my phone):** follow the Phase 7 phone checklist.
- **Safety (Needs my phone):** follow the Phase 6 phone checklist. Use only a willing family member's number for SMS tests.
- **Calls (Needs my phone):** with the app open on both phones, call voice and video on the same Wi-Fi, on two different Wi-Fi networks, and with one phone on mobile data. Try mute, speaker, Bluetooth, camera switch, and leaving the app during a video call.

**Liquid Glass and universal search (27 Nov, 22:38)**
- One set of glass pieces now covers dialogs (with a real blur behind on Android 12+), bottom sheets, menus, filter chips, text fields and the add button on every screen.
- Search on Home finds anything in FamilyHub: payments, bills, documents, events, people, shopping and more. It suggests as you type, remembers recent searches, can listen to your voice, and filters by section.
- Dark mode is a deep charcoal instead of pure black.

**28 Nov (Jeet away; "complete all phases")**
- **Chat:** photos, voice messages (with waveform and 1×/1.5×/2× speed) and documents up to about 4.5 MB, a full-screen photo viewer, a "Photos, voice and files" sheet, "typing…" (can be switched off) and forwarding attachments. Files are stored in Firestore parts that only the chat's members can read (free plan, no Cloud Storage). Video is not supported.
- **Calls:**
  - Group calls of up to 4 people: Join/Decline, who is speaking, connection quality, reconnecting and retries; the host can end the call for everyone.
  - A ringing notification while Family Hub is running, and missed-call notifications.
  - Free STUN servers only.
- **Savings challenges:** 52-week, a fixed amount every day or week, and no-spend days counted from Finance.
- **Home screen widgets:** Family overview and Shopping list, with a "Private widgets" switch.
- **Needs Jeet:** publish the new Firestore rules (firebase/firestore.rules; 92 rules tests pass). Until then, attachments, typing and group calls are refused on real phones.
- **Not done:** per-module permissions, the update checker, video messages, and calls that ring when the app is closed (needs a paid server).

- 28 Nov (p77): Per-member section access (None / View only / Full) — rules, app loading, admin sheet, tests.
- 28 Nov (p78–p79): access rules fixed and verified (rules tests 100/100).
- 28 Nov (p80): Update checker + GitHub Releases per passing build.
- 28 Nov (p81): Expense splitting (equal / exact / % / shares, fewest payments, record payment, per trip) and trip planner header.
- 28 Nov (p82): Memories photo albums, reactions and comments on the free plan; split compile fix.
- 28 Nov (p83): Sync status, edit-conflict protection, restore verification, memory photos in backups.
- 28 Nov (p84): Security review — test screen locked to adb, release gate on check results; review notes in docs.
- 28 Nov (p85–p86): CI fixes, releases publishing (build-114, build-116), travel summary fix. Phone checklist in CONTINUATION-REPORT.
