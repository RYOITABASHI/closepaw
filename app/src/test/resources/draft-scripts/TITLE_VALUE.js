function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          var title = document.querySelector('textarea');
          return { ok: true, same: !!title && title.value === a.title };
        }
