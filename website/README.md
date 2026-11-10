# CivMaker World Atlas

The local prototype runs with Node.js and has no package installation step. From the CivMaker folder in PowerShell:

```powershell
node .\website\server.mjs
```

Open [the local atlas](http://127.0.0.1:8787/) while the terminal is running. Press **Ctrl+C** in the terminal to stop it. The server listens on this computer's loopback address.

## Explore the atlas

- Drag to pan, scroll or use **+ / −** to zoom, and use **Fit map** to see the world overview.
- Toggle capital claims, settlement plots and beacons. Select a village in the searchable points ranking to center it and see its members, claimed chunks, vault balance, settlements and beacon coordinates.
- **Load server data** reads a copy of `plugins/CivMaker/public-v1.json` in the browser. It stays in that tab. **Reset data** returns to the data source the server is configured to serve.
- To follow the export as Paper writes it, start the atlas with the full path. Use a different final port argument if running a second copy.

```powershell
node .\website\server.mjs "C:\path\to\Paper\plugins\CivMaker\public-v1.json" 8787
```

The atlas checks for an updated export every 15 seconds. CivMaker publishes its export on a scheduled save when data changes, so the **Exported** timestamp can stay the same when the world is quiet. When the local server follows an export in the CivMaker plugin folder, it uses the vault reward from the neighboring private save to distinguish Meadow from Plains. The browser receives only the resolved type alongside the public data. The map uses block X/Z, with north (decreasing Z) at the top. Each claim is placed at the northwest corner of its 16 × 16 chunk. Village rankings and details use village-wide totals for the villages shown in the selected world.

The default terrain is illustrated demo scenery. A server export supplies claims, villages and beacon positions. The atlas shows a coordinate grid for any exported dimension without a matching terrain image.

## Edit the CivMaker config

From the CivMaker folder, install the editor's YAML dependency once, then launch the editor with the config you want to change. This example edits the installed Paper plugin's file.

```powershell
pnpm --dir website install
node .\website\config-server.mjs "C:\path\to\Paper\plugins\CivMaker\config.yml"
```

You can also install with `npm install --prefix website`. Run `node .\website\config-server.mjs` without a path to edit the project's `src/main/resources/config.yml`. The optional second argument sets the port.

Open [the local config editor](http://127.0.0.1:8788/) and check the file path in the sidebar. Toggle raid protection, Atlas exports, or either warp timer. Use the number fields for prices, durations, beacon height, and ore rewards. The shield cooldown dropdown offers common durations, and the ore dropdown filters the list. A payout of zero disables the reward for that ore.

Stop Paper before editing its config. Select **Save changes** to write the file, then start Paper to apply the changes. **Reload file** reads the current file from disk. The editor checks for changes made elsewhere before saving and keeps additional YAML settings and comments. It listens only on `127.0.0.1`.

## Use a real world save

Render a north-up surface image from a copy of the Minecraft world. For example, run [uNmINeD CLI](https://unmined.net/docs/cli/getting-started/) with an explicit block rectangle and leave trimming off so the image edges keep their original coordinates. This example covers 2,048 × 2,048 blocks around X 0, Z 0. Adjust the rectangle to cover the part of the world you want to show.

```powershell
New-Item -ItemType Directory -Force .\website\local-world
& "C:\path\to\unmined-cli.exe" image render --world="C:\path\to\world" --output=".\website\local-world\terrain.png" '--area=b(-1024,-1024,2048,2048)' --zoom=0
```

Save `terrain.json` beside `terrain.png` with the same block rectangle. Use the matching dimension's UUID from the `worlds` list in `public-v1.json` so the atlas can align the terrain with that dimension's claims.

```json
{ "world": "REPLACE_WITH_WORLD_UUID", "name": "Overworld", "x": -1024, "z": -1024, "width": 2048, "height": 2048 }
```

Reload the atlas. It can show terrain on its own, or layer claims and stats on top when you load the matching CivMaker export. The render is a snapshot. Render again to pick up changes to the Minecraft landscape. The `local-world` folder is outside the static publishing folder.

## What a live site needs

| Piece | Setup |
| --- | --- |
| **Site and domain** | Serve `dist/` over HTTPS. A static host can serve the dashboard. If you already have an always-on Minecraft VPS, a web server or reverse proxy there can serve both the dashboard and the terrain viewer under one domain. [Cloudflare Pages static HTML](https://developers.cloudflare.com/pages/framework-guides/deploy-anything/) |
| **Village data** | Serve a reviewed copy of `public-v1.json` beside the website assets. On one machine, the web server can serve that single file. On separate hosts, have a scheduled job validate and atomically replace the published copy, retaining the previous good file on error. Cache the feed briefly and alert when syncs fail. Check sync health separately from the export timestamp, since a quiet server can have an old export. |
| **Real terrain and overlays** | Install a renderer compatible with the server's Paper version. BlueMap can render the world and manage polygon markers. A small bridge would map CivMaker world UUIDs to BlueMap map IDs and turn the exact claim chunks into marker shapes. The dashboard can share that terrain viewer and link to a village's coordinates. First renders use CPU and disk, so start with a bounded area and measure before widening it. [BlueMap installation](https://bluemap.bluecolored.de/wiki/getting-started/Installation.html) · [BlueMap markers](https://bluemap.bluecolored.de/wiki/customization/Markers.html) |
| **Access and operations** | Decide who can see beacon coordinates, owner details and village wealth. If those are private, filter the feed before publishing or put the entire site, feed and tiles behind authentication. Keep `state.json`, raw world saves and admin endpoints private. For a competitive server, disable live player markers unless you want visitors to see where players are. [BlueMap player-marker settings](https://bluemap.bluecolored.de/wiki/configs/Plugin.html) |

A read-only launch can run on the export and rendered tiles without a database. Ongoing costs depend on the host, domain, map size and tile traffic. Serving the last copied export and rendered tiles independently of Paper also keeps the atlas viewable during game-server restarts.
