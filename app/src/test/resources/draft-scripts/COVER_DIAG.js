function (a) {
          var title = (function () { var all = Array.prototype.slice.call(document.querySelectorAll('textarea')); var label = function (t) { return ((t.getAttribute('placeholder') || '') + ' ' + (t.getAttribute('aria-label') || '')).toLowerCase(); }; var shown = all.filter(function (t) { var r = t.getBoundingClientRect(); return r.width > 0 && r.height > 0; }); var named = shown.filter(function (t) { var l = label(t); return /title|タイトル/.test(l) && !/sub|サブ/.test(l); }); return named[0] || shown[0] || all[0] || null; })();
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          var cut = function (v, n) {
            var t = String(v || '').replace(/\s+/g, ' ').trim();
            return t.length > n ? t.slice(0, n) : t;
          };
          var seen = [];
          var list = [];
          var describe = function (el) {
            if (list.length >= a.max || seen.indexOf(el) >= 0) return;
            if (el === title || (body && body.contains(el))) return;
            seen.push(el);
            var own = '';
            var nodes = el.childNodes || [];
            for (var k = 0; k < nodes.length; k++) {
              if (nodes[k].nodeType === 3) own += nodes[k].textContent;
            }
            var data = [];
            var attrs = el.attributes || [];
            for (var j = 0; j < attrs.length; j++) {
              if (attrs[j].name.indexOf('data-') === 0) data.push(attrs[j].name);
            }
            var rect = el.getBoundingClientRect();
            list.push({
              tag: el.tagName.toLowerCase(),
              role: cut(el.getAttribute('role'), 20),
              aria: cut(el.getAttribute('aria-label'), 30),
              title: cut(el.getAttribute('title'), 30),
              data: data.slice(0, 6).join(','),
              cls: cut(el.getAttribute('class'), 40),
              text: cut(own, 20),
              w: Math.round(rect.width),
              h: Math.round(rect.height),
              icon: el.querySelector('svg') ? 'svg' : el.querySelector('img') ? 'img' : 'none',
            });
          };
          if (title) {
            var all = document.querySelectorAll('button, [role=button], label, a, input, [tabindex], [onclick]');
            for (var i = 0; i < all.length; i++) {
              if ((all[i].compareDocumentPosition(title) & 4) !== 0) describe(all[i]);
            }
            var box = title.parentElement;
            var inside = box ? box.querySelectorAll('*') : [];
            for (var m = 0; m < inside.length; m++) describe(inside[m]);
          }
          var shadowInputs = 0;
          var walk = function (root) {
            var els = root.querySelectorAll('*');
            for (var q = 0; q < els.length; q++) {
              if (els[q].shadowRoot) {
                shadowInputs += els[q].shadowRoot.querySelectorAll('input[type=file]').length;
                walk(els[q].shadowRoot);
              }
            }
          };
          walk(document);
          var frames = document.querySelectorAll('iframe');
          var frameInputs = 0;
          var unreadable = 0;
          for (var f = 0; f < frames.length; f++) {
            try {
              frameInputs += frames[f].contentDocument.querySelectorAll('input[type=file]').length;
            } catch (e) {
              unreadable++;
            }
          }
          return {
            titleFound: !!title,
            fileInputs: document.querySelectorAll('input[type=file]').length,
            shadowFileInputs: shadowInputs,
            frames: frames.length,
            frameFileInputs: frameInputs,
            unreadableFrames: unreadable,
            candidates: list,
          };
        }
