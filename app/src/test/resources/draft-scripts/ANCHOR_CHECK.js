function (a) {
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          if (!body) return { href: location.href, count: 0, caretEmpty: false, caretAtEnd: false, prevPlain: false, afterPlain: false };
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
          var after = null;
          var siblings = block && block.parentNode ? block.parentNode.children : [];
          for (var n = 0; n < siblings.length - 1; n++) {
            if (siblings[n] === block) after = siblings[n + 1];
          }
          var atEnd = false;
          var selection = getSelection();
          if (block && selection.rangeCount > 0) {
            var caret = selection.getRangeAt(0);
            var rest = document.createRange();
            rest.selectNodeContents(block);
            rest.setStart(caret.endContainer, caret.endOffset);
            atEnd = rest.toString().trim() === '';
          }
          return {
            href: location.href,
            count: count,
            caretEmpty: caretEmpty,
            caretAtEnd: atEnd,
            prevPlain: !!prev && /^(P|H[1-6])$/.test(prev.tagName),
            afterPlain: !!after && /^(P|H[1-6]|UL|OL|BLOCKQUOTE)$/.test(after.tagName),
          };
        }
