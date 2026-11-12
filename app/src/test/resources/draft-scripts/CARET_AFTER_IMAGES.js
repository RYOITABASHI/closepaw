function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          if (!body) return { ok: false, kind: 'no-body' };
          var children = body.children;
          var last = -1;
          for (var i = 0; i < children.length; i++) {
            if (children[i].tagName === 'FIGURE' || children[i].querySelector('img')) last = i;
          }
          if (a.mode === 'select-figure') {
            if (last < 0) return { ok: false, kind: 'no-image' };
            body.focus();
            var whole = document.createRange();
            whole.setStartBefore(children[last]);
            whole.setEndAfter(children[last]);
            var around = getSelection();
            around.removeAllRanges();
            around.addRange(whole);
            return { ok: true, kind: 'figure' };
          }
          for (var j = last + 1; j < children.length; j++) {
            var el = children[j];
            if (el.tagName === 'P' && el.textContent.trim() === '' && !el.querySelector('img')) {
              body.focus();
              var range = document.createRange();
              range.selectNodeContents(el);
              range.collapse(true);
              var selection = getSelection();
              selection.removeAllRanges();
              selection.addRange(range);
              return { ok: true, kind: 'empty-p' };
            }
          }
          return { ok: false, kind: last < 0 ? 'no-empty-p' : 'figure' };
        }
