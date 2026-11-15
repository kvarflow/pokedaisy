package com.pokedaisy.app.companion.calc

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The trainer's likely move, after Radical Red's battle AI: a port of the CALC tab's original
 * (src/js/trainer_ai.js in kvarflow/rr-calc), which ports Complete Fire Red Upgrade's move-choice and
 * switching AI (src/Battle_AI: ai_master.c, ai_negatives.c, ai_positives.c, ai_util.c, ai_advanced.c,
 * ai_switching.c) as Radical Red has it, checked there against the ROM's own code. Kept function for
 * function with the original (the same names, in the same order), so the two can be compared:
 * `CfruAiParityTest` replays the original's answers on thousands of battles.
 *
 * Each of the foe's moves starts at a score of 100. "Negatives" (every trainer) takes points off moves that
 * would fail or waste the turn; "Positives" (Radical Red gives every trainer the smart flags) adds points for
 * KOs, the strongest move, setting up when it is safe and so on, by the Pokémon's fighting style. The highest
 * score is used, ties at random. Before scoring, the AI predicts your move the same way, and the AI's own
 * rolls (a coin for a 50% crit, four "simulated" random numbers, tie breaks) are enumerated exactly
 * ([enumerateChances]), so the shares are the chance of each move given what is known. Before its move the
 * AI may switch out; the chance of each switch is given with the moves.
 *
 * What can't be known is assumed, as the original does: no moves used yet this battle, the foe's benched
 * Pokémon at full HP with no status, and nobody behind a Substitute.
 */
class CfruAi(private val pack: CalcPack) {

    /**
     * The chance of each move and switch this turn. [AiPrediction.zShares] is the part of each move's share used as
     * its Z-Move.
     */
    fun predict(battle: AiBattle): AiPrediction = predictAIMoves(battle)

    /** Why the AI switches, by [AiSwitch.reasons]' keys ("to avoid being knocked out"). */
    fun switchReason(index: Int): String? = SWITCH_REASONS.getOrNull(index)

    /** Who the trainer sends out next if you KO its Pokémon now: each benched Pokémon's share. */
    fun replacement(battle: AiBattle): List<Pair<AiSide, Double>> = predictReplacement(battle)

    // #region constants

    internal companion object {
        const val AI_NEGATIVES = 1
        const val AI_SEMI_SMART = 2
        const val AI_POSITIVES = 4
        const val AI_SMART_FLAGS = 7

        // Fighting styles (ai_advanced.c), singles.
        const val STYLE_NONE = 0
        const val STYLE_KILL = 1
        const val STYLE_SETUP_STATS = 2
        const val STYLE_SETUP_STATUS = 3
        const val STYLE_SETUP_SCREENS = 4
        const val STYLE_STALL = 5
        const val STYLE_BATON_PASS = 6
        const val STYLE_CLERIC = 7
        const val STYLE_SUPPORT_SCREENS = 8
        const val STYLE_PHAZING = 9
        const val STYLE_HAZARDS = 10

        const val MOVE_TARGET_BOTH = 0x8
        const val MOVE_TARGET_USER = 0x10
        const val MOVE_TARGET_FOES_AND_ALLY = 0x20
        const val MOVE_TARGET_FOES_FIELD = 0x40
        const val FLAG_CONTACT = 0x1
        const val FLAG_MAGIC_COAT = 0x4
        const val FLAG_TRIAGE = 0x40
        const val SPLIT_PHYSICAL = 0
        const val SPLIT_SPECIAL = 1
        const val SPLIT_STATUS = 2

        val MOLD_BREAKERS = setOf("Mold Breaker", "Teravolt", "Turboblaze")
        val CONTACT_PUNISHERS = setOf(
            "Effect Spore", "Poison Point", "Static", "Flame Body", "Cute Charm", "Aftermath", "Gooey",
            "Tangling Hair", "Rough Skin", "Iron Barbs", "Pickpocket", "Mummy", "Wandering Spirit", "Cotton Down", "Perish Body",
        )
        val POWDER_MOVES = setOf("Spore", "Sleep Powder", "Stun Spore", "Poison Powder", "Rage Powder", "Cotton Spore", "Powder", "Magic Powder")
        val GRAVITY_BANNED = setOf("Fly", "Bounce", "Sky Drop", "High Jump Kick", "Jump Kick", "Splash", "Magnet Rise", "Telekinesis", "Flying Press")
        val CHARGE_MOVES = setOf(
            "Razor Wind", "Sky Attack", "Skull Bash", "Meteor Beam", "Freeze Shock", "Ice Burn", "Geomancy", "Solar Beam",
            "Solar Blade", "Fly", "Dig", "Dive", "Bounce", "Phantom Force", "Shadow Force", "Sky Drop", "Electro Shot",
        )
        // Radical Red's DoubleDamageWithStatusMoveInMovesetThatAffects table.
        val DOUBLE_DAMAGE_MOVES = setOf("Bitter Malice", "Infernal Parade", "Hex", "Venoshock")
        // The moves Sheer Force boosts (Radical Red's table), which then have no secondary effect.
        val SHEER_FORCE_MOVES = setOf(
            "Acid", "Acid Spray", "Air Slash", "Anchor Shot", "Ancient Power", "Apple Acid", "Astonish",
            "Aurora Beam", "Bite", "Blaze Kick", "Blizzard", "Blue Flare", "Body Slam", "Bolt Strike", "Bone Club", "Bounce", "Bubble",
            "Bubble Beam", "Bug Buzz", "Bulldoze", "Burning Jealousy", "Charge Beam", "Chatter", "Confusion", "Constrict",
            "Cross Poison", "Crunch", "Crush Claw", "Dark Pulse", "Diamond Storm", "Discharge", "Dizzy Punch", "Double Iron Bash",
            "Dragon Breath", "Dragon Rush", "Drum Beating", "Dynamic Punch", "Earth Power", "Electroweb", "Ember", "Energy Ball",
            "Extrasensory", "Fake Out", "Fiery Dance", "Fire Blast", "Fire Fang", "Fire Lash", "Fire Punch", "Flame Charge",
            "Flame Wheel", "Flamethrower", "Flare Blitz", "Flash Cannon", "Focus Blast", "Force Palm", "Freeze Shock", "Freeze-Dry",
            "Frost Breath", "Glaciate", "Grav Apple", "Gunk Shot", "Headbutt", "Head Charge", "Heart Stamp", "Heat Wave", "Hurricane",
            "Hyper Fang", "Ice Beam", "Ice Burn", "Ice Fang", "Ice Punch", "Icicle Crash", "Icy Wind", "Inferno", "Iron Head",
            "Iron Tail", "Lava Plume", "Leaf Tornado", "Lick", "Liquidation", "Low Sweep", "Lunge", "Luster Purge", "Metal Claw",
            "Meteor Mash", "Mirror Shot", "Mist Ball", "Moonblast", "Mud Bomb", "Mud Shot", "Muddy Water", "Mud-Slap",
            "Mystical Fire", "Needle Arm", "Night Daze", "Nuzzle", "Octazooka", "Ominous Wind", "Play Rough", "Poison Fang",
            "Poison Jab", "Poison Sting", "Poison Tail", "Powder Snow", "Power-Up Punch", "Psybeam", "Psychic", "Pyro Ball",
            "Razor Shell", "Relic Song", "Rock Climb", "Rock Slide", "Rock Smash", "Rock Tomb", "Rolling Kick", "Sacred Fire",
            "Scald", "Scale Shot", "Scorching Sands", "Searing Shot", "Secret Power", "Seed Flare", "Shadow Ball", "Shadow Bone",
            "Signal Beam", "Silver Wind", "Skitter Smack", "Sky Attack", "Sludge", "Sludge Bomb", "Sludge Wave", "Smog", "Snarl",
            "Snore", "Spark", "Sparkling Aria", "Spirit Break", "Spirit Shackle", "Steam Eruption", "Steamroller", "Steel Wing",
            "Stomp", "Strange Steam", "Struggle Bug", "Throat Chop", "Thunder", "Thunder Fang", "Thunder Punch", "Thunder Shock",
            "Thunderbolt", "Tri Attack", "Trop Kick", "Twineedle", "Twister", "Volt Tackle", "Water Pulse", "Waterfall",
            "Zap Cannon", "Zen Headbutt", "Zing Zap", "Fiery Wrath", "Freezing Glare", "Thunderous Kick", "Ceaseless Edge",
            "Triple Arrows", "Barb Barrage", "Hydro Cannon", "Frenzy Plant", "Blast Burn", "Bitter Malice", "Esper Wing",
            "Breaking Swipe", "Flash", "Springtide Storm", "Sandsear Storm", "Wildbolt Storm", "Bleakwind Storm", "Alluring Voice",
            "Psychic Noise", "Malignant Chain", "Matcha Gotcha",
        )
        // Moves that thaw the user (Radical Red's table), worth +10 to a frozen AI.
        val UNFREEZE_MOVES = setOf(
            "Flame Wheel", "Sacred Fire", "Flare Blitz", "Scald", "Scorching Sands", "Fusion Flare", "Steam Eruption",
            "Burn Up", "Pyro Ball", "Matcha Gotcha",
        )
        // Radical Red's IsClearBodyAbility tests Clear Body alone; White Smoke and Full Metal Body share its battle id.
        val CLEAR_BODY_ABILITIES = setOf("Clear Body", "White Smoke", "Full Metal Body")
        val STAT_DROP_SELF_EFFECTS = setOf(E.SUPERPOWER, E.OVERHEAT)
        val FIXED_DAMAGE_EFFECTS = setOf(E.LEVEL_DAMAGE, E.SUPER_FANG, E.DRAGON_RAGE, E.SONICBOOM, E.PSYWAVE, E.ENDEAVOR, E.PAIN_SPLIT)
        val SET_STATUS_EFFECTS = setOf(E.SLEEP, E.TOXIC, E.POISON, E.PARALYZE, E.WILL_O_WISP, E.YAWN)
        // The moves that make a set "boosting" for its fighting style, as Radical Red's own code lists them.
        val BOOSTING_EFFECTS = setOf(
            E.ATTACK_UP, E.DEFENSE_UP, E.SPEED_UP, E.SPECIAL_ATTACK_UP, E.SPECIAL_DEFENSE_UP, E.ACCURACY_UP,
            E.EVASION_UP, E.ATTACK_UP_2, E.DEFENSE_UP_2, E.SPEED_UP_2, E.SPECIAL_ATTACK_UP_2, E.SPECIAL_DEFENSE_UP_2,
            E.ACCURACY_UP_2, E.EVASION_UP_2, E.ATTACK_UP_HIT, E.COSMIC_POWER, E.BULK_UP, E.CALM_MIND, E.DRAGON_DANCE,
        )
        val ABSORBING = mapOf(
            "Electric" to setOf("Volt Absorb", "Motor Drive", "Lightning Rod"), "Water" to setOf("Water Absorb", "Dry Skin", "Storm Drain"),
            "Fire" to setOf("Flash Fire", "Well-Baked Body"), "Grass" to setOf("Sap Sipper"), "Ground" to setOf("Earth Eater"),
        )
        // Radical Red's stat-lowering table for Contrary, Clear Body, Defiant and Competitive (no Tickle).
        val RR_STAT_LOWER_EFFECTS = setOf(
            E.ATTACK_DOWN, E.DEFENSE_DOWN, E.SPEED_DOWN, E.SPECIAL_ATTACK_DOWN, E.SPECIAL_DEFENSE_DOWN,
            E.ACCURACY_DOWN, E.EVASION_DOWN, E.ATTACK_DOWN_2, E.DEFENSE_DOWN_2, E.SPEED_DOWN_2, E.SPECIAL_ATTACK_DOWN_2,
            E.SPECIAL_DEFENSE_DOWN_2, E.ACCURACY_DOWN_2, E.EVASION_DOWN_2,
        )
        val WIND_MOVES = setOf(
            "Air Cutter", "Bleakwind Storm", "Blizzard", "Fairy Wind", "Gust", "Heat Wave", "Hurricane", "Icy Wind",
            "Petal Blizzard", "Sandstorm", "Sandsear Storm", "Springtide Storm", "Twister", "Whirlwind", "Wildbolt Storm", "Sonic Slash",
        )
        // Moves that ignore the target's ability, like Mold Breaker.
        val ABILITY_IGNORING_MOVES = setOf("Moongeist Beam", "Shell Side Arm", "Photon Geyser", "Sunsteel Strike")

        /*
         * Z-Moves. Radical Red's CanUseZMove lets trainers use them in Hardcore, without a Z-Power Ring; not while
         * Mega Evolved. A damaging move becomes the Z-Move of its crystal's type, or the item's own Z-Move for its
         * Pokémon and move.
         */
        val Z_CRYSTAL_TYPES = mapOf(
            "Normalium Z" to "Normal", "Fightinium Z" to "Fighting", "Flyinium Z" to "Flying", "Poisonium Z" to "Poison",
            "Groundium Z" to "Ground", "Rockium Z" to "Rock", "Buginium Z" to "Bug", "Ghostium Z" to "Ghost", "Steelium Z" to "Steel",
            "Firium Z" to "Fire", "Waterium Z" to "Water", "Grassium Z" to "Grass", "Electrium Z" to "Electric", "Psychium Z" to "Psychic",
            "Icium Z" to "Ice", "Dragonium Z" to "Dragon", "Darkinium Z" to "Dark", "Fairium Z" to "Fairy",
        )
        val TYPED_Z_MOVES = mapOf(
            "Normal" to "Breakneck Blitz", "Fighting" to "All-Out Pummeling", "Flying" to "Supersonic Skystrike",
            "Poison" to "Acid Downpour", "Ground" to "Tectonic Rage", "Rock" to "Continental Crush", "Bug" to "Savage Spin-Out",
            "Ghost" to "Never-Ending Nightmare", "Steel" to "Corkscrew Crash", "Fire" to "Inferno Overdrive", "Water" to "Hydro Vortex",
            "Grass" to "Bloom Doom", "Electric" to "Gigavolt Havoc", "Psychic" to "Shattered Psyche", "Ice" to "Subzero Slammer",
            "Dragon" to "Devastating Drake", "Dark" to "Black Hole Eclipse", "Fairy" to "Twinkle Tackle",
        )

        class SpecialZ(val item: String, val move: String, val species: Regex, val z: String)

        val SPECIAL_Z_MOVES = listOf(
            SpecialZ("Eevium Z", "Last Resort", Regex("^Eevee"), "Extreme Evoboost"),
            SpecialZ("Pikanium Z", "Volt Tackle", Regex("^Pikachu"), "Catastropika"),
            SpecialZ("Pikashunium Z", "Thunderbolt", Regex("^Pikachu-"), "10,000,000 Volt Thunderbolt"),
            SpecialZ("Aloraichium Z", "Thunderbolt", Regex("^Raichu-Alola"), "Stoked Sparksurfer"),
            SpecialZ("Snorlium Z", "Giga Impact", Regex("^Snorlax"), "Pulverizing Pancake"),
            SpecialZ("Mewnium Z", "Psychic", Regex("^Mew$"), "Genesis Supernova"),
            SpecialZ("Decidium Z", "Spirit Shackle", Regex("^Decidueye"), "Sinister Arrow Raid"),
            SpecialZ("Incinium Z", "Darkest Lariat", Regex("^Incineroar"), "Malicious Moonsault"),
            SpecialZ("Primarium Z", "Sparkling Aria", Regex("^Primarina"), "Oceanic Operetta"),
            SpecialZ("Lycanium Z", "Stone Edge", Regex("^Lycanroc"), "Splintered Stormshards"),
            SpecialZ("Mimikium Z", "Play Rough", Regex("^Mimikyu"), "Let's Snuggle Forever"),
            SpecialZ("Kommonium Z", "Clanging Scales", Regex("^Kommo-o"), "Clangorous Soulblaze"),
            SpecialZ("Tapunium Z", "Nature's Madness", Regex("^Tapu "), "Guardian of Alola"),
            SpecialZ("Solganium Z", "Sunsteel Strike", Regex("^(Solgaleo|Necrozma-Dusk-Mane)"), "Searing Sunraze Smash"),
            SpecialZ("Lunalium Z", "Moongeist Beam", Regex("^(Lunala|Necrozma-Dawn-Wings)"), "Menacing Moonraze Maelstrom"),
            SpecialZ("Ultranecrozium Z", "Photon Geyser", Regex("^Necrozma-Ultra"), "Light That Burns the Sky"),
            SpecialZ("Marshadium Z", "Spectral Thief", Regex("^Marshadow"), "Soul-Stealing 7-Star Strike"),
        )

        /** The special Z-Moves' data: [effect, power, secondary chance, target]. */
        val SPECIAL_Z_DATA = mapOf(
            "Catastropika" to intArrayOf(0, 210, 0, 0), "10,000,000 Volt Thunderbolt" to intArrayOf(43, 195, 0, 0),
            "Stoked Sparksurfer" to intArrayOf(6, 175, 100, 0), "Pulverizing Pancake" to intArrayOf(0, 210, 0, 0),
            "Genesis Supernova" to intArrayOf(140, 185, 100, 0), "Sinister Arrow Raid" to intArrayOf(0, 180, 0, 0),
            "Malicious Moonsault" to intArrayOf(0, 180, 0, 0), "Oceanic Operetta" to intArrayOf(0, 195, 0, 0),
            "Splintered Stormshards" to intArrayOf(243, 190, 0, 0), "Let's Snuggle Forever" to intArrayOf(0, 190, 0, 0),
            "Clangorous Soulblaze" to intArrayOf(140, 185, 100, 8),
            // (Its damage, three quarters of the target's HP, is the engine's.)
            "Guardian of Alola" to intArrayOf(0, 1, 0, 0), "Searing Sunraze Smash" to intArrayOf(0, 200, 0, 0),
            "Menacing Moonraze Maelstrom" to intArrayOf(0, 200, 0, 0), "Light That Burns the Sky" to intArrayOf(0, 200, 0, 0),
            "Soul-Stealing 7-Star Strike" to intArrayOf(0, 195, 0, 0),
            // Eevee's Last Resort becomes a status move: +2 to every stat.
            "Extreme Evoboost" to intArrayOf(207, 0, 0, 16),
        )
        // Your predicted move that makes it hold its Z-Move back (Radical Red adds Silk Trap to CFRU's list).
        val Z_PROTECT_MOVES = setOf("Protect", "King's Shield", "Spiky Shield", "Obstruct", "Silk Trap")

        /** The Z-Move a damaging move would become, or "". */
        fun zMoveNameFor(species: String, item: String, move: AiMove?): String {
            if (move == null || move.split == SPLIT_STATUS || Regex("-(Mega|Primal)").containsMatchIn(species)) return ""
            for (special in SPECIAL_Z_MOVES) {
                if (special.item == item && special.move == move.name && special.species.containsMatchIn(species)) return special.z
            }
            val type = if (move.name.startsWith("Hidden Power")) "Normal" else move.type
            return if (Z_CRYSTAL_TYPES[item] != null && Z_CRYSTAL_TYPES[item] == type) TYPED_Z_MOVES[type].orEmpty() else ""
        }

        val RECOIL_QUARTER = setOf("Take Down", "Submission", "Wild Charge", "Head Charge", "Volt Tackle")
        val RECOIL_THIRD = setOf("Double-Edge", "Flare Blitz", "Brave Bird", "Wood Hammer", "Draco Barrage", "Wave Crash")
        val RECOIL_HALF = setOf("Head Smash", "Light of Ruin", "Chloroblast")
        // Radical Red's IsMoxieAbility (no Chilling Neigh or As One).
        val MOXIE_ABILITIES = setOf("Moxie", "Battle Bond", "Beast Boost", "Soul-Heart", "Grim Neigh")
        val STORM_MOVES = setOf("Springtide Storm", "Bleakwind Storm", "Wildbolt Storm", "Sandsear Storm")
        // Radical Red's AI damage counts multi-hit moves from these tables (after the 0.93), not the hits the
        // engine rolls: 2-5 hits as 2 (5 with Skill Link), two-hit moves as 2, Triple Kick and Triple Axel as 6 of
        // the first hit, anything else (Population Bomb) as 1.
        val TWO_TO_FIVE_HIT_MOVES = setOf(
            "Arm Thrust", "Barrage", "Bone Rush", "Bullet Seed", "Comet Punch", "Double Slap", "Fury Attack",
            "Fury Swipes", "Icicle Spear", "Pin Missile", "Rock Blast", "Scale Shot", "Spike Cannon", "Tail Slap", "Water Shuriken",
            "Surging Strikes", "Beat Up", "Triple Dive",
        )
        val TWO_HIT_MOVES = setOf(
            "Bonemerang", "Double Hit", "Double Iron Bash", "Double Kick", "Dragon Darts", "Dual Chop", "Dual Wingbeat",
            "Gear Grind", "Twineedle", "Twin Beam", "Tachyon Cutter",
        )

        // simulatedRNG[k] (AIRandom() % 100): only below 25, below 75 and its parity are ever read, so one value
        // stands for each of the six cells.
        val SIMULATED_RNG_CELLS = intArrayOf(0, 1, 26, 25, 76, 75)
        val SIMULATED_RNG_CHANCES = doubleArrayOf(0.13, 0.12, 0.25, 0.25, 0.12, 0.13)

        // MoveKnocksOutPossiblyGoesFirstWithBestAccuracy's plain priority-0 move.
        val PLAIN_MOVE = AiMove(
            -1, "Tackle", "Normal", 40, 1, false, null, false, false, 0, 100, 0, 0, 0, 0, SPLIT_PHYSICAL, 40, 0, 0, 0.0, 1.0,
        )

        val CURE_BERRIES = mapOf("par" to "Cheri Berry", "brn" to "Rawst Berry", "psn" to "Pecha Berry")

        // Switching (ai_switching.c, singles).
        const val SF_KO_FOE = 1
        const val SF_RESIST_ALL = 2
        const val SF_REVENGE_KILL = 4
        const val SF_WALLS_FOE = 8
        const val SF_CAN_2HKO = 16
        const val SF_REMOVE_HAZARDS = 32
        const val SF_OUTSPEEDS = 64
        const val SF_FAINTS = 128
        const val SS_KO_FOE = 31
        const val SS_RESIST_ALL = 17
        const val SS_REVENGE_KILL = 8
        const val SS_WALLS_FOE = 2
        const val SS_CAN_2HKO = 2
        const val SS_OUTSPEEDS = 14
        const val SS_WEAK = 1
        const val SS_FAINTS = 39
        const val SS_FAINTS_BUT_OUTSPEEDS = 15
        const val SWITCH_SCORE_MAX = 31 + 17 + 8 + 14
        // OFFENSIVE_STAT_MIN_NUM: a stat stage of 3 is -3.
        const val OFFENSIVE_STAT_MIN = 3

        // Radical Red's FindMonThatAbsorbsOpponentsMove: Earth Eater takes Ground moves and Well-Baked Body Fire
        // ones too, and Earth Eater counts as healing (IsHPAbsorptionAbility).
        val SWITCH_ABSORBING = mapOf(
            "Electric" to setOf("Volt Absorb", "Lightning Rod", "Motor Drive"), "Water" to setOf("Water Absorb", "Dry Skin", "Storm Drain"),
            "Fire" to setOf("Flash Fire", "Well-Baked Body"), "Grass" to setOf("Sap Sipper"), "Ground" to setOf("Earth Eater"),
        )
        val HP_ABSORBING = setOf("Water Absorb", "Volt Absorb", "Earth Eater")
        val STAT_STAGE_RATIOS = arrayOf(
            intArrayOf(10, 40), intArrayOf(10, 35), intArrayOf(10, 30), intArrayOf(10, 25), intArrayOf(10, 20), intArrayOf(10, 15),
            intArrayOf(10, 10), intArrayOf(15, 10), intArrayOf(20, 10), intArrayOf(25, 10), intArrayOf(30, 10), intArrayOf(35, 10), intArrayOf(40, 10),
        )

        /** Why the AI switches, in ShouldSwitch's order. */
        val SWITCH_REASONS = listOf(
            "to take your move with its ability", "against your Wonder Guard", "because its moves are no good here",
            "to heal with its ability", "because it is asleep", "because of Leech Seed", "to avoid being knocked out",
            "because its attacking stat is down", "to save it for later",
        )

        val SEVEN_STATS = listOf("atk", "def", "spa", "spd", "spe", "acc", "eva")
    }

    // #endregion
    // #region enumerating the AI's chances

    /** The random points of a decision: [pick] answers which of [probabilities] happens. */
    internal fun interface Rng {
        fun pick(probabilities: DoubleArray): Int
    }

    /**
     * Runs [decide] once per combination of answers to its random points (an odometer over the choices made, deeper
     * choices restarting whenever an earlier one changes), each weighted by its chance: the exact distribution of what
     * it returns, at most [limit] runs.
     */
    internal fun enumerateChances(limit: Int, decide: (Rng) -> DoubleArray): DoubleArray {
        val path = ArrayList<Int>()
        var total = DoubleArray(0)
        var runs = 0
        var weights = 0.0
        while (true) {
            var depth = 0
            var weight = 1.0
            val sizes = ArrayList<Int>()
            val rng = Rng { probabilities ->
                if (depth == path.size) path.add(0)
                val index = path[depth]
                if (depth < sizes.size) sizes[depth] = probabilities.size else sizes.add(probabilities.size)
                weight *= probabilities[index]
                depth++
                index
            }
            val shares = decide(rng)
            weights += weight
            if (shares.size > total.size) total = total.copyOf(shares.size)
            for (i in shares.indices) total[i] = total[i] + weight * shares[i]
            while (path.size > depth) path.removeAt(path.size - 1)
            while (path.isNotEmpty() && path[path.size - 1] + 1 >= sizes[path.size - 1]) path.removeAt(path.size - 1)
            if (path.isEmpty() || ++runs >= limit) break
            path[path.size - 1] = path[path.size - 1] + 1
        }
        // Over the runs' weight (each run's answer adds up to 1, its Z-Move marks aside), so the runs a limit cut off
        // don't count and a Z-Move's share isn't counted twice.
        return DoubleArray(total.size) { if (weights != 0.0) total[it] / weights else 0.0 }
    }

    /** A battle turn being thought through: random values are drawn lazily and kept for the turn. */
    internal inner class AiTurn(val rng: Rng, var battle: AiBattle) {
        val coins = HashMap<String, Any>() // kept for the whole turn
        var cache = HashMap<String, Any>() // per thinking phase (your prediction, then the AI's choice)
        val prediction = HashMap<String, Int?>() // side key -> move index, or null (switch or nothing)
        val predictedSwitch = HashMap<String, Boolean>()
        var flags: Int = battle.flags
        var phase: String? = null
        var counting = false
        var aiPrediction: AiMove? = null
        var goodToPivot = false
        val kept = HashMap<String, Any>()

        fun coin(key: String, chance: Double): Boolean {
            if (chance <= 0) return false
            if (chance >= 1) return true
            return coins.getOrPut(key) { rng.pick(doubleArrayOf(chance, 1 - chance)) == 0 } as Boolean
        }

        fun simulatedRNG(k: Int): Int {
            val key = "rng" + (phase ?: "") + k
            return coins.getOrPut(key) { SIMULATED_RNG_CELLS[rng.pick(SIMULATED_RNG_CHANCES)] } as Int
        }

        @Suppress("UNCHECKED_CAST")
        fun <T : Any> memo(key: String, compute: () -> T): T = cache[key] as T? ?: compute().also { cache[key] = it }

        @Suppress("UNCHECKED_CAST")
        fun <T : Any> keep(key: String, compute: () -> T): T = kept[key] as T? ?: compute().also { kept[key] = it }

        fun predicted(side: AiSide): AiMove? {
            val index = prediction[side.key] ?: return null
            return side.moves[index]
        }
    }

    /** [side]'s move [move] by identity, as the original's `moves.indexOf`. */
    private fun indexOf(side: AiSide, move: AiMove?): Int = side.moves.indexOfFirst { it != null && it === move }

