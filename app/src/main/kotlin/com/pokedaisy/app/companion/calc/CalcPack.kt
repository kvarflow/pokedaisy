package com.pokedaisy.app.companion.calc

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/** Stat indexes, the order every six-stat array here uses. */
const val HP = 0
const val ATK = 1
const val DEF = 2
const val SPA = 3
const val SPD = 4
const val SPE = 5

internal val STAT_IDS = listOf("hp", "atk", "def", "spa", "spd", "spe")

class CalcSpecies(
    val name: String,
    val types: List<String>,
    val baseStats: IntArray,
    val weightKg: Double,
    /** The first ability, what a Pokémon gets when none is given. */
    val ability: String,
    /** Not fully evolved (Eviolite). */
    val nfe: Boolean,
    /** "M" / "F" / "N" where the species has only one; "" = either. */
    val gender: String,
    val baseSpecies: String,
    val otherFormes: List<String>,
)

class CalcMoveData(
    val name: String,
    val basePower: Int,
    val type: String,
    /** "Physical" / "Special" / "Status". */
    val category: String,
    val priority: Int,
    val target: String,
    val flags: Set<String>,
    val secondaries: Boolean,
    val recoil: Boolean,
    val drain: Boolean,
    /** 0 = hits once; equal = always that many; else a range. */
    val multihitMin: Int,
    val multihitMax: Int,
    val multiaccuracy: Boolean,
    /** Stages the user's Attack / Sp. Atk drop after it (Superpower, Overheat). */
    val selfAtkDrop: Int,
    val selfSpaDrop: Int,
    val willCrit: Boolean,
    val ignoreDefensive: Boolean,
    val overrideDefensiveStat: String,
    val breaksProtect: Boolean,
    val isZ: Boolean,
    /** The power its Z-Move has; 0 = none. */
    val zBasePower: Int,
    val hasCrashDamage: Boolean,
)

class CalcItem(
    val name: String,
    /** The species a Mega Stone evolves; "" = not one. */
    val megaEvolves: String,
    /** The type a plate / type item boosts. */
    val boostType: String?,
    /** The type a resist berry weakens. */
    val berryResistType: String?,
    val flingPower: Int,
    val naturalGiftType: String?,
    val naturalGiftPower: Int,
    val technoBlastType: String?,
)

class CalcNature(val name: String, val plus: Int, val minus: Int)

/** A trainer Pokémon as the game builds it. */
class CalcSet(
    val species: String,
    val name: String,
    val level: Int,
    val nature: String,
    val ability: String,
    val item: String,
    val moves: List<String>,
    val ivs: IntArray,
    val evs: IntArray,
) {
    val id: String get() = "$species ($name)"
}

/** One team a fight can have (the rival's by your starter, a leader's rain or snow team). */
class CalcVariant(
    val label: String,
    /** The fight's standing rule in the game's notes' words ("Doubles + Permanent rain"); "" = none. */
    val battleEffect: String,
    /** The ROM's trainer ids that are this team. */
    val trainerIds: List<Int>,
    val sets: List<CalcSet>,
)

/** A trainer in the order the game brings them. */
class CalcFight(
    val index: Int,
    val name: String,
    val location: String,
    /** The level cap this fight is met under; 0 = none (after the League). */
    val levelCap: Int,
    val optional: Boolean,
    /** A route trainer, not a boss. */
    val route: Boolean,
    /** The trainer's AI flags (CFRU: 1 avoids bad moves, 7 also looks for good ones). */
    val aiFlags: Int,
    val variants: List<CalcVariant>,
)

class CalcMode(val id: String, val fights: List<CalcFight>) {
    private val byTrainerId: Map<Int, Pair<CalcFight, CalcVariant>> = HashMap<Int, Pair<CalcFight, CalcVariant>>().apply {
        for (f in fights) for (v in f.variants) for (id in v.trainerIds) putIfAbsent(id, f to v)
    }

