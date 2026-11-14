package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.pokedaisy.app.companion.calc.CalcFight
import com.pokedaisy.app.companion.calc.CalcLine
import com.pokedaisy.app.companion.calc.CalcLive
import com.pokedaisy.app.companion.calc.CalcMatchup
import com.pokedaisy.app.companion.calc.CalcMode
import com.pokedaisy.app.companion.calc.CalcMon
import com.pokedaisy.app.companion.calc.CalcPack
import com.pokedaisy.app.companion.calc.CalcPacks
import com.pokedaisy.app.companion.calc.CalcVariant
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.spriteAsset
import com.pokedaisy.app.companion.ui.theme.AppColors
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The words on this tab are English only for now: they go through tr() / tk() with the eight
// translations once the tab's wording has settled.

/** What the player has picked on the CALC tab, kept while the game runs (the mode also across launches). */
private class CalcUi {
    /** A fight stepped to by hand; null = follow the game. */
    var fight by mutableStateOf<Int?>(null)
    /** The team picked for a fight with several, by the fight's index. */
    var variants by mutableStateOf(mapOf<Int, Int>())
    var foe by mutableStateOf<Int?>(null)
    var you by mutableStateOf<Int?>(null)
    /** The battle those picks were made in (the trainer's id, 0 on the field): another one clears them. */
    var battle by mutableStateOf(0)
}

private val KoGreen = Color(0xFF389830)
private val KoLime = Color(0xFF78A028)
private val KoRed = Color(0xFFD04030)
private val KoOrange = Color(0xFFD87820)
private val KoGrey = Color(0xFF787880)

private const val PREFS = "calc"

/** The pack for the running game; null = it has none (no tab). */
fun calcPackGame(game: GameKind?): String? = if (game == GameKind.RADICAL_RED) "radical_red" else null

/**
 * The CALC tab: the fight the save is up to (or the one being fought), the trainer's team, and one of the
 * player's Pokémon against one of the trainer's - every move's damage both ways, who moves first and how
 * many hits a knock-out takes - worked out by [com.pokedaisy.app.companion.calc.CalcEngine] from the live game.
 * The party strip along the bottom shows each party Pokémon's best hit and worst hit taken against the foe picked.
 */
