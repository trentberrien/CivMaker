import { demo, demoWorld } from "./demo.js";
import { atlasLabels, makeTerrain, terrainBounds } from "./terrain.js";

const $ = id => document.getElementById(id);
const number = new Intl.NumberFormat();
const money = new Intl.NumberFormat(undefined, { style: "currency", currency: "USD", maximumFractionDigits: 0 });
const settlementTypes = new Map([
  ["minecraft:village_desert", "Desert"],
  ["minecraft:village_meadow", "Meadow"],
  ["minecraft:village_plains", "Plains"],
  ["minecraft:village_savanna", "Savanna"],
  ["minecraft:village_snowy", "Snowy Plains"],
  ["minecraft:village_taiga", "Taiga"],
]);
const canvas = $("canvas");
const ctx = canvas.getContext("2d");
const map = $("map");
const view = { x: 0, z: 0, scale: 1, width: 0, height: 0 };
const layers = {
  claims: $("claims-layer"),
  settlements: $("settlements-layer"),
  beacons: $("beacons-layer"),
};
let source;
let data;
let world;
let selected;
let activeVillages = [];
let activeClaims = [];
let activeSettlements = [];
let markers = [];
let demoTerrain;
let worldTerrain;
let drag;
let hasFit = false;
let remoteSnapshot;
let importAttempt = 0;

function readExport(text) {
  const snapshot = JSON.parse(text.replace(/^\uFEFF/, ""));
  const invalid = () => { throw new Error("Choose a CivMaker public-v1.json export."); };
  const plot = value => value && typeof value.world === "string" &&
    Number.isSafeInteger(value.x) && Number.isSafeInteger(value.z);
  const position = value => plot(value) && Number.isSafeInteger(value.y);
  const count = value => typeof value === "number" && Number.isFinite(value) && value >= 0;
  if (snapshot?.schemaVersion !== 1 || !Array.isArray(snapshot.villages) ||
      !snapshot.worlds || typeof snapshot.worlds !== "object" || Array.isArray(snapshot.worlds)) invalid();
  const ids = new Set();
  for (const village of snapshot.villages) {
    if (!village || typeof village.id !== "string" || ids.has(village.id) || typeof village.name !== "string" ||
        !position(village.beacon) || !Array.isArray(village.claims) || !village.claims.every(plot) ||
        !Array.isArray(village.settlements) || !village.settlements.every(site => position(site?.bell) &&
          typeof site.structure === "string" && Array.isArray(site.plots) && site.plots.every(plot)) ||
        ![village.memberCount, village.claimCount, village.balance, village.points].every(count)) invalid();
    ids.add(village.id);
  }
  return snapshot;
}

function node(tag, className, value) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (value !== undefined) element.textContent = value;
  return element;
}

function color(village) {
  return /^#[0-9a-f]{6}$/i.test(village.color) ? village.color : "#dcbf7f";
}

function setStatus(message, error = false) {
  $("snapshot").textContent = message;
  $("snapshot").dataset.error = error;
}

async function loadWorldTerrain() {
  try {
    const response = await fetch("./world-terrain.json");
    if (!response.ok) return;
    const info = await response.json();
    if (typeof info.world !== "string" || !info.world || typeof info.name !== "string" ||
        ![info.x, info.z, info.width, info.height].every(Number.isSafeInteger) ||
        info.width <= 0 || info.height <= 0) return;
    const image = new Image();
    image.src = "./world-terrain.png";
    await image.decode();
    worldTerrain = { ...info, image };
    if (data) rebuildWorld(true);
  } catch {
    setStatus("The local terrain image could not be loaded.", true);
  }
}

function fallbackSnapshot() {
  if (worldTerrain) {
    applySnapshot({ worlds: { [worldTerrain.world]: worldTerrain.name }, villages: [] }, "terrain");
  } else {
    applySnapshot(demo, "demo");
  }
}

