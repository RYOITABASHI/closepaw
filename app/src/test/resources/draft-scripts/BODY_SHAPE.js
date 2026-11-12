function (a) {
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          if (!body) return { found: false };
          var shape = {
            found: true,
            chars: body.textContent.length,
            p: body.querySelectorAll('p').length,
            h: body.querySelectorAll('h1, h2, h3, h4, h5, h6').length,
            li: body.querySelectorAll('li').length,
          };
          if (typeof a.title === 'string') {
            var title = (function () { var all = Array.prototype.slice.call(document.querySelectorAll('textarea')); var label = function (t) { return ((t.getAttribute('placeholder') || '') + ' ' + (t.getAttribute('aria-label') || '')).toLowerCase(); }; var shown = all.filter(function (t) { var r = t.getBoundingClientRect(); return r.width > 0 && r.height > 0; }); var named = shown.filter(function (t) { var l = label(t); return /title|タイトル/.test(l) && !/sub|サブ/.test(l); }); return named[0] || shown[0] || all[0] || null; })();
            shape.titleSame = !!title && title.value === a.title;
          }
          return shape;
        }
