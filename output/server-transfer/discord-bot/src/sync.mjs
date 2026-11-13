import { PermissionFlagsBits } from 'discord.js';
import { validateSnapshot } from './bridge.mjs';
import { StateWriteError } from './state.mjs';
import { FactionChannels } from './channels.mjs';

export function roleChanges(currentIds, managedIds, desiredId) {
  const current = new Set(currentIds);
  return {
    remove: [...new Set(managedIds)].filter(id => current.has(id) && id !== desiredId),
    add: desiredId && !current.has(desiredId) ? [desiredId] : [],
  };
}

/** Serialize commands and polling so an old snapshot cannot overwrite a new link/unlink. */
export class SerialQueue {
  tail = Promise.resolve();
  run(operation) {
    const result = this.tail.then(operation);
    this.tail = result.catch(() => {});
    return result;
  }
}

export class RoleSync {
  constructor(guild, state, store, config, log = console) {
    Object.assign(this, { guild, state, store, config, log });
    this.channels = new FactionChannels(guild, state, store);
  }

  checkServer(snapshot) {
    validateSnapshot(snapshot);
    if (this.state.serverId && this.state.serverId !== snapshot.serverId) {
      throw new Error('The bridge belongs to a different Minecraft server. Restore the correct discord-links.json; roles were left alone.');
    }
  }

  async apply(snapshot) {
    this.checkServer(snapshot);
    const me = await this.guild.members.fetchMe();
    if (!me.permissions.has(PermissionFlagsBits.ManageRoles)) {
      throw new Error('The bot needs Manage Roles. Place its role above every CivMaker faction role.');
    }
    await this.guild.roles.fetch();
    this.state.serverId = snapshot.serverId;
    // Persist the cleanup list before granting any roles, including across crashes.
    const tracked = new Set([...this.state.trackedUsers, ...snapshot.players.map(player => player.discordId)]);
    this.state.trackedUsers = [...tracked];
    await this.store.save(this.state);
    const errors = [];
    let channelsReady = false;
    if (snapshot.factions.length) {
      try { await this.channels.refresh(me); channelsReady = true; }
      catch (error) { errors.push(error.message); }
    }
    const blockedFactions = new Set();
    for (const faction of snapshot.factions) {
      try {
        let role = this.guild.roles.cache.get(this.state.roles[faction.id]);
        const name = `${this.config.rolePrefix}${faction.name}`.slice(0, 100);
        if (!role) {
          role = await this.guild.roles.create({ name, colors: { primaryColor: faction.color }, permissions: [],
            hoist: false, mentionable: false, reason: 'CivMaker faction role' });
          this.state.roles[faction.id] = role.id;
          try { await this.store.save(this.state); }
          catch (error) { throw new StateWriteError('Could not save the new faction role. Check the bot data directory.', { cause: error }); }
        }
        this.assertSafe(role);
        if (role.name !== name || role.colors.primaryColor !== faction.color) {
          await role.edit({ name, colors: { primaryColor: faction.color }, reason: 'CivMaker faction details' });
        }
        if (channelsReady) {
          try { await this.channels.ensure(faction, role, me); }
          catch (error) {
            if (error instanceof StateWriteError) throw error;
            errors.push(`Faction channels ${faction.id}: ${error.message}`);
          }
        }
      } catch (error) {
        // A failed disk write must stop before any membership changes occur.
        if (error instanceof StateWriteError) throw error;
        blockedFactions.add(faction.id);
        errors.push(`Faction ${faction.id}: ${error.message}`);
      }
    }
    const players = new Map(snapshot.players.map(player => [player.discordId, player]));
    const managed = Object.values(this.state.roles);
    let updated = 0;
    for (const discordId of tracked) {
      try {
        const player = players.get(discordId);
        const blocked = player?.factionId && blockedFactions.has(player.factionId);
        // Force REST: other administrators may have edited roles since the previous poll.
        const member = await this.guild.members.fetch({ user: discordId, force: true });
        // Preserve an already-correct role if its metadata update failed, but do not grant it until safe.
        const desired = player?.factionId ? this.state.roles[player.factionId] : undefined;
        const changes = roleChanges(member.roles.cache.keys(), managed, desired);
        for (const roleId of changes.remove) {
          const role = this.guild.roles.cache.get(roleId);
          if (role) this.assertSafe(role);
          await member.roles.remove(roleId, 'CivMaker faction membership changed or account unlinked');
        }
        // Remove the old faction first, so a transfer never grants both factions at once.
        if (!blocked) {
          for (const roleId of changes.add) await member.roles.add(roleId, 'CivMaker faction membership');
        }
        if (changes.remove.length || !blocked && changes.add.length) updated++;
        if (!player) tracked.delete(discordId);
        if (blocked) errors.push(`Member ${discordId}: faction role is unavailable; will retry.`);
      } catch (error) {
        // A player outside this Discord server may join later; keep their active link.
        if (error.code === 10007) {
          if (!players.has(discordId)) tracked.delete(discordId);
        } else errors.push(`Member ${discordId}: ${error.message}`);
      }
    }
    this.state.trackedUsers = [...tracked];
    await this.store.save(this.state);
    if (errors.length) this.log.warn(`Role sync will retry ${errors.length} issue(s):\n${errors.slice(0, 10).join('\n')}`);
    return { updated, errors: errors.length };
  }

  assertSafe(role) {
    if (role.id === this.guild.id || role.managed || !role.editable || role.permissions.bitfield !== 0n) {
      throw new Error(`Role ${role.id} cannot be synced. It must have no permissions and sit below the bot role.`);
    }
  }
}
