# Scp914 — the Clockworks for Paper

[![Reviewed by PatchPilots](https://img.shields.io/badge/Reviewed%20by-PatchPilots-8A2BE2)](https://github.com/alavesa/patchpilots)

SCP-914, the refinement machine: drop items into the intake, the gears grind, and the
result clatters out of the output booth according to the dial — **Rough, Coarse, 1:1,
Fine or Very Fine**. Every recipe is configured through an **in-game UI** with
automatic paging. Part of the SCP facility family
([lab-datapack](https://github.com/alavesa/lab-datapack),
[scp-mobs-plugin](https://github.com/alavesa/scp-mobs-plugin),
[cars-plugin](https://github.com/alavesa/cars-plugin)).

## Install

1. `Scp914-x.y.z.jar` → server `plugins/`. Paper 1.21.4+, Java 21.
2. The combined **scp_and_chemistry.zip** resource pack includes the machine and dial
   models.

## Using it

- `/scp914 place` — assembles the machine facing you: body model, separate dial, and
  a box of **barrier blocks** for collision (air only — the map is never overwritten,
  and `/scp914 remove` clears exactly what was placed).
- **Drop items at the intake booth** (left side). The machine notices them, swallows
  them with a clank, grinds for ~4 seconds, and the results pop out of the output
  chute (right side). Items with no recipe pass through unchanged.
- **Click the dial knob** to cycle Rough → Coarse → 1:1 → Fine → Very Fine. The knob
  visibly turns; the setting is per machine.

## Recipes — in-game UI

```
/scp914 recipes rough|coarse|1:1|fine|veryfine
```

A double-chest editor per setting: **left slot in, right slot out**, three pairs per
row, 15 per page. The next-page arrow always offers a fresh page when one fills up;
pages save on close or page-turn (`recipes.yml`). Matching is by item type +
custom_model_data strings, so lab elements, compounds and other custom items refine
like anything else. Output counts multiply by input counts.

## Model tuning (per machine, live)

```
/scp914 set scale <v>              /scp914 set offset <x> <y> <z>
/scp914 set dial-scale <v>         /scp914 set dial-offset <x> <y> <z>
/scp914 set intake <x> <y> <z>     /scp914 set output <x> <y> <z>
/scp914 barriers <w> <h> <d>
```

Offsets are machine-local blocks (x = right, z = forward) and apply to the nearest
machine within 12 blocks. Defaults for new machines live in `config.yml`.

## Why the dial is a separate model

Deliberate: the body is **one** model hook (`scp914_body` on the smithing table item)
so it can carry rich Blockbench animations, while the dial (`scp914_dial` on the
comparator item) is a tiny model of its own with its own interaction box. Five dial
positions would otherwise mean five copies of the whole machine model. The plugin
turns the dial knob by rotating the dial display — your body model never needs to
know the setting exists.

## Notes

- The recipe UI holds real item stacks (staff tool, `scp914.admin` only) — configure
  in creative.
- Intake ignores items for their first 2 seconds on the ground, so you can drop a
  stack at your feet near the booth without feeding yourself to the machine.
