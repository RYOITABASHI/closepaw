function (a) {
          var all = Array.prototype.slice.call(document.querySelectorAll('textarea'));
          var chosen = (function () { var all = Array.prototype.slice.call(document.querySelectorAll('textarea')); var label = function (t) { return ((t.getAttribute('placeholder') || '') + ' ' + (t.getAttribute('aria-label') || '')).toLowerCase(); }; var shown = all.filter(function (t) { var r = t.getBoundingClientRect(); return r.width > 0 && r.height > 0; }); var named = shown.filter(function (t) { var l = label(t); return /title|タイトル/.test(l) && !/sub|サブ/.test(l); }); return named[0] || shown[0] || all[0] || null; })();
          return { ok: true, fields: all.map(function (t) {
            var r = t.getBoundingClientRect();
            return {
              placeholder: String(t.getAttribute('placeholder') || '').slice(0, 40),
              aria: String(t.getAttribute('aria-label') || '').slice(0, 40),
              shown: r.width > 0 && r.height > 0,
              chars: String(t.value || '').length,
              chosen: t === chosen,
            };
          }) };
        }
