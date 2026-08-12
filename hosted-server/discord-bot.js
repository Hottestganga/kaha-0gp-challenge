const {
    Client,
    GatewayIntentBits,
    REST,
    Routes,
    SlashCommandBuilder,
    ModalBuilder,
    TextInputBuilder,
    TextInputStyle,
    ActionRowBuilder,
    EmbedBuilder,
    ButtonBuilder,
    ButtonStyle,
    MessageFlags
} = require("discord.js");

const token = process.env.DISCORD_BOT_TOKEN;
const applicationId = process.env.DISCORD_APPLICATION_ID;

const guildId = "1536652671543541887";
const findRaceChannelId = "1536654494194995260";

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
        const dateMatch = dateText
            .trim()
            .match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);

        if (!dateMatch) {
            return null;
        }

        const day = Number(dateMatch[1]);
        const month = Number(dateMatch[2]);
        const year = Number(dateMatch[3]);

        const time = timeText.trim().toUpperCase();

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
         */
        const utcMillis = Date.UTC(
            year,
            month - 1,
            day,
            hour - 10,
            minute,
            0
        );

        const check =
            new Date(utcMillis + (10 * 60 * 60 * 1000));

        if (
            check.getUTCFullYear() !== year ||
            check.getUTCMonth() !== month - 1 ||
            check.getUTCDate() !== day ||
            check.getUTCHours() !== hour ||
            check.getUTCMinutes() !== minute
        ) {
            return null;
        }

        return Math.floor(utcMillis / 1000);
    }

    client.once("clientReady", async () => {
        console.log(
            `[Discord] Bot online as ${client.user.tag}`
        );

        await registerCommands();
    });

    client.on("interactionCreate", async interaction => {

        /*
         * /race create
         */
        if (interaction.isChatInputCommand()) {
            if (
                interaction.commandName === "race" &&
                interaction.options.getSubcommand() === "create"
            ) {
                const modal = new ModalBuilder()
                    .setCustomId("createRaceModal")
                    .setTitle("🏁 Create a 0GP Race");

                const durationInput =
                    new TextInputBuilder()
                        .setCustomId("duration")
                        .setLabel("Race Duration")
                        .setPlaceholder(
                            "Example: 1 Hour, 2 Hours, 30 Minutes"
                        )
                        .setStyle(TextInputStyle.Short)
                        .setRequired(true)
                        .setMaxLength(30);

                const startingGpInput =
                    new TextInputBuilder()
                        .setCustomId("startingGp")
                        .setLabel("Starting GP")
                        .setPlaceholder(
                            "Example: 0, 100K, 1M"
                        )
                        .setStyle(TextInputStyle.Short)
                        .setRequired(true)
                        .setMaxLength(30);

                const playersInput =
                    new TextInputBuilder()
                        .setCustomId("players")
                        .setLabel("Maximum Players")
                        .setPlaceholder(
                            "Example: 4, 8, 10"
                        )
                        .setStyle(TextInputStyle.Short)
                        .setRequired(true)
                        .setMaxLength(3);

                const startDateInput =
                    new TextInputBuilder()
                        .setCustomId("startDate")
                        .setLabel("Start Date")
                        .setPlaceholder(
                            "Example: 12/08/2026"
                        )
                        .setStyle(TextInputStyle.Short)
                        .setRequired(true)
                        .setMaxLength(10);

                const startTimeInput =
                    new TextInputBuilder()
                        .setCustomId("startTime")
                        .setLabel(
                            "Start Time - Brisbane Time"
                        )
                        .setPlaceholder(
                            "Example: 11:00 AM"
                        )
                        .setStyle(TextInputStyle.Short)
                        .setRequired(true)
                        .setMaxLength(20);

                modal.addComponents(
                    new ActionRowBuilder()
                        .addComponents(durationInput),

                    new ActionRowBuilder()
                        .addComponents(startingGpInput),

                    new ActionRowBuilder()
                        .addComponents(playersInput),

                    new ActionRowBuilder()
                        .addComponents(startDateInput),

                    new ActionRowBuilder()
                        .addComponents(startTimeInput)
                );

                await interaction.showModal(modal);
                return;
            }
        }

        /*
         * Race creation form submitted
         */
        if (
            interaction.isModalSubmit() &&
            interaction.customId === "createRaceModal"
        ) {
            const duration =
                interaction.fields
                    .getTextInputValue("duration");

            const startingGp =
                interaction.fields
                    .getTextInputValue("startingGp");

            const playersText =
                interaction.fields
                    .getTextInputValue("players");

            const startDate =
                interaction.fields
                    .getTextInputValue("startDate");

            const startTime =
                interaction.fields
                    .getTextInputValue("startTime");

            const maxPlayers =
                Number(playersText);

            if (
                !Number.isInteger(maxPlayers) ||
                maxPlayers < 2 ||
                maxPlayers > 100
            ) {
                await interaction.reply({
                    content:
                        "❌ Maximum Players must be a number between 2 and 100.",
                    flags: MessageFlags.Ephemeral
                });

                return;
            }

            const unixTimestamp =
                parseBrisbaneDateTime(
                    startDate,
                    startTime
                );

            if (!unixTimestamp) {
                await interaction.reply({
                    content:
                        "❌ I couldn't understand that date or time.\n\n" +
                        "Use this format:\n" +
                        "**Date:** 12/08/2026\n" +
                        "**Time:** 11:00 AM",
                    flags: MessageFlags.Ephemeral
                });

                return;
            }

            try {
                const findRaceChannel =
                    await client.channels.fetch(
                        findRaceChannelId
                    );

                if (
                    !findRaceChannel ||
                    !findRaceChannel.isTextBased()
                ) {
                    await interaction.reply({
                        content:
                            "❌ I couldn't access the #find-a-race channel.",
                        flags: MessageFlags.Ephemeral
                    });

                    return;
                }

                const raceEmbed =
                    new EmbedBuilder()
                        .setTitle(
                            "🏁 NEW RACE — LOOKING FOR RACERS"
                        )
                        .setDescription(
                            "A new 0GP Race is open for applications."
                        )
                        .addFields(
                            {
                                name: "👑 Host",
                                value:
                                    `<@${interaction.user.id}>`,
                                inline: true
                            },
                            {
                                name: "👥 Players",
                                value:
                                    `1 / ${maxPlayers}`,
                                inline: true
                            },
                            {
                                name: "💰 Starting GP",
                                value:
                                startingGp,
                                inline: true
                            },
                            {
                                name: "⏱️ Duration",
                                value:
                                duration,
                                inline: true
                            },
                            {
                                name: "🕐 Start Time",
                                value:
                                    `<t:${unixTimestamp}:F>`,
                                inline: false
                            },
                            {
                                name: "⏳ Starts",
                                value:
                                    `<t:${unixTimestamp}:R>`,
                                inline: false
                            }
                        )
                        .setFooter({
                            text:
                                "🟢 OPEN — RACERS WANTED"
                        })
                        .setTimestamp();

                const applyButton =
                    new ButtonBuilder()
                        .setCustomId(
                            `applyRace:${interaction.user.id}`
                        )
                        .setLabel("Apply to Race")
                        .setEmoji("🏁")
                        .setStyle(
                            ButtonStyle.Success
                        );

                const buttonRow =
                    new ActionRowBuilder()
                        .addComponents(applyButton);

                await findRaceChannel.send({
                    embeds: [raceEmbed],
                    components: [buttonRow]
                });

                await interaction.reply({
                    content:
                        `✅ Your race has been posted in <#${findRaceChannelId}>.`,
                    flags: MessageFlags.Ephemeral
                });

                console.log(
                    `[Discord] Race posted by ${interaction.user.tag}`
                );

            } catch (error) {
                console.error(
                    "[Discord] Failed to post race:",
                    error
                );

                if (!interaction.replied) {
                    await interaction.reply({
                        content:
                            "❌ Something went wrong while posting the race.",
                        flags: MessageFlags.Ephemeral
                    });
                }
            }

            return;
        }

        /*
         * Apply button test
         */
        if (
            interaction.isButton() &&
            interaction.customId.startsWith(
                "applyRace:"
            )
        ) {
            const hostId =
                interaction.customId.split(":")[1];

            if (
                interaction.user.id === hostId
            ) {
                await interaction.reply({
                    content:
                        "👑 You're the host of this race — you don't need to apply.",
                    flags: MessageFlags.Ephemeral
                });

                return;
            }

            await interaction.reply({
                content:
                    "✅ Your Apply button worked. Host approval is the next part we're building.",
                flags: MessageFlags.Ephemeral
            });

            console.log(
                `[Discord] ${interaction.user.tag} clicked Apply to Race`
            );
        }
    });

    client.login(token).catch(error => {
        console.error(
            "[Discord] Failed to log in:",
            error
        );
    });
}