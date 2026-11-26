# Family Hub: Master Prompt for Claude Code

Continue development of my existing native Android app, **Family Hub** (Kotlin, Jetpack Compose, Firebase, package `com.familyhub.app`).

- **Repository:** https://github.com/jeetsisotia/family-hub
- **Firebase project:** `family-hub-1465f`
- **Use:** only me and my family, shared as a private APK built through GitHub Actions. No Play Store release.
- Work only on this repository. My separate Supabase-based FamilyHub project is out of scope.

## How to work: autonomous mode

I will be away from my laptop and Chrome. **Do the whole task yourself, from Phase 1 to Phase 8, without stopping for my approval, access requests or routine questions.**

- Phases are the order of work, not stopping points. Check and test each phase, record the result, then start the next.
- Make design, technical and feature choices yourself. Choose the safest zero-cost option and record why.
- If something is blocked, do not wait. Skip only that item, continue all other work, and list it at the end with the shortest steps I can do from my phone.

### GitHub, Firebase and tools
- Use the GitHub, Firebase and other tools already available to you. Use GitHub Actions for builds and APKs where this environment cannot do them. Stay within free limits and check GitHub Actions limits for this repository.
- At the start, check your access: repository read and push, pull requests, Actions runs, Firebase deployment and required network domains. Report anything missing, then continue with everything else.
- Use only access I have connected or configured. Never invent, search for or expose passwords, tokens or keys.

### What I authorise
- Create branches, commit, push, open pull requests and run builds.
- Merge a phase into main after its build and automated tests pass. Tag the earlier version so it can be restored.
- Deploy Firestore rules and indexes after emulator tests pass, only for `family-hub-1465f`. Save the old version for rollback. Rules must not become weaker. If rules cannot be tested, do not deploy them.

This does **not** authorise payments or billing, deleting or rewriting live user data, creating or replacing signing keys, force-pushing, deleting repositories or Firebase resources, or publishing the app or making the repository public. For anything that needs these, prepare it, leave it undone and tell me.

### Save progress and resume after a usage limit
- At the start, if you have a scheduling tool, create a one-time task to resume this work at **11:35 PM IST on 26 November 2026**. A limit may stop you before you can create it later. If that time has passed, use the next 11:35 PM IST. Confirm whether it was created. If you cannot create it, say so clearly and do not claim a retry is set.
- Keep `PROGRESS.md` in the repository with completed work, test results, pending items and the next step. Commit and push after each meaningful step, using a working branch for unfinished code.
- On resume, read this prompt and `PROGRESS.md`, check the repository state and continue without repeating finished work. If nothing remains, make no changes. Save a copy of this prompt in the repository, without secrets, so a new session can use it.

## Non-negotiable rules

### 1. Zero cost
- Firebase stays on the **Spark plan**. No Blaze, billing account, card, paid API, subscription, domain, or trial that later charges.
- A free quota that requires a billing account does not count as free.
- Prefer native Android, on-device processing, existing Firebase services and free open-source libraries.
- Before adding any service or implementing a new feature, check current official pricing and show: subscription needed, billing account or payment method needed, free plan, usage limits, and alternative. If it cannot work properly for free, skip it and tell me why.
- Add safeguards against excessive Firestore reads and writes (paging, limited listeners, caching).
- No Firebase Cloud Storage, AWS S3, Cloudinary, Supabase Storage or similar. Do not store files as Base64 in Firestore.
- No Cloud Functions or other billing-dependent backend.

### 2. Data safety and updates
- Never delete or reset existing user data. Any data migration must be backward compatible, with a tested backup first.
- Keep the application ID, signing configuration and Google Sign-In compatibility. A new APK must update the installed app without data loss.
- Phone-only checks do not block later work. Mark them **Needs my phone**.
- Never create a new signing key or commit keys, passwords or service-account files. If signing is not set up correctly, stop and tell me.

### 3. Security and privacy
- Never weaken Firestore rules or security to make a feature work.
- Enforce family-level access and private versus shared records in the rules, not only in the UI.
- No unnecessary permissions or data collection.

### 4. Login (preserve exactly)
Google sign-in, optional fingerprint lock with device authentication fallback, existing Firebase user IDs, invite code and QR joining, admin approval, and Super Admin, Family Admin and Member roles. No separate app PIN.

