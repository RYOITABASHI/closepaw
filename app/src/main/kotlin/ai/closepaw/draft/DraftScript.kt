package ai.closepaw.draft

import kotlinx.serialization.json.JsonObject

private const val BODY_LOOKUP =
    "document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]')"

/**
 * The title field: a shown textarea labelled as the title (not the subtitle), else the first
 * shown one, else the first. Substack has more than one textarea, and the first in the
 * document is not always the visible title.
 */
private const val TITLE_LOOKUP =
    "(function () { var all = Array.prototype.slice.call(document.querySelectorAll('textarea')); var label = function (t) { return ((t.getAttribute('placeholder') || '') + ' ' + (t.getAttribute('aria-label') || '')).toLowerCase(); }; var shown = all.filter(function (t) { var r = t.getBoundingClientRect(); return r.width > 0 && r.height > 0; }); var named = shown.filter(function (t) { var l = label(t); return /title|タイトル/.test(l) && !/sub|サブ/.test(l); }); return named[0] || shown[0] || all[0] || null; })()"

/** First line of every script that changes the page: do nothing unless this is the same draft. */
private const val SAME_DRAFT =
    "if (location.href !== a.editorUrl) return { ok: false, moved: true };"

private const val CARET_TO_END =
    "body.focus(); var selection = getSelection(); selection.selectAllChildren(body); selection.collapseToEnd();"

private const val IMAGE_FILE =
    "var raw = atob(a.base64); var bytes = new Uint8Array(raw.length); " +
        "for (var i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i); " +
        "var data = new DataTransfer(); data.items.add(new File([bytes], a.name, { type: a.mime }));"

/** The one control the routine may press, on note only. */
internal const val NOTE_SAVE_LABEL = "下書き保存"

/** The words of note's saved toast. An editor can hold them too, so only text outside counts. */
private const val NOTE_SAVED_WORDS = "保存しました"

/** Start of the text of an in-body image anchor paragraph, e.g. `{{closepaw-image-2}}`. */
private const val ANCHOR_PREFIX = "{{closepaw-"

/** note's words while an image is still on its way to the server. */
private const val UPLOADING_WORDS = "アップロード中"

/**
 * Every JavaScript string the routine can run in the editor tab. [DraftBrowser.evaluate] takes
 * this enum, so no expression can be built from request data: values travel only in the JSON
 * argument of [expression].
 *
 * SaveDraftScriptsTest scans this file, comments included, and pins each script's full text.
 */
enum class DraftScript(val source: String) {
    /** Rows of the draft list that hold a link, and which of them show the exact title. */
    LIST_PROBE(
        """
        function (a) {
          var norm = function (s) { return s.replace(/\s+/g, ' ').trim(); };
          var want = norm(a.title);
          var rows = [];
          var items = document.querySelectorAll('li, [role=listitem], article, tr');
          for (var i = 0; i < items.length && rows.length < 200; i++) {
            var anchors = items[i].querySelectorAll('a[href]');
            var hrefs = [];
            for (var j = 0; j < anchors.length && hrefs.length < 8; j++) hrefs.push(anchors[j].href);
            var outer = items[i].closest('a[href]');
            if (outer) hrefs.push(outer.href);
            if (hrefs.length > 0) {
              rows.push({ hrefs: hrefs, match: items[i].innerText.split('\n').map(norm).indexOf(want) >= 0 });
            }
          }
          return { href: location.href, rows: rows };
        }
        """,
    ),

    /** Where are we, and are the title field and the body there and still empty? */
    EDITOR_PROBE(
        """
        function (a) {
          var title = $TITLE_LOOKUP;
          var body = $BODY_LOOKUP;
          return {
            href: location.href,
            titleFound: !!title,
            bodyFound: !!body,
            titleEmpty: !!title && title.value === '',
            bodyEmpty: !!body && body.textContent.trim() === '',
          };
        }
        """,
    ),