@Composable
internal fun CalcScreen(snapshot: SnapshotView, modifier: Modifier = Modifier) {
    val m = rememberGbaTextMetrics()
    val small = rememberGbaTextMetrics(1f)
    val context = LocalContext.current
    val game = calcPackGame(snapshot.game) ?: return
    val pack = rememberLoaded(game, cached = { CalcPacks.cached(game) }, load = { CalcPacks.load(game) { context.assets.open(it) } })
    if (pack == null) {
        Column(modifier.fillMaxSize()) { OptionTitleWindow("CALC", m, trailing = "LOADING") }
        return
    }
    val prefs = remember { context.getSharedPreferences(PREFS, 0) }
    var modeId by remember(game) { mutableStateOf(prefs.getString("mode_$game", null)?.takeIf { it in pack.modes } ?: pack.modes.keys.first()) }
    val mode = pack.modes.getValue(modeId)
    val ui = remember(game) { CalcUi() }

    // A new battle (or its end) lets go of what was picked by hand, so the tab follows the game again.
    val battleKey = if (snapshot.inBattle) (if (snapshot.trainerId != 0) snapshot.trainerId else -1) else 0
    if (ui.battle != battleKey) {
        ui.battle = battleKey
        ui.fight = null
        ui.foe = null
        ui.you = null
    }

    val live = if (snapshot.inBattle && snapshot.trainerId != 0) mode.fightFor(snapshot.trainerId) else null
    val wild = snapshot.inBattle && live == null && ui.fight == null
    val fight: CalcFight? = when {
        ui.fight != null -> mode.fights.getOrNull(ui.fight!!)
        live != null -> live.first
        wild -> null
        else -> CalcLive.nextFight(mode, snapshot.progress)
    }
    val variant: CalcVariant? = fight?.let { f ->
        when {
            live != null && live.first === f -> live.second
            else -> f.variants.getOrNull(ui.variants[f.index] ?: f.variants.indexOfFirst { v ->
                v.trainerIds.any { id -> snapshot.progress?.flag(0x500 + id) == true }
            }.coerceAtLeast(0)) ?: f.variants.first()
        }
    }
    // The fight on screen is the one being fought: its Pokémon are read live.
    val fighting = live != null && fight === live.first
    val field = remember(variant, snapshot.inBattle, snapshot.isDoubleBattle) { CalcLive.field(variant, snapshot) }

    // The trainer's side: the pack's sets (as the battle has them, while fighting), or what a wild battle shows.
    val foes: List<Pair<CalcMon, Int>> = remember(pack, variant, fighting, wild, snapshot.enemyParty, snapshot.battleOpponent) {
        when {
            variant != null && fighting -> {
                val out = snapshot.battleOpponent.firstOrNull()
                val party = snapshot.enemyParty
                val used = HashSet<String>()
                val fromParty = party.mapIndexedNotNull { i, mv ->
                    val set = CalcLive.setFor(pack, variant, mv, i)
                    // The one that's out is read from the battle itself: its stat changes, its Mega's species.
                    val liveMon = if (i == snapshot.focusEnemyActive && out != null) out else mv
                    val mon = if (set != null) CalcLive.foe(pack, set, liveMon).also { used += set.id } else CalcLive.wild(pack, liveMon)
                    mon?.let { it to liveMon.species }
                }
                fromParty.ifEmpty { variant.sets.mapNotNull { s -> CalcMon.of(pack, s)?.let { it to pack.speciesId(s.species) } } }
            }
            variant != null -> variant.sets.mapNotNull { s -> CalcMon.of(pack, s)?.let { it to pack.speciesId(s.species) } }
            wild -> snapshot.battleOpponent.mapNotNull { mv -> CalcLive.wild(pack, mv)?.let { it to mv.species } }
            else -> emptyList()
        }
    }
    val foeIndex = (ui.foe ?: snapshot.focusEnemyActive.takeIf { fighting && it in foes.indices } ?: 0).coerceIn(0, (foes.size - 1).coerceAtLeast(0))
    val foe = foes.getOrNull(foeIndex)?.first

    // The player's side: the party, the one that's out as the battle has it.
    val outSlot = snapshot.playerBattlers.firstOrNull { it in snapshot.party.indices } ?: -1
    val yours: List<CalcMon?> = remember(pack, snapshot.party, snapshot.focusPlayer, outSlot, snapshot.inBattle) {
        snapshot.party.mapIndexed { i, mv ->
            val battler = if (snapshot.inBattle && i == outSlot) snapshot.focusPlayer.firstOrNull() ?: snapshot.battlePlayer.firstOrNull() else null
            CalcLive.player(pack, mv, battler)
        }
    }
    val youIndex = (ui.you ?: outSlot.takeIf { snapshot.inBattle && it >= 0 } ?: yours.indexOfFirst { it != null && it.curHP() > 0 })
        .coerceIn(0, (yours.size - 1).coerceAtLeast(0))
    // A party against a foe is a few dozen damage calculations: worked out off the UI thread, and only again when the
    // party, the foe or the field changes (the samples' views are data classes, so an unchanged party keeps its CalcMons).
    val matchupState = remember(game) { mutableStateOf<List<CalcMatchup?>?>(null) }
    LaunchedEffect(yours, foe, field) {
        matchupState.value = withContext(Dispatchers.Default) {
            yours.map { y -> if (y != null && foe != null) CalcMatchup.of(y, foe, field) else null }
        }
    }
    val matchups: List<CalcMatchup?> = matchupState.value.orEmpty()
    val matchup = matchups.getOrNull(youIndex)

    val shownIndex = fight?.index ?: -1
    fun step(by: Int) {
        val from = if (shownIndex >= 0) shownIndex else (CalcLive.nextFight(mode, snapshot.progress)?.index ?: 0)
        ui.fight = (from + by).coerceIn(0, mode.fights.lastIndex)
        ui.foe = null
    }
    val u = m.u
    Column(modifier.fillMaxSize()) {
        val title = when {
            fight != null -> fight.name
            wild -> "WILD"
            else -> "CALC"
        }
        OptionTitleWindow(title, m) {
            if (pack.modes.size > 1) {
                OptionButton(modeId.uppercase(), small, onClick = {
                    val ids = pack.modes.keys.toList()
                    modeId = ids[(ids.indexOf(modeId) + 1) % ids.size]
                    prefs.edit().putString("mode_$game", modeId).apply()
                    ui.fight = null
                    ui.foe = null
                }, contentDescription = "Mode: $modeId")
                Spacer(Modifier.width(u * 3))
            }
            OptionButton("PREV", small, onClick = { step(-1) }, enabled = shownIndex != 0)
            Spacer(Modifier.width(u * 3))
            OptionButton("NEXT", small, onClick = { step(1) }, enabled = shownIndex < mode.fights.lastIndex)
        }
        Spacer(Modifier.height(u * 4))
        OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize().padding(horizontal = u * 6, vertical = u * 4)) {
                // The trainer's team; a fight with several teams names the one shown (a tap steps to the next).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val cell = small.lineHeight * 2.4f
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(u * 3)) {
                        foes.forEachIndexed { i, (mon, speciesId) ->
                            CalcSlot(spriteAsset("pokemon", speciesId), mon.name, i == foeIndex, mon.curHP() == 0 && fighting, small, cell) { ui.foe = i }
                        }
                    }
                    if (fight != null && variant != null) {
                        val status = when {
                            fighting -> "FIGHTING"
                            CalcLive.beaten(fight, snapshot.progress) -> "BEATEN"
                            ui.fight == null -> "NEXT UP"
                            else -> null
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            status?.let { GbaText(it, OptionColors.value, OptionColors.valueShadow, small) }
                            val where = listOfNotNull(fight.location.takeIf { it.isNotEmpty() }, if (fight.levelCap > 0) "LEVEL CAP ${fight.levelCap}" else null)
                            if (where.isNotEmpty()) GbaText(where.joinToString(" · "), OptionColors.muted, OptionColors.mutedShadow, small)
                            if (fight.variants.size > 1 && !fighting) {
                                OptionButton(variant.label.ifEmpty { "TEAM" }.uppercase(), small, onClick = {
                                    ui.variants = ui.variants + (fight.index to (fight.variants.indexOf(variant) + 1) % fight.variants.size)
                                    ui.foe = null
                                })
                            } else if (variant.battleEffect.isNotEmpty()) {
                                GbaText(variant.battleEffect.uppercase(), OptionColors.muted, OptionColors.mutedShadow, small)
                            }
                        }
                    }
                }
                if (fight != null && variant != null && fight.variants.size > 1 && !fighting && variant.battleEffect.isNotEmpty()) {
                    GbaText(variant.battleEffect.uppercase(), OptionColors.muted, OptionColors.mutedShadow, small)
                }
                Separator(m, Modifier.fillMaxWidth().padding(vertical = u * 3))
                if (matchup == null || matchup.foe.name != foe?.name) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        GbaText(
                            when {
                                foe == null -> "NOTHING TO FIGHT HERE"
                                matchupState.value == null || matchup != null -> "WORKING IT OUT"
                                else -> "NO POKéMON OF YOURS TO SEND"
                            },
                            OptionColors.muted, OptionColors.mutedShadow, m,
                        )
                    }
                } else {
                    val youFirst = matchup.yourSpeed > matchup.foeSpeed
                    val tie = matchup.yourSpeed == matchup.foeSpeed
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        CalcSide(
                            "YOU", matchup.you, spriteAsset("pokemon", snapshot.party.getOrNull(youIndex)?.species ?: 0),
                            matchup.yourMoves, matchup.yourSpeed, if (tie) null else youFirst, threat = false, m, small,
                            Modifier.weight(1f).fillMaxHeight(),
                        )
                        Separator(m, Modifier.fillMaxHeight().padding(horizontal = u * 4), vertical = true)
                        CalcSide(
                            "FOE", matchup.foe, spriteAsset("pokemon", foes[foeIndex].second),
                            matchup.foeMoves, matchup.foeSpeed, if (tie) null else !youFirst, threat = true, m, small,
                            Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
                Separator(m, Modifier.fillMaxWidth().padding(vertical = u * 3))
                // The party against the foe picked: each one's best hit over the worst it takes.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(u * 3)) {
                    snapshot.party.forEachIndexed { i, mv ->
                        PartyPick(mv, matchups.getOrNull(i), i == youIndex, small, Modifier.weight(1f)) { ui.you = i }
                    }
                    repeat(6 - snapshot.party.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** One Pokémon of a team: its icon in a framed cell, the picked one in the value colour. */
@Composable
private fun CalcSlot(asset: String?, name: String, picked: Boolean, fainted: Boolean, small: GbaTextMetrics, cell: Dp, onClick: () -> Unit) {
    val u = small.u
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(cell)
            .gbaFocusRing(focused, PixelRoundedShape(u * 3))
            .drawBehind {
                val px = u.toPx()
                val frame = if (picked) listOf(OptionColors.value to 2 * px, OptionColors.valueShadow to px) else OptionColors.titleLayers.inPx(px)
                drawLayeredBox(frame, OptionColors.titleFill, radius = 3 * px)
            }
            .soundClickable(interactionSource = source, indication = null, onClick = onClick)
            .semantics { contentDescription = name },
    ) {
        SpeciesIcon(asset, size = cell * 0.85f, modifier = Modifier.alpha(if (fainted) 0.35f else 1f))
    }
}

/** One side of the matchup: who it is, what it carries, how fast it is, and what each of its moves does to the other. */
@Composable
private fun CalcSide(
    side: String, mon: CalcMon, asset: String?, lines: List<CalcLine>, speed: Int, first: Boolean?, threat: Boolean,
    m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier,
) {
    val u = m.u
    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpeciesIcon(asset, size = m.lineHeight * 1.6f)
            Spacer(Modifier.width(u * 3))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GbaText(side, OptionColors.value, OptionColors.valueShadow, small)
                    Spacer(Modifier.width(u * 3))
                    GbaText(mon.name, OptionColors.label, OptionColors.labelShadow, m, Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(u * 3))
                    GbaText("Lv${mon.level}", OptionColors.muted, OptionColors.mutedShadow, small)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 2)) {
                    mon.types.forEach { TypePill(it, small) }
                    if (mon.status.isNotEmpty()) GbaText(mon.status.uppercase(), OptionColors.value, OptionColors.valueShadow, small)
                }
            }
        }
        Spacer(Modifier.height(u * 2))
        GbaText(
            listOf(mon.ability, mon.item.ifEmpty { "No item" }, mon.nature).filter { it.isNotEmpty() }.joinToString(" · "),
            OptionColors.muted, OptionColors.mutedShadow, small,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            GbaText("SPEED $speed", OptionColors.label, OptionColors.labelShadow, small)
            Spacer(Modifier.width(u * 3))
            when (first) {
                true -> VerdictPill("FIRST", if (threat) KoRed else KoGreen)
                false -> VerdictPill("SECOND", KoGrey)
                null -> VerdictPill("TIE", KoGrey)
            }
            Spacer(Modifier.weight(1f))
            GbaText("HP ${mon.curHP()}/${mon.maxHP()}", OptionColors.muted, OptionColors.mutedShadow, small)
        }
        Spacer(Modifier.height(u * 2))
        Separator(m, Modifier.fillMaxWidth().padding(vertical = u * 2))
        lines.forEach { line -> MoveLine(line, threat, m, small) }
        if (lines.isEmpty()) GbaText("MOVES NOT KNOWN", OptionColors.muted, OptionColors.mutedShadow, small)
    }
}

