#!/usr/bin/env node
// Writes the CALC engine's parity cases (app/src/test/resources/calc/vectors.tsv.gz): matchups worked
// out by the calculator the engine was ported from (kvarflow/rr-calc, built: `npm install && node
// build`), which CalcParityTest and ui-preview's `calcCheck` replay through the Kotlin port.
//
//   node scripts/gen_calc_vectors.js <rr-calc checkout> [count] [out.tsv.gz]
//
// Two kinds of case, from a fixed seed: trainers' own sets against each other (what the tab shows)
// and arbitrary species / ability / item / move mixes (the branches no trainer happens to reach),
// each under a random field, status, boosts and HP.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const root = process.argv[2];
if (!root) {
  console.error('usage: gen_calc_vectors.js <rr-calc checkout> [count] [out.tsv.gz]');
  process.exit(2);
}
const count = +(process.argv[3] || 6000);
const out = process.argv[4] ||
  path.join(__dirname, '..', 'app', 'src', 'test', 'resources', 'calc', 'vectors.tsv.gz');

const calc = require(path.join(root, 'calc', 'dist', 'index.js'));
const gen = calc.Generations.get(9);
const load = (file, name) =>
  new Function(fs.readFileSync(path.join(root, file), 'utf8') + '; return ' + name + ';')();

// mulberry32
let seed = +(process.env.SEED || 0x52414443);
function rnd() {
  seed = (seed + 0x6D2B79F5) | 0;
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}
const int = n => Math.floor(rnd() * n);
const pick = xs => xs[int(xs.length)];
const chance = p => rnd() < p;

const species = [...gen.species].map(s => s.name);
const moves = [...gen.moves].filter(m => !m.isMax).map(m => m.name);
const damaging = [...gen.moves].filter(m => !m.isMax && m.category !== 'Status').map(m => m.name);
const itemNames = [...gen.items].map(i => i.name);
const abilities = [...gen.abilities].map(a => a.name);
const natures = [...gen.natures].map(n => n.name);
const sets = [];
for (const mode of ['normal', 'hardcore']) {
  const dex = load(`src/js/data/sets/${mode}.js`, 'SETDEX_SV');
  for (const sp of Object.keys(dex)) {
    if (!gen.species.get(calc.toID(sp))) continue;
    for (const name of Object.keys(dex[sp])) sets.push({species: sp, ...dex[sp][name]});
  }
}

const SITE = {hp: 'hp', at: 'atk', df: 'def', sa: 'spa', sd: 'spd', sp: 'spe'};
const STATS = ['hp', 'atk', 'def', 'spa', 'spd', 'spe'];
const table = (t, def) => {
  const o = {};
  for (const s of STATS) o[s] = def;
  for (const k of Object.keys(t || {})) o[SITE[k] || k] = t[k];
  return o;
};
const STATUSES = ['slp', 'psn', 'brn', 'frz', 'par', 'tox'];
const WEATHERS = ['Sun', 'Rain', 'Sand', 'Hail', 'Snow', 'Harsh Sunshine', 'Heavy Rain', 'Strong Winds'];
const TERRAINS = ['Electric', 'Grassy', 'Misty', 'Psychic'];
const SIDE_FLAGS = ['isSR', 'isReflect', 'isLightScreen', 'isAuroraVeil', 'isProtected', 'isForesight',
  'isTailwind', 'isHelpingHand', 'isFlowerGift', 'isFriendGuard', 'isBattery', 'isPowerSpot'];
const FIELD_FLAGS = ['isMagicRoom', 'isWonderRoom', 'isGravity', 'isAuraBreak', 'isFairyAura', 'isDarkAura',
  'isBeadsOfRuin', 'isSwordOfRuin', 'isTabletsOfRuin', 'isVesselOfRuin'];

function mon(wild) {
  let o;
  if (wild) {
    o = {
      species: pick(species), level: 1 + int(100), nature: pick(natures),
      ability: chance(0.9) ? pick(abilities) : '', item: chance(0.7) ? pick(itemNames) : '',
      ivs: table(null, 0), evs: table(null, 0), moves: [],
    };
    for (const s of STATS) {
      o.ivs[s] = int(32);
      o.evs[s] = chance(0.5) ? 0 : int(64) * 4;
    }
  } else {
    const s = pick(sets);
    o = {
      species: s.species, level: s.level, nature: s.nature || 'Serious', ability: s.ability || '',
      item: s.item || '', ivs: table(s.ivs, 31), evs: table(s.evs, 0), moves: (s.moves || []).filter(Boolean),
    };
  }
  o.gender = chance(0.5) ? '' : pick(['M', 'F', 'N']);
  o.abilityOn = chance(0.4);
  o.boosts = table(null, 0);
  if (chance(0.3)) for (const s of STATS.slice(1)) if (chance(0.4)) o.boosts[s] = int(13) - 6;
  o.status = chance(0.25) ? pick(STATUSES) : '';
  o.hpFraction = chance(0.35) ? 1 + int(100) : 0;   // percent of full; 0 = full
  o.alliesFainted = chance(0.1) ? int(6) : 0;
  o.boostedStat = chance(0.05) ? pick(['auto', 'atk', 'def', 'spa', 'spd', 'spe']) : '';
  return o;
}