    /** Native value setter plus an input event, which is what the editors' framework listens to. */
    SET_TITLE(
        """
        function (a) {
          $SAME_DRAFT
          var title = $TITLE_LOOKUP;
          if (!title) return { ok: false };
          var setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
          title.focus();
          setter.call(title, a.title);
          title.dispatchEvent(new Event('input', { bubbles: true }));
          return { ok: title.value === a.title };
        }
        """,
    ),

    /**
     * Empties the title field and leaves it focused, so a trusted text insertion that follows
     * types the title as the owner would. Substack stores a title only from real input.
     */
    CLEAR_TITLE(
        """
        function (a) {
          $SAME_DRAFT
          var title = $TITLE_LOOKUP;
          if (!title) return { ok: false };
          var setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
          title.focus();
          setter.call(title, '');
          title.dispatchEvent(new Event('input', { bubbles: true }));
          title.setSelectionRange(0, 0);
          return { ok: title.value === '' && document.activeElement === title };
        }
        """,
    ),

    /**
     * The page's textareas for the log: labels cut to 40 characters, whether shown, length
     * only, and which one the title lookup picks. Never their text. Reads only.
     */
    TITLE_DIAG(
        """
        function (a) {
          var all = Array.prototype.slice.call(document.querySelectorAll('textarea'));
          var chosen = $TITLE_LOOKUP;
          return { ok: true, fields: all.map(function (t) {
            var r = t.getBoundingClientRect();
            return {
              placeholder: String(t.getAttribute('placeholder') || '').slice(0, 40),
              aria: String(t.getAttribute('aria-label') || '').slice(0, 40),
              shown: r.width > 0 && r.height > 0,
              chars: String(t.value || '').length,
              chosen: t === chosen,
            };
          }) };
        }
        """,
    ),

    /** Whether the title field holds exactly `a.title`. Reads only. */
    TITLE_VALUE(
        """
        function (a) {
          $SAME_DRAFT
          var title = $TITLE_LOOKUP;
          return { ok: true, same: !!title && title.value === a.title };
        }
        """,
    ),

    /**
     * Puts the validated HTML, with a plain-text alternative, on the clipboard, for the trusted
     * paste that follows (a key press the browser turns into its own paste command). The page's
     * clipboard API needs a user gesture, which the evaluate call grants for this script only.
     * The text never leaves the device: it goes from this page to the clipboard and back.
     */
    CLIPBOARD_WRITE(
        """
        function (a) {
          $SAME_DRAFT
          if (!navigator.clipboard || typeof ClipboardItem === 'undefined') return { ok: false, why: 'no-api' };
          var plain = new DOMParser().parseFromString(a.html, 'text/html').body.textContent;
          var item = new ClipboardItem({
            'text/html': new Blob([a.html], { type: 'text/html' }),
            'text/plain': new Blob([plain], { type: 'text/plain' }),
          });
          return navigator.clipboard.write([item]).then(
            function () { return { ok: true }; },
            function (e) { return { ok: false, why: String(e && e.name) }; });
        }
        """,
    ),

    /**
     * The body's length and its blocks, as numbers only: did the structure survive? With
     * `a.title`, also whether the title field still holds exactly that title.
     */
    BODY_SHAPE(
        """
        function (a) {
          var body = $BODY_LOOKUP;
          if (!body) return { found: false };
          var shape = {
            found: true,
            chars: body.textContent.length,
            p: body.querySelectorAll('p').length,
            h: body.querySelectorAll('h1, h2, h3, h4, h5, h6').length,
            li: body.querySelectorAll('li').length,
          };
          if (typeof a.title === 'string') {
            var title = $TITLE_LOOKUP;
            shape.titleSame = !!title && title.value === a.title;
          }
          return shape;
        }
        """,
    ),

