package com.pokedaisy.app.companion.calc

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.truncate

/**
 * The damage formula of the seventh to ninth generations with Radical Red's changes: a port of
 * `calculateSMSSSV` (calc/src/mechanics/gen789.ts and util.ts in kvarflow/rr-calc, a fork of
 * smogon/damage-calc, MIT), kept line for line so the two can be compared - `CalcParityTest`
 * replays thousands of the original's results. What the original only does for its description text
 * is left out, as are Dynamax and Terastallization (not in this game).
 *
 * Numbers are doubles where the original's are fractions, so every rounding lands the same way.
 */
object CalcEngine {
    /** What [move] does used by [attacker] on [defender]. None of the three, nor [field], is changed. */
    fun calculate(attacker: CalcMon, defender: CalcMon, move: CalcMove, field: CalcField = CalcField()): CalcResult =
        run(attacker.copy(), defender.copy(), move.copy(), field.copy())

    // #region helpers (mechanics/util.ts)

    private fun of16(n: Double): Double = if (n > 65535) n % 65536 else n
    private fun of32(n: Double): Double = if (n > 4294967295.0) n % 4294967296.0 else n

    /** The games round a half down. */
    private fun pokeRound(n: Double): Double = if (n % 1 > 0.5) ceil(n) else floor(n)

    private fun chainMods(mods: List<Int>, lower: Int, upper: Int): Int {
        var m = 4096
        for (mod in mods) if (mod != 4096) m = (m * mod + 2048) shr 12
        return max(min(m, upper), lower)
    }

    private val BOOST_NUM = intArrayOf(2, 2, 2, 2, 2, 2, 2, 3, 4, 5, 6, 7, 8)
    private val BOOST_DEN = intArrayOf(8, 7, 6, 5, 4, 3, 2, 2, 2, 2, 2, 2, 2)

    internal fun modifiedStat(stat: Int, boost: Int): Int {
        val s = of16((stat * BOOST_NUM[6 + boost]).toDouble())
        return floor(s / BOOST_DEN[6 + boost]).toInt()
    }

    private val EV_ITEMS = arrayOf(
        "Macho Brace", "Power Anklet", "Power Band", "Power Belt", "Power Bracer", "Power Lens", "Power Weight",
    )

    private fun isGrounded(p: CalcMon, field: CalcField): Boolean =
        field.isGravity || p.hasItem("Iron Ball") ||
            (!p.hasType("Flying") && !p.hasAbility("Levitate") && !p.hasItem("Air Balloon"))

    private fun isQPActive(p: CalcMon, field: CalcField): Boolean {
        val boosted = p.boostedStat ?: return false
        val weather = field.weather.orEmpty()
        return (p.hasAbility("Protosynthesis") && (weather.contains("Sun") || p.hasItem("Booster Energy"))) ||
            (p.hasAbility("Quark Drive") && (field.terrain == "Electric" || p.hasItem("Booster Energy"))) ||
            boosted != "auto"
    }

    private fun qpBoostedStat(p: CalcMon): Int {
        val boosted = p.boostedStat
        if (boosted != null && boosted != "auto") return STAT_IDS.indexOf(boosted)
        var best = ATK
        for (stat in intArrayOf(DEF, SPA, SPD, SPE)) {
            if (modifiedStat(p.rawStats[stat], p.boosts[stat]) > modifiedStat(p.rawStats[best], p.boosts[best])) best = stat
        }
        return best
    }

    internal fun finalSpeed(p: CalcMon, field: CalcField, side: CalcSide): Int {
        val weather = field.weather.orEmpty()
        var speed = modifiedStat(p.rawStats[SPE], p.boosts[SPE]).toDouble()
        val mods = ArrayList<Int>()
        if (side.isTailwind) mods += 8192
        if ((p.hasAbility("Unburden") && p.abilityOn) ||
            (p.hasAbility("Chlorophyll") && weather.contains("Sun")) ||
            (p.hasAbility("Sand Rush") && weather == "Sand") ||
            (p.hasAbility("Swift Swim") && weather.contains("Rain")) ||
            (p.hasAbility("Slush Rush") && (weather == "Hail" || weather == "Snow")) ||
            (p.hasAbility("Surge Surfer") && field.terrain == "Electric")
        ) {
            mods += 8192
        } else if ((p.named("Cherrim") && p.hasAbility("Flower Gift") && weather.contains("Sun")) ||
            (p.hasAbility("Bull Rush", "Quill Rush") && p.abilityOn)
        ) {
            mods += 6144
        } else if (p.hasAbility("Quick Feet") && p.status.isNotEmpty()) {
            mods += 8192
        } else if (p.hasAbility("Slow Start") && p.abilityOn) {
            mods += 2048
        } else if (isQPActive(p, field) && qpBoostedStat(p) == SPE) {
            mods += 6144
        }

        if (p.hasItem("Choice Scarf") ||
            (p.named("Farfetch’d", "Farfetch’d-Galar") && p.hasItem("Leek")) ||
            (p.named("Porygon-Z") && p.hasItem("Up-Grade"))
        ) {
            mods += 6144
        } else if (p.hasItem("Iron Ball", *EV_ITEMS)) {
            mods += 2048
        } else if (p.hasItem("Quick Powder") && p.named("Ditto")) {
            mods += 8192
        }

        speed = of32(pokeRound(speed * chainMods(mods, 410, 131172) / 4096))
        if (p.hasStatus("par") && !p.hasAbility("Quick Feet")) speed = floor(of32(speed * 50) / 100)
        return max(0.0, min(10000.0, speed)).toInt()
    }

    private fun computeFinalStats(attacker: CalcMon, defender: CalcMon, field: CalcField, vararg stats: Int) {
        for ((p, side) in listOf(attacker to field.attackerSide, defender to field.defenderSide)) {
            for (stat in stats) {
                p.stats[stat] = if (stat == SPE) finalSpeed(p, field, side) else modifiedStat(p.rawStats[stat], p.boosts[stat])
            }
        }
    }

    private fun effectiveness(
        pack: CalcPack, move: CalcMove, type: String, ghostRevealed: Boolean, gravity: Boolean, ringTarget: Boolean,
        boneZone: Boolean, corrosion: Boolean,
    ): Double = when {
        (ringTarget || ghostRevealed) && type == "Ghost" && move.hasType("Normal", "Fighting") -> 1.0
        (ringTarget || gravity) && type == "Flying" && move.hasType("Ground") -> 1.0
        corrosion && type == "Steel" && move.hasType("Poison") -> 1.0
        move.named("Freeze-Dry") && type == "Water" -> 2.0
        move.named("Flying Press") -> pack.effectiveness("Fighting", type) * pack.effectiveness("Flying", type)
        move.named("Draco Barrage") && type == "Fairy" -> 1.0
        else -> {
            val e = pack.effectiveness(move.type, type)
            if (e == 0.0 && boneZone && move.flag("bone")) 1.0 else e
        }
    }

    private fun checkAirLock(p: CalcMon, field: CalcField) {
        if (p.hasAbility("Air Lock", "Cloud Nine")) field.weather = null
    }

    private fun checkTeraformZero(p: CalcMon, field: CalcField) {
        if (p.hasAbility("Teraform Zero") && p.abilityOn) {
            field.weather = null
            field.terrain = null
        }
    }

    private fun checkForecast(p: CalcMon, weather: String?) {
        if (p.hasAbility("Forecast") && p.named("Castform")) {
            p.types = when (weather) {
                "Sun", "Harsh Sunshine" -> listOf("Fire")
                "Rain", "Heavy Rain" -> listOf("Water")
                "Hail", "Snow" -> listOf("Ice")
                else -> listOf("Normal")
            }
        }
    }

    private fun checkItem(p: CalcMon, magicRoom: Boolean) {
        if ((p.hasAbility("Klutz") && p.item !in EV_ITEMS) || magicRoom) p.item = ""
    }

    private fun checkWonderRoom(p: CalcMon, wonderRoom: Boolean) {
        if (wonderRoom) {
            val def = p.rawStats[DEF]
            p.rawStats[DEF] = p.rawStats[SPD]
            p.rawStats[SPD] = def
        }
    }