function applySnapshot(snapshot, kind) {
  const newSource = source !== kind;
  source = kind;
  data = snapshot;
  const title = { demo: "Demo world", terrain: "World terrain", feed: "Server feed", file: "Imported snapshot" }[kind];
  $("source").dataset.kind = kind;
  $("source").lastElementChild.textContent = title;
  $("restore").hidden = kind !== "file";
  const date = new Date(snapshot.generatedAt);
  if (kind === "demo") setStatus("Illustrated terrain and sample village data");
  else if (kind === "terrain") setStatus("Load a CivMaker export to see claims and village stats");
  else setStatus(Number.isNaN(date.getTime()) ? "Snapshot time unavailable" : "Exported " + date.toLocaleString());

  const worlds = new Map(Object.entries(snapshot.worlds));
  for (const village of snapshot.villages) {
    for (const item of [village.beacon, ...village.claims, ...village.settlements.map(site => site.bell)]) {
      if (!worlds.has(item.world)) worlds.set(item.world, item.world);
    }
  }
  const defaultWorld = worlds.has(worldTerrain?.world) ? worldTerrain.world
    : snapshot.villages[0]?.beacon.world ?? worlds.keys().next().value;
  const nextWorld = !newSource && worlds.has(world) ? world : defaultWorld;
  const reset = newSource || nextWorld !== world;
  world = nextWorld;
  if (newSource) $("search").value = "";
  const options = [...worlds].map(([id, name]) => new Option(typeof name === "string" ? name : id, id));
  if (!options.length) options.push(new Option("No worlds", ""));
  $("world").replaceChildren(...options);
  $("world").disabled = worlds.size < 2;
  $("world").value = world ?? "";
  rebuildWorld(reset);
}

function rebuildWorld(reset) {
  activeVillages = [];
  activeClaims = [];
  activeSettlements = [];
  const uniqueClaims = new Set();
  for (const village of data.villages) {
    const plots = village.claims.filter(plot => plot.world === world);
    const sites = village.settlements.filter(site => site.bell.world === world);
    if (village.beacon.world !== world && !plots.length && !sites.length) continue;
    activeVillages.push(village);
    const settlementKeys = new Set(sites.flatMap(site => site.plots
      .filter(plot => plot.world === world).map(plot => plot.x + "," + plot.z)));
    for (const plot of plots) {
      const key = plot.x + "," + plot.z;
      uniqueClaims.add(key);
      activeClaims.push({ ...plot, village, settlement: settlementKeys.has(key) });
    }
    activeSettlements.push(...sites.map(site => ({ ...site, village })));
  }
  activeVillages.sort((a, b) => b.points - a.points || a.name.localeCompare(b.name));
  if (reset || !activeVillages.some(village => village.id === selected)) selected = activeVillages[0]?.id;
  const stats = {
    "total-villages": activeVillages.length,
    "total-claims": uniqueClaims.size,
    "total-settlements": activeSettlements.length,
    "total-members": activeVillages.reduce((sum, village) => sum + village.memberCount, 0),
    "directory-count": activeVillages.length,
  };
  for (const [id, value] of Object.entries(stats)) $(id).textContent = number.format(value);
  $("terrain-label").textContent = terrainForWorld() ? "Rendered Minecraft terrain"
    : source === "demo" ? "Illustrated demo terrain" : "Coordinate grid · terrain unavailable";
  renderDirectory();
  renderDetail();
  buildMarkers();
  if (reset || !hasFit) fitMap();
  else draw();
}

function renderDirectory() {
  const query = $("search").value.trim().toLocaleLowerCase();
  const rows = [];
  activeVillages.forEach((village, index) => {
    if (!village.name.toLocaleLowerCase().includes(query)) return;
    const row = node("li");
    const button = node("button");
    button.type = "button";
    button.dataset.village = village.id;
    button.setAttribute("aria-pressed", village.id === selected);
    const dot = node("i", "village-dot");
    dot.style.setProperty("--village-color", color(village));
    button.append(node("span", "rank", String(index + 1).padStart(2, "0")), dot,
      node("span", "village-name", village.name), node("span", "village-points", number.format(village.points)));
    button.addEventListener("click", () => selectVillage(village, true));
    row.append(button);
    rows.push(row);
  });
  if (!rows.length) {
    const message = source === "terrain" ? "Load a CivMaker export to show villages."
      : query ? "No matching villages." : "No villages in this world.";
    rows.push(node("li", "empty", message));
  }
  $("villages").replaceChildren(...rows);
}

