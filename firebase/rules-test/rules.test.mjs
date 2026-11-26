// Firestore rules tests. They run only in the local Firestore emulator with made-up data
// ("demo-familyhub" project), never against the real database.
import { test, before, after, beforeEach } from 'node:test';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, updateDoc, deleteDoc, collection, getDocs, query, where, writeBatch } from 'firebase/firestore';

let env;
const F = 'fam1';
const CODE = 'FH-ABC123';

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-familyhub',
    firestore: { rules: readFileSync('../firestore.rules', 'utf8'), host: '127.0.0.1', port: 8080 },
  });
});

after(async () => { await env?.cleanup(); });

// owner: Super Admin. admin: Family Admin. member: ordinary member. pending: asked to join. stranger: not in the family.
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, `families/${F}`), { id: F, name: 'Test Family', ownerUid: 'owner', inviteCode: CODE, monthlyBudget: 50000 });
    await setDoc(doc(db, `families/${F}/members/owner`), { uid: 'owner', status: 'ACTIVE', role: 'OWNER' });
    await setDoc(doc(db, `families/${F}/members/admin`), { uid: 'admin', status: 'ACTIVE', isAdmin: true });
    await setDoc(doc(db, `families/${F}/members/member`), { uid: 'member', status: 'ACTIVE' });
    await setDoc(doc(db, `families/${F}/members/pending`), { uid: 'pending', status: 'PENDING_APPROVAL' });
    await setDoc(doc(db, `families/${F}/expenses/e1`), { title: 'Groceries', amountInr: 500, isDeleted: false });
    await setDoc(doc(db, `invites/${CODE}`), { familyId: F, familyName: 'Test Family', createdByUid: 'owner', updatedAt: 1 });
    await setDoc(doc(db, `users/member`), { uid: 'member', name: 'M' });
    await setDoc(doc(db, `families/other/members/stranger`), { uid: 'stranger', status: 'ACTIVE' });
    await setDoc(doc(db, `families/other`), { id: 'other', name: 'Other', ownerUid: 'stranger', inviteCode: 'FH-ZZZ999' });
  });
});

const as = (uid) => env.authenticatedContext(uid).firestore();
const anon = () => env.unauthenticatedContext().firestore();

// ---------- families ----------
test('signed out: cannot read a family', async () => {
  await assertFails(getDoc(doc(anon(), `families/${F}`)));
});
test('stranger: cannot read a family that is not theirs', async () => {
  await assertFails(getDoc(doc(as('stranger'), `families/${F}`)));
});
test('stranger: cannot list or search families (no browsing for invite codes)', async () => {
  await assertFails(getDocs(collection(as('stranger'), 'families')));
  await assertFails(getDocs(query(collection(as('stranger'), 'families'), where('inviteCode', '==', CODE))));
});
test('member: can read their family', async () => {
  await assertSucceeds(getDoc(doc(as('member'), `families/${F}`)));
});
test('pending: can read the family they asked to join', async () => {
  await assertSucceeds(getDoc(doc(as('pending'), `families/${F}`)));
});
test('member: cannot change who owns the family', async () => {
  await assertFails(updateDoc(doc(as('member'), `families/${F}`), { ownerUid: 'member' }));
});
test('family admin: can change settings but not hand over the family', async () => {
  await assertSucceeds(updateDoc(doc(as('admin'), `families/${F}`), { monthlyBudget: 60000 }));
  await assertFails(updateDoc(doc(as('admin'), `families/${F}`), { ownerUid: 'admin' }));
});

