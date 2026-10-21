/* Sets the theme before the first paint (issue #429). A blocking script in the head, from the same origin because the
 * production CSP is script-src 'self'. The choice (system, light or dark) is read from this device's storage; System
 * follows the system setting. frontend/src/app/theme-mode.ts holds the same key and colours and is tested against this file. */
(function () {
  var colours = { light: '#0077b6', dark: '#0e141b' };
  var choice = 'system';
  try {
    var saved = window.localStorage.getItem('bms.theme');
    if (saved === 'light' || saved === 'dark') choice = saved;
  } catch (e) {}
  var dark = choice === 'dark';
  if (choice === 'system') {
    try {
      dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    } catch (e) {}
  }
  var theme = dark ? 'dark' : 'light';
  var root = document.documentElement;
  root.setAttribute('data-theme', theme);
  root.style.colorScheme = theme;
  var meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute('content', colours[theme]);
})();