## Existing features to preserve
- **Finance:** bank accounts, cash, wallets, cards, loans, EMIs, repayment schedules, income, expenses, categories, subcategories, budgets, savings, due calendar, reminders, SMS and Gmail detection, CSV, Excel and Axio imports, duplicate detection, reconciliation, receipt and payment proof photos, personal and family views, dashboard, reports.
- **Health:** Health Connect, steps, distance, sleep, calories, activity, heart rate, weight, BMI, water, medicines, reminders, manual vitals, family sharing, health reports and photos.
- **Family:** profiles, roles, invitations, shared data, reminders, tasks and other implemented functions.
- **Also:** AI assistant, local finance query engine, JSON backup and restore, Firestore and Room architecture.

---

## Phase 1: Audit and fix what is broken

1. Review the repository, main branch, `PLAN-V2.md`, `docs/FEATURES.md`, `docs/LIMITATIONS.md`, `docs/INSTALL.md`, `docs/TESTING.md`, previous Claude instructions, dependencies, Firebase setup, Firestore rules, database models, backup and restore, signing and the build workflow. If a listed file is missing, tell me.
2. Create a feature inventory: **Working / Partially working / Not implemented / Blocked by cost**. Mark whether each status is verified by test or only by reading code.
3. I had a Google login failure on my phone. Check whether the cause is fixed, and give me exact steps for anything I must do in a console.
4. Search the existing code for errors: crashes, build and lint warnings that matter, failed tests, broken screens, and sync problems.
5. **Check financial accuracy in the existing code:** account, wallet and card balances; income and expense totals; transfers and card bill payments counted twice; refunds and reversals; loan and EMI schedules; budgets; dates, month boundaries and time zones; rounding (no floating-point money calculations); SMS, Gmail and statement parsing; duplicate detection; reconciliation; report and dashboard totals. Add tests that reproduce each error before fixing it. Do not change or delete saved transactions or balances; list proposed data corrections for me to review later.
6. Fix errors and partial features, starting with login, data loss, security, finance accuracy and sync. Give me a list of each error, its cause, the fix and its test result. List larger items that belong in later phases.

## Phase 2: iPhone-style design with glass UI

The app stays native Android (Kotlin and Compose) but should look and feel like a premium iPhone app. This is a redesign of existing screens, with no change to data or working logic.

- First design Home, Finance and a chat screen in light and dark, check them, then apply the design to all screens. Include screenshots in the final report if the environment can produce them.
- **One reusable design system:** colours, type, spacing, rounded corners and shared components.
- **Glass effect:** translucent, blurred top bar, floating bottom bar, bottom sheets, dialogs and key cards over soft gradients. Native Android or a free open-source blur library is fine after checking its licence and device support. On older or slow phones, use a plain translucent or solid fallback.
- **iPhone-style behaviour:** large collapsing titles, grouped list settings, segmented controls, bottom sheets, swipe actions, search bars, smooth transitions, subtle haptics. Keep the Android back gesture working.
- Bottom navigation: Home, Finance, Health, Chats, Family, with other items under More.
- **Readability and speed come first:** strong text contrast, especially for money figures; support large text and reduced motion; add a "Reduce transparency" setting; no lag while scrolling.
- Do not copy Apple-owned assets: no SF Pro font, SF Symbols, Apple emojis, Apple logos or the iMessage and FaceTime names. Use free licensed fonts and icons.
- Improve loading, empty and error states.

## Phase 3: Family chat (WhatsApp, Telegram and iPhone messaging style)

A real family messaging feature, separate from the AI assistant, using Firestore within Spark limits and the Phase 2 design.

