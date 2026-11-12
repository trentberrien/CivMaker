const descriptions = {
  "raid-protection.enabled": ["Raid protection", "Allow villagers to buy and activate the shield."],
  "raid-protection.price": ["Shield price", "Cost to buy raid protection.", "$"],
  "raid-protection.cooldown-hours": ["Shield cooldown", "Applies separately to buying and using a shield.", "h"],
  "warp-portal.cooldown-seconds": ["Warp cooldown", "Wait after using a warp portal.", "s", "60"],
  "warp-portal.combat-death-timeout-seconds": ["Combat death timeout", "Wait after dying in combat.", "s", "60"],
  "village.min-beacon-y": ["Minimum beacon height", "Lowest Y-coordinate for a founding beacon.", "Y"],
  "village.chunk-price": ["Chunk price", "Cost of one capital claim.", "$"],
  "village.settlement-price": ["Settlement price", "Cost of buying a natural village.", "$"],
  "village.invite-as-elder": ["Invite players as elders", "New invitees join as elders until their owner demotes them. Turn off to give new invitees ordinary membership."],
  "export.enabled": ["Publish Atlas updates", "Write updates to public-v1.json."],
};

const form = document.querySelector("#editor");
const fieldset = document.querySelector("#fields");
const save = document.querySelector("#save");
const reload = document.querySelector("#reload");
const status = document.querySelector("#status");
const filter = document.querySelector("#ore-filter");
const sections = new Map(Array.from(document.querySelectorAll("[data-section]"), node => [node.dataset.section, node]));
const nav = Array.from(document.querySelectorAll(".sidebar nav a"));
const settings = new Map();
let revision;
let busy = false;

function showStatus(message, tone = "neutral") {
  status.textContent = message;
  status.dataset.tone = tone;
}

function inputWithType(type, id) {
  const input = document.createElement("input");
  input.type = type;
  input.id = id;
  return input;
}

function switchInput(id) {
  const input = inputWithType("checkbox", id);
  input.className = "switch";
  input.setAttribute("role", "switch");
  return input;
}

function makeSetting(field) {
  const [section, name] = field.key.split(".");
  const oreName = name.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, letter => letter.toUpperCase());
  const [title, description, unit, timerDefault] = descriptions[field.key] ?? [oreName, "", "$"];
  const row = document.createElement("div");
  row.className = "setting";
  if (section === "mining-payouts") {
    row.dataset.ore = name.startsWith("DEEPSLATE_") ? "deepslate"
      : name.startsWith("NETHER_") || name === "ANCIENT_DEBRIS" ? "nether" : "overworld";
  }

  const id = "setting-" + section + "-" + name;
  const caption = document.createElement("div");
  const label = document.createElement("label");
  label.htmlFor = id;
  label.textContent = title;
  caption.append(label);
  if (description) {
    const help = document.createElement("small");
    help.textContent = description;
    help.id = id + "-help";
    caption.append(help);
  }
  const error = document.createElement("small");
  error.id = id + "-error";
  error.className = "field-error";
  error.hidden = true;
  caption.append(error);
  const controls = document.createElement("div");
  controls.className = "controls";
  const input = field.range ? inputWithType("number", id) : switchInput(id);
  input.setAttribute("aria-describedby", (description ? id + "-help " : "") + error.id);

  if (field.range) {
    const number = document.createElement("div");
    number.className = unit === "$" ? "number currency" : "number";
    input.value = field.value;
    input.step = "1";
    [input.min, input.max] = field.range;
    const suffix = document.createElement("span");
    suffix.textContent = unit;
    suffix.setAttribute("aria-hidden", "true");
    number.append(input, suffix);
    controls.append(number);
  } else {
    input.checked = field.value;
    controls.append(input);
  }

  if (timerDefault) {
    const toggle = switchInput(id + "-toggle");
    toggle.setAttribute("aria-label", title + " enabled");
    toggle.checked = BigInt(field.value) > 0n;
    input.disabled = !toggle.checked;
    let lastDuration = toggle.checked ? field.value : timerDefault;
    toggle.addEventListener("change", () => {
      if (!toggle.checked && /^\d+$/.test(input.value) && BigInt(input.value) > 0n &&
          BigInt(input.value) <= BigInt(field.range[1])) lastDuration = input.value;
      input.disabled = !toggle.checked;
      input.value = toggle.checked ? lastDuration : "0";
      refresh();
    });
    input.addEventListener("blur", () => {
      if (toggle.checked && wholeNumber(input.value) === 0n) {
        toggle.checked = false;
        input.disabled = true;
      }
    });
    controls.prepend(toggle);
  }

  if (field.key === "raid-protection.cooldown-hours") {
    const preset = document.querySelector("#cooldown-presets").content.cloneNode(true);
    const select = preset.querySelector("select");
    select.addEventListener("change", () => {
      if (select.value) input.value = select.value;
      select.value = "";
      refresh();
    });
    controls.prepend(preset);
  }

  row.append(caption, controls);
  sections.get(section).append(row);
  return { ...field, input, row, error, title };
}