    // #endregion
    // #region facts about one side

    private fun ignoresAbilities(atk: AiSide) = atk.ability in MOLD_BREAKERS

    private fun defAbility(atk: AiSide, def: AiSide) = if (ignoresAbilities(atk)) "" else def.ability

    private val AiField.w: String get() = weather.orEmpty()

    // The engine calls Radical Red's hail "Snow" (it only chips as "Hail").
    private fun isHail(field: AiField) = field.weather == "Hail" || field.weather == "Snow"

    private fun isGrounded(side: AiSide, field: AiField): Boolean {
        if (field.isGravity || side.item == "Iron Ball") return true
        return !("Flying" in side.types || side.ability == "Levitate" || side.item == "Air Balloon")
    }

    private inline fun hasMoveWhere(side: AiSide, test: (AiMove) -> Boolean) = side.moves.any { it != null && test(it) }

    private fun hasPhysical(side: AiSide) = hasMoveWhere(side) { it.split == SPLIT_PHYSICAL && it.power > 0 }

    // A physical move that uses its own Attack: Radical Red leaves out Counter, Metal Burst, Comeuppance, Fake Out,
    // Foul Play and Body Press.
    private fun hasRealPhysical(side: AiSide) = hasMoveWhere(side) {
        it.split == SPLIT_PHYSICAL && it.power > 0 && it.effect != E.COUNTER && it.effect != E.FAKE_OUT &&
            it.name !in setOf("Foul Play", "Body Press", "Metal Burst", "Comeuppance")
    }

    // Any physical attack but Counter (Radical Red's test in the Attack-lowering cases).
    private fun hasPhysicalAttack(side: AiSide) = hasMoveWhere(side) { it.split == SPLIT_PHYSICAL && it.power > 0 && it.effect != E.COUNTER }

    // A special attack (Radical Red leaves out Mirror Coat).
    private fun hasSpecial(side: AiSide) = hasMoveWhere(side) { it.split == SPLIT_SPECIAL && it.power > 0 && it.effect != E.MIRROR_COAT }

    private fun hasMoveNamed(side: AiSide, vararg names: String) = hasMoveWhere(side) { it.name in names }

    private fun hasEffect(side: AiSide, vararg effects: Int) = hasMoveWhere(side) { it.effect in effects }

    private fun hasProtectMove(side: AiSide) = hasMoveWhere(side) {
        it.effect == E.PROTECT && it.name !in setOf("Quick Guard", "Wide Guard", "Crafty Shield", "Mat Block")
    }

    private fun isDamaging(move: AiMove?) = move != null && move.split != SPLIT_STATUS && move.power > 0

    private fun stage(side: AiSide, stat: String) = 6 + (side.boosts[stat] ?: 0)

    private fun healthPercent(side: AiSide) = floor(side.hp * 100.0 / side.maxHP).toInt()

    private fun isAsleep(side: AiSide) = side.status == "slp" && side.ability != "Comatose"

    // Asleep: it cannot act this turn. Radical Red's IsBankIncapacitated is sleep, recharging and Truant only: it is
    // built with frostbite instead of freeze, and a frostbitten Pokémon still attacks.
    private fun isIncapacitated(side: AiSide) = isAsleep(side)

    private fun canBeChoiceLocked(side: AiSide) = side.item.startsWith("Choice ") || side.ability == "Gorilla Tactics"

    private fun isUsable(side: AiSide, i: Int, field: AiField): Boolean {
        val move = side.moves.getOrNull(i) ?: return false
        if (move.split == SPLIT_STATUS && side.item == "Assault Vest") return false
        if (field.isGravity && move.name in GRAVITY_BANNED) return false
        return true
    }

    private fun isSelfTargeting(move: AiMove) = (move.target and MOVE_TARGET_USER) != 0

    private fun needsCharge(atk: AiSide, move: AiMove, field: AiField): Boolean {
        if (move.name !in CHARGE_MOVES || atk.item == "Power Herb") return false
        if ((move.name == "Solar Beam" || move.name == "Solar Blade") && "Sun" in field.w) return false
        return true
    }

    // End-of-turn damage the side will take (GetSecondaryEffectDamage).
    private fun secondaryDamage(side: AiSide, field: AiField): Int {
        if (side.ability == "Magic Guard") return 0
        val max = side.maxHP
        var chip = 0
        val weather = field.w
        val goggles = side.item == "Safety Goggles" || side.ability == "Overcoat"
        if (weather == "Sand" && !goggles && side.types.none { it in setOf("Rock", "Ground", "Steel") } &&
            side.ability !in setOf("Sand Veil", "Sand Rush", "Sand Force")
        ) chip += max(1, max / 16)
        if (weather == "Hail" && !goggles && "Ice" !in side.types && side.ability !in setOf("Ice Body", "Snow Cloak")) chip += max(1, max / 16)
        if (side.status == "psn" && side.ability != "Poison Heal") chip += max(1, max / 8)
        if (side.status == "tox" && side.ability != "Poison Heal") chip += max(1, max / 16)
        if (side.status == "brn") chip += max(1, max / (if (side.ability == "Heatproof") 32 else 16))
        if (side.seeded) chip += max(1, max / 8)
        if (side.saltCured) chip += max(1, max / (if ("Water" in side.types || "Steel" in side.types) 4 else 8))
        return chip
    }

    private fun passiveRecovery(side: AiSide, field: AiField): Int {
        val max = side.maxHP
        var heal = 0
        if (side.item == "Leftovers" || (side.item == "Black Sludge" && "Poison" in side.types)) heal += max / 16
        if ("Rain" in field.w && side.ability == "Rain Dish") heal += max / 16
        if ("Rain" in field.w && side.ability == "Dry Skin") heal += max / 8
        if (isHail(field) && side.ability == "Ice Body") heal += max / 16
        if ((side.status == "psn" || side.status == "tox") && side.ability == "Poison Heal") heal += max / 8
        if (field.terrain == "Grassy" && isGrounded(side, field)) heal += max / 16
        return if (secondaryDamage(side, field) >= heal) 0 else heal
    }

    // WillFaintFromSecondaryDamage, with the game's 8-bit HP wrap kept.
    private fun willFaintFromChip(side: AiSide, field: AiField): Boolean {
        val hp = min(side.hp + passiveRecovery(side, field), side.maxHP) and 0xFF
        val chip = secondaryDamage(side, field)
        return chip > 0 && chip >= hp
    }

    // #endregion
    // #region speed, priority and accuracy

    private fun priorityOf(side: AiSide, move: AiMove?, field: AiField): Int {
        if (move == null || side.hp <= 0) return 0
        var priority = move.priority
        if (side.ability == "Prankster" && move.split == SPLIT_STATUS) priority++
        if (side.ability == "Gale Wings" && move.type == "Flying" && side.hp == side.maxHP) priority++
        if (side.ability == "Triage" && (move.flags and FLAG_TRIAGE) != 0) priority += 3
        if (side.ability == "Blazing Soul" && move.type == "Fire" && side.hp == side.maxHP) priority++
        // Radical Red doesn't check that the user is grounded.
        if (move.name == "Grassy Glide" && field.terrain == "Grassy") priority++
        return priority
    }

    // The speed bracket: Quick Draw (30% with an attack) and Quick Claw (20%) are rolled afresh each time the game
    // asks, here once per side and thinking phase.
    private fun bracketOf(side: AiSide, foe: AiSide, move: AiMove?, turn: AiTurn?): Int {
        if (side.ability == "Quick Draw" && move != null && move.split != SPLIT_STATUS && turn != null &&
            turn.coin("quickDraw" + turn.phase + side.key, 0.3)
        ) return 1
        if (side.item == "Custap Berry" && foe.ability !in setOf("Unnerve", "As One") &&
            side.hp <= side.maxHP / (if (side.ability == "Gluttony") 2.0 else 4.0)
        ) return 1
        if (side.item == "Lagging Tail" || side.item == "Full Incense") return -2
        if (side.item == "Quick Claw" && turn != null && turn.coin("quickClaw" + turn.phase + side.key, 0.2)) return 1
        return 0
    }

    // MoveWouldHitBeforeOtherMove: speed ties go against the attacker. Radical Red's version has no "the other move
    // has higher priority, so this one goes second": it goes on to compare brackets and speed.
    private fun hitsBefore(atk: AiSide, move: AiMove?, def: AiSide, defMove: AiMove?, battle: AiBattle, turn: AiTurn?): Boolean {
        val field = battle.field
        if (defMove == null) {
            if (priorityOf(atk, move, field) > 0) return true
        } else if (priorityOf(atk, move, field) > priorityOf(def, defMove, field)) {
            return true
        }
        if (bracketOf(atk, def, move, turn) > bracketOf(def, atk, defMove, turn)) return true
        var sa = atk.speed
        var sd = def.speed
        if (battle.trickRoom) {
            sa = def.speed
            sd = atk.speed
        }
        return sa > sd
    }

    private fun hitsFirst(turn: AiTurn, atk: AiSide, move: AiMove?, def: AiSide) =
        hitsBefore(atk, move, def, turn.predicted(def), turn.battle, turn)

    private fun moveWillHit(atk: AiSide, def: AiSide, move: AiMove, field: AiField): Boolean {
        if (atk.ability == "No Guard" || def.ability == "No Guard") return true
        if (move.accuracy == 0) return true
        if ((move.effect == E.THUNDER || move.name in STORM_MOVES) && "Rain" in field.w) return true
        if (move.name == "Toxic" && "Poison" in atk.types) return true
        return false
    }

    private fun accuracyOf(atk: AiSide, def: AiSide, move: AiMove, field: AiField): Int {
        if (move.accuracy == 0) return 0xFFFF
        var acc = move.accuracy.toDouble()
        if (move.effect == E.THUNDER && "Sun" in field.w) acc = 50.0
        val defAbil = defAbility(atk, def)
        if (defAbil == "Wonder Skin" && move.split == SPLIT_STATUS) acc = min(acc, 50.0)
        if (atk.ability == "Compound Eyes") acc = acc * 13 / 10
        if (atk.ability == "Illuminate") acc = acc * 12 / 10
        if (atk.ability == "Hustle" && move.split == SPLIT_PHYSICAL) acc = acc * 8 / 10
        if (atk.ability == "Victory Star") acc = acc * 11 / 10
        if ((defAbil == "Sand Veil" && field.weather == "Sand") || (defAbil == "Snow Cloak" && isHail(field))) acc = acc * 8 / 10
        if (atk.item == "Wide Lens") acc = acc * 11 / 10
        if (def.item == "Bright Powder" || def.item == "Lax Incense") acc = acc * 9 / 10
        if (field.isGravity) acc = acc * 5 / 3
        return floor(acc).toInt()
    }

    // CalcAIAccuracy: recharge moves count as 49, rampages a point lower.
    private fun aiAccuracy(atk: AiSide, def: AiSide, move: AiMove, field: AiField): Int {
        val acc = accuracyOf(atk, def, move, field)
        if (move.effect == E.RECHARGE) return 49
        if (move.effect == E.RAMPAGE || move.effect == E.ROLLOUT) return acc - 1
        if (move.name == "Final Gambit") return acc - 5
        return acc
    }

    // #endregion
    // #region damage, KOs and the strongest move

    private fun isSuckerPunchOkay(turn: AiTurn, atk: AiSide, move: AiMove, def: AiSide): Boolean {
        val predicted = turn.predicted(def)
        if (predicted == null || predicted.split == SPLIT_STATUS) return false
        return hitsFirst(turn, atk, move, def)
    }

    // The AI's damage for one use of the move (AI_CalcDmg through CalcFinalAIMoveDamage, 1 hit).
    private fun moveDamage(turn: AiTurn, atk: AiSide, def: AiSide, i: Int): Int = turn.memo("dmg" + atk.key + i) {
        moveDamageNow(turn, atk, def, i)
    }

    private fun moveDamageNow(turn: AiTurn, atk: AiSide, def: AiSide, i: Int): Int {
        val move = atk.moves.getOrNull(i)
        val field = turn.battle.field
        if (move == null || move.split == SPLIT_STATUS || move.power == 0) return 0
        if (move.effect == E.FAKE_OUT && !atk.firstTurn) return 0
        // Radical Red fills its damage numbers before it predicts moves, so on a first turn Sucker Punch (which needs
        // a prediction) counts 0, and so do your Counter, Mirror Coat and Metal Burst.
        if (move.effect == E.SUCKER_PUNCH) return 0
        if (atk.isPlayer && (move.effect == E.COUNTER || move.effect == E.MIRROR_COAT)) return 0
        if (move.effect == E.OHKO) {
            return if (isOHKOUsable(atk, def, move, field)) (if (!atk.isPlayer || moveWillHit(atk, def, move, field)) def.hp else 1) else 0
        }
        if (move.name == "Final Gambit") return min(atk.hp, def.hp)
        if (move.effect == E.PSYWAVE) return min(atk.level, def.hp)
        if (move.effect == E.BURN_UP && "Fire" !in atk.types) return 0
        if (field.terrain == "Psychic" && priorityOf(atk, move, field) > 0 && isGrounded(def, field)) return 0
        if (defAbility(atk, def) in setOf("Dazzling", "Queenly Majesty", "Armor Tail") && priorityOf(atk, move, field) > 0) return 0
        if (move.effect == E.COUNTER || move.effect == E.MIRROR_COAT) return counterDamage(turn, atk, def, move)
        var damage: Int
        if (move.effect in FIXED_DAMAGE_EFFECTS) {
            damage = move.maxDamage
            if (move.effect == E.ENDEAVOR) damage = max(0, def.hp - atk.hp)
        } else {
            val crit = move.critChance >= 1 || (move.critChance >= 0.5 && turn.coin("crit" + atk.key + i, 0.5))
            val hits = aiHitCount(atk, move)
            val oneHit = (if (crit) move.maxCritDamage else move.maxDamage) / calcHitCount(move)
            damage = (oneHit * 93 / 100) * hits
            if (hits <= 1 && move.hits <= 1 && def.hp == def.maxHP && (def.item == "Focus Sash" ||
                    defAbility(atk, def) == "Sturdy" || move.effect == E.FALSE_SWIPE)
            ) {
                damage = min(damage, def.hp - 1)
            } else if (move.effect == E.FALSE_SWIPE) {
                damage = min(damage, def.hp - 1)
            }
        }
        return min(damage, def.hp)
    }

    // Counter and Mirror Coat: twice the foe's strongest (or predicted) move of the right kind.
    private fun counterDamage(turn: AiTurn, atk: AiSide, def: AiSide, move: AiMove): Int {
        if (turn.counting) return 0
        turn.counting = true
        val strongest = strongestMove(turn, def, atk)
        val basis = def.moves.getOrNull(strongest)
        var damage = 0
        val split = if (move.effect == E.COUNTER && move.name != "Metal Burst") SPLIT_PHYSICAL else SPLIT_SPECIAL
        if (basis != null && basis.split != SPLIT_STATUS) {
            val hit = moveDamage(turn, def, atk, strongest)
            if (move.name == "Metal Burst") damage = hit * 3 / 2
            else if (basis.split == split) damage = hit * 2
            if (atk.ability == "Parental Bond") damage *= 2
        }
        turn.counting = false
        return min(damage, def.hp)
    }

    // CalcFinalAIMoveDamage for numHits uses on consecutive turns, with end-of-turn chip between.
    private fun finalDamage(turn: AiTurn, atk: AiSide, def: AiSide, i: Int, numHits: Int): Int {
        val d = moveDamage(turn, atk, def, i)
        if (d == 0 || numHits <= 0) return 0
        if (numHits == 1) return d
        val later = if (defAbility(atk, def) in setOf("Multiscale", "Shadow Shield") && def.hp == def.maxHP) d * 2 else d
        return min(d + later * (numHits - 1) + secondaryDamage(def, turn.battle.field) * (numHits - 1), def.hp)
    }

    private fun knocksOut(turn: AiTurn, atk: AiSide, def: AiSide, i: Int, hits: Int): Boolean {
        var numHits = hits
        val move = atk.moves.getOrNull(i)
        if (move == null || def.hp <= 0) return false
        val field = turn.battle.field
        if (def.ability == "Disguise" && !ignoresAbilities(atk) && def.abilityOn) numHits--
        if (needsCharge(atk, move, field)) numHits--
        if (move.effect == E.EXPLOSION) numHits = min(numHits, 1)
        if (move.effect == E.RECHARGE) numHits--
        if (numHits <= 0) return false
        if (move.effect == E.FUTURE_SIGHT && numHits <= 2) return false
        return finalDamage(turn, atk, def, i, numHits) >= def.hp
    }

    private fun badIdeaToMakeContact(atk: AiSide, def: AiSide): Boolean {
        if (atk.ability == "Long Reach" || atk.item == "Protective Pads") return false
        if (def.item == "Rocky Helmet") return true
        val ability = defAbility(atk, def)
        if (ability == "Effect Spore" && ("Grass" in atk.types || atk.ability == "Overcoat" || atk.item == "Safety Goggles")) return false
        return ability in CONTACT_PUNISHERS
    }

    private fun isRecoilMove(move: AiMove) = move.effect == E.RECOIL || move.effect == E.RECOIL_IF_MISS || move.recoil

    private fun isMultiHit(move: AiMove) =
        move.effect == E.MULTI_HIT || move.effect == E.DOUBLE_HIT || move.effect == E.TRIPLE_KICK || move.hits > 1

    // How many single hits Radical Red's AI damage counts a move as, and how many the engine's number covers.
    private fun aiHitCount(atk: AiSide, move: AiMove): Int {
        if (move.name in TWO_TO_FIVE_HIT_MOVES) return if (atk.ability == "Skill Link") 5 else 2
        if (move.name in TWO_HIT_MOVES) return 2
        if (move.effect == E.TRIPLE_KICK) return 6
        return 1
    }

    private fun calcHitCount(move: AiMove) = if (move.effect == E.TRIPLE_KICK) 6 else max(1, move.hits)

    // The player's side is the human: PickMoveHumanLikelyToChoose's order of preference.
    private fun shouldUseHumanLikelyMove(atk: AiSide, def: AiSide, field: AiField?) =
        atk.isPlayer || def.monsLeft <= 1 || (field != null && isTrappedBy(def, atk, field))

    private fun humanLikelyPreference(atk: AiSide, a: AiMove, b: AiMove): Int {
        val tests = listOf<(AiMove) -> Boolean>(
            { m ->
                m.chance * (if (atk.ability == "Serene Grace") 2 else 1) >= 50 && (m.drain != null || m.effect in setOf(
                    E.ABSORB, E.DREAM_EATER, E.SPEED_UP_1_HIT, E.SPECIAL_ATTACK_UP_HIT, E.DEFENSE_UP_2_HIT, E.DEFENSE_UP_HIT,
                    E.ATTACK_UP_HIT, E.HIGHER_OFFENSES_DEFENSES_UP_HIT,
                ))
            },
            { m -> m.effectiveness > 1 },
            { m -> m.effectiveness >= 1 },
            { m -> m.type in atk.types },
        )
        for (test in tests) {
            val ta = test(a)
            val tb = test(b)
            if (ta != tb) return if (ta) 1 else -1
        }
        if (a.bp != b.bp) return if (a.bp > b.bp) 1 else -1
        return 0
    }

    // The tie break between two equally damaging moves: does `challenger` take over from `current`?
    private fun winsDamageTie(turn: AiTurn, atk: AiSide, def: AiSide, challenger: Int, current: Int, key: String): Boolean {
        val field = turn.battle.field
        val a = atk.moves[challenger]!!
        val b = atk.moves[current]!!
        if (b.effect == E.FUTURE_SIGHT) return true
        // Compared as unsigned bytes, as the game does: a negative priority (Revenge, Avalanche) wins.
        val pa = priorityOf(atk, a, field) and 0xFF
        val pb = priorityOf(atk, b, field) and 0xFF
        if (pa > pb && a.effect != E.FUTURE_SIGHT) return true
        if (pa != pb) return false
        val accA = if (moveWillHit(atk, def, a, field)) 100 else aiAccuracy(atk, def, a, field)
        val accB = if (moveWillHit(atk, def, b, field)) 100 else aiAccuracy(atk, def, b, field)
        if (accA > accB && accB < 100) return true
        if (!(accA == accB || accA >= 100)) return false
        if (canBeChoiceLocked(atk)) return a.bp > b.bp
        fun prefer(test: (AiMove) -> Boolean): Int {
            val ta = test(a)
            val tb = test(b)
            return if (ta == tb) 0 else if (ta) 1 else -1
        }
        var verdict = 0
        if (badIdeaToMakeContact(atk, def)) {
            verdict = prefer { (it.flags and FLAG_CONTACT) == 0 }.takeIf { it != 0 }
                ?: if ((a.flags and FLAG_CONTACT) != 0 && (b.flags and FLAG_CONTACT) != 0) prefer { it.effect != E.MULTI_HIT } else 0
        }
        if (verdict == 0 && atk.ability != "Magic Guard") verdict = prefer { !isRecoilMove(it) }
        if (verdict == 0 && atk.ability != "Contrary") verdict = prefer { it.effect !in STAT_DROP_SELF_EFFECTS }
        if (verdict == 0 && shouldUseHumanLikelyMove(atk, def, field)) verdict = humanLikelyPreference(atk, a, b)
        if (verdict != 0) return verdict > 0
        return turn.coin(key, 0.5)
    }

    // Pursuit counts as hitting a switch when the target can switch and has switched before; you haven't yet as far
    // as anyone knows, so only against the AI.
    private fun isEffectivePursuit(atk: AiSide, def: AiSide, move: AiMove?, field: AiField?): Boolean =
        move != null && move.effect == E.PURSUIT && def.monsLeft > 1 && !(field != null && isTrappedBy(def, atk, field)) && !def.isPlayer

    private fun isOHKOUsable(atk: AiSide, def: AiSide, move: AiMove, field: AiField): Boolean {
        if (move.effect != E.OHKO) return true
        if (!moveWillHit(atk, def, move, field)) return false
        if (atk.level < def.level || defAbility(atk, def) == "Sturdy") return false
        return !(move.name == "Sheer Cold" && "Ice" in def.types)
    }

    // CalcStrongestMove: the highest AI damage number (1 hit, capped at the target's HP).
    private fun strongestMove(turn: AiTurn, atk: AiSide, def: AiSide): Int = turn.memo("strongest" + atk.key) {
        val field = turn.battle.field
        var strongest = 0
        var best = 0
        for (i in 0 until 4) {
            val move = atk.moves.getOrNull(i) ?: break
            if (!isUsable(atk, i, field) || move.power == 0 || !isOHKOUsable(atk, def, move, field)) continue
            val damage = moveDamage(turn, atk, def, i)
            if (damage > best) {
                strongest = i
                best = damage
            } else if (damage == best && i != strongest && move.effect != E.COUNTER && move.effect != E.MIRROR_COAT &&
                move.effect != E.FUTURE_SIGHT && !isEffectivePursuit(atk, def, atk.moves[strongest], field)
            ) {
                if (isEffectivePursuit(atk, def, move, field) ||
                    winsDamageTie(turn, atk, def, i, strongest, "strong" + atk.key + i + "-" + strongest)
                ) strongest = i
            }
        }
        strongest
    }

    private fun isStrongest(turn: AiTurn, atk: AiSide, def: AiSide, i: Int) = strongestMove(turn, atk, def) == i

    // CalcStrongestMoveGoesFirst: the most damaging of the moves that would hit first, or -1.
    private fun strongestGoesFirst(turn: AiTurn, atk: AiSide, def: AiSide): Int = turn.memo("strongestFirst" + atk.key) {
        val field = turn.battle.field
        var strongest = -1
        var best = 0
        for (i in 0 until 4) {
            val move = atk.moves.getOrNull(i)
            if (move == null || !isUsable(atk, i, field) || needsCharge(atk, move, field) || move.effect == E.FUTURE_SIGHT) continue
            if (!hitsFirst(turn, atk, move, def)) continue
            val damage = moveDamage(turn, atk, def, i)
            if (damage > best) {
                strongest = i
                best = damage
            } else if (damage == best && damage > 0 && strongest != -1) {
                if (winsDamageTie(turn, atk, def, i, strongest, "first" + atk.key + i + "-" + strongest)) strongest = i
            }
        }
        strongest
    }

    private fun canKnockOut(turn: AiTurn, atk: AiSide, def: AiSide): Boolean {
        if (atk.hp <= 0 || def.hp <= 0 || isIncapacitated(atk)) return false
        return knocksOut(turn, atk, def, strongestMove(turn, atk, def), 1)
    }

    private fun can2HKO(turn: AiTurn, atk: AiSide, def: AiSide): Boolean {
        if (atk.hp <= 0 || def.hp <= 0) return false
        return canKnockOut(turn, atk, def) || knocksOut(turn, atk, def, strongestMove(turn, atk, def), 2)
    }

    private fun canKnockOutWithChip(turn: AiTurn, atk: AiSide, def: AiSide) = canKnockOut(turn, atk, def) ||
        moveDamage(turn, atk, def, strongestMove(turn, atk, def)) + secondaryDamage(def, turn.battle.field) >= def.hp

    private fun anotherMoveKnocksOut(turn: AiTurn, atk: AiSide, def: AiSide, skip: Int): Boolean {
        if (isIncapacitated(atk)) return false
        for (i in 0 until 4) {
            if (i != skip && atk.moves.getOrNull(i) != null && isUsable(atk, i, turn.battle.field) && knocksOut(turn, atk, def, i, 1)) return true
        }
        return false
    }

    // PriorityMoveInMoveset as Radical Red has it: the highest priority the side's moves get.
    private fun priorityMoveInMoveset(side: AiSide, field: AiField): Int {
        var best = 0
        side.moves.forEachIndexed { i, move ->
            if (move == null || !isUsable(side, i, field)) return@forEachIndexed
            var p = 0
            if (move.name == "Extreme Speed") {
                p = 2
            } else if (side.ability == "Triage" && move.effect == E.ABSORB) {
                p = 3
            } else if (move.priority == 1 || (side.hp == side.maxHP && ((side.ability == "Blazing Soul" && move.type == "Fire") ||
                    (side.ability == "Gale Wings" && move.type == "Flying")))
            ) {
                p = 1
            }
            best = max(best, p)
        }
        return best
    }

    // MoveKnocksOutPossiblyGoesFirstWithBestAccuracy in Radical Red's (older) form.
    private fun knocksOutWithBestAccuracy(turn: AiTurn, atk: AiSide, def: AiSide, check: Int, checkFirst: Boolean): Boolean {
        val field = turn.battle.field
        var best = ArrayList<Int>()
        var bestAcc = 0
        var last = -1
        for (j in 0 until 4) {
            val move = atk.moves.getOrNull(j) ?: break
            if (!isUsable(atk, j, field)) continue
            var acc = aiAccuracy(atk, def, move, field)
            if (acc == 0xFFFF) acc = 100
            if (checkFirst && !hitsFirst(turn, atk, move, def)) continue
            if (!knocksOut(turn, atk, def, j, 1)) continue
            if ((moveWillHit(atk, def, move, field) && bestAcc <= 99) || acc > bestAcc) {
                best = arrayListOf(j)
                bestAcc = acc
                last = j
            } else if (acc == bestAcc) {
                val mine = priorityOf(atk, move, field)
                if (mine <= priorityOf(atk, atk.moves.getOrNull(last), field) ||
                    (hitsBefore(atk, PLAIN_MOVE, def, turn.predicted(def), turn.battle, turn) && mine < priorityMoveInMoveset(def, field))
                ) {
                    best.add(j)
                } else {
                    best = arrayListOf(j)
                }
                last = j
            }
        }
        return check in best
    }

    // #endregion
    // #region fighting styles

