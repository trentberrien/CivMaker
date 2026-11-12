import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Bridge, validateSnapshot } from '../src/bridge.mjs';
import { loadConfig } from '../src/config.mjs';
import { RoleSync, roleChanges, SerialQueue } from '../src/sync.mjs';
import { StateStore } from '../src/state.mjs';
import { Collection, PermissionFlagsBits, PermissionsBitField } from 'discord.js';

const serverId = '11111111-1111-1111-1111-111111111111';
const factionId = '22222222-2222-2222-2222-222222222222';
const otherFaction = '33333333-3333-3333-3333-333333333333';
const minecraftId = '44444444-4444-4444-4444-444444444444';
const userId = '123456789012345678';
const oldRole = '223456789012345678';
const newRole = '323456789012345678';
const unrelatedRole = '423456789012345678';
const guildId = '523456789012345678';

function snapshot(faction = factionId) {
  return { schemaVersion: 1, serverId, generatedAt: new Date().toISOString(),
    factions: [{ id: factionId, name: 'Ashford', color: 0x5599ff }, { id: otherFaction, name: 'Oak', color: 0x55dd55 }],
    players: [{ minecraftId, minecraftName: 'Alex', discordId: userId, factionId: faction }] };
}

function fixture({ failure = false, missingMember = false } = {}) {
  const changes = [];
  const state = { schemaVersion: 1, guildId, serverId, roles: { [factionId]: oldRole, [otherFaction]: newRole }, trackedUsers: [userId] };
  const current = new Set([oldRole, unrelatedRole]);
  const roles = new Map([
    [oldRole, { id: oldRole, name: 'Civ | Ashford', colors: { primaryColor: 0x5599ff }, permissions: { bitfield: 0n }, editable: true }],
    [newRole, { id: newRole, name: 'Civ | Oak', colors: { primaryColor: 0x55dd55 }, permissions: { bitfield: 0n }, editable: true }],
  ]);
  const member = { roles: { cache: current,
    async remove(id) { changes.push(['remove', id]); if (failure) throw new Error('Discord temporarily unavailable'); current.delete(id); },
    async add(id) { changes.push(['add', id]); current.add(id); },
  } };
  const channels = new Map();
  let nextChannel = 623456789012345678n;
  const guild = { id: guildId, channels: { cache: channels, async fetch() {}, async create(options) {
    const channel = { id: String(nextChannel++), type: options.type, permissionOverwrites: {
      cache: new Collection(options.permissionOverwrites.map(overwrite => [overwrite.id, { ...overwrite,
        allow: new PermissionsBitField(overwrite.allow ?? []), deny: new PermissionsBitField(overwrite.deny ?? []) }])),
    } };
    channels.set(channel.id, channel);
    return channel;
  } }, members: {
    async fetchMe() { return { id: '723456789012345678', permissions: { has: () => true } }; },
    async fetch() { if (missingMember) throw Object.assign(new Error('Unknown Member'), { code: 10007 }); return member; },
  }, roles: { cache: roles, async fetch() {} } };
  const saved = [];
  const store = { async save(value) { saved.push(structuredClone(value)); } };
  const sync = new RoleSync(guild, state, store, { rolePrefix: 'Civ | ' }, { warn() {} });
  return { changes, state, current, saved, sync, roles, guild, channels };
}

test('faction transfer removes only the prior managed role, then adds the new role', async () => {
  const f = fixture();
  assert.equal((await f.sync.apply(snapshot(otherFaction))).errors, 0);
  assert.deepEqual(f.changes, [['remove', oldRole], ['add', newRole]]);
  assert.ok(f.current.has(unrelatedRole));
  assert.deepEqual((await f.sync.apply(snapshot(otherFaction))).updated, 0);
  assert.equal(f.changes.length, 2);
});

test('leaving, disbanding and unlinking remove faction roles without touching other roles', async () => {
  for (const kind of ['leave', 'disband', 'unlink']) {
    const f = fixture();
    const data = snapshot(null);
    if (kind === 'disband') data.factions = [];
    if (kind === 'unlink') data.players = [];
    await f.sync.apply(data);
    assert.deepEqual(f.changes, [['remove', oldRole]]);
    assert.ok(f.current.has(unrelatedRole));
    assert.equal(f.state.trackedUsers.includes(userId), kind !== 'unlink');
  }
});

