const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const snowflake = /^\d{17,20}$/;

export class BridgeError extends Error {}

export function validateSnapshot(value, now = Date.now()) {
  const invalid = () => { throw new BridgeError('Minecraft returned an invalid snapshot; roles were left alone.'); };
  if (!value || value.schemaVersion !== 1 || !uuid.test(value.serverId)
      || !Array.isArray(value.factions) || !Array.isArray(value.players)) invalid();
  const generated = Date.parse(value.generatedAt);
  if (!Number.isFinite(generated) || now - generated > 120_000 || generated - now > 60_000) {
    throw new BridgeError('Minecraft faction data is stale; roles were left alone. Check Paper is running normally.');
  }
  const factions = new Set();
  for (const faction of value.factions) {
    if (!faction || !uuid.test(faction.id) || factions.has(faction.id)
        || typeof faction.name !== 'string' || !faction.name.trim()
        || !Number.isInteger(faction.color) || faction.color < 0 || faction.color > 0xffffff) invalid();
    factions.add(faction.id);
  }
  const discordIds = new Set();
  const minecraftIds = new Set();
  for (const player of value.players) {
    if (!player || !uuid.test(player.minecraftId) || !snowflake.test(player.discordId)
        || typeof player.minecraftName !== 'string' || !player.minecraftName
        || discordIds.has(player.discordId) || minecraftIds.has(player.minecraftId)
        || player.factionId != null && !factions.has(player.factionId)) invalid();
    discordIds.add(player.discordId);
    minecraftIds.add(player.minecraftId);
  }
  if (value.raidAlerts !== undefined) {
    if (!Array.isArray(value.raidAlerts) || value.raidAlerts.length > 1024) invalid();
    const ids = new Set();
    for (const alert of value.raidAlerts) {
      if (!alert || !uuid.test(alert.id) || ids.has(alert.id) || !uuid.test(alert.factionId)
          || !['settlement_capture', 'lockpick', 'battering_ram', 'c4', 'grenade', 'block_break', 'combat'].includes(alert.kind)
          || !['factionName', 'attacker', 'world'].every(key => typeof alert[key] === 'string')
          || !['x', 'y', 'z'].every(key => Number.isSafeInteger(alert[key]))
          || !Number.isFinite(Date.parse(alert.createdAt)) || Date.parse(alert.createdAt) - now > 60_000) invalid();
      ids.add(alert.id);
    }
  }
  return value;
}

export class Bridge {
  constructor(config, fetchImpl = fetch) {
    this.config = config;
    this.fetch = fetchImpl;
  }

  async request(path, body) {
    let response;
    try {
      response = await this.fetch(`${this.config.bridgeUrl}/v1/${path}`, {
        method: body ? 'POST' : 'GET',
        headers: { Authorization: `Bearer ${this.config.bridgeToken}`, 'Content-Type': 'application/json' },
        body: body ? JSON.stringify(body) : undefined,
        signal: AbortSignal.timeout(8000),
        redirect: 'error',
      });
    } catch {
      throw new BridgeError('Minecraft bridge is unavailable. Check Paper, discord.enabled, and the local bridge port.');
    }
    let value;
    try { value = await response.json(); }
    catch { throw new BridgeError('Minecraft bridge returned an unreadable response.'); }
    if (!response.ok) {
      if (response.status === 401) throw new BridgeError('Minecraft bridge authentication failed. The bridge secrets must match.');
      throw new BridgeError(value?.error || `Minecraft bridge request failed (${response.status}).`);
    }
    return value;
  }

  async snapshot() { return validateSnapshot(await this.request('snapshot')); }
  link(code, discordId) { return this.request('link', { code, discordId }); }
  unlink(discordId) { return this.request('unlink', { discordId }); }
  async acknowledgeRaids(ids) {
    for (let offset = 0; offset < ids.length; offset += 100) {
      await this.request('raid-alerts/ack', { ids: ids.slice(offset, offset + 100) });
    }
  }
}
