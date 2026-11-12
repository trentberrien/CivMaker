# CivMaker Discord bot

Run this bot on the **same computer as the Paper server**. Players connect their accounts with a private Minecraft code. The bot automatically creates one Discord role per CivMaker faction, using its name and color, and updates linked players' roles every 30 seconds. Links work for offline Minecraft players and survive restarts.

Requires CivMaker **0.10.0+**, Paper 26.3 / Java 25, **Node.js 24.17.0 or newer**, and an internet connection to Discord. No public website, incoming firewall rule, port forwarding, database server, or paid hosting is needed. The bot runs as a separate local process alongside Paper.

## Set up Discord

1. Open the [Discord Developer Portal](https://discord.com/developers/applications), create an application, and open its **Bot** page. Create/reset its bot token and keep it private.
2. In **Installation**, enable a **Guild Install** with the `bot` and `applications.commands` scopes. Give the bot **Manage Roles**. Install it into your Discord server with the generated installation link.
3. In Discord, enable **User Settings → Advanced → Developer Mode**. Right-click your server icon and choose **Copy Server ID**.
4. In **Server Settings → Roles**, put the bot's role above the faction roles it will create. It only needs Manage Roles; the generated faction roles start with no permissions. Keep staff and other powerful roles separate from the bot-created faction roles.

This bot uses slash commands and fetches individual linked members. It does not request Message Content, Presence, or Server Members gateway intents. Discord documents the [role hierarchy and Manage Roles permission](https://docs.discord.com/developers/topics/permissions) and [application commands](https://docs.discord.com/developers/interactions/application-commands).

## Get online temporarily on your computer

To connect to Discord before setting up Minecraft, put the bot token in `.env`, set `DISCORD_GUILD_NAME=CivMaker` (or fill in the server ID), and set **`DISCORD_ONLINE_ONLY=true`**. You can leave the bridge token blank in this mode. Run `start.cmd` or `npm start`. The bot appears online and `/status` explains that Minecraft setup is pending. Linking and role changes stay paused.

When moving to the server computer, stop this temporary process first, copy the bot folder including `.env` and `data`, configure the Minecraft bridge as below, and set `DISCORD_ONLINE_ONLY=false`. Install Node.js and the bot dependencies there, then start it. No Discord reinvite is needed. The temporary process runs only while this computer is awake; starting it does not install a startup task.

## Set up Minecraft and the bot

1. Build CivMaker with `mvn package`, stop Paper, and replace its CivMaker plugin with `target/civmaker-0.10.0.jar`. Keep the previous JAR in your backups, outside the plugins folder.
2. Generate a shared local bridge secret:

   ```powershell
   node -e "console.log(require('node:crypto').randomBytes(32).toString('hex'))"
   ```

3. Add this section to **`plugins/CivMaker/config.yml`** (the existing config is not overwritten by an upgrade). Replace the example secret with your generated value:

   ```yaml
   discord:
     enabled: true
     port: 8790
     bridge-token: 'PASTE_YOUR_GENERATED_SECRET_HERE'
   ```

   Paper must have **`online-mode=true`** in `server.properties` so players cannot impersonate someone else's Minecraft account. Offline-mode/proxy backends are not supported by this version. The bridge always binds to `127.0.0.1`; do not expose it through a reverse proxy or tunnel.

4. Copy the `discord-bot` folder to the server computer if you built elsewhere. In that folder, copy `.env.example` to `.env` and fill in:

   ```dotenv
   DISCORD_TOKEN=YOUR_PRIVATE_BOT_TOKEN
   DISCORD_GUILD_ID=YOUR_DISCORD_SERVER_ID
   CIVMAKER_BRIDGE_TOKEN=THE_SAME_GENERATED_SECRET_AS_ABOVE
   CIVMAKER_BRIDGE_URL=http://127.0.0.1:8790
   SYNC_INTERVAL_SECONDS=30
   ROLE_PREFIX=Civ |
   DATA_DIRECTORY=./data
   ```

5. Install dependencies in the `discord-bot` folder using npm (bundled with the Node.js installer):

   ```powershell
   npm install
   ```

   For a dependency installation matching the checked-in lockfile, use `pnpm install --frozen-lockfile` instead.

6. Start Paper normally. Its console should report `Discord bridge listening on 127.0.0.1:8790`.
7. Check the configuration, then start the bot:

   ```powershell
   npm run check
   npm start
   ```

   On Windows you can also double-click **`start.cmd`**. Leave the bot running alongside Paper. It registers its slash commands in your configured Discord server automatically. `check` verifies bridge authentication, server identity, Discord access, and Manage Roles without changing Discord roles.

## Player commands

1. Join Minecraft with your own account and run **`/civ discord link`**.
2. In the configured Discord server, run **`/link`** and put the displayed code in its **code** option. Replies are private. Codes expire after 10 minutes, are usable once, and are cleared by a Paper restart. Repeating the Minecraft command shows your existing unexpired code.
3. Your faction role appears automatically. If you are unaffiliated, your accounts are still connected and you get a faction role when you join a faction.

| Where | Command | Purpose |
| --- | --- | --- |
| Minecraft | `/civ discord link` | Get your private linking code |
| Minecraft | `/civ discord status` | See whether your account is linked |
| Minecraft | `/civ discord unlink` | Disconnect and cancel any pending code |
| Discord | `/link code:<code>` | Verify and connect the Minecraft account |
| Discord | `/account` | Show your linked Minecraft name and faction |
| Discord | `/status` | Check whether the bot and Minecraft connection are ready |
| Discord | `/unlink` | Disconnect and remove the faction role |
| Discord | `/sync` | Force an immediate full sync; requires Manage Roles |

One Minecraft account connects to one Discord account, and vice versa. Unlink first to change accounts. Keep codes private: possession of the code proves control of the Minecraft account. Five linking attempts per Discord account per minute are allowed.

## Role behavior and local hosting

- Role names use `Civ | <faction>` by default. Change `ROLE_PREFIX` to customize this. Roles are tracked by Discord ID and faction UUID, so duplicate faction names and restarts do not mix factions up.
- Joining, leaving, kicking, transferring, and disbanding update roles on the next poll (normally within about 31 seconds, plus Discord API time). `/link` and `/unlink` also request an immediate sync. Transfers remove the old faction role before granting the new one. Admin test allegiance does not affect Discord.
- Only roles created and recorded by this bot are managed. Other Discord roles are preserved. Existing roles with matching names are not adopted. Keep the roles' server permissions empty: syncing refuses roles with server permissions or roles above the bot. You can use these roles in **channel permission overrides** to give factions access to their own channels.
- Obsolete faction roles remain in Discord for administrators to remove if desired; their members are cleared. Deleting an active faction role makes the bot recreate it on the next sync.
- A linked member who leaves and rejoins Discord gets their role back on the next poll. The bot does not require Minecraft to be online at that moment; it uses saved faction membership from the running plugin.
- When Paper or the bridge is unavailable or stale, existing roles are left in place until fresh data returns. The bot retries automatically. If the bot is stopped, role changes and unlinks are applied when it resumes. A code created before Paper restarts must be regenerated.
- For unattended Windows hosting, create a Task Scheduler task that runs `node.exe` with arguments `--env-file=.env src/index.mjs`, sets **Start in** to the full `discord-bot` folder path, triggers at startup, and restarts on failure. Use the installed Node executable's full path. The bot can start before Paper and will wait for the bridge. On Linux, run the same command with a service manager and set its working directory to the bot folder.

## Backups and troubleshooting

Keep `.env`, **`plugins/CivMaker/discord-links.json`**, and **`discord-bot/data/roles.json`** private and back them up. The first stores secrets; the second stores account links and a persistent Minecraft server identity; the third stores bot-owned Discord role IDs and the list needed to clean up unlinked accounts. Links are never added to the public World Atlas export. Pending codes stay in memory. The bot does not log codes or tokens.

Keep both data files when updating or moving the server. Do not delete `roles.json` to fix an error: without it the bot cannot recognize its existing Discord roles or clean up previously linked members. Corrupt data, a different configured guild, or a different Minecraft server identity stops syncing until the correct backup/configuration is restored. Run only one bot process per Discord server and Minecraft server; a local lock prevents competing processes that share a data directory.

- **Bridge unavailable:** start Paper, check `discord.enabled`, check its console for configuration errors, and match the URL port to `discord.port`. Only the optional bridge is disabled by a bad config; gameplay continues.
- **Authentication failed:** the secrets in `.env` and `plugins/CivMaker/config.yml` must match exactly. Restart the affected process after changing its configuration.
- **Missing permissions / roles never appear:** give the bot Manage Roles and move its highest role above the faction roles. Keep faction roles at zero permissions. Discord's audit log records role changes with a CivMaker reason.
- **Slash commands missing:** install the bot into the correct server with both scopes and check `DISCORD_GUILD_ID`. Members also need permission to use application commands in the channel.
- **Already running:** stop the original process. If a forced shutdown leaves `data/bot.lock`, the bot automatically reclaims it when the recorded process no longer exists. If Windows reuses that PID, verify no bot is running, then remove only `bot.lock`.

Run the bot's automated checks with `npm test` and the plugin tests with `mvn test`.
