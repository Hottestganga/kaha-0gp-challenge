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

    function parseBrisbaneDateTime(dateText, timeText) {
        const dateMatch = dateText.trim().match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);

        if (!dateMatch) {
            return null;
        }

        const day = Number(dateMatch[1]);
        const month = Number(dateMatch[2]);
        const year = Number(dateMatch[3]);

        let time = timeText.trim().toUpperCase();

        const timeMatch = time.match(
            /^(\d{1,2}):(\d{2})\s*(AM|PM)?$/
        );

        if (!timeMatch) {
            return null;
        }

        let hour = Number(timeMatch[1]);
        const minute = Number(timeMatch[2]);
        const ampm = timeMatch[3];

        if (minute < 0 || minute > 59) {
            return null;
        }

        if (ampm) {
            if (hour < 1 || hour > 12) {
                return null;
            }

            if (ampm === "AM" && hour === 12) {
                hour = 0;
            }

            if (ampm === "PM" && hour !== 12) {
                hour += 12;
            }
        } else {
            if (hour < 0 || hour > 23) {
                return null;
            }
        }

        /*
         * Brisbane / Queensland is UTC+10 year-round.
         *
         * We convert the host's Brisbane time to UTC
         * so Discord can display it correctly for everyone.
         */
        const utcMillis = Date.UTC(
            year,
            month - 1,
            day,
            hour - 10,
            minute,
            0
        );

        const date = new Date(utcMillis);

        /*
         * Verify the date wasn't something invalid like 32/08/2026.
         */
        const check = new Date(utcMillis + (10 * 60 * 60 * 1000));

        if (
            check.getUTCFullYear() !== year ||
            check.getUTCMonth() !== month - 1 ||
            check.getUTCDate() !== day ||
            check.getUTCHours() !== hour ||
            check.getUTCMinutes() !== minute
        ) {
            return null;
        }

        return Math.floor(date.getTime() / 1000);
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

                const startDateInput = new TextInputBuilder()
                    .setCustomId("startDate")
                    .setLabel("Start Date")
                    .setPlaceholder("Example: 12/08/2026")
                    .setStyle(TextInputStyle.Short)
                    .setRequired(true)
                    .setMaxLength(10);

                const startTimeInput = new TextInputBuilder()
                    .setCustomId("startTime")
                    .setLabel("Start Time - Brisbane Time")
                    .setPlaceholder("Example: 11:00 AM")
                    .setStyle(TextInputStyle.Short)
                    .setRequired(true)
                    .setMaxLength(20);

                modal.addComponents(
                    new ActionRowBuilder().addComponents(durationInput),
                    new ActionRowBuilder().addComponents(startingGpInput),
                    new ActionRowBuilder().addComponents(playersInput),
                    new ActionRowBuilder().addComponents(startDateInput),
                    new ActionRowBuilder().addComponents(startTimeInput)
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

            const startDate =
                interaction.fields.getTextInputValue("startDate");

            const startTime =
                interaction.fields.getTextInputValue("startTime");

            const unixTimestamp =
                parseBrisbaneDateTime(startDate, startTime);

            if (!unixTimestamp) {
                await interaction.reply({
                    content:
                        "❌ I couldn't understand that date or time.\n\n" +
                        "Use this format:\n" +
                        "**Date:** 12/08/2026\n" +
                        "**Time:** 11:00 AM",
                    ephemeral: true
                });

                return;
            }

            await interaction.reply({
                content:
                    `🏁 **Race Form Received!**\n\n` +
                    `**Duration:** ${duration}\n` +
                    `**Starting GP:** ${startingGp}\n` +
                    `**Maximum Players:** ${players}\n` +
                    `**Start:** <t:${unixTimestamp}:F>\n` +
                    `**Starts:** <t:${unixTimestamp}:R>`,
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