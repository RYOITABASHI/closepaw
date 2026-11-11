function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          if (!body) return { ok: false };
          if (body.textContent.trim() !== '') return { ok: false, notEmpty: true };
          body.focus(); var selection = getSelection(); selection.selectAllChildren(body); selection.collapseToEnd();
          var done = document.execCommand('insertHTML', false, a.html);
          return { ok: true, accepted: !!done, chars: body.textContent.length };
        }
