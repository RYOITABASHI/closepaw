function (a) {
          var title = document.querySelector('textarea');
          if (!title) return { ok: false, count: 0 };
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          var candidates = document.querySelectorAll('button, [role=button], label');
          var found = [];
          for (var i = 0; i < candidates.length; i++) {
            var el = candidates[i];
            var beforeTitle = (el.compareDocumentPosition(title) & 4) !== 0;
            var name = [el.getAttribute('aria-label'), el.getAttribute('title'), el.textContent].join(' ');
            var hits = a.names.filter(function (n) { return name.indexOf(n) >= 0; });
            if (beforeTitle && !(body && body.contains(el)) && hits.length > 0) found.push(el);
          }
          if (found.length !== 1) return { ok: false, count: found.length };
          var rect = found[0].getBoundingClientRect();
          var x = rect.left + rect.width / 2;
          var y = rect.top + rect.height / 2;
          var hit = document.elementFromPoint(x, y);
          if (!hit || !found[0].contains(hit)) return { ok: false, count: 1, why: 'covered' };
          return { ok: true, count: 1, x: x, y: y };
        }
