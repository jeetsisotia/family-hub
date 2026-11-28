# Firestore rules: review, deployment comparison and rollback

**Status: ready for your approval — not published.** Nothing is published without your explicit OK.

## What `firebase/firestore.rules` (474 lines) adds over the last rules known to be live
The last copy saved from the console is `firebase/firestore.rules.backup-2026-11-26-live` (119 lines). If you have
published newer rules since, compare against what the console shows before publishing.

New since then (each covered by the emulator rules tests, 100/100 in run 46114298444, plus the memories tests in run
46115083621):
- Chat attachments in parts, typing indicators, polls, calls, group calls, safety (check-ins, live location).
- Per-member section access: `perms` map (none/view/edit) for Finance, Calendar, Tasks, Documents, Health, Home,
  Family, Memories, Travel, Chat. View-only cannot write; None cannot read, even by id; restricted members cannot read
  whole item lists (only `type in [allowed kinds]`).
- Members cannot change their own `perms`, `canEditFinance`, `isAdmin` or role; only admins delete family records.
- Memory photo parts (≤850 000 characters each, owner fixed, never changed) and memory comments/reactions
  (own uid only, one reaction per person, ≤500 characters).
- "Only me" records stay under `users/{uid}`, readable and writable only by that person.

## Not covered by rules (by design)
- Rules cannot see the size of a whole photo (only each part) — limited to 8 parts by the app.
- Orphaned parts: if the app stops between saving a photo's parts and linking them to the memory or message, the parts
  stay (still protected). They count against free-plan storage; a cleanup is not built yet.

## How to publish (you)
1. Firebase console → Firestore → Rules. **Copy the current rules into a file and keep it** (this is your rollback).
2. Paste the contents of `firebase/firestore.rules` → Publish.
3. On the phone: open Finance, Chat, Memories — everything still loads for an admin.

## Rollback
Console → Firestore → Rules → paste the copy from step 1 → Publish. (The console also keeps a history of rule versions.)
