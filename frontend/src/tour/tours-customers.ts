import type { Me } from '../api/client';
import type { Tour } from './model';

// The customer screens' tours (issue #280): members, their next of kin and ID photos, and the collateral register. Each is a
// page tour for whoever can read the page; a step that needs more (adding, checking) carries its own permission. Plain words,
// short sentences, say what to tap. tours.test.tsx renders each page and fails if an anchor is missing.

const MEMBERS_READ = 'lending.members.read';
const MEMBERS_CREATE = 'lending.members.create';
const MEMBERS_UPDATE = 'lending.members.update';
const MEMBERS_VERIFY = 'lending.members.verify_kyc';
const COLLATERAL_READ = 'lending.collateral.read';
const COLLATERAL_MANAGE = 'lending.collateral.manage';
const COLLATERAL_RELEASE = 'lending.collateral.release_request';
const LOANS = 'lending.loans.read';

const held = (me: Me, permission: string) => (me.permissions ?? []).includes(permission);

export const membersPage: Tour = {
  id: 'page-members',
  version: 1,
  title: 'This page: members',
  audience: (me) => held(me, MEMBERS_READ),
  autoStart: false,
  page: '/staff/lending/members',
  steps: [
    {
      id: 'add',
      target: 'member-add',
      module: 'lending',
      needs: [MEMBERS_CREATE],
      optional: true,
      title: 'Add a member',
      body: 'Tap here when a new customer comes in. You will type their name, phone and ID, and take a photo of the ID.',
    },
    {
      id: 'search',
      target: 'member-search',
      module: 'lending',
      title: 'Find a member',
      body: 'Type a name, a member number, a phone number or an ID number. The list narrows as you type.',
    },
    {
      id: 'status',
      target: 'member-status',
      module: 'lending',
      title: 'Active or not',
      body: 'Choose Not active or Left to see members who no longer borrow.',
    },
  ],
};

export const newMemberPage: Tour = {
  id: 'page-member-new',
  version: 1,
  title: 'This page: add a member',
  audience: (me) => held(me, MEMBERS_CREATE),
  autoStart: false,
  page: '/staff/lending/members/new',
  steps: [
    {
      id: 'who',
      target: 'member-who',
      module: 'lending',
      title: 'Who is the member',
      body: 'Pick the branch, then type the full name and a phone number. Write the phone as 0712 345 678.',
    },
    {
      id: 'id',
      target: 'member-id',
      module: 'lending',
      title: 'The ID',
      body: 'Type the number from the national ID card. It starts with CM or CF. If the same number is already registered, you will see who.',
    },
    {
      id: 'photo',
      target: 'member-id-photo',
      module: 'lending',
      title: 'Photo of the ID',
      body: 'Tap Take a photo to use the camera. The photo is made smaller before it is sent, so it works on a slow connection.',
    },
    {
      id: 'save',
      target: 'member-save',
      module: 'lending',
      title: 'Save',
      body: 'Tap Save member. Then add a next of kin on the member\'s page so the details can be checked.',
    },
  ],
};

export const memberPage: Tour = {
  id: 'page-member',
  version: 1,
  title: 'This page: one member',
  audience: (me) => held(me, MEMBERS_READ),
  autoStart: false,
  page: '/staff/lending/members/$memberId',
  steps: [
    {
      id: 'actions',
      target: 'member-actions',
      module: 'lending',
      optional: true,
      title: 'What you can do',
      body: 'Change the details, or open the member\'s savings and investments. You only see what your role allows.',
    },
    {
      id: 'kyc',
      target: 'member-kyc',
      module: 'lending',
      title: 'Checking the details',
      body: 'This shows what is still missing: the ID number, an address, a next of kin and a photo of the ID. A branch manager then checks them.',
    },
    {
      id: 'kin',
      target: 'member-kin',
      module: 'lending',
      needsAny: [MEMBERS_READ, MEMBERS_UPDATE, MEMBERS_VERIFY],
      title: 'Next of kin',
      body: 'Add someone we can call. If their phone matches another member, you are asked whether they are the same person.',
    },
    {
      id: 'documents',
      target: 'member-documents',
      module: 'lending',
      title: 'Photos and papers',
      body: 'The photos and papers kept for the member. Tap Open to look at one.',
    },
    {
      id: 'documents-add',
      target: 'member-doc-add',
      module: 'lending',
      needs: [MEMBERS_UPDATE],
      optional: true,
      title: 'Add a photo',
      body: 'Tap Take a photo to add the front or back of the ID, or any other paper.',
    },
    {
      id: 'loans',
      target: 'member-loans',
      module: 'lending',
      needs: [LOANS],
      optional: true,
      title: 'Their loans',
      body: 'Every loan the member has had, with what is still owed.',
    },
  ],
};

export const collateralListPage: Tour = {
  id: 'page-collateral',
  version: 1,
  title: 'This page: collateral',
  audience: (me) => held(me, COLLATERAL_READ),
  autoStart: false,
  page: '/staff/lending/collateral',
  steps: [
    {
      id: 'add',
      target: 'collateral-add',
      module: 'lending',
      needs: [COLLATERAL_MANAGE],
      optional: true,
      title: 'Register collateral',
      body: 'Tap here for a land title, a logbook, a vehicle or any other security a member gives. You can take photos of the papers.',
    },
    {
      id: 'filters',
      target: 'collateral-filters',
      module: 'lending',
      title: 'Find an item',
      body: 'Type part of the description, the title number or the owner. Pick a kind, or who keeps the item.',
    },
  ],
};

export const newCollateralPage: Tour = {
  id: 'page-collateral-new',
  version: 1,
  title: 'This page: register collateral',
  audience: (me) => held(me, COLLATERAL_MANAGE),
  autoStart: false,
  page: '/staff/lending/collateral/new',
  steps: [
    {
      id: 'item',
      target: 'collateral-item',
      module: 'lending',
      title: 'The item',
      body: 'Find the member, pick the kind and describe the item. A vehicle needs its plate number.',
    },
    {
      id: 'owner',
      target: 'collateral-owner',
      module: 'lending',
      title: 'Owner and value',
      body: 'Say who owns it and what you think it is worth. A valuation can be added later.',
    },
    {
      id: 'photos',
      target: 'collateral-photos',
      module: 'lending',
      title: 'Photos',
      body: 'Take photos of the item and its papers. Up to five.',
    },
  ],
};

export const collateralItemPage: Tour = {
  id: 'page-collateral-item',
  version: 1,
  title: 'This page: one item',
  audience: (me) => held(me, COLLATERAL_READ),
  autoStart: false,
  page: '/staff/lending/collateral/$collateralId',
  steps: [
    {
      id: 'actions',
      target: 'collateral-actions',
      module: 'lending',
      needsAny: [COLLATERAL_MANAGE, COLLATERAL_RELEASE],
      optional: true,
      title: 'What you can do',
      body: 'Change the details, record that we now hold the item, or ask to give it back. Giving it back needs a second person to agree.',
    },
    {
      id: 'valuations',
      target: 'collateral-valuations',
      module: 'lending',
      title: 'Valuations',
      body: 'Each valuation is kept. For loan cover we use the quick sale value of the latest valuation that has one. If none has one, we use your estimate.',
    },
    {
      id: 'history',
      target: 'collateral-history',
      module: 'lending',
      title: 'History',
      body: 'Every change of who keeps the item, with the date and place.',
    },
  ],
};

export const CUSTOMER_TOURS: readonly Tour[] = [membersPage, newMemberPage, memberPage, collateralListPage, newCollateralPage, collateralItemPage];
