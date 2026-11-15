#!/usr/bin/env node
// Writes the CALC tab's trainer-AI parity cases (app/src/test/resources/calc/ai_vectors.jsonl.gz): battles
// put to the trainer AI the CALC tab's was ported from (kvarflow/rr-calc's src/js/trainer_ai.js, built:
// `npm install && node build`), each with what that AI answered, for CfruAiParityTest to replay.
//
//   SEED=n node scripts/gen_ai_vectors.js <rr-calc checkout> [count] [out.jsonl.gz]
//
// Each battle is built the way the site's Battle tab builds it (buildAISide, readAIParty, readZMoves and
// readPendingMega in trainer_ai.js): a trainer's Pokémon from the game's sets against another trainer's
// as yours, under a random field, HP, status and stat stages, with the rest of the trainer's team on the
// bench. A line is {spec, battle, result}: `spec` the Pokémon and field the battle came from (so the
// app's own way of building a battle can be checked against `battle`), `result` predictAIMoves' and
// predictReplacement's answers.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const root = process.argv[2];
if (!root) {
  console.error('usage: gen_ai_vectors.js <rr-calc checkout> [count] [out.jsonl.gz]');
  process.exit(2);
}
const count = +(process.argv[3] || 1500);
const out = process.argv[4] ||
  path.join(__dirname, '..', 'app', 'src', 'test', 'resources', 'calc', 'ai_vectors.jsonl.gz');

const calc = require(path.join(root, 'calc', 'dist', 'index.js'));
const gen = calc.Generations.get(9);
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const load = (file, name) => new Function(read(file) + '; return ' + name + ';')();

// The AI's core: trainer_ai.js up to its page code, with the few globals it reads.
const aiSource = read('src/js/trainer_ai.js');
const core = aiSource.slice(0, aiSource.indexOf('function formatAIShare('));
const shim = {
  calc,
  typeChart: calc.TYPE_CHART[9],
  TRAINER_AI_MOVES: load('src/js/data/trainer_ai.js', 'TRAINER_AI_MOVES'),
  $: {extend: (...objs) => Object.assign(...objs)},
  currentBattleEffect: '',
};
const ai = new Function(...Object.keys(shim), core +
  '\nreturn {predictAIMoves, predictReplacement, zMoveNameFor, getAIMoveData, SPECIAL_Z_DATA, E, SPLIT_STATUS, typeEffectiveness,' +
  ' setEffect: function (e) { currentBattleEffect = e; }};')(...Object.values(shim));

// mulberry32
let seed = +(process.env.SEED || 0x41494149);
function rnd() {
  seed = (seed + 0x6D2B79F5) | 0;
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}
const int = n => Math.floor(rnd() * n);
const pick = xs => xs[int(xs.length)];
const chance = p => rnd() < p;

const SITE = {hp: 'hp', at: 'atk', df: 'def', sa: 'spa', sd: 'spd', sp: 'spe'};
const STATS = ['hp', 'atk', 'def', 'spa', 'spd', 'spe'];
const table = (t, def) => {
  const o = {};
  for (const s of STATS) o[s] = def;
  for (const k of Object.keys(t || {})) o[SITE[k] || k] = t[k];
  return o;
};

const modes = {};
for (const mode of ['normal', 'hardcore']) {
  modes[mode] = {sets: load(`src/js/data/sets/${mode}.js`, 'SETDEX_SV'), order: load(`src/js/data/trainers/${mode}.js`, 'TRAINER_ORDER')};
}
const setOf = (mode, id) => {
  const m = /^(.*) \((.*)\)$/.exec(id);
  const s = m && modes[mode].sets[m[1]] && modes[mode].sets[m[1]][m[2]];
  return s ? {species: m[1], name: m[2], ...s} : null;
};

// crit_tools.js's getCritChance, Gen 7+.
const HIGH_CRIT_MOVES = ['Aeroblast', 'Air Cutter', 'Aqua Cutter', 'Attack Order', 'Blaze Kick', 'Crabhammer', 'Cross Chop',
  'Cross Poison', 'Dire Claw', 'Drill Run', 'Esper Wing', 'Ivy Cudgel', 'Karate Chop', 'Leaf Blade', 'Night Slash', 'Poison Tail',
  'Psycho Cut', 'Razor Leaf', 'Razor Shell', 'Razor Wind', 'Shadow Claw', 'Sky Attack', 'Slash', 'Snipe Shot', 'Spacial Rend',
  'Stone Edge', 'Triple Arrows'];
