// Firestore rules tests. They run only in the local Firestore emulator with made-up data
// ("demo-familyhub" project), never against the real database.
import { test, before, after, beforeEach } from 'node:test';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, updateDoc, deleteDoc, deleteField, collection, getDocs, query, where, writeBatch, orderBy, limit, serverTimestamp, Timestamp } from 'firebase/firestore';

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

// The assistant's category change writes exactly these fields (Recategorize plan), and Undo writes them back.
const CATEGORY_CHANGE = { category: 'Shopping', subcategory: 'None', updatedAt: 2 };
test('assistant category change: an approved member can change an entry\'s category and change it back', async () => {
  await assertSucceeds(updateDoc(doc(as('member'), `families/${F}/expenses/e1`), CATEGORY_CHANGE));
  await assertSucceeds(updateDoc(doc(as('member'), `families/${F}/expenses/e1`), { category: 'Groceries', subcategory: 'None', updatedAt: 3 }));
});
test('assistant category change: a pending member, a stranger or someone signed out cannot', async () => {
  await assertFails(updateDoc(doc(as('pending'), `families/${F}/expenses/e1`), CATEGORY_CHANGE));
  await assertFails(updateDoc(doc(as('stranger'), `families/${F}/expenses/e1`), CATEGORY_CHANGE));
  await assertFails(updateDoc(doc(anon(), `families/${F}/expenses/e1`), CATEGORY_CHANGE));
});
test('assistant category change: an "Only me" entry can be changed by its owner only', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `users/member/expenses/p1`), { title: 'Gift', amountInr: 900, category: 'Other' });
  });
  await assertSucceeds(updateDoc(doc(as('member'), `users/member/expenses/p1`), CATEGORY_CHANGE));
  await assertFails(updateDoc(doc(as('owner'), `users/member/expenses/p1`), CATEGORY_CHANGE));
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
// ---------- chat attachments and typing (28 Nov) ----------
const att = { id: 'a1', kind: 'photo', name: 'photo.jpg', mime: 'image/jpeg', size: 1200, parts: 1, w: 10, h: 10 };
const amsg = { senderUid: 'member', senderName: 'M', text: '📷 Photo', at: 2, clientAt: 2, attachment: att };
test('chat attachment: a member can send a photo (description plus its part) in the family chat', async () => {
  const b = writeBatch(as('member'));
  b.set(doc(as('member'), `families/${F}/chats/family/parts/a1_0`), { senderUid: 'member', data: 'AAAA', at: 2 });
  b.set(doc(as('member'), `families/${F}/chats/family/messages/m-a1`), amsg);
  await assertSucceeds(b.commit());
  await assertSucceeds(getDoc(doc(as('owner'), `families/${F}/chats/family/parts/a1_0`)));
});
test('chat attachment: parts of a direct chat are private to its two people (admins included)', async () => {
  await assertSucceeds(setDoc(doc(as('admin'), `families/${F}/chats/${DM}/parts/x_0`), { senderUid: 'admin', data: 'AAAA', at: 1 }));
  await assertSucceeds(getDoc(doc(as('member'), `families/${F}/chats/${DM}/parts/x_0`)));
  await assertFails(getDoc(doc(as('owner'), `families/${F}/chats/${DM}/parts/x_0`)));
  await assertFails(getDoc(doc(as('stranger'), `families/${F}/chats/family/parts/x_0`)));
  await assertFails(setDoc(doc(as('owner'), `families/${F}/chats/${DM}/parts/y_0`), { senderUid: 'owner', data: 'AAAA', at: 1 }));
});
test('chat attachment: a part cannot pretend to be someone else, carry extra fields, be too big or change', async () => {
  const p = (id) => doc(as('member'), `families/${F}/chats/family/parts/${id}`);
  await assertFails(setDoc(p('b_0'), { senderUid: 'owner', data: 'AAAA', at: 1 }));
  await assertFails(setDoc(p('b_1'), { senderUid: 'member', data: 'AAAA', at: 1, extra: true }));
  await assertFails(setDoc(p('b_2'), { senderUid: 'member', data: 'A'.repeat(850001), at: 1 }));
  await assertFails(setDoc(p('b_3'), { senderUid: 'member', data: '', at: 1 }));
  await assertSucceeds(setDoc(p('b_4'), { senderUid: 'member', data: 'AAAA', at: 1 }));
  await assertFails(updateDoc(p('b_4'), { data: 'BBBB' }));
  await assertFails(deleteDoc(doc(as('owner'), `families/${F}/chats/family/parts/b_4`)));
  await assertSucceeds(deleteDoc(p('b_4')));
  await assertFails(setDoc(doc(as('pending'), `families/${F}/chats/family/parts/b_5`), { senderUid: 'pending', data: 'AAAA', at: 1 }));
});
test('chat attachment: the description is checked (kind, size, parts, fields)', async () => {
  const m = (id, a) => setDoc(doc(as('member'), `families/${F}/chats/family/messages/${id}`), { ...amsg, attachment: a });
  await assertSucceeds(m('ok1', { ...att, kind: 'voice', durationMs: 4000, wave: [1, 2, 3] }));
  await assertSucceeds(m('ok2', { ...att, kind: 'file', name: 'policy.pdf', mime: 'application/pdf', parts: 8, size: 4800000 }));
  await assertFails(m('bad1', { ...att, kind: 'video' }));
  await assertFails(m('bad2', { ...att, parts: 9 }));
  await assertFails(m('bad3', { ...att, size: 6000000 }));
  await assertFails(m('bad4', { ...att, url: 'https://example.com/x' }));
  await assertFails(m('bad5', 'not a map'));
  await assertFails(m('bad6', { ...att, wave: Array(65).fill(1) }));
});
test('chat typing: each person sets only their own "typing" time; only people in the chat see it', async () => {
  await assertSucceeds(setDoc(doc(as('member'), `families/${F}/chats/family/typing/member`), { at: serverTimestamp() }));
  await assertFails(setDoc(doc(as('member'), `families/${F}/chats/family/typing/owner`), { at: serverTimestamp() }));
  await assertFails(setDoc(doc(as('member'), `families/${F}/chats/family/typing/member`), { at: serverTimestamp(), text: 'secret draft' }));
  await assertSucceeds(getDoc(doc(as('owner'), `families/${F}/chats/family/typing/member`)));
  await assertFails(getDoc(doc(as('owner'), `families/${F}/chats/${DM}/typing/admin`)));
  await assertFails(getDoc(doc(as('stranger'), `families/${F}/chats/family/typing/member`)));
  await assertSucceeds(deleteDoc(doc(as('member'), `families/${F}/chats/family/typing/member`)));
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

// ---------- one-to-one calls (Beta) ----------
// "admin" calls "member"; the owner (Super Admin) is not in the call and must not see it.
const callData = (over = {}) => ({
  callerUid: 'admin', calleeUid: 'member', callerName: 'Asha', calleeName: 'Meera',
  memberUids: ['admin', 'member'], type: 'video', state: 'ringing', clientAt: 1000, ...over,
});
const OFFER = { from: 'admin', type: 'offer', sdp: 'v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\n' };
const ANSWER = { from: 'member', type: 'answer', sdp: 'v=0\r\no=- 3 4 IN IP4 127.0.0.1\r\n' };
const callPath = (id) => `families/${F}/calls/${id}`;

async function startCall(id = 'c1', over = {}, offer = OFFER) {
  const db = as('admin');
  const b = writeBatch(db);
  b.set(doc(db, callPath(id)), callData(over));
  b.set(doc(db, `${callPath(id)}/signal/offer`), offer);
  await b.commit();
}
async function seedCall(id, state, extra = {}) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, callPath(id)), callData({ state, ...extra }));
    await setDoc(doc(db, `${callPath(id)}/signal/offer`), OFFER);
  });
}

