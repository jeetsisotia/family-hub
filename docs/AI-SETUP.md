# AI assistant: setup, Google access, security and cost

## 1. Each person's own Gemini key (BYOK)

The app no longer contains any AI key. Each family member who wants AI answers adds their own free key:

1. Open **aistudio.google.com** and sign in with a Google account.
2. Tap **Get API key**, then **Create API key**.
3. In Family Hub: **AI → ⚙ (AI settings)**, paste the key, then tap **Test and connect**.

How the key is handled:
- It is tested with one tiny request before it is saved.
- It is encrypted with the phone's **Android Keystore** (AES-256-GCM) and stored for that signed-in person only.
- It is never written to Firestore, never synced to other members, never logged, and is excluded from Android backup and device transfer (`backup_rules.xml`, `data_extraction_rules.xml`).
- **Test** checks it again, and **Disconnect** removes it from the phone.

Signing in with Google, or having a Gemini app subscription, does **not** give Gemini API quota by itself. The key's own Google project and its limits apply.

## 2. Shared family key: not available (needs a secure server)

The brief allows a shared key only behind a secure backend or proxy. Family Hub runs on Firebase's free Spark plan, which has no server code. Cloud Functions and similar need the paid Blaze plan, which this project does not use. A shared key inside the app or in Firestore could be copied by anyone with the APK, so it is **not offered**, and AI settings says why.

To add it later, these would be needed:
- a server (for example Cloud Functions on Blaze, or another hosted service) that holds the key
- a check of the caller's Firebase ID token and family role
- per-member limits
- forwarding requests to Gemini

**Status: NEEDS EXTERNAL SETUP.**

The old shared key in GitHub secrets (`GEMINI_API_KEY`) is no longer put into the APK, and the build no longer reads it. The "Check Gemini API key" build step was removed on 27 Nov 2026 (it kept hanging). The `GEMINI_API_KEY` secret can be deleted from GitHub (Settings → Secrets and variables → Actions); nothing uses it.

## 3. Models, limits and cost

- **Model:** "Automatic" tries the cheapest free flash models (`gemini-flash-lite-latest`, then `gemini-flash-latest`, then others the key can use) and **never switches to a paid-only model**. A specific model can be chosen from the key's own model list.
- **Limits on the phone** (AI settings):
  - a daily limit (100 by default) and a monthly limit (1,500 by default)
  - at most 8 requests a minute
  - usage shown as requests, tokens and web searches, today and this month
- **Google's own limits** for the key's project also apply, and Google returns "limit used up" errors that the app shows plainly. Per Google's documentation, limits are counted per project, not per key.
- **Cost:**
  - A project **without billing** stays free.
  - On the free tier, Google says prompts may be used to improve its products.
  - With billing on, Google charges for use.

## 4. Web search