function wholeNumber(value) {
  return /^-?\d+$/.test(value) ? BigInt(value) : null;
}

function collect() {
  const changes = {};
  let invalid = 0;
  for (const setting of settings.values()) {
    const { key, range, input, error, value } = setting;
    const integer = range ? wholeNumber(input.value) : null;
    const draft = range ? integer?.toString() : input.checked;
    const invalidValue = draft !== value && range && (
      integer === null || integer < BigInt(range[0]) || integer > BigInt(range[1])
    );
    input.setAttribute("aria-invalid", invalidValue ? "true" : "false");
    error.hidden = !invalidValue;
    if (invalidValue) {
      error.textContent = integer === null ? "Enter a whole number." : `Use ${range[0]} to ${range[1]}.`;
      invalid++;
    } else if (draft !== value) {
      changes[key] = draft;
    }
  }
  return { changes, invalid, count: Object.keys(changes).length };
}

function filterOres() {
  for (const row of sections.get("mining-payouts").children) {
    row.hidden = filter.value !== "all" && row.dataset.ore !== filter.value &&
      !row.querySelector('[aria-invalid="true"]');
  }
}

function refresh(message, tone) {
  const { count, invalid } = collect();
  fieldset.disabled = busy || !revision;
  reload.disabled = busy;
  save.disabled = busy || invalid > 0 || count === 0;
  filterOres();
  if (message) showStatus(message, tone);
  else if (invalid) showStatus("Fix the highlighted settings before saving.", "error");
  else if (count) showStatus(`${count} unsaved ${count === 1 ? "change" : "changes"}`, "pending");
  else showStatus("All changes saved");
}

function render(data) {
  revision = data.revision;
  document.querySelector("#filename").textContent = data.file.split(/[\\/]/).pop();
  document.querySelector("#filepath").textContent = data.file;
  for (const section of sections.values()) section.replaceChildren();
  settings.clear();
  for (const field of data.fields) settings.set(field.key, makeSetting(field));
}

async function request(options) {
  const response = await fetch("/api/config", options);
  const data = await response.json();
  if (!response.ok) throw new Error(data.error ?? "Could not open the config file.");
  return data;
}

async function load() {
  const { count, invalid } = collect();
  if ((count || invalid) && !window.confirm("Reload the file and discard your unsaved changes?")) return;
  busy = true;
  refresh("Loading config…");
  try {
    render(await request());
    busy = false;
    refresh("File loaded", "success");
  } catch (error) {
    busy = false;
    refresh(error.message, "error");
  }
}

form.addEventListener("input", () => refresh());
form.addEventListener("submit", async event => {
  event.preventDefault();
  const { changes, invalid, count } = collect();
  if (busy || invalid || !count) return;
  busy = true;
  refresh("Saving…");
  try {
    const data = await request({
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ revision, changes }),
    });
    render(data);
    busy = false;
    refresh("Saved to disk", "success");
  } catch (error) {
    busy = false;
    refresh(error.message, "error");
  }
});

function markCurrentSection() {
  for (const link of nav) {
    if (link.hash === location.hash) link.setAttribute("aria-current", "location");
    else link.removeAttribute("aria-current");
  }
}

filter.addEventListener("change", filterOres);
reload.addEventListener("click", load);
window.addEventListener("hashchange", markCurrentSection);
markCurrentSection();
load();