    /** The fight (and which of its teams) the ROM's trainer [id] is; null = not listed. */
    fun fightFor(id: Int): Pair<CalcFight, CalcVariant>? = byTrainerId[id]
}

/**
 * A game's tables for the CALC tab's engine, read from `assets/calc/<game>.pack.gz`
 * (scripts/gen_calc_pack.js writes it): gzipped text, one tab-separated record a line, the first cell
 * its kind - `T` a type's row of the chart, `N` a nature, `P` a species, `M` a move, `I` an item,
 * `XS` / `XM` / `XI` / `XA` the ROM's species / move / item ids and each species' ability slots,
 * then per `@mode` its sets (`S`) and fights (`F`, each followed by its teams, `V`).
 *
 * Everything is keyed by the calculator's names; the ROM ids map the companion's live reads onto
 * them. Nothing in here knows which game it is: another game is another pack.
 */
class CalcPack(
    val game: String,
    val gen: Int,
    val typeNames: List<String>,
    private val chart: Array<DoubleArray>,
    val natures: Map<String, CalcNature>,
    val species: Map<String, CalcSpecies>,
    val moves: Map<String, CalcMoveData>,
    val items: Map<String, CalcItem>,
    private val speciesIds: Map<Int, String>,
    private val moveIds: Map<Int, String>,
    private val itemIds: Map<Int, String>,
    private val abilitySlots: Map<Int, List<String>>,
    val modes: Map<String, CalcMode>,
) {
    private val typeIndex: Map<String, Int> = typeNames.withIndex().associate { it.value to it.index }

    /** How a move of type [attacking] hits the type [defending]: 0, 0.5, 1 or 2. */
    fun effectiveness(attacking: String, defending: String): Double {
        val a = typeIndex[attacking] ?: return 1.0
        val d = typeIndex[defending] ?: return 1.0
        return chart[a][d]
    }

    fun speciesName(romId: Int): String? = speciesIds[romId]
    fun moveName(romId: Int): String? = moveIds[romId]
    fun itemName(romId: Int): String? = itemIds[romId]

    /** The abilities the ROM's species [romId] can have: slot 1, slot 2, hidden. */
    fun abilities(romId: Int): List<String> = abilitySlots[romId].orEmpty()

    companion object {
        fun read(input: InputStream): CalcPack =
            BufferedReader(InputStreamReader(GZIPInputStream(input), Charsets.UTF_8)).use { parse(it.lineSequence()) }

        private fun stats(cell: String, order: IntArray): IntArray {
            val v = cell.split(',')
            return IntArray(6).also { out -> for (i in 0 until 6) out[order[i]] = v[i].toInt() }
        }

        /** The sets' spreads are in the site's order: hp, at, df, sa, sd, sp. */
        private val SET_ORDER = intArrayOf(HP, ATK, DEF, SPA, SPD, SPE)

        private fun items(cell: String): List<String> = if (cell.isEmpty()) emptyList() else cell.split('|')

        internal fun parse(lines: Sequence<String>): CalcPack {
            var game = ""
            var gen = 0
            var typeNames = emptyList<String>()
            val chart = ArrayList<DoubleArray>()
            val natures = HashMap<String, CalcNature>()
            val species = LinkedHashMap<String, CalcSpecies>()
            val moves = LinkedHashMap<String, CalcMoveData>()
            val items = LinkedHashMap<String, CalcItem>()
            val speciesIds = HashMap<Int, String>()
            val moveIds = HashMap<Int, String>()
            val itemIds = HashMap<Int, String>()
            val abilitySlots = HashMap<Int, List<String>>()
            val modes = LinkedHashMap<String, CalcMode>()

            var modeId: String? = null
            var sets = HashMap<String, CalcSet>()
            var fights = ArrayList<CalcFight>()
            var variants: ArrayList<CalcVariant>? = null
            fun endMode() {
                modeId?.let { modes[it] = CalcMode(it, fights) }
            }

            for (line in lines) {
                if (line.isEmpty()) continue
                val c = line.split('\t')
                when (c[0]) {
                    "CALCPACK" -> {
                        require(c[1] == "1") { "CALC pack version ${c[1]}" }
                        game = c[2]
                        gen = c[3].toInt()
                    }
                    "@types" -> typeNames = c.drop(1)
                    "T" -> chart += DoubleArray(typeNames.size) { c[2 + it].toDouble() }
                    "N" -> natures[c[1]] = CalcNature(c[1], STAT_IDS.indexOf(c[2]), STAT_IDS.indexOf(c[3]))
                    "P" -> species[c[1]] = CalcSpecies(
                        name = c[1],
                        types = listOf(c[2], c[3]).filter { it.isNotEmpty() },
                        baseStats = IntArray(6) { c[4 + it].toInt() },
                        weightKg = c[10].toDouble(),
                        ability = c[11],
                        nfe = c[12] == "1",
                        gender = c[13],
                        baseSpecies = c[14],
                        otherFormes = items(c.getOrElse(15) { "" }),
                    )
                    "M" -> moves[c[1]] = CalcMoveData(
                        name = c[1],
                        basePower = c[2].toInt(),
                        type = c[3],
                        category = c[4],
                        priority = c[5].toInt(),
                        target = c[6],
                        flags = items(c[7]).toHashSet(),
                        secondaries = c[8] == "1",
                        recoil = c[9] == "1",
                        drain = c[10] == "1",
                        multihitMin = c[11].toInt(),
                        multihitMax = c[12].toInt(),
                        multiaccuracy = c[13] == "1",
                        selfAtkDrop = c[14].toInt(),
                        selfSpaDrop = c[15].toInt(),
                        willCrit = c[16] == "1",
                        ignoreDefensive = c[17] == "1",
                        overrideDefensiveStat = c[18],
                        breaksProtect = c[19] == "1",
                        isZ = c[20] == "1",
                        zBasePower = c[21].toInt(),
                        hasCrashDamage = c[22] == "1",
                    )
                    "I" -> items[c[1]] = CalcItem(
                        name = c[1],
                        megaEvolves = c[2],
                        boostType = c[3].ifEmpty { null },
                        berryResistType = c[4].ifEmpty { null },
                        flingPower = c[5].toInt(),
                        naturalGiftType = c[6].ifEmpty { null },
                        naturalGiftPower = c[7].toInt(),
                        technoBlastType = c[8].ifEmpty { null },
                    )
                    "XS" -> speciesIds[c[1].toInt()] = c[2]
                    "XM" -> moveIds[c[1].toInt()] = c[2]
                    "XI" -> itemIds[c[1].toInt()] = c[2]
                    "XA" -> abilitySlots[c[1].toInt()] = items(c[2])
                    "@mode" -> {
                        endMode()
                        modeId = c[1]
                        sets = HashMap()
                        fights = ArrayList()
                        variants = null
                    }
                    "S" -> CalcSet(
                        species = c[1], name = c[2], level = c[3].toInt(), nature = c[4], ability = c[5], item = c[6],
                        moves = items(c[7]), ivs = stats(c[8], SET_ORDER), evs = stats(c[9], SET_ORDER),
                    ).let { sets[it.id] = it }
                    "F" -> {
                        val v = ArrayList<CalcVariant>()
                        variants = v
                        fights += CalcFight(
                            index = fights.size, name = c[1], location = c[2], levelCap = c[3].toIntOrNull() ?: 0,
                            optional = c[4] == "1", route = c[5] == "1", aiFlags = c[6].toInt(), variants = v,
                        )
                    }
                    "V" -> variants?.add(
                        CalcVariant(
                            label = c[1],
                            battleEffect = c[2],
                            trainerIds = c[3].split(',').mapNotNull { it.toIntOrNull() },
                            sets = items(c[4]).mapNotNull { sets[it] },
                        ),
                    )
                }
            }
            endMode()
            return CalcPack(
                game, gen, typeNames, chart.toTypedArray(), natures, species, moves, items,
                speciesIds, moveIds, itemIds, abilitySlots, modes,
            )
        }
    }
}
