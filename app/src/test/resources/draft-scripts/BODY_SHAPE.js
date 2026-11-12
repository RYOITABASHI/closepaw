function (a) {
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          if (!body) return { found: false };
          return {
            found: true,
            chars: body.textContent.length,
            p: body.querySelectorAll('p').length,
            h: body.querySelectorAll('h1, h2, h3, h4, h5, h6').length,
            li: body.querySelectorAll('li').length,
          };
        }