    private fun checkSeedBoost(p: CalcMon, field: CalcField) {
        if (p.item.isEmpty()) return
        if (field.terrain != null && p.item.contains("Seed")) {
            val seed = p.item.substringBefore(' ')
            if (p.item.indexOf(' ') >= 0 && field.hasTerrain(seed)) {
                val stat = if (seed == "Grassy" || seed == "Electric") DEF else SPD
                p.boosts[stat] = if (p.hasAbility("Contrary")) max(-6, p.boosts[stat] - 1) else min(6, p.boosts[stat] + 1)
                p.item = ""
            }
        }
    }

    private fun checkEmbody(p: CalcMon) {
        when (p.ability) {
            "Embody Aspect (Cornerstone)" -> p.boosts[DEF] = min(6, p.boosts[DEF] + 1)
            "Embody Aspect (Hearthflame)" -> p.boosts[ATK] = min(6, p.boosts[ATK] + 1)
            "Embody Aspect (Teal)" -> p.boosts[SPE] = min(6, p.boosts[SPE] + 1)
            "Embody Aspect (Wellspring)" -> p.boosts[SPD] = min(6, p.boosts[SPD] + 1)
        }
    }

    private fun checkIntimidate(source: CalcMon, target: CalcMon) {
        val blocked = target.hasAbility("Clear Body", "White Smoke", "Hyper Cutter", "Full Metal Body") ||
            target.hasAbility("Inner Focus", "Own Tempo", "Oblivious", "Scrappy") ||
            target.hasItem("Clear Amulet")
        if (source.hasAbility("Intimidate") && source.abilityOn && !blocked) {
            target.boosts[ATK] = when {
                target.hasAbility("Contrary", "Defiant", "Guard Dog") -> min(6, target.boosts[ATK] + 1)
                target.hasAbility("Simple") -> max(-6, target.boosts[ATK] - 2)
                else -> max(-6, target.boosts[ATK] - 1)
            }
            if (target.hasAbility("Competitive")) target.boosts[SPA] = min(6, target.boosts[SPA] + 2)
        }
    }

    private fun checkDownload(source: CalcMon, target: CalcMon, wonderRoom: Boolean) {
        if (source.hasAbility("Download")) {
            var def = target.stats[DEF]
            var spd = target.stats[SPD]
            // Download ignores Wonder Room.
            if (wonderRoom) def = spd.also { spd = def }
            if (spd <= def) source.boosts[SPA] = min(6, source.boosts[SPA] + 1) else source.boosts[ATK] = min(6, source.boosts[ATK] + 1)
        }
    }

    private fun checkInfiltrator(p: CalcMon, side: CalcSide) {
        if (p.hasAbility("Infiltrator")) {
            side.isReflect = false
            side.isLightScreen = false
            side.isAuroraVeil = false
        }
    }

    /** The guaranteed changes between the hits of one move. [used] = whether the attacker's / the defender's item is spent. */
    private fun checkMultihitBoost(
        attacker: CalcMon, defender: CalcMon, move: CalcMove, field: CalcField, used: BooleanArray = BooleanArray(2),
    ): BooleanArray {
        var attackerUsedItem = used[0]
        var defenderUsedItem = used[1]
        if (move.named("Gyro Ball", "Electro Ball") && defender.hasAbility("Gooey", "Tangling Hair")) {
            if (attacker.hasItem("White Herb") && !attackerUsedItem) {
                attackerUsedItem = true
            } else {
                attacker.boosts[SPE] = max(attacker.boosts[SPE] - 1, -6)
                attacker.stats[SPE] = finalSpeed(attacker, field, field.attackerSide)
            }
        } else if (move.named("Power-Up Punch")) {
            attacker.boosts[ATK] = min(attacker.boosts[ATK] + 1, 6)
            attacker.stats[ATK] = modifiedStat(attacker.rawStats[ATK], attacker.boosts[ATK])
        }

        val atkSimple = if (attacker.hasAbility("Simple")) 2 else 1
        val defSimple = if (defender.hasAbility("Simple")) 2 else 1

        if (!defenderUsedItem &&
            ((defender.hasItem("Luminous Moss") && move.hasType("Water")) ||
                (defender.hasItem("Maranga Berry") && move.category == "Special") ||
                (defender.hasItem("Kee Berry") && move.category == "Physical"))
        ) {
            val stat = if (defender.hasItem("Kee Berry")) DEF else SPD
            if (!attacker.hasAbility("Unaware")) {
                if (defender.hasAbility("Contrary")) {
                    if (defender.hasItem("White Herb") && !defenderUsedItem) {
                        defenderUsedItem = true
                    } else {
                        defender.boosts[stat] = max(-6, defender.boosts[stat] - defSimple)
                    }
                } else {
                    defender.boosts[stat] = min(6, defender.boosts[stat] + defSimple)
                }
                defender.stats[stat] = modifiedStat(defender.rawStats[stat], defender.boosts[stat])
                defenderUsedItem = true
            }
        }

        if (defender.hasAbility("Seed Sower")) field.terrain = "Grassy"
        if (defender.hasAbility("Sand Spit")) field.weather = "Sand"

        if (defender.hasAbility("Stamina")) {
            if (!attacker.hasAbility("Unaware")) {
                defender.boosts[DEF] = min(defender.boosts[DEF] + 1, 6)
                defender.stats[DEF] = modifiedStat(defender.rawStats[DEF], defender.boosts[DEF])
            }
        } else if (defender.hasAbility("Water Compaction") && move.hasType("Water")) {
            if (!attacker.hasAbility("Unaware")) {
                defender.boosts[DEF] = min(defender.boosts[DEF] + 2, 6)
                defender.stats[DEF] = modifiedStat(defender.rawStats[DEF], defender.boosts[DEF])
            }
        } else if (defender.hasAbility("Weak Armor")) {
            if (!attacker.hasAbility("Unaware")) {
                if (defender.hasItem("White Herb") && !defenderUsedItem && defender.boosts[DEF] == 0) {
                    defenderUsedItem = true
                } else {
                    defender.boosts[DEF] = max(defender.boosts[DEF] - 1, -6)
                    defender.stats[DEF] = modifiedStat(defender.rawStats[DEF], defender.boosts[DEF])
                }
            }
            defender.boosts[SPE] = min(defender.boosts[SPE] + 2, 6)
            defender.stats[SPE] = finalSpeed(defender, field, field.defenderSide)
        }

        if (move.dropsStats != 0) {
            if (!attacker.hasAbility("Unaware")) {
                val stat = if (move.category == "Special") SPA else ATK
                var boosts = attacker.boosts[stat]
                boosts = if (attacker.hasAbility("Contrary")) min(6, boosts + move.dropsStats) else max(-6, boosts - move.dropsStats * atkSimple)
                if (attacker.hasItem("White Herb") && attacker.boosts[stat] < 0 && !attackerUsedItem) {
                    boosts += move.dropsStats * atkSimple
                    attackerUsedItem = true
                }
                attacker.boosts[stat] = boosts
                attacker.stats[stat] = modifiedStat(attacker.rawStats[stat], attacker.boosts[stat])
            }
        }

        // The ability swap comes after everything else.
        if (defender.hasAbility("Mummy", "Wandering Spirit", "Lingering Aroma") && move.flag("contact")) {
            val old = attacker.ability
            attacker.ability = defender.ability
            if (defender.hasAbility("Wandering Spirit")) defender.ability = old
        }
        return booleanArrayOf(attackerUsedItem, defenderUsedItem)
    }

    private fun baseDamage(level: Int, basePower: Double, attack: Double, defense: Double): Double =
        floor(of32(floor(of32(of32(floor(2.0 * level / 5 + 2) * basePower) * attack) / defense) / 50 + 2))

