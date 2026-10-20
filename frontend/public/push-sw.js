// Web Push for the installed app (ADR-050). The generated service worker loads this file with importScripts
// (vite.config.ts, workbox.importScripts), so it runs in the same worker as the app shell cache.
//
// What the server sends is a small JSON text, encrypted so that only this browser can read it:
//   { "title": "...", "body": "...", "path": "/staff/approvals", "tag": "..." }
// It holds no name, phone number or amount: it says what happened and where to look. A browser that receives a push
// must show a notification (userVisibleOnly), so a message that cannot be read still shows a plain one.
//
// A tap opens the app on the route in "path". Only a route of this origin can be opened: anything else becomes /staff.

function bmsSafePath(path) {
  if (
    typeof path !== 'string' ||
    path.length > 200 ||
    path.charAt(0) !== '/' ||
    path.charAt(1) === '/' ||
    /[\\\u0000-\u001f]/.test(path)
  ) {
    return '/staff';
  }
  return path;
}

function bmsText(value, max) {
  return typeof value === 'string' ? value.trim().slice(0, max) : '';
}

self.addEventListener('push', (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch (e) {
    data = {};
  }
  const title = bmsText(data.title, 80) || 'BMS';
  const body = bmsText(data.body, 200) || 'Something needs your attention. Open BMS to see it.';
  const options = {
    body,
    icon: '/icons/icon-192.png',
    badge: '/icons/icon-192.png',
    data: { path: bmsSafePath(data.path) },
  };
  const tag = bmsText(data.tag, 200);
  if (tag) {
    options.tag = tag;
  }
  event.waitUntil(self.registration.showNotification(title, options));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const path = bmsSafePath(event.notification.data && event.notification.data.path);
  event.waitUntil(
    (async () => {
      const open = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
      for (const client of open) {
        if (new URL(client.url).origin === self.location.origin) {
          await client.focus();
          if ('navigate' in client) {
            try {
              await client.navigate(path);
            } catch (e) {
              // A window that cannot be navigated keeps its page; the tap still brought it to the front.
            }
          }
          return;
        }
      }
      await self.clients.openWindow(path);
    })(),
  );
});
