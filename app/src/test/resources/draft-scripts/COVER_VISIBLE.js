function (a) {
          var title = document.querySelector('textarea');
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
