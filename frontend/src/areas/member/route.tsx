import { createLazyRoute } from '@tanstack/react-router';

// The member self-service area (chapter 11). Its screens arrive with the member portal epic;
// it is a separate chunk from the staff area from the start.
function MemberHome() {
  return (
    <main>
      <h1>Member area</h1>
      <p>Balances, schedules, applications and payments will appear here.</p>
    </main>
  );
}

export const Route = createLazyRoute('/member')({ component: MemberHome });
