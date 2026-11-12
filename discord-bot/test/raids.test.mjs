import test from 'node:test';
import assert from 'node:assert/strict';
import { ChannelType, Collection, PermissionsBitField } from 'discord.js';
import { RaidNotifications } from '../src/raids.mjs';
import { factionOverwrites } from '../src/channels.mjs';
import { Bridge, validateSnapshot } from '../src/bridge.mjs';

const factionId = '22222222-2222-2222-2222-222222222222';
const alertId = '33333333-3333-3333-3333-333333333333';
const guildId = '123456789012345678';
const roleId = '223456789012345678';
const botId = '323456789012345678';
const channelId = '423456789012345678';

function fixture() {
  const sent = [], saved = [];
  const state = { roles: { [factionId]: roleId }, channels: { [factionId]: { text: channelId } } };
  const me = { id: botId };
  const cache = new Collection(factionOverwrites(guildId, roleId, botId).map(value => [value.id,
    { ...value, allow: new PermissionsBitField(value.allow ?? []), deny: new PermissionsBitField(value.deny ?? []) }]));
  const channel = { id: channelId, type: ChannelType.GuildText, permissionOverwrites: { cache },
    permissionsFor: () => ({ has: () => true }), async send(options) { sent.push(options); } };
  const guild = { id: guildId, members: { me }, channels: { async fetch(id, options) {
    assert.equal(id, channelId); assert.equal(options.force, true); return channel;
  } } };
  const store = { async save(value) { saved.push(structuredClone(value)); } };
  const alert = { id: alertId, factionId, factionName: 'Ashford', kind: 'lockpick', attacker: 'Raider @everyone',
    world: 'world', x: -25, y: 70, z: 30, createdAt: new Date().toISOString() };
  const snapshot = { schemaVersion: 1, serverId: '11111111-1111-1111-1111-111111111111', generatedAt: new Date().toISOString(),
    factions: [{ id: factionId, name: 'Ashford', color: 123 }], players: [], raidAlerts: [alert] };
  const notifications = new RaidNotifications(guild, state, store, { warn() {} });
  return { sent, saved, state, me, channel, guild, store, alert, snapshot, notifications };
}

test('raid alert pings only the attacked faction in its verified private text channel', async () => {
  const f = fixture();
  const result = await f.notifications.apply(f.snapshot);
  assert.equal(result.sent, 1);
  assert.deepEqual(result.acknowledge, [alertId]);
  assert.equal(f.sent.length, 1);
  assert.deepEqual(f.sent[0].allowedMentions, { parse: [], roles: [roleId], users: [], repliedUser: false });
  assert.match(f.sent[0].content, /\(-25, 70, 30\)/);
  assert.ok(f.sent[0].enforceNonce);
  assert.equal(f.sent[0].nonce.length, 25);
  assert.ok(f.saved[0].raidAlertsSent[alertId]);
});

test('a lost Minecraft acknowledgement or bot restart does not send a second faction ping', async () => {
  const f = fixture();
  await f.notifications.apply(f.snapshot);
  const reloaded = new RaidNotifications(f.guild, structuredClone(f.saved[0]), f.store, { warn() {} });
  const result = await reloaded.apply(f.snapshot);
  assert.equal(result.sent, 0);
  assert.deepEqual(result.acknowledge, [alertId]);
  assert.equal(f.sent.length, 1);
});

test('a Discord failure stays pending for retry', async () => {
  const f = fixture();
  f.channel.send = async () => { throw new Error('Discord unavailable'); };
  const result = await f.notifications.apply(f.snapshot);
  assert.equal(result.errors, 1);
  assert.deepEqual(result.acknowledge, []);
  assert.deepEqual(f.state.raidAlertsSent, {});
});

test('raid coordinates are not sent if the channel gained public or outsider access', async () => {
  for (const kind of ['public', 'outsider']) {
    const f = fixture();
    if (kind === 'public') f.channel.permissionOverwrites.cache.delete(guildId);
    else f.channel.permissionOverwrites.cache.set('outsider', { id: 'outsider', type: 0, allow: new PermissionsBitField(1024n), deny: new PermissionsBitField() });
    const result = await f.notifications.apply(f.snapshot);
    assert.equal(result.errors, 1);
    assert.equal(f.sent.length, 0);
    assert.deepEqual(result.acknowledge, []);
  }
});

test('disbanded factions and expired alerts are acknowledged without messaging retained channels', async () => {
  for (const kind of ['disbanded', 'expired']) {
    const f = fixture();
    if (kind === 'disbanded') f.snapshot.factions = [];
    else f.alert.createdAt = '2020-01-01T00:00:00Z';
    const result = await f.notifications.apply(f.snapshot);
    assert.equal(result.sent, 0);
    assert.deepEqual(result.acknowledge, [alertId]);
    assert.equal(f.sent.length, 0);
  }
});

test('failed delivery-state writes never acknowledge an alert and keep a stable retry nonce', async () => {
  const f = fixture();
  f.store.save = async () => { throw new Error('Disk full'); };
  await assert.rejects(f.notifications.apply(f.snapshot), /Could not save/);
  assert.deepEqual(f.state.raidAlertsSent, {});
  f.store.save = async () => {};
  await f.notifications.apply(f.snapshot);
  assert.equal(f.sent[0].nonce, f.sent[1].nonce);
});

test('malformed raid data is rejected before any Discord changes', () => {
  for (const corrupt of [
    f => { f.alert.kind = 'unknown'; },
    f => { f.alert.x = 'secret'; },
    f => { f.snapshot.raidAlerts.push(f.alert); },
    f => { f.alert.createdAt = 'invalid'; },
  ]) {
    const f = fixture();
    corrupt(f);
    assert.throws(() => validateSnapshot(f.snapshot));
  }
  assert.doesNotThrow(() => validateSnapshot(fixture().snapshot));
});

test('large alert acknowledgements are batched below the local API request limit', async () => {
  const bodies = [];
  const bridge = new Bridge({ bridgeUrl: 'http://127.0.0.1:8790', bridgeToken: 'test' }, async (_url, options) => {
    assert.ok(Buffer.byteLength(options.body) < 4096);
    bodies.push(JSON.parse(options.body));
    return { ok: true, json: async () => ({ ok: true }) };
  });
  await bridge.acknowledgeRaids(Array.from({ length: 205 }, () => alertId));
  assert.deepEqual(bodies.map(body => body.ids.length), [100, 100, 5]);
});