test('call: a member can start a call to an active member, with the offer saved together', async () => {
  await assertSucceeds(startCall());
});
test('call: a call without its offer is refused', async () => {
  await assertFails(setDoc(doc(as('admin'), callPath('c1')), callData()));
});
test('call: nobody can start a call as someone else, to themselves, or to a pending member', async () => {
  await assertFails(startCall('c2', { callerUid: 'owner', memberUids: ['owner', 'member'] }));
  await assertFails(startCall('c3', { calleeUid: 'admin', memberUids: ['admin', 'admin'] }));
  await assertFails(startCall('c4', { calleeUid: 'pending', memberUids: ['admin', 'pending'] }));
  await assertFails(startCall('c5', { calleeUid: 'stranger', memberUids: ['admin', 'stranger'] }));
});
test('call: a call must start ringing, be video or audio, and carry only the known fields', async () => {
  await assertFails(startCall('c6', { state: 'accepted' }));
  await assertFails(startCall('c7', { type: 'screen' }));
  await assertFails(startCall('c8', { extra: 'x' }));
  await assertFails(startCall('c9', { memberUids: ['admin', 'member', 'owner'] }));
});
test('call: the offer must come from the caller and stay under the size limit', async () => {
  await assertFails(startCall('c10', {}, { ...OFFER, from: 'member' }));
  await assertFails(startCall('c11', {}, { ...OFFER, sdp: 'x'.repeat(60001) }));
  await assertFails(startCall('c12', {}, { ...OFFER, type: 'answer' }));
});
test('call: only the two people can read it, owner and admins outside the call included', async () => {
  await startCall();
  await assertSucceeds(getDoc(doc(as('admin'), callPath('c1'))));
  await assertSucceeds(getDoc(doc(as('member'), callPath('c1'))));
  await assertSucceeds(getDoc(doc(as('member'), `${callPath('c1')}/signal/offer`)));
  await assertFails(getDoc(doc(as('owner'), callPath('c1'))));
  await assertFails(getDoc(doc(as('owner'), `${callPath('c1')}/signal/offer`)));
  await assertFails(getDoc(doc(as('stranger'), callPath('c1'))));
  await assertFails(getDoc(doc(anon(), callPath('c1'))));
});
test('call: each person lists only their own calls', async () => {
  await startCall();
  await assertSucceeds(getDocs(query(collection(as('member'), `families/${F}/calls`), where('memberUids', 'array-contains', 'member'))));
  await assertFails(getDocs(query(collection(as('owner'), `families/${F}/calls`), where('memberUids', 'array-contains', 'member'))));
  await assertFails(getDocs(collection(as('owner'), `families/${F}/calls`)));
});
test('call: the person called can accept, saving the answer together', async () => {
  await seedCall('c1', 'ringing');
  const db = as('member');
  const b = writeBatch(db);
  b.update(doc(db, callPath('c1')), { state: 'accepted', answeredAt: 2000 });
  b.set(doc(db, `${callPath('c1')}/signal/answer`), ANSWER);
  await assertSucceeds(b.commit());
});
test('call: the caller cannot accept their own call, and a third person cannot touch it', async () => {
  await seedCall('c1', 'ringing');
  await assertFails(updateDoc(doc(as('admin'), callPath('c1')), { state: 'accepted' }));
  await assertFails(updateDoc(doc(as('owner'), callPath('c1')), { state: 'declined' }));
  await assertFails(updateDoc(doc(as('owner'), callPath('c1')), { state: 'ended' }));
});
test('call: an answer needs an accepted call, and only the person called can send it', async () => {
  await seedCall('c1', 'ringing');
  await assertFails(setDoc(doc(as('member'), `${callPath('c1')}/signal/answer`), ANSWER));
  const db = as('admin');
  const b = writeBatch(db);
  b.update(doc(db, callPath('c1')), { state: 'cancelled' });
  b.set(doc(db, `${callPath('c1')}/signal/answer`), { ...ANSWER, from: 'admin' });
  await assertFails(b.commit());
});
test('call: the person called can decline or say busy; the caller can cancel or let it go unanswered', async () => {
  await seedCall('c1', 'ringing');
  await seedCall('c2', 'ringing');
  await seedCall('c3', 'ringing');
  await seedCall('c4', 'ringing');
  await assertSucceeds(updateDoc(doc(as('member'), callPath('c1')), { state: 'declined', endedAt: 5, endReason: 'declined' }));
  await assertSucceeds(updateDoc(doc(as('member'), callPath('c2')), { state: 'busy', endedAt: 5 }));
  await assertSucceeds(updateDoc(doc(as('admin'), callPath('c3')), { state: 'cancelled', endedAt: 5 }));
  await assertSucceeds(updateDoc(doc(as('admin'), callPath('c4')), { state: 'missed', endedAt: 5 }));
});
test('call: either person can end an accepted call', async () => {
  await seedCall('c1', 'accepted');
  await seedCall('c2', 'accepted');
  await assertSucceeds(updateDoc(doc(as('admin'), callPath('c1')), { state: 'ended', endedAt: 9, endReason: 'hangup' }));
  await assertSucceeds(updateDoc(doc(as('member'), callPath('c2')), { state: 'ended', endedAt: 9 }));
});
test('call: a call that is over cannot be brought back, and a ringing call cannot jump to ended', async () => {
  await seedCall('c1', 'declined');
  await seedCall('c2', 'ended');
  await seedCall('c3', 'ringing');
  await assertFails(updateDoc(doc(as('member'), callPath('c1')), { state: 'accepted' }));
  await assertFails(updateDoc(doc(as('admin'), callPath('c2')), { state: 'ringing' }));
  await assertFails(updateDoc(doc(as('admin'), callPath('c3')), { state: 'ended' }));
  await assertFails(updateDoc(doc(as('member'), callPath('c3')), { state: 'ended' }));
});
test('call: nobody can change who is calling, the type or the names after the call starts', async () => {
  await seedCall('c1', 'ringing');
  await assertFails(updateDoc(doc(as('admin'), callPath('c1')), { state: 'cancelled', callerUid: 'owner' }));
  await assertFails(updateDoc(doc(as('member'), callPath('c1')), { state: 'declined', memberUids: ['member', 'owner'] }));
  await assertFails(updateDoc(doc(as('member'), callPath('c1')), { type: 'audio' }));
});
test('call: the offer and answer cannot be rewritten', async () => {
  await seedCall('c1', 'ringing');
  await assertFails(updateDoc(doc(as('admin'), `${callPath('c1')}/signal/offer`), { sdp: 'changed' }));
  await assertFails(setDoc(doc(as('admin'), `${callPath('c1')}/signal/offer`), OFFER));
});
test('call: the offer and answer can be cleaned up only by the two people, and only once the call is over', async () => {
  await seedCall('c1', 'ringing');
  await seedCall('c2', 'ended');
  await assertFails(deleteDoc(doc(as('admin'), `${callPath('c1')}/signal/offer`)));
  await assertSucceeds(deleteDoc(doc(as('admin'), `${callPath('c2')}/signal/offer`)));
  await seedCall('c3', 'ended');
  await assertFails(deleteDoc(doc(as('owner'), `${callPath('c3')}/signal/offer`)));
});
test('call: a call record can never be deleted by anyone', async () => {
  await seedCall('c1', 'ended');
  await assertFails(deleteDoc(doc(as('admin'), callPath('c1'))));
  await assertFails(deleteDoc(doc(as('owner'), callPath('c1'))));
});