function renderDetail() {
  const detail = $("detail");
  const village = activeVillages.find(item => item.id === selected);
  detail.hidden = !village;
  detail.replaceChildren();
  if (!village) return;
  const metrics = node("dl");
  const stats = {
    Members: number.format(village.memberCount),
    "Claimed chunks": number.format(village.claimCount),
    "Vault balance": money.format(village.balance),
    Settlements: number.format(village.settlements.length),
  };
  for (const [label, value] of Object.entries(stats)) {
    const group = node("div");
    group.append(node("dt", "", label), node("dd", "", value));
    metrics.append(group);
  }
  detail.append(node("p", "eyebrow", "SELECTED VILLAGE"), node("h3", "", village.name));
  if (typeof village.ownerName === "string") detail.append(node("p", "", "Led by " + village.ownerName));
  detail.append(metrics);
  const beacon = village.beacon;
  const worldName = beacon.world === world ? "" : " · " + (data.worlds[beacon.world] ?? beacon.world);
  const coordinates = node("p", "beacon");
  coordinates.innerHTML = '<svg aria-hidden="true"><use href="#pin-icon"/></svg>';
  coordinates.append("Beacon · " + [beacon.x, beacon.y, beacon.z].map(value => number.format(value)).join(", ") + worldName);
  detail.append(coordinates);
}

function selectVillage(village, focusMap) {
  selected = village.id;
  for (const button of $("villages").querySelectorAll("button")) {
    button.setAttribute("aria-pressed", button.dataset.village === selected);
  }
  renderDetail();
  if (focusMap) {
    const anchor = village.beacon;
    const plot = activeClaims.find(claim => claim.village === village);
    const center = anchor.world === world ? anchor : plot ? { x: plot.x * 16 + 8, z: plot.z * 16 + 8 }
      : activeSettlements.find(site => site.village === village)?.bell;
    if (center) {
      view.x = center.x;
      view.z = center.z;
      view.scale = Math.max(view.scale, Math.min(view.width, view.height) / 460);
    }
  }
  draw();
}

function buildMarkers() {
  markers = [];
  const add = (village, anchor, label, layer) => {
    const button = node("button", "marker");
    button.type = "button";
    button.style.setProperty("--village-color", color(village));
    button.append(node("i"), node("span", "", label));
    button.setAttribute("aria-label", layer === "beacons" ? village.name + " beacon" : village.name + " " + label);
    button.addEventListener("click", () => selectVillage(village, false));
    markers.push({ village, anchor, layer, button });
  };
  for (const { village, bell, structure, type } of activeSettlements) {
    const name = settlementTypes.get(type) ?? settlementTypes.get(structure);
    add(village, bell, name ? name + " settlement" : "Settlement", "settlements");
  }
  for (const village of activeVillages) {
    if (village.beacon.world === world) add(village, village.beacon, village.name, "beacons");
  }
  $("markers").replaceChildren(...markers.map(marker => marker.button));
}

function fitMap() {
  if (!view.width || !view.height) return;
  let left = Infinity, right = -Infinity, top = Infinity, bottom = -Infinity;
  const include = (x, z, size = 0) => {
    left = Math.min(left, x);
    right = Math.max(right, x + size);
    top = Math.min(top, z);
    bottom = Math.max(bottom, z + size);
  };
  if (source === "demo") {
    include(-1420, -830);
    include(1390, 820);
  } else {
    const base = terrainForWorld();
    if (base) {
      include(base.x, base.z);
      include(base.x + base.width, base.z + base.height);
    }
    for (const plot of activeClaims) include(plot.x * 16, plot.z * 16, 16);
    for (const village of activeVillages) {
      if (village.beacon.world === world) include(village.beacon.x, village.beacon.z);
    }
    for (const site of activeSettlements) include(site.bell.x, site.bell.z);
  }
  view.x = Number.isFinite(left) ? (left + right) / 2 : 0;
  view.z = Number.isFinite(top) ? (top + bottom) / 2 : 0;
  view.scale = Math.min(view.width / (Math.max(196, right - left) + 96),
    view.height / (Math.max(196, bottom - top) + 96));
  hasFit = true;
  draw();
}

function worldX(pixel) { return view.x + (pixel - view.width / 2) / view.scale; }
function worldZ(pixel) { return view.z + (pixel - view.height / 2) / view.scale; }
function screenX(x) { return view.width / 2 + (x - view.x) * view.scale; }
function screenZ(z) { return view.height / 2 + (z - view.z) * view.scale; }

