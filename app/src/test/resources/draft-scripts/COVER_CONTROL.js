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
          var icons = document.querySelectorAll('svg[aria-label], [role=img][aria-label]');
          for (var j = 0; j < icons.length; j++) {
            var label = icons[j].getAttribute('aria-label') || '';
            var named = a.names.filter(function (n) { return label.indexOf(n) >= 0; });
            var owner = icons[j].closest('button, [role=button]') || icons[j];
            var ownerBefore = (owner.compareDocumentPosition(title) & 4) !== 0;
            if (named.length > 0 && ownerBefore && !(body && body.contains(owner)) && found.indexOf(owner) < 0) found.push(owner);
          }
          if (found.length !== 1) return { ok: false, count: found.length };
          var control = found[0];
          var measure = function () {
            var rect = control.getBoundingClientRect();
            var x = rect.left + rect.width / 2;
            var y = rect.top + rect.height / 2;
            var hit = document.elementFromPoint(x, y);
            if (hit && control.contains(hit)) return { ok: true, count: 1, x: x, y: y };
            var by = hit ? [hit.tagName.toLowerCase(), String(hit.getAttribute('class') || '').slice(0, 40)].join(' ') : 'nothing';
            return { ok: false, count: 1, why: 'covered', coveredBy: by, x: x, y: y };
          };
          if (!a.scroll) return measure();
          window.scrollTo(0, 0);
          control.scrollIntoView({ block: 'center' });
          return new Promise(function (resolve) {
            requestAnimationFrame(function () { resolve(measure()); });
          });
        }
