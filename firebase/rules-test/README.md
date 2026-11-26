# Firestore rules tests

`rules.test.mjs` checks `../firestore.rules` in the Firestore emulator, with made-up data in a `demo-familyhub` project. It never touches the real database.

They cover:
- **Families:** signed-out, stranger, pending, member, Family Admin and Super Admin access.
- **Invites:** lookup by code only; codes cannot be listed; only admins can create or change them.
- **Joining:** a new member always starts as pending.
- **Roles:** who can make admins and remove members.
- **Shared entries:** members move them to Recently deleted; only admins delete them for good.
- **Only me records:** owner-only, including against the Super Admin.

The **Firestore rules tests** workflow runs them on GitHub (Actions → Firestore rules tests → Run workflow). To run them on a computer you need Node 20 and Java 21:

```
npm install
npm test
```

New rules are published in the Firebase console only after these tests pass. The rules that were live before are kept next to them for rollback.
