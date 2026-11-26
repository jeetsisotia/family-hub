# Firestore rules

- `firestore.rules`: the rules to publish (Firebase console → Firestore → Rules). They add the v2 roles:
  - **Super Admin:** the family's creator.
  - **Family Admin:** made an admin by the Super Admin.
  - **Member:** everyone else.
- `firestore.rules.backup-2026-11-26`: the rules that were live before v2, copied from the console on 26 Nov 2026. The console also keeps earlier versions in its history.
- `firestore.rules.backup-2026-11-26-live`: the rules that were live just before the invites and chat rules were published (26 Nov 2026, 11:34 PM IST). Its text matched the console exactly. To roll back, paste it into the console and publish, or pick "Today, 2:59 PM" in the console's rules history.
- `firestore.rules.backup-2026-11-27-before-polls`: the live rules just before poll votes were allowed (27 Nov 2026, 12:23 AM IST). To roll back, publish it, or pick "Today, 11:34 PM" (26 Nov) in the console's rules history.
- **Tests:** `rules-test/` holds 49 emulator tests. They run on GitHub Actions ("Firestore rules tests"), and automatically whenever a bundle changes `firebase/`. Publish only after they pass.

## What changes for members and admins

- Members can still add and edit shared entries. Deleting a shared entry puts it in **Recently deleted**.
- Only admins can delete shared entries for good, empty Recently deleted, or reset data.
- Family Admins can approve and remove members. They cannot remove the Super Admin or other admins, and they cannot make or remove admins.
- Private documents keep the same access as before: their owner, the people they chose, and the Super Admin.
