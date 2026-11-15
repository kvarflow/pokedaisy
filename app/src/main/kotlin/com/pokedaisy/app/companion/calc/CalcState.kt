package com.pokedaisy.app.companion.calc

import kotlin.math.floor

/**
 * The engine's Pokémon, move and field: ports of the calculator's `Pokemon`, `Move`, `Field` and
 * `Side` (calc/src in kvarflow/rr-calc, a fork of smogon/damage-calc, MIT). They are mutable on
 * purpose: the mechanics change them as they go, exactly as the original does, so [CalcEngine]
 * always works on copies.
 */
class CalcMon(
    val pack: CalcPack,
    val species: CalcSpecies,
    val level: Int = 100,
    gender: String? = null,
    ability: String? = null,
    var abilityOn: Boolean = false,
    var item: String = "",
    val nature: String = "Serious",
    val ivs: IntArray = IntArray(6) { 31 },
    val evs: IntArray = IntArray(6),
    val boosts: IntArray = IntArray(6),
    /** The HP it has now; 0 or more than it can have = full. */
    curHP: Int = 0,
    /** "" / slp / psn / brn / frz (frostbite here) / par / tox. */
    var status: String = "",
    val toxicCounter: Int = 0,
    val alliesFainted: Int = 0,
    val isSaltCure: Boolean = false,
    /** Protosynthesis / Quark Drive: "auto" or a stat id; null = not active. */
    val boostedStat: String? = null,
    val moves: List<String> = emptyList(),
    /**
     * The six stats as the game has them, instead of working them out from [ivs] / [evs] / [nature]:
     * a live read is exact where the spread isn't known (a foe's).
     */
    val statsOverride: IntArray? = null,
) {
    val name: String get() = species.name
    var types: List<String> = species.types
    val weightKg: Double = if (species.weightKg == 0.0 && species.baseSpecies.isNotEmpty()) {
        pack.species[species.baseSpecies]?.weightKg ?: 0.0
    } else {
        species.weightKg
    }
    val gender: String = gender?.takeIf { it.isNotEmpty() } ?: species.gender.ifEmpty { "M" }
    var ability: String = ability?.takeIf { it.isNotEmpty() } ?: species.ability

    /** Before boosts. */
    val rawStats: IntArray = statsOverride?.copyOf() ?: IntArray(6) { calcStat(it) }

    /** After boosts and the rest: the mechanics fill these in. */
    val stats: IntArray = rawStats.copyOf()

    var originalCurHP: Int = if (curHP in 1..rawStats[HP]) curHP else rawStats[HP]

    fun maxHP(): Int = rawStats[HP]
    fun curHP(): Int = originalCurHP

    fun hasAbility(vararg abilities: String): Boolean = ability.isNotEmpty() && ability in abilities
    fun hasItem(vararg items: String): Boolean = item.isNotEmpty() && item in items
    fun hasStatus(vararg statuses: String): Boolean = status.isNotEmpty() && status in statuses
    fun hasType(vararg ts: String?): Boolean = ts.any { it != null && it in types }
    fun named(vararg names: String): Boolean = name in names

    private fun calcStat(stat: Int): Int {
        val base = species.baseStats[stat]
        val core = floor((base * 2 + ivs[stat] + evs[stat] / 4) * level / 100.0).toInt()
        if (stat == HP) return if (base == 1) 1 else core + level + 10
        val nat = pack.natures[nature]
        val n = when {
            nat == null || nat.plus == nat.minus -> 1.0
            nat.plus == stat -> 1.1
            nat.minus == stat -> 0.9
            else -> 1.0
        }
        return floor((core + 5) * n).toInt()
    }

    /** A fresh copy as the original's `clone()` gives: the mechanics' own changes to types and stats are not carried. */
    fun copy(): CalcMon = CalcMon(
        pack, species, level, gender, ability, abilityOn, item, nature, ivs.copyOf(), evs.copyOf(), boosts.copyOf(),
        originalCurHP, status, toxicCounter, alliesFainted, isSaltCure, boostedStat, moves, statsOverride,
    )

    companion object {
        /** A trainer's Pokémon. */
        fun of(pack: CalcPack, set: CalcSet): CalcMon? {
            val species = pack.species[set.species] ?: return null
            return CalcMon(
                pack, species, level = set.level, ability = set.ability, item = set.item, nature = set.nature,
                ivs = set.ivs.copyOf(), evs = set.evs.copyOf(), moves = set.moves,
            )
        }
    }
}