    private fun fightingStyle(side: AiSide, foe: AiSide): Int {
        var style = STYLE_NONE
        var attacks = 0
        var hazards = 0
        var screens = 0
        var statusMoves = 0
        var boosting = false
        var veil = false
        var phazing = false
        var leechSeed = false
        var protect = false
        for (i in 0 until 4) {
            val move = side.moves.getOrNull(i) ?: continue
            if (move.name == "Baton Pass" && side.monsLeft > 1) {
                style = STYLE_BATON_PASS
            } else {
                when (move.effect) {
                    E.ROAR -> phazing = true
                    E.HAZE, E.REMOVE_TARGET_STAT_CHANGES -> style = STYLE_PHAZING
                    E.WISH, E.HEAL_BELL -> style = STYLE_CLERIC
                    E.REFLECT -> if (move.name == "Aurora Veil") veil = !side.sideState.auroraVeil else screens++
                    E.LIGHT_SCREEN -> screens++
                    E.LEECH_SEED -> leechSeed = true
                    E.PROTECT -> protect = true
                    E.TRAP, E.MEAN_LOOK -> style = STYLE_STALL
                    else -> if (side.item.startsWith("Choice ") || side.ability == "Gorilla Tactics" || side.item == "Assault Vest") style = STYLE_KILL
                }
            }
            if (style != STYLE_NONE) break
            if (move.split != SPLIT_STATUS) attacks++
            if (move.effect in BOOSTING_EFFECTS) {
                boosting = true
            } else if (move.effect == E.SPIKES) {
                val foeSide = foe.sideState
                if ((move.name == "Stealth Rock" && !foeSide.sr) || (move.name == "Toxic Spikes" && foeSide.toxicSpikes < 2) ||
                    (move.name == "Sticky Web" && !foeSide.stickyWeb) || (move.name == "Spikes" && foeSide.spikes < 3)
                ) hazards++
            } else if (move.split == SPLIT_STATUS) {
                statusMoves++
            }
        }
        if (style != STYLE_NONE) return style
        // Radical Red: any screen makes a support screener.
        if (screens >= 1 || veil) return STYLE_SUPPORT_SCREENS
        if (hazards >= 1) return if (phazing) STYLE_PHAZING else STYLE_HAZARDS
        if (attacks >= 3) return if (boosting) STYLE_SETUP_STATS else if (statusMoves > 0) STYLE_SETUP_STATUS else STYLE_KILL
        if (leechSeed && protect) return STYLE_STALL
        if (attacks >= 2 && (boosting || statusMoves > 0 || phazing)) return if (boosting) STYLE_SETUP_STATS else STYLE_SETUP_STATUS
        return STYLE_STALL
    }

    private fun styleOf(turn: AiTurn, side: AiSide): Int = turn.memo("style" + side.key) {
        fightingStyle(side, if (side === turn.battle.ai) turn.battle.player else turn.battle.ai)
    }

    private fun isSweeper(style: Int) = style == STYLE_KILL || style == STYLE_SETUP_STATS || style == STYLE_SETUP_STATUS

    private fun isDamager(style: Int) = isSweeper(style) || style == STYLE_SETUP_SCREENS

    private fun isGoodToTaunt(style: Int) = style in setOf(
        STYLE_STALL, STYLE_SETUP_SCREENS, STYLE_BATON_PASS, STYLE_CLERIC, STYLE_SUPPORT_SCREENS, STYLE_HAZARDS,
    )

    // INCREASE_STATUS_VIABILITY(boost): what a status move's "+boost" is worth to this style.
    private fun statusBonus(turn: AiTurn, atk: AiSide, def: AiSide, boost: Int): Int = when (styleOf(turn, atk)) {
        STYLE_KILL -> 0
        // Radical Red adds the boost (CFRU master: a flat 3).
        STYLE_SETUP_STATS -> if (can2HKO(turn, def, atk)) 0 else 3 + boost
        STYLE_SETUP_STATUS, STYLE_PHAZING -> 4 + boost
        STYLE_STALL, STYLE_CLERIC -> 3 + boost
        STYLE_BATON_PASS -> if (boost >= 3) 1 else 0
        STYLE_SUPPORT_SCREENS, STYLE_SETUP_SCREENS -> 2 + boost
        STYLE_HAZARDS -> 3
        else -> 0
    }

    // #endregion
    // #region status and stat helpers

    private fun canBeSlept(atk: AiSide, def: AiSide, field: AiField): Boolean {
        val ability = defAbility(atk, def)
        if (def.status.isNotEmpty() || ability in setOf("Insomnia", "Vital Spirit", "Sweet Veil", "Comatose", "Purifying Salt")) return false
        if (ability == "Leaf Guard" && "Sun" in field.w) return false
        if ((field.terrain == "Electric" || field.terrain == "Misty") && isGrounded(def, field)) return false
        return !(ability == "Flower Veil" && "Grass" in def.types)
    }

    private fun canBeStatused(atk: AiSide, def: AiSide, field: AiField): Boolean {
        val ability = defAbility(atk, def)
        if (def.status.isNotEmpty() || ability in setOf("Comatose", "Purifying Salt")) return false
        if (ability == "Leaf Guard" && "Sun" in field.w) return false
        if (field.terrain == "Misty" && isGrounded(def, field)) return false
        return !(ability == "Flower Veil" && "Grass" in def.types)
    }

    private fun canBeParalyzed(atk: AiSide, def: AiSide, field: AiField) =
        canBeStatused(atk, def, field) && "Electric" !in def.types && defAbility(atk, def) != "Limber"

    private fun canBeBurned(atk: AiSide, def: AiSide, field: AiField) = canBeStatused(atk, def, field) && "Fire" !in def.types &&
        defAbility(atk, def) !in setOf("Water Veil", "Water Bubble", "Thermal Exchange")

    private fun canBePoisoned(atk: AiSide, def: AiSide, field: AiField): Boolean {
        if (!canBeStatused(atk, def, field) || defAbility(atk, def) in setOf("Immunity", "Pastel Veil")) return false
        if (atk.ability == "Corrosion") return true
        return "Poison" !in def.types && "Steel" !in def.types
    }

    // CanTransferItem for both Pokémon and both items: Mega Stones and Z-Crystals stay put.
    private fun canSwapItems(atk: AiSide, def: AiSide): Boolean {
        fun fixed(item: String) = item.isNotEmpty() && (pack.isMegaStone(item) || item.endsWith(" Z"))
        return !fixed(atk.item) && !fixed(def.item)
    }

    private fun canBeConfused(atk: AiSide, def: AiSide, field: AiField) =
        defAbility(atk, def) != "Own Tempo" && !(field.terrain == "Misty" && isGrounded(def, field))

    // The status a move gives (par, brn, psn, slp) can land on def.
    private fun canBeStatusedWith(atk: AiSide, def: AiSide, kind: String, field: AiField): Boolean = when (kind) {
        "par" -> canBeParalyzed(atk, def, field)
        "brn" -> canBeBurned(atk, def, field)
        "psn" -> canBePoisoned(atk, def, field)
        else -> canBeSlept(atk, def, field)
    }

    // GoodIdeaTo<Status>Self: the side would like the status (what a Synchronize foe passes back).
    private fun goodIdeaToStatusSelf(side: AiSide, kind: String, field: AiField): Boolean {
        if (!canBeStatusedWith(side, side, kind, field)) return false
        val ability = side.ability
        if (ability == "Quick Feet" || (ability == "Guts" && hasRealPhysical(side)) || hasMoveNamed(side, "Facade", "Psycho Shift")) return true
        if (kind == "par") return ability == "Marvel Scale"
        if (kind == "brn") return ability in setOf("Heatproof", "Magic Guard") || (ability == "Flare Boost" && hasSpecial(side))
        return ability in setOf("Marvel Scale", "Poison Heal", "Magic Guard") || (ability == "Toxic Boost" && hasRealPhysical(side))
    }

    // Status a foe would rather have, or shrug off (BadIdeaToParalyze/Burn/Poison/PutToSleep).
    private fun badIdeaToStatus(atk: AiSide, def: AiSide, kind: String, field: AiField): Boolean {
        val ability = defAbility(atk, def)
        val rain = ability == "Hydration" && "Rain" in field.w && def.item != "Utility Umbrella"
        if (kind == "slp") {
            return ability in setOf("Early Bird", "Shed Skin") || rain || (ability == "Synchronize" && canBeSlept(def, atk, field))
        }
        if (def.item == CURE_BERRIES[kind] || def.item == "Lum Berry") return true
        if (ability in setOf("Shed Skin", "Quick Feet") || rain) return true
        if (ability == "Synchronize" && canBeStatusedWith(def, atk, kind, field) && !goodIdeaToStatusSelf(atk, kind, field)) return true
        if (ability == "Marvel Scale" && hasPhysical(atk)) return true
        if (ability == "Natural Cure" && def.monsLeft > 1 && !isTrappedBy(def, atk, field)) return true
        if (ability == "Guts" && hasPhysical(def)) return true
        if (hasMoveNamed(def, "Facade", "Psycho Shift")) return true
        if (kind == "brn") return ability == "Magic Guard" || (ability == "Flare Boost" && hasSpecial(def))
        if (kind == "psn") {
            if (ability in setOf("Poison Heal", "Magic Guard") || (ability == "Toxic Boost" && hasPhysical(def))) return true
            return atk.ability == "Poison Touch" && hasMoveWhere(atk) { isDamaging(it) && (it.flags and FLAG_CONTACT) != 0 && it.effectiveness > 0 }
        }
        return false
    }

    // DoubleDamageWithStatusMoveInMovesetThatAffects: Hex and the like, which hit a statused foe twice as hard.
    private fun hasDoubleDamageMove(atk: AiSide) = hasMoveWhere(atk) { it.name in DOUBLE_DAMAGE_MOVES && it.effectiveness > 0 }

    // CalcSecondaryEffectChance: none for the moves Sheer Force boosts, doubled by Serene Grace.
    private fun chanceOf(atk: AiSide, move: AiMove): Int {
        if (atk.ability == "Sheer Force" && move.name in SHEER_FORCE_MOVES) return 0
        return move.chance * (if (atk.ability == "Serene Grace") 2 else 1)
    }

    // CanStatBeLowered (Negatives): not at -6 (+6 with Contrary), and Radical Red tests only Clear Body, and Hyper
    // Cutter for Attack.
    private fun canStatBeLowered(atk: AiSide, def: AiSide, stat: String): Boolean {
        val ability = defAbility(atk, def)
        if (ability in CLEAR_BODY_ABILITIES || (stat == "atk" && ability == "Hyper Cutter")) return false
        return if (ability == "Contrary") stage(def, stat) < 12 else stage(def, stat) > 0
    }

    private fun canStatBeRaised(side: AiSide, stat: String) = stage(side, stat) < 12

    // The likely kind of move this side uses (BankLikelyToUseMoveSplit).
    private fun likelySplit(turn: AiTurn, side: AiSide): Int {
        val sweeper = isSweeper(styleOf(turn, side))
        val atk = side.stats["atk"] ?: 0
        val spa = side.stats["spa"] ?: 0
        if (hasPhysical(side)) return if (sweeper && atk > spa) SPLIT_PHYSICAL else SPLIT_STATUS
        if (hasSpecial(side)) return if (sweeper && spa > atk) SPLIT_SPECIAL else SPLIT_STATUS
        return SPLIT_STATUS
    }

    // BadIdeaToRaise<Stat>.
    private fun badIdeaToRaiseStat(turn: AiTurn, atk: AiSide, def: AiSide, stat: String): Boolean {
        val predicted = turn.predicted(def)
        val field = turn.battle.field
        if (predicted != null && predicted.effect in setOf(E.ROAR, E.HAZE, E.REMOVE_TARGET_STAT_CHANGES)) return true
        if (predicted != null && (predicted.effect == E.SLEEP || predicted.effect == E.YAWN) &&
            (moveWillHit(def, atk, predicted, field) || accuracyOf(def, atk, predicted, field) >= 80) && def.speed < atk.speed
        ) return true
        if (def.ability == "Unaware" && !willFaintFromChip(def, field)) return true
        if (stat == "atk" || stat == "def") {
            val best = atk.moves.getOrNull(strongestMove(turn, atk, def))
            if (hasMoveNamed(def, if (stat == "atk") "King's Shield" else "Obstruct") && best != null && (best.flags and FLAG_CONTACT) != 0) return true
        }
        if (stat == "spe" && turn.battle.trickRoom) return true
        return false
    }

    private fun goodIdeaToRaise(turn: AiTurn, atk: AiSide, def: AiSide, stat: String): Int {
        if (badIdeaToRaiseStat(turn, atk, def, stat)) return 0
        return when (stat) {
            "atk" -> if (hasRealPhysical(atk)) 1 else 0
            "spa" -> if (hasSpecial(atk)) 1 else 0
            "def" -> if (likelySplit(turn, def) == SPLIT_PHYSICAL) 1 else if (hasMoveNamed(atk, "Body Press")) 2 else 0
            "spd" -> if (likelySplit(turn, def) == SPLIT_SPECIAL) 1 else 0
            "spe" -> if (atk.speed <= def.speed) 1 else 0
            "acc" -> if (hasMoveWhere(atk) { it.accuracy != 0 && it.accuracy < 90 }) 1 else 0
            else -> 1
        }
    }

    // GoodIdeaToLower<Stat>.
    private fun goodIdeaToLower(turn: AiTurn, def: AiSide, atk: AiSide, move: AiMove, stat: String): Boolean {
        val field = turn.battle.field
        val ability = defAbility(atk, def)
        if (ability in CLEAR_BODY_ABILITIES || (stat == "atk" && ability == "Hyper Cutter") || ability == "Contrary") return false
        if (stat != "spe" && stat != "acc" && ability in setOf("Defiant", "Competitive")) return false
        if (stat == "eva") {
            if (stage(def, "eva") > 6) return true
            if (!hasMoveWhere(atk) { isDamaging(it) && accuracyOf(atk, def, it, field) < 90 }) return false
            return if (atk.speed >= def.speed) !canKnockOut(turn, def, atk) else !can2HKO(turn, def, atk)
        }
        if (!hitsFirst(turn, atk, move, def) && canKnockOut(turn, atk, def)) return false
        return when (stat) {
            "atk" -> !willFaintFromChip(def, field) && stage(def, "atk") > 4 && hasRealPhysical(def)
            "def" -> stage(def, "def") > 4 && hasPhysical(atk)
            "spa" -> stage(def, "spa") > 4 && hasSpecial(def)
            "spd" -> stage(def, "spd") > 4 && hasSpecial(atk)
            "spe" -> atk.speed <= def.speed
            else -> true
        }
    }

    // ShouldTryToSetUpStat as Radical Red has it.
    private fun shouldTrySetUp(turn: AiTurn, atk: AiSide, def: AiSide, move: AiMove, stat: String): Boolean {
        val field = turn.battle.field
        // Radical Red's "any stat" (0xFF, Spectral Thief): just whether the move affects the foe.
        if (stat == "any") return move.effectiveness != 0.0
        if (def.ability == "Unaware" && !hasMoveNamed(atk, "Stored Power", "Power Trip") &&
            !(stat == "def" && hasMoveNamed(atk, "Body Press"))
        ) return false
        if (willFaintFromChip(atk, field)) return false
        if (atk.status == "tox" && atk.ability !in setOf("Poison Heal", "Magic Guard", "Toxic Boost") && healthPercent(atk) <= 75) return false
        val undoes = hasEffect(def, E.HAZE, E.PSYCH_UP) || hasMoveNamed(def, "Topsy-Turvy") ||
            ((hasMoveNamed(def, "Roar", "Whirlwind") || (hasMoveNamed(def, "Dragon Tail") && "Fairy" !in atk.types) ||
                (hasMoveNamed(def, "Circle Throw") && "Ghost" !in atk.types)) && atk.monsLeft > 1) ||
            (hasMoveWhere(def) { priorityOf(def, it, field) > 0 } && can2HKO(turn, def, atk))
        if (undoes && turn.coin("setUp" + turn.phase + atk.key + indexOf(atk, move) + stat, 0.5)) return false
        val predicted = turn.predicted(def)
        if (hitsFirst(turn, atk, move, def)) {
            if (canKnockOutWithChip(turn, def, atk)) return false
            return stage(atk, stat) <= 8
        }
        if (predicted != null && predicted.effect == E.SEMI_INVULNERABLE) return true
        if (stat == "spe" && stage(atk, "spe") <= 8 && !(predicted != null && knocksOut(turn, def, atk, indexOf(def, predicted), 1))) {
            var boost = if (move.effect in setOf(E.SPEED_UP_2, E.DRAGON_DANCE) && move.name != "Dragon Dance") 2 else 1
            if (atk.ability == "Simple") boost *= 2
            if (atk.speed * (2 + boost) / 2 >= def.speed) return true
        }
        if (can2HKO(turn, def, atk)) return false
        return stage(atk, stat) <= 9
    }

    // IncreaseStatViability: what setting `stat` up is worth to this style.
    @Suppress("UNUSED_PARAMETER")
    private fun statBonus(turn: AiTurn, atk: AiSide, def: AiSide, move: AiMove, stat: String, limit: Int, boost: Int): Int =
        when (styleOf(turn, atk)) {
            STYLE_KILL -> {
                if (shouldTrySetUp(turn, atk, def, move, stat)) 3 + boost
                else if (isDamaging(move) && (stat == "atk" || stat == "spa")) 1 else 0
            }
            STYLE_SETUP_STATS -> if (shouldTrySetUp(turn, atk, def, move, stat)) 3 + boost else 0
            STYLE_PHAZING -> {
                if (hasEffect(atk, E.HAZE)) 0
                else if (shouldTrySetUp(turn, atk, def, move, stat)) statusBonus(turn, atk, def, 1) else 0
            }
            // Radical Red: a Baton Passer sets up to +4 (evasion +6) without ShouldTryToSetUpStat.
            STYLE_BATON_PASS -> when (stat) {
                "eva" -> if (stage(atk, "eva") < 12) 6 else 0
                "def", "spd" -> if (stage(atk, stat) < 10) 4 else 0
                else -> if (stage(atk, stat) < 10) 5 else 0
            }
            else -> if (shouldTrySetUp(turn, atk, def, move, stat)) statusBonus(turn, atk, def, 1) else 0
        }

    // #endregion
    // #region AIScript_Negatives: points off moves that fail or waste the turn

