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
| CivMaker Paper server plugin | 0.5.0 or newer |

1. Close Minecraft. In CurseForge, open the profile's three-dot menu and choose **Open Folder**.
2. Put **`civmaker-companion-fabric-26.3-0.2.0.jar`** in its **`mods`** folder. Use the normal JAR, not the `-sources.jar`.
3. Keep Fabric API and Xaero's Minimap installed. World Map is optional. Your current profile already has these dependencies.
4. On the server, replace the old CivMaker plugin JAR with **`civmaker-0.5.0.jar`**, then restart the server. Keep only one CivMaker server JAR in `plugins`.
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

Companion 0.2.0 can still display claims from CivMaker 0.4.0; farm data requires CivMaker 0.5.0. The older companion can connect to the newer server and ignores the added farm information.

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

Server administrators can set `companion-map.enabled: false` in `plugins/CivMaker/config.yml` and restart to disable the integration. The bridge sends owned plot locations, village IDs/names/colors/points, owner UUIDs/names, capital coordinates, purchased settlement bell coordinates, registered Farm Station coordinates/names/owners/factions, and public raid status. It excludes wallets, vault contents/locations, members, and unpurchased natural settlements. It does not enable claiming, teleporting, or other server actions from the map.

## Build

Install JDK 25, then from this directory run:

```powershell
.\gradlew.bat build
```

The installable JAR is `build/libs/civmaker-companion-fabric-26.3-0.2.0.jar`. Build the Paper plugin separately with `mvn package` from the repository root. These commands preserve older versioned JARs. The Gradle wrapper downloads its pinned Gradle version and build dependencies on first use. Xaero dependencies are compile-time dependencies, not bundled redistributions.

The protocol source in `../src/main/java/dev/civmaker/map/protocol` is shared with the Paper plugin. The client registers small hooks before Xaero freezes its highlight registries, then uses Xaero's own chunk-highlighter and third-party-waypoint interfaces. Xaero versions are pinned because these interfaces can change between releases.

Automated checks cover bounded/reassembled packets, ownership changes, removed claims, negative chunk coordinates, dimension isolation, border colors, and vanilla-client opt-in behavior. Run server tests with `mvn test` at the repository root and client/protocol tests with `gradlew test` here.
