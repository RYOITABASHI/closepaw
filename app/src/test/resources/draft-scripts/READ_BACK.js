function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          var norm = function (s) { return s.replace(/\s+/g, ' ').trim(); };
          var title = document.querySelector('textarea');
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          var text = body ? norm(body.textContent) : '';
          return {
            titleOk: !!title && title.value === a.title,
            bodyOk: a.samples.every(function (s) { return text.indexOf(s) >= 0; }),
          };
        }
