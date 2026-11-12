function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          if (!this || this.tagName !== 'INPUT' || this.type !== 'file') return { ok: false };
          var raw = atob(a.base64); var bytes = new Uint8Array(raw.length); for (var i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i); var data = new DataTransfer(); data.items.add(new File([bytes], a.name, { type: a.mime }));
          this.files = data.files;
          this.dispatchEvent(new Event('change', { bubbles: true }));
          return { ok: true };
        }
