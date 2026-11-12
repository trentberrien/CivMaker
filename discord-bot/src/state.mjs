import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const snowflake = /^\d{17,20}$/;

export class StateWriteError extends Error {}

export class StateStore {
  constructor(directory, guildId) {
    this.directory = directory;
    this.file = join(directory, 'roles.json');
    this.guildId = guildId;
  }

  async load() {
    await mkdir(this.directory, { recursive: true });
    let data;
    try { data = JSON.parse(await readFile(this.file, 'utf8')); }
    catch (error) {
      if (error.code === 'ENOENT') return { schemaVersion: 1, guildId: this.guildId, serverId: null, roles: {}, channels: {}, raidAlertsSent: {}, trackedUsers: [] };
      throw new Error('Cannot read data/roles.json. Restore a backup before starting the bot.', { cause: error });
    }
    if (data.schemaVersion !== 1 || data.guildId !== this.guildId
        || data.serverId !== null && !uuid.test(data.serverId)
        || !data.roles || typeof data.roles !== 'object' || Array.isArray(data.roles)
        || !Object.entries(data.roles).every(([key, value]) => uuid.test(key) && snowflake.test(value))
        || new Set(Object.values(data.roles)).size !== Object.values(data.roles).length
        || !Array.isArray(data.trackedUsers) || !data.trackedUsers.every(id => snowflake.test(id))) {
      throw new Error('Invalid role state or different Discord server. Restore the correct data/roles.json.');
    }
    // Compatible upgrade: old role-only installations have neither of these maps.
    data.channels ??= {};
    data.raidAlertsSent ??= {};
    const channels = Object.entries(data.channels);
    if (typeof data.channels !== 'object' || Array.isArray(data.channels)
        || !channels.every(([id, pair]) => uuid.test(id) && pair && typeof pair === 'object' && !Array.isArray(pair)
          && Object.entries(pair).every(([kind, channel]) => ['text', 'voice'].includes(kind) && snowflake.test(channel)))
        || new Set(channels.flatMap(([, pair]) => Object.values(pair))).size !== channels.flatMap(([, pair]) => Object.values(pair)).length
        || typeof data.raidAlertsSent !== 'object' || Array.isArray(data.raidAlertsSent)
        || !Object.entries(data.raidAlertsSent).every(([id, sentAt]) => uuid.test(id) && Number.isFinite(Date.parse(sentAt)))) {
      throw new Error('Invalid saved faction channels or raid alert state. Restore the correct data/roles.json.');
    }
    return data;
  }

  async save(data) {
    const temporary = `${this.file}.tmp`;
    await writeFile(temporary, `${JSON.stringify(data, null, 2)}\n`, { mode: 0o600 });
    await rename(temporary, this.file);
  }
}

/** Stops a second local bot process from competing over roles and the state file. */
export async function lockDirectory(directory) {
  const { open, unlink } = await import('node:fs/promises');
  await mkdir(directory, { recursive: true });
  const path = join(directory, 'bot.lock');
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const handle = await open(path, 'wx', 0o600);
      await handle.writeFile(String(process.pid));
      await handle.close();
      return async () => { await unlink(path).catch(() => {}); };
    } catch (error) {
      if (error.code !== 'EEXIST') throw error;
      const pid = Number(await readFile(path, 'utf8'));
      if (!Number.isSafeInteger(pid) || pid < 1) throw new Error('Invalid data/bot.lock. Check no bot is running, then remove it.');
      try { process.kill(pid, 0); }
      catch (processError) {
        if (processError.code !== 'ESRCH') throw new Error('Another process owns data/bot.lock. Stop it before starting the bot.');
        await unlink(path);
        continue;
      }
      throw new Error('The CivMaker bot is already running. Stop the other process first.');
    }
  }
  throw new Error('Could not acquire data/bot.lock.');
}