    private fun finalDamage(
        base: Double, i: Int, effectiveness: Double, halved: Boolean, stabMod: Int, finalMod: Int, protect: Boolean,
    ): Int {
        var d = floor(of32(base * (85 + i)) / 100)
        // A STAB of 1 is skipped: applying it could get the overflow wrong.
        if (stabMod != 4096) d = of32(d * stabMod) / 4096
        d = floor(of32(pokeRound(d) * effectiveness))
        if (halved) d = floor(d / 2)
        if (protect) d = pokeRound(of32(d * 1024) / 4096)
        return of16(pokeRound(max(1.0, of32(d * finalMod) / 4096))).toInt()
    }

    private fun weight(p: CalcMon): Double {
        var hg = p.weightKg * 10
        val factor = if (p.hasAbility("Heavy Metal")) 2.0 else if (p.hasAbility("Light Metal")) 0.5 else 1.0
        if (factor != 1.0) hg = max(truncate(hg * factor), 1.0)
        if (p.hasItem("Float Stone")) hg = max(truncate(hg * 0.5), 1.0)
        return hg / 10
    }

    private fun stabMod(p: CalcMon, move: CalcMove): Int {
        var mod = 4096
        if (p.hasType(move.type)) mod += 2048 else if (p.hasAbility("Protean", "Libero")) mod += 2048
        if (p.hasAbility("Adaptability") && p.hasType(move.type)) mod += 2048
        return mod
    }

    private fun countBoosts(boosts: IntArray): Int {
        var sum = 0
        for (stat in ATK..SPE) if (boosts[stat] > 0) sum += boosts[stat]
        return sum
    }

    private fun fixedDamage(attacker: CalcMon, move: CalcMove): Int = when {
        move.named("Seismic Toss", "Night Shade") -> attacker.level
        move.named("Dragon Rage") -> 40
        move.named("Sonic Boom") -> 20
        else -> 0
    }

    // #endregion

