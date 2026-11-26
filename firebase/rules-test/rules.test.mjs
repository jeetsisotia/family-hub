// Firestore rules tests. They run only in the local Firestore emulator with made-up data
// ("demo-familyhub" project), never against the real database.
import { test, before, after, beforeEach } from 'node:test';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, updateDoc, deleteDoc, deleteField, collection, getDocs, query, where, writeBatch, orderBy, limit } from 'firebase/firestore';

let env;
const F = 'fam1';
const CODE = 'FH-ABC123';
const DM = 'dm_admin_member';

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
    // Chats: the family group, and a direct chat between "admin" and "member" (the owner is not in it).
    await setDoc(doc(db, `families/${F}/chats/family`), { type: 'group', title: 'Family', createdByUid: 'owner', lastAt: 1 });
    await setDoc(doc(db, `families/${F}/chats/family/messages/g1`), { senderUid: 'member', senderName: 'M', text: 'Hi all', at: 1, clientAt: 1 });
    await setDoc(doc(db, `families/${F}/chats/${DM}`), { type: 'direct', memberUids: ['admin', 'member'], createdByUid: 'admin', lastAt: 1 });
    await setDoc(doc(db, `families/${F}/chats/${DM}/messages/d1`), { senderUid: 'admin', senderName: 'A', text: 'Private', at: 1, clientAt: 1 });
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

