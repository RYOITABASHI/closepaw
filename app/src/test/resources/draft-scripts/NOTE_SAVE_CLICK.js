function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          var matches = [];
          var buttons = document.querySelectorAll('button');
          for (var i = 0; i < buttons.length; i++) {
            if (buttons[i].textContent.trim() === '下書き保存') matches.push(buttons[i]);
          }
          if (matches.length !== 1 || matches[0].disabled) return { ok: false, matches: matches.length };
          var at = performance.now();
          matches[0].click();
          return { ok: true, matches: 1, at: at };
        }