    private fun run(attacker: CalcMon, defender: CalcMon, move: CalcMove, field: CalcField): CalcResult {
        val pack = attacker.pack

        // #region Initial

        checkAirLock(attacker, field)
        checkAirLock(defender, field)
        checkTeraformZero(attacker, field)
        checkTeraformZero(defender, field)
        checkForecast(attacker, field.weather)
        checkForecast(defender, field.weather)
        checkItem(attacker, field.isMagicRoom)
        checkItem(defender, field.isMagicRoom)
        checkWonderRoom(attacker, field.isWonderRoom)
        checkWonderRoom(defender, field.isWonderRoom)
        checkSeedBoost(attacker, field)
        checkSeedBoost(defender, field)
        if (attacker.hasAbility("Dauntless Shield")) attacker.boosts[DEF] = min(6, attacker.boosts[DEF] + 1)
        if (defender.hasAbility("Dauntless Shield")) defender.boosts[DEF] = min(6, defender.boosts[DEF] + 1)
        checkEmbody(attacker)
        checkEmbody(defender)

        computeFinalStats(attacker, defender, field, DEF, SPD, SPE)

        checkIntimidate(attacker, defender)
        checkIntimidate(defender, attacker)
        checkDownload(attacker, defender, field.isWonderRoom)
        checkDownload(defender, attacker, field.isWonderRoom)
        if (attacker.hasAbility("Intrepid Sword")) attacker.boosts[ATK] = min(6, attacker.boosts[ATK] + 1)
        if (defender.hasAbility("Intrepid Sword")) defender.boosts[ATK] = min(6, defender.boosts[ATK] + 1)

        if (attacker.hasAbility("Wind Rider") && field.attackerSide.isTailwind) attacker.boosts[ATK] = min(6, attacker.boosts[ATK] + 1)
        if (defender.hasAbility("Wind Rider") && field.defenderSide.isTailwind) defender.boosts[ATK] = min(6, defender.boosts[ATK] + 1)

        if (move.named("Meteor Beam", "Electro Shot")) {
            attacker.boosts[SPA] += if (attacker.hasAbility("Simple")) 2 else if (attacker.hasAbility("Contrary")) -1 else 1
            attacker.boosts[SPA] = min(6, max(-6, attacker.boosts[SPA]))
        }

        computeFinalStats(attacker, defender, field, ATK, SPA)

        checkInfiltrator(attacker, field.defenderSide)
        checkInfiltrator(defender, field.attackerSide)

        val result = CalcResult(attacker, defender, move, field)

        if (move.category == "Status" && !move.named("Nature Power")) return result

        val breaksProtect = move.breaksProtect || move.isZ || (attacker.hasAbility("Unseen Fist") && move.flag("contact"))
        if (field.defenderSide.isProtected && !breaksProtect) return result

        val defenderIgnoresAbility = defender.hasAbility(
            "Full Metal Body", "Neutralizing Gas", "Prism Armor", "Shadow Shield", "Primal Armor",
        )
        val attackerIgnoresAbility = attacker.hasAbility("Mold Breaker", "Teravolt", "Turboblaze")
        val moveIgnoresAbility = move.named(
            "G-Max Drum Solo", "G-Max Fire Ball", "G-Max Hydrosnipe", "Light That Burns the Sky",
            "Menacing Moonraze Maelstrom", "Moongeist Beam", "Photon Geyser", "Searing Sunraze Smash",
            "Sunsteel Strike", "Shell Side Arm",
        )
        if (!defenderIgnoresAbility && !defender.hasAbility("Poison Heal") && (attackerIgnoresAbility || moveIgnoresAbility)) {
            if (!defender.hasItem("Ability Shield")) defender.ability = ""
        }

        // Merciless doesn't get past Shell Armor.
        val isCritical = !defender.hasAbility("Battle Armor", "Shell Armor") &&
            (move.isCrit || (attacker.hasAbility("Merciless") && defender.hasStatus("psn", "tox"))) &&
            move.timesUsed == 1

        var type = move.type
        if (move.named("Weather Ball")) {
            val umbrella = attacker.hasItem("Utility Umbrella")
            type = when {
                field.hasWeather("Sun", "Harsh Sunshine") && !umbrella -> "Fire"
                field.hasWeather("Rain", "Heavy Rain") && !umbrella -> "Water"
                field.hasWeather("Sand") -> "Rock"
                field.hasWeather("Hail", "Snow") -> "Ice"
                else -> "Normal"
            }
        } else if (move.named("Judgment") && attacker.item.contains("Plate")) {
            type = pack.items[attacker.item]?.boostType ?: type
        } else if (move.named("Techno Blast") && attacker.item.contains("Drive")) {
            type = pack.items[attacker.item]?.technoBlastType ?: type
        } else if (move.named("Natural Gift") && attacker.item.contains("Berry")) {
            type = pack.items[attacker.item]?.naturalGiftType ?: type
        } else if (move.named("Nature Power") || (move.named("Terrain Pulse") && isGrounded(attacker, field))) {
            type = when {
                field.hasTerrain("Electric") -> "Electric"
                field.hasTerrain("Grassy") -> "Grass"
                field.hasTerrain("Misty") -> "Fairy"
                field.hasTerrain("Psychic") -> "Psychic"
                else -> "Normal"
            }
        } else if (move.named("Revelation Dance", "Multi-Attack", "Raging Bull")) {
            type = attacker.types[0]
        } else if (move.named("Aura Wheel")) {
            if (attacker.named("Morpeko")) type = "Electric" else if (attacker.named("Morpeko-Hangry")) type = "Dark"
        } else if (move.named("Ivy Cudgel")) {
            if (attacker.name.contains("Ogerpon-Cornerstone")) {
                type = "Rock"
            } else if (attacker.name.contains("Ogerpon-Hearthflame")) {
                type = "Fire"
            } else if (attacker.name.contains("Ogerpon-Wellspring")) {
                type = "Water"
            }
        }

        var hasAteAbilityTypeChange = false
        val noTypeChange = move.named(
            "Revelation Dance", "Judgment", "Nature Power", "Techno Blast", "Multi-Attack", "Natural Gift",
            "Weather Ball", "Terrain Pulse", "Raging Bull", "Struggle",
        )
        if (!move.isZ && !noTypeChange) {
            val normal = move.hasType("Normal")
            when {
                attacker.hasAbility("Aerilate") && normal -> { type = "Flying"; hasAteAbilityTypeChange = true }
                attacker.hasAbility("Galvanize") && normal -> { type = "Electric"; hasAteAbilityTypeChange = true }
                attacker.hasAbility("Liquid Voice") && move.flag("sound") -> type = "Water"
                attacker.hasAbility("Pixilate") && normal -> { type = "Fairy"; hasAteAbilityTypeChange = true }
                attacker.hasAbility("Refrigerate") && normal -> { type = "Ice"; hasAteAbilityTypeChange = true }
                // Normalize boosts any type.
                attacker.hasAbility("Normalize") -> { type = "Normal"; hasAteAbilityTypeChange = true }
            }
        }
        move.type = type

        if ((attacker.hasAbility("Triage") && move.drain) ||
            (attacker.hasAbility("Gale Wings") && move.hasType("Flying") && attacker.curHP() == attacker.maxHP()) ||
            (attacker.hasAbility("Flaming Soul") && move.hasType("Fire") && attacker.curHP() == attacker.maxHP())
        ) {
            move.priority = 1
        }

        val ghostRevealed = attacker.hasAbility("Scrappy") || attacker.hasAbility("Mind's Eye") || field.defenderSide.isForesight
        val ringTarget = defender.hasItem("Ring Target") && !defender.hasAbility("Klutz")
        val boneZone = attacker.hasAbility("Bone Zone")
        val corrosion = attacker.hasAbility("Corrosion")
        fun eff(t: String) = effectiveness(pack, move, t, ghostRevealed, field.isGravity, ringTarget, boneZone, corrosion)
        var typeEffectiveness = eff(defender.types[0]) * (defender.types.getOrNull(1)?.let { eff(it) } ?: 1.0)

        if (typeEffectiveness == 0.0 && move.hasType("Ground") && defender.hasItem("Iron Ball") && !defender.hasAbility("Klutz")) {
            typeEffectiveness = 1.0
        }
        if (typeEffectiveness == 0.0 && move.named("Thousand Arrows")) typeEffectiveness = 1.0
        if (typeEffectiveness == 0.0) return result

        if ((move.named("Sky Drop") && (defender.hasType("Flying") || defender.weightKg >= 200 || field.isGravity)) ||
            (move.named("Synchronoise") && !defender.hasType(attacker.types[0]) &&
                (attacker.types.size < 2 || !defender.hasType(attacker.types[1]))) ||
            (move.named("Dream Eater") && !(defender.hasStatus("slp") || defender.hasAbility("Comatose"))) ||
            (move.named("Steel Roller") && field.terrain == null) ||
            (move.named("Poltergeist") && (defender.item.isEmpty() || isQPActive(defender, field)))
        ) {
            return result
        }

        if ((field.hasWeather("Harsh Sunshine") && move.hasType("Water") && !move.named("Hydro Steam")) ||
            (field.hasWeather("Heavy Rain") && move.hasType("Fire"))
        ) {
            return result
        }

        if (field.hasWeather("Strong Winds") && defender.hasType("Flying") && pack.effectiveness(move.type, "Flying") > 1) {
            typeEffectiveness /= 2
        }

        val turn2TypeEffectiveness = typeEffectiveness

        // Tera Shell works only at full HP, but for every hit of a multi-hit move.
        if (defender.hasAbility("Tera Shell") && defender.curHP() == defender.maxHP() &&
            ((!field.defenderSide.isSR && (field.defenderSide.spikes == 0 || defender.hasType("Flying"))) ||
                defender.hasItem("Heavy-Duty Boots"))
        ) {
            typeEffectiveness = 0.5
        }

        if ((defender.hasAbility("Wonder Guard") && typeEffectiveness <= 1) ||
            (move.hasType("Grass") && defender.hasAbility("Sap Sipper")) ||
            (move.hasType("Fire") && defender.hasAbility("Flash Fire", "Well-Baked Body")) ||
            (move.hasType("Water") && defender.hasAbility("Dry Skin", "Storm Drain", "Water Absorb")) ||
            (move.hasType("Rock") && defender.hasAbility("Mountaineer")) ||
            (move.hasType("Electric") && defender.hasAbility("Lightning Rod", "Motor Drive", "Volt Absorb")) ||
            (move.hasType("Ground") && !field.isGravity && !move.named("Thousand Arrows") &&
                !defender.hasItem("Iron Ball") && defender.hasAbility("Levitate") &&
                !(attacker.hasAbility("Bone Zone") && move.flag("bone"))) ||
            (move.flag("bullet") && defender.hasAbility("Bulletproof")) ||
            (move.flag("sound") && !move.named("Clangorous Soul") && defender.hasAbility("Soundproof")) ||
            (move.priority > 0 && defender.hasAbility("Queenly Majesty", "Dazzling", "Armor Tail")) ||
            (move.hasType("Ground") && defender.hasAbility("Earth Eater")) ||
            (move.flag("wind") && defender.hasAbility("Wind Rider"))
        ) {
            return result
        }

        if (move.hasType("Ground") && !move.named("Thousand Arrows") &&
            !(attacker.hasAbility("Bone Zone") && move.flag("bone")) &&
            !field.isGravity && defender.hasItem("Air Balloon")
        ) {
            return result
        }

        if (move.priority > 0 && field.hasTerrain("Psychic") && isGrounded(defender, field)) return result

        val fixed = fixedDamage(attacker, move)
        if (fixed != 0) {
            result.damage = intArrayOf(fixed)
            if (attacker.hasAbility("Parental Bond", "ORAORAORAORA")) result.childDamage = intArrayOf(fixed)
            return result
        }

        if (move.named("Final Gambit")) {
            result.damage = intArrayOf(attacker.curHP())
            return result
        }

        if (move.named("Guardian of Alola")) {
            var lost = floor(defender.curHP() * 3 / 4.0)
            if (field.defenderSide.isProtected && attacker.item.contains(" Z")) lost = ceil(lost / 4 - 0.5)
            result.damage = intArrayOf(lost.toInt())
            return result
        }

        if (move.named("Nature's Madness")) {
            result.damage = intArrayOf(if (field.defenderSide.isProtected) 0 else defender.curHP() / 2)
            return result
        }

        if (move.named("Spectral Thief", "Soul Robbery")) {
            for (stat in HP..SPE) {
                if (defender.boosts[stat] > 0) {
                    attacker.boosts[stat] += if (attacker.hasAbility("Contrary")) -defender.boosts[stat] else defender.boosts[stat]
                    if (attacker.boosts[stat] > 6) attacker.boosts[stat] = 6
                    if (attacker.boosts[stat] < -6) attacker.boosts[stat] = -6
                    attacker.stats[stat] = modifiedStat(attacker.rawStats[stat], attacker.boosts[stat])
                    defender.boosts[stat] = 0
                    defender.stats[stat] = defender.rawStats[stat]
                }
            }
        }

        // #endregion

        val basePower = basePower(attacker, defender, move, field, hasAteAbilityTypeChange, 1)
        if (basePower == 0.0) return result

        val attack = attack(attacker, defender, move, field, isCritical)
        val defense = defense(attacker, defender, move, field, isCritical)
        val base = baseDamage(attacker, defender, basePower, attack, defense, move, field, isCritical)

        // The random factor goes between the critical hit and STAB, so nothing below is applied outside the loop.
        var stab = stabMod(attacker, move)

        val applyBurn = attacker.hasStatus("brn") && move.category == "Physical" &&
            !attacker.hasAbility("Guts") && !move.named("Facade")
        // Radical Red's frostbite: a burn for special moves.
        val applyFrostbite = attacker.hasStatus("frz") && move.category == "Special" && !move.named("Facade")
        val halved = applyBurn || applyFrostbite
        val finalMod = chainMods(finalMods(attacker, defender, move, field, isCritical, typeEffectiveness, 0), 41, 131072)

        val protect = field.defenderSide.isProtected && move.isZ && attacker.item.contains(" Z")

        val isSpread = field.doubles && (move.target == "allAdjacent" || move.target == "allAdjacentFoes")

        var childDamage: IntArray? = null
        val childAbility = when {
            attacker.hasAbility("Parental Bond") && move.hits == 1 && !isSpread -> "Parental Bond (Child)"
            attacker.hasAbility("ORAORAORAORA") && move.hits == 1 && move.flag("punch") && !isSpread -> "ORAORAORAORA (Child)"
            else -> null
        }
        if (childAbility != null) {
            val child = attacker.copy()
            child.ability = childAbility
            checkMultihitBoost(child, defender, move, field)
            // A second strike that does nothing isn't one.
            childDamage = run(child, defender, move, field).damage.takeIf { it.size == 16 }
        }

        var damage = IntArray(16) { finalDamage(base, it, typeEffectiveness, halved, stab, finalMod, protect) }

        if ((move.dropsStats != 0 && move.timesUsed > 1) || move.hits > 1) {
            val turns = move.dropsStats != 0 && move.timesUsed > 1
            val numAttacks = if (turns) move.timesUsed else move.hits
            var usedItems = BooleanArray(2)
            for (times in 1 until numAttacks) {
                usedItems = checkMultihitBoost(attacker, defender, move, field, usedItems)
                val newAttack = attack(attacker, defender, move, field, isCritical)
                val newDefense = defense(attacker, defender, move, field, isCritical)
                // A lost -ate ability keeps the type and loses the boost.
                hasAteAbilityTypeChange = hasAteAbilityTypeChange &&
                    attacker.hasAbility("Aerilate", "Galvanize", "Pixilate", "Refrigerate", "Normalize")
                if (turns) {
                    // Adaptability doesn't change between the hits of one move, only between turns.
                    stab = stabMod(attacker, move)
                    typeEffectiveness = turn2TypeEffectiveness
                }
                val newBasePower = basePower(attacker, defender, move, field, hasAteAbilityTypeChange, times + 1)
                val newBase = baseDamage(attacker, defender, newBasePower, newAttack, newDefense, move, field, isCritical)
                val newFinalMod = chainMods(finalMods(attacker, defender, move, field, isCritical, typeEffectiveness, times), 41, 131072)
                damage = IntArray(16) {
                    damage[it] + finalDamage(newBase, it, typeEffectiveness, halved, stab, newFinalMod, protect)
                }
            }
        }

        result.damage = damage
        result.childDamage = childDamage
        return result
    }

