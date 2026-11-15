package com.pokedaisy.app.companion.calc

/**
 * The trainer AI's view of a fight ([AiBattle]) from the CALC tab's Pokémon, worked out by [CalcEngine] the way the
 * original's page builds it (trainer_ai.js's buildAISide, readAIParty, readZMoves and readPendingMega in
 * kvarflow/rr-calc). Singles only: the AI isn't modelled in a double battle.
 */
object CalcAi {
    /** Every trainer gets the smart flags in Radical Red (the flags stored with each trainer are never read). */
    const val FLAGS = 7

    /** Each team slot's Pokémon still standing, besides the one out; [slot] its place in the team (the game's party order). */
    class Benched(val slot: Int, val mon: CalcMon)

    /**
     * [you] against [foe] (team slot [foeSlot]), on [field] as you see it (its attacker side is yours). [bench] is the rest
     * of the trainer's team still standing; [kos] how many of its Pokémon are down. [mega] is the Mega [foe] turns into as
     * it moves this turn (at its HP, status and stat changes), when it is still in its base form. [hardcore] = Z-Moves.
     */
    fun battle(
        you: CalcMon, foe: CalcMon, foeSlot: Int, bench: List<Benched>, field: CalcField, yourMonsLeft: Int, kos: Int,
        teamSize: Int, hardcore: Boolean, battleEffect: String = "", mega: CalcMon? = null, trickRoom: Boolean = false,
        yourFocus: Boolean = false, foeFocus: Boolean = false,
    ): AiBattle {
        val pack = you.pack
        val aiField = field.swapped()
        val yourSide = sideState(field.attackerSide)
        val foeSide = sideState(field.defenderSide)
        val yours = results(you, foe, field)
        val theirs = results(foe, you, aiField)
        val p1 = attacker(you, foe, field, yours)
        val p2 = attacker(foe, you, aiField, theirs)
        val player = side(
            "p1", p1, yours, p2, p1.stats[SPE], yourMonsLeft, p1.curHP() == p1.maxHP(), yourSide, yourFocus,
        ) { critChance(p1, p2, it, yourFocus) }
        val monsLeft = maxOf(1, teamSize - kos)
        var ai = side("p2", p2, theirs, p1, p2.stats[SPE], monsLeft, p2.curHP() == p2.maxHP(), foeSide, foeFocus) {
            critChance(p2, p1, it, foeFocus)
        }
        if (hardcore) ai = ai.copy(zMoves = zMoves(pack, ai, theirs))
        val party = bench.mapNotNull { b ->
            val outR = results(b.mon, you, aiField)
            val inR = results(you, b.mon, field)
            val m = attacker(b.mon, you, aiField, outR)
            val side = side("b${b.slot}", m, outR, you, m.stats[SPE], monsLeft, true, foeSide, false, slot = b.slot) {
                critChance(m, you, it, false)
            }
            val incoming = inR.map { r ->
                val move = r?.move ?: return@map null
                val c = critChance(you, m, move, yourFocus)
                val max = r.max
                AiHit(max, if (c >= 0.5 && c < 1) maxCritRoll(r) else max, c, effectiveness(pack, move.type, side.types))
            }
            side.copy(incoming = incoming)
        }
        val firstTurn = ai.firstTurn
        val pending = mega?.let { megaMon ->
            val mOut = results(megaMon, you, aiField)
            val mIn = results(you, megaMon, field)
            val megaSide = attacker(megaMon, you, aiField, mOut)
            val yoursVs = attacker(you, megaMon, field, mIn)
            AiMega(
                side("p2", megaSide, mOut, you, megaSide.stats[SPE], monsLeft, firstTurn, foeSide, foeFocus) { critChance(megaSide, you, it, foeFocus) },
                side("p1", yoursVs, mIn, megaSide, player.speed, yourMonsLeft, player.firstTurn, yourSide, yourFocus) {
                    critChance(you, megaSide, it, yourFocus)
                },
            )
        }
        return AiBattle(
            ai, player, AiField(field.weather, field.terrain, field.isGravity), trickRoom, FLAGS, party,
            justSwitchedIn = firstTurn && (kos > 0 || foeSlot != 0), battleEffect = battleEffect, mega = pending,
        )
    }

    /**
     * A benched Mega in the base form it comes in as (the game scores it so): [mon] is the set's Mega, [preMega] the base
     * form's ability (else its species' first). Anything else is [mon] as it is.
     */
    fun benchForm(mon: CalcMon, preMega: String?): CalcMon {
        if ("-Mega" !in mon.name) return mon
        val base = mon.pack.species[mon.name.substringBefore("-Mega")] ?: return mon
        return CalcMon(
            mon.pack, base, level = mon.level, gender = mon.gender, ability = preMega ?: base.ability, abilityOn = true, item = mon.item,
            nature = mon.nature, ivs = mon.ivs.copyOf(), evs = mon.evs.copyOf(), curHP = mon.curHP(), status = mon.status, moves = mon.moves,
        )
    }