class CalcMove(
    val pack: CalcPack,
    val originalName: String,
    val useZ: Boolean = false,
    isCrit: Boolean = false,
    hits: Int = 0,
    timesUsed: Int = 0,
    val timesUsedWithMetronome: Int = 0,
    /** The user's ability and item, which decide how often a multi-hit move hits and which Z-Move it becomes. */
    val ability: String = "",
    val item: String = "",
) {
    val data: CalcMoveData
    val name: String
    var bp: Int
    var type: String
    var category: String
    var hits: Int = 1
    val flags: Set<String>
    var secondaries: Boolean
    var target: String
    val recoil: Boolean
    val hasCrashDamage: Boolean
    val isCrit: Boolean
    val drain: Boolean
    var priority: Int
    val dropsStats: Int
    val timesUsed: Int
    val ignoreDefensive: Boolean
    val overrideDefensiveStat: String
    val breaksProtect: Boolean
    val isZ: Boolean
    /** Uses in a row with a Metronome held; a Z-Move never counts them. */
    val metronomeUses: Int

    init {
        val base = pack.moves[originalName] ?: UNKNOWN.copyNamed(originalName)
        var d = base
        var power = base.basePower
        var cat = base.category
        var metronome = timesUsedWithMetronome
        if (useZ && base.zBasePower != 0) {
            metronome = 0
            val z = pack.moves[zMoveName(base.name, base.type, item)]
            if (z != null) {
                d = z
                power = if (z.basePower == 1) base.zBasePower else z.basePower
                cat = base.category
            }
        } else if (base.multihitMin != 0) {
            val fixed = base.multihitMin == base.multihitMax
            this.hits = when {
                base.multiaccuracy && fixed -> if (hits > 0) hits else base.multihitMin
                fixed -> base.multihitMin
                hits > 0 -> hits
                // Radical Red's Loaded Dice hits 5 times two times in three, so it takes the most, like Skill Link.
                ability == "Skill Link" || item == "Loaded Dice" -> base.multihitMax
                else -> base.multihitMin + 1
            }
        }
        metronomeUses = metronome
        data = d
        name = d.name
        bp = power
        // Struggle has a type, but the damage it deals has none.
        type = if (d.name == "Struggle") "???" else d.type
        category = cat
        val drop = if (category == "Special") d.selfSpaDrop else d.selfAtkDrop
        dropsStats = drop
        this.timesUsed = if (drop != 0 && timesUsed > 0) timesUsed else 1
        secondaries = d.secondaries
        target = d.target.ifEmpty { "any" }
        recoil = d.recoil
        hasCrashDamage = d.hasCrashDamage
        this.isCrit = isCrit || d.willCrit
        drain = d.drain
        flags = d.flags
        priority = d.priority
        ignoreDefensive = d.ignoreDefensive
        overrideDefensiveStat = d.overrideDefensiveStat
        breaksProtect = d.breaksProtect
        isZ = d.isZ
        // Full happiness, as the calculator assumes.
        if (bp == 0 && d.name in FULL_HAPPINESS) bp = 102
    }

    fun named(vararg names: String): Boolean = name in names
    fun hasType(vararg types: String?): Boolean = type in types
    fun flag(f: String): Boolean = f in flags

    fun copy(): CalcMove = CalcMove(
        pack, originalName, useZ, isCrit, hits, timesUsed, timesUsedWithMetronome, ability, item,
    )

    companion object {
        private val FULL_HAPPINESS = setOf("Return", "Frustration", "Pika Papow", "Veevee Volley")

        private val UNKNOWN = CalcMoveData(
            "", 0, "Normal", "Status", 0, "", emptySet(), false, false, false, 0, 0, false, 0, 0, false, false, "",
            false, false, 0, false,
        )

        private fun CalcMoveData.copyNamed(n: String) = CalcMoveData(
            n, basePower, type, category, priority, target, flags, secondaries, recoil, drain, multihitMin, multihitMax,
            multiaccuracy, selfAtkDrop, selfSpaDrop, willCrit, ignoreDefensive, overrideDefensiveStat, breaksProtect,
            isZ, zBasePower, hasCrashDamage, drainRatio,
        )

        private val Z_BY_TYPE = mapOf(
            "Bug" to "Savage Spin-Out", "Dark" to "Black Hole Eclipse", "Dragon" to "Devastating Drake",
            "Electric" to "Gigavolt Havoc", "Fairy" to "Twinkle Tackle", "Fighting" to "All-Out Pummeling",
            "Fire" to "Inferno Overdrive", "Flying" to "Supersonic Skystrike", "Ghost" to "Never-Ending Nightmare",
            "Grass" to "Bloom Doom", "Ground" to "Tectonic Rage", "Ice" to "Subzero Slammer",
            "Normal" to "Breakneck Blitz", "Poison" to "Acid Downpour", "Psychic" to "Shattered Psyche",
            "Rock" to "Continental Crush", "Steel" to "Corkscrew Crash", "Water" to "Hydro Vortex",
        )

        private val SIGNATURE_Z = mapOf(
            ("Clanging Scales" to "Kommonium Z") to "Clangorous Soulblaze",
            ("Darkest Lariat" to "Incinium Z") to "Malicious Moonsault",
            ("Giga Impact" to "Snorlium Z") to "Pulverizing Pancake",
            ("Moongeist Beam" to "Lunalium Z") to "Menacing Moonraze Maelstrom",
            ("Photon Geyser" to "Ultranecrozium Z") to "Light That Burns the Sky",
            ("Play Rough" to "Mimikium Z") to "Let's Snuggle Forever",
            ("Psychic" to "Mewnium Z") to "Genesis Supernova",
            ("Sparkling Aria" to "Primarium Z") to "Oceanic Operetta",
            ("Spectral Thief" to "Marshadium Z") to "Soul-Stealing 7-Star Strike",
            ("Spirit Shackle" to "Decidium Z") to "Sinister Arrow Raid",
            ("Stone Edge" to "Lycanium Z") to "Splintered Stormshards",
            ("Sunsteel Strike" to "Solganium Z") to "Searing Sunraze Smash",
            ("Volt Tackle" to "Pikanium Z") to "Catastropika",
            ("Nature's Madness" to "Tapunium Z") to "Guardian of Alola",
            ("Thunderbolt" to "Aloraichium Z") to "Stoked Sparksurfer",
            ("Thunderbolt" to "Pikashunium Z") to "10,000,000 Volt Thunderbolt",
        )

        /** The Z-Move [moveName] turns into while holding [item]. */
        fun zMoveName(moveName: String, moveType: String, item: String): String = when {
            moveName.contains("Hidden Power") -> "Breakneck Blitz"
            else -> SIGNATURE_Z[moveName to item] ?: Z_BY_TYPE[moveType].orEmpty()
        }
    }
}