    private fun basePower(
        attacker: CalcMon, defender: CalcMon, move: CalcMove, field: CalcField, hasAteAbilityTypeChange: Boolean, hit: Int,
    ): Double {
        val pack = attacker.pack
        val turnOrderFirst = attacker.stats[SPE] > defender.stats[SPE]
        val bp = move.bp.toDouble()

        var basePower: Double = when (move.name) {
            "Payback" -> bp * (if (!turnOrderFirst) 2 else 1)
            "Bolt Beak", "Fishious Rend" -> bp * (if (turnOrderFirst) 2 else 1)
            "Pursuit" -> bp * (if (field.defenderSide.isSwitchingOut) 2 else 1)
            "Electro Ball" -> {
                if (defender.stats[SPE] == 0) {
                    40.0
                } else {
                    val r = attacker.stats[SPE] / defender.stats[SPE]
                    if (r >= 4) 150.0 else if (r >= 3) 120.0 else if (r >= 2) 80.0 else if (r >= 1) 60.0 else 40.0
                }
            }
            "Gyro Ball" ->
                if (attacker.stats[SPE] == 0) 1.0 else min(150.0, floor(25.0 * defender.stats[SPE] / attacker.stats[SPE]) + 1)
            "Sonic Slash" -> {
                // Against a Speed of 0 the original divides by zero: the top power.
                val r = if (defender.stats[SPE] == 0) (if (attacker.stats[SPE] == 0) 0 else 3) else attacker.stats[SPE] / defender.stats[SPE]
                if (r >= 3) 140.0 else if (r >= 2) 120.0 else 80.0
            }
            "Punishment" -> min(200.0, 60.0 + 20 * countBoosts(defender.boosts))
            "Low Kick", "Grass Knot" -> {
                val w = weight(defender)
                if (w >= 200) 120.0 else if (w >= 100) 100.0 else if (w >= 50) 80.0 else if (w >= 25) 60.0 else if (w >= 10) 40.0 else 20.0
            }
            // Radical Red: all of them double against any status, as Hex does.
            "Hex", "Barb Barrage", "Bitter Malice", "Infernal Parade" ->
                bp * (if (defender.status.isNotEmpty() || defender.hasAbility("Comatose")) 2 else 1)
            "Heavy Slam", "Heat Crash" -> {
                val wr = weight(attacker) / weight(defender)
                if (wr >= 5) 120.0 else if (wr >= 4) 100.0 else if (wr >= 3) 80.0 else if (wr >= 2) 60.0 else 40.0
            }
            "Stored Power", "Power Trip" -> 20.0 + 20 * countBoosts(attacker.boosts)
            "Acrobatics" ->
                bp * (if (attacker.hasItem("Flying Gem") || attacker.item.isEmpty() || isQPActive(attacker, field)) 2 else 1)
            "Assurance" -> bp * (if (defender.hasAbility("Parental Bond (Child)", "ORAORAORAORA (Child)")) 2 else 1)
            "Wake-Up Slap" -> bp * (if (defender.hasStatus("slp") || defender.hasAbility("Comatose")) 2 else 1)
            "Smelling Salts" -> bp * (if (defender.hasStatus("par")) 2 else 1)
            "Weather Ball" -> {
                if (field.hasWeather("Sun", "Harsh Sunshine", "Rain", "Heavy Rain") && attacker.hasItem("Utility Umbrella")) {
                    bp
                } else {
                    bp * (if (field.weather != null && !field.hasWeather("Strong Winds")) 2 else 1)
                }
            }
            "Terrain Pulse" -> bp * (if (isGrounded(attacker, field) && field.terrain != null) 2 else 1)
            "Rising Voltage" -> bp * (if (isGrounded(defender, field) && field.hasTerrain("Electric")) 2 else 1)
            "Psyblade" -> bp * (if (field.hasTerrain("Electric")) 1.5 else 1.0)
            "Fling" -> (pack.items[attacker.item]?.flingPower ?: 0).toDouble()
            "Dragon Energy", "Eruption", "Water Spout" -> max(1.0, floor(150.0 * attacker.curHP() / attacker.maxHP()))
            "Flail", "Reversal" -> {
                val p = 48 * attacker.curHP() / attacker.maxHP()
                if (p <= 1) 200.0 else if (p <= 4) 150.0 else if (p <= 9) 100.0 else if (p <= 16) 80.0 else if (p <= 32) 40.0 else 20.0
            }
            "Natural Gift" ->
                if (attacker.item.endsWith("Berry")) (pack.items[attacker.item]?.naturalGiftPower ?: 0).toDouble() else bp
            "Nature Power" -> {
                move.category = "Special"
                move.secondaries = true
                if (attacker.hasAbility("Prankster") && "Dark" in defender.types) {
                    0.0
                } else {
                    when (field.terrain) {
                        "Electric", "Grassy" -> 90.0
                        "Misty" -> 95.0
                        // Prankster's Nature Power doesn't reach a grounded target on Psychic Terrain.
                        "Psychic" -> if (attacker.hasAbility("Prankster") && isGrounded(defender, field)) 0.0 else 90.0
                        else -> 80.0
                    }
                }
            }
            "Water Shuriken" -> if (attacker.named("Greninja-Ash") && attacker.hasAbility("Battle Bond")) 20.0 else 15.0
            "Triple Axel", "Triple Kick" -> hit * 20.0
            "Crush Grip", "Wring Out" -> {
                val x = 100 * floor(defender.curHP() * 4096.0 / defender.maxHP())
                floor(floor((120 * x + 2048 - 1) / 4096) / 100).let { if (it == 0.0) 1.0 else it }
            }
            "Hard Press" -> {
                val x = 100 * floor(defender.curHP() * 4096.0 / defender.maxHP())
                floor(floor((100 * x + 2048 - 1) / 4096) / 100).let { if (it == 0.0) 1.0 else it }
            }
            else -> bp
        }
        if (basePower == 0.0) return 0.0
        val mods = bpMods(attacker, defender, move, field, basePower, hasAteAbilityTypeChange, turnOrderFirst)
        basePower = of16(max(1.0, pokeRound(basePower * chainMods(mods, 41, 2097152) / 4096)))
        return basePower
    }