    /**
     * The last resort, for an editor that took no paste: the browser's own editing command
     * inserts the same validated HTML at the caret. Only into an empty body. note's editor keeps
     * the text but drops the structure, so the routine reports a body put in this way.
     */
    INSERT_HTML(
        """
        function (a) {
          $SAME_DRAFT
          var body = $BODY_LOOKUP;
          if (!body) return { ok: false };
          if (body.textContent.trim() !== '') return { ok: false, notEmpty: true };
          $CARET_TO_END
          var done = document.execCommand('insertHTML', false, a.html);
          return { ok: true, accepted: !!done, chars: body.textContent.length };
        }
        """,
    ),

    /**
     * Where to put the pointer to give the body real focus. Preferably inside the body's last
     * block, if it is a plain paragraph or heading with nothing in it that reacts to a pointer;
     * otherwise near the bottom of the body itself (an empty body has no such block). The page
     * must show the body there, and nothing at that point may be an image, figure, link, button,
     * input or non-editable part. Otherwise no point is given.
     */
    FOCUS_TARGET(
        """
        function (a) {
          $SAME_DRAFT
          var body = $BODY_LOOKUP;
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
        """,
    ),

    /**
     * Is the focus inside the body, does the page have focus at all, which field holds the caret
     * (`body`, `figure`, `title` or `none`), is it inside an image's figure or caption, and is it
     * in an empty paragraph of its own?
     */
    FOCUS_PROBE(
        """
        function (a) {
          var body = $BODY_LOOKUP;
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
        """,
    ),

    /**
     * Gives the body the focus and the caret without a pointer, for an empty body that cannot be
     * clicked: focus on the body element, the caret at the start of its first block.
     */
    FOCUS_BODY(
        """
        function (a) {
          $SAME_DRAFT
          var body = $BODY_LOOKUP;
          if (!body) return { ok: false };
          body.focus();
          var first = body.firstElementChild;
          var range = document.createRange();
          if (first && first.tagName !== 'FIGURE' && !first.querySelector('img')) range.selectNodeContents(first);
          else range.selectNodeContents(body);
          range.collapse(true);
          var selection = getSelection();
          selection.removeAllRanges();
          selection.addRange(range);
          return { ok: true };
        }
        """,
    ),

    /**
     * note only: hands the cover image to the editor's own cover file input, the one file input
     * that sits before the title and outside the body. The bytes come from memory, so no file
     * chooser opens.
     */
    COVER_FILE(
        """
        function (a) {
          $SAME_DRAFT
          var title = $TITLE_LOOKUP;
          if (!title) return { ok: false, why: 'no-title' };
          var body = $BODY_LOOKUP;
          var inputs = document.querySelectorAll('input[type=file]');
          var cover = [];
          for (var i = 0; i < inputs.length; i++) {
            var beforeTitle = (inputs[i].compareDocumentPosition(title) & 4) !== 0;
            if (beforeTitle && !(body && body.contains(inputs[i]))) cover.push(inputs[i]);
          }
          if (cover.length !== 1) return { ok: false, why: 'inputs', total: inputs.length, candidates: cover.length };
          $IMAGE_FILE
          cover[0].files = data.files;
          cover[0].dispatchEvent(new Event('change', { bubbles: true }));
          return { ok: true, total: inputs.length, candidates: 1 };
        }
        """,
    ),

    /**
     * note only: stored images before the title and outside the body and any dialog (the cover),
     * and whether an image dialog (one holding a canvas or image, as a crop step does) is open
     * with exactly one enabled button labelled one of `a.labels`, and the point to press it, if
     * the page shows that button there.
     */
    COVER_STATE(
        """
        function (a) {
          var title = $TITLE_LOOKUP;
          var body = $BODY_LOOKUP;
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
        """,
    ),

