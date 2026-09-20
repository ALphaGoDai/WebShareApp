(function() {
  'use strict';
  if (window.__wsShimInstalled) return;
  var bridge = window.Android;
  if (!bridge || !bridge.httpPost || !bridge.httpGet) return;
  window.__wsShimInstalled = true;

  var seq = 0;
  var CB = (window.__wscb = {});
  var TIMEOUT_MS = 30000;

  function callBridge(method, url, body, contentType) {
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
      try {
        if (method === 'GET') {
          bridge.httpGet(url, '__wscb.' + id);
        } else {
          bridge.httpPost(url, body || '', contentType || 'application/x-www-form-urlencoded', '__wscb.' + id);
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
        if (!url || !/^https?:/i.test(url)) throw err;
        var method = String(opts.method || 'GET').toUpperCase();
        var ct = headerGet(opts.headers, 'content-type') || '';
        return bodyToText(opts.body)
          .then(function(text) {
            if (!ct && method !== 'GET') ct = 'application/x-www-form-urlencoded';
            return callBridge(method, url, text, ct);
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
        retried = true;
        var ct = reqHeaders['content-type'] || '';
        bodyToText(sentBody)
          .then(function(text) {
            return callBridge(method, url, text, method === 'GET' ? '' : ct);
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
        if (native.status === 0 && /^https?:/i.test(url)) { retry(); return; }
        finishNative();
      };
      native.onerror = function() {
        if (retried) return;
        if (/^https?:/i.test(url)) { retry(); return; }
        finishError();
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
})();
