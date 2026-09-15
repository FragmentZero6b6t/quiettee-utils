# Quiettee Utils

Good evening, reader. Quiettee here. You may know me from 6b6t, the books and puzzles I have created (The Fragment Series, The Convergence Chronicles and as of late, The Love Story.) I will be departing 6b6t on the sixteenth of September two thousand and twenty six. As a gift to the community at large I have decided to open source all my modules upon completion of the puzzle being solved. I apologise in advance to all players this may cause frustration to. This is a **meteor addon**, please follow the instructions below to download as such.

## Modules

| Module | What it does |
| --- | --- |
| **MaceSmash** | Allows for mace smash attacks to happen while hovering, largely bypassing the anti-mace plugin 6b6t has in place. |
| **Lance**  | This is rather one of my favourites. Simply put, a lance variation of MaceSmash. Ships with a black box log under `.minecraft/lance-debug/`. |
| **Fusillade** | Crossbow volley. Reloads every crossbow in the hotbar, then fires all charged ones on a key or right-click, as a burst or on a cadence, with optional aim. This is used for more a flare if anything. |
| **AirMiner** | Mines at full ground speed while airborne and makes the server agree, by claiming the ground flag around each block action. Built for cutting out of cobwebs if one is caught within the use of MaceSmash or Lance. |
| **Float** | Pins you mid-air with the elytra closed by holding the ground flag on outgoing move packets. |
| **GliderWalk** | Walk around on the ground in the full elytra flight pose. Ground skating at elytra speed, hidden nametag, spin and bob show-off options. Yet again another flare module. |
| **ElytraFollow** | Follows a player on the elytra in formation: behind, beside, or shadowing their exact position. |
| **ElytraOrbit** | Circles a player on the elytra, flat or as a loop, with an optional helix and obstacle avoidance. |
| **ElytraDive** | Perches above a player and dives on them, pulling up before the ground, repeating for as long as you like. |
| **BoatPhase** | Boat flight with block phasing, passenger carrying, and a 30-block quick dive. WASD steers, jump rises, sprint descends, and sneak dismounts. Server corrections are accepted and recorded. Quite the peculiar module I will admit. |
| **BoatShot** | Camera-independent target locking, vertical bow bursts, server hit feedback and optional return to height. Prefers players; larger bursts are experimental. Also repairs duplicate-event horizontal slowdown in BoatPhase. |
| **Flicker** | Animates your own skin on everyone else's screen by cycling skin layers, main arm and held item. Strobe, Peel, Chase, Static and Bare modes. This can be seen within tab as your head is animated to all to see. |
| **HighContrast** | This module is purely for myself, though I might as well open-source it for the very few among you all. I have lost a large majority of my sight and thus this module was developed to help with that. |

## Requirements

- Minecraft 1.21.11 with Fabric Loader 0.16 or newer
- Meteor Client for 1.21.11
- Java 21

## Install

1. Install Meteor Client for 1.21.11 as usual.
2. Download `quiettee-utils-<version>+mc1.21.11.jar` from the releases page.
3. Put it in your `mods` folder next to `meteor-client`.
4. Start the game. The modules live in the **Quiettee Utils** tab of the Meteor GUI.

## Building

```
./gradlew build
```

The jar lands in `build/libs/`. Java 21 is required; the Gradle wrapper downloads everything else.

The pure-Java helpers behind Lance and MaceSmash have regression programs in `checks/`:

```
sh checks/run-checks.sh
```

or on Windows

```
powershell -File checks/Run-Checks.ps1
```

## License

GPL-3.0, the same license as Meteor Client. Built on the Meteor Client addon API.