// ---------- family chat ----------
test('chat: family members can read the family group chat', async () => {
  await assertSucceeds(getDoc(doc(as('member'), `families/${F}/chats/family/messages/g1`)));
  await assertSucceeds(getDocs(query(collection(as('owner'), `families/${F}/chats/family/messages`), orderBy('clientAt', 'desc'), limit(60))));
});
test('chat: pending members and strangers cannot read the family group chat', async () => {
  await assertFails(getDoc(doc(as('pending'), `families/${F}/chats/family/messages/g1`)));
  await assertFails(getDoc(doc(as('stranger'), `families/${F}/chats/family/messages/g1`)));
});
test('chat: the two people in a direct chat can read it', async () => {
  await assertSucceeds(getDoc(doc(as('admin'), `families/${F}/chats/${DM}/messages/d1`)));
  await assertSucceeds(getDoc(doc(as('member'), `families/${F}/chats/${DM}`)));
});
test('chat: nobody else can read a direct chat, not even the super admin', async () => {
  await assertFails(getDoc(doc(as('owner'), `families/${F}/chats/${DM}`)));
  await assertFails(getDoc(doc(as('owner'), `families/${F}/chats/${DM}/messages/d1`)));
  await assertFails(getDocs(collection(as('owner'), `families/${F}/chats/${DM}/messages`)));
});
test('chat: a person can list only the direct chats they are in', async () => {
  await assertSucceeds(getDocs(query(collection(as('member'), `families/${F}/chats`), where('memberUids', 'array-contains', 'member'))));
  await assertFails(getDocs(query(collection(as('owner'), `families/${F}/chats`), where('memberUids', 'array-contains', 'member'))));
  await assertFails(getDocs(collection(as('owner'), `families/${F}/chats`)));
});
test('chat: sending a message as yourself works; as someone else does not', async () => {
  await assertSucceeds(setDoc(doc(as('member'), `families/${F}/chats/${DM}/messages/d2`), { senderUid: 'member', senderName: 'M', text: 'Hello', at: 2, clientAt: 2 }));
  await assertFails(setDoc(doc(as('member'), `families/${F}/chats/${DM}/messages/d3`), { senderUid: 'admin', senderName: 'A', text: 'Fake', at: 2, clientAt: 2 }));
});
test('chat: the super admin cannot post into a direct chat they are not in', async () => {
  await assertFails(setDoc(doc(as('owner'), `families/${F}/chats/${DM}/messages/d4`), { senderUid: 'owner', senderName: 'O', text: 'Hi', at: 2, clientAt: 2 }));
});
test('chat: messages longer than 4000 characters or empty are refused', async () => {
  await assertFails(setDoc(doc(as('member'), `families/${F}/chats/family/messages/g2`), { senderUid: 'member', senderName: 'M', text: 'x'.repeat(4001), at: 2, clientAt: 2 }));
  await assertFails(setDoc(doc(as('member'), `families/${F}/chats/family/messages/g3`), { senderUid: 'member', senderName: 'M', text: '', at: 2, clientAt: 2 }));
});
test('chat: the sender can edit or delete for everyone; others cannot', async () => {
  await assertSucceeds(updateDoc(doc(as('member'), `families/${F}/chats/family/messages/g1`), { text: 'Hi everyone', editedAt: 3 }));
  await assertSucceeds(updateDoc(doc(as('member'), `families/${F}/chats/family/messages/g1`), { text: '', deletedForAll: true, editedAt: 4 }));
  await assertFails(updateDoc(doc(as('owner'), `families/${F}/chats/family/messages/g1`), { text: 'Changed by someone else' }));
});
test('chat: nobody can remove a message for good', async () => {
  await assertFails(deleteDoc(doc(as('member'), `families/${F}/chats/family/messages/g1`)));
  await assertFails(deleteDoc(doc(as('owner'), `families/${F}/chats/family/messages/g1`)));
});
test('chat: anyone in the chat may set only their own reaction', async () => {
  await assertSucceeds(updateDoc(doc(as('owner'), `families/${F}/chats/family/messages/g1`), { 'reactions.owner': '👍' }));
  await assertFails(updateDoc(doc(as('owner'), `families/${F}/chats/family/messages/g1`), { 'reactions.member': '👍' }));
});
test('chat: a poll needs 2 to 6 answers', async () => {
  const base = { senderUid: 'member', senderName: 'M', text: 'Dinner?', at: 2, clientAt: 2 };
  await assertSucceeds(setDoc(doc(as('member'), `families/${F}/chats/family/messages/p1`), { ...base, pollOptions: ['Pizza', 'Dosa'] }));
  await assertFails(setDoc(doc(as('member'), `families/${F}/chats/family/messages/p2`), { ...base, pollOptions: ['Only one'] }));
  await assertFails(setDoc(doc(as('member'), `families/${F}/chats/family/messages/p3`), { ...base, pollOptions: ['1', '2', '3', '4', '5', '6', '7'] }));
  await assertFails(setDoc(doc(as('member'), `families/${F}/chats/family/messages/p4`), { ...base, pollOptions: 'Pizza' }));
});
test('chat: each person votes only for themselves, and only for a real answer', async () => {
  await assertSucceeds(setDoc(doc(as('member'), `families/${F}/chats/family/messages/p1`), { senderUid: 'member', senderName: 'M', text: 'Dinner?', at: 2, clientAt: 2, pollOptions: ['Pizza', 'Dosa'] }));
  const poll = doc(as('owner'), `families/${F}/chats/family/messages/p1`);
  await assertSucceeds(updateDoc(poll, { 'votes.owner': 1 }));
  await assertFails(updateDoc(poll, { 'votes.member': 0 }));
  await assertFails(updateDoc(poll, { 'votes.owner': 5 }));
  await assertFails(updateDoc(poll, { 'votes.owner': 'Pizza' }));
  await assertSucceeds(updateDoc(poll, { 'votes.owner': deleteField() }));
  await assertFails(updateDoc(poll, { pollOptions: ['Pizza', 'Dosa', 'Burger'] }));
  await assertFails(updateDoc(doc(as('owner'), `families/${F}/chats/family/messages/g1`), { 'votes.owner': 0 }));
});
test('chat: a person outside a direct chat cannot vote in it', async () => {
  await assertSucceeds(setDoc(doc(as('member'), `families/${F}/chats/${DM}/messages/p5`), { senderUid: 'member', senderName: 'M', text: 'Movie?', at: 2, clientAt: 2, pollOptions: ['Yes', 'No'] }));
  await assertFails(updateDoc(doc(as('owner'), `families/${F}/chats/${DM}/messages/p5`), { 'votes.owner': 0 }));
  await assertSucceeds(updateDoc(doc(as('admin'), `families/${F}/chats/${DM}/messages/p5`), { 'votes.admin': 0 }));
});
test('chat: starting a direct chat with another active member works, and with yourself left out does not', async () => {
  await assertSucceeds(setDoc(doc(as('owner'), `families/${F}/chats/dm_member_owner`), { type: 'direct', memberUids: ['member', 'owner'], createdByUid: 'owner', lastAt: 5 }));
  await assertFails(setDoc(doc(as('owner'), `families/${F}/chats/dm_admin_member2`), { type: 'direct', memberUids: ['admin', 'member'], createdByUid: 'owner', lastAt: 5 }));
});
test('chat: a direct chat with someone still pending is refused', async () => {
  await assertFails(setDoc(doc(as('member'), `families/${F}/chats/dm_member_pending`), { type: 'direct', memberUids: ['member', 'pending'], createdByUid: 'member', lastAt: 5 }));
});
test('chat: looking up a direct chat that does not exist yet works only for its own people', async () => {
  await assertSucceeds(getDoc(doc(as('owner'), `families/${F}/chats/dm_admin_owner`)));
  await assertFails(getDoc(doc(as('member'), `families/${F}/chats/dm_admin_owner`)));
});
test('chat: read markers: each person sets only their own', async () => {
  await assertSucceeds(setDoc(doc(as('member'), `families/${F}/chats/${DM}/reads/member`), { at: 9 }));
  await assertFails(setDoc(doc(as('member'), `families/${F}/chats/${DM}/reads/admin`), { at: 9 }));
  await assertFails(getDoc(doc(as('owner'), `families/${F}/chats/${DM}/reads/member`)));
});
test('chat: sending updates the preview only as yourself', async () => {
  await assertSucceeds(updateDoc(doc(as('member'), `families/${F}/chats/family`), { lastText: 'Hello', lastSenderUid: 'member', lastAt: 6 }));
  await assertFails(updateDoc(doc(as('member'), `families/${F}/chats/family`), { lastText: 'Hello', lastSenderUid: 'owner', lastAt: 6 }));
  await assertFails(updateDoc(doc(as('member'), `families/${F}/chats/${DM}`), { memberUids: ['member', 'owner'] }));
});
