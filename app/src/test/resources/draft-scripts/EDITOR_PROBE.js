function (a) {
          var title = document.querySelector('textarea');
          var body = document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]');
          return {
            href: location.href,
            titleFound: !!title,
            bodyFound: !!body,
            titleEmpty: !!title && title.value === '',
            bodyEmpty: !!body && body.textContent.trim() === '',
          };
        }