function getCritChance(attacker, defender, move, focusEnergy) {
  if (!move || move.name === '(No Move)' || move.category === 'Status') return null;
  if (['Battle Armor', 'Shell Armor'].includes(defender.ability) && !['Mold Breaker', 'Teravolt', 'Turboblaze'].includes(attacker.ability)) return 0;
  if (move.isCrit || (attacker.ability === 'Merciless' && /^(psn|tox)$/.test(defender.status))) return 1;
  let stage = 0;
  if (HIGH_CRIT_MOVES.includes(move.originalName || move.name)) stage++;
  if (attacker.item === 'Scope Lens' || attacker.item === 'Razor Claw') stage++;
  if (attacker.ability === 'Super Luck') stage++;
  if (/^(Farfetch’d|Sirfetch’d)/.test(attacker.name) && (attacker.item === 'Leek' || attacker.item === 'Leek Stick')) stage += 2;
  if (attacker.name === 'Chansey' && attacker.item === 'Lucky Punch') stage += 2;
  if (focusEnergy) stage += 2;
  const chances = [1 / 24, 1 / 8, 1 / 2, 1];
  return chances[Math.min(stage, chances.length - 1)];
}

function maxCritRoll(result) {
  try {
    const move = result.move.clone();
    move.isCrit = true;
    return calc.calculate(gen, result.attacker, result.defender, move, result.field).range()[1];
  } catch (e) {
    return result.range()[1];
  }
}

// trainer_ai.js's buildAISide, as the page calls it.
function buildAISide(key, pokemon, results, foe, options) {
  const side = {
    key, isPlayer: key === 'p1', name: pokemon.name, types: pokemon.types.slice(), ability: pokemon.ability || '',
    abilityOn: pokemon.abilityOn, item: pokemon.item || '', status: pokemon.status || '', toxicCounter: pokemon.toxicCounter || 0,
    level: pokemon.level, gender: pokemon.gender || '', hp: pokemon.curHP(), maxHP: pokemon.maxHP(),
    boosts: Object.assign({atk: 0, def: 0, spa: 0, spd: 0, spe: 0}, pokemon.boosts), stats: pokemon.stats, speed: options.speed,
    monsLeft: options.monsLeft, firstTurn: options.firstTurn, sideState: options.sideState, seeded: options.sideState.seeded,
    saltCured: !!options.saltCured, focusEnergy: !!options.focusEnergy, moves: [],
  };
  for (let i = 0; i < 4; i++) {
    const result = results[i];
    const move = result && result.move;
    if (!move || move.name === '(No Move)' || !move.name) {
      side.moves.push(null);
      continue;
    }
    const data = ai.getAIMoveData(move);
    const range = result.range();
    const crit = options.critChance ? options.critChance(move) || 0 : 0;
    let maxCrit = range[1];
    if (crit >= 0.5 && !move.isCrit && options.critDamage) maxCrit = options.critDamage(i);
    let effectiveness = 1;
    const row = shim.typeChart[move.type];
    if (row && foe) effectiveness = foe.types.reduce((product, type) => product * (row[type] !== undefined ? row[type] : 1), 1);
    if (data.split !== ai.SPLIT_STATUS && range[1] === 0 && data.power > 0) effectiveness = 0;
    side.moves.push({
      index: i, name: move.originalName || move.name, type: move.type, bp: move.bp, hits: move.hits || 1, recoil: move.recoil,
      drain: move.drain, sound: !!(move.flags && move.flags.sound), bullet: !!(move.flags && move.flags.bullet),
      effect: data.effect, accuracy: data.accuracy, chance: data.chance, target: data.target, priority: data.priority,
      flags: data.flags, split: data.split, power: data.power, maxDamage: range[1], maxCritDamage: maxCrit, critChance: crit,
      effectiveness,
    });
  }
  return side;
}

function sideState() {
  const s = {sr: chance(0.15), spikes: chance(0.1) ? 1 + int(3) : 0, toxicSpikes: 0, stickyWeb: false,
    reflect: chance(0.08), lightScreen: chance(0.08), auroraVeil: chance(0.04), tailwind: chance(0.05), seeded: chance(0.06)};
  return s;
}
const calcSide = s => ({isSR: s.sr, spikes: s.spikes, isReflect: s.reflect, isLightScreen: s.lightScreen,
  isAuroraVeil: s.auroraVeil, isTailwind: s.tailwind, isSeeded: s.seeded});

