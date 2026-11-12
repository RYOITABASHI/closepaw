function (a) {
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          if (!body) return { href: location.href, count: 0, caretEmpty: false, prevPlain: false };
          var count = 0;
          var blocks = body.querySelectorAll('p');
          for (var i = 0; i < blocks.length; i++) {
            if (blocks[i].textContent.trim() === a.marker) count++;
          }
          var node = getSelection().anchorNode;
          var el = node && node.nodeType === 1 ? node : node && node.parentElement;
          var block = el && body.contains(el) ? el.closest('p, h1, h2, h3, h4, h5, h6, li') : null;
          var caretEmpty = !!block && block.textContent.trim() === '' && !block.querySelector('img');
          var prev = block ? block.previousElementSibling : null;
          return {
            href: location.href,
            count: count,
            caretEmpty: caretEmpty,
            prevPlain: !!prev && /^(P|H[1-6])$/.test(prev.tagName),
          };
        }
