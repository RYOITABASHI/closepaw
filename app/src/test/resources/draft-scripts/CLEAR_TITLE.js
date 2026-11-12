function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          var title = (function () { var all = Array.prototype.slice.call(document.querySelectorAll('textarea')); var label = function (t) { return ((t.getAttribute('placeholder') || '') + ' ' + (t.getAttribute('aria-label') || '')).toLowerCase(); }; var shown = all.filter(function (t) { var r = t.getBoundingClientRect(); return r.width > 0 && r.height > 0; }); var named = shown.filter(function (t) { var l = label(t); return /title|タイトル/.test(l) && !/sub|サブ/.test(l); }); return named[0] || shown[0] || all[0] || null; })();
          if (!title) return { ok: false };
          var setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
          title.focus();
          setter.call(title, '');
          title.dispatchEvent(new Event('input', { bubbles: true }));
          title.setSelectionRange(0, 0);
          return { ok: title.value === '' && document.activeElement === title };
        }
