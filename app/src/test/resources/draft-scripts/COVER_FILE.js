function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          var title = (function () { var all = Array.prototype.slice.call(document.querySelectorAll('textarea')); var label = function (t) { return ((t.getAttribute('placeholder') || '') + ' ' + (t.getAttribute('aria-label') || '')).toLowerCase(); }; var shown = all.filter(function (t) { var r = t.getBoundingClientRect(); return r.width > 0 && r.height > 0; }); var named = shown.filter(function (t) { var l = label(t); return /title|タイトル/.test(l) && !/sub|サブ/.test(l); }); return named[0] || shown[0] || all[0] || null; })();
          if (!title) return { ok: false, why: 'no-title' };
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          var inputs = document.querySelectorAll('input[type=file]');
          var cover = [];
          for (var i = 0; i < inputs.length; i++) {
            var beforeTitle = (inputs[i].compareDocumentPosition(title) & 4) !== 0;
            if (beforeTitle && !(body && body.contains(inputs[i]))) cover.push(inputs[i]);
          }
          if (cover.length !== 1) return { ok: false, why: 'inputs', total: inputs.length, candidates: cover.length };
          var raw = atob(a.base64); var bytes = new Uint8Array(raw.length); for (var i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i); var data = new DataTransfer(); data.items.add(new File([bytes], a.name, { type: a.mime }));
          cover[0].files = data.files;
          cover[0].dispatchEvent(new Event('change', { bubbles: true }));
          return { ok: true, total: inputs.length, candidates: 1 };
        }
