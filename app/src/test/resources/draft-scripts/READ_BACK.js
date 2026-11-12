function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          var norm = function (s) { return s.replace(/\s+/g, ' ').trim(); };
          var title = document.querySelector('textarea');
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          var text = body ? norm(body.textContent) : '';
          var blocks = body ? body.querySelectorAll('p, h1, h2, h3, h4, h5, h6, li').length : 0;
          var want = a.blocks || 0;
          return {
            titleOk: !!title && title.value === a.title,
            bodyOk: a.samples.every(function (s) { return text.indexOf(s) >= 0; }) && (want <= 1 || blocks * 2 >= want),
            blocks: blocks,
          };
        }
