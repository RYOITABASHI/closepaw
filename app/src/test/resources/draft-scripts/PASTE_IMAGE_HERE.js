function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          if (!body) return { ok: false };
          var node = getSelection().anchorNode;
          if (!node || !body.contains(node)) return { ok: false };
          var raw = atob(a.base64); var bytes = new Uint8Array(raw.length); for (var i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i); var data = new DataTransfer(); data.items.add(new File([bytes], a.name, { type: a.mime }));
          body.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
          return { ok: true };
        }