function build(o) {
  const p = new calc.Pokemon(gen, o.species, {
    level: o.level, gender: o.gender || undefined, ability: o.ability || undefined, abilityOn: o.abilityOn,
    item: o.item || undefined, nature: o.nature, ivs: o.ivs, evs: o.evs, boosts: o.boosts,
    status: o.status, alliesFainted: o.alliesFainted, boostedStat: o.boostedStat || undefined,
  });
  o.curHP = o.hpFraction ? Math.max(1, Math.floor(p.maxHP() * o.hpFraction / 100)) : 0;
  if (!o.curHP) return p;
  return new calc.Pokemon(gen, o.species, {
    level: o.level, gender: o.gender || undefined, ability: o.ability || undefined, abilityOn: o.abilityOn,
    item: o.item || undefined, nature: o.nature, ivs: o.ivs, evs: o.evs, boosts: o.boosts, curHP: o.curHP,
    status: o.status, alliesFainted: o.alliesFainted, boostedStat: o.boostedStat || undefined,
  });
}

function side() {
  const s = {spikes: chance(0.1) ? 1 + int(3) : 0, flags: []};
  for (const f of SIDE_FLAGS) if (chance(0.06)) s.flags.push(f);
  s.switching = chance(0.04);
  return s;
}
const sideOptions = s => {
  const o = {spikes: s.spikes};
  for (const f of s.flags) o[f] = true;
  if (s.switching) o.isSwitching = 'out';
  return o;
};

const stats = t => STATS.map(s => t[s]).join(',');
const monCells = o => [o.species, o.level, o.gender, o.ability, o.abilityOn ? 1 : 0, o.item, o.nature,
  stats(o.ivs), stats(o.evs), stats(o.boosts), o.curHP, o.status, o.alliesFainted, o.boostedStat].join('|');

const lines = [];
let tries = 0;
while (lines.length < count) {
  tries++;
  const wild = chance(0.35);
  const a = mon(wild);
  const d = mon(wild && chance(0.7));
  const moveName = wild || !a.moves.length || chance(0.1)
    ? pick(chance(0.9) ? damaging : moves)
    : pick(a.moves);
  const data = gen.moves.get(calc.toID(moveName));
  if (!data) continue;
  const m = {
    name: data.name,
    useZ: (a.item.endsWith(' Z') && chance(0.6)) || chance(0.02),
    isCrit: chance(0.12),
    hits: Array.isArray(data.multihit) && chance(0.5) ? data.multihit[0] + int(data.multihit[1] - data.multihit[0] + 1)
      : data.multiaccuracy && chance(0.5) ? 1 + int(data.multihit) : 0,
    timesUsed: chance(0.3) ? 1 + int(4) : 0,
    metronome: chance(0.1) ? int(7) : 0,
  };
  const f = {
    doubles: chance(0.15), weather: chance(0.3) ? pick(WEATHERS) : '', terrain: chance(0.15) ? pick(TERRAINS) : '',
    flags: FIELD_FLAGS.filter(() => chance(0.03)), attacker: side(), defender: side(),
  };

  let result, attacker, defender;
  try {
    attacker = build(a);
    defender = build(d);
    const move = new calc.Move(gen, m.name, {
      useZ: m.useZ, isCrit: m.isCrit, hits: m.hits || undefined, timesUsed: m.timesUsed || undefined,
      timesUsedWithMetronome: m.metronome || undefined, ability: a.ability || undefined, item: a.item || undefined,
      species: a.species,
    });
    const fieldOptions = {
      gameType: f.doubles ? 'Doubles' : 'Singles', weather: f.weather || undefined, terrain: f.terrain || undefined,
      attackerSide: sideOptions(f.attacker), defenderSide: sideOptions(f.defender),
    };
    for (const k of f.flags) fieldOptions[k] = true;
    result = calc.calculate(gen, attacker, defender, move, new calc.Field(fieldOptions));
  } catch (e) {
    continue;
  }
  let main, child = '-';
  const dmg = result.damage;
  if (typeof dmg === 'number') {
    main = String(dmg);
  } else if (typeof dmg[0] === 'number' && dmg.length === 2) {
    main = String(dmg[0]);
    child = String(dmg[1]);
  } else if (typeof dmg[0] === 'number') {
    main = dmg.join(',');
  } else {
    main = dmg[0].join(',');
    child = dmg[1].join(',');
  }
  if (!/^[0-9,]+$/.test(main)) continue;   // a NaN: nothing to compare
  const sideCells = s => [s.spikes, s.flags.join(','), s.switching ? 1 : 0].join('|');
  lines.push([
    monCells(a), monCells(d),
    [m.name, m.useZ ? 1 : 0, m.isCrit ? 1 : 0, m.hits, m.timesUsed, m.metronome].join('|'),
    [f.doubles ? 1 : 0, f.weather, f.terrain, f.flags.join(',')].join('|'),
    sideCells(f.attacker), sideCells(f.defender),
    main, child, result.attacker.stats.spe, result.defender.stats.spe,
  ].join('\t'));
}

fs.mkdirSync(path.dirname(out), {recursive: true});
const gz = zlib.gzipSync(Buffer.from(lines.join('\n') + '\n', 'utf8'), {level: 9});
gz.writeUInt32LE(0, 4);
fs.writeFileSync(out, gz);
const nonzero = lines.filter(l => !/\t0\t-\t\d+\t\d+$/.test(l)).length;
console.log(`${out}: ${lines.length} cases (${nonzero} with damage) from ${tries} tries, ${gz.length} bytes`);
