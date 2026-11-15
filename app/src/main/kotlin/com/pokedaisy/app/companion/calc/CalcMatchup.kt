package com.pokedaisy.app.companion.calc

import kotlin.math.ceil

/** The field a fight is fought on, from its standing rule ("Doubles + Permanent rain"); [doubles] = the battle's own say, null = the rule's. */
fun fieldFor(variant: CalcVariant?, doubles: Boolean? = null): CalcField {
    val effect = variant?.battleEffect.orEmpty().lowercase()
    return CalcField(
        doubles = doubles ?: effect.contains("doubles"),
        weather = when {
            "rain" in effect -> "Rain"
            "sun" in effect -> "Sun"
            "snow" in effect -> "Snow"
            "sandstorm" in effect -> "Sand"
            else -> null
        },
        terrain = when {
            "electric terrain" in effect -> "Electric"
            "grassy terrain" in effect -> "Grassy"
            "misty terrain" in effect -> "Misty"
            "psychic terrain" in effect -> "Psychic"
            else -> null
        },
    )
}

/** One move's line on the CALC tab: what it does to the other side. */
class CalcLine(
    val move: String,
    val type: String,
    /** "Physical" / "Special" / "Status". */
    val category: String,
    /** Tenths of a percent of the target's full HP. */
    val minPermille: Int,
    val maxPermille: Int,
    /** Hits it takes to knock the target out from the HP it has: at best and for sure; 0 = never, or no damage. */
    val hitsBest: Int,
    val hitsSure: Int,
    /** Rolls of sixteen that knock it out in one hit. */
    val ohkoRolls: Int,
    val priority: Int,
) {
    val damages: Boolean get() = maxPermille > 0
}

/** One of the player's Pokémon against one of the trainer's. */
class CalcMatchup(
    val you: CalcMon,
    val foe: CalcMon,
    val yourMoves: List<CalcLine>,
    val foeMoves: List<CalcLine>,
    val yourSpeed: Int,
    val foeSpeed: Int,
) {
    /** Your best line and the foe's. */
    val best: CalcLine? get() = yourMoves.filter { it.damages }.maxByOrNull { it.maxPermille }
    val threat: CalcLine? get() = foeMoves.filter { it.damages }.maxByOrNull { it.maxPermille }

    companion object {
        private fun line(attacker: CalcMon, defender: CalcMon, name: String, field: CalcField): CalcLine {
            val pack = attacker.pack
            val move = CalcMove(pack, name, ability = attacker.ability, item = attacker.item)
            val r = CalcEngine.calculate(attacker, defender, move, field)
            val hp = defender.curHP().coerceAtLeast(1)
            val sure = if (r.min <= 0) 0 else ceil(hp.toDouble() / r.min).toInt()
            val best = if (r.max <= 0) 0 else ceil(hp.toDouble() / r.max).toInt()
            val child = r.childDamage
            val ohko = if (r.damage.size == 1) (if (r.max >= hp) 16 else 0) else r.damage.indices.count { r.damage[it] + (child?.getOrNull(it) ?: 0) >= hp }
            return CalcLine(
                r.move.name, r.move.type, r.move.category, r.minPermille, r.maxPermille, best, sure, ohko, r.move.priority,
            )
        }

        fun of(you: CalcMon, foe: CalcMon, field: CalcField = CalcField()): CalcMatchup {
            val back = field.swapped()
            // The two as the mechanics leave them (Speed after items, abilities, paralysis and the field).
            val probe = CalcEngine.calculate(you, foe, CalcMove(you.pack, "Splash"), field)
            return CalcMatchup(
                you, foe,
                you.moves.map { line(you, foe, it, field) },
                foe.moves.map { line(foe, you, it, back) },
                probe.attacker.stats[SPE], probe.defender.stats[SPE],
            )
        }
    }
}