    private fun scoreNegatives(turn: AiTurn, atk: AiSide, def: AiSide, i: Int, initial: Int): Int {
        var score = initial
        val move = atk.moves[i]!!
        val field = turn.battle.field
        val flags = turn.flags
        val goodAI = flags > 1
        val ability = defAbility(atk, def)
        val predicted = turn.predicted(def)
        val effect = move.effect
        val damaging = isDamaging(move)
        fun down(amount: Int): Int {
            score = max(0, score - amount)
            return score
        }

        if (!isSelfTargeting(move)) {
            if (field.isGravity && move.name in GRAVITY_BANNED) return 0
            if (move.name in POWDER_MOVES && ("Grass" in def.types || def.ability == "Overcoat" || def.item == "Safety Goggles")) down(10)
            // Radical Red: Ground moves (status ones too) do nothing to a Pokémon in the air, except Thousand Arrows,
            // Bonemerang, Bone Zone and Mold Breaker (Levitate is left to the type check below).
            if (move.type == "Ground" && !isGrounded(def, field) && move.name != "Thousand Arrows" && move.name != "Bonemerang" &&
                atk.ability != "Bone Zone" && !ignoresAbilities(atk) && def.ability != "Levitate"
            ) return 0
            if (ability.isNotEmpty() && move.name !in ABILITY_IGNORING_MOVES) {
                // Hardcore Erika's rematch: her Grass moves ignore immunities, Sap Sipper included.
                val grassIgnores = move.type == "Grass" &&
                    Regex("grass type moves.*ignore immunities", RegexOption.IGNORE_CASE).containsMatchIn(turn.battle.battleEffect)
                val absorbing = ABSORBING[move.type]
                if (absorbing != null && ability in absorbing && !grassIgnores) return down(20)
                if (ability == "Mountaineer" && move.type == "Rock" && move.split != SPLIT_STATUS) return down(20)
                if (ability == "Wind Rider" && move.name in WIND_MOVES) return down(10)
                if (ability == "Good as Gold" && move.split == SPLIT_STATUS && (move.target and MOVE_TARGET_BOTH.inv()) == 0) return down(20)
                if (ability == "Purifying Salt" && effect in SET_STATUS_EFFECTS) return down(10)
                if (ability == "Aroma Veil" && move.name in setOf("Disable", "Attract", "Encore", "Torment", "Taunt", "Heal Block")) return down(20)
                if ((ability == "Soundproof" && move.sound) || (ability == "Bulletproof" && move.bullet)) return down(10)
                if (ability in setOf("Dazzling", "Queenly Majesty", "Armor Tail") && priorityOf(atk, move, field) > 0) return down(10)
                if (ability == "Magic Bounce" && (move.flags and FLAG_MAGIC_COAT) != 0) return down(20)
                val lowers = effect in RR_STAT_LOWER_EFFECTS
                if (ability == "Contrary" && lowers) return down(20)
                if (ability in setOf("Clear Body", "Full Metal Body", "White Smoke") && (lowers || move.name == "Parting Shot")) return down(10)
                if (ability == "Hyper Cutter" && (effect == E.ATTACK_DOWN || effect == E.ATTACK_DOWN_2)) return down(10)
                if (ability == "Defiant" && move.split == SPLIT_STATUS && lowers && canStatBeRaised(def, "atk") && hasRealPhysical(def)) return down(8)
                if (ability == "Competitive" && move.split == SPLIT_STATUS && lowers && canStatBeRaised(def, "spa") && hasSpecial(def)) return down(8)
                if (ability == "Justified" && move.type == "Dark" && move.split != SPLIT_STATUS && canStatBeRaised(def, "atk") &&
                    !knocksOut(turn, atk, def, i, 2) && hasRealPhysical(def)
                ) down(4)
                if (ability == "Steam Engine" && (move.type == "Fire" || move.type == "Water") && move.split != SPLIT_STATUS &&
                    canStatBeRaised(def, "spe") && !knocksOut(turn, atk, def, i, 1) && atk.speed > def.speed
                ) down(if (knocksOut(turn, atk, def, i, 2)) 5 else 9)
                if (effect in SET_STATUS_EFFECTS && (ability == "Comatose" || (ability == "Shields Down" && def.name.startsWith("Minior") &&
                        "Core" !in def.name) || (ability == "Leaf Guard" && "Sun" in field.w && def.item != "Utility Umbrella"))
                ) return down(10)
                if ((effect == E.SLEEP || effect == E.YAWN) && ability == "Sweet Veil") return down(10)
            }
            if (atk.ability == "Prankster" && move.split == SPLIT_STATUS && (move.target and MOVE_TARGET_FOES_FIELD) == 0 &&
                "Dark" in def.types
            ) return down(10)
            if (isGrounded(def, field)) {
                if (field.terrain == "Electric" && (effect == E.SLEEP || effect == E.YAWN)) return down(10)
                // Radical Red's table has the damaging confusion moves (Psybeam), not Confuse Ray.
                if (field.terrain == "Misty" && (effect in SET_STATUS_EFFECTS || effect == E.CONFUSE_HIT || effect == E.SWAGGER ||
                        effect == E.FLATTER)
                ) return down(10)
                if (field.terrain == "Psychic" && priorityOf(atk, move, field) > 0) return down(10)
            }
        }
        if (move.split != SPLIT_STATUS && ((field.weather == "Harsh Sunshine" && move.type == "Water" && move.name != "Hydro Steam") ||
                (field.weather == "Heavy Rain" && move.type == "Fire"))
        ) return down(20)

        fun standardDamage(): Int {
            if (damaging && move.effectiveness == 0.0) down(15)
            return score
        }
        fun statusChecks(canApply: Boolean): Int {
            if (!canApply) down(10)
            return score
        }
        fun selfBoost(stats: List<String>, noLockCheck: Boolean = false): Int {
            val locked = !noLockCheck && goodAI && canBeChoiceLocked(atk)
            if (locked || atk.ability == "Contrary") return down(10)
            if (!stats.any { stat ->
                    canStatBeRaised(atk, stat) && (stat != "atk" || hasRealPhysical(atk)) && (stat != "spa" || hasSpecial(atk))
                }
            ) down(10)
            return score
        }
        val itemFixed = Regex("ite$|ium Z$")

        // Moves that fail now: nothing has been used yet this battle, the AI's bench is assumed healthy, or this foe or
        // field rules them out.
        fun failsNow(): Boolean {
            val first = hitsFirst(turn, atk, move, def)
            return when (move.name) {
                "Splash", "Conversion 2", "Sketch", "Belch", "Recycle", "Spit Up", "Swallow", "Stuff Cheeks", "Healing Wish",
                "Lunar Dance", "Celebrate", "Hold Hands", "Happy Hour" -> true
                "Mimic", "Spite" -> first || predicted == null
                "Disable" -> def.item == "Mental Herb" || first || predicted == null
                "Last Resort" -> atk.moves.count { it != null } > 1
                "Purify" -> def.status.isEmpty()
                "Heal Pulse", "Floral Healing" -> true
                "Heal Bell", "Aromatherapy", "Sparkly Swirl" -> atk.status.isEmpty() || (move.name == "Heal Bell" && atk.ability == "Soundproof")
                "Refresh" -> atk.status !in setOf("psn", "tox", "brn", "par")
                "Psycho Shift" -> atk.status !in setOf("psn", "tox", "brn", "par") || def.status.isNotEmpty()
                "Magic Coat" -> !hasMoveWhere(def) { (it.flags and FLAG_MAGIC_COAT) != 0 }
                "Attract", "Captivate" -> atk.gender.isEmpty() || def.gender.isEmpty() || atk.gender == "N" || def.gender == "N" ||
                    atk.gender == def.gender || (move.name == "Attract" && ability in setOf("Oblivious", "Aroma Veil"))
                "Nightmare" -> !isAsleep(def) && def.ability != "Comatose"
                "Mean Look", "Block", "Spider Web" -> isTrappedBy(def, atk, field)
                "Lock-On", "Mind Reader" -> atk.ability == "No Guard" || def.ability == "No Guard"
                "Taunt" -> ability == "Aroma Veil"
                "Trick", "Switcheroo" -> (atk.item.isEmpty() && def.item.isEmpty()) || ability == "Sticky Hold" ||
                    itemFixed.containsMatchIn(atk.item) || itemFixed.containsMatchIn(def.item)
                "Bestow" -> atk.item.isEmpty() || def.item.isNotEmpty() || itemFixed.containsMatchIn(atk.item)
                "Magnet Rise" -> field.isGravity || atk.item == "Iron Ball" || !isGrounded(atk, field)
                "Defog" -> def.sideState.sr || def.sideState.spikes != 0 || def.sideState.toxicSpikes != 0 || def.sideState.stickyWeb
                "Powder" -> !hasMoveWhere(def) { it.type == "Fire" && it.split != SPLIT_STATUS }
                "Memento" -> stage(def, "atk") == 0 && stage(def, "spa") == 0
                "Flower Shield" -> "Grass" !in atk.types
                "Burn Up", "Double Shock" -> "Fire" !in atk.types
                "Poltergeist" -> def.item.isEmpty()
                else -> false
            }
        }
        if (failsNow()) return down(10)
        if (move.name == "Foresight" || move.name == "Odor Sleuth" || move.name == "Miracle Eye") {
            if (!((if (move.name == "Miracle Eye") "Dark" else "Ghost") in def.types && stage(def, "eva") >= 5)) down(9)
            return score
        }
        if (move.name == "Laser Focus" && ability in setOf("Battle Armor", "Shell Armor")) return down(8)
        if (move.name == "Torment" && def.item == "Mental Herb") return down(6)
        // Synchronoise (Radical Red's effect 254): it only hits a target sharing a type with the user.
        if (move.name == "Synchronoise") {
            if (def.item == "Ring Target" || atk.types.any { it in def.types }) return standardDamage()
            return down(10)
        }

        when (effect) {
            E.SLEEP -> {
                if (move.effectiveness == 0.0) down(10)
                return statusChecks(canBeSlept(atk, def, field))
            }
            E.YAWN -> return statusChecks(canBeSlept(atk, def, field))
            E.PARALYZE -> {
                if (!canBeParalyzed(atk, def, field)) return down(10)
                if (move.name != "Glare" && move.effectiveness == 0.0) down(10)
                return score
            }
            E.WILL_O_WISP -> return statusChecks(canBeBurned(atk, def, field))
            E.POISON, E.TOXIC -> {
                if (move.effectiveness == 0.0 && atk.ability != "Corrosion") return down(10)
                return statusChecks(canBePoisoned(atk, def, field))
            }
            E.CONFUSE, E.SWAGGER, E.FLATTER -> return statusChecks(canBeConfused(atk, def, field))
            E.ATTACK_UP, E.ATTACK_UP_2 -> return selfBoost(listOf("atk"))
            E.DEFENSE_UP, E.DEFENSE_UP_2, E.DEFENSE_CURL -> return selfBoost(listOf("def"))
            E.SPEED_UP, E.SPEED_UP_2 -> {
                if (turn.battle.trickRoom) return down(10)
                return selfBoost(listOf("spe"))
            }
            E.SPECIAL_ATTACK_UP, E.SPECIAL_ATTACK_UP_2 -> {
                // Radical Red: Growth, Work Up, Rototiller and Gear Up are this effect and boost either attack; no
                // Choice-lock check here.
                if (atk.ability == "Contrary") return down(10)
                if (move.name in setOf("Growth", "Work Up", "Rototiller", "Gear Up")) {
                    if (move.name == "Rototiller" && ("Grass" !in atk.types || !isGrounded(atk, field))) return down(10)
                    if (move.name == "Gear Up" && atk.ability !in setOf("Plus", "Minus")) return score
                    if (!((stage(atk, "atk") < 12 && hasRealPhysical(atk)) || (stage(atk, "spa") < 12 && hasSpecial(atk)))) down(10)
                } else if (stage(atk, "spa") >= 12 || !hasSpecial(atk)) {
                    down(10)
                }
                return score
            }
            E.SPECIAL_DEFENSE_UP, E.SPECIAL_DEFENSE_UP_2 -> return selfBoost(listOf("spd"))
            E.ACCURACY_UP, E.ACCURACY_UP_2 -> {
                selfBoost(listOf("acc"))
                if (goodAI && score > 0 && !hasMoveWhere(atk) { it.accuracy != 0 && it.accuracy < 100 }) down(1)
                return score
            }
            E.EVASION_UP, E.EVASION_UP_2, E.MINIMIZE -> return selfBoost(listOf("eva"), true)
            E.ATK_SPATK_UP -> {
                if (move.name == "Fickle Beam") return standardDamage()
                return selfBoost(listOf("atk", "spa"))
            }
            E.ATK_ACC_UP -> return selfBoost(listOf("atk", "acc"))
            E.DEF_SPD_UP, E.COSMIC_POWER -> return selfBoost(listOf("def", "spd"))
            E.BULK_UP -> {
                // Radical Red: only when nothing can go up any more (Coil checks accuracy too).
                if (atk.ability == "Contrary" || (goodAI && canBeChoiceLocked(atk))) return down(10)
                if ((stage(atk, "atk") >= 12 || !hasRealPhysical(atk)) && stage(atk, "def") >= 12 &&
                    (move.name != "Coil" || stage(atk, "acc") >= 12)
                ) down(10)
                return score
            }
            E.CALM_MIND -> {
                if ((move.name == "Quiver Dance" || move.name == "Geomancy") && turn.battle.trickRoom) return down(10)
                if (move.name == "Quiver Dance" && def.ability == "Dancer") return down(10)
                return selfBoost(if (move.name == "Quiver Dance" || move.name == "Geomancy") listOf("spa", "spd", "spe") else listOf("spa", "spd"))
            }
            E.DRAGON_DANCE -> {
                if (turn.battle.trickRoom) return down(10)
                if (move.name == "Dragon Dance" && def.ability == "Dancer") return down(10)
                return selfBoost(if (move.name == "Shell Smash") listOf("atk", "spa", "spe") else listOf("atk", "spe"))
            }
            E.EXTREME_EVOBOOST -> {
                if (move.name == "Clangorous Soul" && atk.hp <= atk.maxHP / 3) return down(10)
                return selfBoost(listOf("atk", "def", "spa", "spd", "spe"), true)
            }
            E.BELLY_DRUM -> {
                if (atk.ability == "Contrary" || healthPercent(atk) <= 50) down(10)
                return score
            }
            E.CURSE -> {
                if ("Ghost" in atk.types) {
                    if (healthPercent(atk) <= 50) down(6)
                } else if (!canStatBeRaised(atk, "atk") && !canStatBeRaised(atk, "def") && stage(atk, "spe") <= 0) {
                    down(10)
                }
                return score
            }
            E.ATTACK_DOWN, E.ATTACK_DOWN_2 -> {
                // Radical Red tests no abilities here; Noble Roar, Tearful Look and Play Nice take either attack.
                var lowerAtk = stage(def, "atk") > 0 && hasPhysicalAttack(def)
                if (move.name in setOf("Noble Roar", "Tearful Look", "Play Nice")) {
                    lowerAtk = lowerAtk || (stage(def, "spa") > 0 && hasSpecial(def))
                } else if (move.name == "Venom Drench") {
                    if (def.status != "psn" && def.status != "tox") return down(10)
                    lowerAtk = lowerAtk || stage(def, "spe") > 0 || (stage(def, "spa") > 0 && hasSpecial(def))
                }
                if (!lowerAtk) down(10)
                return score
            }
            E.DEFENSE_DOWN, E.DEFENSE_DOWN_2 -> return statusChecks(canStatBeLowered(atk, def, "def"))
            E.SPEED_DOWN, E.SPEED_DOWN_2 -> return statusChecks(canStatBeLowered(atk, def, "spe"))
            E.SPECIAL_ATTACK_DOWN, E.SPECIAL_ATTACK_DOWN_2 -> {
                if (!canStatBeLowered(atk, def, "spa") || !hasSpecial(def)) down(10)
                return score
            }
            E.SPECIAL_DEFENSE_DOWN, E.SPECIAL_DEFENSE_DOWN_2 -> return statusChecks(canStatBeLowered(atk, def, "spd"))
            E.ACCURACY_DOWN, E.ACCURACY_DOWN_2 -> return statusChecks(canStatBeLowered(atk, def, "acc"))
            E.EVASION_DOWN, E.EVASION_DOWN_2 -> return statusChecks(canStatBeLowered(atk, def, "eva"))
            E.TICKLE -> {
                if ((stage(def, "atk") == 0 || !hasPhysicalAttack(def)) && stage(def, "def") == 0) down(10)
                return score
            }
            E.REFLECT -> {
                if (move.name == "Aurora Veil") {
                    if (atk.sideState.auroraVeil || !isHail(field)) down(10)
                    return score
                }
                if (atk.sideState.reflect) down(10)
                return score
            }
            E.LIGHT_SCREEN -> {
                if (atk.sideState.lightScreen) down(10)
                return score
            }
            E.SPIKES -> {
                // Radical Red's own rule: Stone Axe and Ceaseless Edge are attacks; with three or fewer of your Pokémon
                // left (or your Magic Bounce) nothing counts against hazards; a foe that can clear or reflect them costs
                // 10 half the time; Stealth Rock already up costs 20.
                if (move.name == "Stone Axe" || move.name == "Ceaseless Edge") return standardDamage()
                if (def.monsLeft <= 3 || def.ability == "Magic Bounce") return score
                // A fight where hazards can't be removed (Hardcore Clair and Lance) only counts Magic Coat.
                val hazardsStay = Regex("Can't remove hazards", RegexOption.IGNORE_CASE).containsMatchIn(turn.battle.battleEffect)
                if (((!hazardsStay && ((hasMoveNamed(def, "Rapid Spin") && "Ghost" !in atk.types) || hasMoveNamed(def, "Defog"))) ||
                        hasMoveNamed(def, "Magic Coat")) &&
                    !turn.coin("hazardClear" + turn.phase + atk.key + i, 0.5)
                ) down(10)
                if (move.name == "Stealth Rock" && def.sideState.sr) {
                    down(20)
                } else if ((move.name == "Sticky Web" && def.sideState.stickyWeb) || (move.name == "Spikes" && def.sideState.spikes >= 3) ||
                    (move.name == "Toxic Spikes" && def.sideState.toxicSpikes >= 2)
                ) {
                    down(10)
                } else if (goodAI && ((move.name == "Spikes" && def.sideState.spikes >= 1) || (move.name == "Toxic Spikes" &&
                        def.sideState.toxicSpikes == 1)) && hasMoveNamed(def, "Rapid Spin", "Defog", "Mortal Spin", "Tidy Up")
                ) {
                    down(10)
                }
                return score
            }
            E.SANDSTORM -> {
                if (field.weather == "Sand" || field.weather in setOf("Harsh Sunshine", "Heavy Rain", "Strong Winds")) down(10)
                return score
            }
            E.RAIN_DANCE -> {
                if ("Rain" in field.w || field.weather in setOf("Harsh Sunshine", "Heavy Rain", "Strong Winds")) down(10)
                return score
            }
            E.SUNNY_DAY -> {
                if ("Sun" in field.w || field.weather in setOf("Harsh Sunshine", "Heavy Rain", "Strong Winds")) down(10)
                return score
            }
            E.HAIL -> {
                if (isHail(field) || field.weather in setOf("Harsh Sunshine", "Heavy Rain", "Strong Winds")) down(10)
                return score
            }
            E.SET_TERRAIN -> {
                val terrain = field.terrain
                if (!terrain.isNullOrEmpty() && move.name.startsWith(terrain)) down(10)
                return score
            }
            E.RESTORE_HP, E.MORNING_SUN, E.SWALLOW -> {
                // Radical Red has no "heal at full HP to stall a foe taking chip damage" rule.
                if (atk.hp == atk.maxHP) down(10) else if (healthPercent(atk) >= 90) down(9)
                return score
            }
            E.REST -> {
                // Radical Red's CanRest: Insomnia and Vital Spirit don't stop it.
                if (atk.hp == atk.maxHP || atk.status == "slp" || atk.ability in setOf("Purifying Salt", "Sweet Veil", "Comatose") ||
                    (atk.ability == "Leaf Guard" && "Sun" in field.w && atk.item != "Utility Umbrella") ||
                    ((field.terrain == "Electric" || field.terrain == "Misty") && isGrounded(atk, field))
                ) down(10)
                if (atk.hp == atk.maxHP) down(10) else if (healthPercent(atk) >= 90) down(9)
                return score
            }
            E.PROTECT -> {
                if (def.ability == "Unseen Fist") return down(10)
                if (move.name in setOf("Quick Guard", "Wide Guard", "Crafty Shield")) return down(10)
                if (move.name == "Mat Block" && !atk.firstTurn) return down(10)
                if (move.name == "Endure" && (atk.hp == 1 || secondaryDamage(atk, field) > 0)) down(10)
                return score
            }
            E.SUBSTITUTE -> {
                if (healthPercent(atk) <= 25) return down(10)
                if (def.ability == "Infiltrator" || hasMoveWhere(def) { it.sound }) down(8)
                return score
            }
            E.TAUNT -> {
                if (def.ability == "Oblivious") down(10)
                return score
            }
            E.ENCORE -> {
                if (def.item == "Mental Herb") return down(10)
                // No move used yet this battle: going first there is nothing to encore.
                if (hitsFirst(turn, atk, move, def) || predicted == null) down(10)
                return score
            }
            E.FIELD_EFFECTS -> {
                if (move.name == "Trick Room") {
                    if (if (turn.battle.trickRoom) atk.speed < def.speed else atk.speed > def.speed) down(10)
                } else if (move.name == "Gravity" && field.isGravity && "Flying" !in atk.types && atk.item != "Air Balloon") {
                    down(10)
                }
                return score
            }
            E.TEAM_EFFECTS -> {
                if (move.name == "Tailwind" && (atk.sideState.tailwind || turn.battle.trickRoom)) down(10)
                return score
            }
            E.FAKE_OUT -> {
                if (!atk.firstTurn) {
                    down(10)
                    return standardDamage()
                }
                if (move.name == "Fake Out" && canBeChoiceLocked(atk) && (def.monsLeft >= 2 || !knocksOut(turn, atk, def, i, 1)) &&
                    atk.monsLeft <= 1
                ) down(10)
                return standardDamage()
            }
            E.BATON_PASS -> {
                if (move.name == "Baton Pass") {
                    if (atk.monsLeft <= 1) return down(10)
                    if (!atk.boosts.values.any { it > 0 }) down(6)
                    return score
                }
                if (move.name == "Shed Tail") {
                    if (atk.monsLeft <= 1 || healthPercent(atk) <= 50) down(10)
                    return score
                }
                if (move.name == "Parting Shot") {
                    if (atk.monsLeft <= 1) return down(10)
                    // As the game does it: a foe whose Attack and Sp. Atk can both drop.
                    if (canStatBeLowered(atk, def, "atk") && canStatBeLowered(atk, def, "spa")) down(10)
                    return score
                }
                return standardDamage()
            }
            E.TELEPORT -> {
                if (atk.monsLeft <= 1) down(10)
                return score
            }
            E.EXPLOSION -> {
                if ((atk.ability == "Damp" || def.ability == "Damp") && !ignoresAbilities(atk)) return down(10)
                // Radical Red: not into a foe that can Protect or hide (Fly, Dig); no type check after.
                if (hasProtectMove(def) || hasEffect(def, E.SEMI_INVULNERABLE)) return down(10)
                val explosionKOs = knocksOut(turn, atk, def, i, 1)
                if (def.monsLeft <= 1 && explosionKOs) return score
                if (!explosionKOs || (def.monsLeft >= 2 && anotherMoveKnocksOut(turn, atk, def, i))) down(4)
                return score
            }
            E.SUCKER_PUNCH -> {
                // Radical Red: -9 whenever it would fail.
                if (!isSuckerPunchOkay(turn, atk, move, def)) return down(9)
                return standardDamage()
            }
            E.FOCUS_PUNCH -> {
                if (predicted != null && isDamaging(predicted)) return down(10)
                return standardDamage()
            }
            E.LEECH_SEED -> {
                if ("Grass" in def.types || def.seeded || ability == "Liquid Ooze") down(10)
                return score
            }
            E.PSYCH_UP, E.HAZE -> {
                // Spectral Thief and Soul Robbery are attacks; Psych Up gets Haze's rule.
                if (effect == E.PSYCH_UP && move.name != "Psych Up") return standardDamage()
                if (goodAI && usefulStatChanges(def, 1) + usefulStatChanges(atk, -1) < usefulStatChanges(def, -1) + usefulStatChanges(atk, 1)) {
                    down(10)
                } else if (!goodAI && atk.boosts.values.any { it > 0 }) {
                    down(10)
                }
                return score
            }
            E.ROAR -> {
                if (willFaintFromChip(def, field) || def.seeded ||
                    ((def.status == "psn" || def.status == "tox") && secondaryDamage(def, field) >= def.maxHP / 4.0)
                ) down(10)
                if (move.name == "Dragon Tail" || move.name == "Circle Throw" || move.name == "Roar of Time") return standardDamage()
                if (def.monsLeft <= 1 || ability == "Suction Cups") down(10)
                return score
            }
            E.KNOCK_OFF -> return score
            E.DREAM_EATER -> {
                if (!isAsleep(def) && def.ability != "Comatose") return down(10)
                return score
            }
            E.RECHARGE -> {
                if (knocksOut(turn, atk, def, i, 1) && anotherMoveKnocksOut(turn, atk, def, i) && atk.ability != "Truant") down(9)
                return standardDamage()
            }
            E.OHKO -> {
                if (defAbility(atk, def) == "Sturdy" || atk.level < def.level || (move.name == "Sheer Cold" && "Ice" in def.types)) return down(10)
                return standardDamage()
            }
            E.RECOIL_IF_MISS -> {
                if (accuracyOf(atk, def, move, field) < 70 && atk.ability != "Magic Guard") down(6)
                return standardDamage()
            }
            E.FOCUS_ENERGY -> {
                if (atk.focusEnergy) down(10)
                return score
            }
            E.COUNTER, E.MIRROR_COAT -> {
                // Radical Red: a further -10 half the time, every turn.
                if (predicted == null || predicted.split == SPLIT_STATUS) down(10)
                if (move.name == "Metal Burst" && hitsFirst(turn, atk, move, def)) {
                    down(10)
                    return standardDamage()
                }
                if (!turn.coin("counter" + turn.phase + atk.key + i, 0.5)) down(10)
                return standardDamage()
            }
            E.ENDEAVOR, E.PAIN_SPLIT -> {
                if (atk.hp > (atk.hp + def.hp) / 2.0) return down(10)
                return standardDamage()
            }
            E.SNORE, E.SLEEP_TALK -> {
                if (!isAsleep(atk) && atk.ability != "Comatose") return down(10)
                return standardDamage()
            }
            E.FALSE_SWIPE -> {
                if (knocksOut(turn, atk, def, i, 1) && anotherMoveKnocksOut(turn, atk, def, i)) down(10)
                return score
            }
            E.PERISH_SONG -> {
                if (atk.monsLeft <= 1 && atk.ability != "Soundproof" && def.monsLeft >= 2) down(10)
                if (def.ability == "Soundproof") down(10)
                return score
            }
            E.MEMENTO -> {
                if (atk.monsLeft <= 1) return down(10)
                return if (move.name == "Final Gambit") standardDamage() else score
            }
            E.HELPING_HAND, E.FOLLOW_ME, E.INSTRUCT_AFTER_YOU_QUASH -> {
                down(10)
                return score
            }
            E.TOPSY_TURVY_ELECTRIFY -> {
                if (move.name == "Electrify") {
                    if (!hitsFirst(turn, atk, move, def) || (predicted != null && predicted.type == "Electric")) down(10)
                    return score
                }
                // Topsy-Turvy: nothing to flip (-10), or it would flip more drops than boosts (-5).
                if (statStageTotal(def, 1) == 0) return down(10)
                if (statStageTotal(def, -1) > statStageTotal(def, 1)) down(5)
                return score
            }
            E.SEMI_INVULNERABLE -> {
                // Radical Red: Fly, Dig and the like aren't treated as charging moves.
                if (predicted != null && predicted.effect == E.SEMI_INVULNERABLE && hitsFirst(turn, atk, move, def)) down(10)
                if ((move.name == "Fly" || move.name == "Bounce") && secondaryDamage(atk, field) > 0 && (field.weather == "Sand" || isHail(field)) &&
                    atk.hp <= atk.maxHP / 16
                ) down(10)
                return standardDamage()
            }
            E.SOLARBEAM, E.SKULL_BASH, E.SKY_ATTACK, E.RAZOR_WIND -> {
                // Radical Red: always -4 for the charging turn (-10 if it would faint first), whatever the AI level.
                if (!needsCharge(atk, move, field)) return standardDamage()
                down(if (willFaintFromChip(atk, field)) 10 else 4)
                if (def.status == "par" || isAsleep(def)) return standardDamage()
                if (hasProtectMove(def) && !willFaintFromChip(def, field)) {
                    down(8)
                    return standardDamage()
                }
                if (atk.speed > def.speed) {
                    if (canKnockOut(turn, def, atk) && predicted != null) down(4)
                } else if (can2HKO(turn, def, atk) && predicted != null) {
                    down(8)
                }
                return standardDamage()
            }
            E.ABSORB -> {
                if (move.name == "Strength Sap") {
                    if (ability == "Contrary" || stage(def, "atk") == 0) down(10)
                    return score
                }
                if (ability == "Liquid Ooze" && !knocksOut(turn, atk, def, i, 1)) down(6)
                return standardDamage()
            }
            E.RECOIL -> {
                // Radical Red's recoil lists; a move not on them counts its whole damage as recoil.
                if (atk.ability in setOf("Magic Guard", "Rock Head")) return standardDamage()
                val dealt = moveDamage(turn, atk, def, i)
                val recoil = when {
                    move.name in RECOIL_QUARTER -> max(1, dealt shr 2)
                    move.name in RECOIL_THIRD -> max(1, dealt / 3)
                    move.name in RECOIL_HALF -> max(1, dealt shr 1)
                    move.name in setOf("Mind Blown", "Steel Beam") -> max(1, atk.maxHP shr 1)
                    else -> dealt
                }
                if (atk.hp > recoil || def.monsLeft <= 1) return standardDamage()
                if (def.hp > recoil || anotherMoveKnocksOut(turn, atk, def, i)) down(4)
                return score
            }
        }
        return standardDamage()
    }

    // AnyUsefulStatIsRaised (Radical Red's older form, for phazing and Explosion).
    private fun anyUsefulStatRaised(side: AiSide, foe: AiSide): Boolean {
        fun up(stat: String) = stage(side, stat) > 6
        if (hasMoveNamed(side, "Stored Power", "Power Trip")) return SEVEN_STATS.any(::up)
        return (up("atk") && hasPhysical(side)) || (up("def") && (hasPhysical(foe) || hasMoveNamed(foe, "Body Press"))) ||
            (up("spa") && hasSpecial(side)) || (up("spd") && hasSpecial(foe)) || up("spe") || up("acc") || up("eva")
    }

    // CanBeFlinched and IsUsefulToFlinchTarget.
    private fun canFlinch(turn: AiTurn, atk: AiSide, def: AiSide, move: AiMove): Boolean {
        if (defAbility(atk, def) in setOf("Inner Focus", "Shield Dust") || def.item == "Covert Cloak") return false
        if (!hitsFirst(turn, atk, move, def)) return false
        return !isAsleep(def) || hasEffect(def, E.SLEEP_TALK, E.SNORE)
    }

    // ShouldTrap (stall styles): the foe can switch and won't knock the AI out before the trap pays.
    private fun shouldTrap(turn: AiTurn, atk: AiSide, def: AiSide, move: AiMove): Boolean {
        if (willFaintFromChip(atk, turn.battle.field) || !isGoodToTaunt(styleOf(turn, atk)) || def.monsLeft <= 1) return false
        return if (hitsFirst(turn, atk, move, def)) !canKnockOut(turn, def, atk) else !canKnockOut(turn, def, atk) && !can2HKO(turn, def, atk)
    }

    // ResistsAllMovesAtFullHealth: none of the foe's moves takes a third of the AI's full HP.
    private fun resistsAllAtFullHealth(turn: AiTurn, def: AiSide, atk: AiSide) = def.moves.withIndex().all { (i, move) ->
        move == null || !isDamaging(move) || moveDamage(turn, def, atk, i) < atk.maxHP / 3
    }

    // ShouldUseWishAromatherapy (Wish), singles.
    private fun shouldUseWish(turn: AiTurn, atk: AiSide, def: AiSide): Boolean {
        if (styleOf(turn, atk) != STYLE_CLERIC || willFaintFromChip(atk, turn.battle.field)) return false
        if (atk.monsLeft <= 1 && ((can2HKO(turn, def, atk) && !hasProtectMove(atk)) || canKnockOut(turn, atk, def))) return false
        return healthPercent(atk) < 65
    }

    // ShouldCourtChange: swapping the sides' conditions helps the side.
    private fun shouldCourtChange(side: AiSide, foe: AiSide): Boolean {
        val mine = side.sideState
        val theirs = foe.sideState
        return listOf("reflect", "lightScreen", "auroraVeil", "tailwind").any { theirs.has(it) && !mine.has(it) } ||
            listOf("sr", "spikes", "stickyWeb").any { mine.has(it) && !theirs.has(it) }
    }

    // CountBanksPositiveStatStages / NegativeStatStages.
    private fun statStageTotal(side: AiSide, sign: Int) = side.boosts.values.sumOf { max(0, it * sign) }

    // GoodIdeaToSwapStatStages: at least two useful stages better off after swapping.
    private fun goodIdeaToSwapStages(atk: AiSide, def: AiSide) =
        usefulStatChanges(def, 1) + usefulStatChanges(atk, -1) - (usefulStatChanges(def, -1) + usefulStatChanges(atk, 1)) >= 2

    // CountUsefulStatChanges: raised (sign 1) or lowered (sign -1) stages of the stats the side uses.
    private fun usefulStatChanges(side: AiSide, sign: Int): Int {
        var total = 0
        val all = hasMoveNamed(side, "Stored Power", "Power Trip")
        for ((stat, value) in side.boosts) {
            val change = value * sign
            if (change <= 0) continue
            if (all || (stat == "atk" && hasPhysical(side)) || (stat == "spa" && hasSpecial(side)) ||
                stat == "def" || stat == "spd" || stat == "spe"
            ) total += change
        }
        return total
    }

    // #endregion
    // #region AIScript_Positives: points for good moves, then DamageMoveViabilityIncrease for every attack

