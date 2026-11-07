package ai.closepaw.draft

import kotlinx.serialization.json.JsonObject

private const val BODY_LOOKUP =
    "document.querySelector('.ProseMirror[contenteditable=true]') || document.querySelector('[contenteditable=true]')"

private const val CARET_TO_END =
    "body.focus(); var selection = getSelection(); selection.selectAllChildren(body); selection.collapseToEnd();"

private const val IMAGE_FILE =
    "var raw = atob(a.base64); var bytes = new Uint8Array(raw.length); " +
        "for (var i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i); " +
        "var data = new DataTransfer(); data.items.add(new File([bytes], a.name, { type: a.mime }));"

/** The one control the routine may press, on note only. */
internal const val NOTE_SAVE_LABEL = "下書き保存"

/**
 * Every JavaScript string the routine can run in the editor tab. [DraftBrowser.evaluate] takes
 * this enum, so no expression can be built from request data: values travel only in the JSON
 * argument of [expression].
 *
 * SaveDraftScriptsTest scans this file. It must never name any other control, and comments count.
 */
enum class DraftScript(val source: String) {
    /** Is the exact title on a line of the draft list? */
    LIST_PROBE(
        """
        function (a) {
          var norm = function (s) { return s.replace(/\s+/g, ' ').trim(); };
          var want = norm(a.title);
          var lines = (document.body ? document.body.innerText : '').split('\n').map(norm);
          var found = lines.indexOf(want) >= 0;
          var url = null;
          if (found) {
            var links = document.querySelectorAll('a[href]');
            for (var i = 0; i < links.length; i++) {
              if (norm(links[i].textContent).indexOf(want) >= 0) { url = links[i].href; break; }
            }
          }
          return { href: location.href, ready: document.readyState === 'complete', found: found, url: url };
        }
        """,
    ),

    /** Where are we, and are the title field and the body there and still empty? */
    EDITOR_PROBE(
        """
        function (a) {
          var title = document.querySelector('textarea');
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
          var title = document.querySelector('textarea');
          if (!title) return { ok: false };
          var setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
          title.focus();
          setter.call(title, a.title);
          title.dispatchEvent(new Event('input', { bubbles: true }));
          return { ok: title.value === a.title };
        }
        """,
    ),

    /** Synthetic paste of the validated HTML at the end of the body. */
    PASTE_HTML(
        """
        function (a) {
          var body = $BODY_LOOKUP;
          if (!body) return { ok: false };
          $CARET_TO_END
          var data = new DataTransfer();
          data.setData('text/html', a.html);
          body.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
          return { ok: true };
        }
        """,
    ),

    /** Number of images in the whole page, so a header slot counts as well as the body. */
    IMAGE_COUNT(
        """
        function (a) {
          return { count: document.querySelectorAll('img').length };
        }
        """,
    ),

    /** First image route: a File on a synthetic paste. */
    PASTE_IMAGE(
        """
        function (a) {
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
          var input = document.querySelector('input[type=file]');
          if (!input) return { ok: false };
          $IMAGE_FILE
          input.files = data.files;
          input.dispatchEvent(new Event('change', { bubbles: true }));
          return { ok: true };
        }
        """,
    ),

    /** Do the title and every sample appear in the live editor? */
    READ_BACK(
        """
        function (a) {
          var norm = function (s) { return s.replace(/\s+/g, ' ').trim(); };
          var title = document.querySelector('textarea');
          var body = $BODY_LOOKUP;
          var text = body ? norm(body.textContent) : '';
          return {
            href: location.href,
            titleOk: !!title && title.value === a.title,
            bodyOk: a.samples.every(function (s) { return text.indexOf(s) >= 0; }),
          };
        }
        """,
    ),

    /** note only: the single allowed click. Exact label, exactly one match, or nothing happens. */
    NOTE_SAVE_CLICK(
        """
        function (a) {
          var matches = [];
          var buttons = document.querySelectorAll('button');
          for (var i = 0; i < buttons.length; i++) {
            if (buttons[i].textContent.trim() === '$NOTE_SAVE_LABEL') matches.push(buttons[i]);
          }
          if (matches.length !== 1 || matches[0].disabled) return { clicked: false, matches: matches.length };
          var at = performance.now();
          matches[0].click();
          return { clicked: true, matches: 1, at: at };
        }
        """,
    ),

    /** note only: a saved toast, or a finished save request after the click. */
    NOTE_SAVED_PROBE(
        """
        function (a) {
          var toast = /保存しました/.test(document.body ? document.body.innerText : '');
          var request = performance.getEntriesByType('resource').some(function (entry) {
            return entry.startTime >= a.at && entry.responseEnd > 0 && entry.name.indexOf('draft_save') >= 0;
          });
          return { saved: toast || request };
        }
        """,
    ),

    /** Substack only: a read of the stored draft. It uses fetch defaults, so it changes nothing. */
    SUBSTACK_SAVED_PROBE(
        """
        function (a) {
          var norm = function (s) { return s.replace(/\s+/g, ' ').trim(); };
          var collect = function (node) {
            if (!node || typeof node !== 'object') return '';
            return (node.text || '') + (node.content || []).map(collect).join('');
          };
          var storedText = function (raw) {
            try { return norm(collect(JSON.parse(raw))); } catch (e) { return norm(String(raw)); }
          };
          return fetch('/api/v1/drafts/' + encodeURIComponent(a.id), { credentials: 'same-origin' })
            .then(function (response) {
              if (!response.ok) return { saved: false, http: response.status };
              return response.json().then(function (draft) {
                var text = storedText(draft.draft_body);
                return {
                  saved: draft.draft_title === a.title && a.samples.every(function (s) { return text.indexOf(s) >= 0; }),
                  http: response.status,
                };
              });
            })
            .catch(function () { return { saved: false, http: 0 }; });
        }
        """,
    );

    /** The full expression: the fixed function applied to one JSON-encoded argument. */
    fun expression(args: JsonObject): String = "(${source.trim()})($args)"
}
