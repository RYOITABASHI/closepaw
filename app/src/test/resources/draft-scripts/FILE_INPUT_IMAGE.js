function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          var input = document.querySelector('input[type=file]');
          if (!input) return { ok: false };
          var raw = atob(a.base64); var bytes = new Uint8Array(raw.length); for (var i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i); var data = new DataTransfer(); data.items.add(new File([bytes], a.name, { type: a.mime }));
          input.files = data.files;
          input.dispatchEvent(new Event('change', { bubbles: true }));
          return { ok: true };
        }
