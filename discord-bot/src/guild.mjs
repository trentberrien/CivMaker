/** Select only the explicitly configured server, never an arbitrary server the bot has joined. */
export function selectGuildId(config, guilds) {
  if (config.guildId) return config.guildId;
  const matches = [...guilds].filter(guild => guild.name === config.guildName);
  if (matches.length !== 1) {
    throw new Error(matches.length === 0
      ? 'The bot has not joined the configured Discord server. Check its invite and DISCORD_GUILD_NAME, or set DISCORD_GUILD_ID.'
      : 'Multiple Discord servers have that name. Set DISCORD_GUILD_ID to choose the intended server.');
  }
  return matches[0].id;
}