    /**
     * note only: the editor's own cover control, before the title and outside the body: the one
     * button, label or button-like element whose aria-label, title or text holds one of
     * `a.names`, or that holds an icon (svg or role=img) whose aria-label does (note labels the
     * icon, not its button). Gives the point to press it, if the page shows it there; with
     * `a.scroll`, after scrolling it into view and one frame. When covered, it names what covers
     * the point (tag and class only) and still gives the point, for a hover.
     */
    COVER_CONTROL(
        """
        function (a) {
          var title = $TITLE_LOOKUP;
          if (!title) return { ok: false, count: 0 };
          var body = $BODY_LOOKUP;
          var candidates = document.querySelectorAll('button, [role=button], label');
          var found = [];
          for (var i = 0; i < candidates.length; i++) {
            var el = candidates[i];
            var beforeTitle = (el.compareDocumentPosition(title) & 4) !== 0;
            var name = [el.getAttribute('aria-label'), el.getAttribute('title'), el.textContent].join(' ');
            var hits = a.names.filter(function (n) { return name.indexOf(n) >= 0; });
            if (beforeTitle && !(body && body.contains(el)) && hits.length > 0) found.push(el);
          }
          var icons = document.querySelectorAll('svg[aria-label], [role=img][aria-label]');
          for (var j = 0; j < icons.length; j++) {
            var label = icons[j].getAttribute('aria-label') || '';
            var named = a.names.filter(function (n) { return label.indexOf(n) >= 0; });
            var owner = icons[j].closest('button, [role=button]') || icons[j];
            var ownerBefore = (owner.compareDocumentPosition(title) & 4) !== 0;
            if (named.length > 0 && ownerBefore && !(body && body.contains(owner)) && found.indexOf(owner) < 0) found.push(owner);
          }
          if (found.length !== 1) return { ok: false, count: found.length };
          var control = found[0];
          var measure = function () {
            var rect = control.getBoundingClientRect();
            var x = rect.left + rect.width / 2;
            var y = rect.top + rect.height / 2;
            var hit = document.elementFromPoint(x, y);
            if (hit && control.contains(hit)) return { ok: true, count: 1, x: x, y: y };
            var by = hit ? [hit.tagName.toLowerCase(), String(hit.getAttribute('class') || '').slice(0, 40)].join(' ') : 'nothing';
            return { ok: false, count: 1, why: 'covered', coveredBy: by, x: x, y: y };
          };
          if (!a.scroll) return measure();
          window.scrollTo(0, 0);
          control.scrollIntoView({ block: 'center' });
          return new Promise(function (resolve) {
            requestAnimationFrame(function () { resolve(measure()); });
          });
        }
        """,
    ),

    /**
     * note only: the one visible clickable element (outside the title and the body) whose text or
     * aria-label holds `a.item` and none of `a.avoid` (generated, stock or suggested images), with
     * the point to press it. Of nested matches only the innermost counts.
     */
    COVER_MENU_ITEM(
        """
        function (a) {
          var title = $TITLE_LOOKUP;
          var body = $BODY_LOOKUP;
          var items = document.querySelectorAll('[role=menuitem], [role=option], button, [role=button], li, label');
          var matches = [];
          for (var i = 0; i < items.length; i++) {
            var el = items[i];
            var text = [el.textContent, el.getAttribute('aria-label')].join(' ');
            var avoided = a.avoid.filter(function (w) { return text.indexOf(w) >= 0; });
            var box = el.getBoundingClientRect();
            var shown = box.width > 0 && box.height > 0;
            var outside = el !== title && !(body && body.contains(el));
            if (shown && outside && text.indexOf(a.item) >= 0 && avoided.length === 0) matches.push(el);
          }
          var found = matches.filter(function (el) {
            return matches.filter(function (other) { return other !== el && el.contains(other); }).length === 0;
          });
          if (found.length !== 1) return { ok: false, count: found.length };
          var rect = found[0].getBoundingClientRect();
          var x = rect.left + rect.width / 2;
          var y = rect.top + rect.height / 2;
          var hit = document.elementFromPoint(x, y);
          if (!hit || !found[0].contains(hit)) return { ok: false, count: 1, why: 'covered' };
          return { ok: true, count: 1, x: x, y: y };
        }
        """,
    ),

