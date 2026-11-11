# Install CivMaker Companion

This package contains two different JARs:

1. **Client:** copy `client-mods/civmaker-companion-fabric-26.3-0.3.0.jar` into your CurseForge profile's `mods` folder. Close Minecraft first. Your CivMaker profile already has Fabric 0.20.1, Fabric API 0.166.0+26.3, Xaero's Minimap 26.6.2, and Xaero's World Map 1.47.2.
2. **Server:** replace the old CivMaker plugin with `server-plugins/civmaker-0.8.0.jar` in your Paper 26.3 server's `plugins` folder, then restart. Keep one CivMaker plugin JAR. Existing CivMaker data/configs are compatible.
3. Join the server and run `/civmap status`. Ownership and markers appear automatically. Changes normally sync within three seconds.

Farm Station chunks have gold outlines and `F` markers, with the farm name, owner, and faction in the tooltip. Farm data requires both updated JARs. Replace the old companion JAR in your profile; keep backups outside `mods`.

Carry a genuine CivMaker Radar anywhere in the main inventory or offhand to see claims, territorial landmarks, and enemy players on Xaero's maps. Farm displays and faction teammates remain visible without one. A renamed ordinary compass does not count. Install **both companion 0.3.0 and server 0.8.0** for this behavior; the chat Radar still requires holding it.

`/civmap` toggles overlays. `/civmap farms` toggles farm highlights and markers. `/civmap markers` toggles landmarks. `/civmap refresh` requests a fresh snapshot.

The companion requires Minecraft 26.3 and the Xaero versions above. Xaero's World Map is optional. No server mod loader is required. Other players can join without the companion.

To share the custom mod in a private CurseForge pack, ensure the exported ZIP includes the client JAR under `overrides/mods/`.

See README.md for display settings, build instructions, and the data the server shares.
