package com.pokedaisy.app.companion.calc

import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Replays the parity cases scripts/gen_calc_vectors.js wrote from the original calculator through
 * [CalcEngine]. Plain Kotlin, no test framework: `CalcParityTest` runs it, and so does ui-preview's
 * `calcCheck` where the app's own tests can't be built.
 */
object CalcVectors {
    class Report(val cases: Int, val failures: List<String>)

    private fun ints(cell: String): IntArray = cell.split(',').map { it.toInt() }.toIntArray()

    private fun mon(pack: CalcPack, cell: String): CalcMon {
        val c = cell.split('|')
        return CalcMon(
            pack, pack.species.getValue(c[0]), level = c[1].toInt(), gender = c[2], ability = c[3], abilityOn = c[4] == "1",
            item = c[5], nature = c[6], ivs = ints(c[7]), evs = ints(c[8]), boosts = ints(c[9]), curHP = c[10].toInt(),
            status = c[11], alliesFainted = c[12].toInt(), boostedStat = c[13].ifEmpty { null },
        )
    }

    private fun side(cell: String): CalcSide {
        val c = cell.split('|')
        val flags = c[1].split(',').toSet()
        return CalcSide(
            spikes = c[0].toInt(), isSR = "isSR" in flags, isReflect = "isReflect" in flags,
            isLightScreen = "isLightScreen" in flags, isAuroraVeil = "isAuroraVeil" in flags,
            isProtected = "isProtected" in flags, isForesight = "isForesight" in flags, isTailwind = "isTailwind" in flags,
            isHelpingHand = "isHelpingHand" in flags, isFlowerGift = "isFlowerGift" in flags,
            isFriendGuard = "isFriendGuard" in flags, isBattery = "isBattery" in flags, isPowerSpot = "isPowerSpot" in flags,
            isSwitchingOut = c[2] == "1",
        )
    }

    fun replay(pack: CalcPack, vectors: InputStream, maxFailures: Int = 25): Report {
        val failures = ArrayList<String>()
        var cases = 0
        GZIPInputStream(vectors).bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isEmpty()) continue
                cases++
                val c = line.split('\t')
                val got = try {
                    val attacker = mon(pack, c[0])
                    val defender = mon(pack, c[1])
                    val m = c[2].split('|')
                    val move = CalcMove(
                        pack, m[0], useZ = m[1] == "1", isCrit = m[2] == "1", hits = m[3].toInt(), timesUsed = m[4].toInt(),
                        timesUsedWithMetronome = m[5].toInt(), ability = attacker.ability.takeIf { c[0].split('|')[3].isNotEmpty() }.orEmpty(),
                        item = attacker.item,
                    )
                    val f = c[3].split('|')
                    val flags = f[3].split(',').toSet()
                    val field = CalcField(
                        doubles = f[0] == "1", weather = f[1].ifEmpty { null }, terrain = f[2].ifEmpty { null },
                        isMagicRoom = "isMagicRoom" in flags, isWonderRoom = "isWonderRoom" in flags, isGravity = "isGravity" in flags,
                        isAuraBreak = "isAuraBreak" in flags, isFairyAura = "isFairyAura" in flags, isDarkAura = "isDarkAura" in flags,
                        isBeadsOfRuin = "isBeadsOfRuin" in flags, isSwordOfRuin = "isSwordOfRuin" in flags,
                        isTabletsOfRuin = "isTabletsOfRuin" in flags, isVesselOfRuin = "isVesselOfRuin" in flags,
                        attackerSide = side(c[4]), defenderSide = side(c[5]),
                    )
                    val r = CalcEngine.calculate(attacker, defender, move, field)
                    listOf(
                        r.damage.joinToString(","), r.childDamage?.joinToString(",") ?: "-",
                        r.attacker.stats[SPE].toString(), r.defender.stats[SPE].toString(),
                    )
                } catch (e: Exception) {
                    listOf("threw $e")
                }
                val want = c.subList(6, 10)
                if (got != want && failures.size < maxFailures) failures += "case $cases: want $want, got $got\n    $line"
                else if (got != want) failures += ""
            }
        }
        return Report(cases, failures)
    }
}
