function (a) {
          var editor = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          var images = document.querySelectorAll('img');
          var uploaded = 0;
          var local = 0;
          for (var i = 0; i < images.length; i++) {
            var src = String(images[i].currentSrc || images[i].src || '');
            if (src.indexOf('https://') === 0) uploaded++;
            if (src.indexOf('blob:') === 0) local++;
          }
          var busy = false;
          var all = document.body.querySelectorAll('*');
          for (var j = 0; j < all.length && !busy; j++) {
            var el = all[j];
            if (el.children.length === 0 && !(editor && editor.contains(el))) {
              var text = String(el.textContent || '');
              busy = text.indexOf('アップロード中') >= 0 || text.toLowerCase().indexOf('uploading') >= 0;
            }
          }
          return { count: uploaded, local: local, uploading: busy || local > 0 };
        }
