function (a) {
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          var active = document.activeElement;
          return { href: location.href, focused: !!body && !!active && body.contains(active), pageFocused: document.hasFocus() };
        }
