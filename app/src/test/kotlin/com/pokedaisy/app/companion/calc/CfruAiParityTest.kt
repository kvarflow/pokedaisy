package com.pokedaisy.app.companion.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [CfruAi] answers what the trainer AI it was ported from answers, on every battle in `calc/ai_vectors.jsonl.gz`
 * (scripts/gen_ai_vectors.js): each battle as the original's page built it, and again as [CalcAi] builds it from
 * the same Pokémon and field over [CalcEngine].
 */
class CfruAiParityTest {
    private val pack = File("src/main/assets/calc/radical_red.pack.gz").inputStream().use { CalcPack.read(it) }

    @Test
    fun aiMatchesTheOriginal() {
        val report = javaClass.getResourceAsStream("/calc/ai_vectors.jsonl.gz")!!.use { AiVectors.replay(pack, it) }
        assertTrue("no battles read", report.cases > 1000)
        assertEquals(report.failures.filter { it.isNotEmpty() }.joinToString("\n"), 0, report.failures.size)
    }
}
