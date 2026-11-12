function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          var title = document.querySelector('textarea');
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
