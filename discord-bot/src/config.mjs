import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const botDirectory = fileURLToPath(new URL('../', import.meta.url));

export function loadConfig(env = process.env) {
  const required = name => {
    const value = env[name]?.trim();
    if (!value) throw new Error(`Set ${name} in discord-bot/.env first.`);
    return value;
  };
  const token = required('DISCORD_TOKEN');
  const mode = (env.DISCORD_ONLINE_ONLY || 'false').trim().toLowerCase();
  if (!['true', 'false'].includes(mode)) throw new Error('DISCORD_ONLINE_ONLY must be true or false.');
  const onlineOnly = mode === 'true';
  const guildId = env.DISCORD_GUILD_ID?.trim() || '';
  const guildName = env.DISCORD_GUILD_NAME?.trim() || '';
  if (guildId && !/^\d{17,20}$/.test(guildId)) throw new Error('DISCORD_GUILD_ID must be a Discord server ID.');
  if (!guildId && !guildName) throw new Error('Set DISCORD_GUILD_ID or DISCORD_GUILD_NAME in discord-bot/.env.');
  const bridgeToken = env.CIVMAKER_BRIDGE_TOKEN?.trim() || '';
  if (!onlineOnly && (bridgeToken.length < 32 || bridgeToken.includes('CHANGE_ME'))) {
    throw new Error('CIVMAKER_BRIDGE_TOKEN must match the random bridge secret (at least 32 characters).');
  }
  const url = new URL(env.CIVMAKER_BRIDGE_URL || 'http://127.0.0.1:8790');
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || url.username || url.password
      || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('CIVMAKER_BRIDGE_URL must be http://127.0.0.1:<port>. Run the bot on the Minecraft server computer.');
  }
  const interval = Number(env.SYNC_INTERVAL_SECONDS || 30);
  if (!Number.isInteger(interval) || interval < 10 || interval > 3600) {
    throw new Error('SYNC_INTERVAL_SECONDS must be a whole number from 10 to 3600.');
  }
  const prefix = env.ROLE_PREFIX?.trim() || 'Civ |';
  if (prefix.length > 40) throw new Error('ROLE_PREFIX must be at most 40 characters.');
  return { token, guildId, guildName, onlineOnly, bridgeToken, bridgeUrl: url.origin, intervalMs: interval * 1000,
    rolePrefix: `${prefix} `, dataDirectory: resolve(botDirectory, env.DATA_DIRECTORY || './data') };
}
