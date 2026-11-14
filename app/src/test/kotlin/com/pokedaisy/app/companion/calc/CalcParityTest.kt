package com.pokedaisy.app.companion.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [CalcEngine] gives what the calculator it was ported from gives, on every case in `calc/vectors.tsv.gz`. */
class CalcParityTest {
    private val pack = File("src/main/assets/calc/radical_red.pack.gz").inputStream().use { CalcPack.read(it) }

    @Test
    fun engineMatchesTheOriginal() {
        val report = javaClass.getResourceAsStream("/calc/vectors.tsv.gz")!!.use { CalcVectors.replay(pack, it) }
        assertTrue("no cases read", report.cases > 1000)
        assertEquals(report.failures.filter { it.isNotEmpty() }.joinToString("\n"), 0, report.failures.size)
    }

    @Test
    fun packHasBothModes() {
        for (mode in listOf("normal", "hardcore")) {
            val fights = pack.modes.getValue(mode).fights
            assertTrue("$mode has ${fights.size} fights", fights.size > 300)
            assertTrue(fights.all { f -> f.variants.isNotEmpty() && f.variants.all { it.sets.isNotEmpty() } })
        }
        // Brock, by the ROM's trainer id.
        assertEquals("Brock", pack.modes.getValue("normal").fightFor(414)?.first?.name)
        assertEquals("Charizard", pack.speciesName(6))
    }
}
