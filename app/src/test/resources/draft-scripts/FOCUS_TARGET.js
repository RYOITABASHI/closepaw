function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          if (!body) return { ok: false, why: 'no-body' };
          var block = body.lastElementChild;
          if (!block || !/^(P|H[1-6])$/.test(block.tagName)) return { ok: false, why: 'no-block' };
          if (block.querySelector('img, figure, a, button, input, [contenteditable=false]')) return { ok: false, why: 'busy-block' };
          block.scrollIntoView({ block: 'center' });
          var rect = block.getBoundingClientRect();
          if (rect.width < 4 || rect.height < 4) return { ok: false, why: 'no-size' };
          var x = rect.left + Math.min(8, rect.width / 2);
          var y = rect.top + rect.height / 2;
          var hit = document.elementFromPoint(x, y);
          if (!hit || !block.contains(hit)) return { ok: false, why: 'covered' };
          return { ok: true, x: x, y: y };
        }