// A Pokémon from a set, as the page's createPokemon makes one; `state` its battle state.
function makeMon(set, state, overrides = {}) {
  const species = overrides.species || set.species;
  return new calc.Pokemon(gen, species, {
    level: set.level, nature: set.nature || 'Serious', ability: overrides.ability || set.ability || undefined,
    abilityOn: overrides.abilityOn, item: set.item || undefined, ivs: table(set.ivs, 31), evs: table(set.evs, 0),
    moves: (set.moves || []).map(m => new calc.Move(gen, m || '(No Move)', {ability: set.ability, item: set.item, species})),
    curHP: state.curHP || undefined, status: state.status || '', boosts: state.boosts, toxicCounter: state.toxicCounter || 0,
  });
}

// The base form a Mega shows before it evolves (readAIParty's rule).
function baseOf(set, slot, variant) {
  const base = set.species.split('-Mega')[0];
  const species = gen.species.get(calc.toID(base));
  if (!species) return null;
  return {species: base, ability: (variant.preMega && variant.preMega[slot]) || species.abilities[0], abilityOn: true};
}

const WEATHERS = ['Sun', 'Rain', 'Sand', 'Snow'];
const TERRAINS = ['Electric', 'Grassy', 'Misty', 'Psychic'];
const STATUSES = ['slp', 'psn', 'brn', 'frz', 'par', 'tox'];

function randomState(set, full) {
  const st = {curHP: 0, status: '', boosts: {}, toxicCounter: 0};
  if (!full) {
    if (chance(0.45)) st.hpPercent = 1 + int(99);
    if (chance(0.2)) st.status = pick(STATUSES);
    if (st.status === 'tox') st.toxicCounter = int(9);
    if (chance(0.3)) for (const s of ['atk', 'def', 'spa', 'spd', 'spe']) if (chance(0.35)) st.boosts[s] = int(13) - 6;
  }
  return st;
}
function withHp(mon, st) {
  if (st.hpPercent) st.curHP = Math.max(1, Math.floor(mon.maxHP() * st.hpPercent / 100));
  return st;
}

const fights = [];
for (const mode of Object.keys(modes)) {
  modes[mode].order.forEach((f, index) => f.variants.forEach(v => {
    if (!/Doubles/i.test(v.battleEffect || '') && v.sets.length) fights.push({mode, fight: f, variant: v, index});
  }));
}

