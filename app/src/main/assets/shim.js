(function() {
  'use strict';
  if (window.__wsShimInstalled) return;
  var bridge = window.Android;
  if (!bridge || !bridge.httpPost || !bridge.httpGet) return;
  window.__wsShimInstalled = true;

  var seq = 0;
  var CB = (window.__wscb = {});
  var TIMEOUT_MS = 30000;

  function callBridge(method, url, body, contentType, headers) {
    return new Promise(function(resolve, reject) {
      var id = 'c' + (++seq);
      var timer = setTimeout(function() {
        delete CB[id];
        reject(new TypeError('bridge request timeout'));
      }, TIMEOUT_MS);
      CB[id] = function(r) {
        clearTimeout(timer);
        delete CB[id];
        if (r && r.ok) resolve(r);
        else reject(new TypeError((r && r.error) || 'network error'));
      };
      var hjson = '{}';
      try { hjson = JSON.stringify(headers || {}); } catch (e) {}
      try {
        if (method === 'GET') {
          if (bridge.httpGetH) bridge.httpGetH(url, '__wscb.' + id, hjson);
          else bridge.httpGet(url, '__wscb.' + id);
        } else {
          var ct = contentType || 'application/x-www-form-urlencoded';
          if (bridge.httpPostH) bridge.httpPostH(url, body || '', ct, '__wscb.' + id, hjson);
          else bridge.httpPost(url, body || '', ct, '__wscb.' + id);
        }
      } catch (e) {
        clearTimeout(timer);
        delete CB[id];
        reject(e);
      }
    });
  }

  function headerGet(h, name) {
    try {
      if (!h) return null;
      if (typeof h.get === 'function') return h.get(name);
      if (Array.isArray(h)) {
        for (var i = 0; i < h.length; i++) {
          if (String(h[i][0]).toLowerCase() === name) return h[i][1];
        }
        return null;
      }
      var keys = Object.keys(h);
      for (var j = 0; j < keys.length; j++) {
        if (keys[j].toLowerCase() === name) return h[keys[j]];
      }
    } catch (e) {}
    return null;
  }

  function headerAll(h) {
    var out = {};
    try {
      if (!h) return out;
      if (typeof h.forEach === 'function') {
        h.forEach(function(v, k) { out[k] = v; });
        return out;
      }
      if (Array.isArray(h)) {
        for (var i = 0; i < h.length; i++) out[h[i][0]] = h[i][1];
        return out;
      }
      var keys = Object.keys(h);
      for (var j = 0; j < keys.length; j++) out[keys[j]] = h[keys[j]];
    } catch (e) {}
    return out;
  }

  function bodyToText(b) {
    if (b == null) return Promise.resolve('');
    if (typeof b === 'string') return Promise.resolve(b);
    try {
      if (typeof URLSearchParams !== 'undefined' && b instanceof URLSearchParams) {
        return Promise.resolve(b.toString());
      }
      if (typeof FormData !== 'undefined' && b instanceof FormData) {
        var parts = [];
        var it = b.entries();
        while (true) {
          var n = it.next();
          if (n.done) break;
          if (typeof n.value[1] !== 'string') {
            return Promise.reject(new Error('FormData with file fields is not supported by the bridge'));
          }
          parts.push(encodeURIComponent(n.value[0]) + '=' + encodeURIComponent(n.value[1]));
        }
        return Promise.resolve(parts.join('&'));
      }
      if (b && typeof b.text === 'function') return b.text();
    } catch (e) {
      return Promise.reject(e);
    }
    return Promise.reject(new Error('unsupported request body type'));
  }

  // ---------- 带文件的 FormData：走桥接的 multipart 上传 ----------
  // 网页里 File 对象的 name/size 会被送到 App；App 用它们找回用户刚在系统
  // 文件选择器里选中的那个文件，直接流式读取，不需要把文件内容搬进 JS。
  function formDataParts(fd) {
    var fields = [], files = [];
    try {
      var it = fd.entries();
      while (true) {
        var n = it.next();
        if (n.done) break;
        var name = n.value[0], v = n.value[1];
        if (typeof v === 'string') {
          fields.push({ name: name, value: v });
        } else if (v && typeof v === 'object' && typeof v.name === 'string') {
          files.push({
            name: name,
            filename: v.name,
            size: (typeof v.size === 'number' ? v.size : -1)
          });
        } else {
          return null;
        }
      }
    } catch (e) {
      return null;
    }
    return { fields: fields, files: files };
  }

  function postFormViaBridge(url, parts, headers) {
    return new Promise(function(resolve, reject) {
      if (!bridge.httpPostForm) { reject(new Error('bridge has no httpPostForm')); return; }
      var id = 'c' + (++seq);
      var timer = setTimeout(function() {
        delete CB[id];
        reject(new TypeError('bridge upload timeout'));
      }, 600000);
      CB[id] = function(r) {
        clearTimeout(timer);
        delete CB[id];
        if (r && r.ok) resolve(r);
        else reject(new TypeError((r && r.error) || 'upload failed'));
      };
      var payload = { headers: headers || {}, fields: parts.fields, files: parts.files };
      try {
        bridge.httpPostForm(url, JSON.stringify(payload), '__wscb.' + id);
      } catch (e) {
        clearTimeout(timer);
        delete CB[id];
        reject(e);
      }
    });
  }

  function makeResp(r, url) {
    var body = r && typeof r.data === 'string' ? r.data : '';
    var status = (r && r.status) || 0;
    var ct = (r && r.contentType) || '';
    var headers = {
      get: function(n) {
        return String(n).toLowerCase() === 'content-type' && ct ? ct : null;
      },
      has: function(n) { return headers.get(n) !== null; },
      forEach: function(fn) { if (ct) fn(ct, 'content-type'); }
    };
    return {
      ok: status >= 200 && status < 300,
      status: status,
      statusText: '',
      url: url || '',
      redirected: false,
      type: 'basic',
      headers: headers,
      text: function() { return Promise.resolve(body); },
      json: function() { return Promise.resolve(JSON.parse(body)); },
      clone: function() { return makeResp(r, url); }
    };
  }

  // ---------- fetch: native first, bridge fallback ----------
  function toAbs(u) {
    if (!u) return null;
    if (/^https?:/i.test(u)) return u;
    try {
      var abs = new URL(u, location.href).href;
      return /^https?:/i.test(abs) ? abs : null;
    } catch (e) { return null; }
  }

  if (typeof window.fetch === 'function') {
    var origFetch = window.fetch.bind(window);
    window.__wsOrigFetch = origFetch;
    window.fetch = function(input, init) {
      return origFetch(input, init).catch(function(err) {
        var url = null;
        var opts = init || {};
        if (typeof input === 'string') {
          url = input;
        } else if (input && typeof input.url === 'string') {
          url = input.url;
          if (!init && input.method) opts = { method: input.method, headers: input.headers };
        }
        url = toAbs(url);
        if (!url) throw err;
        var method = String(opts.method || 'GET').toUpperCase();
        var ct = headerGet(opts.headers, 'content-type') || '';
        var hdrs = headerAll(opts.headers);

        var fdParts = null;
        try {
          if (typeof FormData !== 'undefined' && opts.body instanceof FormData) {
            fdParts = formDataParts(opts.body);
          }
        } catch (e) {}
        if (fdParts && fdParts.files.length) {
          return postFormViaBridge(url, fdParts, hdrs).then(function(r) {
            return makeResp(r, url);
          });
        }

        return bodyToText(opts.body)
          .then(function(text) {
            if (!ct && method !== 'GET') ct = 'application/x-www-form-urlencoded';
            return callBridge(method, url, text, ct, hdrs);
          })
          .then(function(r) {
            return makeResp(r, url);
          });
      });
    };
  }

  // ---------- XMLHttpRequest: native first, bridge fallback ----------
  if (typeof window.XMLHttpRequest === 'function') {
    var OrigXHR = window.XMLHttpRequest;
    window.__wsOrigXHR = OrigXHR;

    function ShimXHR() {
      var native = new OrigXHR();
      var self = this;
      var method = 'GET';
      var url = '';
      var reqHeaders = {};
      var sentBody = null;
      var retried = false;

      var listeners = {};
      self.addEventListener = function(type, fn) {
        if (typeof fn !== 'function') return;
        (listeners[type] = listeners[type] || []).push(fn);
      };
      self.removeEventListener = function(type, fn) {
        var a = listeners[type];
        if (!a) return;
        var i = a.indexOf(fn);
        if (i >= 0) a.splice(i, 1);
      };

      function emit(type) {
        var a = listeners[type];
        if (!a) return;
        var e = { type: type, target: self, currentTarget: self, lengthComputable: false, loaded: 0, total: 0 };
        for (var i = 0; i < a.length; i++) {
          try { a[i].call(self, e); } catch (err) {}
        }
      }

      function prop(name) {
        var h = self['on' + name];
        if (typeof h === 'function') { try { h.call(self); } catch (err) {} }
      }

      function copyState() {
        self.readyState = native.readyState;
        self.status = native.status;
        self.statusText = native.statusText;
        self.responseText = native.responseText;
        try { self.response = native.response; } catch (e) {}
        if (native.responseURL) self.responseURL = native.responseURL;
      }

      function finishNative() {
        copyState();
        prop('readystatechange'); emit('readystatechange');
        prop('load'); emit('load');
        prop('loadend'); emit('loadend');
      }

      function finishError() {
        self.readyState = 4;
        self.status = 0;
        prop('readystatechange'); emit('readystatechange');
        prop('error'); emit('error');
        prop('loadend'); emit('loadend');
      }

      function finishBridge(r) {
        self.readyState = 4;
        self.status = r.status || 0;
        self.statusText = '';
        self.responseText = r.data || '';
        self.response = r.data || '';
        self.responseURL = url;
        prop('readystatechange'); emit('readystatechange');
        if (self.status > 0) { prop('load'); emit('load'); }
        else { prop('error'); emit('error'); }
        prop('loadend'); emit('loadend');
      }

      function retry() {
        if (retried) { finishError(); return; }
        var abs = toAbs(url);
        if (!abs) { finishError(); return; }
        retried = true;
        var ct = reqHeaders['content-type'] || '';
        var hdrs = {};
        for (var hk in reqHeaders) hdrs[hk] = reqHeaders[hk];

        var fdParts = null;
        try {
          if (typeof FormData !== 'undefined' && sentBody instanceof FormData) {
            fdParts = formDataParts(sentBody);
          }
        } catch (e) {}
        if (fdParts && fdParts.files.length) {
          postFormViaBridge(abs, fdParts, hdrs).then(finishBridge).catch(finishError);
          return;
        }

        bodyToText(sentBody)
          .then(function(text) {
            return callBridge(method, abs, text, method === 'GET' ? '' : ct, hdrs);
          })
          .then(finishBridge)
          .catch(finishError);
      }

      native.onreadystatechange = function() {
        if (retried) return;
        copyState();
        prop('readystatechange'); emit('readystatechange');
      };
      native.onload = function() {
        if (retried) return;
        if (native.status === 0) { retry(); return; }
        finishNative();
      };
      native.onerror = function() {
        if (retried) return;
        retry();
      };
      native.onabort = function() {
        if (retried) return;
        copyState();
        prop('abort'); emit('abort');
        prop('loadend'); emit('loadend');
      };
      native.ontimeout = function() {
        if (retried) return;
        self.readyState = 4;
        prop('timeout'); emit('timeout');
        prop('loadend'); emit('loadend');
      };

      self.open = function(m, u) {
        method = String(m || 'GET').toUpperCase();
        url = String(u || '');
        self.readyState = 1;
        native.open(m, u);
      };
      self.setRequestHeader = function(k, v) {
        reqHeaders[String(k).toLowerCase()] = String(v);
        native.setRequestHeader(k, v);
      };
      self.send = function(b) {
        sentBody = b;
        native.send(b);
      };
      self.abort = function() { native.abort(); };
      self.overrideMimeType = function() {};
      self.getAllResponseHeaders = function() {
        if (retried) return '';
        try { return native.getAllResponseHeaders(); } catch (e) { return ''; }
      };
      self.getResponseHeader = function(n) {
        if (retried) return null;
        try { return native.getResponseHeader(n); } catch (e) { return null; }
      };
      self.upload = { addEventListener: function() {} };
    }

    window.XMLHttpRequest = ShimXHR;
  }

  // ---------- 剪贴板 ----------
  // App 内网页常见 http:// 源(非安全上下文): navigator.clipboard 整个不存在,
  // document.execCommand('copy'/'paste') 也常常被禁 → 页面上的「复制」按钮点了没反应。
  // 这里用 App 的 ClipboardManager 补齐。
  function bridgeCopy(text) {
    try {
      if (bridge.copyText && bridge.copyText(String(text))) return Promise.resolve();
    } catch (e) {}
    return Promise.reject(new Error('copy failed'));
  }

  function bridgeRead() {
    try {
      if (bridge.readClipboard) return Promise.resolve(bridge.readClipboard() || '');
    } catch (e) {}
    return Promise.reject(new Error('clipboard read failed'));
  }

  (function installClipboard() {
    var native = null;
    try { native = navigator.clipboard || null; } catch (e) {}

    var api = {};
    api.writeText = function(text) {
      if (native && typeof native.writeText === 'function') {
        return native.writeText(text).catch(function() { return bridgeCopy(text); });
      }
      return bridgeCopy(text);
    };
    api.readText = function() {
      if (native && typeof native.readText === 'function') {
        return native.readText().then(function(v) {
          return (v === null || v === undefined || v === '') ? bridgeRead() : v;
        }).catch(function() { return bridgeRead(); });
      }
      return bridgeRead();
    };
    // 少数内核对 write/read（ClipboardItem）实现更全，原样透传
    if (native && typeof native.write === 'function') api.write = function() { return native.write.apply(native, arguments); };
    if (native && typeof native.read === 'function') api.read = function() { return native.read.apply(native, arguments); };

    try {
      Object.defineProperty(navigator, 'clipboard', { value: api, configurable: true });
    } catch (e) {
      try {
        Object.defineProperty(Navigator.prototype, 'clipboard', {
          get: function() { return api; }, configurable: true
        });
      } catch (e2) {}
    }

    // 老接口兜底: 部分页面用 execCommand('copy') 复制临时输入框内容
    var origExec = document.execCommand ? document.execCommand.bind(document) : null;
    document.execCommand = function(cmd) {
      if (String(cmd).toLowerCase() === 'copy') {
        try {
          var sel = window.getSelection ? String(window.getSelection()) : '';
          if (sel) {
            var ok = false;
            try { ok = bridgeCopy(sel); } catch (e) {}
            if (ok && ok.then) { ok.then(function() {}, function() {}); return true; }
          }
        } catch (e) {}
      }
      return origExec ? origExec.apply(document, arguments) : false;
    };
  })();

  // ---------- 解码能力：纠正 WebView 对 H.265 的谎报 ----------
  // Android WebView 的 canPlayType("video/mp4; codecs=hvc1...") 在不少机型/ROM 上
  // 一律返回 "probably"，但真解码时抛 MEDIA_ERR_DECODE，页面于是拿到一个本机播不了的
  // H.265 码流（微博/B站/资源站都按 canPlayType 选码流、或决定要不要服务端转码）。
  // 只在本机确实没有 H.265 解码器时把回答改成空串，其余一律沿用原生回答——
  // VP9/AV1 即使没硬解也有 Chromium 内置软解，不能按"系统没解码器"否决。
  (function fixCanPlayType() {
    var caps = null;
    try { if (bridge.mediaCaps) caps = JSON.parse(bridge.mediaCaps()); } catch (e) {}
    if (!caps || typeof caps.hevc !== 'boolean') return;
    window.__wsMediaCaps = caps;

    function claimsHevc(type) {
      var s = String(type || '').toLowerCase();
      return s.indexOf('hvc1') >= 0 || s.indexOf('hev1') >= 0 ||
             s.indexOf('hevc') >= 0 || s.indexOf('h265') >= 0 ||
             s.indexOf('h.265') >= 0;
    }
    function deny(type) {
      return caps.hevc === false && claimsHevc(type);
    }

    var proto = window.HTMLMediaElement && HTMLMediaElement.prototype;
    if (proto && typeof proto.canPlayType === 'function') {
      var origCPT = proto.canPlayType;
      proto.canPlayType = function(type) {
        if (deny(type)) return '';
        return origCPT.apply(this, arguments);
      };
    }
    // MSE 播放器（B站 DASH 等）用 MediaSource.isTypeSupported 判能力
    var MS = window.MediaSource;
    if (MS && typeof MS.isTypeSupported === 'function') {
      var origITS = MS.isTypeSupported;
      MS.isTypeSupported = function(type) {
        if (deny(type)) return false;
        return origITS.apply(this, arguments);
      };
    }
  })();

  // ---------- 新窗口 / target=_blank ----------
  // 网页里的「浏览器打开」按钮走 window.open, WebView 不实现多窗口时点了没反应。
  // 统一交给系统浏览器打开，应用窗口保持停留在当前页面。
  function openExternal(url) {
    try {
      if (bridge.openExternal && /^https?:/i.test(url) && bridge.openExternal(url)) return true;
    } catch (e) {}
    return false;
  }

  if (typeof window.open === 'function') {
    var origOpen = window.open;
    window.open = function(url) {
      var abs = toAbs(url);
      if (abs && openExternal(abs)) return null;
      try { return origOpen.apply(window, arguments); } catch (e) { return null; }
    };
  }

  document.addEventListener('click', function(e) {
    if (e.defaultPrevented) return;
    var el = e.target;
    var a = null;
    while (el && el !== document) {
      if (el.tagName === 'A') { a = el; break; }
      el = el.parentNode;
    }
    if (!a) return;
    if (a.hasAttribute && a.hasAttribute('download')) return;   // 下载链接交给下载流程
    // 网页自己声明的「开 App」链接（全家日历的豆瓣条目用 data-app=douban://…，
    // 且往往同时带 target=_blank）：capture 相在这里劫持的话，页面自己的点击处理器
    // 会看到 defaultPrevented 而直接放弃，App 内就永远弹不出「此网站请求打开 App」。
    // 放行给页面处理器 → 它 location.href=douban://… → App 拦截弹条。
    if (a.hasAttribute && a.hasAttribute('data-app')) return;
    var target = (a.getAttribute('target') || '').toLowerCase();
    if (target !== '_blank') return;
    var href = toAbs(a.getAttribute('href') || a.href);
    if (href && openExternal(href)) e.preventDefault();
  }, true);

  // ---------- 语音合成：speechSynthesis 补丁 ----------
  // Android WebView 不实现 Web Speech API（window.speechSynthesis 整个不存在），
  // 学习类站点（轰轰爱学习等）的朗读会直接弹"浏览器不支持语音合成"。
  // 这里把接口补齐，后端是 App 的系统 TTS（Android.ttsSpeak / ttsStop / ttsVoices）。
  // 页面用法（cancel → setTimeout → speak、onend 串下一句、getVoices 挑中文嗓音、
  // 首次点击播空 utterance 解锁）全部照浏览器语义支持。
  (function installSpeech() {
    if (!bridge.ttsSpeak) return;
    var nativeSynth = window.speechSynthesis;
    if (nativeSynth) {
      // 有的 WebView 有对象却一个语音都报不出来（等于不能用）→ 照样接管；
      // 真能报出语音的原生实现就让给它。
      try {
        if (nativeSynth.getVoices && nativeSynth.getVoices().length > 0) return;
      } catch (e) { return; }
    }

    var byId = {};          // utteranceId -> utterance（App 回调时找回它）
    var order = [];         // 待播/在播顺序
    var seq = 0;
    var voicesCache = [];
    var listeners = [];
    var pausedList = [];

    function num(v, dflt) {
      var n = Number(v);
      return (isFinite(n) && n > 0) ? n : dflt;
    }

    function fire(u, type, extra) {
      var ev = { type: type, target: u, currentTarget: u, timeStamp: Date.now() };
      if (extra) for (var k in extra) ev[k] = extra[k];
      var h = u['on' + type];
      if (typeof h === 'function') {
        try { h.call(u, ev); } catch (e) { console.error('speech ' + type + ' handler:', e); }
      }
      var list = u._wsL && u._wsL[type];
      if (list) {
        for (var i = 0; i < list.length; i++) {
          try { list[i].call(u, ev); } catch (e) {}
        }
      }
    }

    function flags() {
      var speaking = 0, queued = 0;
      for (var i = 0; i < order.length; i++) {
        if (order[i]._wsStarted) speaking++; else queued++;
      }
      speech.speaking = speaking > 0;
      speech.pending = queued > 0;
    }

    function drop(u) {
      if (u._wsId) delete byId[u._wsId];
      var i = order.indexOf(u);
      if (i >= 0) order.splice(i, 1);
    }

    function Utterance(text) {
      this.text = String(text == null ? '' : text);
      this.lang = '';
      this.voice = null;
      this.volume = 1;
      this.rate = 1;
      this.pitch = 1;
      this.onstart = null;
      this.onend = null;
      this.onerror = null;
      this.onpause = null;
      this.onresume = null;
      this.onmark = null;
      this.onboundary = null;
      this._wsId = '';
      this._wsStarted = false;
      this._wsL = {};
    }
    Utterance.prototype.addEventListener = function (type, fn) {
      if (typeof fn !== 'function') return;
      (this._wsL[type] = this._wsL[type] || []).push(fn);
    };
    Utterance.prototype.removeEventListener = function (type, fn) {
      var a = this._wsL[type];
      if (!a) return;
      var i = a.indexOf(fn);
      if (i >= 0) a.splice(i, 1);
    };
    Utterance.prototype.dispatchEvent = function (ev) {
      if (ev && ev.type) fire(this, ev.type, ev);
      return true;
    };

    // ---- 语音列表：App 侧同步返回（addJavascriptInterface 同步调用） ----
    function refreshVoices() {
      var raw = [];
      try { raw = JSON.parse(bridge.ttsVoices() || '[]'); } catch (e) { raw = []; }
      var seen = {}, out = [];
      for (var i = 0; i < (raw || []).length; i++) {
        var v = raw[i] || {};
        if (!v.name) continue;
        var key = v.name + '|' + (v.lang || '');
        if (seen[key]) continue;
        seen[key] = 1;
        out.push({
          name: v.name,
          lang: v.lang || '',
          localService: true,
          'default': false,
          voiceURI: v.name
        });
      }
      voicesCache = out;
      return out;
    }

    function getVoices() {
      // 引擎就绪前页面来问会拿到空表（和浏览器一致）；之后每次问都重取，无需等事件
      if (!voicesCache.length) refreshVoices();
      return voicesCache.slice();
    }

    function emitVoices() {
      var ev = { type: 'voiceschanged', target: speech, currentTarget: speech, timeStamp: Date.now() };
      if (typeof speech.onvoiceschanged === 'function') {
        try { speech.onvoiceschanged(ev); } catch (e) { console.error('voiceschanged handler:', e); }
      }
      for (var i = 0; i < listeners.length; i++) {
        try { listeners[i].call(speech, ev); } catch (e) {}
      }
    }

    // ---- 朗读 ----
    function speak(u) {
      if (!u || typeof u !== 'object') return;
      u._wsStarted = false;
      u._wsId = 'u' + (++seq);
      byId[u._wsId] = u;
      order.push(u);
      flags();
      var text = String(u.text == null ? '' : u.text);
      // 空 utterance（移动端"解锁 TTS"的常见写法）：按浏览器行为立刻收尾，不占着队列
      if (!text.trim()) {
        setTimeout(function () { start(u); end(u); }, 0);
        return;
      }
      var id = u._wsId;
      setTimeout(function () { if (byId[id]) start(u); }, 0);
      try {
        bridge.ttsSpeak(text, u.lang || '', num(u.rate, 1), num(u.pitch, 1),
                        (u.voice && u.voice.name) || '', id);
      } catch (e) {
        fail(u, 'synthesis-failed');
      }
    }

    function start(u) {
      if (byId[u._wsId] !== u) return;
      u._wsStarted = true;
      flags();
      fire(u, 'start');
    }

    function end(u) {
      drop(u);
      flags();
      fire(u, 'end');
    }

    function fail(u, reason) {
      drop(u);
      flags();
      fire(u, 'error', { error: reason, message: reason });
    }

    function cancel() {
      for (var id in byId) delete byId[id];
      order.length = 0;
      pausedList.length = 0;
      speech.paused = false;
      flags();
      try { bridge.ttsStop(); } catch (e) {}
    }

    // Android 的系统 TTS 没有暂停/继续：先停声，恢复时从头再念（页面几乎不用这两个）
    function pause() {
      speech.paused = true;
      if (!order.length) return;
      pausedList = order.slice();
      for (var id in byId) delete byId[id];
      order.length = 0;
      flags();
      for (var i = 0; i < pausedList.length; i++) fire(pausedList[i], 'pause');
      try { bridge.ttsStop(); } catch (e) {}
    }

    function resume() {
      speech.paused = false;
      if (!pausedList.length) return;
      var list = pausedList;
      pausedList = [];
      for (var i = 0; i < list.length; i++) {
        fire(list[i], 'resume');
        speak(list[i]);
      }
    }

    var speech = {
      pending: false,
      speaking: false,
      paused: false,
      onvoiceschanged: null,
      speak: speak,
      cancel: cancel,
      pause: pause,
      resume: resume,
      getVoices: getVoices,
      addEventListener: function (type, fn) {
        if (type === 'voiceschanged' && typeof fn === 'function') listeners.push(fn);
      },
      removeEventListener: function (type, fn) {
        var i = listeners.indexOf(fn);
        if (i >= 0) listeners.splice(i, 1);
      },
      dispatchEvent: function (ev) {
        if (ev && ev.type === 'voiceschanged') emitVoices();
        return true;
      }
    };

    // App 回调：TTS 播完/出错
    window.__webshareTtsDone = function (id, err) {
      var u = byId[id];
      if (!u) return;
      if (err) fail(u, 'synthesis-failed'); else end(u);
    };
    // App 回调：系统 TTS 刚就绪（之前的 getVoices 是空的），页面该重新挑嗓音了
    window.__webshareTtsVoices = function () {
      refreshVoices();
      emitVoices();
    };

    window.SpeechSynthesisUtterance = Utterance;
    try {
      Object.defineProperty(window, 'speechSynthesis', {
        value: speech, configurable: true, writable: false
      });
    } catch (e) {
      window.speechSynthesis = speech;
    }
    refreshVoices();
  })();

  // ---------- 系统通知：网页 Notification → App 的系统通知 ----------
  // Android WebView 不实现网页通知：window.Notification 对象在，但 requestPermission
  // 永远拿不到权限、new Notification 什么都不弹，页面里的"提醒你一下"就静默消失了。
  // 这里把它接到 App 的原生通知上（Android.notify → 系统通知栏），页面代码不用改。
  (function installNotifications() {
    if (window.__wsNotifyInstalled) return;
    if (!bridge.notify) return;
    function Notify(title, options) {
      options = options || {};
      this.title = String(title == null ? '' : title);
      this.body = String(options.body == null ? '' : options.body);
      this.tag = String(options.tag == null ? '' : options.tag);
      this.onclick = null;
      this.onclose = null;
      this.onshow = null;
      this.onerror = null;
      var self = this;
      try {
        bridge.notify(self.title, self.body, String(location.host || ''));
      } catch (e) {}
      // onshow 在浏览器里是异步派的，页面常拿它当"已经发出去了"的信号
      setTimeout(function () {
        if (typeof self.onshow === 'function') {
          try { self.onshow({ type: 'show' }); } catch (e) {}
        }
      }, 0);
      this.close = function () {};
      this.addEventListener = function (type, fn) {
        if (type === 'show') self.onshow = fn;
        else if (type === 'click') self.onclick = fn;
        else if (type === 'close') self.onclose = fn;
        else if (type === 'error') self.onerror = fn;
      };
      this.removeEventListener = function () {};
    }
    Notify.permission = 'granted';
    Notify.maxActions = 0;
    Notify.requestPermission = function (cb) {
      if (typeof cb === 'function') { try { cb('granted'); } catch (e) {} }
      return Promise.resolve('granted');
    };
    try {
      Object.defineProperty(window, 'Notification', {
        value: Notify, configurable: true, writable: true
      });
    } catch (e) {
      try { window.Notification = Notify; } catch (e2) {}
    }
    window.__wsNotifyInstalled = true;
  })();

})();
