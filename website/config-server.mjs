import { createHash, randomUUID } from "node:crypto";
import { readFile, realpath, rename, stat, unlink, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { isMap, parseDocument } from "yaml";

const bundledPath = fileURLToPath(new URL("../src/main/resources/config.yml", import.meta.url));
const [fileArgument = bundledPath, portArgument = "8788", ...extra] = process.argv.slice(2);
const port = Number(portArgument);
if (extra.length || !/\.ya?ml$/i.test(fileArgument) ||
    !Number.isInteger(port) || port < 1 || port > 65535) {
  console.error('Usage: node website/config-server.mjs ["path/to/config.yml"] [port]');
  process.exit(1);
}

const host = "127.0.0.1:" + port;
const origin = "http://" + host;
const longMax = "9223372036854775807";
const intMax = "2147483647";
const fields = new Map([
  ["raid-protection.enabled", null],
  ["raid-protection.price", ["0", longMax]],
  ["raid-protection.cooldown-hours", ["1", "8760"]],
  ["warp-portal.cooldown-seconds", ["0", intMax]],
  ["warp-portal.combat-death-timeout-seconds", ["0", intMax]],
  ["village.min-beacon-y", ["-2147483648", intMax]],
  ["village.chunk-price", ["0", longMax]],
  ["village.settlement-price", ["0", longMax]],
  ["export.enabled", null],
]);

class ConfigError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function parseConfig(source) {
  const document = parseDocument(source, { version: "1.1", intAsBigInt: true });
  if (document.errors.length) throw new ConfigError(422, document.errors[0].message.trim());
  if (document.contents !== null && !isMap(document.contents)) {
    throw new ConfigError(422, "The config must be a YAML mapping.");
  }
  return document;
}

function valueAt(document, key, fallback) {
  const path = key.split(".");
  const section = document.get(path[0], true);
  if (section !== undefined && section.value !== null && !isMap(section)) {
    throw new ConfigError(422, `${path[0]} must be a YAML mapping.`);
  }
  const value = document.getIn(path) ?? fallback;
  const type = fields.get(key) ? "bigint" : "boolean";
  if (typeof value !== type) {
    const expected = type === "bigint" ? "a whole number" : "true or false";
    throw new ConfigError(422, `${key} must be ${expected}.`);
  }
  return value;
}

function configuredValue(document, key) {
  // Mining only pays ores listed in the installed configuration.
  const fallback = key.startsWith("mining-payouts.") ? 0n : valueAt(defaults, key);
  return valueAt(document, key, fallback);
}

let file;
let defaults;
try {
  file = await realpath(resolve(fileArgument));
  if (!(await stat(file)).isFile()) throw new Error("Select a YAML file.");
  defaults = parseConfig(await readFile(bundledPath, "utf8"));
  const ores = defaults.get("mining-payouts", true);
  if (!isMap(ores)) throw new Error("The bundled config is missing its mining-payouts section.");
  for (const { key } of ores.items) fields.set("mining-payouts." + key.value, ["0", longMax]);
} catch (error) {
  console.error("Could not open the config editor. " + error.message);
  process.exit(1);
}

function snapshot(source, document = parseConfig(source.toString("utf8"))) {
  return {
    file,
    revision: createHash("sha256").update(source).digest("hex"),
    fields: Array.from(fields, ([key, range]) => {
      const value = configuredValue(document, key);
      return { key, range, value: range ? String(value) : value };
    }),
  };
}

async function readChanges(request) {
  let body = "";
  request.setEncoding("utf8");
  for await (const chunk of request) {
    body += chunk;
    if (body.length > 16384) throw new ConfigError(413, "The changes are too large.");
  }
  let payload;
  try {
    payload = JSON.parse(body);
  } catch {
    throw new ConfigError(400, "Expected a JSON object with settings to save.");
  }
  const { revision, changes } = payload ?? {};
  if (typeof revision !== "string" || !changes || typeof changes !== "object" || Array.isArray(changes)) {
    throw new ConfigError(400, "Expected a file revision and settings to save.");
  }
  const updates = Object.entries(changes).map(([key, value]) => {
    if (!fields.has(key)) throw new ConfigError(400, `Unknown setting ${key}.`);
    const range = fields.get(key);
    if (range && typeof value === "string" && /^-?\d{1,19}$/.test(value)) {
      const integer = BigInt(value);
      if (integer >= BigInt(range[0]) && integer <= BigInt(range[1])) return [key, integer];
    }
    if (!range && typeof value === "boolean") return [key, value];
    throw new ConfigError(400, `Invalid value for ${key}.`);
  });
  return { revision, updates };
}

let writes = Promise.resolve();

async function save({ revision, updates }) {
  const source = await readFile(file);
  const document = parseConfig(source.toString("utf8"));
  const current = snapshot(source, document);
  if (revision !== current.revision) {
    throw new ConfigError(409, "The file changed on disk. Reload it before saving.");
  }
  const changed = updates.filter(([key, value]) => configuredValue(document, key) !== value);
  if (!changed.length) return current;

  for (const [key, value] of changed) {
    const path = key.split(".");
    if (document.get(path[0]) == null) document.set(path[0], document.createNode({}));
    document.setIn(path, value);
  }
  const serialized = document.toString({ lineWidth: 0 });
  const contents = Buffer.from(source.includes("\r\n") ? serialized.replaceAll("\n", "\r\n") : serialized);
  const result = snapshot(contents);
  const temporary = join(dirname(file), `.${basename(file)}.${randomUUID()}.tmp`);
  try {
    const { mode } = await stat(file);
    await writeFile(temporary, contents, { flag: "wx", mode });
    if (!(await readFile(file)).equals(source)) {
      throw new ConfigError(409, "The file changed on disk. Reload it before saving.");
    }
    await rename(temporary, file);
  } finally {
    await unlink(temporary).catch(() => {});
  }
  return result;
}

const assets = new Map([
  ["/", ["index.html", "text/html"]],
  ["/editor.css", ["editor.css", "text/css"]],
  ["/editor.js", ["editor.js", "text/javascript"]],
]);

function json(response, status, data) {
  response.writeHead(status, { "Content-Type": "application/json; charset=utf-8" }).end(JSON.stringify(data));
}

const server = createServer(async (request, response) => {
  response.setHeader("Cache-Control", "no-store");
  response.setHeader("X-Content-Type-Options", "nosniff");
  response.setHeader("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
  if (request.headers.host !== host) return response.writeHead(403).end();
  const route = request.url.split("?")[0];
  try {
    if (route === "/api/config") {
      if (request.method === "GET") return json(response, 200, snapshot(await readFile(file)));
      if (request.method !== "POST") return response.writeHead(405, { Allow: "GET, POST" }).end();
      if (request.headers.origin !== origin || request.headers["content-type"] !== "application/json") {
        return json(response, 403, { error: "Open the local editor to save this file." });
      }
      const changes = await readChanges(request);
      const pending = writes.then(() => save(changes));
      writes = pending.catch(() => {});
      return json(response, 200, await pending);
    }
    const asset = assets.get(route);
    if (!asset) return response.writeHead(404).end();
    if (request.method !== "GET" && request.method !== "HEAD") {
      return response.writeHead(405, { Allow: "GET, HEAD" }).end();
    }
    const [name, type] = asset;
    const contents = await readFile(new URL("./config-editor/" + name, import.meta.url));
    response.writeHead(200, { "Content-Type": type + "; charset=utf-8", "Content-Length": contents.length });
    response.end(request.method === "HEAD" ? undefined : contents);
  } catch (error) {
    if (!(error instanceof ConfigError)) console.error(error);
    json(response, error.status ?? 500, { error: error.status ? error.message : "Could not read or save the config file. Check the terminal for details." });
  }
});

server.on("error", (error) => {
  console.error(error.code === "EADDRINUSE" ? `Port ${port} is already in use.` : error.message);
  process.exitCode = 1;
});
server.listen(port, "127.0.0.1", () => {
  console.log("CivMaker Config Editor · " + origin + "/");
  console.log("Editing " + file);
});