const lines = [];
let tries = 0;
let errors = 0;
const started = Date.now();
while (lines.length < count) {
  tries++;
  const foe = pick(fights);
  const mine = pick(fights.filter(f => f.mode === foe.mode));
  const aiSlot = int(foe.variant.sets.length);
  const aiSet = setOf(foe.mode, foe.variant.sets[aiSlot]);
  const youSet = setOf(mine.mode, pick(mine.variant.sets));
  if (!aiSet || !youSet || !gen.species.get(calc.toID(aiSet.species)) || !gen.species.get(calc.toID(youSet.species))) continue;
  const megaPending = /-Mega/.test(aiSet.species) && chance(0.4) ? baseOf(aiSet, aiSlot, foe.variant) : null;
  const spec = {
    mode: foe.mode, fight: foe.index, label: foe.variant.label || '', battleEffect: foe.variant.battleEffect || '', aiSlot,
    you: {set: youSet, state: randomState(youSet, chance(0.4))}, ai: {set: aiSet, state: randomState(aiSet, chance(0.4))},
    megaPending,
    field: {weather: chance(0.25) ? pick(WEATHERS) : '', terrain: chance(0.12) ? pick(TERRAINS) : '', gravity: chance(0.03),
      trickRoom: chance(0.06)},
    youSide: sideState(), aiSide: sideState(), focus: {p1: chance(0.05), p2: chance(0.05)},
    yourMonsLeft: 1 + int(6), kos: [], justSwitchedIn: false,
  };
  const teamSize = foe.variant.sets.length;
  for (let s = 0; s < teamSize; s++) if (s !== aiSlot && chance(0.25)) spec.kos.push(s);

  let line;
  try {
    ai.setEffect(spec.battleEffect);
    const p1 = makeMon(youSet, spec.you.state);
    withHp(p1, spec.you.state);
    const p1b = makeMon(youSet, spec.you.state);
    const shown = megaPending || {};
    const p2 = makeMon(aiSet, spec.ai.state, shown);
    withHp(p2, spec.ai.state);
    const p2b = makeMon(aiSet, spec.ai.state, shown);
    const fieldBase = {gameType: 'Singles', weather: spec.field.weather || undefined, terrain: spec.field.terrain || undefined,
      isGravity: spec.field.gravity};
    const aiField = new calc.Field({...fieldBase, attackerSide: calcSide(spec.aiSide), defenderSide: calcSide(spec.youSide)});
    const playerField = new calc.Field({...fieldBase, attackerSide: calcSide(spec.youSide), defenderSide: calcSide(spec.aiSide)});
    const yours = p1b.moves.map(m => calc.calculate(gen, p1b, p2b, m, playerField));
    const theirs = p2b.moves.map(m => calc.calculate(gen, p2b, p1b, m, aiField));
    while (yours.length < 4) yours.push(null);
    while (theirs.length < 4) theirs.push(null);
    const P1 = yours[0].attacker;
    const P2 = theirs[0].attacker;
    const crit = (a, d, focus) => move => getCritChance(a, d, move, focus) || 0;
    const critDamage = results => i => maxCritRoll(results[i]);
    const player = buildAISide('p1', P1, yours, P2, {
      speed: P1.stats.spe, monsLeft: spec.yourMonsLeft, firstTurn: P1.curHP() === P1.maxHP(), sideState: spec.youSide,
      focusEnergy: spec.focus.p1, critChance: crit(P1, P2, spec.focus.p1), critDamage: critDamage(yours)});
    const aiSide = buildAISide('p2', P2, theirs, P1, {
      speed: P2.stats.spe, monsLeft: Math.max(1, teamSize - spec.kos.length), firstTurn: P2.curHP() === P2.maxHP(),
      sideState: spec.aiSide, focusEnergy: spec.focus.p2, critChance: crit(P2, P1, spec.focus.p2), critDamage: critDamage(theirs)});
    // readZMoves (Hardcore).
    if (spec.mode === 'hardcore') {
      let any = false;
      const z = aiSide.moves.map((move, i) => {
        const name = ai.zMoveNameFor(aiSide.name, aiSide.item, move);
        const result = theirs[i];
        if (!name || !result) return null;
        const special = ai.SPECIAL_Z_DATA[name];
        any = true;
        if (special && special[0] === ai.E.EXTREME_EVOBOOST) {
          return Object.assign({}, move, {name, zBase: move.name, bp: 0, power: 0, effect: special[0], accuracy: 0, chance: 0,
            target: special[3], priority: 0, flags: 0, split: ai.SPLIT_STATUS, hits: 1, recoil: undefined, drain: undefined,
            maxDamage: 0, maxCritDamage: 0, effectiveness: 1});
        }
        let zMove;
        let range;
        try {
          zMove = new calc.Move(gen, move.name, {useZ: true, item: aiSide.item, species: aiSide.name, ability: aiSide.ability});
          range = calc.calculate(gen, result.attacker, result.defender, zMove, result.field).range();
        } catch (e) {
          return null;
        }
        return Object.assign({}, move, {name, zBase: move.name, bp: zMove.bp, power: special && special[1] > 1 ? special[1] : zMove.bp,
          effect: special ? special[0] : ai.E.HIT, accuracy: 0, chance: special ? special[2] : 0, target: special ? special[3] : 0,
          priority: 0, flags: 0, hits: 1, recoil: undefined, drain: undefined, sound: false, bullet: false,
          maxDamage: range[1], maxCritDamage: range[1]});
      });
      aiSide.zMoves = any ? z : null;
    }
    // readAIParty: the rest of the team, not KO'd, at full HP; a benched Mega in its base form.
    const party = [];
    foe.variant.sets.forEach((setId, slot) => {
      if (slot === aiSlot || spec.kos.includes(slot)) return;
      const set = setOf(foe.mode, setId);
      if (!set || !gen.species.get(calc.toID(set.species))) return;
      const base = /-Mega/.test(set.species) ? baseOf(set, slot, foe.variant) : null;
      const mon = makeMon(set, {}, base || {});
      const outR = mon.moves.map(m => calc.calculate(gen, mon, p1b, m, aiField));
      const inR = p1b.moves.map(m => calc.calculate(gen, p1b, mon, m, playerField));
      while (outR.length < 4) outR.push(null);
      while (inR.length < 4) inR.push(null);
      const M = outR[0].attacker;
      const side = buildAISide('b' + slot, M, outR, p1b, {speed: M.stats.spe, monsLeft: aiSide.monsLeft, firstTurn: true,
        sideState: spec.aiSide, critChance: move => getCritChance(M, p1b, move, false) || 0, critDamage: critDamage(outR)});
      side.slot = slot;
      side.setId = setId;
      side.incoming = inR.map(result => {
        const move = result && result.move;
        if (!move || !move.name || move.name === '(No Move)') return null;
        const c = getCritChance(p1b, M, move, spec.focus.p1) || 0;
        const max = result.range()[1];
        return {maxDamage: max, maxCritDamage: c >= 0.5 && c < 1 ? maxCritRoll(result) : max, critChance: c,
          effectiveness: ai.typeEffectiveness(move.type, side.types)};
      });
      party.push(side);
    });
    spec.justSwitchedIn = aiSide.firstTurn && (spec.kos.length > 0 || aiSlot !== 0);
    const battle = {ai: aiSide, player, field: {weather: aiField.weather, terrain: aiField.terrain, isGravity: aiField.isGravity},
      trickRoom: spec.field.trickRoom, flags: 7, party, justSwitchedIn: spec.justSwitchedIn, battleEffect: spec.battleEffect};
    // readPendingMega: the Mega it turns into this turn, and you against it.
    if (megaPending) {
      const mega = makeMon(aiSet, spec.ai.state);
      mega.originalCurHP = P2.curHP();
      const mOut = mega.moves.map(m => calc.calculate(gen, mega, p1b, m, aiField));
      const mIn = p1b.moves.map(m => calc.calculate(gen, p1b, mega, m, playerField));
      while (mOut.length < 4) mOut.push(null);
      while (mIn.length < 4) mIn.push(null);
      const megaSide = mOut[0].attacker;
      battle.mega = {
        ai: buildAISide('p2', megaSide, mOut, p1b, {speed: megaSide.stats.spe, monsLeft: aiSide.monsLeft, firstTurn: aiSide.firstTurn,
          sideState: aiSide.sideState, focusEnergy: spec.focus.p2, critChance: crit(megaSide, p1b, spec.focus.p2),
          critDamage: critDamage(mOut)}),
        player: buildAISide('p1', mIn[0].attacker, mIn, megaSide, {speed: player.speed, monsLeft: player.monsLeft,
          firstTurn: player.firstTurn, sideState: player.sideState, focusEnergy: spec.focus.p1, critChance: crit(p1b, megaSide, spec.focus.p1),
          critDamage: critDamage(mIn)}),
      };
    }
    const json = JSON.parse(JSON.stringify(battle));
    const prediction = ai.predictAIMoves(battle);
    const replacement = ai.predictReplacement(battle);
    const result = {
      shares: prediction.shares, zShares: prediction.zShares, switchShare: prediction.switchShare,
      switches: prediction.switches.map(e => ({slot: e.mon.slot, share: e.share, reasons: e.reasons})),
      replacement: replacement.map(e => ({slot: e.mon.slot, share: e.share})),
    };
    line = JSON.stringify({spec, battle: json, result});
  } catch (e) {
    if (++errors <= 3) console.warn('skipped a battle the AI threw on:', e.message);
    continue;
  }
  lines.push(line);
  if (lines.length % 250 === 0) console.log(`${lines.length} battles, ${((Date.now() - started) / 1000).toFixed(0)} s`);
}

fs.mkdirSync(path.dirname(out), {recursive: true});
const gz = zlib.gzipSync(Buffer.from(lines.join('\n') + '\n', 'utf8'), {level: 9});
gz.writeUInt32LE(0, 4);
fs.writeFileSync(out, gz);
const switches = lines.filter(l => !/"switchShare":0[,}]/.test(l)).length;
console.log(`${out}: ${lines.length} battles (${switches} with a switch) from ${tries} tries, ${errors} thrown, ${gz.length} bytes`);
