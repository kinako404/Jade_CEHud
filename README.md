# Jade

> **CE Block HUD fork** (branch base: upstream `26.3-fabric`). On servers running the CEBlockHud Paper plugin
> (`../ce-block-hud`), Jade shows the real CraftEngine block/furniture name, icon and pack instead of the vanilla
> carrier block (mushroom stem, note block, tripwire …), and the server stops sending its boss bar to this player.
> On other servers it behaves exactly like upstream Jade.
>
> - Code: `src/main/java/snownee/jade/addon/cehud/` (protocol in `CEHudPackets`, must match `ClientModBridge` in the plugin).
>   Hooks in upstream files: payload registration (`CommonProxy`, `ClientProxy`), plugin entrypoint (`fabric.mod.json`),
>   and `Jade.canBeTarget` (allows the furniture's hidden Interaction entities).
> - Build: `./gradlew build` → `build/libs/Jade-mc26.3-Fabric-<version>.jar` (mod id stays `jade`; replaces upstream Jade).
> - End-to-end test against a running server: start a display (`Xvfb :99 &`), `op CeHudBot` on the server, then
>   `DISPLAY=:99 ./gradlew runClientGameTest` (`src/gametest/`). It places blocks at 0 250 2 in the overworld and checks
>   the tooltip and that no boss bar is shown.
> - License: upstream is CC BY-NC-SA 4.0; this fork inherits it (non-commercial, share-alike).

[Documentation](https://jademc.readthedocs.io/en/latest/)

Jade is a UI improvement mod which shows information about what you are looking at. Jade is a fork of [HWYLA](https://github.com/TehNut/HWYLA) by TehNut
