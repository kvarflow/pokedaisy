#!/usr/bin/env node
// Writes a CALC pack (app/src/main/assets/calc/<game>.pack.gz) from a built checkout of the
// damage calculator the CALC tab's engine was ported from (kvarflow/rr-calc, a fork of
// smogon/damage-calc; `npm install && node build` there first).
//
//   node scripts/gen_calc_pack.js <rr-calc checkout> [out.pack.gz]
//
// The pack is the game's own tables as the calculator has them: types, natures, species, moves,
// items, the ROM's ids for each (so the companion's live reads map straight onto them), and per
// mode every trainer's sets and the fights in the order they're met. companion/calc/CalcPack.kt
// reads it; its format is described there. Text, tab-separated, gzipped.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const root = process.argv[2];
if (!root) {
  console.error('usage: gen_calc_pack.js <rr-calc checkout> [out.pack.gz]');
  process.exit(2);
}
const out = process.argv[3] ||
  path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'calc', 'radical_red.pack.gz');

const calc = require(path.join(root, 'calc', 'dist', 'index.js'));
const items = require(path.join(root, 'calc', 'dist', 'items.js'));
const gen = calc.Generations.get(9);

// The site's data files are plain scripts that declare one global each.
function load(file, name) {
  const src = fs.readFileSync(path.join(root, file), 'utf8');
  return new Function(src + '; return ' + name + ';')();
}
const ids = load('src/js/data/radical_red_save_data.js', 'RADICAL_RED_SAVE_DATA');

const lines = [];
const row = (...cells) => {
  for (const c of cells) {
    if (/[\t\n]/.test(String(c))) throw new Error('cell with a tab or newline: ' + c);
  }
  lines.push(cells.join('\t'));
};
const list = xs => {
  for (const x of xs) if (String(x).includes('|')) throw new Error('list item with a bar: ' + x);
  return xs.join('|');
};
const flag = x => (x ? 1 : 0);

row('CALCPACK', 1, 'radical_red', 9);

const typeNames = [];
for (const t of gen.types) typeNames.push(t.name);
row('@types', ...typeNames);
for (const t of gen.types) row('T', t.name, ...typeNames.map(n => (t.effectiveness[n] === undefined ? 1 : t.effectiveness[n])));

for (const n of gen.natures) row('N', n.name, n.plus || '', n.minus || '');

const speciesNames = new Set();
for (const s of gen.species) {
  speciesNames.add(s.name);
  const b = s.baseStats;
  row('P', s.name, s.types[0], s.types[1] || '', b.hp, b.atk, b.def, b.spa, b.spd, b.spe,
    s.weightkg, (s.abilities && s.abilities[0]) || '', flag(s.nfe), s.gender || '',
    s.baseSpecies || '', list(s.otherFormes || []));
}

const moveNames = new Set();
for (const m of gen.moves) {
  moveNames.add(m.name);
  const self = (m.self && m.self.boosts) || {};
  const mh = m.multihit === undefined ? [0, 0]
    : typeof m.multihit === 'number' ? [m.multihit, m.multihit] : m.multihit;
  row('M', m.name, m.basePower || 0, m.type, m.category || 'Status', m.priority || 0,
    m.target || '', list(Object.keys(m.flags || {})), flag(m.secondaries), flag(m.recoil),
    m.drain ? m.drain.join('/') : 0, mh[0], mh[1], flag(m.multiaccuracy),
    Math.max(0, -(self.atk || 0)), Math.max(0, -(self.spa || 0)),
    flag(m.willCrit), flag(m.ignoreDefensive), m.overrideDefensiveStat || '',
    flag(m.breaksProtect), flag(m.isZ), (m.zMove && m.zMove.basePower) || 0,
    flag(m.hasCrashDamage));
}

