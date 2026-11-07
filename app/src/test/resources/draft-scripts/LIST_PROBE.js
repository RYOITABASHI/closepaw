function (a) {
          var norm = function (s) { return s.replace(/\s+/g, ' ').trim(); };
          var want = norm(a.title);
          var rows = [];
          var items = document.querySelectorAll('li, [role=listitem], article, tr');
          for (var i = 0; i < items.length && rows.length < 200; i++) {
            var anchors = items[i].querySelectorAll('a[href]');
            var hrefs = [];
            for (var j = 0; j < anchors.length && hrefs.length < 8; j++) hrefs.push(anchors[j].href);
            var outer = items[i].closest('a[href]');
            if (outer) hrefs.push(outer.href);
            if (hrefs.length > 0) {
              rows.push({ hrefs: hrefs, match: items[i].innerText.split('\n').map(norm).indexOf(want) >= 0 });
            }
          }
          return { href: location.href, rows: rows };
        }
