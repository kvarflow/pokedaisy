---
title: Fight planner
summary: The CALC tab works out the damage both sides deal in Radical Red, for the fight you're up to or the one you're in, from your game as it is right now.
section: companion
order: 11
keywords: [calc, calculator, damage, damage calculator, fight planner, planner, ohko, 2hko, ko, knock out, rolls, speed, outspeed, level cap, trainer, boss, gym leader, rival, hardcore, normal, nuzlocke, radical red, ai, trainer ai, odds, chance, predict, prediction, switch, switch in, next in, z-move, mega]
---

The `CALC` tab answers the question you'd otherwise open a damage calculator for: can my Pokémon take this fight? It knows every trainer's team in the order you meet them, reads your party straight from the game, and works out what each move does to the other side.

It's there in Radical Red. Open it from `MORE`, or put it on the tab bar in SETTINGS, `TAB BAR`.

## The fight

The title shows the trainer, where you meet them and the level cap you fight them under. Out of battle the tab opens on the next trainer you haven't beaten, going by your save. `PREV` and `NEXT` step through the whole game in order, so you can plan a few fights ahead. Leaving the tab and coming back, or starting a battle, takes it back to where your game is.

In a trainer battle the tab follows the battle on its own: the trainer you're fighting, their Pokémon with the HP and stat changes they have right now, and your Pokémon that's out.

A few fights have more than one team (the rival's depends on your starter). The button under the status says which team is shown, and a tap steps to the next one. A fight with a standing rule, like permanent rain or a double battle, shows the rule there too, and the numbers take it into account.

`NORMAL` or `HARDCORE` at the top picks the game's mode. The tab remembers it for this game.

## The matchup

Along the top are the trainer's Pokémon; tap one to plan against it. Along the bottom is your party: each slot shows the most damage that Pokémon deals to the one you picked (green) over the most it takes back (red), both as a share of full HP. Tap one to see it in full.

The middle shows the two side by side. Each side has the Pokémon's types, HP, ability, item and nature, its Speed and whether it moves `FIRST` or `SECOND`. Then every move, with:

- The damage as a range of the other side's full HP, like `42.1 - 49.8%`.
- How many hits a knockout takes from the HP the target has now: `OHKO` for a sure one, `OHKO 37%` when only some damage rolls do it, `2HKO`, `3-4HKO` and so on. Green marks a good hit for you; on the foe's side, red marks a threat to you.
- `+1` beside a move that goes first.

Status moves say `STATUS`, and a move that can't touch the target says `NO EFFECT`.

## What the trainer will do

Radical Red's trainers don't pick moves at random: each one scores its options the way the game's battle AI does, and the tab works that out too. On the foe's side, the number left of each move is the chance the trainer uses it this turn. The likeliest is in blue, and a move it never picks shows `-`. A trainer may switch out instead: each switch is a row of its own, `Switch:` and the Pokémon it brings in, with the reason, like `to avoid being knocked out`. The foe's options are listed likeliest first, so what it will do is always at the top.

On the trainer's team, `NEXT` marks the Pokémon it sends out if you knock out the one you picked this turn, with each one's chance when it could be more than one.

In `HARDCORE`, a move with a `Z` after its chance is one the trainer may turn into its Z-Move. A trainer's Pokémon that will Mega Evolve is counted as the AI sees it: it decides as the Mega, but moves as its base form on the turn it changes.

The chances count every way the AI's own dice can fall (its guess at your move, a lucky critical hit, ties between moves), so they're exact for what the tab knows. The tab can't see everything the game tracks during a battle, like which moves have already been used, so treat the numbers as a strong guide. They show for a trainer's single battle only, not a double battle or a wild one.

## Where the numbers come from

Your Pokémon are read from your game: their stats, IVs, EVs, nature, item and moves. The trainers' teams are the game's own, with the spreads, abilities and items their trainers really use. The damage follows Radical Red's battle rules, including the changes it made to the main games.

The numbers assume no critical hits, and no field effects beyond what the fight itself sets. In a battle, your Pokémon's and the foe's stat changes, HP and status are counted as they are.