class CalcSide(
    var spikes: Int = 0,
    var isSR: Boolean = false,
    var isReflect: Boolean = false,
    var isLightScreen: Boolean = false,
    var isAuroraVeil: Boolean = false,
    var isProtected: Boolean = false,
    var isSeeded: Boolean = false,
    var isForesight: Boolean = false,
    var isTailwind: Boolean = false,
    var isHelpingHand: Boolean = false,
    var isFlowerGift: Boolean = false,
    var isFriendGuard: Boolean = false,
    var isBattery: Boolean = false,
    var isPowerSpot: Boolean = false,
    /** The Pokémon on this side is switching out (Pursuit, Analytic). */
    var isSwitchingOut: Boolean = false,
) {
    fun copy(): CalcSide = CalcSide(
        spikes, isSR, isReflect, isLightScreen, isAuroraVeil, isProtected, isSeeded, isForesight, isTailwind,
        isHelpingHand, isFlowerGift, isFriendGuard, isBattery, isPowerSpot, isSwitchingOut,
    )
}

class CalcField(
    var doubles: Boolean = false,
    /** Sun / Rain / Sand / Hail / Snow / Harsh Sunshine / Heavy Rain / Strong Winds; null = none. */
    var weather: String? = null,
    /** Electric / Grassy / Misty / Psychic; null = none. */
    var terrain: String? = null,
    var isMagicRoom: Boolean = false,
    var isWonderRoom: Boolean = false,
    var isGravity: Boolean = false,
    var isAuraBreak: Boolean = false,
    var isFairyAura: Boolean = false,
    var isDarkAura: Boolean = false,
    var isBeadsOfRuin: Boolean = false,
    var isSwordOfRuin: Boolean = false,
    var isTabletsOfRuin: Boolean = false,
    var isVesselOfRuin: Boolean = false,
    var attackerSide: CalcSide = CalcSide(),
    var defenderSide: CalcSide = CalcSide(),
) {
    fun hasWeather(vararg ws: String): Boolean = weather != null && weather in ws
    fun hasTerrain(vararg ts: String): Boolean = terrain != null && terrain in ts

    fun copy(): CalcField = CalcField(
        doubles, weather, terrain, isMagicRoom, isWonderRoom, isGravity, isAuraBreak, isFairyAura, isDarkAura,
        isBeadsOfRuin, isSwordOfRuin, isTabletsOfRuin, isVesselOfRuin, attackerSide.copy(), defenderSide.copy(),
    )

    /** The same field seen from the other side. */
    fun swapped(): CalcField = copy().also {
        val a = it.attackerSide
        it.attackerSide = it.defenderSide
        it.defenderSide = a
    }
}

/**
 * What a move does: [damage] is one roll per entry (16 of them, or one for a move that always deals the
 * same), [childDamage] the second strike of Parental Bond and its like. [attacker] / [defender] are the
 * two as the mechanics left them (final Speed, boosts after Intimidate and so on).
 */
class CalcResult(
    val attacker: CalcMon,
    val defender: CalcMon,
    val move: CalcMove,
    val field: CalcField,
) {
    var damage: IntArray = intArrayOf(0)
    var childDamage: IntArray? = null

    val min: Int get() = damage.first() + (childDamage?.first() ?: 0)
    val max: Int get() = damage.last() + (childDamage?.last() ?: 0)

    /** [min] / [max] as tenths of a percent of the defender's full HP. */
    val minPermille: Int get() = min * 1000 / defender.maxHP().coerceAtLeast(1)
    val maxPermille: Int get() = max * 1000 / defender.maxHP().coerceAtLeast(1)
}