    /**
     * note only, a diagnostic when no cover control matched: up to `a.max` interactive elements
     * before the title and outside the body, plus everything in the title's own container,
     * described by page chrome only (tag, role, short aria-label and title, data attribute names,
     * class, a little own text, size, icon); and file inputs in the page, in shadow roots and in
     * same-origin frames. Never the title's or the body's text.
     */
    COVER_DIAG(
        """
        function (a) {
          var title = $TITLE_LOOKUP;
          var body = $BODY_LOOKUP;
          var cut = function (v, n) {
            var t = String(v || '').replace(/\s+/g, ' ').trim();
            return t.length > n ? t.slice(0, n) : t;
          };
          var seen = [];
          var list = [];
          var describe = function (el) {
            if (list.length >= a.max || seen.indexOf(el) >= 0) return;
            if (el === title || (body && body.contains(el))) return;
            seen.push(el);
            var own = '';
            var nodes = el.childNodes || [];
            for (var k = 0; k < nodes.length; k++) {
              if (nodes[k].nodeType === 3) own += nodes[k].textContent;
            }
            var data = [];
            var attrs = el.attributes || [];
            for (var j = 0; j < attrs.length; j++) {
              if (attrs[j].name.indexOf('data-') === 0) data.push(attrs[j].name);
            }
            var rect = el.getBoundingClientRect();
            list.push({
              tag: el.tagName.toLowerCase(),
              role: cut(el.getAttribute('role'), 20),
              aria: cut(el.getAttribute('aria-label'), 30),
              title: cut(el.getAttribute('title'), 30),
              data: data.slice(0, 6).join(','),
              cls: cut(el.getAttribute('class'), 40),
              text: cut(own, 20),
              w: Math.round(rect.width),
              h: Math.round(rect.height),
              icon: el.querySelector('svg') ? 'svg' : el.querySelector('img') ? 'img' : 'none',
            });
          };
          if (title) {
            var all = document.querySelectorAll('button, [role=button], label, a, input, [tabindex], [onclick]');
            for (var i = 0; i < all.length; i++) {
              if ((all[i].compareDocumentPosition(title) & 4) !== 0) describe(all[i]);
            }
            var box = title.parentElement;
            var inside = box ? box.querySelectorAll('*') : [];
            for (var m = 0; m < inside.length; m++) describe(inside[m]);
          }
          var shadowInputs = 0;
          var walk = function (root) {
            var els = root.querySelectorAll('*');
            for (var q = 0; q < els.length; q++) {
              if (els[q].shadowRoot) {
                shadowInputs += els[q].shadowRoot.querySelectorAll('input[type=file]').length;
                walk(els[q].shadowRoot);
              }
            }
          };
          walk(document);
          var frames = document.querySelectorAll('iframe');
          var frameInputs = 0;
          var unreadable = 0;
          for (var f = 0; f < frames.length; f++) {
            try {
              frameInputs += frames[f].contentDocument.querySelectorAll('input[type=file]').length;
            } catch (e) {
              unreadable++;
            }
          }
          return {
            titleFound: !!title,
            fileInputs: document.querySelectorAll('input[type=file]').length,
            shadowFileInputs: shadowInputs,
            frames: frames.length,
            frameFileInputs: frameInputs,
            unreadableFrames: unreadable,
            candidates: list,
          };
        }
        """,
    ),

