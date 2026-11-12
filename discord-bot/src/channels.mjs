import { ChannelType, OverwriteType, PermissionFlagsBits as P, PermissionsBitField } from 'discord.js';
import { StateWriteError } from './state.mjs';

export function factionOverwrites(guildId, roleId, botId, voice = false) {
  return [
    { id: guildId, type: OverwriteType.Role, deny: [P.ViewChannel, ...(voice ? [P.Connect] : [])] },
    { id: roleId, type: OverwriteType.Role, allow: voice
      ? [P.ViewChannel, P.Connect, P.Speak, P.UseVAD]
      : [P.ViewChannel, P.SendMessages, P.ReadMessageHistory] },
    { id: botId, type: OverwriteType.Member, allow: [P.ViewChannel, P.ManageChannels, P.ManageRoles,
      ...(voice ? [] : [P.SendMessages, P.ReadMessageHistory])] },
  ];
}

function overwriteKey(overwrites) {
  return [...overwrites].map(overwrite => [overwrite.id, overwrite.type,
    new PermissionsBitField(overwrite.allow ?? []).bitfield.toString(),
    new PermissionsBitField(overwrite.deny ?? []).bitfield.toString()].join(':')).sort().join('|');
}

export function hasFactionPrivacy(channel, guildId, roleId, botId, voice = false) {
  return overwriteKey(channel.permissionOverwrites.cache.values()) === overwriteKey(factionOverwrites(guildId, roleId, botId, voice));
}

export function textChannelName(name, factionId) {
  const slug = name.normalize('NFKD').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
  return `faction-${slug || factionId.slice(0, 8)}`.slice(0, 100);
}

export class FactionChannels {
  constructor(guild, state, store) {
    Object.assign(this, { guild, state, store });
    this.state.channels ??= {};
  }

  async refresh(me) {
    if (!me.permissions.has(P.ManageChannels)) throw new Error('CivBot needs Manage Channels to create private faction text and voice channels.');
    await this.guild.channels.fetch();
  }

  async ensure(faction, role, me) {
    const pair = this.state.channels[faction.id] ??= {};
    for (const [kind, type] of [['text', ChannelType.GuildText], ['voice', ChannelType.GuildVoice]]) {
      const overwrites = factionOverwrites(this.guild.id, role.id, me.id, kind === 'voice');
      let channel = this.guild.channels.cache.get(pair[kind]);
      if (channel && channel.type !== type) throw new Error(`Saved faction ${kind} channel has an unexpected type; check data/roles.json.`);
      if (!channel) {
        // Include permissions in the creation request: the channel is never briefly public.
        channel = await this.guild.channels.create({ name: kind === 'text' ? textChannelName(faction.name, faction.id)
          : `${faction.name} Voice`.slice(0, 100), type, permissionOverwrites: overwrites,
          reason: 'CivMaker private faction channel' });
        pair[kind] = channel.id;
        try { await this.store.save(this.state); }
        catch (error) { throw new StateWriteError('Could not save the new faction channel. Check the bot data directory.', { cause: error }); }
      } else if (!hasFactionPrivacy(channel, this.guild.id, role.id, me.id, kind === 'voice')) {
        // Also removes accidental outsider grants and replaces deleted/recreated faction roles.
        await channel.permissionOverwrites.set(overwrites, 'CivMaker faction-only access');
      }
    }
    return pair;
  }
}
