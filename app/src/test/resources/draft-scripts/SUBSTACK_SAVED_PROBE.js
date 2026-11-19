function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          var norm = function (s) { return s.replace(/\s+/g, ' ').trim(); };
          var collect = function (node) {
            if (!node || typeof node !== 'object') return '';
            return (node.text || '') + (node.content || []).map(collect).join('');
          };
          var storedText = function (raw) {
            try { return norm(collect(JSON.parse(raw))); } catch (e) { return norm(String(raw)); }
          };
          return fetch('/api/v1/drafts/' + encodeURIComponent(a.key), { credentials: 'same-origin' })
            .then(function (response) {
              if (!response.ok) return { ok: true, saved: false, evidence: 'http=' + response.status };
              return response.json().then(function (draft) {
                var text = storedText(draft.draft_body);
                return {
                  ok: true,
                  saved: draft.draft_title === a.title && a.samples.every(function (s) { return text.indexOf(s) >= 0; }),
                  evidence: 'http=' + response.status,
                };
              });
            })
            .catch(function () { return { ok: true, saved: false, evidence: 'http=0' }; });
        }