// ---------- live location (Family safety) ----------
// "member" shares with "admin" for another hour; "admin" shared with "member" but it has expired.
const locPath = (uid) => `families/${F}/locations/${uid}`;
const inMs = (ms) => Timestamp.fromMillis(Date.now() + ms);
const H = 60 * 60 * 1000;
async function seedLocations() {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, locPath('member')), { uid: 'member', name: 'M', lat: 12.97, lng: 77.59, accuracy: 12, battery: 64,
      updatedAt: Timestamp.now(), fixAt: Timestamp.now(), expiresAt: inMs(H), endAt: 0, viewerUids: ['admin'] });
    await setDoc(doc(db, locPath('admin')), { uid: 'admin', name: 'A', lat: 12.9, lng: 77.5, accuracy: 30, battery: 20,
      updatedAt: Timestamp.now(), fixAt: Timestamp.now(), expiresAt: inMs(-60 * 1000), endAt: 0, viewerUids: ['member'] });
  });
}
const myLoc = (uid, extra = {}) => ({ uid, name: 'Me', lat: 12.97, lng: 77.59, accuracy: 15, battery: 50,
  updatedAt: serverTimestamp(), fixAt: Timestamp.now(), expiresAt: inMs(H), endAt: Date.now() + H, viewerUids: ['owner'], ...extra });

