/* A minimal A2UI v0.9.1 renderer for Luna (see docs/protocol.md, "Widgets"). No dependencies.
 *
 * Two parts: `Store` holds surfaces and applies protocol messages (pure, tested in Node: web/test/a2ui.test.js), and
 * `Renderer` draws a Store with the DOM. Everything the agent sends is rendered with textContent / checked URLs, never as HTML.
 *
 * Supported: the basic catalog's Text, Image, Icon, Row, Column, List, Card, Tabs, Divider, Modal, Button, CheckBox, TextField,
 * DateTimeInput, ChoicePicker, Slider. Values are literals or {"path": "/pointer"} bindings; inputs write back to the data model.
 * NOT supported yet: List templates (children as {path, componentId}), function calls inside values (formatString etc.), Video,
 * AudioPlayer. Unknown pieces render as nothing rather than failing the surface. */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.A2UI = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';
  const VERSION = 'v0.9.1';
  const BASIC = 'https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json';
  const KNOWN = [BASIC];

  // ---- JSON pointer helpers --------------------------------------------------------------
  const parts = p => (p === '' || p === '/' || p == null) ? [] : String(p).split('/').slice(1).map(s => s.replace(/~1/g, '/').replace(/~0/g, '~'));
  function getPath(model, path) {
    let cur = model;
    for (const k of parts(path)) { if (cur == null || typeof cur !== 'object') return undefined; cur = cur[k]; }
    return cur;
  }
  function setPath(model, path, value) {            // returns the (possibly new) model; value undefined removes the key
    const ks = parts(path);
    if (!ks.length) return value === undefined ? {} : value;
    let cur = model;
    for (const k of ks.slice(0, -1)) {
      if (cur[k] == null || typeof cur[k] !== 'object') cur[k] = {};
      cur = cur[k];
    }
    const last = ks[ks.length - 1];
    if (value === undefined) delete cur[last]; else cur[last] = value;
    return model;
  }

  // ---- the store: surfaces and protocol messages -----------------------------------------
  class Store {
    constructor(onChange, onError) { this.surfaces = new Map(); this.onChange = onChange || (() => {}); this.onError = onError || (() => {}); }

    fail(msg, surfaceId, path) { this.onError({ code: 'VALIDATION_FAILED', surfaceId: surfaceId || '', path: path || '/', message: msg }); }

    processText(text) { for (const line of String(text).split('\n')) if (line.trim()) this.processLine(line); }

    processLine(line) {
      let msg;
      try { msg = JSON.parse(line); } catch (e) { return this.fail('not JSON'); }
      this.process(msg);
    }

    process(msg) {                                  // one bad message is reported and skipped; the rest still apply
      if (!msg || typeof msg !== 'object' || msg.version !== VERSION) return this.fail('unsupported or missing version');
      const keys = ['createSurface', 'updateComponents', 'updateDataModel', 'deleteSurface'].filter(k => k in msg);
      if (keys.length !== 1) return this.fail('a message needs exactly one of createSurface, updateComponents, updateDataModel, deleteSurface');
      const body = msg[keys[0]] || {};
      const id = body.surfaceId;
      if (typeof id !== 'string' || !id) return this.fail('surfaceId is required');
      const s = this.surfaces.get(id);
      switch (keys[0]) {
        case 'createSurface':
          if (s) return this.fail('surface already exists', id);
          if (!KNOWN.includes(body.catalogId)) return this.fail('unknown catalogId', id);
          this.surfaces.set(id, { id, catalogId: body.catalogId, theme: body.theme || {}, components: new Map(), model: {} });
          break;
        case 'updateComponents':
          if (!s) return this.fail('no such surface', id);
          if (!Array.isArray(body.components)) return this.fail('components must be an array', id, '/components');
          for (const c of body.components) if (c && typeof c.id === 'string' && typeof c.component === 'string') s.components.set(c.id, c);
          break;
        case 'updateDataModel':
          if (!s) return this.fail('no such surface', id);
          s.model = setPath(s.model, body.path, body.value);
          break;
        case 'deleteSurface':
          if (!s) return this.fail('no such surface', id);
          this.surfaces.delete(id);
          break;
      }
      this.onChange(id);
    }

    clear() { const ids = [...this.surfaces.keys()]; this.surfaces.clear(); ids.forEach(i => this.onChange(i)); }
    ready(id) { const s = this.surfaces.get(id); return !!(s && s.components.has('root')); }   // nothing draws until root exists
  }

  // ---- the renderer ----------------------------------------------------------------------
  const ICONS = { check: '✔', close: '✖', phone: '☎', call: '☎', home: '⌂', warning: '⚠', info: 'ℹ', star: '★', favorite: '♥',
                  search: '⌕', settings: '⚙', mail: '✉', send: '➤', play: '▶', pause: '❚❚', add: '+', remove: '−', notifications: '🔔' };
  const httpUrl = u => typeof u === 'string' && (u.startsWith('https://') || u.startsWith('http://'));
  const imageUrl = u => httpUrl(u) || (typeof u === 'string' && u.startsWith('data:image/'));

  class Renderer {
    constructor(container, store, { onAction, openUrl } = {}) {
      this.container = container; this.store = store;
      this.onAction = onAction || (() => {});
      this.openUrl = openUrl || (u => window.open(u, '_blank', 'noopener,noreferrer'));
      this.nodes = new Map();
      store.onChange = id => this.draw(id);
    }

    draw(id) {
      const old = this.nodes.get(id);
      const s = this.store.surfaces.get(id);
      if (!s || !this.store.ready(id)) { if (old && !s) { old.remove(); this.nodes.delete(id); } return; }
      const el = document.createElement('section');
      el.className = 'a2ui-surface'; el.dataset.surface = id;
      el.appendChild(this.build(s, 'root', new Set()));
      if (old) { old.replaceWith(el); } else { this.container.appendChild(el); }
      this.nodes.set(id, el);
    }

    val(s, v) {                                      // a literal or a {"path": ...} binding
      if (v && typeof v === 'object' && !Array.isArray(v)) return 'path' in v ? getPath(s.model, v.path) : undefined;
      return v;
    }
    bind(s, v, value) { if (v && typeof v === 'object' && 'path' in v) setPath(s.model, v.path, value); }

    dispatch(s, c, action) {
      if (!action) return;
      if (action.event) {
        const context = {};
        for (const [k, v] of Object.entries(action.event.context || {})) context[k] = this.val(s, v);
        this.onAction({ version: VERSION, action: { name: String(action.event.name), surfaceId: s.id, sourceComponentId: c.id,
                                                    timestamp: new Date().toISOString(), context } });
      } else if (action.functionCall && action.functionCall.call === 'openUrl') {
        const u = action.functionCall.args && action.functionCall.args.url;
        if (httpUrl(u)) this.openUrl(u);
      }
    }

    kids(s, c, seen) {
      const list = Array.isArray(c.children) ? c.children : (c.child ? [c.child] : []);
      return list.filter(x => typeof x === 'string').map(id => this.build(s, id, seen));
    }

    build(s, id, seen) {
      const c = s.components.get(id);
      const empty = () => document.createComment('missing ' + id);
      if (!c || seen.has(id) || seen.size > 60) return empty();     // placeholder for a child not sent yet; loops are cut
      seen = new Set(seen).add(id);
      const h = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
      let el;
      switch (c.component) {
        case 'Text': {
          const v = c.variant || 'body';
          el = h({ h1: 'h1', h2: 'h2', h3: 'h3', h4: 'h4', h5: 'h5' }[v] || 'p', 'a2ui-text a2ui-' + v, String(this.val(s, c.text) ?? ''));
          break;
        }
        case 'Image': {
          const u = this.val(s, c.url);
          el = h('img', 'a2ui-image a2ui-' + (c.variant || 'mediumFeature'));
          if (imageUrl(u)) el.src = u;
          el.alt = String(this.val(s, c.description) ?? '');
          break;
        }
        case 'Icon': {
          const n = String(this.val(s, c.name) ?? '');
          el = h('span', 'a2ui-icon', ICONS[n] || n);
          el.setAttribute('aria-hidden', 'true');
          break;
        }
        case 'Row': case 'Column': case 'List': {
          const row = c.component === 'Row' || (c.component === 'List' && c.direction === 'horizontal');
          el = h('div', 'a2ui-' + (row ? 'row' : 'column') + (c.component === 'List' ? ' a2ui-list' : ''));
          if (c.justify) el.style.justifyContent = { start: 'flex-start', center: 'center', end: 'flex-end', spaceBetween: 'space-between',
                                                      spaceAround: 'space-around', spaceEvenly: 'space-evenly' }[c.justify] || '';
          if (c.align) el.style.alignItems = { start: 'flex-start', center: 'center', end: 'flex-end', stretch: 'stretch' }[c.align] || '';
          this.kids(s, c, seen).forEach(k => el.appendChild(k));
          break;
        }
        case 'Card': el = h('div', 'a2ui-card'); this.kids(s, c, seen).forEach(k => el.appendChild(k)); break;
        case 'Divider': el = h('hr', 'a2ui-divider'); break;
        case 'Button': {
          el = h('button', 'a2ui-button a2ui-' + (c.variant || 'default'));
          el.type = 'button';
          this.kids(s, c, seen).forEach(k => el.appendChild(k));
          el.addEventListener('click', () => this.dispatch(s, c, c.action));
          break;
        }
        case 'Tabs': {
          el = h('div', 'a2ui-tabs');
          const bar = h('div', 'a2ui-tabbar'), body = h('div', 'a2ui-tabbody');
          bar.setAttribute('role', 'tablist');
          const tabs = Array.isArray(c.tabs) ? c.tabs : [];
          const show = i => { [...bar.children].forEach((b, j) => b.setAttribute('aria-selected', String(i === j)));
                              body.replaceChildren(tabs[i] ? this.build(s, tabs[i].child, seen) : empty()); };
          tabs.forEach((t, i) => { const b = h('button', 'a2ui-tab', String(this.val(s, t.title) ?? '')); b.type = 'button';
                                   b.setAttribute('role', 'tab'); b.addEventListener('click', () => show(i)); bar.appendChild(b); });
          el.append(bar, body); show(0);
          break;
        }
        case 'Modal': {
          el = h('div', 'a2ui-modal');
          const dlg = h('dialog', 'a2ui-dialog');
          const close = h('button', 'a2ui-button a2ui-default', 'Close'); close.type = 'button'; close.addEventListener('click', () => dlg.close());
          dlg.append(this.build(s, c.content, seen), close);
          const trig = this.build(s, c.trigger, seen);
          trig.addEventListener && trig.addEventListener('click', () => dlg.showModal && dlg.showModal());
          el.append(trig, dlg);
          break;
        }
        case 'CheckBox': {
          el = h('label', 'a2ui-field a2ui-check');
          const i = h('input'); i.type = 'checkbox'; i.checked = !!this.val(s, c.value);
          i.addEventListener('change', () => this.bind(s, c.value, i.checked));
          el.append(i, h('span', null, String(this.val(s, c.label) ?? '')));
          break;
        }
        case 'TextField': {
          el = h('label', 'a2ui-field');
          const long = c.variant === 'longText';
          const i = h(long ? 'textarea' : 'input');
          if (!long) i.type = { number: 'number', obscured: 'password' }[c.variant] || 'text';
          i.value = String(this.val(s, c.value) ?? '');
          i.addEventListener('input', () => this.bind(s, c.value, c.variant === 'number' ? Number(i.value) : i.value));
          el.append(h('span', null, String(this.val(s, c.label) ?? '')), i);
          break;
        }
        case 'DateTimeInput': {
          el = h('label', 'a2ui-field');
          const d = c.enableDate !== false, t = !!c.enableTime;
          const i = h('input'); i.type = d && t ? 'datetime-local' : (t ? 'time' : 'date');
          i.value = String(this.val(s, c.value) ?? '').slice(0, d && t ? 16 : (t ? 8 : 10));
          i.addEventListener('change', () => this.bind(s, c.value, i.value));
          el.append(h('span', null, String(this.val(s, c.label) ?? '')), i);
          break;
        }
        case 'Slider': {
          el = h('label', 'a2ui-field');
          const i = h('input'); i.type = 'range'; i.min = this.val(s, c.min) ?? 0; i.max = this.val(s, c.max) ?? 100;
          i.value = this.val(s, c.value) ?? i.min;
          const out = h('output', null, i.value);
          i.addEventListener('input', () => { out.textContent = i.value; this.bind(s, c.value, Number(i.value)); });
          el.append(h('span', null, String(this.val(s, c.label) ?? '')), i, out);
          break;
        }
        case 'ChoicePicker': {
          el = h('fieldset', 'a2ui-field a2ui-choices');
          el.appendChild(h('legend', null, String(this.val(s, c.label) ?? '')));
          const multi = c.variant === 'multipleSelection';
          const cur = [].concat(this.val(s, c.value) ?? []);
          const name = 'a2ui-' + s.id + '-' + c.id;
          const read = () => [...el.querySelectorAll('input:checked')].map(x => x.value);
          (Array.isArray(c.options) ? c.options : []).forEach(o => {
            const l = h('label', 'a2ui-check'); const i = h('input'); i.type = multi ? 'checkbox' : 'radio'; i.name = name;
            i.value = String(o.value); i.checked = cur.includes(o.value);
            i.addEventListener('change', () => this.bind(s, c.value, read()));
            l.append(i, h('span', null, String(this.val(s, o.label) ?? o.value))); el.appendChild(l);
          });
          break;
        }
        default: return empty();                      // not in the basic catalog subset: draw nothing
      }
      el.dataset.component = c.id;
      return el;
    }
  }

  return { VERSION, BASIC, KNOWN, Store, Renderer, getPath, setPath };
});
