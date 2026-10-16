// Shared behaviour: duplicate-submit protection, confirmation dialogs, loading state.
(function () {
  'use strict';
  document.addEventListener('submit', function (e) {
    var form = e.target;
    if (!(form instanceof HTMLFormElement)) return;
    var msg = form.getAttribute('data-confirm');
    if (msg && !window.confirm(msg)) { e.preventDefault(); return; }
    if (form.method.toLowerCase() !== 'post' || form.hasAttribute('data-multi')) return;
    if (form.dataset.submitting === '1') { e.preventDefault(); return; }   // ignore double clicks
    form.dataset.submitting = '1';
    var buttons = form.querySelectorAll('button[type=submit], input[type=submit]');
    // Disable after the browser has captured the submission so the request still goes out.
    setTimeout(function () {
      buttons.forEach(function (b) {
        b.disabled = true;
        if (b.tagName === 'BUTTON' && !b.querySelector('.spinner-border')) {
          var s = document.createElement('span');
          s.className = 'spinner-border spinner-border-sm ms-2';
          s.setAttribute('aria-hidden', 'true');
          b.appendChild(s);
        }
      });
    }, 0);
  });
  // Back/forward cache: re-enable forms when the page is shown again.
  window.addEventListener('pageshow', function (ev) {
    if (!ev.persisted) return;
    document.querySelectorAll('form').forEach(function (f) { delete f.dataset.submitting; });
    document.querySelectorAll('button[disabled]').forEach(function (b) { b.disabled = false; });
  });
})();
