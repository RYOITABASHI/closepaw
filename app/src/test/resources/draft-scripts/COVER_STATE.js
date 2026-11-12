function (a) {
          var title = document.querySelector('textarea');
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          var count = 0;
          if (title) {
            var images = document.querySelectorAll('img');
            for (var i = 0; i < images.length; i++) {
              var im = images[i];
              var src = String(im.currentSrc || im.src || '');
              var beforeTitle = (im.compareDocumentPosition(title) & 4) !== 0;
              if (src.indexOf('https://') === 0 && beforeTitle && !(body && body.contains(im)) && !im.closest('[role=dialog]')) count++;
            }
          }
          var dialog = null;
          var dialogs = document.querySelectorAll('[role=dialog]');
          for (var j = 0; j < dialogs.length; j++) {
            if (dialogs[j].querySelector('canvas, img')) dialog = dialogs[j];
          }
          var result = { count: count, dialog: !!dialog, pressable: false };
          if (!dialog) return result;
          var buttons = dialog.querySelectorAll('button');
          var matches = [];
          for (var k = 0; k < buttons.length; k++) {
            if (a.labels.indexOf(buttons[k].textContent.trim()) >= 0) matches.push(buttons[k]);
          }
          if (matches.length !== 1 || matches[0].disabled) return result;
          var rect = matches[0].getBoundingClientRect();
          var x = rect.left + rect.width / 2;
          var y = rect.top + rect.height / 2;
          var hit = document.elementFromPoint(x, y);
          if (!hit || !matches[0].contains(hit)) return result;
          result.pressable = true;
          result.x = x;
          result.y = y;
          return result;
        }