function terrainForWorld() {
  return worldTerrain?.world === world ? worldTerrain : undefined;
}

function readCoordinates(x = view.x, z = view.z) {
  $("coordinates").textContent = "X " + number.format(Math.floor(x)) + " · Z " + number.format(Math.floor(z));
}

function draw() {
  const { width, height, scale } = view;
  if (!width || !height) return;
  ctx.fillStyle = source === "demo" ? "#2a4a4d" : "#28342d";
  ctx.fillRect(0, 0, width, height);
  const base = terrainForWorld();
  if (base) {
    ctx.imageSmoothingEnabled = scale < 1;
    ctx.drawImage(base.image, screenX(base.x), screenZ(base.z), base.width * scale, base.height * scale);
  } else if (source === "demo") {
    demoTerrain ??= makeTerrain();
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(demoTerrain, screenX(terrainBounds.x), screenZ(terrainBounds.z),
      terrainBounds.width * scale, terrainBounds.height * scale);
  }
  const grid = 16 * 2 ** Math.max(0, Math.ceil(Math.log2(43 / (16 * scale))));
  ctx.strokeStyle = source === "demo" ? "#f7edc41f" : "#b3d0b42c";
  ctx.lineWidth = 1;
  ctx.beginPath();
  for (let x = Math.ceil(worldX(0) / grid) * grid; x <= worldX(width); x += grid) {
    const px = Math.round(screenX(x)) + .5;
    ctx.moveTo(px, 0);
    ctx.lineTo(px, height);
  }
  for (let z = Math.ceil(worldZ(0) / grid) * grid; z <= worldZ(height); z += grid) {
    const pz = Math.round(screenZ(z)) + .5;
    ctx.moveTo(0, pz);
    ctx.lineTo(width, pz);
  }
  ctx.stroke();

  if (source === "demo" && scale < .65) {
    ctx.font = "italic 11px Georgia, serif";
    ctx.textAlign = "center";
    ctx.lineWidth = 3;
    ctx.strokeStyle = "#1b2a26a8";
    ctx.fillStyle = "#eee3becc";
    for (const [x, z, label] of atlasLabels) {
      ctx.strokeText(label, screenX(x), screenZ(z));
      ctx.fillText(label, screenX(x), screenZ(z));
    }
  }
  const size = 16 * scale;
  for (const plot of activeClaims) {
    if (!(plot.settlement ? layers.settlements : layers.claims).checked) continue;
    const x = screenX(plot.x * 16);
    const z = screenZ(plot.z * 16);
    if (x + size < 0 || x > width || z + size < 0 || z > height) continue;
    const tint = color(plot.village);
    ctx.fillStyle = tint + (plot.village.id === selected ? "80" : "53");
    ctx.fillRect(x, z, size, size);
    if (size > 3) {
      ctx.strokeStyle = tint + (plot.settlement ? "ff" : "ba");
      ctx.lineWidth = .75;
      ctx.setLineDash(plot.settlement && size > 6 ? [2, 2] : []);
      ctx.strokeRect(x, z, size, size);
    }
  }
  ctx.setLineDash([]);
  for (const { village, anchor, layer, button } of markers) {
    const x = screenX(anchor.x);
    const z = screenZ(anchor.z);
    const visible = layers[layer].checked && x >= 0 && x <= width && z >= 24 && z <= height;
    button.hidden = !visible;
    if (!visible) continue;
    ctx.fillStyle = color(village);
    ctx.beginPath();
    ctx.arc(x, z, layer === "settlements" ? 2.5 : 4, 0, Math.PI * 2);
    ctx.fill();
    button.style.left = x + "px";
    button.style.top = (z - 7) + "px";
    button.setAttribute("aria-pressed", village.id === selected);
  }
  const distance = 2 ** Math.floor(Math.log2(100 / scale));
  $("scale-label").textContent = number.format(distance) + " blocks";
  $("scale-bar").style.width = distance * scale + "px";
  readCoordinates();
}

function zoom(multiplier, x = view.width / 2, z = view.height / 2) {
  const targetX = worldX(x);
  const targetZ = worldZ(z);
  view.scale = Math.max(Math.min(.0125, view.scale), Math.min(8, view.scale * multiplier));
  view.x += targetX - worldX(x);
  view.z += targetZ - worldZ(z);
  draw();
}

