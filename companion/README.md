# CivMaker Companion

Live village territory and registered **Farm Stations** on **Xaero's Minimap and World Map**, with colored outer borders, owner names, village points, raid status, and capital/settlement/farm waypoints. Clients receive public data for the world they are currently in. Updates normally arrive within three seconds.

## Add it to your CurseForge profile

This release matches the existing **CivMaker** profile:

| Component | Version |
| --- | --- |
| Minecraft | 26.3 |
| Mod loader | Fabric 0.20.1 or newer |
| Fabric API | 0.166.0+26.3 or newer |
| Xaero's Minimap | 26.6.2 |
| Xaero's World Map (optional) | 1.47.2 |
| CivMaker Paper server plugin | 0.9.0 or newer |

1. Close Minecraft. In CurseForge, open the profile's three-dot menu and choose **Open Folder**.
2. Put **`civmaker-companion-fabric-26.3-0.4.0.jar`** in its **`mods`** folder. Use the normal JAR, not the `-sources.jar`.
3. Keep Fabric API and Xaero's Minimap installed. World Map is optional. Your current profile already has these dependencies.
4. On the server, replace the old CivMaker plugin JAR with **`civmaker-0.9.0.jar`**, then restart the server. Keep only one CivMaker server JAR in `plugins`.
5. Launch the profile and join the server. Run **`/civmap status`** to confirm the connection.

The local profile's mod folder is `C:\Users\trber\curseforge\minecraft\Instances\CivMaker\mods`.

The companion is a **client mod**; do not put it in the Paper server's `plugins` folder. The server continues to support players who do not install it. It stays inactive on other servers.

For sharing a private pack, ensure the exported ZIP contains this custom JAR in `overrides/mods/`; if CurseForge's export omits the manually installed file, add it there before sharing. This local JAR is not a published CurseForge project and will not have an automatic update listing. A public CurseForge pack may need the companion published and approved as its own project.

## Controls and display

| Client command | Effect |
| --- | --- |
| `/civmap` | Toggle territory overlays and landmarks |
| `/civmap markers` | Toggle capital (`C`), settlement (`S`), and farm (`F`) landmarks |
| `/civmap farms` | Toggle farm chunk highlights and farm markers |
| `/civmap status` | Show sync status, plot count, village count, farm count, and raid status |
| `/civmap refresh` | Request a fresh snapshot |

The World Map shows ownership details using Xaero's highlight tooltips. The minimap can display the village/owner through Xaero's current-highlight information display. Outer borders use the village's color; capital territory and settlements have distinct borders where they meet. Adjacent chunks of the same territory share a continuous fill. Highlights follow Xaero's discovered-map behavior and do not draw over unexplored terrain.

Farm Station chunks get a gold outline. On village territory they retain the village's fill color; in wilderness they have a green fill. Each station gets an `F` waypoint in its owner's faction color (gray when unaffiliated). Tooltips show the farm name, station owner, owner's faction, and the underlying land ownership separately. Renaming or removing a station, or changing its owner's faction, updates the map automatically. Farms remain visible outside village claims and in unloaded chunks using CivMaker's registered station index. Ordinary crop fields and unregistered lodestones are not classified as farms.

### Radar requirement

Your own faction's claim fills/outlines, capital/settlement waypoints, farms, and player markers are visible without a Radar. Farms you personally own also remain visible when you have no faction. Your display toggles still apply.

Carry a genuine **CivMaker Radar** in the main inventory, hotbar, or offhand to also see other factions' claims/landmarks, enemy farms, and enemy players on Xaero's Minimap and World Map. Other players' unaffiliated farms require a Radar too. Holding it is not required for the companion; a plain compass renamed Radar does not qualify. Items in chests, shulker boxes, ender chests, or the inventory cursor do not count. Farm tooltips do not reveal hidden foreign territory ownership. The existing chat Radar still requires holding the item in either hand.

The server checks Radar possession every tick and replaces the player's map snapshot immediately when access changes. Without a Radar it sends only your faction's claims/landmarks and your own/faction farms, using stable faction UUIDs rather than display names. The companion applies the same rules to fills, borders, tooltips, and waypoints, and filters nearby/tracked enemy player markers. Faction membership changes refresh the visible territory and farms. The `/civmap` display toggle does not bypass these requirements. Removing, dropping, storing, or losing the Radar hides foreign information as soon as the server update arrives. Other servers and normal in-world player rendering are unaffected.

Companion 0.3.1 fixes stale Xaero highlight textures after Radar access changes. Both the minimap writer and the World Map's cached region hashes are refreshed when map data, Radar access, display settings, or the map session changes. Claim colors and borders therefore redraw on Radar pickup/removal without needing to move, reconnect, or clear your saved map.

Faction-aware maps require **companion 0.4.0 and CivMaker 0.9.0**. Update both. Older servers cannot provide the faction identity needed for always-visible friendly territory. This integration governs Xaero displays in the companion; it is not an anti-cheat for other client mods.

Markers use Xaero's session-only third-party waypoints. They never change your personal waypoints. Xaero's normal waypoint visibility settings still apply; the default waypoint set displays third-party markers. Ownership changes, disbands, world switches, and disconnects remove stale overlays and markers.

Client settings are stored in `config/civmaker-companion.json`:

```json
{
  "enabled": true,
  "markers": true,
  "farms": true,
  "fillOpacity": 28,
  "borderOpacity": 90
}
```

Opacity ranges from 0 to 100. Edit while Minecraft is closed. Commands save toggle settings automatically.

Server administrators can set `companion-map.enabled: false` in `plugins/CivMaker/config.yml` and restart to disable map data. The bridge sends public raid status, a Radar verdict, your viewer/faction UUIDs, and friendly-player UUIDs. It filters plot ownership, capital/settlement coordinates, and registered Farm Station details for each viewer: own faction/personal information without a Radar, all mapped factions with one. It excludes wallets, vault contents/locations, full membership lists, and unpurchased natural settlements. It does not enable claiming, teleporting, or other server actions from the map.

## Build

Install JDK 25, then from this directory run:

```powershell
.\gradlew.bat build
```

The installable JAR is `build/libs/civmaker-companion-fabric-26.3-0.4.0.jar`. Build the Paper plugin separately with `mvn package` from the repository root. These commands preserve older versioned JARs. The Gradle wrapper downloads its pinned Gradle version and build dependencies on first use. Xaero dependencies are compile-time dependencies, not bundled redistributions.

The protocol source in `../src/main/java/dev/civmaker/map/protocol` is shared with the Paper plugin. The client registers small hooks before Xaero freezes its highlight registries, then uses Xaero's own chunk-highlighter and third-party-waypoint interfaces. Xaero versions are pinned because these interfaces can change between releases.

Automated checks cover bounded/reassembled packets, ownership changes, removed claims, negative chunk coordinates, dimension isolation, border colors, and vanilla-client opt-in behavior. Run server tests with `mvn test` at the repository root and client/protocol tests with `gradlew test` here.