    private fun scorePositives(turn: AiTurn, atk: AiSide, def: AiSide, i: Int, initial: Int): Int {
        var score = initial
        val move = atk.moves[i]!!
        val field = turn.battle.field
        val predicted = turn.predicted(def)
        val effect = move.effect
        val style = styleOf(turn, atk)
        fun up(amount: Int) {
            score = min(255, score + amount)
        }
        fun status(boost: Int) = up(statusBonus(turn, atk, def, boost))
        fun raise(stat: String, limit: Int, boost: Int): Boolean {
            if (atk.ability == "Contrary") return false
            if (goodIdeaToRaise(turn, atk, def, stat) == 0) return false
            up(statBonus(turn, atk, def, move, stat, limit, boost))
            return true
        }
        // Secondary chances with Sheer Force and Serene Grace (nobody is assumed to be behind a Substitute).
        val chance = chanceOf(atk, move)
        val phazingPredicted = predicted != null && predicted.effect in setOf(E.ROAR, E.HAZE, E.REMOVE_TARGET_STAT_CHANGES)
        // Recover's +8 for stallers and phazers, else STATUS(3) (RECOVER_INCREASE).
        fun recoverBonus() {
            if (style == STYLE_STALL || style == STYLE_PHAZING) up(8) else status(3)
        }
        // IncreasePsychUpViability: when swapping stages is worth it, the first stat (offence first) the foe has higher,
        // if the AI uses it.
        fun psychUpBonus() {
            if (!goodIdeaToSwapStages(atk, def)) return
            for (stat in listOf("atk", "spa", "spe", "acc", "eva", "def", "spd")) {
                val difference = stage(def, stat) - stage(atk, stat)
                if (difference <= 0) continue
                if ((stat == "atk" && hasRealPhysical(atk)) || (stat == "spa" && hasSpecial(atk)) ||
                    stat == "spe" || stat == "acc" || stat == "eva" || style == STYLE_STALL
                ) {
                    up(statBonus(turn, atk, def, move, stat, 8, difference))
                    return
                }
            }
        }
        // Trick / Switcheroo: what handing over the AI's item (or taking the foe's) does.
        fun trickBonus() {
            val item = atk.item
            if (item.startsWith("Choice ")) {
                if (item == "Choice Scarf" || !(if (item == "Choice Band") hasPhysical(def) else hasSpecial(def))) status(2)
            } else if (item == "Toxic Orb") {
                if (!goodIdeaToStatusSelf(atk, "psn", field) && !badIdeaToStatus(atk, def, "psn", field)) status(2)
            } else if (item == "Flame Orb") {
                if (!goodIdeaToStatusSelf(atk, "brn", field) && !badIdeaToStatus(atk, def, "brn", field)) status(2)
            } else if (item == "Black Sludge") {
                if ("Poison" !in def.types) status(3)
            } else if (item == "Iron Ball") {
                if (!hasMoveNamed(def, "Fling") || !isGrounded(def, field)) status(2)
            } else if (item == "Lagging Tail" || item == "Sticky Barb") {
                status(3)
            } else if (item == "Utility Umbrella") {
                if (atk.ability !in setOf("Solar Power", "Dry Skin") &&
                    ((def.ability == "Swift Swim" && "Rain" in field.w) ||
                        (def.ability in setOf("Chlorophyll", "Flower Gift") && "Sun" in field.w))
                ) status(3)
            } else if (item == "Assault Vest") {
                if (styleOf(turn, def) == STYLE_STALL && hasMoveWhere(def) { it.split == SPLIT_STATUS }) status(2)
            } else if (item.isEmpty() && move.name != "Bestow") {
                val theirs = def.item
                if (theirs.startsWith("Choice ") || theirs == "Lagging Tail" || theirs == "Sticky Barb") return
                when (theirs) {
                    "Toxic Orb" -> if (goodIdeaToStatusSelf(atk, "psn", field)) status(2)
                    "Flame Orb" -> if (goodIdeaToStatusSelf(atk, "brn", field)) status(2)
                    "Black Sludge" -> if ("Poison" in atk.types) status(3)
                    "Iron Ball" -> if (hasMoveNamed(atk, "Fling")) status(2)
                    else -> status(1)
                }
            }
        }
        // The stat swaps and splits (Radical Red's Heart Swap is CFRU's older rule).
        fun statSwapBonus() {
            fun higher(stat: String) = stage(def, stat) > stage(atk, stat)
            fun noLower(stat: String) = stage(def, stat) >= stage(atk, stat)
            fun st(side: AiSide, stat: String) = side.stats[stat] ?: 0
            when (move.name) {
                "Guard Swap" -> if ((higher("def") && noLower("spd")) || (higher("spd") && noLower("def"))) status(1)
                "Power Swap" -> if ((higher("atk") && noLower("spa")) || (higher("spa") && noLower("atk"))) status(1)
                "Power Trick" -> if (st(atk, "def") > st(atk, "atk") && hasRealPhysical(atk)) status(2)
                "Heart Swap" -> if (SEVEN_STATS.none { stage(def, it) < stage(atk, it) } && SEVEN_STATS.any(::higher)) status(1)
                "Speed Swap" -> if (def.speed > atk.speed) status(3)
                "Guard Split" -> {
                    val newDef = (st(atk, "def") + st(def, "def")) / 2
                    val newSpd = (st(atk, "spd") + st(def, "spd")) / 2
                    if ((newDef > st(atk, "def") && newSpd >= st(atk, "spd")) || (newSpd > st(atk, "spd") && newDef >= st(atk, "def"))) status(1)
                }
                "Power Split" -> {
                    val newAtk = (st(atk, "atk") + st(def, "atk")) / 2
                    val newSpa = (st(atk, "spa") + st(def, "spa")) / 2
                    if ((newAtk > st(atk, "atk") && newSpa >= st(atk, "spa")) || (newSpa > st(atk, "spa") && newAtk >= st(atk, "atk"))) status(1)
                }
            }
        }
        // The Cosmic Power logic (Defend Order, Stockpile, Contrary Shell Smash): Defence (its "1" raises Attack, as in
        // the game), else Sp. Def.
        fun cosmicPower() {
            val defense = goodIdeaToRaise(turn, atk, def, "def")
            if (defense == 1) up(statBonus(turn, atk, def, move, "atk", 10, 2))
            else if (defense == 2) up(statBonus(turn, atk, def, move, "def", 8, 1))
            else if (goodIdeaToRaise(turn, atk, def, "spd") != 0) up(statBonus(turn, atk, def, move, "spd", 10, 1))
        }
        val lowerStat = mapOf(
            E.ATTACK_DOWN_HIT to "atk", E.DEFENSE_DOWN_HIT to "def", E.SPECIAL_ATTACK_DOWN_HIT to "spa",
            E.SPECIAL_DEFENSE_DOWN_HIT to "spd", E.ACCURACY_DOWN_HIT to "acc", E.EVASION_DOWN_HIT to "eva",
        )

        when (effect) {
            E.SLEEP, E.YAWN -> up(sleepBonus(turn, atk, def, move))
            E.PARALYZE, E.PARALYZE_HIT -> run {
                if (effect == E.PARALYZE_HIT && chance < 75) return@run
                if (!canBeParalyzed(atk, def, field) || badIdeaToStatus(atk, def, "par", field)) return@run
                status(if ((def.speed >= atk.speed && def.speed / 2.0 < atk.speed) || hasDoubleDamageMove(atk) || hasEffect(atk, E.FLINCH_HIT)) 2 else 1)
            }
            E.WILL_O_WISP, E.BURN_HIT -> run {
                if (effect == E.BURN_HIT && chance < 75) return@run
                if (!canBeBurned(atk, def, field) || badIdeaToStatus(atk, def, "brn", field)) return@run
                if (predicted != null && predicted.split == SPLIT_PHYSICAL && knocksOut(turn, def, atk, indexOf(def, predicted), 1)) status(3)
                else status(if (hasDoubleDamageMove(atk) || hasMoveNamed(atk, "Infernal Parade") || hasPhysical(def)) 2 else 1)
            }
            E.POISON, E.TOXIC, E.POISON_HIT, E.BAD_POISON_HIT -> run {
                if ((effect == E.POISON_HIT || effect == E.BAD_POISON_HIT) && chance < 75) return@run
                if (!canBePoisoned(atk, def, field) || badIdeaToStatus(atk, def, "psn", field)) return@run
                // Radical Red: Venoshock, Barb Barrage or Merciless (no Venom Drench); or Hex with Will-O-Wisp when burning
                // is no better.
                if (hasMoveNamed(atk, "Venoshock", "Barb Barrage") || atk.ability == "Merciless") status(2)
                else if (hasDoubleDamageMove(atk) && hasEffect(atk, E.WILL_O_WISP) &&
                    (!hasPhysical(def) || badIdeaToStatus(atk, def, "brn", field) || defAbility(atk, def) == "Flash Fire")
                ) status(2)
                else status(1)
            }
            E.CONFUSE, E.CONFUSE_HIT -> run {
                // A confusing attack only when it can't miss (No Guard, a never-miss move).
                if (effect == E.CONFUSE_HIT && (chance < 75 || !moveWillHit(atk, def, move, field))) return@run
                if (canBeConfused(atk, def, field)) {
                    status(if (def.status == "par" || (atk.ability == "Serene Grace" && hasEffect(atk, E.FLINCH_HIT))) 2 else 1)
                }
            }
            E.SWAGGER, E.FLATTER -> {
                // Flatter goes with Psych Up only; Swagger with Foul Play too.
                if (hasEffect(atk, E.PSYCH_UP) || (effect == E.SWAGGER && hasMoveNamed(atk, "Foul Play"))) {
                    status(2)
                } else if (canBeConfused(atk, def, field)) {
                    status(if (def.status == "par" || (atk.ability == "Serene Grace" && hasEffect(atk, E.FLINCH_HIT))) 2 else 1)
                }
            }
            E.ATTACK_UP, E.ATTACK_UP_2 -> {
                if (move.name == "Hone Claws") {
                    if (!raise("atk", 8, 2)) raise("acc", 12, 2)
                } else {
                    raise("atk", 8, 2)
                }
            }
            E.DEFENSE_UP, E.DEFENSE_UP_2 -> {
                val defense = if (atk.ability == "Contrary") 0 else goodIdeaToRaise(turn, atk, def, "def")
                if (defense == 1) up(statBonus(turn, atk, def, move, "atk", 10, 2))
                else if (defense == 2) up(statBonus(turn, atk, def, move, "def", 8, 1))
            }
            E.SPECIAL_ATTACK_UP, E.SPECIAL_ATTACK_UP_2 -> {
                // Radical Red: Growth and Work Up raise Attack for a physical attacker, else Sp. Atk (no Contrary check).
                if (move.name == "Growth" || move.name == "Work Up") {
                    if (goodIdeaToRaise(turn, atk, def, "atk") != 0) up(statBonus(turn, atk, def, move, "atk", 8, 2))
                    else if (goodIdeaToRaise(turn, atk, def, "spa") != 0) up(statBonus(turn, atk, def, move, "spa", 8, 2))
                } else {
                    raise("spa", 8, 2)
                }
            }
            E.SPECIAL_DEFENSE_UP, E.SPECIAL_DEFENSE_UP_2 -> raise("spd", 10, 1)
            E.SPEED_UP, E.SPEED_UP_2 -> raise("spe", 8, 3)
            E.ACCURACY_UP, E.ACCURACY_UP_2 -> raise("acc", 12, 2)
            // Radical Red: Double Team and Minimize get nothing.
            E.EVASION_UP, E.EVASION_UP_2, E.MINIMIZE -> {}
            // Radical Red: no bonus for ATK_SPATK_UP (Fickle Beam), ATK_ACC_UP or DEF_SPD_UP.
            E.COSMIC_POWER -> if (atk.ability != "Contrary") cosmicPower()
            E.STOCKPILE -> {
                if (hasMoveNamed(atk, "Swallow", "Spit Up")) status(2)
                else if (atk.ability != "Contrary") cosmicPower()
            }
            E.CHARGE -> {
                if (hasMoveWhere(atk) { isDamaging(it) && it.type == "Electric" && it.effectiveness > 0 }) status(2)
                else raise("spd", 10, 1)
            }
            E.BULK_UP -> if (!(hasPhysical(atk) && raise("atk", 8, 2))) raise("def", 8, 1)
            E.CALM_MIND -> run {
                // Quiver Dance not into a Dancer; Geomancy only with Power Herb or a foe that can't act, and no phazing move
                // to undo it.
                if (move.name == "Quiver Dance" && def.ability == "Dancer") return@run
                if (move.name == "Geomancy" && (!(atk.item == "Power Herb" || isIncapacitated(def)) ||
                        hasEffect(def, E.ROAR, E.HAZE, E.REMOVE_TARGET_STAT_CHANGES))
                ) return@run
                if ((move.name == "Quiver Dance" || move.name == "Geomancy") && raise("spe", 8, 3)) return@run
                if (!raise("spa", 8, 2)) raise("spd", 8, 1)
            }
            E.DRAGON_DANCE -> run {
                // Shell Smash with Contrary raises the defences: the Cosmic Power logic.
                if (move.name == "Shell Smash" && atk.ability == "Contrary") {
                    cosmicPower()
                    return@run
                }
                if (raise("spe", 8, 3)) return@run
                if (!raise("atk", 8, 2) && move.name == "Shell Smash") raise("spa", 8, 2)
            }
            E.EXTREME_EVOBOOST -> run {
                if (phazingPredicted || atk.ability == "Contrary") return@run
                if (move.name == "Clangorous Soul" && predicted != null) {
                    val hit = indexOf(def, predicted)
                    if (knocksOut(turn, def, atk, hit, 1) || (knocksOut(turn, def, atk, hit, 3) && !knocksOut(turn, def, atk, hit, 2))) return@run
                }
                // Attack with a physical move, else Sp. Atk with a special one, else Speed: the next only if the last gave
                // nothing.
                val evoBefore = score
                if (hasRealPhysical(atk)) up(statBonus(turn, atk, def, move, "atk", 8, 2))
                if (score == evoBefore && hasSpecial(atk)) up(statBonus(turn, atk, def, move, "spa", 8, 2))
                if (score == evoBefore) up(statBonus(turn, atk, def, move, "spe", 8, 3))
            }
            E.BELLY_DRUM -> {
                if (hasRealPhysical(atk) && atk.ability != "Contrary" && !badIdeaToRaiseStat(turn, atk, def, "atk")) {
                    up(statBonus(turn, atk, def, move, "atk", 8, 2))
                }
            }
            E.CURSE -> {
                // A Ghost's Curse is worth more against a trapped foe; the other kind checks the foe's Magic Guard (as
                // Radical Red's code does), not the AI's.
                if ("Ghost" in atk.types) {
                    status(if (isTrappedBy(def, atk, field)) 3 else 1)
                } else if (atk.ability != "Contrary" && defAbility(atk, def) != "Magic Guard" && !phazingPredicted) {
                    if (hasMoveNamed(atk, "Gyro Ball")) {
                        up(statBonus(turn, atk, def, move, "atk", 12, 4))
                    } else if (stage(atk, "spe") >= 3) {
                        if (stage(atk, "atk") < 8) up(statBonus(turn, atk, def, move, "atk", 8, 2))
                        else if (stage(atk, "def") <= 7) up(statBonus(turn, atk, def, move, "def", 8, 1))
                    }
                }
            }
            // Radical Red: SPECIAL_ATTACK_UP_HIT (Charge Beam, Fiery Dance) gets nothing.
            E.ATTACK_UP_HIT -> run {
                if (atk.ability == "Contrary") return@run
                if (move.name == "Fell Stinger" && stage(atk, "atk") < 12 && knocksOut(turn, atk, def, i, 1)) {
                    up(if (hitsFirst(turn, atk, move, def)) 9 else 3)
                    return@run
                }
                if (atk.ability == "Parental Bond" && goodIdeaToRaise(turn, atk, def, "atk") != 0) up(statBonus(turn, atk, def, move, "atk", 8, 2))
                else if (chance >= 75) raise("atk", 8, 2)
            }
            E.SPEED_UP_1_HIT -> if (chance >= 75) raise("spe", 8, 3)
            E.ATTACK_DOWN_HIT, E.DEFENSE_DOWN_HIT, E.SPECIAL_ATTACK_DOWN_HIT, E.SPECIAL_DEFENSE_DOWN_HIT, E.ACCURACY_DOWN_HIT,
            E.EVASION_DOWN_HIT -> {
                // Not for damage dealers; a 50% or better drop it's a good idea to land (Snarl, Lunge).
                if (!isDamager(style) && chance >= 50 && goodIdeaToLower(turn, def, atk, move, lowerStat.getValue(effect))) {
                    status(if (effect == E.EVASION_DOWN_HIT) 2 else 1)
                }
            }
            E.FLINCH_HIT -> run {
                if (!canFlinch(turn, atk, def, move)) return@run
                // With Serene Grace or a foe already slowed (paralysed), a likely flinch or one that buys a turn the AI
                // needs; Radical Red also always likes Double Iron Bash.
                if (atk.ability == "Serene Grace" || def.status == "par") {
                    if (chance >= 60 || !can2HKO(turn, atk, def)) status(3)
                } else if (move.name == "Double Iron Bash") {
                    status(3)
                }
            }
            E.MULTI_HIT, E.DOUBLE_HIT, E.TRIPLE_KICK -> {
                // A sweeper's King's Rock flinches, or Scale Shot's Speed.
                val strongest = atk.moves.getOrNull(strongestMove(turn, atk, def))
                if (isSweeper(style) && !isStrongest(turn, atk, def, i) && strongest != null && !isMultiHit(strongest) &&
                    atk.item in setOf("King's Rock", "Razor Fang")
                ) up(3)
                else if (move.name == "Scale Shot") raise("spe", 8, 3)
            }
            E.ATTACK_DOWN, E.ATTACK_DOWN_2 -> if (goodIdeaToLower(turn, def, atk, move, "atk")) status(1)
            E.DEFENSE_DOWN, E.DEFENSE_DOWN_2 -> if (goodIdeaToLower(turn, def, atk, move, "def")) status(1)
            E.SPEED_DOWN, E.SPEED_DOWN_2 -> if (goodIdeaToLower(turn, def, atk, move, "spe")) status(2)
            E.SPECIAL_ATTACK_DOWN, E.SPECIAL_ATTACK_DOWN_2 -> if (goodIdeaToLower(turn, def, atk, move, "spa")) status(1)
            E.SPECIAL_DEFENSE_DOWN, E.SPECIAL_DEFENSE_DOWN_2 -> if (goodIdeaToLower(turn, def, atk, move, "spd")) status(1)
            E.ACCURACY_DOWN, E.ACCURACY_DOWN_2 -> if (goodIdeaToLower(turn, def, atk, move, "acc")) status(1)
            E.TICKLE -> if (goodIdeaToLower(turn, def, atk, move, "atk") || goodIdeaToLower(turn, def, atk, move, "def")) status(1)
            E.EVASION_DOWN, E.EVASION_DOWN_2 -> if (goodIdeaToLower(turn, def, atk, move, "eva")) status(2)
            // Radical Red: a speed-lowering attack and grounding a foe in the air (Smack Down) only get their +3 on half
            // the rolls.
            E.SPEED_DOWN_HIT -> {
                if (chance >= 50 && goodIdeaToLower(turn, def, atk, move, "spe") && turn.coin("speedDrop" + turn.phase + atk.key + i, 0.5)) up(3)
            }
            E.SMACK_DOWN -> if (!isGrounded(def, field) && turn.coin("smackDown" + turn.phase + atk.key + i, 0.5)) up(3)
            E.REFLECT, E.LIGHT_SCREEN -> {
                if (shouldSetUpScreens(turn, atk, def, move)) {
                    // Radical Red: +15 for Reflect and Light Screen (CFRU master +7).
                    if (style == STYLE_SETUP_SCREENS || style == STYLE_SUPPORT_SCREENS) up(if (move.name == "Aurora Veil") 8 else 15)
                    else status(2)
                }
            }
            E.SPIKES -> if (def.monsLeft > 1) up(hazardBonus(turn, atk, def, move))
            E.SANDSTORM -> {
                if (atk.types.any { it in setOf("Rock", "Ground", "Steel") } ||
                    atk.ability in setOf("Sand Veil", "Sand Rush", "Sand Force", "Overcoat", "Magic Guard") ||
                    atk.item in setOf("Safety Goggles", "Smooth Rock") || hasMoveNamed(atk, "Shore Up", "Weather Ball")
                ) status(2)
            }
            E.RAIN_DANCE -> {
                if (atk.item != "Utility Umbrella" && (atk.ability in setOf("Swift Swim", "Forecast", "Hydration", "Rain Dish", "Dry Skin") ||
                        hasEffect(atk, E.THUNDER) || hasMoveWhere(atk) { it.type == "Water" && it.split != SPLIT_STATUS } ||
                        hasMoveNamed(atk, "Weather Ball") || atk.item == "Damp Rock" ||
                        hasMoveWhere(def) { (it.type == "Fire" && it.split != SPLIT_STATUS) || it.effect == E.MORNING_SUN })
                ) status(2)
            }
            E.SUNNY_DAY -> {
                if (atk.item != "Utility Umbrella" && (atk.ability in setOf("Chlorophyll", "Flower Gift", "Forecast", "Leaf Guard", "Solar Power", "Harvest") ||
                        hasEffect(atk, E.SOLARBEAM, E.MORNING_SUN) || hasMoveNamed(atk, "Weather Ball", "Growth") ||
                        hasMoveWhere(atk) { it.type == "Fire" && it.split != SPLIT_STATUS } || atk.item == "Heat Rock" ||
                        hasMoveWhere(def) { it.effect == E.THUNDER || (it.type == "Water" && it.split != SPLIT_STATUS) })
                ) status(2)
            }
            E.HAIL -> {
                // With Aurora Veil to follow, only a screen setter gets anything.
                if (hasMoveNamed(atk, "Aurora Veil")) {
                    if (style == STYLE_SETUP_SCREENS || style == STYLE_SUPPORT_SCREENS) up(8)
                } else if (atk.ability in setOf("Ice Body", "Forecast", "Slush Rush", "Magic Guard", "Overcoat") ||
                    hasMoveNamed(atk, "Blizzard", "Aurora Veil", "Weather Ball") || atk.item == "Icy Rock" || hasEffect(def, E.MORNING_SUN)
                ) status(2)
            }
            E.SET_TERRAIN -> status(2)
            E.RESTORE_HP, E.MORNING_SUN, E.SWALLOW -> {
                if (shouldRecover(turn, atk, def, move)) recoverBonus()
                else if (move.name == "Jungle Healing" && atk.status.isNotEmpty()) status(3)
            }
            // Radical Red: draining moves only help a staller in singles.
            E.ABSORB, E.DREAM_EATER -> if (shouldRecover(turn, atk, def, move) && style == STYLE_STALL) status(2)
            E.WISH -> {
                if (shouldUseWish(turn, atk, def)) up(8)
                else if (shouldRecover(turn, atk, def, move)) recoverBonus()
            }
            E.REST -> {
                val foeChipped = secondaryDamage(def, field) > 0
                if (resistsAllAtFullHealth(turn, def, atk) && !hasOffensiveSetupMove(def)) {
                    if ((atk.hp < atk.maxHP && foeChipped) || shouldRecover(turn, atk, def, move)) recoverBonus()
                } else if (atk.item == "Chesto Berry" || atk.item == "Lum Berry" || hasEffect(atk, E.SLEEP_TALK, E.SNORE) ||
                    atk.ability in setOf("Shed Skin", "Early Bird") || (atk.ability == "Hydration" && "Rain" in field.w &&
                        atk.item != "Utility Umbrella")
                ) {
                    if (shouldRecover(turn, atk, def, move)) recoverBonus()
                } else if (foeChipped && style == STYLE_STALL) {
                    recoverBonus()
                } else if (atk.status.isNotEmpty() && turn.coin("rest" + turn.phase + atk.key, 0.5) && shouldRecover(turn, atk, def, move)) {
                    recoverBonus()
                }
            }
            E.PROTECT -> run {
                // Quick Guard, Wide Guard, Crafty Shield and Mat Block count as Protect when your predicted move is one
                // they stop.
                if (move.name == "Quick Guard" && !(predicted != null && priorityOf(def, predicted, field) > 0)) return@run
                if (move.name == "Wide Guard" && !(predicted != null && (predicted.target and (MOVE_TARGET_BOTH or MOVE_TARGET_FOES_AND_ALLY)) != 0)) return@run
                if (move.name == "Crafty Shield" && !(predicted != null && predicted.split == SPLIT_STATUS && !isSelfTargeting(predicted))) return@run
                if (move.name == "Mat Block" && !(atk.firstTurn && predicted != null && isDamaging(predicted) && !isSelfTargeting(predicted))) return@run
                // Radical Red: an Aegislash in Blade Forme uses King's Shield half the time it can.
                if (move.name == "King's Shield" && atk.ability == "Stance Change" && atk.name == "Aegislash-Blade" && !isIncapacitated(def) &&
                    turn.coin("kingsShield" + turn.phase + atk.key + i, 0.5)
                ) {
                    if (style == STYLE_STALL) up(3) else status(3)
                    return@run
                }
                if (move.name == "Endure") {
                    if (canKnockOut(turn, def, atk) && ((atk.item.endsWith("Berry") && atk.hp > atk.maxHP / 4.0) ||
                            (hasMoveNamed(atk, "Flail", "Reversal", "Endeavor") && atk.hp > 1))
                    ) status(3)
                    return@run
                }
                if (shouldProtect(turn, atk, def)) up(protectBonus(turn, atk, def))
            }
            E.SUBSTITUTE -> if (shouldUseSubstitute(turn, atk, def, move)) up(substituteBonus(turn, atk, def))
            E.TAUNT -> run {
                if (isAsleep(def) && !hasEffect(def, E.SLEEP_TALK)) return@run
                // Radical Red: +8 against any foe with a status move, and no Choice-item check.
                val foeHasStatusMove = hasMoveWhere(def) { it.split == SPLIT_STATUS }
                if (foeHasStatusMove) up(8)
                if ((predicted != null && predicted.split == SPLIT_STATUS) || isGoodToTaunt(styleOf(turn, def))) status(3)
                else if (foeHasStatusMove) status(2)
            }
            E.ENCORE -> {
                if (def.item != "Mental Herb" && !hitsFirst(turn, atk, move, def) && predicted != null && predicted.split == SPLIT_STATUS) status(3)
            }
            E.FIELD_EFFECTS -> {
                if (move.name == "Trick Room") {
                    if (!turn.battle.trickRoom && atk.speed < def.speed) status(3)
                } else if (move.name == "Magic Room") {
                    status(1)
                } else if (move.name == "Wonder Room") {
                    // When swapping the defences helps against what the foe hits with.
                    val d = atk.stats["def"] ?: 0
                    val sd = atk.stats["spd"] ?: 0
                    if ((hasPhysical(def) && d < sd) || (hasSpecial(def) && sd < d)) status(2)
                } else if (move.name == "Gravity" && !field.isGravity) {
                    // Radical Red: nothing once Gravity is up. A shaky sleep move goes through the sleep rules.
                    val sleeper = atk.moves.firstOrNull { it != null && it.effect == E.SLEEP && it.accuracy != 0 && it.accuracy < 90 }
                    if (sleeper != null) up(sleepBonus(turn, atk, def, sleeper))
                    else if (defAbility(atk, def) != "Levitate") status(if (hasMoveWhere(atk) { it.accuracy != 0 && it.accuracy < 90 }) 2 else 1)
                }
            }
            E.TEAM_EFFECTS -> {
                if (move.name == "Tailwind") {
                    status(if (style in setOf(STYLE_SETUP_SCREENS, STYLE_BATON_PASS, STYLE_CLERIC, STYLE_SUPPORT_SCREENS, STYLE_PHAZING)) 2 else 1)
                } else if (move.name == "Lucky Chant") {
                    status(1)
                } else if (move.name == "Magnet Rise" && isGrounded(atk, field) && hasMoveWhere(def) { isDamaging(it) && it.type == "Ground" } &&
                    typeEffectiveness("Ground", atk.types) >= 1
                ) {
                    val groundPredicted = predicted != null && predicted.type == "Ground" && isDamaging(predicted)
                    status(if (groundPredicted && hitsFirst(turn, atk, move, def)) 3 else if (groundPredicted) 2 else 1)
                }
            }
            E.TRAP -> run {
                // Not into Rapid Spin, a Ghost or a foe already trapped. Radical Red: Salt Cure +8 on Water and Steel types,
                // else +4; the others when a staller should trap.
                if (hasMoveNamed(def, "Rapid Spin") || "Ghost" in def.types) return@run
                if (move.name == "Salt Cure") {
                    if (!def.saltCured) up(if ("Water" in def.types || "Steel" in def.types) 8 else 4)
                } else if (shouldTrap(turn, atk, def, move)) {
                    up(7)
                }
            }
            E.MIST -> if (style == STYLE_SETUP_SCREENS || style == STYLE_SUPPORT_SCREENS) up(6)
            E.SAFEGUARD -> {
                if (style in setOf(STYLE_BATON_PASS, STYLE_CLERIC, STYLE_SUPPORT_SCREENS, STYLE_PHAZING) &&
                    !(field.terrain == "Misty" && isGrounded(atk, field))
                ) status(1)
            }
            E.PERISH_SONG -> if (isTrappedBy(def, atk, field)) status(3)
            E.NIGHTMARE -> if ((def.status == "slp" || def.ability == "Comatose") && defAbility(atk, def) != "Magic Guard") status(3)
            E.ATTRACT -> run {
                if (willFaintFromChip(def, field) && atk.speed < def.speed) return@run
                status(if (def.status.isNotEmpty() || isTrappedBy(def, atk, field)) 2 else 1)
            }
            // A sweeper's Pursuit into a predicted U-turn or Volt Switch that comes after it.
            E.PURSUIT -> if (isSweeper(style) && predicted != null && isPivotMove(predicted) && !hitsFirst(turn, atk, move, def)) up(3)
            E.RAPID_SPIN -> {
                val mine = atk.sideState
                val theirs = def.sideState
                if ((mine.sr || mine.spikes != 0 || mine.toxicSpikes != 0 || mine.stickyWeb) && atk.monsLeft > 1) {
                    status(3)
                } else if (move.name == "Defog") {
                    if (theirs.reflect || theirs.lightScreen || theirs.auroraVeil) status(3)
                    else if (!(theirs.sr || theirs.spikes != 0 || theirs.toxicSpikes != 0 || theirs.stickyWeb) &&
                        goodIdeaToLower(turn, def, atk, move, "eva")
                    ) status(2)
                } else if (move.name == "Court Change") {
                    if (shouldCourtChange(atk, def) && !shouldCourtChange(def, atk)) status(2)
                } else if (atk.seeded && move.effectiveness > 0) {
                    status(3)
                }
            }
            E.FEINT -> if (predicted != null && predicted.effect == E.PROTECT) up(3)
            E.IMPRISON -> status(if (predicted != null && hasMoveNamed(atk, predicted.name)) 3 else 1)
            E.INGRAIN -> status(if (atk.item == "Big Root") 2 else 1)
            E.HEAL_BELL -> {
                if (style == STYLE_CLERIC && atk.status.isNotEmpty() && !(move.name == "Heal Bell" && atk.ability == "Soundproof")) up(7)
            }
            E.REFRESH -> if (move.name != "Psycho Shift" && atk.status.isNotEmpty()) status(3)
            E.PSYCH_UP -> {
                if (move.name != "Spectral Thief") {
                    psychUpBonus()
                } else if (atk.ability != "Contrary") {
                    // Spectral Thief: worth it against a foe at +2 in anything.
                    if (def.boosts.keys.any { stage(def, it) >= 8 }) {
                        if (isDamager(style)) up(statBonus(turn, atk, def, move, "any", 12, 3)) else up(3)
                    }
                }
            }
            E.TRICK -> trickBonus()
            E.STAT_SWAP_SPLIT -> statSwapBonus()
            E.TOPSY_TURVY_ELECTRIFY -> {
                if (move.name == "Electrify") {
                    if (predicted != null && predicted.type == "Normal" && atk.ability in setOf("Volt Absorb", "Motor Drive", "Lightning Rod")) status(3)
                } else if (statStageTotal(def, 1) > statStageTotal(def, -1)) {
                    status(1)
                }
            }
            E.LOCK_ON -> {
                // Radical Red: only Laser Focus, when the AI can't KO now and survives to use the crit.
                if (move.name == "Laser Focus" && !canKnockOut(turn, atk, def) &&
                    (if (atk.speed > def.speed) !canKnockOut(turn, def, atk) else !can2HKO(turn, def, atk))
                ) status(1)
            }
            E.FURY_CUTTER -> if (isSweeper(style) && atk.item == "Metronome") up(3)
            E.TORMENT -> status(if (def.item.startsWith("Choice ") || def.ability == "Gorilla Tactics") 2 else 0)
            E.MAGIC_COAT -> {
                if (predicted != null && predicted.split == SPLIT_STATUS && (predicted.target and (MOVE_TARGET_BOTH or MOVE_TARGET_FOES_FIELD)) != 0) status(3)
            }
            E.SNATCH -> if (predicted != null && (predicted.flags and 0x8) != 0) status(3) // FLAG_SNATCH_AFFECTED
            E.DISABLE -> {
                if (def.item != "Mental Herb" && !hitsFirst(turn, atk, move, def) && predicted != null && predicted.split == SPLIT_STATUS) status(1)
            }
            // Radical Red: only a staller, when the split heals it enough.
            E.PAIN_SPLIT -> if ((atk.hp + def.hp) / 2.0 > atk.hp * 1.2 && style == STYLE_STALL) status(2)
            E.SEMI_INVULNERABLE -> {
                if (isSweeper(style)) {
                    if (if (hitsFirst(turn, atk, move, def)) predicted != null && predicted.effect in setOf(E.EXPLOSION, E.PROTECT)
                        else predicted != null && predicted.effect == E.SEMI_INVULNERABLE
                    ) up(3)
                }
            }
            // Radical Red: Sky Drop against a foe taking chip damage, for a sweeper.
            E.LAST_RESORT -> if (move.name == "Sky Drop" && isSweeper(style) && secondaryDamage(def, field) > 0) up(3)
            E.TYPE_CHANGES -> {
                if (move.name == "Soak") {
                    if (isSweeper(style) && hasMoveWhere(atk) { isDamaging(it) && (it.type == "Electric" || it.type == "Grass") }) status(2)
                } else if (defAbility(atk, def) == "Wonder Guard") {
                    status(2)
                }
            }
            E.ATTACK_BLOCKERS -> {
                val healing = setOf(E.RESTORE_HP, E.MORNING_SUN, E.SWALLOW, E.WISH, E.REST)
                val healer = hasMoveWhere(def) { it.effect in healing }
                if (move.name == "Throat Chop") {
                    if (hitsFirst(turn, atk, move, def) && predicted != null && predicted.sound) status(3)
                    else if (isSweeper(style) && hasMoveWhere(def) { it.sound }) up(3)
                } else if (move.name == "Telekinesis") {
                    if (hasMoveWhere(atk) { it.accuracy != 0 && it.accuracy < 90 }) status(1)
                } else if (move.name == "Embargo") {
                    if (def.item.isNotEmpty()) status(1)
                } else if (move.name != "Powder") {
                    // Heal Block, Psychic Noise, Glaive Rush: against healing.
                    if (hitsFirst(turn, atk, move, def) && predicted != null && predicted.effect in healing) status(3)
                    else if (healer || def.item == "Leftovers" || (def.item == "Black Sludge" && "Poison" in def.types)) status(2)
                } else if (predicted != null && predicted.type == "Fire" && isDamaging(predicted)) {
                    status(3)
                }
            }
            // Radical Red: only Fake Out itself (not First Impression).
            E.FAKE_OUT -> if (move.name == "Fake Out" && shouldUseFakeOut(turn, atk, def, move)) up(8)
            E.BATON_PASS, E.TELEPORT -> run {
                if (move.name == "Baton Pass") {
                    if (style == STYLE_BATON_PASS) up(3)
                    return@run
                }
                // Radical Red: Shed Tail +3 with someone to pass to.
                if (move.name == "Shed Tail") {
                    if (atk.monsLeft > 1) up(3)
                    return@run
                }
                val pivot = shouldPivot(turn, atk, def, i)
                if (pivot == "pivot") {
                    up(pivotBonus(style))
                } else if (pivot == "immediately") {
                    up(9)
                } else if (pivot == "dont") {
                    score = max(0, score - 9)
                    if (isStrongest(turn, atk, def, i)) recalcStrongestIgnoring(turn, atk, def, i)
                }
                // Radical Red: a Zero Form Palafin always wants to switch out (+18).
                if (atk.name == "Palafin") up(18)
            }
            E.EXPLOSION -> run {
                if (predicted == null || predicted.effect == E.PROTECT) return@run
                // Radical Red: +4 with a Gem of its type or a Custap Berry in the red; when another move already knocks out,
                // only against useful boosts.
                if (atk.item == move.type + " Gem" || (atk.item == "Custap Berry" && healthPercent(atk) <= 25)) {
                    up(4)
                    return@run
                }
                if (!isIncapacitated(atk) && atk.moves.withIndex().any { (j, m) ->
                        j != i && m != null && m.effect != E.FUTURE_SIGHT && isUsable(atk, j, field) && knocksOut(turn, atk, def, j, 1)
                    }
                ) {
                    if (anyUsefulStatRaised(def, atk)) up(4)
                    return@run
                }
                if (if (atk.speed >= def.speed) can2HKO(turn, atk, def) && !canKnockOut(turn, def, atk)
                    else can2HKO(turn, atk, def) && !can2HKO(turn, def, atk)
                ) return@run
                up(4)
            }
            E.LEECH_SEED -> {
                if ("Grass" !in def.types && !def.seeded && !hasMoveNamed(def, "Rapid Spin") && def.ability !in setOf("Liquid Ooze", "Magic Guard")) status(3)
            }
            E.DESTINY_BOND -> {
                if (if (hitsFirst(turn, atk, move, def)) canKnockOut(turn, def, atk) else can2HKO(turn, def, atk)) status(3)
            }
            E.HAZE, E.REMOVE_TARGET_STAT_CHANGES -> {
                if (shouldPhaze(turn, atk, def, i)) {
                    if (style == STYLE_PHAZING) up(8) else status(2)
                } else if (effect == E.HAZE && usefulStatChanges(atk, -1) > 0) {
                    status(1)
                }
            }
            E.ROAR -> {
                if (shouldPhaze(turn, atk, def, i)) {
                    if (style == STYLE_PHAZING) up(if (canKnockOut(turn, def, atk) && move.priority < 0) 1 else 8) else status(2)
                }
            }
            E.KNOCK_OFF -> run {
                // Not an item the foe is glad to lose or that can't be knocked off (Mega Stones); Radical Red has no Sticky
                // Hold check here, and only another move knocking out (not Knock Off) cancels it.
                if (def.item.isEmpty() || def.item in setOf("Iron Ball", "Lagging Tail", "Sticky Barb") ||
                    Regex("ite( [XY])?$").containsMatchIn(def.item) || def.item.endsWith("ium Z") || hasMoveNamed(atk, "Poltergeist")
                ) return@run
                if (move.name == "Corrosive Gas") status(2)
                else if (!anotherMoveKnocksOut(turn, atk, def, i)) up(3)
            }
            E.FOCUS_ENERGY -> status(if (atk.ability in setOf("Super Luck", "Sniper") || atk.item == "Scope Lens") 2 else 1)
            // Radical Red: no change for Memento, Healing Wish or Final Gambit.
            E.BRICK_BREAK -> {
                if (def.sideState.reflect || def.sideState.lightScreen || def.sideState.auroraVeil) {
                    if (isSweeper(style)) up(3) else status(2)
                }
            }
            E.SNORE, E.SLEEP_TALK -> if (isAsleep(atk)) up(10)
        }
        if (isDamaging(move)) score = damageMoveBonus(turn, atk, def, i, score)
        // Radical Red: a frozen AI wants a move that thaws it (Scald, Flare Blitz...), +10.
        if (atk.status == "frz" && move.name in UNFREEZE_MOVES) score += 10
        return min(score, 255)
    }