    private fun bpMods(
        attacker: CalcMon, defender: CalcMon, move: CalcMove, field: CalcField, basePower: Double,
        hasAteAbilityTypeChange: Boolean, turnOrderFirst: Boolean,
    ): List<Int> {
        val pack = attacker.pack
        val mods = ArrayList<Int>()

        // Move effects

        var resistedKnockOff = defender.item.isEmpty() || isQPActive(defender, field) ||
            (defender.named("Dialga-Origin") && defender.hasItem("Adamant Crystal")) ||
            (defender.named("Palkia-Origin") && defender.hasItem("Lustrous Globe")) ||
            (defender.name.contains("Giratina-Origin") && defender.item.contains("Griseous")) ||
            (defender.name.contains("Arceus") && defender.item.contains("Plate")) ||
            (defender.name.contains("Genesect") && defender.item.contains("Drive")) ||
            (defender.named("Groudon", "Groudon-Primal") && defender.hasItem("Red Orb")) ||
            (defender.named("Kyogre", "Kyogre-Primal") && defender.hasItem("Blue Orb")) ||
            (defender.name.contains("Silvally") && defender.item.contains("Memory")) ||
            defender.item.contains(" Z") ||
            (defender.named("Zacian") && defender.hasItem("Rusted Sword")) ||
            (defender.named("Zamazenta") && defender.hasItem("Rusted Shield")) ||
            (defender.name.contains("Ogerpon-Cornerstone") && defender.hasItem("Cornerstone Mask")) ||
            (defender.name.contains("Ogerpon-Hearthflame") && defender.hasItem("Hearthflame Mask")) ||
            (defender.name.contains("Ogerpon-Wellspring") && defender.hasItem("Wellspring Mask")) ||
            (defender.named("Venomicon-Epilogue") && defender.hasItem("Vile Vial"))

        // A Mega Stone that fits its holder can't be knocked off.
        if (!resistedKnockOff && defender.item.isNotEmpty()) {
            val mega = pack.items[defender.item]?.megaEvolves.orEmpty()
            resistedKnockOff = mega.isNotEmpty() && defender.name.contains(mega)
        }

        if ((move.named("Facade") && attacker.hasStatus("brn", "par", "psn", "tox", "frz")) ||
            (move.named("Brine") && defender.curHP() <= defender.maxHP() / 2.0) ||
            (move.named("Venoshock") && defender.hasStatus("psn", "tox")) ||
            (move.named("Lash Out") && countBoosts(attacker.boosts) < 0)
        ) {
            mods += 8192
        } else if (move.named("Expanding Force") && isGrounded(attacker, field) && field.hasTerrain("Psychic")) {
            move.target = "allAdjacentFoes"
            mods += 6144
        } else if ((move.named("Knock Off") && !resistedKnockOff) ||
            (move.named("Misty Explosion") && isGrounded(attacker, field) && field.hasTerrain("Misty")) ||
            (move.named("Grav Apple") && field.isGravity)
        ) {
            mods += 6144
        } else if (move.named("Solar Beam", "Solar Blade") && field.hasWeather("Rain", "Heavy Rain", "Sand", "Hail", "Snow")) {
            mods += 2048
        } else if (move.named("Collision Course", "Electro Drift")) {
            val ghostRevealed = attacker.hasAbility("Scrappy") || attacker.hasAbility("Mind's Eye") || field.defenderSide.isForesight
            val ringTarget = defender.hasItem("Ring Target") && !defender.hasAbility("Klutz")
            val boneZone = attacker.hasAbility("Bone Zone")
            val corrosion = attacker.hasAbility("Corrosion")
            fun eff(t: String) = effectiveness(pack, move, t, ghostRevealed, field.isGravity, ringTarget, boneZone, corrosion)
            if (eff(defender.types[0]) * (defender.types.getOrNull(1)?.let { eff(it) } ?: 1.0) >= 2) mods += 5461
        }

        if (field.attackerSide.isHelpingHand) mods += 6144

        // Field effects

        if (isGrounded(attacker, field)) {
            if ((field.hasTerrain("Electric") && move.hasType("Electric")) ||
                (field.hasTerrain("Grassy") && move.hasType("Grass")) ||
                (field.hasTerrain("Psychic") && move.hasType("Psychic"))
            ) {
                mods += 5325
            }
        }
        if (isGrounded(defender, field)) {
            if ((field.hasTerrain("Misty") && move.hasType("Dragon")) ||
                (field.hasTerrain("Grassy") && move.named("Bulldoze", "Earthquake"))
            ) {
                mods += 2048
            }
        }

        // Abilities

        // Technician looks at the power after the moves that set their own.
        if ((attacker.hasAbility("Technician") && basePower <= 60) ||
            (attacker.hasAbility("Flare Boost") && attacker.hasStatus("brn") && move.category == "Special") ||
            (attacker.hasAbility("Toxic Boost") && attacker.hasStatus("psn", "tox") && move.category == "Physical") ||
            (attacker.hasAbility("Mega Launcher") && move.flag("pulse")) ||
            (attacker.hasAbility("Strong Jaw") && move.flag("bite")) ||
            (attacker.hasAbility("Steely Spirit") && move.hasType("Steel")) ||
            (attacker.hasAbility("Sharpness") && move.flag("slicing"))
        ) {
            mods += 6144
        }

        val aura = "${move.type} Aura"
        val attackerAura = attacker.hasAbility(aura)
        val defenderAura = defender.hasAbility(aura)
        val auraActive = attackerAura || defenderAura ||
            (field.isFairyAura && move.type == "Fairy") || (field.isDarkAura && move.type == "Dark")
        val auraBreak = field.isAuraBreak || attacker.hasAbility("Aura Break") || defender.hasAbility("Aura Break")
        if (auraActive) mods += if (auraBreak) 3072 else 5448

        if ((attacker.hasAbility("Sheer Force") && (move.secondaries || move.named("Jet Punch", "Order Up"))) ||
            (attacker.hasAbility("Sand Force") && field.hasWeather("Sand") && move.hasType("Rock", "Ground", "Steel")) ||
            (attacker.hasAbility("Analytic") && (!turnOrderFirst || field.defenderSide.isSwitchingOut)) ||
            (attacker.hasAbility("Tough Claws") && move.flag("contact")) ||
            (attacker.hasAbility("Punk Rock") && move.flag("sound")) ||
            (attacker.hasAbility("Iron Fist") && move.flag("punch")) ||
            (attacker.hasAbility("Striker") && move.flag("kick")) ||
            (attacker.hasAbility("Illusion") && attacker.abilityOn)
        ) {
            mods += 5325
        }

        if (field.attackerSide.isBattery && move.category == "Special") mods += 5325
        if (field.attackerSide.isPowerSpot) mods += 5325

        if (attacker.hasAbility("Rivalry") && attacker.gender != "N" && defender.gender != "N") {
            mods += if (attacker.gender == defender.gender) 5120 else 3072
        }

        // The -ate abilities changed the type earlier.
        if (hasAteAbilityTypeChange) mods += 4915

        if ((attacker.hasAbility("Reckless") &&
                (move.recoil || move.hasCrashDamage || move.named("Explosion", "Misty Explosion", "Self-Destruct"))) ||
            (attacker.hasAbility("Liquid Voice") && move.flag("sound"))
        ) {
            mods += 4915
        }

        if (attacker.hasItem("Punching Glove") && move.flag("punch")) mods += 4506

        if (defender.hasAbility("Dry Skin") && move.hasType("Fire")) mods += 5120

        if (attacker.hasAbility("Supreme Overlord") && attacker.alliesFainted > 0) {
            mods += intArrayOf(4096, 4506, 4915, 5325, 5734, 6144)[min(5, attacker.alliesFainted)]
        }

        // Items

        val boostType = if (attacker.item.isNotEmpty()) pack.items[attacker.item]?.boostType else null
        if (attacker.hasItem("${move.type} Gem") ||
            (attacker.named("Electivire") && attacker.hasItem("Electirizer") && move.hasType("Fighting")) ||
            (attacker.named("Sunflora") && attacker.hasItem("Burnt Seed") && move.hasType("Fire"))
        ) {
            mods += 6144
        } else if (
            (((attacker.hasItem("Adamant Crystal") && attacker.named("Dialga-Origin")) ||
                (attacker.hasItem("Adamant Orb") && attacker.named("Dialga"))) && move.hasType("Steel", "Dragon")) ||
            (((attacker.hasItem("Lustrous Orb") && attacker.named("Palkia")) ||
                (attacker.hasItem("Lustrous Globe") && attacker.named("Palkia-Origin"))) && move.hasType("Water", "Dragon")) ||
            ((attacker.hasItem("Griseous Orb") || attacker.hasItem("Griseous Core")) &&
                (attacker.named("Giratina-Origin") || attacker.named("Giratina")) && move.hasType("Ghost", "Dragon")) ||
            (attacker.hasItem("Vile Vial") && attacker.named("Venomicon-Epilogue") && move.hasType("Poison", "Flying")) ||
            (attacker.hasItem("Soul Dew") && attacker.named("Latios", "Latias", "Latios-Mega", "Latias-Mega") &&
                move.hasType("Psychic", "Dragon")) ||
            (boostType != null && move.hasType(boostType)) ||
            (attacker.name.contains("Ogerpon-Cornerstone") && attacker.hasItem("Cornerstone Mask")) ||
            (attacker.name.contains("Ogerpon-Hearthflame") && attacker.hasItem("Hearthflame Mask")) ||
            (attacker.name.contains("Ogerpon-Wellspring") && attacker.hasItem("Wellspring Mask"))
        ) {
            mods += 4915
        } else if ((attacker.hasItem("Muscle Band") && move.category == "Physical") ||
            (attacker.hasItem("Wise Glasses") && move.category == "Special")
        ) {
            mods += 4505
        }
        return mods
    }

