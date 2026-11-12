function (a) {
          var title = document.querySelector('textarea');
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          var items = document.querySelectorAll('[role=menuitem], [role=option], button, [role=button], li, label');
          var matches = [];
          for (var i = 0; i < items.length; i++) {
            var el = items[i];
            var text = [el.textContent, el.getAttribute('aria-label')].join(' ');
            var avoided = a.avoid.filter(function (w) { return text.indexOf(w) >= 0; });
            var box = el.getBoundingClientRect();
            var shown = box.width > 0 && box.height > 0;
            var outside = el !== title && !(body && body.contains(el));
            if (shown && outside && text.indexOf(a.item) >= 0 && avoided.length === 0) matches.push(el);
          }
          var found = matches.filter(function (el) {
            return matches.filter(function (other) { return other !== el && el.contains(other); }).length === 0;
          });
          if (found.length !== 1) return { ok: false, count: found.length };
          var rect = found[0].getBoundingClientRect();
          var x = rect.left + rect.width / 2;
          var y = rect.top + rect.height / 2;
          var hit = document.elementFromPoint(x, y);
          if (!hit || !found[0].contains(hit)) return { ok: false, count: 1, why: 'covered' };
          return { ok: true, count: 1, x: x, y: y };
        }