test('location: a chosen family member can see a live share', async () => {
  await seedLocations();
  await assertSucceeds(getDoc(doc(as('admin'), locPath('member'))));
});
test('location: the Super Admin cannot see a share they were not chosen for (no admin override)', async () => {
  await seedLocations();
  await assertFails(getDoc(doc(as('owner'), locPath('member'))));
});
test('location: the person sharing can see their own; strangers and pending members cannot', async () => {
  await seedLocations();
  await assertSucceeds(getDoc(doc(as('member'), locPath('member'))));
  await assertFails(getDoc(doc(as('stranger'), locPath('member'))));
  await assertFails(getDoc(doc(as('pending'), locPath('member'))));
});
test('location: an expired share cannot be read, even by someone who was chosen', async () => {
  await seedLocations();
  await assertFails(getDoc(doc(as('member'), locPath('admin'))));
});
test('location: nobody can list who is sharing', async () => {
  await seedLocations();
  await assertFails(getDocs(collection(as('admin'), `families/${F}/locations`)));
  await assertFails(getDocs(query(collection(as('admin'), `families/${F}/locations`), where('viewerUids', 'array-contains', 'admin'))));
});
test('location: a family member may check someone who is not sharing (nothing there)', async () => {
  await assertSucceeds(getDoc(doc(as('member'), locPath('owner'))));
  await assertFails(getDoc(doc(as('stranger'), locPath('owner'))));
});
test('location: a member can share their own location', async () => {
  await assertSucceeds(setDoc(doc(as('member'), locPath('member')), myLoc('member')));
  await assertSucceeds(setDoc(doc(as('member'), locPath('member')), myLoc('member', { lat: 13.0, endAt: 0, expiresAt: inMs(30 * 60 * 1000) })));
});
test('location: nobody can write someone else\'s location, admins included; pending members cannot share', async () => {
  await assertFails(setDoc(doc(as('owner'), locPath('member')), myLoc('member')));
  await assertFails(setDoc(doc(as('admin'), locPath('member')), myLoc('member')));
  await assertFails(setDoc(doc(as('member'), locPath('admin')), myLoc('admin')));
  await assertFails(setDoc(doc(as('pending'), locPath('pending')), myLoc('pending')));
  await assertFails(setDoc(doc(as('stranger'), locPath('stranger')), myLoc('stranger')));
});
test('location: a share must end within 9 hours and not already be over', async () => {
  await assertFails(setDoc(doc(as('member'), locPath('member')), myLoc('member', { expiresAt: inMs(10 * H) })));
  await assertFails(setDoc(doc(as('member'), locPath('member')), myLoc('member', { expiresAt: inMs(-1000) })));
  await assertFails(setDoc(doc(as('member'), locPath('member')), myLoc('member', { expiresAt: Date.now() + H })));
});
test('location: bad places, viewers and extra fields are refused', async () => {
  await assertFails(setDoc(doc(as('member'), locPath('member')), myLoc('member', { lat: 91 })));
  await assertFails(setDoc(doc(as('member'), locPath('member')), myLoc('member', { lng: -181 })));
  await assertFails(setDoc(doc(as('member'), locPath('member')), myLoc('member', { viewerUids: [] })));
  await assertFails(setDoc(doc(as('member'), locPath('member')), myLoc('member', { viewerUids: ['member', 'owner'] })));
  await assertFails(setDoc(doc(as('member'), locPath('member')), myLoc('member', { viewerUids: Array.from({ length: 21 }, (_, i) => `u${i}`) })));
  await assertFails(setDoc(doc(as('member'), locPath('member')), myLoc('member', { history: [1, 2] })));
  await assertFails(setDoc(doc(as('member'), locPath('member')), myLoc('member', { uid: 'admin' })));
});
test('location: the update time must be the server\'s, not the phone\'s', async () => {
  await assertFails(setDoc(doc(as('member'), locPath('member')), myLoc('member', { updatedAt: Timestamp.now() })));
});
test('location: only the person sharing can stop it (delete)', async () => {
  await seedLocations();
  await assertFails(deleteDoc(doc(as('owner'), locPath('member'))));
  await assertFails(deleteDoc(doc(as('admin'), locPath('member'))));
  await assertSucceeds(deleteDoc(doc(as('member'), locPath('member'))));
});
test('safety settings: private to their owner, admins included', async () => {
  await assertSucceeds(setDoc(doc(as('member'), 'users/member/safety/settings'), { contacts: [], lowBatteryOn: false }));
  await assertFails(getDoc(doc(as('owner'), 'users/member/safety/settings')));
  await assertFails(getDoc(doc(as('admin'), 'users/member/safety/settings')));
});