- **Core first:** one-to-one and family group chats, history, offline send and sync, sending and failed states with retry, unread counts, reply, edit, delete for me, delete for everyone.
- **Look and feel:** rounded message bubbles, grouped messages, date separators, chat list with last message and time, swipe to reply, long-press menu, jump to latest.
- **Emojis:** emoji picker with search and recent emojis, emoji reactions, large display for emoji-only messages. Use free Android emoji libraries.
- **Then:** forward, copy, drafts, mentions, pinned chats and messages, starred messages, search, mute, archive, delivered and read receipts, typing indicators, simple polls, bundled free-licensed or self-made text-free stickers.
- Reduce or skip any item that uses too much quota. State the search limitations.
- No paid GIF or sticker services. No image, video, file or voice note attachments unless there is a genuinely free and secure storage method.
- Only chat participants can read a one-to-one conversation, including against admins. Test this in the rules.
- Do not describe chat as end-to-end encrypted unless it really is.
- **Notifications:** sending FCM push needs a trusted server. Never put a service-account key or server credential in the APK, and do not use Cloud Functions. Check what can work reliably at zero cost with the app in the background or closed. Explain delays, battery impact and Android restrictions, and use a best-effort method only if it is free, secure and clearly labelled with its limits.

## Phase 4: Finance improvements

Errors are fixed in Phase 1. Now improve the existing screens: reconciliation, SMS, Gmail and statement imports, duplicate detection, categorisation, reports, charts and performance. Retest all Phase 1 finance fixes. Add automated tests using made-up or anonymised data.

## Phase 5: Video and voice calling (FaceTime-style)

I want this feature. Build it with free open-source WebRTC, Android to Android, with signalling through Firestore within Spark limits.

1. First do a short feasibility and dependency check, including that the WebRTC library is free and maintained. If it passes, build calling.
2. **Calling:** one-to-one video and voice, incoming and outgoing, accept and decline, call history, missed calls. Group calls are out of scope unless reliable at zero cost.
3. **Screen:** full-screen video, draggable small self-view, floating glass control bar, mute, speaker, Bluetooth audio, camera on/off, front and rear switching, picture-in-picture, call timer, connection and reconnecting status.
4. Handle permissions, privacy indicators, busy, no answer and network loss.
5. **Network limits:** no paid TURN, calling SDK or service, and no unreliable public TURN server. A free public STUN server may be used after you explain its limits and privacy impact. Without TURN, some calls, especially on mobile data, may not connect. Show a clear failure message.
6. **Incoming calls:** ringing when the app is in the background or closed may not be reliable without a trusted server. Follow the Phase 3 notification rules and tell me exactly what works.
7. Test on two real phones: same Wi-Fi, different Wi-Fi and mobile data. Label calling **Beta** and list what fails. Do not call it complete until it is reliable. Never weaken the zero-cost rule to improve it.

## Phase 6: Family safety, live location and emergency alerts

Build these with native Android and existing Firebase within Spark limits. Check Android background, battery and permission rules first.

- **Emergency contacts:** add, edit, order and choose contacts, including people who do not use the app. Each person controls their own contacts and settings.
- **Low battery alert:** send an SMS to chosen emergency contacts when battery reaches a level I set (default 15%) and is not charging. Include name, battery level, time, and last known location with its time, as a standard map link. Send once per low-battery episode, with a cooldown, and keep a sent, delivered if available, or failed log. Verify that the trigger works with the app closed; do not assume a battery broadcast will wake it.
- **Live location:** share with chosen family members for 15 minutes, 1 hour, 8 hours or until stopped. Show last update time, accuracy, battery level and stale status, with a stop button. Limit update frequency to protect battery and Firestore quotas, and delete expired location points. This new temporary data may be deleted automatically.
- **People without the app:** SMS can send only a location snapshot, not live tracking. Do not send repeated location SMS by default.
- **SOS:** large button with a cancellable countdown that alerts chosen contacts by SMS and family chat, starts live sharing, and offers a call button. Open the dialer for 112; never call or message emergency services automatically.
- **Other safety features:** "I'm safe" check-in, request check-in, safe arrival timer with an alert if I do not confirm, optional arrive and leave place alerts, home screen or quick-settings SOS shortcut, and an opt-in emergency card (blood group, allergies, notes, contacts). Never send emergency card data to AI or include it in SMS unless I turn that on.
- **Consent and privacy:** location features are off by default. Each member must enable sharing on their own phone. No hidden tracking and no admin override. Show a persistent notification while tracking, and enforce who can see location in Firestore rules.
- **SMS and maps cost:** use only the phone's own SIM, with clear notice that carrier SMS charges may apply, and a limit on repeat messages. No SMS gateway. Open locations in an installed maps app; add no maps SDK, API or tile service that needs billing or breaks its usage terms.
- **Permissions:** ask for SMS, location and background location only when the feature is enabled, and explain why. Include phone steps for restrictions on privately installed APKs and battery optimisation. Handle no SIM, no signal, location off and denied permission.
- **Honest limits:** alerts cannot be promised if the phone is off, the battery dies, the app is force-stopped, permission or signal is missing, or Android blocks background work. Show this in the app. Skip fall or crash detection unless it can be shown to be reliable.
- **Testing:** use simulated sending. Do not send real SMS to real contacts. Mark real SMS, background location and low battery behaviour **Needs my phone**, with a test checklist.

