const {
    Client,
    GatewayIntentBits,
    REST,
    Routes,
    SlashCommandBuilder
} = require("discord.js");

const token = process.env.DISCORD_BOT_TOKEN;
const applicationId = process.env.DISCORD_APPLICATION_ID;

const guildId = "1536652671543541887";

if (!token) {
    console.log("[Discord] DISCORD_BOT_TOKEN is not set. Bot disabled.");
} else if (!applicationId) {
    console.log("[Discord] DISCORD_APPLICATION_ID is not set. Bot disabled.");
} else {
    const client = new Client({
        intents: [
            GatewayIntentBits.Guilds
        ]
    });

    const commands = [
        new SlashCommandBuilder()
            .setName("race")
            .setDescription("0GP Race commands")
            .addSubcommand(subcommand =>
                subcommand
                    .setName("create")
                    .setDescription("Create a new race")
            )
            .toJSON()
    ];

    async function registerCommands() {
        try {
            const rest = new REST({ version: "10" }).setToken(token);

            await rest.put(
                Routes.applicationGuildCommands(applicationId, guildId),
                {
                    body: commands
                }
            );

            console.log("[Discord] /race create registered.");
        } catch (error) {
            console.error("[Discord] Failed to register commands:", error);
        }
    }

    client.once("clientReady", async () => {
        console.log(`[Discord] Bot online as ${client.user.tag}`);

        await registerCommands();
    });

    client.on("interactionCreate", async interaction => {
        if (!interaction.isChatInputCommand()) {
            return;
        }

        if (
            interaction.commandName === "race" &&
            interaction.options.getSubcommand() === "create"
        ) {
            await interaction.reply({
                content: "🏁 Race creation is working!",
                ephemeral: true
            });
        }
    });

    client.login(token).catch(error => {
        console.error("[Discord] Failed to log in:", error);
    });
}