test('disbanding retains faction channels and roles while removing player membership', async () => {
  const f = fixture();
  await f.sync.apply(snapshot());
  const original = structuredClone(f.state.channels);
  const originalRoles = structuredClone(f.state.roles);
  const data = snapshot(null);
  data.factions = [];
  await f.sync.apply(data);
  assert.deepEqual(f.state.channels, original);
  assert.deepEqual(f.state.roles, originalRoles);
  assert.equal(f.channels.size, 4);
  assert.ok(!f.current.has(oldRole));
});

test('a channel permission failure cannot prevent faction role cleanup', async () => {
  const f = fixture();
  f.guild.members.fetchMe = async () => ({ id: '723456789012345678', permissions: {
    has: permission => permission === PermissionFlagsBits.ManageRoles,
  } });
  const result = await f.sync.apply(snapshot(null));
  assert.ok(result.errors > 0);
  assert.deepEqual(f.changes, [['remove', oldRole]]);
  assert.equal(f.channels.size, 0);
});

test('failed removals remain tracked for retry and never grant a second faction', async () => {
  const f = fixture({ failure: true });
  const result = await f.sync.apply(snapshot(otherFaction));
  assert.equal(result.errors, 1);
  assert.deepEqual(f.changes, [['remove', oldRole]]);
  assert.deepEqual(f.state.trackedUsers, [userId]);
});

test('a linked user outside Discord stays tracked for their return', async () => {
  const f = fixture({ missingMember: true });
  await f.sync.apply(snapshot());
  assert.deepEqual(f.state.trackedUsers, [userId]);
  const unlinked = snapshot();
  unlinked.players = [];
  await f.sync.apply(unlinked);
  assert.deepEqual(f.state.trackedUsers, []);
});

test('roles with administrator-added permissions cannot be assigned', async () => {
  const f = fixture();
  f.roles.get(newRole).permissions.bitfield = 8n;
  assert.ok((await f.sync.apply(snapshot(otherFaction))).errors > 0);
  assert.ok(!f.changes.some(([action, role]) => action === 'add' && role === newRole));
});

test('temporary role metadata errors preserve an already-correct faction role', async () => {
  const f = fixture();
  f.roles.get(oldRole).name = 'Old faction name';
  f.roles.get(oldRole).edit = async () => { throw new Error('Discord temporarily unavailable'); };
  assert.ok((await f.sync.apply(snapshot())).errors > 0);
  assert.deepEqual(f.changes, []);
});

test('new faction roles are recorded before membership is granted', async () => {
  const f = fixture();
  delete f.state.roles[factionId];
  f.roles.delete(oldRole);
  f.current.delete(oldRole);
  f.guild.roles.create = async options => {
    assert.deepEqual(options.permissions, []);
    const role = { id: oldRole, name: options.name, colors: options.colors, permissions: { bitfield: 0n }, editable: true };
    f.roles.set(role.id, role);
    return role;
  };
  await f.sync.apply(snapshot());
  assert.equal(f.state.roles[factionId], oldRole);
  assert.ok(f.saved.some(value => value.roles[factionId] === oldRole && value.trackedUsers.includes(userId)));
  assert.deepEqual(f.changes, [['add', oldRole]]);
});

test('failure to persist a new Discord role stops membership changes', async () => {
  const f = fixture();
  delete f.state.roles[factionId];
  f.guild.roles.create = async options => ({ id: oldRole, name: options.name, colors: options.colors,
    permissions: { bitfield: 0n }, editable: true });
  let writes = 0;
  f.sync.store.save = async () => { if (++writes > 1) throw new Error('Disk full'); };
  await assert.rejects(f.sync.apply(snapshot()), /Could not save/);
  assert.deepEqual(f.changes, []);
});

