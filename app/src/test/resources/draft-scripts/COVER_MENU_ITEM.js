function (a) {
          var items = document.querySelectorAll('[role=menuitem], [role=menu] button, [role=menu] li, [role=dialog] button, [role=listbox] li');
          var found = [];
          for (var i = 0; i < items.length; i++) {
            var text = items[i].textContent.trim();
            var avoided = a.avoid.filter(function (w) { return text.indexOf(w) >= 0; });
            if (text.indexOf(a.item) >= 0 && avoided.length === 0) found.push(items[i]);
          }
          if (found.length !== 1) return { ok: false, count: found.length };
          var rect = found[0].getBoundingClientRect();
          var x = rect.left + rect.width / 2;
          var y = rect.top + rect.height / 2;
          var hit = document.elementFromPoint(x, y);
          if (!hit || !found[0].contains(hit)) return { ok: false, count: 1, why: 'covered' };
          return { ok: true, count: 1, x: x, y: y };
        }
