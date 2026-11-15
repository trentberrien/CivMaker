import { ChannelType, escapeMarkdown, PermissionFlagsBits as P } from 'discord.js';
import { hasFactionPrivacy } from './channels.mjs';
import { StateWriteError } from './state.mjs';

const lifetime = 86_400_000;
const descriptions = {
  settlement_capture: 'Enemies are attempting to capture your settlement',
  lockpick: 'An enemy started picking a lock',
  battering_ram: 'An enemy breached a door with a battering ram',
  c4: 'An enemy detonated C4',
  grenade: 'An enemy detonated a grenade',
  block_break: 'An enemy is breaking blocks inside your territory',
  combat: 'An enemy attacked a faction member inside your territory',
};
const safeText = value => escapeMarkdown(value.replace(/[\r\n\x00-\x1f\x7f]/g, ' ').slice(0, 160));

export class RaidNotifications {
  constructor(guild, state, store, log = console) {
    Object.assign(this, { guild, state, store, log });
    this.state.raidAlertsSent ??= {};
  }

  async apply(snapshot) {
    const acknowledge = [];
    const errors = [];
    let sent = 0;
    for (const alert of snapshot.raidAlerts ?? []) {
      const current = snapshot.factions.some(faction => faction.id === alert.factionId);
      if (!current || Date.now() - Date.parse(alert.createdAt) >= lifetime || this.state.raidAlertsSent[alert.id]) {
        acknowledge.push(alert.id);
        continue;
      }
      try {
        const roleId = this.state.roles[alert.factionId];
        const channelId = this.state.channels?.[alert.factionId]?.text;
        if (!roleId || !channelId) throw new Error('The faction text channel is not ready yet.');
        const me = this.guild.members.me;
        const channel = await this.guild.channels.fetch(channelId, { force: true });
        if (!me || !channel || channel.type !== ChannelType.GuildText
            || !hasFactionPrivacy(channel, this.guild.id, roleId, me.id)) {
          throw new Error('Faction channel privacy could not be verified; the alert will be retried.');
        }
        const permissions = channel.permissionsFor(me);
        if (!permissions?.has([P.ViewChannel, P.SendMessages])) throw new Error('CivBot cannot send to the faction text channel.');
        if (!permissions.has(P.MentionEveryone)) throw new Error('CivBot needs Mention @everyone, @here, and All Roles to ping a faction during a raid.');
        const content = `<@&${roleId}> **Your faction is being raided!**\n${descriptions[alert.kind]}.\n`
          + `**Attacker:** ${safeText(alert.attacker)}\n**Location:** ${safeText(alert.world)} (${alert.x}, ${alert.y}, ${alert.z})\n`
          + `**Detected:** <t:${Math.floor(Date.parse(alert.createdAt) / 1000)}:R>`;
        await channel.send({ content, allowedMentions: { parse: [], roles: [roleId], users: [], repliedUser: false },
          nonce: alert.id.replaceAll('-', '').slice(0, 25), enforceNonce: true });
        // Save before acknowledging Paper. A retry after a lost ACK cannot send a second ping.
        const previous = this.state.raidAlertsSent;
        this.state.raidAlertsSent = { ...previous, [alert.id]: new Date().toISOString() };
        for (const [id, sentAt] of Object.entries(this.state.raidAlertsSent)) {
          if (Date.now() - Date.parse(sentAt) > lifetime * 2) delete this.state.raidAlertsSent[id];
        }
        try { await this.store.save(this.state); }
        catch (error) {
          this.state.raidAlertsSent = previous;
          throw new StateWriteError('Could not save raid delivery state. Check the bot data directory.', { cause: error });
        }
        acknowledge.push(alert.id);
        sent++;
      } catch (error) {
        if (error instanceof StateWriteError) throw error;
        errors.push(`Raid alert ${alert.id}: ${error.message}`);
      }
    }
    if (errors.length) this.log.warn(`Raid alerts will retry ${errors.length} issue(s):\n${errors.slice(0, 10).join('\n')}`);
    return { sent, acknowledge, errors: errors.length };
  }
}
