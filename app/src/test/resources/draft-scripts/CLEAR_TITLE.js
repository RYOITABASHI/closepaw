function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          var title = document.querySelector('textarea');
          if (!title) return { ok: false };
          var setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
          title.focus();
          setter.call(title, '');
          title.dispatchEvent(new Event('input', { bubbles: true }));
          title.setSelectionRange(0, 0);
          return { ok: title.value === '' && document.activeElement === title };
        }
