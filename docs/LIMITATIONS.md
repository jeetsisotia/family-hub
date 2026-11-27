# Known limitations and outside approvals

## Integrations that need approval or setup outside the app

| Integration | Works today for | Needed before anyone else can use it | Cost |
|---|---|---|---|
| **Gmail (read-only)** | Google accounts added as **test users** on the project's OAuth consent screen (Google Cloud console → APIs & Services → OAuth consent screen). Up to 100 test users. While the project is in "Testing", Google may ask you to allow Gmail access again about every 7 days. | `gmail.readonly` is a *restricted* scope. Publishing it to the public needs Google's OAuth app verification and a yearly independent security assessment (CASA). | The assessment is usually paid |
| **SMS reading** | Any phone where the APK is installed directly. On some Android versions you first need **App info → ⋮ → Allow restricted settings**. | On Google Play, `READ_SMS` is only allowed for approved uses. "SMS-based money management" is one of them, but it needs a permissions declaration and Google's review, which can be refused. | Free |
| **Health Connect** | Any phone with Health Connect (built into Android 14 and later; an app from the Play Store on Android 9 to 13) | For a Play Store release: the Health Connect declaration form in Play Console, plus a privacy policy | Free |
| **Google sign-in** | APKs signed with the CI key, whose SHA-1 is registered in Firebase | A Play Store release key: add its SHA-1 (and the Play App Signing SHA-1) in Firebase → Project settings | Free |
| **AI assistant (Gemini)** | Each person who adds their **own** Google AI Studio key in AI settings (kept encrypted with the Android Keystore on that phone, never synced, backed up or logged). No key is built into the APK. Money questions work without a key. | A shared family key would need a secure server (not free on Firebase), so it is not built. | Free tier of each person's key, with daily limits |
| **Axio** | CSV or Excel exports from Axio | Axio has no public API. A direct connection would need a partnership with Axio. | — |
| **Automatic bank sync** | Not built | India's Account Aggregator network needs a licensed partner (an FIU agreement). Until then, use SMS, Gmail and statement imports. | Usually paid |
| **Play Store release** | Not set up | A Play developer account, privacy policy, Data safety form, release signing key, and the SMS and Health Connect declarations above | One-time Play account fee |

## Known limitations

### Money
- **Balances depend on what is recorded.** An account is exact only if its opening balance is right and every entry since then has been added or imported. Use **Match** on the account screen to line it up with the bank's balance.
- **SMS formats vary by bank.** The reader handles the common formats (UPI, card spends, credits, card payments, balances and limits). An unusual message may be missed or marked "unclear" for you to check.
- **Scanning is not instant.** It runs when you tap Scan, and the daily background check looks for bills and dues. Payments do not appear the moment the SMS arrives.
- **Statement import** reads CSV and .xlsx files. Old .xls files and PDFs are not read; save them as .xlsx or CSV first.
- **Card minimum due** is 5% of the bill (at least ₹200) unless the bank's alert gives the exact minimum.
- **Spending is in rupees.** There are no multi-currency accounts.

### Health
- **Only what Health Connect has.** Sleep, heart rate, weight and calories appear only if a watch or another app writes them to Health Connect, or you log them yourself. A missing value shows "—" and is never estimated.
- **Steps on the phone** need a step sensor. Distance and calories worked out from steps are labelled "(est.)".
- **Nutrition:** there is a meal planner, but no food or calorie diary.
- **Pictures** (receipts, payment proofs, profile photo) added from this version on are saved only on the phone that added them, in the app's private storage. They are not uploaded, and other phones show "The picture is saved on the phone that added it". They are included in backup files, so a backup moved to a new phone brings them along. Uninstalling the app deletes them unless you made a backup first. Pictures saved by earlier versions stay where they are.
- **Where health data is kept:**
  - Health Connect readings stay on the phone.
  - Health records you log yourself (medicines, vitals, periods and so on) are saved as **Only me** entries.
  - Neither kind ever goes to the AI.

### AI
- **Code changes:** the assistant can make previewed, confirmable changes to settings (categories, the family budget, the theme), plus everyday entries. It cannot change the app's code or run database commands. Your brief asked for that limit. Bigger changes are made in the repository and shipped as a new APK.
- **Privacy on the free Gemini tier:** Google may use what is sent to improve its products. Family Hub sends only the question and the non-sensitive context needed to answer it: never health data, ID numbers or notes. Money questions the app recognises (spend, top category, EMIs, card bills, dues, balances, savings) are answered on the phone and not sent at all. A paid Gemini key removes this use.
- **AI keys:** no key is built into the APK. Each person adds their own Gemini key, which is kept encrypted on the phone (see AI-SETUP.md).
- **AI action cards in the Gemini chat** are kept while the app is open. After a restart the chat text remains but the Change/Undo buttons do not. A category change made by the AI can still be undone from Finance → Categories → Recent changes.
- **Finding a transaction by words:** the assistant matches the words you give against the entry's title, notes, payee, account and card. If the name in Finance is different (for example an SMS "VPA 98xxxx@ybl"), add the amount ("change category of ₹645 to Shopping").

### Family and access
- **Older app versions:** members on an app version older than this release get "Could not delete" when deleting shared entries, because the new rules move shared deletes to Recently deleted. Updating the app fixes it.
- **Restore adds, it does not replace.** Entries that are already there are left as they are. To go back to a backup exactly, reset first and then restore.
- **Only me entries** are stored under your own account, and the database rules let only you read them, including against the Super Admin. A rules test checks this. The one exception is the separate private Documents section, where the Super Admin can also see private documents, as before.
- **QR scanning** uses Google Play services' code scanner. On phones without Play services, type the invite code instead.

### Build and testing
- **Debug-signed APK.** Fine for installing directly. The Play Store needs a release build with its own key.
- **Sample-data screen.** The APK includes a sample-data screen that the automatic emulator test opens. It shows only made-up data and cannot open or change real family data.
- **Real-phone checks.** These were not tested on a real phone in this release: Google sign-in on your phone, your real SMS inbox, Gmail consent, Health Connect with a watch, QR scanning and reminder timing. [TESTING.md](TESTING.md) has a short checklist for them.


## Finance redesign and universal AI (27 Nov 2026)

- **No shared family AI key:** it would need a secure server, which the free Firebase plan does not have. Each person adds their own Gemini key; money questions work without one.
- **Gmail, Drive and Calendar** need a one-time Google Cloud setup (APIs, scopes, test users) before they work. They are read-only. Consent is asked again about every 7 days while the app is in Testing.
- **Web search** may be refused on free keys for Gemini 3 models (Google's rule). This was confirmed live on 27 Nov 2026: the test key's free models refused the search tool with "quota exceeded" while normal answers worked. On paid keys it can cost money after Google's monthly free allowance.
- **Free-tier prompts** may be used by Google to improve its products.