test('stale, malformed and wrong-server snapshots cause no Discord changes', async () => {
  for (const corrupt of [
    value => { value.generatedAt = '2020-01-01T00:00:00Z'; },
    value => { value.players.push(value.players[0]); },
    value => { value.factions = []; },
    value => { value.serverId = minecraftId; },
    value => { value.factions[0].color = -1; },
  ]) {
    const f = fixture();
    const data = snapshot();
    corrupt(data);
    await assert.rejects(f.sync.apply(data));
    assert.deepEqual(f.changes, []);
    assert.equal(f.saved.length, 0);
  }
});

test('snapshot validation accepts unaffiliated players and rejects duplicate Minecraft links', () => {
  assert.equal(validateSnapshot(snapshot(null)).players.length, 1);
  const data = snapshot();
  data.players.push({ ...data.players[0], discordId: oldRole });
  assert.throws(() => validateSnapshot(data));
});

test('state round-trips atomically and refuses corruption or a different guild', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'civmaker-discord-'));
  const store = new StateStore(directory, guildId);
  const state = await store.load();
  state.serverId = serverId;
  state.roles[factionId] = oldRole;
  state.trackedUsers = [userId];
  await store.save(state);
  await store.save(state);
  assert.deepEqual(await store.load(), state);
  assert.ok((await readFile(store.file, 'utf8')).endsWith('\n'));
  await assert.rejects(new StateStore(directory, userId).load());
  await writeFile(store.file, '{broken');
  await assert.rejects(store.load());
});

test('old role-only state upgrades and saved channel IDs survive restarts', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'civmaker-channel-state-'));
  const store = new StateStore(directory, guildId);
  await store.save({ schemaVersion: 1, guildId, serverId, roles: { [factionId]: oldRole }, trackedUsers: [userId] });
  const upgraded = await store.load();
  assert.deepEqual(upgraded.channels, {});
  assert.deepEqual(upgraded.raidAlertsSent, {});
  upgraded.channels[factionId] = { text: '623456789012345678', voice: '723456789012345678' };
  await store.save(upgraded);
  assert.deepEqual((await store.load()).channels, upgraded.channels);
  upgraded.channels[otherFaction] = { text: upgraded.channels[factionId].text };
  await store.save(upgraded);
  await assert.rejects(store.load(), /Invalid saved faction channels/);
});

test('serialized operations preserve order and recover from an error', async () => {
  const queue = new SerialQueue();
  const order = [];
  const first = queue.run(async () => { order.push(1); throw new Error('retry'); });
  const second = queue.run(async () => { order.push(2); });
  await assert.rejects(first);
  await second;
  assert.deepEqual(order, [1, 2]);
});

test('role plan never removes unrelated roles', () => {
  assert.deepEqual(roleChanges([oldRole, unrelatedRole], [oldRole, newRole], newRole), { remove: [oldRole], add: [newRole] });
});

test('bridge sends credentials only to loopback and uses Discord-issued account ID', async () => {
  const config = loadConfig({ DISCORD_TOKEN: 'test', DISCORD_GUILD_ID: guildId, CIVMAKER_BRIDGE_TOKEN: 'a'.repeat(32) });
  let request;
  const bridge = new Bridge(config, async (url, options) => {
    request = { url, options };
    return { ok: true, json: async () => ({ minecraftName: 'Alex' }) };
  });
  await bridge.link('ABCD-ABCD-ABCD-ABCD', userId);
  assert.equal(request.url, 'http://127.0.0.1:8790/v1/link');
  assert.equal(request.options.redirect, 'error');
  assert.equal(JSON.parse(request.options.body).discordId, userId);
  assert.throws(() => loadConfig({ DISCORD_TOKEN: 'test', DISCORD_GUILD_ID: guildId,
    CIVMAKER_BRIDGE_TOKEN: 'a'.repeat(32), CIVMAKER_BRIDGE_URL: 'https://example.com' }));
});

test('bridge outages and authentication failures leave account data untouched', async () => {
  const config = { bridgeUrl: 'http://127.0.0.1:8790', bridgeToken: 'test' };
  await assert.rejects(new Bridge(config, async () => { throw new Error('connection refused'); }).snapshot(), /unavailable/);
  await assert.rejects(new Bridge(config, async () => ({ ok: false, status: 401, json: async () => ({}) })).snapshot(), /authentication/);
});