/** A move on one row: its type's colour down the left, its name, the share of the target's HP it takes, the hits to a knockout. */
@Composable
private fun MoveLine(line: CalcLine, threat: Boolean, m: GbaTextMetrics, small: GbaTextMetrics) {
    val u = m.u
    Row(Modifier.fillMaxWidth().padding(vertical = u * 1), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(u * 3).height(small.lineHeight).background(AppColors.typeColors[line.type] ?: AppColors.muted))
        Spacer(Modifier.width(u * 3))
        GbaText(line.move, OptionColors.label, OptionColors.labelShadow, small, Modifier.weight(1f))
        if (line.priority > 0) {
            VerdictPill("+${line.priority}", KoGrey)
            Spacer(Modifier.width(u * 2))
        }
        if (!line.damages) {
            GbaText(if (line.category == "Status") "STATUS" else "NO EFFECT", OptionColors.muted, OptionColors.mutedShadow, small)
        } else {
            // Tenths matter near a knockout's thresholds; past 100% whole numbers leave the move's name room.
            val range = if (line.maxPermille >= 1000) "${line.minPermille / 10}-${line.maxPermille / 10}%"
                else "${percent(line.minPermille)}-${percent(line.maxPermille)}%"
            GbaText(range, OptionColors.value, OptionColors.valueShadow, small)
            koLabel(line)?.let { (text, tier) ->
                Spacer(Modifier.width(u * 2))
                VerdictPill(text, when (tier) {
                    0 -> if (threat) KoRed else KoGreen
                    1 -> if (threat) KoOrange else KoLime
                    else -> KoGrey
                })
            }
        }
    }
}

