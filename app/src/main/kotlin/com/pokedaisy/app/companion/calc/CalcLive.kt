package com.pokedaisy.app.companion.calc

import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.STAT_ATK
import com.pokedaisy.app.companion.data.STAT_DEF
import com.pokedaisy.app.companion.data.STAT_HP
import com.pokedaisy.app.companion.data.STAT_SPATK
import com.pokedaisy.app.companion.data.STAT_SPDEF
import com.pokedaisy.app.companion.data.STAT_SPEED
import com.pokedaisy.app.companion.data.SaveProgress
import com.pokedaisy.app.companion.data.SnapshotView
import kotlin.math.ceil

/**
 * The bridge from what the companion reads live ([SnapshotView], [MonView]) to the engine's own
 * Pokémon and fights. Nothing here knows the game: the pack's ROM ids do the mapping.
 */
object CalcLive {
    /** The game's stat order (HP, ATTACK, DEFENSE, SPEED, SP. ATK, SP. DEF) for each of the engine's. */
    private val GAME_STAT = intArrayOf(STAT_HP, STAT_ATK, STAT_DEF, STAT_SPATK, STAT_SPDEF, STAT_SPEED)

    /** gBattleMons' stats and stages run ATTACK, DEFENSE, SPEED, SP. ATK, SP. DEF: where each of the engine's five is. */
    private val BATTLE_STAT = intArrayOf(-1, 0, 1, 3, 4, 2)

    private val NATURES = listOf(
        "Hardy", "Lonely", "Brave", "Adamant", "Naughty", "Bold", "Docile", "Relaxed", "Impish", "Lax",
        "Timid", "Hasty", "Serious", "Jolly", "Naive", "Modest", "Mild", "Quiet", "Bashful", "Rash",
        "Calm", "Gentle", "Sassy", "Careful", "Quirky",
    )

    private val HIDDEN_POWER = listOf(
        "Fighting", "Flying", "Poison", "Ground", "Rock", "Bug", "Ghost", "Steel",
        "Fire", "Water", "Grass", "Electric", "Psychic", "Ice", "Dragon", "Dark",
    )

    private fun status(label: String): String = when (label) {
        "SLP" -> "slp"
        "FRZ" -> "frz"
        "BRN" -> "brn"
        "PAR" -> "par"
        "TOX" -> "tox"
        "PSN" -> "psn"
        else -> ""
    }

    private fun fromGame(xs: List<Int>?, default: Int): IntArray =
        IntArray(6) { xs?.getOrNull(GAME_STAT[it]) ?: default }

    /** The move names of [mon], Hidden Power with the type its IVs give ([ivs] in the game's stat order: each one's lowest bit). */
    private fun moves(pack: CalcPack, mon: MonView, ivs: List<Int>?): List<String> = mon.moves.mapNotNull { mv ->
        // By the ROM's id; a view without one (a preview's) by its name.
        val name = pack.moveName(mv.id) ?: pack.moveNamed(mv.name) ?: return@mapNotNull null
        if (name != "Hidden Power" || ivs == null || ivs.size < 6) return@mapNotNull name
        val bits = (0 until 6).sumOf { (ivs[it] and 1) shl it }
        "Hidden Power ${HIDDEN_POWER[bits * 15 / 63]}".takeIf { it in pack.moves } ?: name
    }

    private fun sameName(a: String, b: String): Boolean =
        a.filter { it.isLetterOrDigit() }.equals(b.filter { it.isLetterOrDigit() }, ignoreCase = true)

    /**
     * The ability [mon] has: the one the battle names ([liveAbility], the ROM's own spelling) where the pack knows
     * it, else its species' slot - the hidden one by its flag, the first or second by the personality's low bit.
     */
    private fun ability(pack: CalcPack, speciesId: Int, mon: MonView, liveAbility: String?): String {
        val slots = pack.abilities(speciesId)
        if (liveAbility != null) slots.firstOrNull { sameName(it, liveAbility) }?.let { return it }
        val hidden = mon.stats?.hiddenAbility == true
        return (if (hidden) slots.getOrNull(2) else null)
            ?: (if (mon.personality and 1L == 1L) slots.getOrNull(1) else null)
            ?: slots.firstOrNull().orEmpty()
    }

    /** The boosts a battler has (its stages), in the engine's order. */
    private fun boosts(battler: MonView?): IntArray =
        IntArray(6) { i -> if (BATTLE_STAT[i] < 0) 0 else battler?.statStages?.getOrNull(BATTLE_STAT[i])?.coerceIn(-6, 6) ?: 0 }

    /** A battler's six stats as the battle has them; null where they aren't read. */
    private fun battleStats(battler: MonView?): IntArray? {
        val s = battler?.battleStats?.takeIf { it.size >= 5 && battler.maxHp > 0 } ?: return null
        return IntArray(6) { i -> if (i == HP) battler.maxHp else s[BATTLE_STAT[i]] }
    }