    private fun attack(attacker: CalcMon, defender: CalcMon, move: CalcMove, field: CalcField, isCritical: Boolean): Double {
        val source = if (move.named("Foul Play")) defender else attacker
        if (move.named("Photon Geyser", "Light That Burns the Sky")) {
            move.category = if (source.stats[ATK] > source.stats[SPA]) "Physical" else "Special"
        }
        val stat = if (move.named("Body Press")) DEF else if (move.category == "Special") SPA else ATK
        var attack: Double = if (source.boosts[stat] == 0 || (isCritical && source.boosts[stat] < 0)) {
            source.rawStats[stat].toDouble()
        } else if (defender.hasAbility("Unaware")) {
            source.rawStats[stat].toDouble()
        } else {
            modifiedStat(source.rawStats[stat], source.boosts[stat]).toDouble()
        }

        // Unlike every other attack modifier, Hustle is applied directly.
        if (attacker.hasAbility("Hustle") && move.category == "Physical") attack = pokeRound(attack * 3 / 2)
        val mods = atMods(attacker, defender, move, field)
        return of16(max(1.0, pokeRound(attack * chainMods(mods, 410, 131072) / 4096)))
    }

    private fun atMods(attacker: CalcMon, defender: CalcMon, move: CalcMove, field: CalcField): List<Int> {
        val mods = ArrayList<Int>()
        val physical = move.category == "Physical"
        val special = move.category == "Special"
        val sun = field.hasWeather("Sun", "Harsh Sunshine")

        // Slow Start also halves special Z-Moves.
        if ((attacker.hasAbility("Slow Start") && attacker.abilityOn && (physical || (special && move.isZ))) ||
            (attacker.hasAbility("Defeatist") && attacker.curHP() <= attacker.maxHP() / 3.0)
        ) {
            mods += 2048
        } else if ((attacker.hasAbility("Solar Power") && sun && special) ||
            (attacker.named("Cherrim") && attacker.hasAbility("Flower Gift") && sun && physical)
        ) {
            mods += 6144
        } else if ((attacker.hasAbility("Gorilla Tactics") && physical) || (attacker.hasAbility("Sage Power") && special)) {
            mods += 6144
        } else if (field.attackerSide.isFlowerGift && sun && physical) {
            mods += 6144
        } else if ((attacker.hasAbility("Guts") && attacker.status.isNotEmpty() && physical) ||
            (attacker.curHP() <= attacker.maxHP() / 3.0 &&
                ((attacker.hasAbility("Overgrow") && move.hasType("Grass")) ||
                    (attacker.hasAbility("Blaze") && move.hasType("Fire")) ||
                    (attacker.hasAbility("Torrent") && move.hasType("Water")) ||
                    (attacker.hasAbility("Swarm") && move.hasType("Bug")))) ||
            (special && attacker.abilityOn && attacker.hasAbility("Plus", "Minus"))
        ) {
            mods += 6144
        } else if (attacker.hasAbility("Flash Fire") && attacker.abilityOn && move.hasType("Fire")) {
            mods += 6144
        } else if ((attacker.hasAbility("Steelworker") && move.hasType("Steel")) ||
            (attacker.hasAbility("Dragon's Maw") && move.hasType("Dragon")) ||
            (attacker.hasAbility("Rocky Payload") && move.hasType("Rock"))
        ) {
            mods += 6144
        } else if (attacker.hasAbility("Transistor") && move.hasType("Electric")) {
            mods += 5325
        } else if (attacker.hasAbility("Stakeout") && attacker.abilityOn) {
            mods += 8192
        } else if ((attacker.hasAbility("Water Bubble", "Cash Splash") && move.hasType("Water")) ||
            (attacker.hasAbility("Huge Power", "Pure Power") && physical) ||
            (attacker.hasAbility("Feline Prowess") && special)
        ) {
            mods += 8192
        }

        if ((defender.hasAbility("Thick Fat") && move.hasType("Fire", "Ice")) ||
            (defender.hasAbility("Water Bubble", "Cash Splash") && move.hasType("Fire")) ||
            (defender.hasAbility("Purifying Salt") && move.hasType("Ghost")) ||
            (defender.hasAbility("Water Compaction") && move.hasType("Water"))
        ) {
            mods += 2048
        }

        if (defender.hasAbility("Heatproof") && move.hasType("Fire")) mods += 2048

        // A Pokémon with an "of Ruin" ability isn't touched by the same one.
        val tablets = (defender.hasAbility("Tablets of Ruin") || field.isTabletsOfRuin) && !attacker.hasAbility("Tablets of Ruin")
        val vessel = (defender.hasAbility("Vessel of Ruin") || field.isVesselOfRuin) && !attacker.hasAbility("Vessel of Ruin")
        if ((tablets && physical) || (vessel && special)) mods += 3072

        if (isQPActive(attacker, field)) {
            val boosted = qpBoostedStat(attacker)
            if ((physical && boosted == ATK) || (special && boosted == SPA)) mods += 5325
        }

        if ((attacker.hasAbility("Hadron Engine") && special && field.hasTerrain("Electric") && isGrounded(attacker, field)) ||
            (attacker.hasAbility("Orichalcum Pulse") && physical && sun && !attacker.hasItem("Utility Umbrella"))
        ) {
            mods += 5461
        }

        if (attacker.hasAbility("Bull Rush", "Quill Rush") && physical && attacker.abilityOn) mods += 4915

        if ((attacker.hasItem("Thick Club") && attacker.named("Cubone", "Marowak", "Marowak-Alola", "Marowak-Alola-Totem") && physical) ||
            (attacker.hasItem("Deep Sea Tooth") && attacker.named("Clamperl") && special) ||
            (attacker.hasItem("Light Ball") && attacker.name.contains("Pikachu") && !move.isZ)
        ) {
            mods += 8192
        } else if (!move.isZ && ((attacker.hasItem("Choice Band") && physical) || (attacker.hasItem("Choice Specs") && special))) {
            mods += 6144
        }
        return mods
    }

