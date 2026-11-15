package com.pokedaisy.app.companion.calc

import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * Replays the trainer-AI parity cases scripts/gen_ai_vectors.js wrote from the original AI (kvarflow/rr-calc's
 * trainer_ai.js) through [CfruAi]: each line's `battle` is read into an [AiBattle] and the AI's answers compared with
 * the original's `result`. Plain Kotlin, no test framework (a small JSON reader of its own), so it also runs where
 * the app's tests can't be built.
 */
object AiVectors {
    class Report(val cases: Int, val failures: List<String>)

    fun replay(pack: CalcPack, vectors: InputStream, maxFailures: Int = 25): Report {
        val ai = CfruAi(pack)
        val failures = ArrayList<String>()
        var cases = 0
        GZIPInputStream(vectors).bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isEmpty()) continue
                cases++
                val case = Json(line).value() as Map<*, *>
                val problem = try {
                    val battle = battle(case["battle"] as Map<*, *>)
                    compare(ai.predict(battle), ai.replacement(battle), case["result"] as Map<*, *>)
                        ?: fromSpec(pack, case["spec"] as Map<*, *>).let { built ->
                            // The same battle built by the app's own code (CalcAi over the engine) from the Pokémon and field.
                            compare(ai.predict(built), ai.replacement(built), case["result"] as Map<*, *>)?.let { "built by CalcAi: $it" }
                        }
                } catch (e: Exception) {
                    "threw ${e.javaClass.simpleName}: ${e.message} at ${e.stackTrace.take(4).joinToString(" < ")}"
                }
                if (problem != null) {
                    val spec = case["spec"] as Map<*, *>
                    val you = (spec["you"] as Map<*, *>)["set"] as Map<*, *>
                    val foe = (spec["ai"] as Map<*, *>)["set"] as Map<*, *>
                    if (failures.size < maxFailures) failures.add("case $cases (${foe["species"]} vs ${you["species"]}, ${spec["mode"]}): $problem")
                    else if (failures.size == maxFailures) failures.add("")
                }
            }
        }
        return Report(cases, failures)
    }

    private fun near(a: Double, b: Double) = abs(a - b) < 1e-9

    private fun compare(got: AiPrediction, next: List<Pair<AiSide, Double>>, want: Map<*, *>): String? {
        val shares = doubles(want["shares"])
        val zShares = doubles(want["zShares"])
        if ((0 until 4).any { !near(got.shares[it], shares[it]) }) return "shares ${got.shares.toList()} want ${shares.toList()}" +
            " (z ${got.zShares.toList()} want ${zShares.toList()}; switches ${got.switches.map { it.mon.slot to it.reasons }} want ${want["switches"]})"
        if ((0 until 4).any { !near(got.zShares[it], zShares[it]) }) return "zShares ${got.zShares.toList()} want ${zShares.toList()}"
        if (!near(got.switchShare, num(want["switchShare"]))) return "switchShare ${got.switchShare} want ${want["switchShare"]}"
        val switches = (want["switches"] as List<*>).map { it as Map<*, *> }
        val gotSwitches = got.switches.joinToString { s -> "${s.mon.slot}:${s.share}:${s.reasons}" }
        val wantSwitches = switches.joinToString { s -> "${int(s["slot"])}:${s["share"]}:${s["reasons"]}" }
        if (switches.size != got.switches.size || switches.indices.any { i ->
                val s = switches[i]
                val g = got.switches[i]
                val reasons = (s["reasons"] as Map<*, *>).entries.associate { (k, v) -> (k as String).toInt() to num(v) }
                int(s["slot"]) != g.mon.slot || !near(num(s["share"]), g.share) || reasons.keys != g.reasons.keys ||
                    reasons.any { (r, v) -> !near(v, g.reasons.getValue(r)) }
            }
        ) return "switches [$gotSwitches] want [$wantSwitches]"
        val replacement = (want["replacement"] as List<*>).map { it as Map<*, *> }
        if (replacement.size != next.size || replacement.indices.any { i ->
                int(replacement[i]["slot"]) != next[i].first.slot || !near(num(replacement[i]["share"]), next[i].second)
            }
        ) return "replacement ${next.map { it.first.slot to it.second }} want ${replacement.map { int(it["slot"]) to num(it["share"]) }}"
        return null
    }

    // #region the battle

    fun battle(b: Map<*, *>): AiBattle {
        val field = b["field"] as Map<*, *>
        val mega = b["mega"] as Map<*, *>?
        return AiBattle(
            ai = side(b["ai"] as Map<*, *>),
            player = side(b["player"] as Map<*, *>),
            field = AiField(field["weather"] as String?, field["terrain"] as String?, field["isGravity"] == true),
            trickRoom = b["trickRoom"] == true,
            flags = int(b["flags"]),
            party = (b["party"] as List<*>).map { side(it as Map<*, *>) },
            justSwitchedIn = b["justSwitchedIn"] == true,
            battleEffect = b["battleEffect"] as String? ?: "",
            mega = mega?.let { AiMega(side(it["ai"] as Map<*, *>), side(it["player"] as Map<*, *>)) },
        )
    }

    private fun side(s: Map<*, *>): AiSide {
        val st = s["sideState"] as Map<*, *>
        return AiSide(
            key = s["key"] as String,
            name = s["name"] as String,
            types = (s["types"] as List<*>).map { it as String },
            ability = s["ability"] as String? ?: "",
            abilityOn = s["abilityOn"] == true,
            item = s["item"] as String? ?: "",
            status = s["status"] as String? ?: "",
            toxicCounter = int(s["toxicCounter"]),
            level = int(s["level"]),
            gender = s["gender"] as String? ?: "",
            hp = int(s["hp"]),
            maxHP = int(s["maxHP"]),
            boosts = ints(s["boosts"]),
            stats = ints(s["stats"]),
            speed = int(s["speed"]),
            monsLeft = int(s["monsLeft"]),
            firstTurn = s["firstTurn"] == true,
            sideState = AiSideState(
                sr = st["sr"] == true, spikes = int(st["spikes"]), toxicSpikes = int(st["toxicSpikes"]), stickyWeb = st["stickyWeb"] == true,
                reflect = st["reflect"] == true, lightScreen = st["lightScreen"] == true, auroraVeil = st["auroraVeil"] == true,
                tailwind = st["tailwind"] == true, seeded = st["seeded"] == true,
            ),
            saltCured = s["saltCured"] == true,
            focusEnergy = s["focusEnergy"] == true,
            moves = (s["moves"] as List<*>).map { m -> (m as Map<*, *>?)?.let(::move) },
            zMoves = (s["zMoves"] as List<*>?)?.map { m -> (m as Map<*, *>?)?.let(::move) },
            slot = if (s["slot"] == null) -1 else int(s["slot"]),
            incoming = (s["incoming"] as List<*>?)?.map { h ->
                (h as Map<*, *>?)?.let { AiHit(int(it["maxDamage"]), int(it["maxCritDamage"]), num(it["critChance"]), num(it["effectiveness"])) }
            } ?: emptyList(),
        )
    }

    private fun move(m: Map<*, *>) = AiMove(
        index = int(m["index"]),
        name = m["name"] as String,
        type = m["type"] as String,
        bp = int(m["bp"]),
        hits = if (m["hits"] == null) 1 else int(m["hits"]),
        recoil = m["recoil"] != null && m["recoil"] != false,
        drain = (m["drain"] as List<*>?)?.map { int(it) }?.toIntArray(),
        sound = m["sound"] == true,
        bullet = m["bullet"] == true,
        effect = int(m["effect"]),
        accuracy = int(m["accuracy"]),
        chance = int(m["chance"]),
        target = int(m["target"]),
        priority = int(m["priority"]),
        flags = int(m["flags"]),
        split = int(m["split"]),
        power = int(m["power"]),
        maxDamage = int(m["maxDamage"]),
        maxCritDamage = int(m["maxCritDamage"]),
        critChance = if (m["critChance"] == null) 0.0 else num(m["critChance"]),
        effectiveness = if (m["effectiveness"] == null) 1.0 else num(m["effectiveness"]),
        zBase = m["zBase"] as String?,
    )

    private fun num(v: Any?): Double = (v as Number).toDouble()
    private fun int(v: Any?): Int = if (v == null) 0 else (v as Number).toInt()
    private fun doubles(v: Any?): DoubleArray = (v as List<*>).map { num(it) }.toDoubleArray()
    private fun ints(v: Any?): Map<String, Int> = LinkedHashMap<String, Int>().apply {
        (v as Map<*, *>).forEach { (k, n) -> put(k as String, int(n)) }
    }

    // #endregion
    // #region the battle from its Pokémon (CalcAi)

    private val BOOST_KEYS = listOf("hp", "atk", "def", "spa", "spd", "spe")

    private fun calcSide(s: Map<*, *>) = CalcSide(
        spikes = int(s["spikes"]), isSR = s["sr"] == true, isReflect = s["reflect"] == true, isLightScreen = s["lightScreen"] == true,
        isAuroraVeil = s["auroraVeil"] == true, isTailwind = s["tailwind"] == true, isSeeded = s["seeded"] == true,
    )

    /** A set's Pokémon in [state] (HP, status, stat stages), as the vectors' makeMon makes one; [species] / [ability] = a base form's. */
    private fun mon(pack: CalcPack, set: CalcSet, state: Map<*, *>?, species: String? = null, ability: String? = null): CalcMon {
        val boosts = IntArray(6)
        (state?.get("boosts") as Map<*, *>?)?.forEach { (k, v) -> BOOST_KEYS.indexOf(k).takeIf { it > 0 }?.let { boosts[it] = int(v) } }
        return CalcMon(
            pack, pack.species.getValue(species ?: set.species), level = set.level, ability = ability ?: set.ability,
            abilityOn = ability != null, item = set.item, nature = set.nature, ivs = set.ivs.copyOf(), evs = set.evs.copyOf(),
            boosts = boosts, curHP = int(state?.get("curHP")), status = state?.get("status") as String? ?: "",
            toxicCounter = int(state?.get("toxicCounter")), moves = set.moves,
        )
    }

    private fun fromSpec(pack: CalcPack, spec: Map<*, *>): AiBattle {
        val mode = pack.modes.getValue(spec["mode"] as String)
        val fight = mode.fights[int(spec["fight"])]
        val aiSlot = int(spec["aiSlot"])
        val you = spec["you"] as Map<*, *>
        val foe = spec["ai"] as Map<*, *>
        val youSet = you["set"] as Map<*, *>
        val foeSet = foe["set"] as Map<*, *>
        val foeId = "${foeSet["species"]} (${foeSet["name"]})"
        val variant = fight.variants.first { v ->
            v.label == spec["label"] && v.battleEffect == spec["battleEffect"] && v.sets.getOrNull(aiSlot)?.id == foeId
        }
        val youId = "${youSet["species"]} (${youSet["name"]})"
        val yourSet = mode.fights.asSequence().flatMap { it.variants }.flatMap { it.sets }.first { it.id == youId }
        val aiSet = variant.sets[aiSlot]
        val pending = spec["megaPending"] as Map<*, *>?
        val p1 = mon(pack, yourSet, you["state"] as Map<*, *>)
        val p2 = mon(pack, aiSet, foe["state"] as Map<*, *>, pending?.get("species") as String?, pending?.get("ability") as String?)
        val kos = (spec["kos"] as List<*>).map { int(it) }
        val bench = variant.sets.mapIndexedNotNull { slot, set ->
            if (slot == aiSlot || slot in kos || set.species !in pack.species) return@mapIndexedNotNull null
            if ("-Mega" in set.species) {
                val base = set.species.substringBefore("-Mega")
                val baseSpecies = pack.species[base] ?: return@mapIndexedNotNull null
                CalcAi.Benched(slot, mon(pack, set, null, base, variant.preMega[slot] ?: baseSpecies.ability))
            } else {
                CalcAi.Benched(slot, mon(pack, set, null))
            }
        }
        val f = spec["field"] as Map<*, *>
        val field = CalcField(
            weather = (f["weather"] as String?)?.ifEmpty { null }, terrain = (f["terrain"] as String?)?.ifEmpty { null },
            isGravity = f["gravity"] == true, attackerSide = calcSide(spec["youSide"] as Map<*, *>),
            defenderSide = calcSide(spec["aiSide"] as Map<*, *>),
        )
        val focus = spec["focus"] as Map<*, *>
        val mega = if (pending != null) mon(pack, aiSet, foe["state"] as Map<*, *>).also { it.originalCurHP = p2.curHP() } else null
        return CalcAi.battle(
            p1, p2, aiSlot, bench, field, int(spec["yourMonsLeft"]), kos.size, variant.sets.size, spec["mode"] == "hardcore",
            spec["battleEffect"] as String? ?: "", mega, trickRoom = f["trickRoom"] == true, yourFocus = focus["p1"] == true,
            foeFocus = focus["p2"] == true,
        )
    }

    // #endregion

    /** Just enough JSON for the vectors: objects keep their order, numbers are doubles. */
    private class Json(private val s: String) {
        private var i = 0

        fun value(): Any? {
            skip()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("bad JSON at $i: $c")
            }
        }

        private fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun word(w: String, v: Any?): Any? {
            check(s.startsWith(w, i)) { "bad JSON at $i" }
            i += w.length
            return v
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }

        private fun str(): String {
            val out = StringBuilder()
            i++
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'u' -> {
                                out.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            skip()
            if (s[i] == '}') return out.also { i++ }
            while (true) {
                skip()
                val key = str()
                skip()
                check(s[i++] == ':')
                out[key] = value()
                skip()
                if (s[i++] == '}') return out
            }
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            skip()
            if (s[i] == ']') return out.also { i++ }
            while (true) {
                out.add(value())
                skip()
                if (s[i++] == ']') return out
            }
        }
    }
}
