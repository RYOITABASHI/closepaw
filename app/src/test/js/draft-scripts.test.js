// Runs the pinned draft scripts under a small fake DOM. DraftScriptsNodeTest starts this file;
// SaveDraftScriptsTest proves the pinned text equals the shipped text.
// It checks the scripts' own logic, not how a real editor reacts.
'use strict';
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const vm = require('vm');

const dir = process.argv[2];
const scripts = {};
for (const file of fs.readdirSync(dir)) {
  scripts[path.basename(file, '.js')] = fs.readFileSync(path.join(dir, file), 'utf8').trim();
}
const EDITOR = 'https://editor.note.com/notes/nabc/edit/';

class El {
  constructor(tag, props = {}) {
    this.tagName = tag.toUpperCase();
    this.children = [];
    this.listeners = {};
    this.textContent = '';
    Object.assign(this, props);
  }
  focus() {}
  addEventListener(t, f) { (this.listeners[t] = this.listeners[t] || []).push(f); }
  dispatchEvent(e) { (this.listeners[e.type] || []).forEach((f) => f(e)); return !e.defaultPrevented; }
  click() { this.clicks = (this.clicks || 0) + 1; }
  contains(other) { return other === this || this.children.includes(other); }
  closest() { return this.outerLink || null; }
  querySelectorAll(sel) {
    if (sel === 'a[href]') return this.links || [];
    throw new Error('selector ' + sel);
  }
}
class FakeEvent {
  constructor(type, init) { this.type = type; Object.assign(this, init); }
  preventDefault() { this.defaultPrevented = true; }
}

function page(options = {}) {
  const {
    href = EDITOR, buttons = [], withTextarea = true, withBody = true, fileInput = null, images = 0,
    resources = [], items = [], outside = [], bodyChildren = [], fetchImpl,
    hitAt = null, active = null, pageFocused = false, execResult = true,
  } = options;
  const textarea = new El('textarea');
  let value = '';
  const descriptor = { configurable: true, get() { return value; }, set(v) { value = v; } };
  Object.defineProperty(textarea, 'value', descriptor);
  const body = new El('div');
  body.children = bodyChildren;
  body.lastElementChild = bodyChildren[bodyChildren.length - 1] || null;
  const execCalls = [];
  const root = {
    querySelectorAll(sel) {
      if (sel === '*') return [textarea, body, ...bodyChildren, ...outside];
      throw new Error('selector ' + sel);
    },
  };
  const document = {
    body: root,
    activeElement: active,
    hasFocus: () => pageFocused,
    elementFromPoint: (x, y) => (hitAt ? hitAt(x, y) : null),
    execCommand: (...args) => { execCalls.push(args); return execResult; },
    querySelector(sel) {
      if (sel === 'textarea') return withTextarea ? textarea : null;
      if (sel === 'input[type=file]') return fileInput;
      if (sel.includes('contenteditable')) return withBody ? body : null;
      throw new Error('selector ' + sel);
    },
    querySelectorAll(sel) {
      if (sel === 'button') return buttons;
      if (sel === 'img') return (Array.isArray(images) ? images : new Array(images).fill('https://x/i.png')).map((src) => ({ src }));
      if (sel.startsWith('li,')) return items;
      throw new Error('selector ' + sel);
    },
  };
  class DT {
    constructor() { this.store = {}; this.list = []; this.items = { add: (f) => this.list.push(f) }; }
    setData(t, v) { this.store[t] = v; }
    get files() { return this.list; }
  }
  class FakeFile { constructor(parts, name, opt) { this.parts = parts; this.name = name; this.type = opt.type; } }
  class FakeDOMParser {
    parseFromString(html) { return { body: { textContent: html.replace(/<[^>]*>/g, '') } }; }
  }
  const selection = { selectAllChildren(n) { this.node = n; }, collapseToEnd() { this.end = true; } };
  const sandbox = {
    document, location: { href }, getSelection: () => selection, Math,
    DataTransfer: DT, ClipboardEvent: FakeEvent, Event: FakeEvent, File: FakeFile, DOMParser: FakeDOMParser, URL,
    atob: (s) => Buffer.from(s, 'base64').toString('binary'),
    HTMLTextAreaElement: { prototype: {} },
    performance: { now: () => 5, getEntriesByType: () => resources },
    fetch: fetchImpl || (() => Promise.reject(new Error('no fetch expected'))),
  };
  Object.defineProperty(sandbox.HTMLTextAreaElement.prototype, 'value', descriptor);
  return { sandbox, textarea, body, selection, execCalls };
}
const run = (name, sandbox, args = {}) => {
  const out = vm.runInNewContext(`(${scripts[name]})(${JSON.stringify(args)})`, sandbox);
  return out && typeof out.then === 'function' ? out.then((x) => JSON.parse(JSON.stringify(x))) : JSON.parse(JSON.stringify(out));
};
const same = { editorUrl: EDITOR };
const image = { ...same, name: 'a.jpg', mime: 'image/jpeg', base64: Buffer.from([255, 0, 10]).toString('base64') };
const WRITERS = ['SET_TITLE', 'PASTE_HTML', 'INSERT_HTML', 'FOCUS_TARGET', 'PASTE_IMAGE', 'FILE_INPUT_IMAGE', 'NOTE_SAVE_CLICK', 'READ_BACK', 'NOTE_SAVED_PROBE', 'SUBSTACK_SAVED_PROBE'];
const toast = (text = '下書きを保存しました') => new El('div', { textContent: text });
const apiEntry = (over = {}) => ({
  startTime: 6, initiatorType: 'fetch', responseStatus: 200,
  name: 'https://note.com/api/v1/text_notes/draft_save?id=1', ...over,
});
const savedArgs = { ...same, at: 1, key: 'nabc', title: 'T', samples: ['Body text'] };
const stored = (name, body) => () => Promise.resolve({
  ok: true, status: 200, json: () => Promise.resolve({ data: { name, body } }),
});
const noRead = () => Promise.reject(new Error('blocked'));

