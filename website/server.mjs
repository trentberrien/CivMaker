import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { basename, dirname, join, resolve } from "node:path";

const [exportPath, portArgument = "8787"] = process.argv.slice(2);
const port = Number(portArgument);
if ((exportPath && basename(exportPath) !== "public-v1.json") ||
    !Number.isInteger(port) || port < 1 || port > 65535) {
  console.error('Usage: node website/server.mjs ["path/to/public-v1.json"] [port]');
  process.exit(1);
}

const assets = new Map([
  ["/", ["dist/index.html", "text/html; charset=utf-8"]],
  ["/index.html", ["dist/index.html", "text/html; charset=utf-8"]],
  ["/atlas.css", ["dist/atlas.css", "text/css; charset=utf-8"]],
  ["/atlas.js", ["dist/atlas.js", "text/javascript; charset=utf-8"]],
  ["/terrain.js", ["dist/terrain.js", "text/javascript; charset=utf-8"]],
  ["/demo.js", ["dist/demo.js", "text/javascript; charset=utf-8"]],
  ["/world-terrain.json", ["local-world/terrain.json", "application/json; charset=utf-8"]],
  ["/world-terrain.png", ["local-world/terrain.png", "image/png"]],
]);
const exportFile = exportPath && resolve(exportPath);
const plains = "minecraft:village_plains";
const bellKey = ({ world, x, y, z }) => JSON.stringify([world, x, y, z]);

async function readVillageExport() {
  const contents = await readFile(exportFile);
  const state = await readFile(join(dirname(exportFile), "state.json"), "utf8")
    .then(JSON.parse).catch(() => null);
  if (!state) return contents;

  const meadows = new Set();
  for (const village of Object.values(state.villages ?? {})) {
    for (const site of village.settlements ?? []) {
      if (site.structure === plains && site.bell && site.vault?.reward === "AMETHYST") {
        meadows.add(bellKey(site.bell));
      }
    }
  }
  if (!meadows.size) return contents;

  const snapshot = JSON.parse(contents.toString("utf8"));
  for (const village of snapshot.villages) {
    for (const site of village.settlements ?? []) {
      if (site.type === undefined && site.structure === plains && meadows.has(bellKey(site.bell))) {
        site.type = "minecraft:village_meadow";
      }
    }
  }
  return Buffer.from(JSON.stringify(snapshot));
}

const server = createServer(async (request, response) => {
  response.setHeader("X-Content-Type-Options", "nosniff");
  response.setHeader("Cache-Control", "no-store");
  if (request.method !== "GET" && request.method !== "HEAD") {
    response.writeHead(405, { Allow: "GET, HEAD" }).end();
    return;
  }
  const route = request.url.split("?")[0];
  const asset = assets.get(route);
  if (!asset && (route !== "/public-v1.json" || !exportFile)) {
    response.writeHead(404).end();
    return;
  }
  try {
    const contents = asset ? await readFile(new URL(asset[0], import.meta.url)) : await readVillageExport();
    response.writeHead(200, {
      "Content-Type": asset?.[1] ?? "application/json; charset=utf-8",
      "Content-Length": contents.length,
    }).end(request.method === "HEAD" ? undefined : contents);
  } catch {
    response.writeHead(asset ? 404 : 503).end();
  }
});

server.on("error", (error) => {
  console.error(error.code === "EADDRINUSE" ? "Port " + port + " is already in use." : error.message);
  process.exitCode = 1;
});
server.listen(port, "127.0.0.1", () => {
  console.log("CivMaker Atlas · http://127.0.0.1:" + port);
  console.log(exportFile ? "Reading " + exportFile : "Use Load server data to open a CivMaker export.");
});
