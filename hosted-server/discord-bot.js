const {
    Client,
    GatewayIntentBits,
    REST,
    Routes,
    SlashCommandBuilder,
    ModalBuilder,
    TextInputBuilder,
    TextInputStyle,
    ActionRowBuilder
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

        if (interaction.isChatInputCommand()) {
            if (
                interaction.commandName === "race" &&
                interaction.options.getSubcommand() === "create"
            ) {
                const modal = new ModalBuilder()
                    .setCustomId("createRaceModal")
                    .setTitle("🏁 Create a 0GP Race");

                const durationInput = new TextInputBuilder()
                    .setCustomId("duration")
                    .setLabel("Race Duration")
                    .setPlaceholder("Example: 1 Hour, 2 Hours, 30 Minutes")
                    .setStyle(TextInputStyle.Short)
                    .setRequired(true)
                    .setMaxLength(30);

                const startingGpInput = new TextInputBuilder()
                    .setCustomId("startingGp")
                    .setLabel("Starting GP")
                    .setPlaceholder("Example: 0, 100K, 1M")
                    .setStyle(TextInputStyle.Short)
                    .setRequired(true)
                    .setMaxLength(30);

                const playersInput = new TextInputBuilder()
                    .setCustomId("players")
                    .setLabel("Maximum Players")
                    .setPlaceholder("Example: 4, 8, 10")
                    .setStyle(TextInputStyle.Short)
                    .setRequired(true)
                    .setMaxLength(3);

                const startTimeInput = new TextInputBuilder()
                    .setCustomId("startTime")
                    .setLabel("Start Time")
                    .setPlaceholder("Example: 7:30 PM AEST or Starting Now")
                    .setStyle(TextInputStyle.Short)
                    .setRequired(true)
                    .setMaxLength(50);

                const rulesInput = new TextInputBuilder()
                    .setCustomId("rules")
                    .setLabel("Special Rules")
                    .setPlaceholder("Leave blank for standard 0GP Race rules")
                    .setStyle(TextInputStyle.Paragraph)
                    .setRequired(false)
                    .setMaxLength(500);

                modal.addComponents(
                    new ActionRowBuilder().addComponents(durationInput),
                    new ActionRowBuilder().addComponents(startingGpInput),
                    new ActionRowBuilder().addComponents(playersInput),
                    new ActionRowBuilder().addComponents(startTimeInput),
                    new ActionRowBuilder().addComponents(rulesInput)
                );

                await interaction.showModal(modal);
                return;
            }
        }

        if (
            interaction.isModalSubmit() &&
            interaction.customId === "createRaceModal"
        ) {
            const duration =
                interaction.fields.getTextInputValue("duration");

            const startingGp =
                interaction.fields.getTextInputValue("startingGp");

            const players =
                interaction.fields.getTextInputValue("players");

            const startTime =
                interaction.fields.getTextInputValue("startTime");

            const rules =
                interaction.fields.getTextInputValue("rules") ||
                "Standard 0GP Race rules";

            await interaction.reply({
                content:
                    `🏁 **Race Form Received!**\n\n` +
                    `**Duration:** ${duration}\n` +
                    `**Starting GP:** ${startingGp}\n` +
                    `**Maximum Players:** ${players}\n` +
                    `**Start Time:** ${startTime}\n` +
                    `**Rules:** ${rules}`,
                ephemeral: true
            });

            console.log(
                `[Discord] Race form submitted by ${interaction.user.tag}`
            );
        }
    });

    client.login(token).catch(error => {
        console.error("[Discord] Failed to log in:", error);
    });
}