    private fun sleepBonus(turn: AiTurn, atk: AiSide, def: AiSide, move: AiMove): Int {
        val field = turn.battle.field
        if (badIdeaToStatus(atk, def, "slp", field) || !canBeSlept(atk, def, field)) return 0
        if (move.effect == E.YAWN && canKnockOut(turn, atk, def)) return 0
        val accurate = accuracyOf(atk, def, move, field) >= 80
        val three = statusBonus(turn, atk, def, 3)
        return when (styleOf(turn, atk)) {
            STYLE_KILL -> 0
            STYLE_SETUP_STATS, STYLE_BATON_PASS, STYLE_PHAZING -> if (accurate) 9 else three
            STYLE_SETUP_STATUS, STYLE_SETUP_SCREENS, STYLE_SUPPORT_SCREENS, STYLE_HAZARDS -> if (accurate) 8 else three
            STYLE_CLERIC -> if (accurate) 7 else three
            else -> three
        }
    }

    private fun shouldSetUpScreens(turn: AiTurn, atk: AiSide, def: AiSide, move: AiMove): Boolean {
        if (move.name == "Aurora Veil") {
            return isHail(turn.battle.field) && !atk.sideState.auroraVeil && !(atk.sideState.reflect && atk.sideState.lightScreen)
        }
        val physical = move.effect == E.REFLECT
        val wanted = if (physical) hasPhysical(def) else hasSpecial(def)
        if (!wanted) return false
        if (!hasMoveWhere(def) { isDamaging(it) }) return true
        val predicted = turn.predicted(def)
        val other = if (physical) E.LIGHT_SCREEN else E.REFLECT
        if (hasPhysical(def) && hasSpecial(def) && hasEffect(atk, other) && predicted != null &&
            predicted.split == (if (physical) SPLIT_SPECIAL else SPLIT_PHYSICAL)
        ) return false
        return true
    }

