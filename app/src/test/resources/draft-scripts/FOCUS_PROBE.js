function (a) {
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          var active = document.activeElement;
          var node = getSelection().anchorNode;
          var el = node && node.nodeType === 1 ? node : node && node.parentElement;
          var inBody = !!body && !!el && body.contains(el);
          var block = inBody ? el.closest('p, h1, h2, h3, h4, h5, h6, li, figcaption') : null;
          var where = 'none';
          if (inBody) where = el.closest('figure, figcaption') ? 'figure' : 'body';
          else if (active && active.tagName === 'TEXTAREA') where = 'title';
          return {
            href: location.href,
            focused: !!body && !!active && body.contains(active),
            pageFocused: document.hasFocus(),
            where: where,
            inFigure: inBody && !!el.closest('figure, figcaption'),
            caretEmptyP: !!block && block.tagName === 'P' && block.textContent.trim() === '' &&
              !block.querySelector('img') && !block.closest('figure'),
          };
        }
