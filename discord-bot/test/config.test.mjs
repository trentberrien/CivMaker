import test from 'node:test';
import assert from 'node:assert/strict';
import { loadConfig } from '../src/config.mjs';
import { selectGuildId } from '../src/guild.mjs';

const serverId = '123456789012345678';
const base = { DISCORD_TOKEN: 'test-token', DISCORD_GUILD_NAME: 'CivMaker', DISCORD_ONLINE_ONLY: 'true' };

test('temporary Discord hosting accepts a token and server name without Minecraft credentials', () => {
  const config = loadConfig(base);
  assert.equal(config.onlineOnly, true);
  assert.equal(config.guildName, 'CivMaker');
  assert.equal(config.guildId, '');
  assert.equal(config.bridgeToken, '');
});

test('full syncing still requires a bridge secret and a configured Discord server', () => {
  assert.throws(() => loadConfig({ ...base, DISCORD_ONLINE_ONLY: 'false' }), /bridge secret/);
  assert.throws(() => loadConfig({ ...base, DISCORD_GUILD_NAME: '' }), /DISCORD_GUILD_ID/);
  assert.throws(() => loadConfig({ ...base, DISCORD_ONLINE_ONLY: 'maybe' }), /true or false/);
});

test('exact server discovery works and never falls back to an unrelated or ambiguous server', () => {
  const config = loadConfig(base);
  assert.equal(selectGuildId(config, [{ id: serverId, name: 'CivMaker' }, { id: 'other', name: 'Elsewhere' }]), serverId);
  assert.throws(() => selectGuildId(config, [{ id: 'other', name: 'Elsewhere' }]), /has not joined/);
  assert.throws(() => selectGuildId(config, [{ id: serverId, name: 'CivMaker' }, { id: 'other', name: 'CivMaker' }]), /Multiple/);
  assert.equal(selectGuildId({ ...config, guildId: serverId }, []), serverId);
});