    /**
     * note only, a diagnostic: the visible interactive elements outside the title and the body,
     * each as `tag|role|aria|own text|class|WxH` (page chrome only, short), up to `a.max`, and how
     * many dialogs, menus, modal and popover elements the page has.
     */
    COVER_VISIBLE(
        """
        function (a) {
          var title = $TITLE_LOOKUP;
          var body = $BODY_LOOKUP;
          var cut = function (v, n) {
            var t = String(v || '').replace(/\s+/g, ' ').trim();
            return t.length > n ? t.slice(0, n) : t;
          };
          var els = document.querySelectorAll('input, label, a, li, [role=option], [role=menuitem], [role=button], button');
          var items = [];
          for (var i = 0; i < els.length && items.length < a.max; i++) {
            var el = els[i];
            var rect = el.getBoundingClientRect();
            var outside = el !== title && !(body && body.contains(el));
            if (outside && rect.width > 0 && rect.height > 0) {
              var own = '';
              var nodes = el.childNodes || [];
              for (var k = 0; k < nodes.length; k++) {
                if (nodes[k].nodeType === 3) own += nodes[k].textContent;
              }
              items.push([
                el.tagName.toLowerCase(),
                cut(el.getAttribute('role'), 20),
                cut(el.getAttribute('aria-label'), 30),
                cut(own, 20),
                cut(el.getAttribute('class'), 40),
                [Math.round(rect.width), Math.round(rect.height)].join('x'),
              ].join('|'));
            }
          }
          return {
            items: items,
            dialogs: document.querySelectorAll('[role=dialog]').length,
            menus: document.querySelectorAll('[role=menu]').length,
            modals: document.querySelectorAll('[aria-modal=true]').length,
            popovers: document.querySelectorAll('[popover]').length,
          };
        }
        """,
    ),

    /**
     * Called on the file input Chrome reported when the page asked for a file (the chooser is
     * intercepted, so none opens): the image bytes from memory become its file.
     */
    FILE_TO_INPUT(
        """
        function (a) {
          $SAME_DRAFT
          if (!this || this.tagName !== 'INPUT' || this.type !== 'file') return { ok: false };
          $IMAGE_FILE
          this.files = data.files;
          this.dispatchEvent(new Event('change', { bubbles: true }));
          return { ok: true };
        }
        """,
    ),

    /**
     * After the leading images: puts the caret in the first empty paragraph after the body's
     * last image block (or anywhere in a body without one). With `a.mode` 'select-figure' it
     * selects that image block as a whole instead, so an Enter makes a paragraph after it.
     */
    CARET_AFTER_IMAGES(
        """
        function (a) {
          $SAME_DRAFT
          var body = $BODY_LOOKUP;
          if (!body) return { ok: false, kind: 'no-body' };
          var children = body.children;
          var last = -1;
          for (var i = 0; i < children.length; i++) {
            if (children[i].tagName === 'FIGURE' || children[i].querySelector('img')) last = i;
          }
          if (a.mode === 'select-figure') {
            if (last < 0) return { ok: false, kind: 'no-image' };
            body.focus();
            var whole = document.createRange();
            whole.setStartBefore(children[last]);
            whole.setEndAfter(children[last]);
            var around = getSelection();
            around.removeAllRanges();
            around.addRange(whole);
            return { ok: true, kind: 'figure' };
          }
          for (var j = last + 1; j < children.length; j++) {
            var el = children[j];
            if (el.tagName === 'P' && el.textContent.trim() === '' && !el.querySelector('img')) {
              body.focus();
              var range = document.createRange();
              range.selectNodeContents(el);
              range.collapse(true);
              var selection = getSelection();
              selection.removeAllRanges();
              selection.addRange(range);
              return { ok: true, kind: 'empty-p' };
            }
          }
          return { ok: false, kind: last < 0 ? 'no-empty-p' : 'figure' };
        }
        """,
    ),

    /**
     * Images in the whole page that the site has stored, so a header slot counts as well as the
     * body. A local preview (`blob:`) appears the moment a file is handed over, before the upload
     * ends, so it is not counted: it, or a leaf outside the body that says an upload is running,
     * means the upload is still going. `data:` images are page decoration and count as neither.
     */
    IMAGE_COUNT(
        """
        function (a) {
          var editor = $BODY_LOOKUP;
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
              busy = text.indexOf('$UPLOADING_WORDS') >= 0 || text.toLowerCase().indexOf('uploading') >= 0;
            }
          }
          return { count: uploaded, local: local, uploading: busy || local > 0 };
        }
        """,
    ),

    /** First image route: a File on a synthetic paste. */
    PASTE_IMAGE(
        """
        function (a) {
          $SAME_DRAFT
          var body = $BODY_LOOKUP;
          if (!body) return { ok: false };
          $CARET_TO_END
          $IMAGE_FILE
          body.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
          return { ok: true };
        }
        """,
    ),