    private fun hazardBonus(turn: AiTurn, atk: AiSide, def: AiSide, move: AiMove): Int {
        val worth = def.monsLeft != 2
        return when (styleOf(turn, atk)) {
            STYLE_KILL -> 0
            STYLE_SETUP_STATS, STYLE_SETUP_STATUS -> if (worth) statusBonus(turn, atk, def, 2) else 0
            STYLE_STALL, STYLE_CLERIC, STYLE_SETUP_SCREENS, STYLE_SUPPORT_SCREENS -> statusBonus(turn, atk, def, if (worth) 2 else 1)
            STYLE_BATON_PASS -> {
                val ok = if (atk.speed > def.speed) !canKnockOut(turn, def, atk) && !willFaintFromChip(atk, turn.battle.field)
                else !can2HKO(turn, def, atk)
                if (ok) 4 else 0
            }
            STYLE_PHAZING -> statusBonus(turn, atk, def, if (def.monsLeft == 2) 2 else 3)
            STYLE_HAZARDS -> mapOf("Sticky Web" to 7, "Stealth Rock" to 6, "Toxic Spikes" to 5)[move.name] ?: 4
            else -> 0
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun healAmount(atk: AiSide, def: AiSide, move: AiMove, field: AiField): Int {
        val max = atk.maxHP.toDouble()
        val heal = when (move.effect) {
            E.REST -> max
            E.MORNING_SUN -> when {
                move.name == "Shore Up" -> if (field.weather == "Sand") max else max / 2
                "Sun" in field.w -> max
                else -> if (!field.weather.isNullOrEmpty()) max / 4 else max / 2
            }
            E.ABSORB -> 0.0 // the drain is worked out from the damage below
            else -> if (move.name == "Life Dew" || move.name == "Jungle Healing") max / 4 else max / 2
        }
        return min(floor(heal).toInt(), atk.maxHP - 1)
    }

    // ShouldRecover: healing keeps the AI out of KO range.
    private fun shouldRecover(turn: AiTurn, atk: AiSide, def: AiSide, move: AiMove): Boolean {
        val field = turn.battle.field
        var heal = healAmount(atk, def, move, field)
        if (move.effect == E.ABSORB) {
            val i = indexOf(atk, move)
            val drain = move.drain
            heal = min(
                floor(moveDamage(turn, atk, def, i) * (if (drain != null) drain[0].toDouble() / drain[1] else 0.5) *
                    (if (atk.item == "Big Root") 1.3 else 1.0)).toInt(),
                atk.maxHP - 1,
            )
        }
        if (hitsFirst(turn, atk, move, def)) {
            if (canKnockOut(turn, def, atk) && !canKnockOutAfterHealing(turn, def, atk, heal)) return true
            return can2HKO(turn, def, atk) && !isIncapacitated(def) && heal > atk.hp && (turn.simulatedRNG(1) and 1) == 0
        }
        return !canKnockOut(turn, def, atk) && !isIncapacitated(def) && can2HKO(turn, def, atk) &&
            !canKnockOutAfterHealing(turn, def, atk, heal, true)
    }

    // CanKnockOutAfterHealing (one hit), from the healed HP (or after a hit first, when dealOneHitFirst).
    private fun canKnockOutAfterHealing(turn: AiTurn, atk: AiSide, def: AiSide, heal: Int, dealOneHitFirst: Boolean = false): Boolean {
        val field = turn.battle.field
        for (i in 0 until 4) {
            if (atk.moves.getOrNull(i) == null || !isUsable(atk, i, field)) continue
            val damage = moveDamage(turn, atk, def, i)
            val hp: Int
            if (dealOneHitFirst) {
                if (damage >= def.hp) return true
                hp = min(def.hp - damage + heal + passiveRecovery(def, field) - secondaryDamage(def, field), def.maxHP)
            } else {
                hp = min(def.hp + heal, def.maxHP)
            }
            if (damage >= hp) return true
        }
        return false
    }

    private fun shouldProtect(turn: AiTurn, atk: AiSide, def: AiSide): Boolean {
        val field = turn.battle.field
        if ((willFaintFromChip(atk, field) && def.ability !in MOXIE_ABILITIES) || isIncapacitated(def)) return false
        // Radical Red: not while badly poisoned.
        if (atk.status == "tox" && atk.ability !in setOf("Poison Heal", "Magic Guard", "Toxic Boost")) return false
        if ((atk.item == "Toxic Orb" && atk.status.isEmpty() && (atk.ability in setOf("Poison Heal", "Toxic Boost", "Quick Feet", "Magic Guard") ||
                hasMoveNamed(atk, "Facade"))) || (atk.item == "Flame Orb" && atk.status.isEmpty() &&
                (atk.ability in setOf("Guts", "Flare Boost", "Magic Guard") || hasMoveNamed(atk, "Facade")))
        ) return true
        if (atk.ability == "Speed Boost" && atk.speed <= def.speed && stage(atk, "spe") < 12) return true
        val predicted = turn.predicted(def)
        if (predicted != null && predicted.effect == E.EXPLOSION) return true
        val passive = passiveRecovery(atk, field)
        if (canKnockOut(turn, def, atk)) return !canKnockOutAfterHealing(turn, def, atk, passive) || secondaryDamage(def, field) > 0
        if (can2HKO(turn, def, atk)) return secondaryDamage(def, field) > 0 || !twoHitsAfterHealing(turn, def, atk, passive)
        return false
    }

    private fun twoHitsAfterHealing(turn: AiTurn, atk: AiSide, def: AiSide, heal: Int): Boolean {
        val field = turn.battle.field
        for (i in 0 until 4) {
            if (atk.moves.getOrNull(i) == null || !isUsable(atk, i, field)) continue
            if (finalDamage(turn, atk, def, i, 2) >= min(def.hp + heal, def.maxHP)) return true
        }
        return false
    }

    private fun protectBonus(turn: AiTurn, atk: AiSide, def: AiSide): Int = when (styleOf(turn, atk)) {
        STYLE_STALL -> if (isTrappedBy(def, atk, turn.battle.field)) 7 else 3
        STYLE_KILL, STYLE_BATON_PASS -> 0
        else -> statusBonus(turn, atk, def, 3)
    }

    private fun shouldUseSubstitute(turn: AiTurn, atk: AiSide, def: AiSide, move: AiMove): Boolean {
        if (isIncapacitated(def)) return true
        val predicted = turn.predicted(def) ?: return turn.predictedSwitch[def.key] == true
        val index = indexOf(def, predicted)
        val damage = moveDamage(turn, def, atk, index)
        if (hitsFirst(turn, atk, move, def)) return damage < atk.maxHP / 4.0
        return atk.hp - damage > atk.maxHP / 4.0
    }

    private fun substituteBonus(turn: AiTurn, atk: AiSide, def: AiSide): Int {
        // The original's table is an object literal: its STATUS(2) entry is worked out whatever the style (and may draw
        // a coin), so it is here too.
        val setupStatus = statusBonus(turn, atk, def, 2)
        return substituteBonusOf(styleOf(turn, atk), setupStatus)
    }

    private fun substituteBonusOf(style: Int, setupStatus: Int): Int = when (style) {
        1 -> 0
        2 -> 8
        3 -> setupStatus
        5 -> 5
        6, 7 -> 4
        4, 8 -> 3
        9 -> 5
        10 -> 3
        else -> 0
    }

    // CalcShouldAIUseZMove for a damaging move (one Z-Move a battle, taken as not used yet).
    private fun shouldUseZMove(turn: AiTurn, atk: AiSide, def: AiSide, i: Int): Boolean {
        val move = atk.moves.getOrNull(i) ?: return false
        val z = atk.zMoves?.getOrNull(i) ?: return false
        val field = turn.battle.field
        val predicted = turn.predicted(def)
        if (move.name == "Fake Out" && shouldUseFakeOut(turn, atk, def, move)) return false
        if (predicted != null && predicted.effect == E.SUBSTITUTE && !hitsFirst(turn, atk, z, def) && atk.ability != "Infiltrator") return false
        if (defAbility(atk, def) == "Disguise" && def.name.startsWith("Mimikyu") && "Busted" !in def.name) return false
        if (predicted != null && predicted.name in Z_PROTECT_MOVES) return false
        if (move.effect in setOf(E.RECHARGE, E.SEMI_INVULNERABLE, E.SKY_ATTACK, E.SKULL_BASH, E.SOLARBEAM, E.SKY_DROP)) return true
        // The move KOs anyway (and is sure enough to hit), with more of your Pokémon to come: keep the Z.
        if (move.effect != E.OVERHEAT && (move.effect != E.SUPERPOWER || atk.ability == "Contrary") &&
            (atk.ability == "Magic Guard" || !isRecoilMove(move)) && knocksOut(turn, atk, def, i, 1) &&
            (moveWillHit(atk, def, move, field) || accuracyOf(atk, def, move, field) >= 90) && def.monsLeft >= 2
        ) return false
        if (atk.item == "Normalium Z" && hasEffect(atk, E.FAIRY_LOCK_HAPPY_HOUR)) return false
        if (move.name == "Spectral Thief" && anyUsefulStatRaised(def, atk)) return false
        return true
    }

    // The battle with each move the AI would turn into a Z-Move replaced by it (TryReplaceMoveWithZMove).
    private fun withZMoves(turn: AiTurn, battle: AiBattle): AiBattle {
        val ai = battle.ai
        val zMoves = ai.zMoves
        if (zMoves == null || battle.mega != null) return battle
        val saved = turn.battle
        val savedCache = turn.cache
        turn.battle = battle
        turn.cache = HashMap()
        val zSlots = ai.moves.indices.map { shouldUseZMove(turn, ai, battle.player, it) }
        turn.battle = saved
        turn.cache = savedCache
        if (zSlots.none { it }) return battle
        val moves = ai.moves.mapIndexed { i, move -> if (zSlots[i]) zMoves[i] else move }
        return battle.copy(ai = ai.copy(moves = moves, zSlots = zSlots))
    }

    private fun shouldUseFakeOut(turn: AiTurn, atk: AiSide, def: AiSide, move: AiMove?): Boolean {
        if (!atk.firstTurn || isAsleep(def) || defAbility(atk, def) in setOf("Inner Focus", "Shield Dust")) return false
        if (!hitsFirst(turn, atk, move, def)) return false
        if (canBeChoiceLocked(atk) && atk.monsLeft <= 1) return def.monsLeft <= 1 && knocksOut(turn, atk, def, indexOf(atk, move), 1)
        return true
    }

    // ShouldPivot: whether U-turn, Volt Switch, Flip Turn or Parting Shot is worth using now. "immediately" when the
    // switching checks chose to pivot out instead of switching.
    private fun shouldPivot(turn: AiTurn, atk: AiSide, def: AiSide, i: Int): String {
        val move = atk.moves[i]!!
        val field = turn.battle.field
        if (turn.goodToPivot && atk === turn.battle.ai) return "immediately"
        val style = styleOf(turn, atk)
        val usefulBoost = (usefulOffenseRaised(atk) && isDamager(style)) || stage(def, "eva") >= 10
        val flags = if (atk === turn.battle.ai && turn.battle.party.isNotEmpty()) switchInFlags(scoreSwitchIns(turn).best) else 0
        if (hazardsHurt(atk, 4, field)) return "dont"
        if (atk.monsLeft <= 1) return "try"
        if (turn.predictedSwitch[def.key] == true && !usefulBoost) return "pivot"
        val damaging = move.split != SPLIT_STATUS
        val full = def.hp == def.maxHP
        val breaksSash = full && (def.item == "Focus Sash" || defAbility(atk, def) == "Sturdy" ||
            defAbility(atk, def) in setOf("Multiscale", "Shadow Shield"))
        val hazardsOnSide = hasHazards(atk.sideState)
        // Pivot if its attacking stat is at -3 or worse (with both kinds of attack CFRU only looks at Sp. Atk).
        fun weakAttacks(): Boolean {
            val physical = hasRealPhysical(atk)
            val special = hasSpecial(atk)
            if (physical && !special) return stage(atk, "atk") <= OFFENSIVE_STAT_MIN
            if (special) return stage(atk, "spa") <= OFFENSIVE_STAT_MIN
            return false
        }
        if (hitsFirst(turn, atk, move, def)) {
            if (anotherMoveKnocksOut(turn, atk, def, i)) return "dont"
            if (canKnockOut(turn, atk, def)) return "try"
            if (isDamager(style) && can2HKO(turn, atk, def)) {
                if (canKnockOut(turn, def, atk)) return "pivot"
                if (damaging && (((flags and SF_RESIST_ALL) != 0 && (flags and (SF_KO_FOE or SF_REMOVE_HAZARDS)) != 0) || breaksSash)) return "pivot"
            } else if (!usefulBoost) {
                if (isDamager(style) && damaging && breaksSash) return "pivot"
                if ((flags and (SF_WALLS_FOE or SF_RESIST_ALL)) != 0) {
                    if (hazardsOnSide && (flags and SF_REMOVE_HAZARDS) != 0) return "pivot"
                    if (willFaintFromChip(atk, field)) return "pivot"
                    if (isDamager(style)) return if (weakAttacks()) "pivot" else "try"
                }
            }
            return "dont"
        }
        if (canKnockOut(turn, def, atk)) return if (move.effect == E.TELEPORT) "dont" else "try"
        if (can2HKO(turn, def, atk)) {
            if (canKnockOut(turn, atk, def)) return if (anotherMoveKnocksOut(turn, atk, def, i)) "dont" else "try"
            if (isDamager(style) && (flags and SF_KO_FOE) != 0 && (flags and (SF_OUTSPEEDS or SF_WALLS_FOE or SF_RESIST_ALL)) != 0) return "pivot"
            return "dont"
        }
        if (canKnockOut(turn, atk, def)) return if (!anotherMoveKnocksOut(turn, atk, def, i) && !usefulBoost) "try" else "dont"
        if (can2HKO(turn, atk, def)) {
            if (isDamager(style) && damaging && (((flags and SF_KO_FOE) != 0 && (flags and (SF_OUTSPEEDS or SF_WALLS_FOE or SF_RESIST_ALL)) != 0) ||
                    ((flags and SF_RESIST_ALL) != 0 && (flags and SF_REMOVE_HAZARDS) != 0) || breaksSash)
            ) return "pivot"
            return "dont"
        }
        if (isDamager(style) && (flags and SF_KO_FOE) != 0 && (flags and SF_OUTSPEEDS) != 0) return "pivot"
        if (!usefulBoost && (flags and (SF_OUTSPEEDS or SF_WALLS_FOE or SF_RESIST_ALL)) != 0) {
            if (hazardsOnSide && (flags and SF_REMOVE_HAZARDS) != 0) return "pivot"
            if (willFaintFromChip(atk, field)) return "pivot"
            if (isDamager(style) && weakAttacks()) return "pivot"
            return "try"
        }
        return "dont"
    }

    private fun pivotBonus(style: Int): Int = when (style) {
        1 -> 3
        2, 3, 5, 6 -> 9
        7, 4, 8 -> 7
        9 -> 4
        10 -> 3
        else -> 3
    }

    // RecalcStrongestMoveIgnoringMove: a pivot move the AI won't use stops counting as its strongest.
    private fun recalcStrongestIgnoring(turn: AiTurn, atk: AiSide, def: AiSide, ignore: Int) {
        var best = 0
        var strongest = 0
        for (i in 0 until 4) {
            val move = atk.moves.getOrNull(i)
            if (i == ignore || move == null || !isUsable(atk, i, turn.battle.field) || move.power == 0) continue
            val damage = moveDamage(turn, atk, def, i)
            if (damage > best) {
                best = damage
                strongest = i
            }
        }
        turn.cache["strongest" + atk.key] = strongest
    }

    private fun shouldPhaze(turn: AiTurn, atk: AiSide, def: AiSide, i: Int): Boolean {
        if (anotherMoveKnocksOut(turn, atk, def, i) || canKnockOut(turn, atk, def)) return false
        if (atk.moves[i]!!.effect == E.ROAR && styleOf(turn, atk) == STYLE_PHAZING &&
            (def.sideState.sr || def.sideState.spikes != 0 || def.sideState.toxicSpikes != 0)
        ) return true
        return anyUsefulStatRaised(def, atk)
    }

    // DamageMoveViabilityIncrease (singles): the KO, desperation and strongest-move bonuses.
    private fun damageMoveBonus(turn: AiTurn, atk: AiSide, def: AiSide, i: Int, score: Int): Int {
        val move = atk.moves[i]!!
        val field = turn.battle.field
        val predicted = turn.predicted(def)
        val style = styleOf(turn, atk)
        val switching = turn.predictedSwitch[def.key] == true
        fun suckerPunchRule() = !(predicted != null && predicted.effect == E.SUCKER_PUNCH) || isDamager(style) ||
            (priorityOf(atk, move, field) > 0 && atk.speed > def.speed)
        fun up(amount: Int) = min(255, score + amount)
        val helpsAccuracy = hasEffect(atk, E.ACCURACY_UP, E.ACCURACY_UP_2, E.EVASION_DOWN, E.EVASION_DOWN_2, E.LOCK_ON, E.ATK_ACC_UP) ||
            hasMoveNamed(atk, "Coil", "Defog")
        val noProtect = !hasProtectMove(atk)
        val chipSafe = !willFaintFromChip(def, field) ||
            (predicted != null && (predicted.effect == E.RESTORE_HP || predicted.effect == E.MORNING_SUN)) || atk.ability in MOXIE_ABILITIES

        if (!switching && knocksOutWithBestAccuracy(turn, atk, def, i, true) &&
            (accuracyOf(atk, def, move, field) >= 70 || !helpsAccuracy || canKnockOut(turn, def, atk))
        ) {
            return if (suckerPunchRule()) up(9) else score
        }
        if (!switching && noProtect && !hitsFirst(turn, atk, move, def) && knocksOutWithBestAccuracy(turn, atk, def, i, false) && chipSafe) {
            return up(slowKOBonus(turn, atk, def))
        }
        val predictedIndex = if (predicted != null) indexOf(def, predicted) else -1
        if (noProtect && predictedIndex != -1 && knocksOut(turn, def, atk, predictedIndex, 1) &&
            strongestGoesFirst(turn, atk, def) == i && !(turn.goodToPivot && atk === turn.battle.ai) &&
            (style != STYLE_HAZARDS || (turn.simulatedRNG(3) and 1) != 0) &&
            (style != STYLE_PHAZING || (priorityOf(atk, move, field) > 0 && atk.speed < def.speed)) &&
            (style != STYLE_STALL || (priorityOf(atk, move, field) > 0 && atk.speed < def.speed)) &&
            !(hasEffect(atk, E.FAKE_OUT) && shouldUseFakeOut(turn, atk, def, atk.moves.firstOrNull { it != null && it.effect == E.FAKE_OUT }))
        ) {
            if (suckerPunchRule()) return up(9)
            if (!isStrongest(turn, atk, def, i)) return score
        }
        if (isStrongest(turn, atk, def, i) && (hitsFirst(turn, atk, move, def) || chipSafe)) {
            if (score == 100 && knocksOut(turn, atk, def, i, 1) && !(predicted != null && (predicted.effect == E.RESTORE_HP ||
                    predicted.effect == E.ABSORB))
            ) {
                return up(mapOf(7 to 5, 8 to 6, 6 to 6, 9 to 8, 5 to 8, 10 to 4)[style] ?: 2)
            }
            return up(2)
        }
        return score
    }

    private fun slowKOBonus(turn: AiTurn, atk: AiSide, def: AiSide): Int {
        val lastFoe = (canKnockOut(turn, def, atk) || can2HKO(turn, def, atk)) && def.monsLeft <= 1
        val foe2HKO = can2HKO(turn, def, atk)
        return when (styleOf(turn, atk)) {
            STYLE_SETUP_STATS -> if (lastFoe) 8 else 6
            STYLE_STALL -> if (lastFoe) 9 else 8
            STYLE_BATON_PASS -> if (lastFoe) 9 else if (!foe2HKO) 3 else 8
            STYLE_CLERIC -> if (lastFoe) 9 else if (!foe2HKO) 6 else 7
            STYLE_SETUP_SCREENS, STYLE_SUPPORT_SCREENS -> if (lastFoe) 9 else if (!foe2HKO) 6 else 9
            STYLE_PHAZING -> if (lastFoe) 9 else 8
            STYLE_HAZARDS -> if (lastFoe) 9 else if (!foe2HKO) 3 else 6
            else -> 8
        }
    }

    // #endregion
    // #region switching (ai_switching.c, singles)

    private fun withStage(value: Int, stageIndex: Int): Int {
        val ratio = STAT_STAGE_RATIOS[max(0, min(12, stageIndex))]
        return value * ratio[0] / ratio[1]
    }

    // AnyStatGreaterThan: any stat stage above `amount` (6 is no change).
    private fun anyStageAbove(side: AiSide, amount: Int) = listOf("atk", "def", "spe", "spa", "spd", "acc", "eva").any { stage(side, it) > amount }

    // AnyUsefulOffseniveStatIsRaised, as Radical Red has it.
    private fun usefulOffenseRaised(side: AiSide) =
        (stage(side, "atk") > 6 && hasRealPhysical(side)) || (stage(side, "spa") > 6 && hasSpecial(side)) || stage(side, "spe") > 6

    private fun typeEffectiveness(type: String, types: List<String>): Double = types.fold(1.0) { product, t -> product * pack.effectiveness(type, t) }

    // Any hazard on a side (CFRU's SIDE_STATUS_SPIKES: Stealth Rock, Spikes, Toxic Spikes, Sticky Web).
    private fun hasHazards(s: AiSideState) = s.sr || s.spikes > 0 || s.toxicSpikes > 0 || s.stickyWeb

    // GetMonEntryHazardDamage: Stealth Rock and Spikes on the AI's side of the field.
    private fun entryHazardDamage(mon: AiSide, sideState: AiSideState, field: AiField): Int {
        if (mon.ability == "Magic Guard" || mon.item == "Heavy-Duty Boots") return 0
        var damage = 0
        if (sideState.sr) damage += floor(mon.maxHP * typeEffectiveness("Rock", mon.types) / 8).toInt()
        if (sideState.spikes > 0 && isGrounded(mon, field)) damage += mon.maxHP / intArrayOf(0, 8, 6, 4)[min(3, sideState.spikes)]
        return damage
    }

    private fun willFaintFromHazards(mon: AiSide, sideState: AiSideState, field: AiField) = entryHazardDamage(mon, sideState, field) >= mon.hp

    // WillTakeSignificantDamageFromEntryHazards: coming back in would cost 1/fraction of its HP.
    private fun hazardsHurt(side: AiSide, fraction: Int, field: AiField): Boolean {
        val damage = entryHazardDamage(side, side.sideState, field)
        return damage > 0 && (damage >= side.hp || damage >= side.maxHP / fraction)
    }

    private fun isMoldBreaker(side: AiSide) = side.ability in MOLD_BREAKERS

    // IsTrapped through an ability (Shadow Tag, Arena Trap, Magnet Pull); trapping moves are not known.
    private fun isTrappedBy(side: AiSide, foe: AiSide, field: AiField): Boolean {
        if ("Ghost" in side.types || side.item == "Shed Shell") return false
        if (foe.ability == "Shadow Tag") return side.ability != "Shadow Tag"
        if (foe.ability == "Arena Trap") return isGrounded(side, field)
        if (foe.ability == "Magnet Pull") return "Steel" in side.types
        return false
    }

    private fun isPivotMove(move: AiMove?) = move != null && move.effect == E.BATON_PASS && move.name != "Baton Pass"

    private fun hasPivotMoveThatAffects(turn: AiTurn, atk: AiSide, def: AiSide, mustGoFirst: Boolean) = atk.moves.withIndex().any { (i, move) ->
        if (!isPivotMove(move) || !isUsable(atk, i, turn.battle.field)) return@any false
        move!!
        if (move.split != SPLIT_STATUS && move.maxDamage == 0) return@any false
        !mustGoFirst || hitsFirst(turn, atk, move, def)
    }

    // HealingMoveInMonMoveset / HealingMoveInMoveset.
    private fun hasHealingMove(side: AiSide) = hasEffect(side, E.RESTORE_HP, E.MORNING_SUN, E.SWALLOW, E.WISH)

    // GetFinalAIMoveDamageFromParty (AI_CalcPartyDmg): one use of a benched Pokémon's move on you.
    private fun partyDamage(turn: AiTurn, mon: AiSide, def: AiSide, i: Int): Int = turn.keep("pdmg" + mon.key + i) {
        partyDamageNow(turn, mon, def, i)
    }

    private fun partyDamageNow(turn: AiTurn, mon: AiSide, def: AiSide, i: Int): Int {
        val move = mon.moves.getOrNull(i)
        val field = turn.battle.field
        if (move == null || move.split == SPLIT_STATUS || move.power == 0) return 0
        if (move.effect == E.OHKO) return def.hp
        if (move.effect == E.BURN_UP && "Fire" !in mon.types) return 0
        if (field.terrain == "Psychic" && priorityOf(mon, move, field) > 0 && isGrounded(def, field)) return 0
        if (defAbility(mon, def) in setOf("Dazzling", "Queenly Majesty", "Armor Tail") && priorityOf(mon, move, field) > 0) return 0
        var damage: Int
        if (move.effect in FIXED_DAMAGE_EFFECTS) {
            damage = if (move.effect == E.ENDEAVOR) max(0, def.hp - mon.hp) else move.maxDamage
        } else {
            val crit = move.critChance >= 1 || (move.critChance >= 0.5 && turn.coin("pcrit" + mon.key + i, 0.5))
            damage = (if (crit) move.maxCritDamage else move.maxDamage) * 96 / 100
            if (move.hits <= 1 && (move.effect == E.FALSE_SWIPE || (def.hp == def.maxHP &&
                    (def.item == "Focus Sash" || defAbility(mon, def) == "Sturdy")))
            ) damage = min(damage, def.hp - 1)
        }
        return min(damage, def.maxHP)
    }

    private fun partyMoveKnocksOut(turn: AiTurn, mon: AiSide, def: AiSide, i: Int): Boolean {
        val move = mon.moves.getOrNull(i)
        if (move == null || move.split == SPLIT_STATUS) return false
        if (def.ability == "Disguise" && def.abilityOn && !isMoldBreaker(mon)) return false
        return partyDamage(turn, mon, def, i) >= def.hp
    }

    // CanKnockOutFromParty: any of its moves.
    private fun partyCanKnockOut(turn: AiTurn, mon: AiSide, def: AiSide): Boolean {
        for (i in 0 until 4) {
            if (mon.moves.getOrNull(i) != null && isUsable(mon, i, turn.battle.field) && partyMoveKnocksOut(turn, mon, def, i)) return true
        }
        return false
    }

    private fun partyMaxDamage(turn: AiTurn, mon: AiSide, def: AiSide): Int {
        var best = 0
        for (i in 0 until 4) {
            if (mon.moves.getOrNull(i) != null && isUsable(mon, i, turn.battle.field)) best = max(best, partyDamage(turn, mon, def, i))
        }
        return best
    }

    // AI_CalcMonDefDmg: one use of your move on a benched Pokémon, after it comes in.
    private fun damageToBenched(turn: AiTurn, atk: AiSide, mon: AiSide, i: Int): Int = turn.keep("bdmg" + atk.key + i + mon.key) {
        damageToBenchedNow(turn, atk, mon, i)
    }

    private fun damageToBenchedNow(turn: AiTurn, atk: AiSide, mon: AiSide, i: Int): Int {
        val move = atk.moves.getOrNull(i)
        val hit = mon.incoming.getOrNull(i)
        if (move == null || hit == null) return 0
        if (move.effect != E.PAIN_SPLIT && (move.split == SPLIT_STATUS || (hit.maxDamage == 0 && move.power > 0))) return 0
        when (move.effect) {
            E.SUPER_FANG -> return mon.hp / 2
            E.ENDEAVOR -> return max(0, mon.hp - atk.hp)
            E.PAIN_SPLIT -> return max(0, mon.hp - max(1, (atk.hp + mon.hp) / 2))
            E.BURN_UP -> if ("Fire" !in atk.types) return 0
        }
        if (move.effect in FIXED_DAMAGE_EFFECTS) return hit.maxDamage
        val crit = hit.critChance >= 1 || (hit.critChance >= 0.5 && turn.coin("bcrit" + atk.key + i + mon.key, 0.5))
        var damage = (if (crit) hit.maxCritDamage else hit.maxDamage) * 96 / 100
        if (move.hits <= 1) {
            if (move.effect == E.FALSE_SWIPE) {
                damage = min(damage, mon.hp - 1)
            } else if (entryHazardDamage(mon, mon.sideState, turn.battle.field) == 0 && mon.hp == mon.maxHP &&
                ((mon.ability == "Sturdy" && !isMoldBreaker(atk)) || mon.item == "Focus Sash")
            ) {
                damage = min(damage, mon.hp - 1)
            }
        }
        return damage
    }

    // Disguise (and Ice Face against physical moves) takes the first hit.
    private fun disguiseTakesHit(mon: AiSide, atk: AiSide, move: AiMove): Boolean {
        if (isMoldBreaker(atk) || !mon.abilityOn) return false
        return mon.ability == "Disguise" || (mon.ability == "Ice Face" && move.split == SPLIT_PHYSICAL)
    }

    private fun benchedHitDamage(turn: AiTurn, mon: AiSide, i: Int): Int {
        val player = turn.battle.player
        val move = player.moves[i]!!
        if (disguiseTakesHit(mon, player, move)) return if (mon.ability == "Disguise") mon.maxHP / 8 else 0
        return damageToBenched(turn, player, mon, i) + entryHazardDamage(mon, mon.sideState, turn.battle.field)
    }

    // PredictedMoveWontDoTooMuchToMon: the switch-in can take your predicted move.
    private fun predictedHitIsSafe(turn: AiTurn, mon: AiSide, flags: Int): Boolean {
        val player = turn.battle.player
        val move = turn.predicted(player)
        if ((flags and SF_FAINTS) != 0) return false
        if (move == null || move.split == SPLIT_STATUS || move.name == "Pursuit") return true
        if ((flags and SF_RESIST_ALL) != 0) return true
        if (move.effect == E.SUCKER_PUNCH) return true
        val damage = benchedHitDamage(turn, mon, indexOf(player, move))
        if (damage >= mon.hp) return false
        if (damage * 2 < mon.hp) return true
        return damage * 2 >= mon.maxHP && hasHealingMove(mon) && mon.speed > player.speed
    }

    // PredictedMoveWontKOMon.
    private fun predictedHitWontKO(turn: AiTurn, mon: AiSide): Boolean {
        val player = turn.battle.player
        val move = turn.predicted(player)
        if (move == null || move.split == SPLIT_STATUS || move.effect == E.SUCKER_PUNCH) return true
        return benchedHitDamage(turn, mon, indexOf(player, move)) < mon.hp
    }

    // PredictedMoveKOsSelfDueToContact: your predicted move would faint you on its Rough Skin, Iron Barbs or Rocky Helmet.
    private fun contactKnocksYouOut(turn: AiTurn, mon: AiSide): Boolean {
        val player = turn.battle.player
        val move = turn.predicted(player)
        if (move == null || (move.flags and FLAG_CONTACT) == 0 || player.ability == "Long Reach" || player.item == "Protective Pads") return false
        if (player.ability == "Magic Guard") return false
        var damage = 0
        if (mon.ability in setOf("Rough Skin", "Iron Barbs") && !isMoldBreaker(player)) damage += player.maxHP / 8
        if (mon.item == "Rocky Helmet") damage += player.maxHP / 6
        return damage > 0 && damage >= player.hp
    }

    internal class SwitchEntry(val mon: AiSide, var score: Int = 0, var flags: Int = 0, var canRemoveHazards: Boolean = false, var ranked: Boolean = false)

    internal class SwitchScores(val entries: List<SwitchEntry>, val best: SwitchEntry?, val second: SwitchEntry?)

    // CalcMostSuitableMonToSwitchInto: each benched Pokémon scored against yours.
    private fun scoreSwitchIns(turn: AiTurn): SwitchScores = turn.keep("switchIns") { scoreSwitchInsNow(turn) }

    private fun scoreSwitchInsNow(turn: AiTurn): SwitchScores {
        val battle = turn.battle
        val foe = battle.player
        val ai = battle.ai
        val field = battle.field
        val smart = battle.flags > AI_NEGATIVES
        val hazardsUp = hasHazards(ai.sideState)
        val viable = 1 + battle.party.size
        val entries = ArrayList<SwitchEntry>()
        var best: SwitchEntry? = null
        var second: SwitchEntry? = null
        var lastValid: SwitchEntry? = null
        var secondLastValid: SwitchEntry? = null
        fun sameAsBest(entry: SwitchEntry, other: SwitchEntry, key: String): Boolean {
            if (entry.score != other.score) return false
            if ((other.flags and SF_FAINTS) != 0 && (entry.flags and SF_FAINTS) == 0) return true
            return (other.flags and SF_FAINTS) == 0 && (entry.flags and SF_FAINTS) == 0 && turn.coin(key, 0.5)
        }
        for (mon in battle.party) {
            val entry = SwitchEntry(mon)
            entries.add(entry)
            secondLastValid = lastValid
            lastValid = entry
            if (willFaintFromHazards(mon, ai.sideState, field)) continue
            fun noteHazardRemoval(move: AiMove) {
                if (move.effect == E.RAPID_SPIN && hazardsUp) entry.canRemoveHazards = viable >= 2
            }
            if (mon.speed >= foe.speed) {
                entry.score += SS_OUTSPEEDS
                entry.flags = entry.flags or SF_OUTSPEEDS
            }
            if (partyCanKnockOut(turn, mon, foe)) {
                entry.score += SS_KO_FOE
                entry.flags = entry.flags or SF_KO_FOE
                if (mon.ability in MOXIE_ABILITIES || isTrappedBy(foe, mon, field)) {
                    entry.score += SS_REVENGE_KILL
                    entry.flags = entry.flags or SF_REVENGE_KILL
                } else {
                    // Priority moves first (each one found goes to the front), then the rest in order.
                    val ordered = ArrayList<Pair<Int, Boolean>>()
                    for (k in 0 until 4) {
                        val m = mon.moves.getOrNull(k)
                        if (m == null || !isUsable(mon, k, field)) continue
                        if (priorityOf(mon, m, field) > 0) ordered.add(0, k to true) else ordered.add(k to false)
                    }
                    for ((index, priority) in ordered) {
                        val move = mon.moves[index]!!
                        noteHazardRemoval(move)
                        if (move.split != SPLIT_STATUS && priority && partyMoveKnocksOut(turn, mon, foe, index)) {
                            entry.score += SS_REVENGE_KILL
                            entry.flags = entry.flags or SF_REVENGE_KILL
                            if ((entry.flags and SF_OUTSPEEDS) == 0) {
                                entry.score += SS_OUTSPEEDS
                                entry.flags = entry.flags or SF_OUTSPEEDS
                            }
                            break
                        } else if (move.name == "Fell Stinger" || move.name == "Pursuit") {
                            if (partyMoveKnocksOut(turn, mon, foe, index)) {
                                entry.score += SS_REVENGE_KILL
                                entry.flags = entry.flags or SF_REVENGE_KILL
                                break
                            }
                        }
                    }
                }
            } else {
                var usable = false
                for (k in 0 until 4) {
                    val m = mon.moves.getOrNull(k)
                    if (m == null || !isUsable(mon, k, field)) continue
                    usable = true
                    noteHazardRemoval(m)
                    if (m.split != SPLIT_STATUS && partyMaxDamage(turn, mon, foe) >= foe.hp / 2) {
                        entry.score += SS_CAN_2HKO
                        entry.flags = entry.flags or SF_CAN_2HKO
                        break
                    }
                }
                if (!usable) {
                    entry.score = -1
                    continue
                }
            }

            // How well it takes your moves (priority ones first).
            val foeMoves = ArrayList<Pair<Int, Boolean>>()
            var physical = false
            var special = false
            for (k in 0 until 4) {
                val fm = foe.moves.getOrNull(k)
                if (fm == null || !isUsable(foe, k, field)) continue
                if (fm.split == SPLIT_PHYSICAL) physical = true
                else if (fm.split == SPLIT_SPECIAL) special = true
                else continue
                if (priorityOf(foe, fm, field) > 0) foeMoves.add(0, k to true) else foeMoves.add(k to false)
            }
            val hpOnSwitchIn = max(0, mon.hp - entryHazardDamage(mon, ai.sideState, field))
            var faints = false
            var weak = false
            var neutral = 0
            val recovery = passiveRecovery(mon, field)
            for ((index, priority) in foeMoves) {
                val hitMove = foe.moves[index]!!
                if (smart) {
                    var first = damageToBenched(turn, foe, mon, index)
                    var other = first
                    if (disguiseTakesHit(mon, foe, hitMove)) {
                        first = if (mon.ability == "Disguise") mon.maxHP / 8 else 0
                    } else if (first >= hpOnSwitchIn) {
                        faints = true
                        if ((entry.flags and SF_OUTSPEEDS) != 0 && priority && (field.terrain != "Psychic" || !isGrounded(mon, field))) {
                            entry.flags = entry.flags and SF_OUTSPEEDS.inv()
                        }
                        break
                    }
                    if (mon.ability in setOf("Multiscale", "Shadow Shield") && mon.hp == mon.maxHP && !isMoldBreaker(foe)) other *= 2
                    val adjustedHP = min(hpOnSwitchIn, mon.maxHP)
                    if (adjustedHP + recovery <= first + other) weak = true
                    else if (adjustedHP + recovery * 2 <= first + other * 2) neutral++
                } else {
                    val effectiveness = mon.incoming[index]!!.effectiveness
                    if (effectiveness > 1) {
                        weak = true
                        break
                    } else if (effectiveness == 1.0) {
                        neutral++
                    }
                }
            }
            fun weaken() {
                entry.score = if (entry.score >= SS_WEAK) entry.score - SS_WEAK else 0
            }
            if (faints) {
                if ((entry.flags and SF_OUTSPEEDS) == 0) {
                    entry.flags = entry.flags or SF_FAINTS
                    entry.score = if (entry.score >= SS_FAINTS) entry.score - SS_FAINTS else 0
                } else if ((entry.flags and SF_KO_FOE) == 0) {
                    entry.score = if (entry.score >= SS_FAINTS_BUT_OUTSPEEDS) entry.score - SS_FAINTS_BUT_OUTSPEEDS else 0
                } else {
                    weaken()
                }
            } else if (weak) {
                weaken()
            } else if (neutral == 0) {
                entry.score += SS_RESIST_ALL
                entry.flags = entry.flags or SF_RESIST_ALL
            } else {
                val attack = withStage(foe.stats["atk"] ?: 0, stage(foe, "atk"))
                val spAttack = withStage(foe.stats["spa"] ?: 0, stage(foe, "spa"))
                val monDef = mon.stats["def"] ?: 0
                val monSpd = mon.stats["spd"] ?: 0
                val cantWall = (physical && monDef <= attack) || (!(physical && monDef <= attack) && special && monSpd <= spAttack)
                if (!cantWall) {
                    entry.score += SS_WALLS_FOE
                    entry.flags = entry.flags or SF_WALLS_FOE
                }
            }

            entry.ranked = true
            val b = best
            val s = second
            if (entry.score >= SWITCH_SCORE_MAX && entry.canRemoveHazards) {
                second = best
                best = entry
            } else if (b == null || entry.score > b.score || sameAsBest(entry, b, "bestTie" + mon.key)) {
                second = best
                best = entry
            } else if (s == null || entry.score > s.score || sameAsBest(entry, s, "secondTie" + mon.key)) {
                second = entry
            }
        }
        val b = best
        if (b != null) {
            if ((b.flags and SF_RESIST_ALL) == 0 && (b.flags and (SF_KO_FOE or SF_OUTSPEEDS)) != (SF_KO_FOE or SF_OUTSPEEDS)) {
                for (entry in entries) {
                    if (entry.canRemoveHazards && (entry.flags and SF_FAINTS) == 0) {
                        second = best
                        best = entry
                        break
                    }
                }
            }
            return SwitchScores(entries, best, second)
        }
        // None stood out: the last ones checked.
        return SwitchScores(entries, lastValid?.let { SwitchEntry(it.mon) }, secondLastValid?.let { SwitchEntry(it.mon) })
    }

    private fun switchInFlags(entry: SwitchEntry?) = entry?.flags ?: 0

    // The best Pokémon, the second best, then the rest of the party in order: (entry, flags).
    private fun switchInsInOrder(turn: AiTurn): List<SwitchEntry> {
        val scored = scoreSwitchIns(turn)
        val list = ArrayList<SwitchEntry>()
        scored.best?.let { list.add(it) }
        scored.second?.let { list.add(it) }
        for (entry in scored.entries) if (entry !== scored.best && entry !== scored.second) list.add(entry)
        return list
    }

    /** A switch the checks decided on: to [mon] (null = the best switch-in), or a pivot move instead. */
    internal class SwitchDecision(var mon: AiSide?, val pivot: Boolean = false)

    // FindMonThatAbsorbsOpponentsMove.
    private fun switchToAbsorb(turn: AiTurn, justIn: Boolean): SwitchDecision? {
        val battle = turn.battle
        val ai = battle.ai
        val foe = battle.player
        val move = turn.predicted(foe) ?: return null
        if (stage(ai, "eva") >= 9) return null
        if (move.split == SPLIT_STATUS) return null
        if (!isDamager(styleOf(turn, foe))) {
            if (justIn) return null
            if (turn.coin("absorbHalf", 0.5)) return null
        }
        if (!hitsFirst(turn, foe, move, ai)) {
            if (canKnockOut(turn, ai, foe)) return null
        } else if (!canKnockOut(turn, foe, ai) && canKnockOut(turn, ai, foe) && anyStageAbove(ai, 6)) {
            return null
        }
        if (!canKnockOut(turn, foe, ai) && anyStageAbove(ai, 7)) return null
        if (isMoldBreaker(foe)) return null
        val abilities = SWITCH_ABSORBING[move.type] ?: return null
        if (ai.ability in abilities) return null
        for (entry in switchInsInOrder(turn)) {
            val mon = entry.mon
            if (mon.ability !in abilities || willFaintFromHazards(mon, ai.sideState, battle.field)) continue
            if (mon.ability in HP_ABSORBING && mon.hp == mon.maxHP && entryHazardDamage(mon, ai.sideState, battle.field) == 0) {
                if (justIn) continue
                if (finalDamage(turn, foe, ai, indexOf(foe, move), 2) < ai.hp) continue
            }
            return SwitchDecision(mon)
        }
        return null
    }

    // ShouldSwitchIfWonderGuard: your Wonder Guard Pokémon, and nothing here can touch it.
    private fun switchForWonderGuard(turn: AiTurn): SwitchDecision? {
        val battle = turn.battle
        val ai = battle.ai
        val foe = battle.player
        val field = battle.field
        if (foe.ability != "Wonder Guard" || willFaintFromChip(foe, field)) return null
        for (i in 0 until 4) {
            val move = ai.moves.getOrNull(i)
            if (move == null || !isUsable(ai, i, field)) continue
            if (move.name in ABILITY_IGNORING_MOVES) return null
            if (move.split != SPLIT_STATUS) {
                if (isMoldBreaker(ai) || move.effectiveness > 1) return null
                continue
            }
            when (move.effect) {
                E.SLEEP, E.YAWN -> if (canBeSlept(ai, foe, field)) return null
                E.ROAR -> if (foe.monsLeft > 1) return null
                E.TOXIC, E.POISON -> if (canBePoisoned(ai, foe, field)) return null
                E.WILL_O_WISP -> if (canBeBurned(ai, foe, field)) return null
                E.CONFUSE, E.SWAGGER, E.FLATTER -> if (canBeConfused(ai, foe, field)) return null
                E.PARALYZE -> if (canBeParalyzed(ai, foe, field)) return null
                E.LEECH_SEED -> if ("Grass" !in foe.types) return null
                E.NIGHTMARE -> if (isAsleep(foe)) return null
                E.CURSE -> if ("Ghost" in ai.types) return null
                // (No Perish Song count, pending Wish or Lucky Chant is known: none.)
                E.PERISH_SONG, E.WISH -> return null
                E.TRICK -> {
                    if (move.name == "Trick" && canSwapItems(ai, foe)) {
                        if (ai.item == "Toxic Orb" && canBePoisoned(ai, foe, field)) return null
                        if (ai.item == "Flame Orb" && canBeBurned(ai, foe, field)) return null
                        if (ai.item == "Black Sludge" && "Poison" !in foe.types) return null
                        if (ai.item == "Sticky Barb") return null
                    }
                }
                E.SKILL_SWAP -> if (move.name != "Skill Swap") return null
                E.TYPE_CHANGES -> {
                    if ((move.name == "Trick-or-Treat" && "Ghost" !in foe.types) || (move.name == "Forest's Curse" && "Grass" !in foe.types)) return null
                }
                // (As CFRU: Tailwind counts when your side already has it.)
                E.TEAM_EFFECTS -> if ((move.name == "Tailwind" && ai.sideState.tailwind) || move.name == "Lucky Chant") return null
                E.SPIKES -> {
                    if ((move.name == "Stealth Rock" && !foe.sideState.sr) || (move.name == "Toxic Spikes" && foe.sideState.toxicSpikes < 2) ||
                        (move.name == "Sticky Web" && !foe.sideState.stickyWeb) || (move.name == "Spikes" && foe.sideState.spikes < 3)
                    ) return null
                }
                E.SANDSTORM -> if (foe.types.none { it in setOf("Rock", "Ground", "Steel") }) return null
                E.HAIL -> if ("Ice" !in foe.types) return null
                E.BATON_PASS, E.TELEPORT -> return null
                E.MEMENTO -> return null
            }
        }
        for (mon in battle.party) {
            if (mon.moves.any { it != null && it.split != SPLIT_STATUS && it.effectiveness > 1 }) return SwitchDecision(mon)
        }
        return null
    }

    // ResistsAllMoves: none of the attacker's moves does a third of the defender's HP.
    private fun resistsAllMoves(turn: AiTurn, atk: AiSide, def: AiSide): Boolean {
        for (i in 0 until 4) {
            if (atk.moves.getOrNull(i) != null && isUsable(atk, i, turn.battle.field) && finalDamage(turn, atk, def, i, 1) >= def.hp / 3) return false
        }
        return true
    }

    // WallsFoe: its Defence (Sp. Def) beats the attacker's Attack (Sp. Atk) for the moves it has.
    private fun wallsFoe(atk: AiSide, def: AiSide): Boolean {
        val attack = withStage(atk.stats["atk"] ?: 0, stage(atk, "atk"))
        val spAttack = withStage(atk.stats["spa"] ?: 0, stage(atk, "spa"))
        if (hasPhysical(atk) && (def.stats["def"] ?: 0) <= attack) return false
        if (hasSpecial(atk) && (def.stats["spd"] ?: 0) <= spAttack) return false
        return true
    }

    // OnlyBadMovesLeftInMoveset: nothing worth using (Negatives only) and nothing that hurts enough.
    private fun onlyBadMovesLeft(turn: AiTurn): Boolean {
        val battle = turn.battle
        val ai = battle.ai
        val foe = battle.player
        val field = battle.field
        if (battle.flags == AI_NEGATIVES) return false
        var damaging = 0
        for (i in 0 until 4) {
            val move = ai.moves.getOrNull(i)
            if (move == null || !isUsable(ai, i, field)) continue
            if (scoreNegatives(turn, ai, foe, i, 100) >= 100) {
                if (move.split == SPLIT_STATUS) return false
                if (move.effect == E.KNOCK_OFF && foe.item.isNotEmpty() && foe.ability != "Sticky Hold") return false
                damaging++
            }
        }
        if (hasEffect(ai, E.PROTECT)) return false
        if (damaging == 0) return true
        if (hasPivotMoveThatAffects(turn, ai, foe, false)) return false
        val damage = finalDamage(turn, ai, foe, strongestMove(turn, ai, foe), 1)
        if (damage >= foe.hp) return false
        val recovery = passiveRecovery(foe, field)
        if (recovery != 0 || hasHealingMove(foe)) {
            if (damage * 3 >= foe.hp + recovery * 2) return false
        } else if (damage * 3 >= foe.hp) {
            return false
        }
        val flags = switchInFlags(scoreSwitchIns(turn).best)
        if (flags == 0) return false
        if ((flags and (SF_KO_FOE or SF_CAN_2HKO)) != 0) return true
        if ((flags and SF_RESIST_ALL) != 0) return !resistsAllMoves(turn, foe, ai)
        if ((flags and SF_WALLS_FOE) != 0) return !wallsFoe(foe, ai)
        return true
    }

    // ShouldSwitchIfOnlyBadMovesLeft.
    private fun switchFromBadMoves(turn: AiTurn, justIn: Boolean): SwitchDecision? {
        if (justIn || !onlyBadMovesLeft(turn)) return null
        val scored = scoreSwitchIns(turn)
        val wanted = SF_OUTSPEEDS or SF_WALLS_FOE or SF_RESIST_ALL
        val best = scored.best
        val second = scored.second
        if (best != null && (best.flags and wanted) != 0 && predictedHitIsSafe(turn, best.mon, best.flags)) return SwitchDecision(null)
        if (second != null && (second.flags and wanted) != 0 && predictedHitIsSafe(turn, second.mon, second.flags)) return SwitchDecision(second.mon)
        return null
    }

    // SwitchToBestResistMon: one that resists everything, or one your contact move would faint you on.
    private fun switchToResist(turn: AiTurn, pivot: Boolean, checkContact: Boolean): SwitchDecision? {
        for (entry in switchInsInOrder(turn)) {
            val mon = entry.mon
            if ((entry.flags and SF_RESIST_ALL) != 0 ||
                (checkContact && contactKnocksYouOut(turn, mon) && predictedHitWontKO(turn, mon))
            ) return SwitchDecision(mon, pivot)
        }
        return null
    }

    // ShouldSwitchIfNaturalCureOrRegenerator.
    private fun switchToHeal(turn: AiTurn): SwitchDecision? {
        val battle = turn.battle
        val ai = battle.ai
        val foe = battle.player
        val field = battle.field
        if (ai.ability == "Natural Cure") {
            if (hazardsHurt(ai, 4, field)) return null
            // (Radical Red has frostbite, not freeze: only sleep makes it switch at any HP.)
            if (!(isAsleep(ai) || (ai.status.isNotEmpty() && ai.hp >= ai.maxHP / 2))) return null
        } else if (ai.ability == "Regenerator") {
            if (ai.hp > ai.maxHP / 2 || hazardsHurt(ai, 3, field)) return null
            val predicted = turn.predicted(foe)
            if (predicted == null || !knocksOut(turn, foe, ai, indexOf(foe, predicted), 1)) return null
            val own = turn.aiPrediction
            if (own != null && hitsFirst(turn, ai, own, foe) && knocksOut(turn, ai, foe, indexOf(ai, own), 1)) return null
        } else {
            return null
        }
        val resist = switchToResist(turn, false, false)
        if (resist != null) return resist
        val best = scoreSwitchIns(turn).best
        if (best != null && !predictedHitIsSafe(turn, best.mon, best.flags)) {
            if (!predictedHitWontKO(turn, best.mon)) return null
            if (turn.coin("healHalf", 0.5)) return null
        }
        return SwitchDecision(null)
    }

    // ShouldSwitchWhileAsleep.
    private fun switchWhileAsleep(turn: AiTurn, justIn: Boolean): SwitchDecision? {
        val battle = turn.battle
        val ai = battle.ai
        if (justIn || !isAsleep(ai) || !turn.coin("sleepHalf", 0.5)) return null
        if (stage(ai, "atk") >= 8 || stage(ai, "spa") >= 8 || stage(ai, "spe") >= 8 || stage(ai, "eva") >= 9) {
            if (turn.coin("sleepBoosted", 0.5)) return null
        }
        if (ai.ability in setOf("Shed Skin", "Early Bird") || (ai.ability == "Hydration" && "Rain" in battle.field.w) ||
            hasEffect(ai, E.SLEEP_TALK, E.SNORE, E.REST) ||
            (styleOf(turn, ai) == STYLE_STALL && isTrappedBy(battle.player, ai, battle.field))
        ) return null
        val best = scoreSwitchIns(turn).best
        if (best != null && !predictedHitIsSafe(turn, best.mon, best.flags)) return null
        return SwitchDecision(null)
    }

    // IsTakingAnnoyingSecondaryDamage: Leech Seed (a quarter of the time), or badly poisoned for more than six turns
    // (urgent: any switch will do).
    private fun switchFromLeechSeed(turn: AiTurn): SwitchDecision? {
        val battle = turn.battle
        val ai = battle.ai
        val foe = battle.player
        if (ai.ability == "Magic Guard" || canKnockOut(turn, ai, foe) || battle.flags <= AI_NEGATIVES) return null
        val urgent: Boolean
        if (ai.seeded && turn.coin("seedQuarter", 0.25) && !usefulOffenseRaised(ai) && !canKnockOut(turn, ai, foe)) {
            urgent = false
        } else if (ai.status == "tox" && ai.toxicCounter > 6 && ai.ability != "Poison Heal") {
            urgent = true
        } else {
            return null
        }
        if (hazardsHurt(ai, 4, battle.field)) return null
        val scored = scoreSwitchIns(turn)
        fun good(entry: SwitchEntry?): Boolean {
            if (entry == null) return false
            if (urgent) return true
            val flags = entry.flags
            if ((flags and SF_OUTSPEEDS) != 0 && ((flags and SF_KO_FOE) != 0 ||
                    ((flags and SF_CAN_2HKO) != 0 && (flags and SF_FAINTS) == 0)) && predictedHitIsSafe(turn, entry.mon, flags)
            ) return true
            if ((flags and (SF_RESIST_ALL or SF_WALLS_FOE)) != 0) return true
            return can2HKO(turn, ai, foe)
        }
        if (good(scored.best)) return SwitchDecision(scored.best!!.mon)
        if (good(scored.second)) return SwitchDecision(scored.second!!.mon)
        return null
    }

    // ShouldSwitchToAvoidDeath: you would KO it first, and a teammate takes the hit better.
    private fun switchToAvoidDeath(turn: AiTurn, justIn: Boolean): SwitchDecision? {
        val battle = turn.battle
        val ai = battle.ai
        val foe = battle.player
        if (justIn || battle.flags <= AI_NEGATIVES) return null
        val own = turn.aiPrediction
        val predicted = turn.predicted(foe)
        if (ai.status == "par" && ai.hp < ai.maxHP / 3 && ai.ability !in setOf("Quick Feet", "Natural Cure")) return null
        // Radical Red: Phoenix Down (revives once at half HP) stays in while its revive is unused.
        if (ai.ability == "Phoenix Down") return null
        if (ai.name.startsWith("Aegislash") && ai.ability == "Stance Change" && hasMoveNamed(ai, "King's Shield") && predicted != null &&
            (predicted.flags and FLAG_CONTACT) != 0 && hasRealPhysical(foe)
        ) return null
        if (predicted == null || (own != null && hitsFirst(turn, ai, own, foe))) return null
        if (!knocksOut(turn, foe, ai, indexOf(foe, predicted), 1) || hazardsHurt(ai, 3, battle.field)) return null
        val scored = scoreSwitchIns(turn)
        if (healthPercent(ai) <= 20 && !(scored.best != null && scored.best.score >= SWITCH_SCORE_MAX)) return null
        for (entry in switchInsInOrder(turn)) {
            val mon = entry.mon
            val flags = entry.flags
            val hit = mon.incoming.getOrNull(indexOf(foe, predicted))
            val immune = predicted.power > 0 && hit != null && hit.maxDamage == 0
            if (((flags and (SF_OUTSPEEDS or SF_WALLS_FOE or SF_RESIST_ALL)) != 0 && immune) ||
                (((flags and (SF_WALLS_FOE or SF_RESIST_ALL)) != 0 ||
                    ((flags and SF_KO_FOE) != 0 && (flags and SF_OUTSPEEDS) != 0)) && predictedHitIsSafe(turn, mon, flags)) ||
                (contactKnocksYouOut(turn, mon) && predictedHitWontKO(turn, mon))
            ) return SwitchDecision(mon)
        }
        return null
    }

    // ShouldSwitchWhenOffensiveStatsAreLow: an attacker at -3 or worse in what it attacks with.
    private fun switchFromLowStats(turn: AiTurn, justIn: Boolean): SwitchDecision? {
        val battle = turn.battle
        val ai = battle.ai
        val foe = battle.player
        val style = styleOf(turn, ai)
        if (justIn || !isDamager(style)) return null
        val atk = stage(ai, "atk")
        val spa = stage(ai, "spa")
        val acc = stage(ai, "acc")
        if (!(atk <= OFFENSIVE_STAT_MIN || spa <= OFFENSIVE_STAT_MIN || acc <= OFFENSIVE_STAT_MIN)) return null
        // It is about to trade the drops away.
        if (turn.aiPrediction?.name == "Heart Swap") return null
        var physical = false
        var special = false
        ai.moves.forEachIndexed { i, move ->
            if (move == null || !isUsable(ai, i, battle.field) || move.power == 0) return@forEachIndexed
            if (move.split == SPLIT_PHYSICAL && move.effect != E.COUNTER && move.name != "Body Press" && move.name != "Foul Play") physical = true
            else if (move.split == SPLIT_SPECIAL && move.effect != E.MIRROR_COAT) special = true
        }
        val inaccurate = acc <= OFFENSIVE_STAT_MIN && ai.moves.all { move ->
            move == null || move.split == SPLIT_STATUS || move.accuracy == 0 ||
                withStage(accuracyOf(ai, foe, move, battle.field), acc - stage(foe, "eva") + 6) < 70
        } && style != STYLE_STALL
        if (!((atk <= OFFENSIVE_STAT_MIN && physical && !special) || (spa <= OFFENSIVE_STAT_MIN && special && !physical) ||
                (atk <= OFFENSIVE_STAT_MIN && spa <= OFFENSIVE_STAT_MIN && physical && special) || inaccurate)
        ) return null
        val pivot = hasPivotMoveThatAffects(turn, ai, foe, true)
        if (canKnockOut(turn, foe, ai) && acc > OFFENSIVE_STAT_MIN) return null
        val best = scoreSwitchIns(turn).best ?: return null
        if ((best.flags and (SF_WALLS_FOE or SF_RESIST_ALL)) != 0 ||
            ((best.flags and SF_OUTSPEEDS) != 0 && predictedHitIsSafe(turn, best.mon, best.flags))
        ) return SwitchDecision(best.mon, pivot)
        return null
    }

    // OffensiveSetupMoveInMoveset (the status moves; the hit-and-boost moves never count, as in CFRU).
    private fun hasOffensiveSetupMove(side: AiSide) = hasEffect(
        side, E.ATTACK_UP, E.SPECIAL_ATTACK_UP, E.EVASION_UP, E.ATTACK_UP_2, E.SPEED_UP_2, E.SPECIAL_ATTACK_UP_2,
        E.EVASION_UP_2, E.ATK_SPATK_UP, E.ATK_ACC_UP, E.BULK_UP, E.CALM_MIND, E.DRAGON_DANCE, E.BELLY_DRUM,
    )

    // CanHealFirstToPreventKnockOut.
    private fun canHealBeforeKO(turn: AiTurn, atk: AiSide, def: AiSide): Boolean {
        val field = turn.battle.field
        for (i in 0 until 4) {
            val move = atk.moves.getOrNull(i)
            if (move == null || !isUsable(atk, i, field)) continue
            if (move.effect in setOf(E.RESTORE_HP, E.MORNING_SUN, E.ABSORB, E.DREAM_EATER) || (move.effect == E.PROTECT && turn.predicted(def) != null)) {
                val heal = if (move.effect == E.PROTECT) 0 else healAmount(atk, def, move, field)
                if (hitsFirst(turn, atk, move, def) && !canKnockOutAfterHealing(turn, def, atk, heal, false)) return true
            }
        }
        return false
    }

    // ShouldSaveSweeperForLater: a sweeper that can't KO steps aside for one that can, or for a wall.
    private fun switchToSaveSweeper(turn: AiTurn, justIn: Boolean): SwitchDecision? {
        val battle = turn.battle
        val ai = battle.ai
        val foe = battle.player
        val field = battle.field
        if (justIn || battle.flags <= AI_NEGATIVES) return null
        if (ai.status == "par" && ai.ability !in setOf("Quick Feet", "Guts")) return null
        if (!isDamager(styleOf(turn, ai))) return null
        val predicted = turn.predicted(foe)
        val fakeOutHurts = predicted != null && predicted.name == "Fake Out" && ai.ability !in setOf("Inner Focus", "Shield Dust") &&
            ai.item != "Covert Cloak" && finalDamage(turn, foe, ai, indexOf(foe, predicted), 1) >= ai.maxHP / 2
        if (canKnockOut(turn, ai, foe) && !fakeOutHurts) return null
        if (hazardsHurt(ai, 4, field)) return null
        val trappingFoe = isTrappedBy(foe, ai, field) && secondaryDamage(foe, field) > 0
        if (trappingFoe) return null
        val willDie = canKnockOut(turn, foe, ai) && !canHealBeforeKO(turn, ai, foe)
        var reason = willDie
        if (!reason && !usefulOffenseRaised(ai) && stage(ai, "eva") < 9 && !hasOffensiveSetupMove(ai)) {
            val scored = scoreSwitchIns(turn)
            if ((switchInFlags(scored.best) and SF_KO_FOE) != 0) {
                reason = true
            } else if (turn.coin("sweeperHalf", 0.5) && (!can2HKO(turn, ai, foe) || turn.coin("sweeperAnyway", 0.5)) &&
                !resistsAllMoves(turn, foe, ai)
            ) {
                val strongest = ai.moves.getOrNull(strongestMove(turn, ai, foe))
                reason = !(strongest != null && (strongest.effect == E.ABSORB || strongest.effect == E.DREAM_EATER))
            }
        }
        if (!reason) return null
        val own = turn.aiPrediction
        if (own != null && ((own.name == "Fake Out" && foe.ability !in setOf("Inner Focus", "Shield Dust")) || own.effect == E.PROTECT)) return null
        val pivot = hasPivotMoveThatAffects(turn, ai, foe, true)
        if (canKnockOut(turn, foe, ai)) {
            if (own != null && hitsFirst(turn, ai, own, foe) && own.effect == E.DESTINY_BOND) return null
            for (entry in listOf(scoreSwitchIns(turn).best, scoreSwitchIns(turn).second)) {
                if (entry == null) continue
                val flags = entry.flags
                val wanted = SF_OUTSPEEDS or SF_KO_FOE
                if (((flags and wanted) == wanted && predictedHitIsSafe(turn, entry.mon, flags)) ||
                    (contactKnocksYouOut(turn, entry.mon) && predictedHitWontKO(turn, entry.mon))
                ) return SwitchDecision(entry.mon, pivot)
            }
        }
        return switchToResist(turn, pivot, true)
    }

    // ShouldSwitch's checks for the Pokémon on the field, in order; the first that says so decides.
    private fun switchChecks(turn: AiTurn): List<() -> SwitchDecision?> {
        val battle = turn.battle
        val justIn = battle.justSwitchedIn
        fun decide(check: () -> SwitchDecision?): () -> SwitchDecision? = {
            if (isTrappedBy(battle.ai, battle.player, battle.field)) {
                null
            } else {
                val decision = check()
                if (decision != null && decision.mon == null) {
                    val best = scoreSwitchIns(turn).best
                    decision.mon = best?.mon ?: battle.party[0]
                }
                decision
            }
        }
        return listOf(
            decide { switchToAbsorb(turn, justIn) },
            decide { switchForWonderGuard(turn) },
            decide { switchFromBadMoves(turn, justIn) },
            decide { switchToHeal(turn) },
            decide { switchWhileAsleep(turn, justIn) },
            decide { switchFromLeechSeed(turn) },
            decide { switchToAvoidDeath(turn, justIn) },
            decide { switchFromLowStats(turn, justIn) },
            decide { switchToSaveSweeper(turn, justIn) },
        )
    }

    // The AI's own move as PredictMovesForBanks has it (smart flags; under 100 means it would switch).
    private fun predictAISelf(turn: AiTurn) {
        val battle = turn.battle
        val ai = battle.ai
        turn.phase = "p"
        turn.cache = HashMap()
        var index: Int? = null
        if (!(isAsleep(ai) && !hasEffect(ai, E.SLEEP_TALK, E.SNORE)) && ai.hp > 0) {
            val values = runScripts(turn, ai, battle.player, AI_SMART_FLAGS, false)
            val best = values.max()
            if (best >= 100) {
                val ties = values.indices.filter { values[it] == best && ai.moves.getOrNull(it) != null }
                index = if (ties.size == 1) ties[0] else ties[turn.rng.pick(DoubleArray(ties.size) { 1.0 / ties.size })]
            }
        }
        turn.aiPrediction = if (index == null) null else ai.moves[index]
    }

    // #endregion
    // #region the decision

    private fun runScripts(turn: AiTurn, atk: AiSide, def: AiSide, flags: Int, signed: Boolean): IntArray {
        val field = turn.battle.field
        val scores = IntArray(4) { if (isUsable(atk, it, field)) 100 else 0 }
        val previous = turn.flags
        turn.flags = flags
        for (bit in intArrayOf(AI_NEGATIVES, AI_SEMI_SMART, AI_POSITIVES)) {
            if ((flags and bit) == 0) continue
            // Semi Smart does nothing once Positives runs; on its own it is the damage bonuses only.
            if (bit == AI_SEMI_SMART && (flags and AI_POSITIVES) != 0) continue
            for (j in 0 until 4) {
                if (atk.moves.getOrNull(j) == null || scores[j] <= 0) {
                    scores[j] = 0
                    continue
                }
                var value = scores[j] and 0xFF
                value = when (bit) {
                    AI_NEGATIVES -> scoreNegatives(turn, atk, def, j, value)
                    AI_POSITIVES -> scorePositives(turn, atk, def, j, value)
                    else -> if (isDamaging(atk.moves[j])) damageMoveBonus(turn, atk, def, j, value) else value
                }
                scores[j] = if (signed && value >= 128) value - 256 else value
            }
        }
        turn.flags = previous
        return scores
    }

    // Your move, as the AI predicts it before choosing its own (always with the smart flags).
    private fun predictPlayer(turn: AiTurn) {
        val battle = turn.battle
        val player = battle.player
        val ai = battle.ai
        turn.phase = "p"
        turn.cache = HashMap()
        turn.prediction[ai.key] = null
        var index: Int? = null
        if (!(isAsleep(player) && !hasEffect(player, E.SLEEP_TALK, E.SNORE)) && player.hp > 0) {
            val values = runScripts(turn, player, ai, AI_SMART_FLAGS, false)
            val best = values.max()
            if (best < 100) {
                turn.predictedSwitch[player.key] = true
            } else {
                val ties = values.indices.filter { values[it] == best && player.moves.getOrNull(it) != null }
                index = if (ties.size == 1) ties[0] else ties[turn.rng.pick(DoubleArray(ties.size) { 1.0 / ties.size })]
            }
        }
        turn.prediction[player.key] = index
        turn.phase = "a"
        turn.cache = HashMap()
    }

    // ChooseMoveOrAction_Singles: the shares of the four slots after scoring (with the s8 quirk).
    private fun chooseShares(scores: IntArray, moves: List<AiMove?>): DoubleArray {
        var best = scores[0] and 0xFF
        var list = arrayListOf(0)
        for (i in 1 until 4) {
            if (moves.getOrNull(i) == null) continue
            val s = scores[i]
            if (best == s) {
                list.add(i)
            } else if (best < s) {
                best = s and 0xFF
                list = arrayListOf(i)
            }
        }
        val shares = DoubleArray(4)
        for (i in list) shares[i] = 1.0 / list.size
        return shares
    }

    private fun predictAIMoves(battle: AiBattle): AiPrediction {
        val party = battle.party
        val reasons = SWITCH_REASONS.size
        // Where the shares of moves used as Z-Moves go, after the switches.
        val zBase = 4 + party.size * reasons
        val outcomes = enumerateChances(20000) { rng ->
            val turn = AiTurn(rng, megaPhaseBattle(battle, false))
            val outcome = DoubleArray(zBase + 4)
            predictPlayer(turn)
            turn.battle = withZMoves(turn, turn.battle)
            if (party.isNotEmpty()) {
                predictAISelf(turn)
                turn.phase = "w"
                turn.cache = HashMap()
                turn.prediction[battle.ai.key] = turn.aiPrediction?.let { own -> turn.battle.ai.moves.indexOfFirst { it === own } }
                var decision: SwitchDecision? = null
                var reason = -1
                // Radical Red: on a quarter of its turns the AI doesn't think about switching at all.
                val checks = if (isTrappedBy(battle.ai, battle.player, battle.field) || turn.coin("noSwitchCheck", 0.25)) emptyList() else switchChecks(turn)
                var c = 0
                while (c < checks.size && decision == null) {
                    decision = checks[c]()
                    reason = c
                    c++
                }
                turn.prediction[battle.ai.key] = null
                if (decision != null && !decision.pivot) {
                    outcome[4 + party.indexOfFirst { it === decision.mon } * reasons + reason] = 1.0
                    return@enumerateChances outcome
                }
                if (decision != null) turn.goodToPivot = true
            }
            turn.phase = "a"
            turn.cache = HashMap()
            turn.battle = withZMoves(turn, megaPhaseBattle(battle, true))
            val shares = chooseShares(runScripts(turn, turn.battle.ai, turn.battle.player, battle.flags, true), battle.ai.moves)
            val zSlots = turn.battle.ai.zSlots.orEmpty()
            for (i in 0 until 4) {
                outcome[i] = shares[i]
                if (zSlots.getOrNull(i) == true) outcome[zBase + i] = shares[i]
            }
            outcome
        }
        val switches = party.mapIndexedNotNull { p, mon ->
            var share = 0.0
            val byReason = LinkedHashMap<Int, Double>()
            for (r in 0 until reasons) {
                val s = outcomes.getOrNull(4 + p * reasons + r) ?: 0.0
                if (s > 0) {
                    share += s
                    byReason[r] = s
                }
            }
            if (share > 0) AiSwitch(mon, share, byReason) else null
        }.sortedByDescending { it.share }
        return AiPrediction(
            DoubleArray(4) { outcomes.getOrNull(it) ?: 0.0 },
            DoubleArray(4) { outcomes.getOrNull(zBase + it) ?: 0.0 },
            switches,
            switches.sumOf { it.share },
        )
    }

    // Who the AI sends out next if you KO its Pokémon now: the best switch-in against yours.
    private fun predictReplacement(battle: AiBattle): List<Pair<AiSide, Double>> {
        val party = battle.party
        if (party.isEmpty()) return emptyList()
        val outcomes = enumerateChances(5000) { rng ->
            val turn = AiTurn(rng, battle)
            val outcome = DoubleArray(party.size)
            val best = scoreSwitchIns(turn).best
            val at = party.indexOfFirst { it === (best?.mon ?: party[0]) }
            outcome[at] = 1.0
            outcome
        }
        return party.mapIndexed { i, mon -> mon to (outcomes.getOrNull(i) ?: 0.0) }.filter { it.second > 0 }.sortedByDescending { it.second }
    }

    // The battle as each thinking phase sees it: everything but the move choice sees the pending Mega; the move choice
    // keeps the base form but with the Mega's damage numbers.
    private fun megaPhaseBattle(battle: AiBattle, choosing: Boolean): AiBattle {
        val mega = battle.mega ?: return battle
        if (!choosing) return battle.copy(ai = mega.ai, player = mega.player)
        val ai = battle.ai.copy(moves = battle.ai.moves.mapIndexed { i, move ->
            val megaMove = mega.ai.moves.getOrNull(i)
            if (move != null && megaMove != null) move.copy(maxDamage = megaMove.maxDamage, maxCritDamage = megaMove.maxCritDamage) else move
        })
        return battle.copy(ai = ai, player = mega.player)
    }

    // #endregion
}