    private fun defense(attacker: CalcMon, defender: CalcMon, move: CalcMove, field: CalcField, isCritical: Boolean): Double {
        val hitsPhysical = move.overrideDefensiveStat == "def" || move.category == "Physical"
        val stat = if (hitsPhysical) DEF else SPD
        var defense: Double = if (defender.boosts[stat] == 0 || (isCritical && defender.boosts[stat] > 0) || move.ignoreDefensive) {
            defender.rawStats[stat].toDouble()
        } else if (attacker.hasAbility("Unaware")) {
            defender.rawStats[stat].toDouble()
        } else {
            modifiedStat(defender.rawStats[stat], defender.boosts[stat]).toDouble()
        }

        // Unlike every other defense modifier, the weather's boosts are applied directly.
        if (field.hasWeather("Sand") && defender.hasType("Rock") && !hitsPhysical) defense = pokeRound(defense * 3 / 2)
        if (field.hasWeather("Snow") && defender.hasType("Ice") && hitsPhysical) defense = pokeRound(defense * 3 / 2)

        // Radical Red (CFRU's OLD_EXPLOSION_BOOST): the exploding moves halve the target's defence.
        if (move.named("Explosion", "Self-Destruct", "Misty Explosion")) defense = pokeRound(defense / 2)

        val mods = dfMods(attacker, defender, field, hitsPhysical)
        return of16(max(1.0, pokeRound(defense * chainMods(mods, 410, 131072) / 4096)))
    }

    private fun dfMods(attacker: CalcMon, defender: CalcMon, field: CalcField, hitsPhysical: Boolean): List<Int> {
        val mods = ArrayList<Int>()
        if (defender.hasAbility("Marvel Scale") && defender.status.isNotEmpty() && hitsPhysical) {
            mods += 6144
        } else if (defender.hasAbility("Grass Pelt") && field.hasTerrain("Grassy") && hitsPhysical) {
            mods += 6144
        } else if (defender.hasAbility("Fur Coat") && hitsPhysical) {
            mods += 8192
        }
        val sword = (attacker.hasAbility("Sword of Ruin") || field.isSwordOfRuin) && !defender.hasAbility("Sword of Ruin")
        val beads = (attacker.hasAbility("Beads of Ruin") || field.isBeadsOfRuin) && !defender.hasAbility("Beads of Ruin")
        if ((sword && hitsPhysical) || (beads && !hitsPhysical)) mods += 3072

        if (isQPActive(defender, field)) {
            val boosted = qpBoostedStat(defender)
            if ((hitsPhysical && boosted == DEF) || (!hitsPhysical && boosted == SPD)) mods += 5324
        }

        if ((defender.hasItem("Eviolite") && defender.pack.species[defender.name]?.nfe == true) ||
            (!hitsPhysical && defender.hasItem("Assault Vest"))
        ) {
            mods += 6144
        } else if ((defender.hasItem("Metal Powder") && defender.named("Ditto") && hitsPhysical) ||
            (defender.hasItem("Deep Sea Scale") && defender.named("Clamperl") && !hitsPhysical)
        ) {
            mods += 8192
        }
        return mods
    }

    private fun baseDamage(
        attacker: CalcMon, defender: CalcMon, basePower: Double, attack: Double, defense: Double, move: CalcMove,
        field: CalcField, isCritical: Boolean,
    ): Double {
        var base = baseDamage(attacker.level, basePower, attack, defense)
        val isSpread = field.doubles && (move.target == "allAdjacent" || move.target == "allAdjacentFoes")
        if (isSpread) base = pokeRound(of32(base * 3072) / 4096)

        if (attacker.hasAbility("Parental Bond (Child)")) base = pokeRound(of32(base * 1024) / 4096)
        if (attacker.hasAbility("ORAORAORAORA (Child)")) base = pokeRound(of32(base * 2048) / 4096)

        if (field.hasWeather("Sun") && move.named("Hydro Steam") && !attacker.hasItem("Utility Umbrella")) {
            base = pokeRound(of32(base * 6144) / 4096)
        } else if (!defender.hasItem("Utility Umbrella")) {
            if ((field.hasWeather("Sun", "Harsh Sunshine") && move.hasType("Fire")) ||
                (field.hasWeather("Rain", "Heavy Rain") && move.hasType("Water"))
            ) {
                base = pokeRound(of32(base * 6144) / 4096)
            } else if ((field.hasWeather("Sun") && move.hasType("Water")) || (field.hasWeather("Rain") && move.hasType("Fire"))) {
                base = pokeRound(of32(base * 2048) / 4096)
            }
        }

        if (isCritical) base = floor(of32(base * 1.5))
        return base
    }

    private fun finalMods(
        attacker: CalcMon, defender: CalcMon, move: CalcMove, field: CalcField, isCritical: Boolean,
        typeEffectiveness: Double, hitCount: Int,
    ): List<Int> {
        val mods = ArrayList<Int>()
        val side = field.defenderSide
        val screen = if (field.doubles) 2732 else 2048

        if (side.isReflect && move.category == "Physical" && !isCritical && !side.isAuroraVeil) {
            mods += screen
        } else if (side.isLightScreen && move.category == "Special" && !isCritical && !side.isAuroraVeil) {
            mods += screen
        }
        if (side.isAuroraVeil && !isCritical) mods += screen

        if (attacker.hasAbility("Neuroforce") && typeEffectiveness > 1) {
            mods += 5120
        } else if (attacker.hasAbility("Fatal Precision") && typeEffectiveness > 1) {
            mods += 4915
        } else if (attacker.hasAbility("Sniper") && isCritical) {
            mods += 6144
        } else if ((attacker.hasAbility("Tinted Lens") && typeEffectiveness < 1) ||
            (attacker.hasAbility("Bone Zone") && move.flag("bone") && typeEffectiveness < 1)
        ) {
            mods += 8192
        }

        if (defender.hasAbility("Multiscale", "Shadow Shield", "Blubber Defense") &&
            defender.curHP() == defender.maxHP() && hitCount == 0 &&
            ((!side.isSR && (side.spikes == 0 || defender.hasType("Flying"))) || defender.hasItem("Heavy-Duty Boots")) &&
            !attacker.hasAbility("Parental Bond (Child)", "ORAORAORAORA (Child)")
        ) {
            mods += 2048
        }

        if (defender.hasAbility("Fluffy") && move.flag("contact") && !attacker.hasAbility("Long Reach")) {
            mods += 2048
        } else if ((defender.hasAbility("Punk Rock") && move.flag("sound")) ||
            (defender.hasAbility("Ice Scales") && move.category == "Special")
        ) {
            mods += 2048
        }

        if (defender.hasAbility("Primal Armor") && typeEffectiveness > 1) {
            mods += 2048
        } else if (defender.hasAbility("Solid Rock", "Filter", "Prism Armor") && typeEffectiveness > 1) {
            mods += 3072
        }

        if (side.isFriendGuard) mods += 3072

        if (defender.hasAbility("Fluffy") && move.hasType("Fire")) mods += 8192

        if (attacker.hasItem("Expert Belt") && typeEffectiveness > 1 && !move.isZ) {
            mods += 4915
        } else if (attacker.hasItem("Life Orb")) {
            mods += 5324
        } else if (attacker.hasItem("Metronome") && move.metronomeUses >= 1) {
            mods += if (move.metronomeUses <= 4) 4096 + move.metronomeUses * 819 else 8192
        }

        val berry = if (defender.item.isNotEmpty()) defender.pack.items[defender.item]?.berryResistType else null
        if (berry != null && move.hasType(berry) && (typeEffectiveness > 1 || move.hasType("Normal")) && hitCount == 0 &&
            !attacker.hasAbility("Unnerve", "As One (Glastrier)", "As One (Spectrier)")
        ) {
            mods += if (defender.hasAbility("Ripen")) 1024 else 2048
        }
        return mods
    }
}
