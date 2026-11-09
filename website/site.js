(function () {
  // The app's address (sign-in, trial form, status, terms) and the demo server's, set once on <body>. Opened from a
  // developer's machine, the page uses the local app instead (frontend on 5173, API on 8082).
  var local = location.protocol === 'file:' || /^(localhost|127\.0\.0\.1)$/.test(location.hostname);
  var app = local ? 'http://localhost:5173' : document.body.dataset.app;
  var api = local ? 'http://localhost:8082' : (document.body.dataset.api || document.body.dataset.app);
  var demo = document.body.dataset.demo;
  var lang = /(^|[?&])lang=en/.test(location.search) ? 'en'
    : /(^|[?&])lang=fr/.test(location.search) ? 'fr'
    : ((navigator.language || 'fr').slice(0, 2) === 'en' ? 'en' : 'fr');
  var only = document.body.dataset.lang;   // a page written in one language only (the articles)
  if (only) { lang = only; }
  var listeners = [];
  function apply() {
    document.documentElement.lang = lang;
    document.querySelectorAll('[lang]:not(html)').forEach(function (el) { el.hidden = el.getAttribute('lang') !== lang; });
    document.querySelectorAll('[data-app-path]').forEach(function (a) { a.href = app + a.dataset.appPath; });
    document.querySelectorAll('[data-demo-path]').forEach(function (a) { a.href = demo + a.dataset.demoPath; });
    var sw = document.querySelector('[data-switch]');
    if (sw) { sw.textContent = lang === 'fr' ? 'English' : 'Français'; }
    listeners.forEach(function (f) { f(lang); });
  }
  var sw = document.querySelector('[data-switch]');
  if (sw) { sw.addEventListener('click', function () { lang = lang === 'fr' ? 'en' : 'fr'; apply(); }); }
  window.HRPay = {
    app: app, api: api,
    lang: function () { return lang; },
    onLang: function (f) { listeners.push(f); }
  };
  apply();
})();
