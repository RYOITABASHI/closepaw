function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          if (!body) return { ok: false };
          body.focus(); var selection = getSelection(); selection.selectAllChildren(body); selection.collapseToEnd();
          var data = new DataTransfer();
          data.setData('text/html', a.html);
          body.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
          return { ok: true };
        }
