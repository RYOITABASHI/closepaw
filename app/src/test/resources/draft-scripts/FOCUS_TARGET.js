function (a) {
          if (location.href !== a.editorUrl) return { ok: false, moved: true };
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          if (!body) return { ok: false, why: 'no-body' };
          var unsafe = 'img, figure, a, button, input, [contenteditable=false]';
          var block = body.lastElementChild;
          var on = 'block';
          var box = block;
          if (!block || !/^(P|H[1-6])$/.test(block.tagName) || block.querySelector(unsafe)) {
            on = 'body';
            box = body;
          }
          box.scrollIntoView({ block: 'center' });
          var rect = box.getBoundingClientRect();
          if (rect.width < 4 || rect.height < 4) return { ok: false, why: 'no-size' };
          var x = rect.left + Math.min(8, rect.width / 2);
          var y = on === 'block' ? rect.top + rect.height / 2 : rect.bottom - Math.min(6, rect.height / 2);
          var hit = document.elementFromPoint(x, y);
          if (!hit || !box.contains(hit)) return { ok: false, why: 'covered' };
          var near = hit.closest ? hit.closest(unsafe) : null;
          if (near && body.contains(near)) return { ok: false, why: 'busy-point' };
          return { ok: true, x: x, y: y, on: on };
        }
