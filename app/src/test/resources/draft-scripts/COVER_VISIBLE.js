function (a) {
          var title = (function () { var all = Array.prototype.slice.call(document.querySelectorAll('textarea')); var label = function (t) { return ((t.getAttribute('placeholder') || '') + ' ' + (t.getAttribute('aria-label') || '')).toLowerCase(); }; var shown = all.filter(function (t) { var r = t.getBoundingClientRect(); return r.width > 0 && r.height > 0; }); var named = shown.filter(function (t) { var l = label(t); return /title|タイトル/.test(l) && !/sub|サブ/.test(l); }); return named[0] || shown[0] || all[0] || null; })();
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          var cut = function (v, n) {
            var t = String(v || '').replace(/\s+/g, ' ').trim();
            return t.length > n ? t.slice(0, n) : t;
          };
          var els = document.querySelectorAll('input, label, a, li, [role=option], [role=menuitem], [role=button], button');
          var items = [];
          for (var i = 0; i < els.length && items.length < a.max; i++) {
            var el = els[i];
            var rect = el.getBoundingClientRect();
            var outside = el !== title && !(body && body.contains(el));
            if (outside && rect.width > 0 && rect.height > 0) {
              var own = '';
              var nodes = el.childNodes || [];
              for (var k = 0; k < nodes.length; k++) {
                if (nodes[k].nodeType === 3) own += nodes[k].textContent;
              }
              items.push([
                el.tagName.toLowerCase(),
                cut(el.getAttribute('role'), 20),
                cut(el.getAttribute('aria-label'), 30),
                cut(own, 20),
                cut(el.getAttribute('class'), 40),
                [Math.round(rect.width), Math.round(rect.height)].join('x'),
              ].join('|'));
            }
          }
          return {
            items: items,
            dialogs: document.querySelectorAll('[role=dialog]').length,
            menus: document.querySelectorAll('[role=menu]').length,
            modals: document.querySelectorAll('[aria-modal=true]').length,
            popovers: document.querySelectorAll('[popover]').length,
          };
        }
