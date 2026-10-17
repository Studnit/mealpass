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

// Progressive Web App: register the service worker and offer "Install app".
(function () {
  'use strict';
  if ('serviceWorker' in navigator && (window.isSecureContext)) {
    window.addEventListener('load', function () { navigator.serviceWorker.register('/sw.js').catch(function () {}); });
  }
  var btn = document.getElementById('installApp');
  if (!btn) return;
  var standalone = window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone;
  if (standalone) return;
  var deferred = null;
  window.addEventListener('beforeinstallprompt', function (e) {
    e.preventDefault(); deferred = e; btn.classList.remove('d-none');
  });
  var ios = /iphone|ipad|ipod/i.test(navigator.userAgent);
  if (ios) btn.classList.remove('d-none');
  btn.addEventListener('click', function () {
    if (deferred) { deferred.prompt(); deferred.userChoice.finally(function () { deferred = null; btn.classList.add('d-none'); }); }
    else if (ios) { window.alert(btn.getAttribute('data-ios-hint')); }
  });
  window.addEventListener('appinstalled', function () { btn.classList.add('d-none'); });
})();

// Back button: shown on every inner page; goes to the previous page of this app, else to the fallback (home).
(function () {
  'use strict';
  var roots = /^\/(home|owner\/dashboard|staff\/dashboard|customer\/dashboard|admin\/dashboard)\/?$/;
  var bar = document.querySelector('[data-page-bar]');
  document.querySelectorAll('[data-back]').forEach(function (b) {
    if (bar && bar.contains(b) && roots.test(location.pathname)) return;     // dashboards have nothing to go back to
    b.hidden = false;
    b.addEventListener('click', function (e) {
      e.preventDefault();
      var sameSite = document.referrer && document.referrer.indexOf(location.origin) === 0 && document.referrer !== location.href;
      if (sameSite && history.length > 1) history.back();
      else location.href = b.getAttribute('data-fallback') || '/home';
    });
  });
})();

document.addEventListener('click', function (e) { if (e.target.closest('[data-print]')) window.print(); });

// Spoken announcements (browser text-to-speech). Everyone can stop the audio that is playing and switch voice alerts off
// (the speaker button in the top bar); the choice is remembered on this device.
(function () {
  'use strict';
  var synth = window.speechSynthesis;
  var toggle = document.getElementById('soundToggle');
  var msg = document.getElementById('voiceMsg');
  if (!synth) { if (toggle) toggle.hidden = true; if (msg) msg.hidden = true; return; }

  var on = storeGet('mt.sound') !== 'off', last = -1, unlocked = false;
  var replay = document.getElementById('voiceReplay'), stopBtn = document.getElementById('voiceStop');

  function showStop(playing) {
    if (replay) replay.classList.toggle('d-none', playing);
    if (stopBtn) stopBtn.classList.toggle('d-none', !playing);
  }
  function storeGet(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }
  function storeSet(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* private mode */ } }
  // One consistent female voice on every screen (customer and owner): the same picking rule runs everywhere.
  var FEMALE = /female|woman|heera|veena|lekha|kalpana|swara|neerja|aditi|zira|samantha|karen|tessa|moira|google (uk english female|हिन्दी|हिंदी)/i;
  var MALE = /(^|[^a-z])male|hemant|ravi|prabhat|madhur|rishi|david|mark|james|google uk english male/i;
  function pickVoice(lang) {
    var all = synth.getVoices() || [];
    var l = (lang || 'en-IN').toLowerCase(), base = l.split('-')[0];
    var pools = [
      all.filter(function (v) { return v.lang && v.lang.toLowerCase().replace('_', '-') === l; }),
      all.filter(function (v) { return v.lang && v.lang.toLowerCase().indexOf(base) === 0; }),
      all.filter(function (v) { return v.lang && v.lang.toLowerCase().indexOf('en') === 0; })
    ];
    for (var i = 0; i < pools.length; i++) {
      var p = pools[i]; if (!p.length) continue;
      return p.filter(function (v) { return FEMALE.test(v.name); })[0]
          || p.filter(function (v) { return !MALE.test(v.name); })[0] || p[0];
    }
    return null;
  }
  if (synth.onvoiceschanged !== undefined) synth.onvoiceschanged = function () { /* voices are looked up at speak time */ };

  var SPEEDS = { slow: 0.8, normal: 0.95, fast: 1.15 };
  var speed = storeGet('mt.speed'); if (!SPEEDS[speed]) speed = 'normal';
  function paintSpeed() {
    document.querySelectorAll('[data-speed]').forEach(function (b) {
      var active = b.getAttribute('data-speed') === speed;
      b.classList.toggle('btn-brand', active); b.classList.toggle('btn-outline-secondary', !active);
      b.setAttribute('aria-pressed', String(active));
    });
  }
  paintSpeed();
  document.querySelectorAll('[data-speed]').forEach(function (b) {
    b.addEventListener('click', function (e) {
      e.stopPropagation();                                   // keep the user menu open while choosing
      speed = b.getAttribute('data-speed'); storeSet('mt.speed', speed); paintSpeed();
      synth.cancel();
      var sample = document.getElementById('voiceSample');   // let the person hear the chosen speed
      if (sample) speak(sample.getAttribute('data-text'), sample.getAttribute('data-lang'));
    });
  });

  function speak(text, lang) {
    if (!text) return;
    var u = new SpeechSynthesisUtterance(text);
    u.lang = lang || 'en-IN'; u.rate = SPEEDS[speed]; u.pitch = 1.1;
    var v = pickVoice(u.lang); if (v) u.voice = v;
    u.onstart = function () { showStop(true); };
    u.onend = u.onerror = function () { if (!synth.speaking && !synth.pending) showStop(false); };
    synth.speak(u);
  }
  function stop() { synth.cancel(); showStop(false); }

  function paint() {
    if (!toggle) return;
    toggle.querySelector('i').className = on ? 'bi bi-volume-up' : 'bi bi-volume-mute';
    var label = toggle.getAttribute(on ? 'data-on-label' : 'data-off-label');
    toggle.setAttribute('aria-pressed', String(on)); toggle.setAttribute('aria-label', label); toggle.title = label;
  }
  paint();
  function unlock() {                         // browsers only allow speech after a tap on the page
    if (unlocked) return; unlocked = true;
    synth.speak(new SpeechSynthesisUtterance(' '));
  }
  document.addEventListener('pointerdown', unlock, { once: true });
  if (toggle) toggle.addEventListener('click', function () {
    on = !on; storeSet('mt.sound', on ? 'on' : 'off'); paint();
    if (on) unlock(); else stop();            // turning it off silences whatever is playing right now
  });

  // 1) message prepared by the server for this page (meal confirmed / request submitted)
  if (msg) {
    var say = function () { synth.cancel(); speak(msg.getAttribute('data-text'), msg.getAttribute('data-lang')); };
    if (replay) replay.addEventListener('click', say);          // an explicit tap always plays, even when alerts are off
    if (stopBtn) stopBtn.addEventListener('click', stop);
    if (on) say();                                              // may be blocked until the user taps; the button covers that
  }

  // 2) live alerts: read new meal scans and requests aloud while the page is open (owner / staff only get items)
  function poll() {
    if (!on || document.hidden) return;
    fetch('/notifications/poll?after=' + last, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        if (!d) return;
        if (last >= 0) d.items.forEach(function (i) { speak(i.text, i.lang); });
        last = d.latest;
      }).catch(function () { /* offline: try again next time */ });
  }
  if (document.querySelector('[data-owner-alerts]')) { poll(); setInterval(poll, 8000); }
})();