private fun percent(permille: Int): String = "${permille / 10}.${permille % 10}"

/** "OHKO", "OHKO 37%", "2HKO", "3-4HKO"; with how good it is: 0 a sure one-hit, 1 one or two hits, 2 more. */
private fun koLabel(line: CalcLine): Pair<String, Int>? = when {
    line.hitsBest == 0 -> null
    line.hitsSure == 1 -> "OHKO" to 0
    line.hitsBest == 1 -> "OHKO ${line.ohkoRolls * 100 / 16}%" to 1
    line.hitsBest > 9 -> null
    line.hitsBest == line.hitsSure || line.hitsSure == 0 -> "${line.hitsBest}HKO" to (if (line.hitsBest == 2) 1 else 2)
    else -> "${line.hitsBest}-${line.hitsSure.coerceAtMost(99)}HKO" to (if (line.hitsBest == 2) 1 else 2)
}

/** A type in its own colour. */
@Composable
private fun TypePill(type: String, small: GbaTextMetrics) {
    val u = small.u
    Box(
        Modifier.clip(PixelRoundedShape(u * 2)).background(AppColors.typeColors[type] ?: AppColors.muted)
            .padding(horizontal = u * 3, vertical = u * 1),
    ) { GbaText(type.uppercase(), Color.White, Color(0x66000000), small) }
}

