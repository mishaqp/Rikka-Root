package me.rerere.rikkahub.miniapps

// Protocol reference: Moru 2101b5e457031d8adb1098dac880bdbb3019623f.
object MiniAppBridgeScript {
    val source: String = """
(function () {
  if (window.moru) return;
  var pending = {};
  var next = 0;
  // Problems go to Moru, which shows them to the agent after publishing.
  function report(kind, message) {
    try {
      MoruBridge.postMessage(JSON.stringify({
        method: '__report', args: { kind: kind, message: String(message).slice(0, 500) }
      }));
    } catch (e) {}
  }
  window.addEventListener('error', function (e) {
    var target = e.target;
    if (target && target !== window && (target.src || target.href)) {
      report('resource', 'Failed to load ' + (target.src || target.href).split('/app/').pop());
    } else {
      var where = e.filename ? ' (' + e.filename.split('/app/').pop() + ':' + e.lineno + ')' : '';
      report('error', (e.message || 'Script error') + where);
    }
  }, true);
  window.addEventListener('unhandledrejection', function (e) {
    var reason = e.reason;
    report('promise', reason && reason.message ? reason.message : String(reason));
  });
  function call(method, args) {
    return new Promise(function (resolve, reject) {
      var id = ++next;
      pending[id] = { resolve: resolve, reject: reject };
      MoruBridge.postMessage(JSON.stringify({ id: id, method: method, args: args || {} }));
    });
  }
  window.__moruReply = function (id, ok, value) {
    var request = pending[id];
    if (!request) return;
    delete pending[id];
    if (ok) request.resolve(value);
    else request.reject(new Error(value));
  };
  window.__moruChanged = function (key) {
    window.dispatchEvent(new CustomEvent('moru:storage', { detail: { key: key } }));
  };
  window.moru = {
    storage: {
      get: function (key) { return call('storage.get', { key: key }); },
      set: function (key, value) { return call('storage.set', { key: key, value: value }); },
      remove: function (key) { return call('storage.remove', { key: key }); },
      keys: function () { return call('storage.keys'); }
    },
    ai: {
      ask: function (prompt, options) {
        return call('ai.ask', { prompt: prompt, system: (options || {}).system });
      }
    },
    notify: function (title, body) {
      return call('notify', { title: title, body: body });
    },
    reminders: {
      set: function (id, reminder) { return call('reminders.set', { id: id, reminder: reminder }); },
      remove: function (id) { return call('reminders.remove', { id: id }); },
      list: function () { return call('reminders.list'); }
    },
    fetch: function (url, options) {
      options = options || {};
      return call('fetch', {
        url: url, method: options.method, headers: options.headers, body: options.body
      }).then(function (response) {
        response.json = function () { return JSON.parse(response.body); };
        response.text = function () { return response.body; };
        return response;
      });
    },
    calendar: {
      list: function (query) { return call('calendar.list', query); },
      add: function (event) { return call('calendar.add', event); }
    },
    app: {
      info: function () { return call('app.info'); }
    }
  };
})();
""".trimIndent()
}
