function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          var norm = function (s) { return s.replace(/\s+/g, ' ').trim(); };
          var words = '保存しました';
          var editor = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          var outside = false;
          if (a.title.indexOf(words) < 0) {
            var all = document.body.querySelectorAll('*');
            for (var i = 0; i < all.length && !outside; i++) {
              var el = all[i];
              var leaf = el.children.length === 0 && el.tagName !== 'TEXTAREA';
              if (leaf && !(editor && editor.contains(el))) outside = el.textContent.indexOf(words) >= 0;
            }
          }
          var api = performance.getEntriesByType('resource').some(function (entry) {
            if (entry.startTime < a.at) return false;
            if (entry.initiatorType !== 'fetch' && entry.initiatorType !== 'xmlhttprequest') return false;
            if (!(entry.responseStatus >= 200 && entry.responseStatus < 300)) return false;
            var url = new URL(entry.name);
            var host = url.hostname === 'note.com' || url.hostname === 'editor.note.com';
            return host && url.pathname.indexOf('/api/') === 0 && url.pathname.indexOf('draft_save') >= 0;
          });
          var evidence = 'outside=' + outside + ' api=' + api;
          if (!outside || !api) return { ok: true, saved: false, evidence: evidence };
          return fetch('https://note.com/api/v3/notes/' + encodeURIComponent(a.key) + '?draft=true', { credentials: 'include' })
            .then(function (response) {
              if (!response.ok) return { ok: true, saved: true, evidence: evidence + ' stored=unread-' + response.status };
              return response.json().then(function (json) {
                var note = json.data || {};
                var html = note.body || '';
                var text = norm(new DOMParser().parseFromString(html, 'text/html').body.textContent);
                var same = note.name === a.title && a.samples.every(function (s) { return text.indexOf(s) >= 0; });
                return { ok: true, saved: same, evidence: evidence + ' stored=' + (same ? 'same' : 'different') };
              });
            })
            .catch(function () { return { ok: true, saved: true, evidence: evidence + ' stored=unread' }; });
        }