    /**
     * Second image route: a file input already in the page. The bytes are assigned from memory,
     * so Chrome never has to read a path, and no control has to be pressed to reach the input.
     */
    FILE_INPUT_IMAGE(
        """
        function (a) {
          $SAME_DRAFT
          var input = document.querySelector('input[type=file]');
          if (!input) return { ok: false };
          $IMAGE_FILE
          input.files = data.files;
          input.dispatchEvent(new Event('change', { bubbles: true }));
          return { ok: true };
        }
        """,
    ),

    /**
     * In-body image anchors: selects the text of the paragraph whose whole trimmed text is
     * `a.marker`, so a Backspace then empties it. Only when there is exactly one such paragraph,
     * or, with `a.any` (to remove markers), at least one: then the first.
     */
    ANCHOR_SELECT(
        """
        function (a) {
          $SAME_DRAFT
          var body = $BODY_LOOKUP;
          if (!body) return { ok: false, count: 0 };
          var found = [];
          var blocks = body.querySelectorAll('p');
          for (var i = 0; i < blocks.length; i++) {
            if (blocks[i].textContent.trim() === a.marker) found.push(blocks[i]);
          }
          if (found.length === 0 || (found.length > 1 && !a.any)) return { ok: false, count: found.length };
          body.focus();
          var range = document.createRange();
          range.selectNodeContents(found[0]);
          var selection = getSelection();
          selection.removeAllRanges();
          selection.addRange(range);
          return { ok: true, count: found.length };
        }
        """,
    ),

    /**
     * After a Backspace: how many marker paragraphs are left, is the caret in an empty block (no
     * text, no image), is it at the end of its block (the emptied paragraph may have been merged
     * into the one before), and are the blocks before and after plain text blocks?
     */
    ANCHOR_CHECK(
        """
        function (a) {
          var body = $BODY_LOOKUP;
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
        """,
    ),

    /** An image as a File on a synthetic paste, at the caret, which must be in the body. */
    PASTE_IMAGE_HERE(
        """
        function (a) {
          $SAME_DRAFT
          var body = $BODY_LOOKUP;
          if (!body) return { ok: false };
          var node = getSelection().anchorNode;
          if (!node || !body.contains(node)) return { ok: false };
          $IMAGE_FILE
          body.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
          return { ok: true };
        }
        """,
    ),

    /**
     * Do the title and every sample appear in the live editor of this draft, and did roughly the
     * structure arrive: at least half as many paragraphs, headings and list items as the source?
     * No image anchor text may be left in it.
     */
    READ_BACK(
        """
        function (a) {
          $SAME_DRAFT
          var norm = function (s) { return s.replace(/\s+/g, ' ').trim(); };
          var title = $TITLE_LOOKUP;
          var body = $BODY_LOOKUP;
          var text = body ? norm(body.textContent) : '';
          var blocks = body ? body.querySelectorAll('p, h1, h2, h3, h4, h5, h6, li').length : 0;
          var want = a.blocks || 0;
          var markersLeft = text.indexOf('$ANCHOR_PREFIX') >= 0;
          return {
            titleOk: !!title && title.value === a.title,
            bodyOk: a.samples.every(function (s) { return text.indexOf(s) >= 0; }) && (want <= 1 || blocks * 2 >= want) && !markersLeft,
            blocks: blocks,
            markersLeft: markersLeft,
          };
        }
        """,
    ),

    /** note only: the single allowed click. Exact label, exactly one match, or nothing happens. */
    NOTE_SAVE_CLICK(
        """
        function (a) {
          $SAME_DRAFT
          var matches = [];
          var buttons = document.querySelectorAll('button');
          for (var i = 0; i < buttons.length; i++) {
            if (buttons[i].textContent.trim() === '$NOTE_SAVE_LABEL') matches.push(buttons[i]);
          }
          if (matches.length !== 1 || matches[0].disabled) return { ok: false, matches: matches.length };
          var at = performance.now();
          matches[0].click();
          return { ok: true, matches: 1, at: at };
        }
        """,
    ),