const tests = {
  'every script parses': () => {
    assert.strictEqual(Object.keys(scripts).length, 14);
    for (const [name, src] of Object.entries(scripts)) new vm.Script(`(${src})({})`, { filename: name });
  },

  'every page-changing script refuses another URL': async () => {
    for (const name of WRITERS) {
      const button = new El('button', { textContent: '下書き保存', disabled: false });
      const input = new El('input');
      const p = page({ href: 'https://editor.note.com/notes/other/edit/', buttons: [button], fileInput: input });
      let pasted = 0;
      p.body.addEventListener('paste', () => pasted++);
      const out = await run(name, p.sandbox, { ...savedArgs, html: '<p>x</p>', base64: 'AAAA', name: 'a', mime: 'image/png' });
      assert.deepStrictEqual(out, { ok: false, moved: true }, name);
      assert.strictEqual(p.textarea.value, '', name);
      assert.strictEqual(pasted, 0, name);
      assert.strictEqual(button.clicks, undefined, name);
      assert.strictEqual(input.files, undefined, name);
    }
  },

  'list probe keeps rows with links and marks only the exact title': () => {
    const row = (lines, hrefs, outerLink) => new El('li', {
      innerText: lines, links: hrefs.map((href) => ({ href })), outerLink,
    });
    const items = [
      row('A title\nyesterday', ['https://x/1']),
      row('A title and more', ['https://x/2']),
      row('A title', []),
      row('A  title', [], { href: 'https://x/3' }),
    ];
    const out = run('LIST_PROBE', page({ items }).sandbox, { title: 'A title' });
    assert.deepStrictEqual(out.rows, [
      { hrefs: ['https://x/1'], match: true },
      { hrefs: ['https://x/2'], match: false },
      { hrefs: ['https://x/3'], match: true },
    ]);
  },

  'a page with the title outside any list row reports no rows': () => {
    assert.deepStrictEqual(run('LIST_PROBE', page({ items: [] }).sandbox, { title: 'A title' }).rows, []);
  },

  'editor probe reports emptiness': () => {
    const p = page();
    assert.strictEqual(run('EDITOR_PROBE', p.sandbox).bodyEmpty, true);
    p.body.textContent = 'x';
    assert.strictEqual(run('EDITOR_PROBE', p.sandbox).bodyEmpty, false);
  },

  'image count holds only stored images, and a local preview means still uploading': () => {
    const count = (options) => run('IMAGE_COUNT', page(options).sandbox);
    assert.deepStrictEqual(count({ images: [] }), { count: 0, local: 0, uploading: false });
    assert.deepStrictEqual(count({ images: ['blob:https://editor.note.com/1'] }), { count: 0, local: 1, uploading: true });
    assert.deepStrictEqual(count({ images: ['https://assets.st-note.com/a.jpg'] }), { count: 1, local: 0, uploading: false });
    assert.deepStrictEqual(count({ images: ['data:image/png;base64,AAAA', ''] }), { count: 0, local: 0, uploading: false });
    assert.deepStrictEqual(
      count({ images: ['https://assets.st-note.com/a.jpg', 'blob:https://editor.note.com/2'] }),
      { count: 1, local: 1, uploading: true },
    );
  },

  'an upload notice outside the body is uploading; the same words in the body are not': () => {
    const stored = ['https://assets.st-note.com/a.jpg'];
    const outside = run('IMAGE_COUNT', page({ images: stored, outside: [new El('span', { textContent: '画像アップロード中です' })] }).sandbox);
    assert.deepStrictEqual(outside, { count: 1, local: 0, uploading: true });
    const english = run('IMAGE_COUNT', page({ images: stored, outside: [new El('span', { textContent: 'Uploading image…' })] }).sandbox);
    assert.strictEqual(english.uploading, true);
    const inBody = run('IMAGE_COUNT', page({ images: stored, bodyChildren: [new El('p', { textContent: 'アップロード中' })] }).sandbox);
    assert.strictEqual(inBody.uploading, false);
  },

  'paste reports whether the editor took it and the body length after': () => {
    const p = page();
    p.body.addEventListener('paste', (e) => { e.preventDefault(); p.body.textContent = 'Body'; });
    assert.deepStrictEqual(run('PASTE_HTML', p.sandbox, { ...same, html: '<p>Body</p>' }), { ok: true, accepted: true, chars: 4 });
    const ignored = page();
    assert.deepStrictEqual(run('PASTE_HTML', ignored.sandbox, { ...same, html: '<p>Body</p>' }), { ok: true, accepted: false, chars: 0 });
  },

  'insert html uses the editing command, and only into an empty body': () => {
    const p = page();
    const out = run('INSERT_HTML', p.sandbox, { ...same, html: '<h2>H</h2><p>x</p>' });
    assert.deepStrictEqual(out, { ok: true, accepted: true, chars: 0 });
    assert.deepStrictEqual(p.execCalls, [['insertHTML', false, '<h2>H</h2><p>x</p>']]);
    assert.strictEqual(p.selection.end, true);
    const full = page();
    full.body.textContent = 'already';
    assert.deepStrictEqual(run('INSERT_HTML', full.sandbox, { ...same, html: '<p>x</p>' }), { ok: false, notEmpty: true });
    assert.deepStrictEqual(full.execCalls, []);
  },

  'focus target points into the last plain block, and nowhere else': () => {
    const block = (tag, props = {}) => {
      const el = new El(tag, props);
      el.scrollIntoView = () => { el.scrolled = true; };
      el.getBoundingClientRect = () => ({ left: 10, top: 100, width: 300, height: 20 });
      el.querySelector = () => props.inner || null;
      return el;
    };
    const para = block('p');
    const p = page({ bodyChildren: [new El('figure'), para], hitAt: () => para });
    assert.deepStrictEqual(run('FOCUS_TARGET', p.sandbox, same), { ok: true, x: 18, y: 110 });
    assert.strictEqual(para.scrolled, true);

    const fig = block('figure');
    assert.strictEqual(run('FOCUS_TARGET', page({ bodyChildren: [fig], hitAt: () => fig }).sandbox, same).why, 'no-block');
    const withLink = block('p', { inner: new El('a') });
    assert.strictEqual(run('FOCUS_TARGET', page({ bodyChildren: [withLink], hitAt: () => withLink }).sandbox, same).why, 'busy-block');
    const covered = block('p');
    assert.strictEqual(run('FOCUS_TARGET', page({ bodyChildren: [covered], hitAt: () => new El('div') }).sandbox, same).why, 'covered');
    assert.strictEqual(run('FOCUS_TARGET', page({ bodyChildren: [] }).sandbox, same).why, 'no-block');
  },

  'focus probe says whether the focus is in the body': () => {
    const inside = new El('p');
    const p = page({ bodyChildren: [inside], active: inside, pageFocused: true });
    assert.deepStrictEqual(run('FOCUS_PROBE', p.sandbox), { href: EDITOR, focused: true, pageFocused: true });
    assert.strictEqual(run('FOCUS_PROBE', page({ active: new El('textarea') }).sandbox).focused, false);
  },

  'title uses the native setter and an input event': () => {
    const p = page();
    let inputs = 0;
    p.textarea.addEventListener('input', () => inputs++);
    assert.strictEqual(run('SET_TITLE', p.sandbox, { ...same, title: 'T "q" 日本語' }).ok, true);
    assert.strictEqual(p.textarea.value, 'T "q" 日本語');
    assert.strictEqual(inputs, 1);
  },

  'paste carries text/html with the caret at the end': () => {
    const p = page();
    let event;
    p.body.addEventListener('paste', (e) => { event = e; });
    assert.strictEqual(run('PASTE_HTML', p.sandbox, { ...same, html: '<p>x</p>' }).ok, true);
    assert.strictEqual(event.clipboardData.store['text/html'], '<p>x</p>');
    assert.strictEqual(p.selection.end, true);
  },

  'image routes deliver the bytes': () => {
    let p = page();
    let event;
    p.body.addEventListener('paste', (e) => { event = e; });
    assert.strictEqual(run('PASTE_IMAGE', p.sandbox, image).ok, true);
    assert.deepStrictEqual([...event.clipboardData.files[0].parts[0]], [255, 0, 10]);

    const input = new El('input');
    let changes = 0;
    input.addEventListener('change', () => changes++);
    p = page({ fileInput: input });
    assert.strictEqual(run('FILE_INPUT_IMAGE', p.sandbox, image).ok, true);
    assert.strictEqual(input.files[0].name, 'a.jpg');
    assert.strictEqual(changes, 1);
    assert.strictEqual(run('FILE_INPUT_IMAGE', page().sandbox, image).ok, false);
  },

  'read back compares normalized text': () => {
    const p = page();
    p.textarea.value = 'T';
    p.body.textContent = 'one   two\nthree';
    assert.deepStrictEqual(run('READ_BACK', p.sandbox, { ...same, title: 'T', samples: ['one two three'] }), { titleOk: true, bodyOk: true });
    assert.strictEqual(run('READ_BACK', p.sandbox, { ...same, title: 'T', samples: ['absent'] }).bodyOk, false);
  },

  'the click needs the exact label, one match, and an enabled button': () => {
    const label = '下書き保存';
    const save = new El('button', { textContent: ` ${label} `, disabled: false });
    const other = new El('button', { textContent: '公開に進む', disabled: false });
    assert.deepStrictEqual(run('NOTE_SAVE_CLICK', page({ buttons: [other, save] }).sandbox, same), { ok: true, matches: 1, at: 5 });
    assert.strictEqual(save.clicks, 1);
    assert.strictEqual(other.clicks, undefined);

    const pair = [new El('button', { textContent: label }), new El('button', { textContent: label })];
    assert.strictEqual(run('NOTE_SAVE_CLICK', page({ buttons: pair }).sandbox, same).ok, false);
    assert.strictEqual(pair[0].clicks, undefined);

    const off = new El('button', { textContent: label, disabled: true });
    assert.strictEqual(run('NOTE_SAVE_CLICK', page({ buttons: [off] }).sandbox, same).ok, false);
    assert.strictEqual(off.clicks, undefined);

    const near = new El('button', { textContent: `${label}して公開` });
    assert.strictEqual(run('NOTE_SAVE_CLICK', page({ buttons: [near] }).sandbox, same).matches, 0);
    assert.strictEqual(near.clicks, undefined);
  },

  'a real save needs a toast outside the editor and a 2xx API answer': async () => {
    const out = await run('NOTE_SAVED_PROBE', page({
      outside: [toast()], resources: [apiEntry()], fetchImpl: stored('T', '<p>Body text here</p>'),
    }).sandbox, savedArgs);
    assert.strictEqual(out.saved, true);
    assert.strictEqual(out.evidence, 'outside=true api=true stored=same');
  },

  'the saved words inside the article do not count': async () => {
    const inEditor = new El('p', { textContent: '下書きを保存しました' });
    const out = await run('NOTE_SAVED_PROBE', page({ bodyChildren: [inEditor], resources: [apiEntry()] }).sandbox, savedArgs);
    assert.strictEqual(out.saved, false);
    assert.strictEqual(out.evidence, 'outside=false api=true');
  },

  'a title holding the saved words disables the toast signal': async () => {
    const out = await run('NOTE_SAVED_PROBE', page({ outside: [toast()], resources: [apiEntry()] }).sandbox, {
      ...savedArgs, title: '保存しました',
    });
    assert.strictEqual(out.saved, false);
  },

  'no API answer means not saved': async () => {
    assert.strictEqual((await run('NOTE_SAVED_PROBE', page({ outside: [toast()] }).sandbox, savedArgs)).saved, false);
  },

  'a failed or fake save request is not a save': async () => {
    const fakes = [
      apiEntry({ responseStatus: 500 }),
      apiEntry({ responseStatus: 302 }),
      apiEntry({ responseStatus: 0 }),
      apiEntry({ responseStatus: undefined }),
      apiEntry({ initiatorType: 'img' }),
      apiEntry({ initiatorType: 'img', name: 'https://note.com/x/draft_save.png' }),
      apiEntry({ name: 'https://evil.test/api/draft_save' }),
      apiEntry({ name: 'https://note.com.evil.test/api/draft_save' }),
      apiEntry({ name: 'https://note.com/static/draft_save.js' }),
      apiEntry({ startTime: 0 }),
    ];
    for (const entry of fakes) {
      const out = await run('NOTE_SAVED_PROBE', page({ outside: [toast()], resources: [entry] }).sandbox, savedArgs);
      assert.strictEqual(out.saved, false, JSON.stringify(entry));
    }
  },

  'a stored copy that differs vetoes the save': async () => {
    const out = await run('NOTE_SAVED_PROBE', page({
      outside: [toast()], resources: [apiEntry()], fetchImpl: stored('T', '<p>other</p>'),
    }).sandbox, savedArgs);
    assert.strictEqual(out.saved, false);
    assert.strictEqual(out.evidence, 'outside=true api=true stored=different');
  },

  'an unreadable stored copy leaves the two signals standing, and says so': async () => {
    const out = await run('NOTE_SAVED_PROBE', page({ outside: [toast()], resources: [apiEntry()], fetchImpl: noRead }).sandbox, savedArgs);
    assert.strictEqual(out.saved, true);
    assert.strictEqual(out.evidence, 'outside=true api=true stored=unread');
  },

  'substack reads the stored draft': async () => {
    const doc = JSON.stringify({ type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Body ' }, { type: 'text', text: 'text' }] }] });
    const ok = (json) => () => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(json) });
    const probe = (fetchImpl) => run('SUBSTACK_SAVED_PROBE', page({ fetchImpl }).sandbox, savedArgs);
    assert.deepStrictEqual(await probe(ok({ draft_title: 'T', draft_body: doc })), { ok: true, saved: true, evidence: 'http=200' });
    assert.strictEqual((await probe(ok({ draft_title: 'U', draft_body: doc }))).saved, false);
    assert.strictEqual((await probe(ok({ draft_title: 'T', draft_body: 'null' }))).saved, false);
    assert.strictEqual((await probe(() => Promise.resolve({ ok: false, status: 404 }))).evidence, 'http=404');
    assert.strictEqual((await probe(noRead)).evidence, 'http=0');
  },
};

(async () => {
  let failed = 0;
  for (const [name, test] of Object.entries(tests)) {
    try {
      await test();
    } catch (e) {
      failed++;
      console.error(`FAIL ${name}: ${e.stack}`);
    }
  }
  console.log(`${Object.keys(tests).length - failed}/${Object.keys(tests).length} script tests passed`);
  process.exit(failed ? 1 : 0);
})();