/** A party Pokémon under the matchup: its icon over its best hit on the foe picked (green) and the worst it takes (red). */
@Composable
private fun PartyPick(mv: MonView, matchup: CalcMatchup?, picked: Boolean, small: GbaTextMetrics, modifier: Modifier, onClick: () -> Unit) {
    val u = small.u
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val tiny = rememberGbaTextMetrics(1f, wholePixels = false)
    Row(
        modifier
            .gbaFocusRing(focused, PixelRoundedShape(u * 3))
            .drawBehind {
                val px = u.toPx()
                val frame = if (picked) listOf(OptionColors.value to 2 * px, OptionColors.valueShadow to px) else OptionColors.titleLayers.inPx(px)
                drawLayeredBox(frame, OptionColors.titleFill, radius = 3 * px)
            }
            .soundClickable(interactionSource = source, indication = null, onClick = onClick)
            .semantics { contentDescription = mv.shownName }
            .padding(horizontal = u * 2, vertical = u * 1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeciesIcon(mv.iconAsset, size = small.lineHeight * 1.4f, modifier = Modifier.alpha(if (mv.hp == 0 && !mv.isEgg) 0.35f else 1f))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            GbaText(matchup?.best?.let { "${it.maxPermille / 10}%" } ?: "-", KoGreen, Color(0x33000000), tiny)
            GbaText(matchup?.threat?.let { "${it.maxPermille / 10}%" } ?: "-", KoRed, Color(0x33000000), tiny)
        }
    }
}
