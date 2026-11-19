function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          var norm = function (s) { return s.replace(/\s+/g, ' ').trim(); };
          var title = (function () { var all = Array.prototype.slice.call(document.querySelectorAll('textarea')); var label = function (t) { return ((t.getAttribute('placeholder') || '') + ' ' + (t.getAttribute('aria-label') || '')).toLowerCase(); }; var shown = all.filter(function (t) { var r = t.getBoundingClientRect(); return r.width > 0 && r.height > 0; }); var named = shown.filter(function (t) { var l = label(t); return /title|タイトル/.test(l) && !/sub|サブ/.test(l); }); return named[0] || shown[0] || all[0] || null; })();
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          var text = body ? norm(body.textContent) : '';
          var blocks = body ? body.querySelectorAll('p, h1, h2, h3, h4, h5, h6, li').length : 0;
          var want = a.blocks || 0;
          var markersLeft = text.indexOf('{{closepaw-') >= 0;
          return {
            titleOk: !!title && title.value === a.title,
            bodyOk: a.samples.every(function (s) { return text.indexOf(s) >= 0; }) && (want <= 1 || blocks * 2 >= want) && !markersLeft,
            blocks: blocks,
            markersLeft: markersLeft,
          };
        }