const itemNames = new Set();
for (const i of gen.items) {
  itemNames.add(i.name);
  const gift = items.getNaturalGift(gen, i.name);
  row('I', i.name, i.megaEvolves || '', items.getItemBoostType(i.name) || '',
    items.getBerryResistType(i.name) || '', items.getFlingPower(i.name),
    (gift && gift.t) || '', (gift && gift.p) || 0, items.getTechnoBlast(i.name) || '',
    items.getMultiAttack(i.name) || '', items.SEED_BOOSTED_STAT[i.name] || '');
}

// The trainer AI's view of each move, from the ROM's move table (the site's data/trainer_ai.js): by the
// move's name in lowercase letters and digits, [effect, accuracy, secondary chance, target, priority,
// flags, split, power].
const aiMoves = load('src/js/data/trainer_ai.js', 'TRAINER_AI_MOVES');
for (const key of Object.keys(aiMoves)) row('AM', key, ...aiMoves[key]);

// The ROM's ids. A name the calculator has no data for is kept: the reader shows it and skips it.
ids.species.forEach((name, id) => { if (name) row('XS', id, name); });
ids.moves.forEach((name, id) => { if (name) row('XM', id, name); });
ids.items.forEach((name, id) => { if (name) row('XI', id, name); });
ids.abilities.forEach((names, id) => { if (names) row('XA', id, list(names)); });

const STATS = ['hp', 'at', 'df', 'sa', 'sd', 'sp'];
const spread = (t, def) => STATS.map(s => (t && t[s] !== undefined ? t[s] : def)).join(',');
const unknown = {species: new Set(), moves: new Set(), items: new Set()};

for (const mode of ['normal', 'hardcore']) {
  const sets = load(`src/js/data/sets/${mode}.js`, 'SETDEX_SV');
  const order = load(`src/js/data/trainers/${mode}.js`, 'TRAINER_ORDER');
  const flags = load('src/js/data/trainer_ai.js', 'TRAINER_AI_FLAGS')[mode] || {};
  row('@mode', mode);
  // Only the sets a fight uses: the site also carries the old boss documents' leftovers.
  const used = new Set();
  for (const f of order) for (const v of f.variants) for (const s of v.sets) used.add(s);
  for (const species of Object.keys(sets)) {
    for (const name of Object.keys(sets[species])) {
      if (!used.has(`${species} (${name})`)) continue;
      const s = sets[species][name];
      if (!speciesNames.has(species)) unknown.species.add(species);
      for (const m of s.moves || []) if (m && !moveNames.has(m)) unknown.moves.add(m);
      if (s.item && !itemNames.has(s.item)) unknown.items.add(s.item);
      row('S', species, name, s.level, s.nature || 'Serious', s.ability || '', s.item || '',
        list((s.moves || []).filter(Boolean)), spread(s.ivs, 31), spread(s.evs, 0));
    }
  }
  for (const f of order) {
    const ai = flags[`${f.name}|${f.location}|${f.levelCap}`];
    row('F', f.name, f.location || '', f.levelCap || 0, flag(f.optional), flag(f.route),
      ai === undefined ? (f.optional ? 1 : 7) : ai);
    for (const v of f.variants) {
      // preMega: the ability a Mega's base form has before it evolves, by the team slot.
      const preMega = Object.keys(v.preMega || {}).map(k => `${k}:${v.preMega[k]}`);
      row('V', v.label || '', v.battleEffect || '', (v.trainerIds || []).join(','), list(v.sets), list(preMega));
    }
  }
}

for (const k of Object.keys(unknown)) {
  if (unknown[k].size) console.warn(`sets use ${k} the calculator has no data for:`, [...unknown[k]].join(', '));
}

fs.mkdirSync(path.dirname(out), {recursive: true});
// mtime 0, so the same data gives the same bytes.
const gz = zlib.gzipSync(Buffer.from(lines.join('\n') + '\n', 'utf8'), {level: 9});
gz.writeUInt32LE(0, 4);
fs.writeFileSync(out, gz);
console.log(`${out}: ${lines.length} lines, ${gz.length} bytes`);