    /**
     * One of the player's Pokémon: [mon] from the party or a box, and [battler], the same one as the battle has it
     * (HP, status, stat changes, a Mega's species) while it is out. Null = a species the pack doesn't have, or an egg.
     */
    fun player(pack: CalcPack, mon: MonView, battler: MonView? = null, liveAbility: String? = null): CalcMon? {
        if (mon.isEgg) return null
        val live = battler ?: mon
        val species = pack.speciesName(live.species)?.let { pack.species[it] }
            ?: pack.speciesName(mon.species)?.let { pack.species[it] } ?: return null
        val st = mon.stats
        val ivs = fromGame(st?.ivs, 31)
        val item = when {
            // A battler's item is what it holds now (a berry eaten is gone).
            battler?.heldItem != null -> pack.itemName(battler.heldItemId).orEmpty()
            else -> pack.itemName(mon.heldItemId).orEmpty()
        }
        return CalcMon(
            pack, species, level = live.level,
            gender = gender(mon), ability = ability(pack, live.species, mon, liveAbility), item = item,
            nature = NATURES.getOrElse(st?.nature ?: -1) { "Serious" },
            ivs = ivs, evs = fromGame(st?.evs, 0), boosts = boosts(battler),
            curHP = live.hp, status = status(live.status),
            moves = moves(pack, live.takeIf { it.moves.isNotEmpty() } ?: mon, st?.ivs),
            // The game's own numbers where it has them: a Mega's or a changed forme's in battle, the party's otherwise.
            statsOverride = battleStats(battler) ?: st?.stats?.takeIf { it.size >= 6 && battler == null && it[STAT_HP] > 0 }?.let { fromGame(it, 0) },
        )
    }

    private fun gender(mon: MonView): String = when (mon.genderSymbol) {
        1 -> "M"
        2 -> "F"
        3 -> "N"
        else -> ""
    }

    /**
     * A trainer's Pokémon from the pack, as [live] (the battle's foe, or the trainer's party entry) has it now:
     * its HP, status and stat changes on the set's own spread, ability, item and moves.
     */
    fun foe(pack: CalcPack, set: CalcSet, live: MonView? = null): CalcMon? {
        val species = live?.let { pack.speciesName(it.species) }?.let { pack.species[it] } ?: pack.species[set.species] ?: return null
        val base = CalcMon.of(pack, set) ?: return null
        val override = battleStats(live)
        // Its HP as a share of the set's own, so a foe whose live stats aren't read still shows the damage it took.
        val maxHp = override?.get(HP) ?: (if (species === base.species) base.maxHP() else null)
        val hp = when {
            live == null || live.maxHp <= 0 -> 0
            maxHp == null || maxHp == live.maxHp -> live.hp
            else -> ceil(live.hp.toDouble() * maxHp / live.maxHp).toInt()
        }
        val item = if (live?.heldItem != null) pack.itemName(live.heldItemId).orEmpty() else set.item
        return CalcMon(
            pack, species, level = live?.level?.takeIf { it > 0 } ?: set.level, gender = live?.let(::gender),
            ability = set.ability, item = item, nature = set.nature, ivs = set.ivs.copyOf(), evs = set.evs.copyOf(),
            boosts = boosts(live), curHP = hp, status = live?.let { status(it.status) }.orEmpty(), moves = set.moves,
            statsOverride = override,
        )
    }

    /** A foe the pack has no set for (a wild Pokémon, a trainer not listed): what the battle shows of it. */
    fun wild(pack: CalcPack, live: MonView, liveAbility: String? = null): CalcMon? {
        val species = pack.speciesName(live.species)?.let { pack.species[it] } ?: return null
        val st = live.stats
        return CalcMon(
            pack, species, level = live.level, gender = gender(live),
            ability = ability(pack, live.species, live, liveAbility),
            item = if (live.heldItem != null) pack.itemName(live.heldItemId).orEmpty() else "",
            nature = NATURES.getOrElse(st?.nature ?: -1) { "Serious" },
            ivs = fromGame(st?.ivs, 31), evs = fromGame(st?.evs, 0), boosts = boosts(live),
            curHP = live.hp, status = status(live.status), moves = moves(pack, live, st?.ivs),
            statsOverride = battleStats(live),
        )
    }

    /** The set of [variant] that [live] is: its species, or the species it Mega Evolved from. */
    fun setFor(pack: CalcPack, variant: CalcVariant, live: MonView, slot: Int = -1): CalcSet? {
        val name = pack.speciesName(live.species) ?: return null
        val base = pack.species[name]?.baseSpecies.orEmpty()
        fun fits(s: CalcSet) = s.species == name || (base.isNotEmpty() && (s.species == base || pack.species[s.species]?.baseSpecies == base)) ||
            pack.species[s.species]?.baseSpecies == name
        variant.sets.getOrNull(slot)?.takeIf(::fits)?.let { return it }
        return variant.sets.firstOrNull { it.species == name } ?: variant.sets.firstOrNull(::fits)
    }

    // FireRed's trainer flags: FLAG_TRAINER_FLAG_START + the trainer's id.
    private const val TRAINER_FLAGS = 0x500

    /** Whether the save has beaten [fight] (any of its teams). */
    fun beaten(fight: CalcFight, progress: SaveProgress?): Boolean =
        progress != null && fight.variants.any { v -> v.trainerIds.any { it in 1 until 0x300 && progress.flag(TRAINER_FLAGS + it) } }

    /** The fight the save is up to: the one after the last it has beaten. */
    fun nextFight(mode: CalcMode, progress: SaveProgress?): CalcFight? {
        val last = mode.fights.indexOfLast { beaten(it, progress) }
        return mode.fights.getOrNull(last + 1) ?: mode.fights.lastOrNull()
    }

    /** The field a fight is fought on, from its standing rule and, in a battle, whether it's a double one. */
    fun field(variant: CalcVariant?, snapshot: SnapshotView?): CalcField =
        fieldFor(variant, snapshot?.takeIf { it.inBattle }?.isDoubleBattle)
}