    /**
     * note only. Saved means all of: the saved words outside the editor, a 2xx answer after the
     * click from a note API on the same site, and no stored copy that disagrees. An image or the
     * article's own text cannot supply either signal: the first ignores the editor and a title
     * that holds the words, the second accepts only fetch or XHR and needs a real status.
     */
    NOTE_SAVED_PROBE(
        """
        function (a) {
          $SAME_DRAFT
          var norm = function (s) { return s.replace(/\s+/g, ' ').trim(); };
          var words = '$NOTE_SAVED_WORDS';
          var editor = $BODY_LOOKUP;
          var outside = false;
          if (a.title.indexOf(words) < 0) {
            var all = document.body.querySelectorAll('*');
            for (var i = 0; i < all.length && !outside; i++) {
              var el = all[i];
              var leaf = el.children.length === 0 && el.tagName !== 'TEXTAREA';
              if (leaf && !(editor && editor.contains(el))) outside = el.textContent.indexOf(words) >= 0;
            }
          }
          var api = performance.getEntriesByType('resource').some(function (entry) {
            if (entry.startTime < a.at) return false;
            if (entry.initiatorType !== 'fetch' && entry.initiatorType !== 'xmlhttprequest') return false;
            if (!(entry.responseStatus >= 200 && entry.responseStatus < 300)) return false;
            var url = new URL(entry.name);
            var host = url.hostname === 'note.com' || url.hostname === 'editor.note.com';
            return host && url.pathname.indexOf('/api/') === 0 && url.pathname.indexOf('draft_save') >= 0;
          });
          var evidence = 'outside=' + outside + ' api=' + api;
          if (!outside || !api) return { ok: true, saved: false, evidence: evidence };
          return fetch('https://note.com/api/v3/notes/' + encodeURIComponent(a.key) + '?draft=true', { credentials: 'include' })
            .then(function (response) {
              if (!response.ok) return { ok: true, saved: true, evidence: evidence + ' stored=unread-' + response.status };
              return response.json().then(function (json) {
                var note = json.data || {};
                var html = note.body || '';
                var text = norm(new DOMParser().parseFromString(html, 'text/html').body.textContent);
                var same = note.name === a.title && a.samples.every(function (s) { return text.indexOf(s) >= 0; });
                return { ok: true, saved: same, evidence: evidence + ' stored=' + (same ? 'same' : 'different') };
              });
            })
            .catch(function () { return { ok: true, saved: true, evidence: evidence + ' stored=unread' }; });
        }
        """,
    ),

    /** Substack only: a read of the stored draft. It uses fetch defaults, so it changes nothing. */
    SUBSTACK_SAVED_PROBE(
        """
        function (a) {
          $SAME_DRAFT
          var norm = function (s) { return s.replace(/\s+/g, ' ').trim(); };
          var collect = function (node) {
            if (!node || typeof node !== 'object') return '';
            return (node.text || '') + (node.content || []).map(collect).join('');
          };
          var storedText = function (raw) {
            try { return norm(collect(JSON.parse(raw))); } catch (e) { return norm(String(raw)); }
          };
          return fetch('/api/v1/drafts/' + encodeURIComponent(a.key), { credentials: 'same-origin' })
            .then(function (response) {
              if (!response.ok) return { ok: true, saved: false, evidence: 'http=' + response.status };
              return response.json().then(function (draft) {
                var text = storedText(draft.draft_body);
                return {
                  ok: true,
                  saved: draft.draft_title === a.title && a.samples.every(function (s) { return text.indexOf(s) >= 0; }),
                  evidence: 'http=' + response.status,
                };
              });
            })
            .catch(function () { return { ok: true, saved: false, evidence: 'http=0' }; });
        }
        """,
    );

    /** The full expression: the fixed function applied to one JSON-encoded argument. */
    fun expression(args: JsonObject): String = "(${source.trim()})($args)"
}
