import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import type { Me } from '../../../api/client';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Shared by the cash book component tests: they render to static markup (the test setup has no DOM), with
// fabricated sessions. `without` drops permissions from a session, to test one gate at a time.

export const branch = mockMe('admin').branches?.[0]?.id ?? '';
export const otherBranch = mockMe('admin').branches?.[1]?.id ?? '';

export const html = (node: ReactElement): string => render(node).replaceAll('<!-- -->', '');

export type Role = 'sales' | 'cashier' | 'admin';

export function sessionOf(role: Role, without: string[] = []): Me {
  const me = mockMe(role);
  return { ...me, permissions: (me.permissions ?? []).filter((p) => !without.includes(p)) };
}

export function pageAs(me: Me, node: ReactElement, client = new QueryClient(), selected: string | null = branch): string {
  return html(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch: selected }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

export const page = (role: Role, node: ReactElement, client = new QueryClient(), selected: string | null = branch): string => pageAs(sessionOf(role), node, client, selected);
