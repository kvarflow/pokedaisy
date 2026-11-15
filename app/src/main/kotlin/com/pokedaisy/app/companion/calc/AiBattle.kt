package com.pokedaisy.app.companion.calc

/**
 * The battle as the trainer AI ([CfruAi]) sees it: the site's `battle` object (trainer_ai.js's buildAISide /
 * readAIBattle in kvarflow/rr-calc), field for field. Damage numbers come in worked out ([AiMove.maxDamage]):
 * the AI never calls the engine itself. Sides are copied, never changed, while the AI thinks (`copy()` is the
 * site's `$.extend({}, ...)`), and it tells sides and moves apart by identity, as the site does.
 */
class AiSideState(
    val sr: Boolean = false,
    val spikes: Int = 0,
    val toxicSpikes: Int = 0,
    val stickyWeb: Boolean = false,
    val reflect: Boolean = false,
    val lightScreen: Boolean = false,
    val auroraVeil: Boolean = false,
    val tailwind: Boolean = false,
    val seeded: Boolean = false,
) {
    /** The hazards and screens shouldCourtChange compares by name. */
    fun has(key: String): Boolean = when (key) {
        "sr" -> sr
        "spikes" -> spikes > 0
        "stickyWeb" -> stickyWeb
        "reflect" -> reflect
        "lightScreen" -> lightScreen
        "auroraVeil" -> auroraVeil
        "tailwind" -> tailwind
        else -> false
    }
}

/** One of a side's moves: the ROM's AI data ([AiMoveData]) with the engine's numbers against the other side. */
class AiMove(
    val index: Int,
    val name: String,
    val type: String,
    val bp: Int,
    val hits: Int,
    val recoil: Boolean,
    /** The share of the damage drained, as [numerator, denominator]; null = none. */
    val drain: IntArray?,
    val sound: Boolean,
    val bullet: Boolean,
    val effect: Int,
    val accuracy: Int,
    val chance: Int,
    val target: Int,
    val priority: Int,
    val flags: Int,
    val split: Int,
    val power: Int,
    /** The engine's highest roll against the other side, and its highest critical roll where a crit is likely. */
    val maxDamage: Int,
    val maxCritDamage: Int,
    val critChance: Double,
    /** The type chart's multiplier against the other side (0 where the engine finds no damage). */
    val effectiveness: Double,
    /** A Z-Move: the move it was made from. */
    val zBase: String? = null,
) {
    fun copy(
        name: String = this.name, bp: Int = this.bp, power: Int = this.power, effect: Int = this.effect, accuracy: Int = this.accuracy,
        chance: Int = this.chance, target: Int = this.target, priority: Int = this.priority, flags: Int = this.flags, split: Int = this.split,
        hits: Int = this.hits, recoil: Boolean = this.recoil, drain: IntArray? = this.drain, sound: Boolean = this.sound,
        bullet: Boolean = this.bullet, maxDamage: Int = this.maxDamage, maxCritDamage: Int = this.maxCritDamage,
        effectiveness: Double = this.effectiveness, zBase: String? = this.zBase,
    ) = AiMove(
        index, name, type, bp, hits, recoil, drain, sound, bullet, effect, accuracy, chance, target, priority, flags, split, power,
        maxDamage, maxCritDamage, critChance, effectiveness, zBase,
    )
}

/** One of the other side's moves on a benched Pokémon (readAIParty's `incoming`). */
class AiHit(val maxDamage: Int, val maxCritDamage: Int, val critChance: Double, val effectiveness: Double)

class AiSide(
    /** "p1" yours, "p2" the trainer's on the field, "b<slot>" its bench. */
    val key: String,
    val name: String,
    val types: List<String>,
    val ability: String,
    val abilityOn: Boolean,
    val item: String,
    /** "" / slp / psn / brn / frz / par / tox. */
    val status: String,
    val toxicCounter: Int,
    val level: Int,
    /** "M" / "F" / "N" / "". */
    val gender: String,
    val hp: Int,
    val maxHP: Int,
    /** Stat stages by the calculator's ids (atk, def, spa, spd, spe, ...), in its order; a missing one is 0. */
    val boosts: Map<String, Int>,
    /** The six stats as the engine left them (hp, atk, def, spa, spd, spe). */
    val stats: Map<String, Int>,
    val speed: Int,
    val monsLeft: Int,
    val firstTurn: Boolean,
    val sideState: AiSideState,
    val saltCured: Boolean = false,
    val focusEnergy: Boolean = false,
    val moves: List<AiMove?>,
    /** In Hardcore, each move as its Z-Move (or null); null = none. */
    val zMoves: List<AiMove?>? = null,
    /** Which moves the AI is turning into Z-Moves this turn. */
    val zSlots: List<Boolean>? = null,
    /** A benched Pokémon: its team slot, and your moves on it. */
    val slot: Int = -1,
    val incoming: List<AiHit?> = emptyList(),
) {
    val isPlayer: Boolean get() = key == "p1"
    val seeded: Boolean get() = sideState.seeded

    fun copy(
        moves: List<AiMove?> = this.moves, zSlots: List<Boolean>? = this.zSlots, zMoves: List<AiMove?>? = this.zMoves,
        incoming: List<AiHit?> = this.incoming,
    ) = AiSide(
        key, name, types, ability, abilityOn, item, status, toxicCounter, level, gender, hp, maxHP, boosts, stats, speed, monsLeft,
        firstTurn, sideState, saltCured, focusEnergy, moves, zMoves, zSlots, slot, incoming,
    )
}

class AiField(val weather: String?, val terrain: String?, val isGravity: Boolean)

/** A Mega the trainer's Pokémon turns into as it moves this turn, and your side against it. */
class AiMega(val ai: AiSide, val player: AiSide)

class AiBattle(
    val ai: AiSide,
    val player: AiSide,
    val field: AiField,
    val trickRoom: Boolean,
    val flags: Int,
    /** The trainer's other Pokémon still standing, at full HP. */
    val party: List<AiSide>,
    /** The trainer's Pokémon came in this turn: most switching checks wait a turn. */
    val justSwitchedIn: Boolean,
    /** The fight's standing rule in the game's notes' words. */
    val battleEffect: String = "",
    val mega: AiMega? = null,
) {
    fun copy(ai: AiSide = this.ai, player: AiSide = this.player) =
        AiBattle(ai, player, field, trickRoom, flags, party, justSwitchedIn, battleEffect, mega)
}

/** One switch the AI may make: to [mon], with this share of the turn, by reason (CfruAi.SWITCH_REASONS' index). */
class AiSwitch(val mon: AiSide, val share: Double, val reasons: Map<Int, Double>)

/** What the AI does this turn: the share of each of its four moves (of them as Z-Moves too) and of each switch. */
class AiPrediction(val shares: DoubleArray, val zShares: DoubleArray, val switches: List<AiSwitch>, val switchShare: Double)
