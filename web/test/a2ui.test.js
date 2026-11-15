// Run: node --test web/test
const test = require('node:test');
const assert = require('node:assert/strict');
const A = require('../a2ui.js');

const make = () => { const errors = [], changed = []; const s = new A.Store(id => changed.push(id), e => errors.push(e)); return { s, errors, changed }; };
const line = o => JSON.stringify({ version: A.VERSION, ...o });
const create = id => line({ createSurface: { surfaceId: id, catalogId: A.BASIC } });
const comps = (id, components) => line({ updateComponents: { surfaceId: id, components } });

test('nothing is ready until root exists, then it is', () => {
  const { s } = make();
  s.processText(create('a'));
  assert.equal(s.ready('a'), false);
  s.processText(comps('a', [{ id: 't', component: 'Text', text: 'x' }]));
  assert.equal(s.ready('a'), false);
  s.processText(comps('a', [{ id: 'root', component: 'Column', children: ['t'] }]));
  assert.equal(s.ready('a'), true);
});

test('JSONL: several messages in one stream, a bad line is reported and the rest still apply', () => {
  const { s, errors } = make();
  s.processText([create('a'), 'garbage', comps('a', [{ id: 'root', component: 'Text', text: 'hi' }])].join('\n'));
  assert.equal(errors.length, 1);
  assert.equal(errors[0].code, 'VALIDATION_FAILED');
  assert.equal(s.ready('a'), true);
});

test('rules: duplicate create, unknown catalog, wrong version, two keys, missing surface', () => {
  const { s, errors } = make();
  s.processText(create('a'));
  s.processText(create('a'));
  s.processText(line({ createSurface: { surfaceId: 'b', catalogId: 'https://evil.example/c.json' } }));
  s.processText(JSON.stringify({ version: 'v0.8', createSurface: { surfaceId: 'c', catalogId: A.BASIC } }));
  s.processText(line({ createSurface: { surfaceId: 'd', catalogId: A.BASIC }, deleteSurface: { surfaceId: 'a' } }));
  s.processText(comps('nope', []));
  assert.equal(errors.length, 5);
  assert.deepEqual([...s.surfaces.keys()], ['a']);
});

test('data model: set, replace, remove by omitting value, nested paths', () => {
  const { s } = make();
  s.processText(create('a'));
  const upd = (path, value) => line({ updateDataModel: { surfaceId: 'a', path, value } });
  s.processText(upd('/', { user: { name: 'Ron' }, n: 1 }));
  assert.equal(A.getPath(s.surfaces.get('a').model, '/user/name'), 'Ron');
  s.processText(upd('/user/name', 'Alex'));
  s.processText(upd('/deep/er/x', 5));
  s.processText(line({ updateDataModel: { surfaceId: 'a', path: '/n' } }));
  const m = s.surfaces.get('a').model;
  assert.equal(m.user.name, 'Alex');
  assert.equal(m.deep.er.x, 5);
  assert.equal('n' in m, false);
});

test('deleteSurface and clear', () => {
  const { s, changed } = make();
  s.processText(create('a')); s.processText(create('b'));
  s.processText(line({ deleteSurface: { surfaceId: 'a' } }));
  assert.deepEqual([...s.surfaces.keys()], ['b']);
  s.clear();
  assert.equal(s.surfaces.size, 0);
  assert.ok(changed.includes('b'));
});

test('pointer escapes', () => {
  const m = A.setPath({}, '/a~1b/c~0d', 1);
  assert.deepEqual(m, { 'a/b': { 'c~d': 1 } });
});
