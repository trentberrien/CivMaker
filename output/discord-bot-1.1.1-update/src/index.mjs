import { ActivityType, Client, Events, GatewayIntentBits, MessageFlags, PermissionFlagsBits, SlashCommandBuilder } from 'discord.js';
import { loadConfig } from './config.mjs';
import { Bridge, BridgeError } from './bridge.mjs';
import { StateStore, lockDirectory } from './state.mjs';
import { RoleSync, SerialQueue } from './sync.mjs';
import { selectGuildId } from './guild.mjs';
import { RaidNotifications } from './raids.mjs';

const config = loadConfig();
const release = await lockDirectory(config.dataDirectory);
const bridge = new Bridge(config);
const queue = new SerialQueue();
const client = new Client({ intents: [GatewayIntentBits.Guilds], allowedMentions: { parse: [] },
  presence: { status: 'online', activities: [{ name: config.onlineOnly ? 'CivMaker setup' : 'CivMaker', type: ActivityType.Playing }] } });
let sync;
let raidNotifications;
let timer;
let stopping = false;
let lastHealth = '';

function report(error) {
  // API errors are summarized; request objects and tokens are never logged.
  const message = error.message || 'Unknown bot error';
  if (message !== lastHealth) console.error(message);
  lastHealth = message;
}

async function reconcile() {
  const snapshot = await bridge.snapshot();
  const result = await sync.apply(snapshot);
  const alerts = await raidNotifications.apply(snapshot);
  if (alerts.acknowledge.length) await bridge.acknowledgeRaids(alerts.acknowledge);
  result.errors += alerts.errors;
  if (lastHealth) console.log('Minecraft bridge connected; role syncing resumed.');
  lastHealth = '';
  if (result.updated) console.log(`Updated faction roles for ${result.updated} member(s).`);
  return result;
}

function poll() {
  if (stopping) return;
  queue.run(reconcile).catch(report).finally(() => {
    if (!stopping) timer = setTimeout(poll, config.intervalMs);
  });
}

const commands = [
  new SlashCommandBuilder().setName('status').setDescription('Check whether the bot and Minecraft faction syncing are ready'),
  new SlashCommandBuilder().setName('link').setDescription('Connect your Minecraft account with a private in-game code')
    .addStringOption(option => option.setName('code').setDescription('Code from /civ discord link in Minecraft')
      .setRequired(true).setMinLength(16).setMaxLength(19)),
  new SlashCommandBuilder().setName('unlink').setDescription('Disconnect your Minecraft account and remove your faction role'),
  new SlashCommandBuilder().setName('account').setDescription('Show your Minecraft link and current faction'),
  new SlashCommandBuilder().setName('sync').setDescription('Refresh all CivMaker faction roles now')
    .setDefaultMemberPermissions(PermissionFlagsBits.ManageRoles),
].map(command => command.toJSON());

client.once(Events.ClientReady, async () => {
  try {
    config.guildId = selectGuildId(config, client.guilds.cache.values());
    const guild = await client.guilds.fetch(config.guildId);
    const store = new StateStore(config.dataDirectory, config.guildId);
    const state = await store.load();
    sync = new RoleSync(guild, state, store, config);
    raidNotifications = new RaidNotifications(guild, state, store);
    await guild.commands.set(commands);
    console.log(`CivMaker bot ready in ${guild.name} (${guild.id}) as ${client.user.tag}.`);
    if (config.onlineOnly) console.log('Temporary Discord-only mode: online; Minecraft linking and faction role changes are paused.');
    else {
      console.log(`Faction sync interval: ${config.intervalMs / 1000}s.`);
      poll();
    }
  } catch (error) {
    report(error);
    await shutdown(1);
  }
});

client.on(Events.InteractionCreate, async interaction => {
  if (!interaction.isChatInputCommand()) return;
  if (interaction.guildId !== config.guildId || !sync || stopping) {
    await interaction.reply({ content: 'Use these commands in the configured Minecraft Discord server while the bot is ready.',
      flags: MessageFlags.Ephemeral }).catch(report);
    return;
  }
  try {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    if (config.onlineOnly) {
      await interaction.editReply('The CivMaker bot is online, temporarily hosted on the owner\'s computer. Minecraft linking and faction role syncing will be enabled when the server connection is set up.');
      return;
    }
    await queue.run(async () => {
      const discordId = interaction.user.id;
      const snapshot = await bridge.snapshot();
      sync.checkServer(snapshot);
      switch (interaction.commandName) {
        case 'status': {
          await interaction.editReply(`CivMaker is online and connected to Minecraft. Faction syncing is enabled every ${config.intervalMs / 1000} seconds.`);
          break;
        }
        case 'link': {
          const account = await bridge.link(interaction.options.getString('code', true), discordId);
          let synced = false;
          try { synced = (await reconcile()).errors === 0; } catch (error) { report(error); }
          await interaction.editReply(`Linked to Minecraft **${account.minecraftName}**. ${synced
            ? 'Your faction role is up to date.' : 'Role updates are pending; the bot will retry automatically.'}`);
          break;
        }
        case 'unlink': {
          await bridge.unlink(discordId);
          let synced = false;
          try { synced = (await reconcile()).errors === 0; } catch (error) { report(error); }
          await interaction.editReply(`Your Minecraft account is disconnected. ${synced
            ? 'Your faction role has been removed.' : 'Role removal is pending; the bot will retry automatically.'}`);
          break;
        }
        case 'account': {
          const account = snapshot.players.find(player => player.discordId === discordId);
          const faction = snapshot.factions.find(faction => faction.id === account?.factionId);
          await interaction.editReply(account ? `Minecraft: **${account.minecraftName}**\nFaction: **${faction?.name || 'Unaffiliated'}**`
            : 'No Minecraft account linked. In Minecraft use `/civ discord link`, then submit the code with `/link` here.');
          break;
        }
        case 'sync': {
          if (!interaction.memberPermissions?.has(PermissionFlagsBits.ManageRoles)) {
            await interaction.editReply('You need Manage Roles to force a full sync.');
            break;
          }
          const result = await reconcile();
          await interaction.editReply(`Updated ${result.updated} member(s). ${result.errors
            ? `${result.errors} issue(s) will be retried; check the bot console.` : 'Faction roles are up to date.'}`);
          break;
        }
      }
    });
  } catch (error) {
    report(error);
    await interaction.editReply(error instanceof BridgeError ? error.message
      : 'The command could not finish. Try again or ask an administrator to check the bot console.').catch(report);
  }
});

client.on(Events.Error, report);
client.on(Events.ShardError, report);

async function shutdown(code = 0) {
  if (stopping) return;
  stopping = true;
  clearTimeout(timer);
  await client.destroy();
  // Finish the one serialized state writer before releasing its lock.
  await queue.tail;
  await release();
  process.exitCode = code;
}
process.once('SIGINT', () => { void shutdown(); });
process.once('SIGTERM', () => { void shutdown(); });

try { await client.login(config.token); }
catch (error) { report(error); await shutdown(1); }
