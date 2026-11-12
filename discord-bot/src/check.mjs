import { REST, Routes, PermissionFlagsBits } from 'discord.js';
import { loadConfig } from './config.mjs';
import { Bridge } from './bridge.mjs';
import { StateStore } from './state.mjs';
import { selectGuildId } from './guild.mjs';

try {
  const config = loadConfig();
  const rest = new REST({ version: '10' }).setToken(config.token);
  if (!config.guildId) {
    config.guildId = selectGuildId(config, await rest.get(Routes.userGuilds()));
  }
  const user = await rest.get(Routes.user('@me'));
  const member = await rest.get(Routes.guildMember(config.guildId, user.id));
  if (config.onlineOnly) {
    console.log(`Connected as ${user.username}. Temporary Discord-only mode is configured for server ${config.guildId}.`);
    process.exit(0);
  }
  const snapshot = await new Bridge(config).snapshot();
  const state = await new StateStore(config.dataDirectory, config.guildId).load();
  if (state.serverId && state.serverId !== snapshot.serverId) throw new Error('Bot state belongs to another Minecraft server.');
  const roles = await rest.get(Routes.guildRoles(config.guildId));
  const permissions = roles.filter(role => role.id === config.guildId || member.roles.includes(role.id))
    .reduce((bits, role) => bits | BigInt(role.permissions), 0n);
  if (!(permissions & PermissionFlagsBits.Administrator)) {
    for (const [name, permission] of Object.entries({ 'Manage Roles': PermissionFlagsBits.ManageRoles,
      'Manage Channels': PermissionFlagsBits.ManageChannels, 'Mention @everyone, @here, and All Roles': PermissionFlagsBits.MentionEveryone })) {
      if (!(permissions & permission)) throw new Error(`Bot is in the Discord server but lacks ${name}.`);
    }
  }
  console.log(`Connected as ${user.username}. Bridge: ${snapshot.factions.length} faction(s), ${snapshot.players.length} linked account(s).`);
  console.log('Configuration and Discord access verified. Start the bot to sync roles, private channels, and raid alerts.');
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