## Phase 7: Data reliability, calendar, reminders and dashboard

- Improve offline access, sync, conflict handling, duplicate prevention, backup, restore, export, import and recovery from interrupted operations. Explain backup privacy.
- Extend the existing calendar and reminders: birthdays, anniversaries, family, school and work events, appointments, insurance renewals, EMI, card and utility dues, recurring and shared reminders.
- **Date and time in reminders:** every reminder type must have a date and time picker, show the time in lists, the calendar and notifications, and allow all-day reminders. Add repeat options (daily, weekly, monthly, yearly, custom), multiple times a day for medicines, early alerts, snooze, mark done, and shared reminder assignment.
- Reminders must work with the app closed and after a phone restart, within Android limits. Handle exact alarm and notification permissions, time zone changes and month-end dates without duplicate alerts. Keep existing reminders and do not invent times for old ones; treat date-only reminders as all-day.
- Home summaries: finance, upcoming bills and EMIs, health, events, tasks, unread messages, important reminders, safety status and SOS, and missed calls only if calling is built.

## Phase 8: More free features

Suggest any other useful features that pass the zero-cost check, along with the items below. Rank them by value and effort, then build those that are useful, reliable and testable. List the rest.

- **Family:** household tasks, shopping lists, announcements, shared goals, memories (no paid media storage).
- **Health:** food and calorie diary, nutrition, fitness goals, trends, medicine history and adherence. Never invent readings or give a diagnosis.
- **AI assistant:** keep the current AI setup. Do not move to a paid backend or to Firebase AI Logic. Recognised finance queries stay local. Never send health records, ID numbers, private notes or sensitive data to AI. Use only the current Gemini free tier, check its limits and data-use terms, add request limits and clear errors, and never fall back to a paid model. Review API key handling and explain the remaining risk honestly.
- **Local documents (optional):** only with Android's document picker and private app storage. Support open, rename, categorise and delete. Clearly show that files stay on this device and are not shared or cloud-synced. Explain what happens on uninstall or phone change. Include them in backup only if it can be done safely. If this is complex or unreliable, skip it.

---

## Quality target: fully working, zero known errors

- I need a fully functional app where every included feature works correctly.
- Fix every error you find, then rebuild and retest. Repeat until the build and all automated tests pass with no known crashes or finance calculation errors.
- Never get a pass by deleting or disabling tests, hiding errors, using mock data or removing working features.
- Every visible button and screen must work. Hide unfinished features.
- Do not promise zero errors where you could not test. If something cannot be fixed or verified, say so plainly.

## Testing for every phase
- Run build, automated tests and emulator tests where the environment supports them. Test Firestore rules with the emulator.
- Retest affected existing features. As relevant: login, fingerprint, invites, roles, finance, SMS and Gmail imports, duplicates, Health Connect, reminders and their times, safety alerts, location sharing and privacy, backup and restore, offline use, chat access and APK update.
- A successful compile does not mean a feature is complete.
- Label every item **Passed / Failed / Not tested / Needs my phone**. Never claim a test you did not run.

## Reports
Record these in `PROGRESS.md` after each phase, and give me one combined report at the end, readable on a phone:

1. What already worked, what was fixed or improved, and what is new.
2. What was excluded or deferred, and why (especially cost).
3. Test results.
4. Security and privacy findings.
5. Free-tier limits and technical limitations.
6. APK build status, where to download it on my phone, install and update steps (export a backup first), and my phone test checklist.
7. Pending items and anything only I can do.

In the final report, also include the document storage decision.

**Final priority:** zero cost, data safety, privacy and reliability come before more features. If something cannot be delivered properly for free, leave it out.

**Start now: check access, set the 11:35 PM resume task, then begin Phase 1 and continue through all phases.**