- Uses Gemini's **Grounding with Google Search** tool.
- It is **off** until turned on in AI settings, and then chosen per question.
- **Sources** come only from Google's grounding data and are shown as links. Each answer says whether it came from web search or from the model's own knowledge.
- Per Google's pricing pages, Search grounding is **not available on the free tier for Gemini 3 models**. Paid projects get 5,000 free searches a month across Gemini 3 models, then $14 per 1,000. When the key cannot use it, the app says so.
- **Checked live on 27 Nov 2026** (GitHub → Actions → "AI live check" run #2, using the older `GEMINI_API_KEY` secret on GitHub, not the key on your phone):
  - A general question and a follow-up were answered by `gemini-flash-lite-latest`.
  - Web search was refused with "quota exceeded" (HTTP 429) on both free models.
  - The older 2.x model names are no longer available.
  - The app's message for this case was misleading ("your limit is used up", although normal answers still worked). It now says that web search is not available on this key's free quota and to turn web search off. No source is ever shown unless Google's grounding data returned one.

## 4a. Live check

GitHub → Actions → **AI live check** → Run workflow. It sends the app's request format to Gemini:
- a general question
- a web-search question, which needs real sources
- a follow-up
- an invalid key

It uses the `GEMINI_API_KEY` repository secret if one is set, and the key is never printed. It runs only by hand, so it never spends quota on its own. Your own key stays encrypted on your phone; this check cannot use it.

## 5. Gmail, Google Drive and Google Calendar (optional, read-only)

- **Opened from** the **Google** button in the AI chat. It is separate from Family Hub's sign-in and from the Gemini key.
- **Consent:** asked on Google's own consent screen the first time, per service. The scopes are the smallest read-only ones: `gmail.readonly`, `drive.readonly` and `calendar.readonly`.
- **No send, write or delete access:**
  - Reply drafts appear in the chat for the person to copy and send themselves.
  - Calendar suggestions are only text.
- **Tokens:** Google's short-lived access tokens stay in memory only and are never stored.
- **Revoking access:** Google Account → Security → Your connections to third-party apps → Family Hub → Delete all connections. AI settings has a "Manage Google access" link.
- **Prompt injection:** fetched emails and files go to the model marked as data, with an instruction to ignore any instructions inside them. The chat itself can never change Family Hub data.

**Setup needed once (NEEDS EXTERNAL SETUP),** in the Google Cloud project of the app's Android OAuth client (the same one Gmail import already uses):
1. **APIs & Services → Library:** enable **Gmail API**, **Google Drive API** and **Google Calendar API**.
2. **OAuth consent screen → Data access:** add the three read-only scopes above.
3. While the app is in **Testing**, add each family member's Google account as a **test user**. Google then asks for consent again about every 7 days.
4. Gmail and Drive read-only scopes are "restricted". Publishing the app for everyone would need Google's verification and a security assessment, which is not needed for a private family APK in Testing.

## 6. FamilyHub actions

- **FamilyHub actions** (from the AI chat): adding expenses, income, bills, reminders, tasks, events, shopping and so on.
- Each proposed action is shown as a card and saved **only after the person taps it**. The app says "Added ✓" only after the database save succeeds; failures are shown.
- Shared records follow the family's Firestore rules, and an admin-only action (the budget) is refused for others.
- Only the signed-in person's active family is used.
- With **FamilyHub data** turned on in the chat, the assistant gets a summary of family money, dues, events, tasks and shared reminders. It never includes health records, medicines, the food diary, ID numbers, private notes, "Only me" reminders or the emergency card.

### 6a. Changing a transaction's category (both AI screens)

Phone test of 27 Nov 2026: adding a task worked, but asking the AI to change a Finance category failed. Root causes:
1. **No such action existed.** The action list could only *add* records (expense, task, reminder…), so Gemini had nothing to offer for "change the category".
2. **The Gemini chat was read-only.** Its instructions told the model it "cannot change Family Hub data" and to send the user to FamilyHub actions. That is the redirect seen on the phone. It also had no action cards at all.
3. **The model could not see the transaction.** It only gets a 10-day spending summary without record ids, so it could not find a test transaction.

How it works now, in **both** AI screens (FamilyHub actions and the Gemini chat), through one shared path (`FinanceActions`, `runAiAction`, `undoAiAction`, `AiActionCard`):
- "Change the category of Swiggy ₹645 to Shopping" (also "move X to Y", "X ki category Y kar do", "X ko Y mein daal do") is read **on the phone**. No AI call and no web search are needed, and nothing from Finance is sent to the model for it.
- The transaction is looked up among the entries the person can see in Finance. Deleted entries, transfers and card payments are never offered. The model never supplies a record or an id.
- If nothing matches, the assistant says so and that nothing was changed. If several match, each is shown as a card (date, amount, method, card, member, current category) and the person taps **Change** on the right one. "Do all" is never offered for these.
- The card shows the real entry and "Category: Dining → Shopping". Nothing is written until **Change** is tapped.
- On **Change**:
  - Finance permission and the entry are checked again; if its category changed in the meantime, nothing is written.
  - The write uses the same code as Finance → Transactions: one update of `category`, `subcategory` and `updatedAt`, with the change logged for Undo. It appears in Finance → Categories → Recent changes too.
  - **"Changed ✓" appears only after the server accepted the write and a fresh read from the server shows the new category.** Offline, the card says "Not confirmed" instead.
- **Undo** puts the old category back through the same log, only if nobody changed it again since, and is confirmed the same way.
- If Gemini itself suggests a category change (kind `recategorize`), the app replaces it with cards for the real entries found on the phone.
- In the Gemini chat, "add …" requests use the same action format, with only today's date and the family members' names sent to the model.
- Firestore rules are unchanged; new rules tests cover this exact write (an approved member may; pending members, strangers and signed-out users may not; "Only me" entries only by their owner).

## Security check (28 Nov 2026)
- No Gemini or other AI key in the source or APK (searched for `AIza…` keys: only the Firebase key in
  `google-services.json`, which is a public project identifier).
- AI actions show a card and run only after you tap it; they are re-checked just before running (membership, Finance
  editing, and section access: none/view-only sections are refused).
- "Only me" records of other people are never in the assistant's data; Gmail access is read-only and only on request.
- PR #2 (moving Gemini to Cloud Functions) is **closed and not merged**: it needs the paid Blaze plan. AI Logic stays off.
- If a key was ever pasted into a public place, revoke it in Google AI Studio → API keys.
