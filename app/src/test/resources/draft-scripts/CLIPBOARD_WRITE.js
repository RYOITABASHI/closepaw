function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          if (!navigator.clipboard || typeof ClipboardItem === 'undefined') return { ok: false, why: 'no-api' };
          var plain = new DOMParser().parseFromString(a.html, 'text/html').body.textContent;
          var item = new ClipboardItem({
            'text/html': new Blob([a.html], { type: 'text/html' }),
            'text/plain': new Blob([plain], { type: 'text/plain' }),
          });
          return navigator.clipboard.write([item]).then(
            function () { return { ok: true }; },
            function (e) { return { ok: false, why: String(e && e.name) }; });
        }
