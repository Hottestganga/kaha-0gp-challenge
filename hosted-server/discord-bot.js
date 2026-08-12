const { Client, GatewayIntentBits } = require("discord.js");

const token = process.env.DISCORD_BOT_TOKEN;

if (!token) {
    console.log("[Discord] DISCORD_BOT_TOKEN is not set. Bot disabled.");
} else {
    const client = new Client({
        intents: [
            GatewayIntentBits.Guilds
        ]
    });

    client.once("ready", () => {
        console.log(`[Discord] Bot online as ${client.user.tag}`);
    });

    client.login(token).catch((err) => {
        console.error("[Discord] Failed to log in:", err);
    });
}