function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          if (!body) return { ok: false };
          body.focus();
          var first = body.firstElementChild;
          var range = document.createRange();
          if (first && first.tagName !== 'FIGURE' && !first.querySelector('img')) range.selectNodeContents(first);
          else range.selectNodeContents(body);
          range.collapse(true);
          var selection = getSelection();
          selection.removeAllRanges();
          selection.addRange(range);
          return { ok: true };
        }
