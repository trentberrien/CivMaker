import test from 'node:test';
import assert from 'node:assert/strict';
import { ChannelType, Collection, PermissionFlagsBits as P, PermissionsBitField } from 'discord.js';
import { FactionChannels, factionOverwrites, hasFactionPrivacy, textChannelName } from '../src/channels.mjs';

const factionId = '22222222-2222-2222-2222-222222222222';
const guildId = '123456789012345678';
const roleId = '223456789012345678';
const botId = '323456789012345678';
const faction = { id: factionId, name: 'Ashford' };
export function overwriteCache(overwrites) {
  return new Collection(overwrites.map(value => [value.id, { ...value,
    allow: new PermissionsBitField(value.allow ?? []), deny: new PermissionsBitField(value.deny ?? []) }]));
}

function fixture() {
  const state = { channels: {} };
  const created = [], changed = [], saved = [];
  const cache = new Map();
  let id = 423456789012345678n;
  const guild = { id: guildId, channels: { cache, async fetch() {}, async create(options) {
    created.push(options);
    const overwrites = { cache: overwriteCache(options.permissionOverwrites), async set(values) {
      changed.push(values); this.cache = overwriteCache(values);
    } };
    const channel = { id: String(id++), type: options.type, permissionOverwrites: overwrites };
    cache.set(channel.id, channel);
    return channel;
  } } };
  const store = { async save(value) { saved.push(structuredClone(value)); } };
  const me = { id: botId, permissions: { has: () => true } };
  return { state, created, changed, saved, cache, store, guild, me, sync: new FactionChannels(guild, state, store) };
}

test('new faction gets one text and one voice channel, private from the creation request', async () => {
  const f = fixture();
  await f.sync.refresh(f.me);
  const pair = await f.sync.ensure(faction, { id: roleId }, f.me);
  assert.equal(f.created.length, 2);
  assert.deepEqual(f.created.map(value => value.type), [ChannelType.GuildText, ChannelType.GuildVoice]);
  assert.equal(f.created[0].name, 'faction-ashford');
  assert.equal(f.created[1].name, 'Ashford Voice');
  for (const [kind, channelId] of Object.entries(pair)) {
    const channel = f.cache.get(channelId);
    assert.ok(hasFactionPrivacy(channel, guildId, roleId, botId, kind === 'voice'));
    assert.ok(channel.permissionOverwrites.cache.get(guildId).deny.has(P.ViewChannel));
    assert.equal(channel.permissionOverwrites.cache.size, 3);
  }
  await f.sync.ensure(faction, { id: roleId }, f.me);
  assert.equal(f.created.length, 2);
  assert.equal(f.changed.length, 0);
  assert.equal(f.saved.length, 2);
});

test('deleted roles and accidental outsider grants are repaired without new channels', async () => {
  const f = fixture();
  const pair = await f.sync.ensure(faction, { id: roleId }, f.me);
  f.cache.get(pair.text).permissionOverwrites.cache.set('outsider', { id: 'outsider', type: 0, allow: new PermissionsBitField(P.ViewChannel), deny: new PermissionsBitField() });
  const replacementRole = '523456789012345678';
  await f.sync.ensure(faction, { id: replacementRole }, f.me);
  assert.equal(f.created.length, 2);
  assert.equal(f.changed.length, 2);
  assert.ok(hasFactionPrivacy(f.cache.get(pair.text), guildId, replacementRole, botId));
  assert.ok(!f.cache.get(pair.text).permissionOverwrites.cache.has(roleId));
});

test('a new faction with a reused name gets different private channels', async () => {
  const f = fixture();
  const oldPair = await f.sync.ensure(faction, { id: roleId }, f.me);
  const newPair = await f.sync.ensure({ ...faction, id: '33333333-3333-3333-3333-333333333333' }, { id: '523456789012345678' }, f.me);
  assert.notEqual(oldPair.text, newPair.text);
  assert.notEqual(oldPair.voice, newPair.voice);
  assert.equal(f.cache.size, 4);
});

test('a partial channel creation resumes without recreating the saved text channel', async () => {
  const f = fixture();
  const create = f.guild.channels.create;
  let failVoice = true;
  f.guild.channels.create = async options => {
    if (options.type === ChannelType.GuildVoice && failVoice) throw new Error('Discord unavailable');
    return create(options);
  };
  await assert.rejects(f.sync.ensure(faction, { id: roleId }, f.me));
  assert.ok(f.state.channels[factionId].text);
  failVoice = false;
  await f.sync.ensure(faction, { id: roleId }, f.me);
  assert.equal(f.created.length, 2);
});

test('voice overwrites allow speaking and joining only to the faction role', () => {
  const overwrites = overwriteCache(factionOverwrites(guildId, roleId, botId, true));
  assert.ok(overwrites.get(guildId).deny.has([P.ViewChannel, P.Connect]));
  assert.ok(overwrites.get(roleId).allow.has([P.ViewChannel, P.Connect, P.Speak, P.UseVAD]));
  assert.ok(!overwrites.has('outsider'));
  assert.match(textChannelName('!!!', factionId), /^faction-22222222$/);
});
