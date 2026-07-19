import { existsSync, readFileSync } from 'node:fs';

for (const file of ['.dev.vars', '.env']) {
  if (!existsSync(file)) continue;
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const match = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/);
    if (!match || process.env[match[1]]) continue;
    process.env[match[1]] = match[2].replace(/^['"]|['"]$/g, '');
  }
}

const required = ['DISCORD_APPLICATION_ID', 'DISCORD_BOT_TOKEN', 'DISCORD_GUILD_ID'];
const missing = required.filter((name) => !process.env[name]);
if (missing.length) {
  console.error(`Missing environment variables: ${missing.join(', ')}`);
  console.error('Set them in your shell or in an untracked workers/support/.dev.vars file.');
  process.exitCode = 1;
} else {
  const closingStatuses = [
    ['Fixed', 'fixed'],
    ['Resolved', 'resolved'],
    ['Duplicate', 'duplicate'],
    ['Unable to reproduce', 'unable_to_reproduce'],
    ['Declined', 'declined'],
    ['Closed', 'closed']
  ];
  const messageOption = {
    type: 3,
    name: 'message',
    description: 'The user-facing response shown in Orchard',
    required: true,
    max_length: 2000
  };
  const commands = [
    {
      type: 1,
      name: 'reply',
      description: 'Reply to the Orchard report in this thread',
      options: [messageOption]
    },
    {
      type: 1,
      name: 'request-info',
      description: 'Ask the Orchard user for more information',
      options: [messageOption]
    },
    {
      type: 1,
      name: 'reopen',
      description: 'Reopen the Orchard report in this thread',
      options: [messageOption]
    },
    {
      type: 1,
      name: 'close',
      description: 'Close the Orchard report and explain why',
      options: [
        {
          type: 3,
          name: 'status',
          description: 'The final report status',
          required: true,
          choices: closingStatuses.map(([name, value]) => ({ name, value }))
        },
        messageOption,
        {
          type: 3,
          name: 'version',
          description: 'Optional target Orchard version, such as 1.1.0',
          required: false,
          max_length: 32
        }
      ]
    }
  ];
  const endpoint = `https://discord.com/api/v10/applications/${process.env.DISCORD_APPLICATION_ID}/guilds/${process.env.DISCORD_GUILD_ID}/commands`;
  const response = await fetch(endpoint, {
    method: 'PUT',
    headers: {
      authorization: `Bot ${process.env.DISCORD_BOT_TOKEN}`,
      'content-type': 'application/json'
    },
    body: JSON.stringify(commands)
  });
  if (!response.ok) {
    console.error(`Discord returned ${response.status}: ${(await response.text()).slice(0, 1000)}`);
    process.exitCode = 1;
  } else {
    const installed = await response.json();
    console.log(`Registered ${installed.length} Orchard support commands.`);
    console.log(installed.map((command) => `/${command.name}`).join(', '));
  }
}