    /**
     * The Mega [mon] (the base form, as the battle has it) turns into as it moves: [set]'s species at its HP, status and
     * stat changes. Null = [set] isn't a Mega, or [mon] already is one.
     */
    fun pendingMega(mon: CalcMon, set: CalcSet): CalcMon? {
        if ("-Mega" !in set.species || "-Mega" in mon.name) return null
        val species = mon.pack.species[set.species] ?: return null
        return CalcMon(
            mon.pack, species, level = mon.level, gender = mon.gender, ability = set.ability, item = mon.item, nature = set.nature,
            ivs = set.ivs.copyOf(), evs = set.evs.copyOf(), boosts = mon.boosts.copyOf(), curHP = mon.curHP(), status = mon.status,
            toxicCounter = mon.toxicCounter, moves = set.moves,
        ).also { it.originalCurHP = mon.curHP() }
    }

    private fun sideState(s: CalcSide) = AiSideState(
        sr = s.isSR, spikes = s.spikes, reflect = s.isReflect, lightScreen = s.isLightScreen, auroraVeil = s.isAuroraVeil,
        tailwind = s.isTailwind, seeded = s.isSeeded,
    )

    /** Each of [attacker]'s four move slots used on [defender] (null where it has none). */
    private fun results(attacker: CalcMon, defender: CalcMon, field: CalcField): List<CalcResult?> = List(4) { i ->
        attacker.moves.getOrNull(i)?.let { name -> CalcEngine.calculate(attacker, defender, move(attacker, name), field) }
    }

    private fun move(mon: CalcMon, name: String) = CalcMove(mon.pack, name, ability = mon.ability, item = mon.item)

    /** [mon] as the mechanics leave it as the attacker (its final stats): from its first move, or a Splash with none. */
    private fun attacker(mon: CalcMon, other: CalcMon, field: CalcField, results: List<CalcResult?>): CalcMon =
        results.firstOrNull()?.attacker ?: CalcEngine.calculate(mon, other, CalcMove(mon.pack, "Splash"), field).attacker

    private fun effectiveness(pack: CalcPack, type: String, types: List<String>): Double =
        types.fold(1.0) { product, t -> product * pack.effectiveness(type, t) }

    private val HIGH_CRIT_MOVES = setOf(
        "Aeroblast", "Air Cutter", "Aqua Cutter", "Attack Order", "Blaze Kick", "Crabhammer", "Cross Chop", "Cross Poison", "Dire Claw",
        "Drill Run", "Esper Wing", "Ivy Cudgel", "Karate Chop", "Leaf Blade", "Night Slash", "Poison Tail", "Psycho Cut", "Razor Leaf",
        "Razor Shell", "Razor Wind", "Shadow Claw", "Sky Attack", "Slash", "Snipe Shot", "Spacial Rend", "Stone Edge", "Triple Arrows",
    )
    private val CRIT_CHANCES = doubleArrayOf(1.0 / 24, 1.0 / 8, 1.0 / 2, 1.0)

    /** The chance [move] lands a critical hit (Gen 7's stages), as the original's crit tools have it. */
    fun critChance(attacker: CalcMon, defender: CalcMon, move: CalcMove, focusEnergy: Boolean): Double {
        if (move.category == "Status") return 0.0
        if (defender.ability in setOf("Battle Armor", "Shell Armor") && attacker.ability !in setOf("Mold Breaker", "Teravolt", "Turboblaze")) return 0.0
        if (move.isCrit || (attacker.ability == "Merciless" && defender.status in setOf("psn", "tox"))) return 1.0
        var stage = 0
        if (move.originalName in HIGH_CRIT_MOVES) stage++
        if (attacker.item == "Scope Lens" || attacker.item == "Razor Claw") stage++
        if (attacker.ability == "Super Luck") stage++
        if ((attacker.name.startsWith("Farfetch’d") || attacker.name.startsWith("Sirfetch’d")) && (attacker.item == "Leek" || attacker.item == "Leek Stick")) stage += 2
        if (attacker.name == "Chansey" && attacker.item == "Lucky Punch") stage += 2
        if (focusEnergy) stage += 2
        return CRIT_CHANCES[minOf(stage, CRIT_CHANCES.size - 1)]
    }

    /** The highest critical roll of [result]. */
    private fun maxCritRoll(result: CalcResult): Int = try {
        val m = result.move
        val crit = CalcMove(m.pack, m.originalName, m.useZ, isCrit = true, hits = m.hits, timesUsed = m.timesUsed,
            timesUsedWithMetronome = m.timesUsedWithMetronome, ability = m.ability, item = m.item)
        CalcEngine.calculate(result.attacker, result.defender, crit, result.field).max
    } catch (e: RuntimeException) {
        result.max
    }

