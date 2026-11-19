function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          if (!body) return { ok: false, count: 0 };
          var found = [];
          var blocks = body.querySelectorAll('p');
          for (var i = 0; i < blocks.length; i++) {
            if (blocks[i].textContent.trim() === a.marker) found.push(blocks[i]);
          }
          if (found.length === 0 || (found.length > 1 && !a.any)) return { ok: false, count: found.length };
          body.focus();
          var range = document.createRange();
          range.selectNodeContents(found[0]);
          var selection = getSelection();
          selection.removeAllRanges();
          selection.addRange(range);
          return { ok: true, count: found.length };
        }
