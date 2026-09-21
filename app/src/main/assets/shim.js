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
    var target = (a.getAttribute('target') || '').toLowerCase();
    if (target !== '_blank') return;
    var href = toAbs(a.getAttribute('href') || a.href);
    if (href && openExternal(href)) e.preventDefault();
  }, true);
})();