new ResizeObserver(() => {
  view.width = map.clientWidth;
  view.height = map.clientHeight;
  const ratio = window.devicePixelRatio || 1;
  canvas.width = Math.round(view.width * ratio);
  canvas.height = Math.round(view.height * ratio);
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  if (data && !hasFit) fitMap();
  else draw();
}).observe(map);

map.addEventListener("pointerdown", event => {
  if (!event.isPrimary || event.button !== 0 || event.target.closest("button")) return;
  drag = { id: event.pointerId, x: event.clientX, z: event.clientY };
  map.setPointerCapture(event.pointerId);
  map.classList.add("dragging");
});
map.addEventListener("pointermove", event => {
  if (drag?.id === event.pointerId) {
    view.x -= (event.clientX - drag.x) / view.scale;
    view.z -= (event.clientY - drag.z) / view.scale;
    drag.x = event.clientX;
    drag.z = event.clientY;
    draw();
  }
  const bounds = map.getBoundingClientRect();
  readCoordinates(worldX(event.clientX - bounds.left), worldZ(event.clientY - bounds.top));
});
map.addEventListener("lostpointercapture", () => {
  drag = undefined;
  map.classList.remove("dragging");
});
map.addEventListener("pointerleave", () => readCoordinates());
map.addEventListener("wheel", event => {
  if (event.ctrlKey || event.metaKey) return;
  event.preventDefault();
  const bounds = map.getBoundingClientRect();
  zoom(event.deltaY < 0 ? 1.2 : 1 / 1.2, event.clientX - bounds.left, event.clientY - bounds.top);
}, { passive: false });
map.addEventListener("keydown", event => {
  if (event.target !== map) return;
  const step = 70 / view.scale;
  switch (event.key) {
    case "ArrowUp": view.z -= step; break;
    case "ArrowDown": view.z += step; break;
    case "ArrowLeft": view.x -= step; break;
    case "ArrowRight": view.x += step; break;
    case "+":
    case "=": zoom(1.4); break;
    case "-": zoom(1 / 1.4); break;
    case "Home": fitMap(); break;
    default: return;
  }
  event.preventDefault();
  draw();
});
$("zoom-in").addEventListener("click", () => zoom(1.4));
$("zoom-out").addEventListener("click", () => zoom(1 / 1.4));
$("fit").addEventListener("click", fitMap);
for (const layer of Object.values(layers)) layer.addEventListener("change", draw);
$("search").addEventListener("input", renderDirectory);
$("world").addEventListener("change", event => {
  world = event.target.value;
  rebuildWorld(true);
});

async function refresh() {
  if (source === "file") return;
  try {
    const response = await fetch("./public-v1.json", { cache: "no-store" });
    if (source === "file") return;
    if (response.status === 404 && !data) {
      fallbackSnapshot();
      return;
    }
    if (response.status === 404 && (source === "demo" || source === "terrain")) return;
    if (!response.ok) throw new Error("Export unavailable");
    const text = await response.text();
    if (source === "file") return;
    if (source !== "feed" || text !== remoteSnapshot || $("snapshot").dataset.error === "true") {
      applySnapshot(readExport(text), "feed");
      remoteSnapshot = text;
    }
  } catch {
    if (source === "file") return;
    if (!data && worldTerrain) fallbackSnapshot();
    if (!data) {
      $("source").dataset.kind = "error";
      $("source").lastElementChild.textContent = "Export unavailable";
    }
    setStatus(data && source !== "terrain" ? "Could not refresh. Showing the last loaded data." :
      "Could not read the server export. Check the file path or load a snapshot.", true);
  } finally {
    if (source !== "file") setTimeout(refresh, 15000);
  }
}

$("import").addEventListener("click", () => $("export-file").click());
$("export-file").addEventListener("change", async event => {
  const file = event.target.files[0];
  event.target.value = "";
  if (!file) return;
  const attempt = ++importAttempt;
  try {
    const snapshot = readExport(await file.text());
    if (attempt === importAttempt) applySnapshot(snapshot, "file");
  } catch {
    if (attempt === importAttempt) setStatus("Could not load the file. Choose a CivMaker public-v1.json export.", true);
  }
});
$("restore").addEventListener("click", () => location.reload());
loadWorldTerrain().then(refresh);
