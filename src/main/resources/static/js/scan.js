// Camera QR scanner (uses jsQR). Two modes, chosen by data-mode on #scanner:
//   hotel : decoded text must be this app's /customer/scan/<id> URL -> navigate to it
//   pass  : decoded text is a customer pass token -> put it in #token and submit #passForm
(function () {
  'use strict';
  var root = document.getElementById('scanner');
  if (!root) return;
  var mode = root.getAttribute('data-mode');
  var video = document.getElementById('video');
  var status = document.getElementById('scanStatus');
  var startBtn = document.getElementById('startScan');
  var stopBtn = document.getElementById('stopScan');
  var box = document.getElementById('scanBox');
  var canvas = document.createElement('canvas');
  var ctx = canvas.getContext('2d', { willReadFrequently: true });
  var stream = null, running = false;

  function say(key) { if (status) status.textContent = root.getAttribute('data-msg-' + key) || ''; }

  function handle(text) {
    if (mode === 'hotel') {
      try {
        var u = new URL(text);
        var m = u.pathname.match(/^\/customer\/scan\/([A-Za-z0-9_-]{16,64})$/);
        if (m) { window.location.href = '/customer/scan/' + m[1]; return true; }
      } catch (e) { /* not a URL */ }
      say('invalid');
      return false;
    }
    document.getElementById('token').value = text.trim();
    document.getElementById('passForm').submit();
    return true;
  }

  function tick() {
    if (!running) return;
    if (video.readyState === video.HAVE_ENOUGH_DATA) {
      canvas.width = video.videoWidth; canvas.height = video.videoHeight;
      ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
      var img = ctx.getImageData(0, 0, canvas.width, canvas.height);
      var code = window.jsQR && window.jsQR(img.data, img.width, img.height, { inversionAttempts: 'dontInvert' });
      if (code && code.data) {
        if (handle(code.data)) { stop(); return; }
        running = false; setTimeout(function () { running = true; requestAnimationFrame(tick); }, 1500);
        return;
      }
    }
    requestAnimationFrame(tick);
  }

  function ui(on) {
    if (box) box.classList.toggle('idle', !on);
    if (startBtn) startBtn.classList.toggle('d-none', on);
    if (stopBtn) stopBtn.classList.toggle('d-none', !on);
  }

  function stop() {
    running = false;
    if (stream) stream.getTracks().forEach(function (t) { t.stop(); });
    stream = null;
    if (video) video.srcObject = null;
    ui(false);
  }

  function start() {
    // Browsers only expose the live camera on HTTPS (or localhost). The photo button below works everywhere.
    if (!window.isSecureContext) { say('insecure'); return; }
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) { say('nocamera'); return; }
    navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' }, audio: false }).then(function (s) {
      stream = s; video.srcObject = s; video.setAttribute('playsinline', 'true');
      return video.play();
    }).then(function () { running = true; ui(true); say('scanning'); requestAnimationFrame(tick); })
      .catch(function () { stop(); say('denied'); });
  }

  // Fallback: open the phone's native camera app, take a photo of the QR, decode it here.
  var photo = document.getElementById('photoInput');
  if (photo) photo.addEventListener('change', function () {
    var file = photo.files && photo.files[0];
    if (!file) return;
    var url = URL.createObjectURL(file), img = new Image();
    img.onload = function () {
      var max = 1600, scale = Math.min(1, max / Math.max(img.width, img.height));
      canvas.width = Math.round(img.width * scale); canvas.height = Math.round(img.height * scale);
      ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
      URL.revokeObjectURL(url);
      var data = ctx.getImageData(0, 0, canvas.width, canvas.height);
      var code = window.jsQR && window.jsQR(data.data, data.width, data.height, { inversionAttempts: 'attemptBoth' });
      if (!code || !code.data || !handle(code.data)) say('photofail');
      photo.value = '';
    };
    img.onerror = function () { say('photofail'); };
    img.src = url;
  });

  var manual = document.getElementById('manualForm');
  if (manual) manual.addEventListener('submit', function (e) {
    e.preventDefault();
    handle((document.getElementById('manualCode').value || '').trim());
  });

  if (startBtn) startBtn.addEventListener('click', start);
  if (stopBtn) stopBtn.addEventListener('click', function () { stop(); say('stopped'); });
  window.addEventListener('pagehide', stop);
})();