// ---------- invites ----------
test('anyone signed in: can look up one invite code', async () => {
  await assertSucceeds(getDoc(doc(as('stranger'), `invites/${CODE}`)));
});
test('signed out: cannot look up an invite code', async () => {
  await assertFails(getDoc(doc(anon(), `invites/${CODE}`)));
});
test('nobody: can list invite codes', async () => {
  await assertFails(getDocs(collection(as('stranger'), 'invites')));
  await assertFails(getDocs(collection(as('owner'), 'invites')));
});
test('admin: can create the invite for their own family code', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => deleteDoc(doc(ctx.firestore(), `invites/${CODE}`)));
  await assertSucceeds(setDoc(doc(as('admin'), `invites/${CODE}`), { familyId: F, familyName: 'Test Family', createdByUid: 'admin', updatedAt: 2 }));
});
test('member who is not an admin: cannot create or change an invite', async () => {
  await assertFails(setDoc(doc(as('member'), `invites/${CODE}`), { familyId: F, familyName: 'X', createdByUid: 'member', updatedAt: 2 }));
});
test('stranger: cannot point an invite at someone else\'s family', async () => {
  await assertFails(setDoc(doc(as('stranger'), 'invites/FH-NEW111'), { familyId: F, familyName: 'Test Family', createdByUid: 'stranger', updatedAt: 2 }));
});
test('admin: cannot make an invite whose code is not the family\'s code', async () => {
  await assertFails(setDoc(doc(as('owner'), 'invites/FH-WRONG1'), { familyId: F, familyName: 'Test Family', createdByUid: 'owner', updatedAt: 2 }));
});
test('invite: extra fields are refused', async () => {
  await assertFails(setDoc(doc(as('owner'), `invites/${CODE}`), { familyId: F, familyName: 'Test Family', createdByUid: 'owner', updatedAt: 2, secret: 'x' }));
});
test('new family: creating the family, its owner and its invite in one batch works', async () => {
  const db = as('newbie');
  const b = writeBatch(db);
  b.set(doc(db, 'families/fam2'), { id: 'fam2', name: 'New', ownerUid: 'newbie', inviteCode: 'FH-NEW222' });
  b.set(doc(db, 'families/fam2/members/newbie'), { uid: 'newbie', status: 'ACTIVE', role: 'OWNER' });
  b.set(doc(db, 'invites/FH-NEW222'), { familyId: 'fam2', familyName: 'New', createdByUid: 'newbie', updatedAt: 3 });
  await assertSucceeds(b.commit());
});

// ---------- joining ----------
test('joining: a person can ask to join (pending)', async () => {
  await assertSucceeds(setDoc(doc(as('joiner'), `families/${F}/members/joiner`), { uid: 'joiner', status: 'PENDING_APPROVAL' }));
});
test('joining: a person cannot make themselves an active member', async () => {
  await assertFails(setDoc(doc(as('joiner'), `families/${F}/members/joiner`), { uid: 'joiner', status: 'ACTIVE' }));
});
test('pending: cannot approve themselves', async () => {
  await assertFails(updateDoc(doc(as('pending'), `families/${F}/members/pending`), { status: 'ACTIVE' }));
});
test('pending: cannot read shared entries before approval', async () => {
  await assertFails(getDoc(doc(as('pending'), `families/${F}/expenses/e1`)));
});

// ---------- roles ----------
test('family admin: can approve a member', async () => {
  await assertSucceeds(updateDoc(doc(as('admin'), `families/${F}/members/pending`), { status: 'ACTIVE' }));
});
test('family admin: cannot make someone an admin', async () => {
  await assertFails(updateDoc(doc(as('admin'), `families/${F}/members/member`), { isAdmin: true }));
});
test('member: cannot make themselves an admin', async () => {
  await assertFails(updateDoc(doc(as('member'), `families/${F}/members/member`), { isAdmin: true }));
});
test('super admin: can make a member a family admin', async () => {
  await assertSucceeds(updateDoc(doc(as('owner'), `families/${F}/members/member`), { isAdmin: true }));
});
test('family admin: cannot remove the super admin', async () => {
  await assertFails(deleteDoc(doc(as('admin'), `families/${F}/members/owner`)));
});

// ---------- shared entries ----------
test('member: can add and edit shared entries', async () => {
  await assertSucceeds(setDoc(doc(as('member'), `families/${F}/expenses/e2`), { title: 'Fuel', amountInr: 900 }));
  await assertSucceeds(updateDoc(doc(as('member'), `families/${F}/expenses/e1`), { isDeleted: true }));
});
test('member: cannot delete shared entries for good', async () => {
  await assertFails(deleteDoc(doc(as('member'), `families/${F}/expenses/e1`)));
});
test('admin: can delete shared entries for good', async () => {
  await assertSucceeds(deleteDoc(doc(as('admin'), `families/${F}/expenses/e1`)));
});
test('stranger: cannot read or write shared entries', async () => {
  await assertFails(getDoc(doc(as('stranger'), `families/${F}/expenses/e1`)));
  await assertFails(setDoc(doc(as('stranger'), `families/${F}/expenses/e9`), { title: 'x' }));
});

// ---------- private records ----------
test('Only me records: nobody else can read them, not even the super admin', async () => {
  await assertSucceeds(getDoc(doc(as('member'), 'users/member')));
  await assertFails(getDoc(doc(as('owner'), 'users/member')));
  await assertFails(getDoc(doc(as('owner'), 'users/member/records/r1')));
});