    /** The AI's data for [move]: the ROM's, or what a plain hit of the move would be. */
    private fun aiData(move: CalcMove): AiMoveData {
        val split = when (move.category) {
            "Status" -> CfruAi.SPLIT_STATUS
            "Special" -> CfruAi.SPLIT_SPECIAL
            else -> CfruAi.SPLIT_PHYSICAL
        }
        return move.pack.aiMove(move.originalName)
            ?: AiMoveData(E.HIT, 100, 0, 0, move.priority, if (move.flag("contact")) CfruAi.FLAG_CONTACT else 0, split, move.bp)
    }

    private fun side(
        key: String, mon: CalcMon, results: List<CalcResult?>, foe: CalcMon, speed: Int, monsLeft: Int, firstTurn: Boolean,
        sideState: AiSideState, focusEnergy: Boolean, slot: Int = -1, crit: (CalcMove) -> Double,
    ): AiSide {
        val moves = results.mapIndexed { i, r ->
            val move = r?.move ?: return@mapIndexed null
            val data = aiData(move)
            val c = crit(move)
            val max = r.max
            var effectiveness = effectiveness(mon.pack, move.type, foe.types)
            if (data.split != CfruAi.SPLIT_STATUS && max == 0 && data.power > 0) effectiveness = 0.0
            AiMove(
                index = i, name = move.originalName, type = move.type, bp = move.bp, hits = maxOf(1, move.hits), recoil = move.recoil,
                drain = move.data.drainRatio, sound = move.flag("sound"), bullet = move.flag("bullet"), effect = data.effect,
                accuracy = data.accuracy, chance = data.chance, target = data.target, priority = data.priority, flags = data.flags,
                split = data.split, power = data.power, maxDamage = max, maxCritDamage = if (c >= 0.5 && !move.isCrit) maxCritRoll(r) else max,
                critChance = c, effectiveness = effectiveness,
            )
        }
        return AiSide(
            key = key, name = mon.name, types = mon.types.toList(), ability = mon.ability, abilityOn = mon.abilityOn, item = mon.item,
            status = mon.status, toxicCounter = mon.toxicCounter, level = mon.level, gender = mon.gender, hp = mon.curHP(),
            maxHP = mon.maxHP(), boosts = boostMap(mon), stats = statMap(mon), speed = speed, monsLeft = monsLeft, firstTurn = firstTurn,
            sideState = sideState, saltCured = mon.isSaltCure, focusEnergy = focusEnergy, moves = moves, slot = slot,
        )
    }

    private val STAT_KEYS = listOf("hp", "atk", "def", "spa", "spd", "spe")

    private fun boostMap(mon: CalcMon): Map<String, Int> = LinkedHashMap<String, Int>().apply {
        for (i in 1 until 6) put(STAT_KEYS[i], mon.boosts[i])
    }

    private fun statMap(mon: CalcMon): Map<String, Int> = LinkedHashMap<String, Int>().apply {
        for (i in 0 until 6) put(STAT_KEYS[i], mon.stats[i])
    }

    /** Hardcore: each of the AI's moves as its Z-Move on you, or null; null = none at all. */
    private fun zMoves(pack: CalcPack, ai: AiSide, theirs: List<CalcResult?>): List<AiMove?>? {
        var any = false
        val z = ai.moves.mapIndexed { i, move ->
            val name = CfruAi.zMoveNameFor(ai.name, ai.item, move)
            val result = theirs[i]
            if (name.isEmpty() || result == null || move == null) return@mapIndexed null
            val special = CfruAi.SPECIAL_Z_DATA[name]
            any = true
            if (special != null && special[0] == E.EXTREME_EVOBOOST) {
                return@mapIndexed move.copy(
                    name = name, zBase = move.name, bp = 0, power = 0, effect = special[0], accuracy = 0, chance = 0, target = special[3],
                    priority = 0, flags = 0, split = CfruAi.SPLIT_STATUS, hits = 1, recoil = false, drain = null, maxDamage = 0,
                    maxCritDamage = 0, effectiveness = 1.0,
                )
            }
            val zMove = try {
                CalcMove(pack, move.name, useZ = true, ability = ai.ability, item = ai.item)
            } catch (e: RuntimeException) {
                return@mapIndexed null
            }
            val max = try {
                CalcEngine.calculate(result.attacker, result.defender, zMove, result.field).max
            } catch (e: RuntimeException) {
                return@mapIndexed null
            }
            move.copy(
                name = name, zBase = move.name, bp = zMove.bp, power = if (special != null && special[1] > 1) special[1] else zMove.bp,
                effect = special?.get(0) ?: E.HIT, accuracy = 0, chance = special?.get(2) ?: 0, target = special?.get(3) ?: 0,
                priority = 0, flags = 0, hits = 1, recoil = false, drain = null, sound = false, bullet = false, maxDamage = max,
                maxCritDamage = max,
            )
        }
        return if (any) z else null
    }
}
