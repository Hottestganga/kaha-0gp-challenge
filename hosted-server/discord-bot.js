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

/*
 * Discord matchmaking race data.
 *
 * Key = Discord race post message ID
 */
const discordRaces = new Map();

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
            const rest =
                new REST({ version: "10" })
                    .setToken(token);

            await rest.put(
                Routes.applicationGuildCommands(
                    applicationId,
                    guildId
                ),
                {
                    body: commands
                }
            );

            console.log(
                "[Discord] /race create registered."
            );

        } catch (error) {
            console.error(
                "[Discord] Failed to register commands:",
                error
            );
        }
    }

    function parseBrisbaneDateTime(
        dateText,
        timeText
    ) {
        const dateMatch =
            dateText
                .trim()
                .match(
                    /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/
                );

        if (!dateMatch) {
            return null;
        }

        const day =
            Number(dateMatch[1]);

        const month =
            Number(dateMatch[2]);

        const year =
            Number(dateMatch[3]);

        const time =
            timeText
                .trim()
                .toUpperCase();

        const timeMatch =
            time.match(
                /^(\d{1,2}):(\d{2})\s*(AM|PM)?$/
            );

        if (!timeMatch) {
            return null;
        }

        let hour =
            Number(timeMatch[1]);

        const minute =
            Number(timeMatch[2]);

        const ampm =
            timeMatch[3];

        if (
            minute < 0 ||
            minute > 59
        ) {
            return null;
        }

        if (ampm) {

            if (
                hour < 1 ||
                hour > 12
            ) {
                return null;
            }

            if (
                ampm === "AM" &&
                hour === 12
            ) {
                hour = 0;
            }

            if (
                ampm === "PM" &&
                hour !== 12
            ) {
                hour += 12;
            }

        } else {

            if (
                hour < 0 ||
                hour > 23
            ) {
                return null;
            }
        }

        /*
         * Brisbane / Queensland
         * UTC+10 all year.
         */
        const utcMillis =
            Date.UTC(
                year,
                month - 1,
                day,
                hour - 10,
                minute,
                0
            );

        const check =
            new Date(
                utcMillis +
                (10 * 60 * 60 * 1000)
            );

        if (
            check.getUTCFullYear() !== year ||
            check.getUTCMonth() !== month - 1 ||
            check.getUTCDate() !== day ||
            check.getUTCHours() !== hour ||
            check.getUTCMinutes() !== minute
        ) {
            return null;
        }

        return Math.floor(
            utcMillis / 1000
        );
    }

    function buildRaceEmbed(race) {

        const acceptedCount =
            race.acceptedPlayers.size;

        const currentPlayers =
            1 + acceptedCount;

        const full =
            currentPlayers >= race.maxPlayers;

        return new EmbedBuilder()
            .setTitle(
                full
                    ? "🏁 RACE FULL"
                    : "🏁 NEW RACE — LOOKING FOR RACERS"
            )
            .setDescription(
                full
                    ? "This race has filled all available player slots."
                    : "A new 0GP Race is open for applications."
            )
            .addFields(
                {
                    name: "👑 Host",
                    value:
                        `<@${race.hostId}>`,
                    inline: true
                },
                {
                    name: "👥 Players",
                    value:
                        `${currentPlayers} / ${race.maxPlayers}`,
                    inline: true
                },
                {
                    name: "💰 Starting GP",
                    value:
                    race.startingGp,
                    inline: true
                },
                {
                    name: "⏱️ Duration",
                    value:
                    race.duration,
                    inline: true
                },
                {
                    name: "🕐 Start Time",
                    value:
                        `<t:${race.unixTimestamp}:F>`,
                    inline: false
                },
                {
                    name: "⏳ Starts",
                    value:
                        `<t:${race.unixTimestamp}:R>`,
                    inline: false
                }
            )
            .setFooter({
                text:
                    full
                        ? "🔴 RACE FULL"
                        : "🟢 OPEN — RACERS WANTED"
            })
            .setTimestamp();
    }

    function buildRaceButtons(race) {

        const currentPlayers =
            1 + race.acceptedPlayers.size;

        const full =
            currentPlayers >= race.maxPlayers;

        const applyButton =
            new ButtonBuilder()
                .setCustomId(
                    `applyRace:${race.messageId}`
                )
                .setLabel(
                    full
                        ? "Race Full"
                        : "Apply to Race"
                )
                .setEmoji("🏁")
                .setStyle(
                    full
                        ? ButtonStyle.Secondary
                        : ButtonStyle.Success
                )
                .setDisabled(full);

        return new ActionRowBuilder()
            .addComponents(applyButton);
    }

    async function updateRacePost(race) {
        try {
            const channel =
                await client.channels.fetch(
                    findRaceChannelId
                );

            if (
                !channel ||
                !channel.isTextBased()
            ) {
                return;
            }

            const message =
                await channel.messages.fetch(
                    race.messageId
                );

            await message.edit({
                embeds: [
                    buildRaceEmbed(race)
                ],
                components: [
                    buildRaceButtons(race)
                ]
            });

        } catch (error) {
            console.error(
                "[Discord] Failed to update race post:",
                error
            );
        }
    }

    client.once(
        "clientReady",
        async () => {

            console.log(
                `[Discord] Bot online as ${client.user.tag}`
            );

            await registerCommands();
        }
    );

    client.on(
        "interactionCreate",
        async interaction => {

            /*
             * /race create
             */
            if (
                interaction.isChatInputCommand()
            ) {
                if (
                    interaction.commandName === "race" &&
                    interaction.options.getSubcommand() === "create"
                ) {
                    const modal =
                        new ModalBuilder()
                            .setCustomId(
                                "createRaceModal"
                            )
                            .setTitle(
                                "🏁 Create a 0GP Race"
                            );

                    const durationInput =
                        new TextInputBuilder()
                            .setCustomId(
                                "duration"
                            )
                            .setLabel(
                                "Race Duration"
                            )
                            .setPlaceholder(
                                "Example: 1 Hour, 2 Hours, 30 Minutes"
                            )
                            .setStyle(
                                TextInputStyle.Short
                            )
                            .setRequired(true)
                            .setMaxLength(30);

                    const startingGpInput =
                        new TextInputBuilder()
                            .setCustomId(
                                "startingGp"
                            )
                            .setLabel(
                                "Starting GP"
                            )
                            .setPlaceholder(
                                "Example: 0, 100K, 1M"
                            )
                            .setStyle(
                                TextInputStyle.Short
                            )
                            .setRequired(true)
                            .setMaxLength(30);

                    const playersInput =
                        new TextInputBuilder()
                            .setCustomId(
                                "players"
                            )
                            .setLabel(
                                "Maximum Players"
                            )
                            .setPlaceholder(
                                "Example: 4, 8, 10"
                            )
                            .setStyle(
                                TextInputStyle.Short
                            )
                            .setRequired(true)
                            .setMaxLength(3);

                    const startDateInput =
                        new TextInputBuilder()
                            .setCustomId(
                                "startDate"
                            )
                            .setLabel(
                                "Start Date"
                            )
                            .setPlaceholder(
                                "Example: 12/08/2026"
                            )
                            .setStyle(
                                TextInputStyle.Short
                            )
                            .setRequired(true)
                            .setMaxLength(10);

                    const startTimeInput =
                        new TextInputBuilder()
                            .setCustomId(
                                "startTime"
                            )
                            .setLabel(
                                "Start Time - Brisbane Time"
                            )
                            .setPlaceholder(
                                "Example: 11:00 AM"
                            )
                            .setStyle(
                                TextInputStyle.Short
                            )
                            .setRequired(true)
                            .setMaxLength(20);

                    modal.addComponents(
                        new ActionRowBuilder()
                            .addComponents(
                                durationInput
                            ),

                        new ActionRowBuilder()
                            .addComponents(
                                startingGpInput
                            ),

                        new ActionRowBuilder()
                            .addComponents(
                                playersInput
                            ),

                        new ActionRowBuilder()
                            .addComponents(
                                startDateInput
                            ),

                        new ActionRowBuilder()
                            .addComponents(
                                startTimeInput
                            )
                    );

                    await interaction.showModal(
                        modal
                    );

                    return;
                }
            }

            /*
             * Race form submitted
             */
            if (
                interaction.isModalSubmit() &&
                interaction.customId ===
                "createRaceModal"
            ) {

                const duration =
                    interaction.fields
                        .getTextInputValue(
                            "duration"
                        );

                const startingGp =
                    interaction.fields
                        .getTextInputValue(
                            "startingGp"
                        );

                const playersText =
                    interaction.fields
                        .getTextInputValue(
                            "players"
                        );

                const startDate =
                    interaction.fields
                        .getTextInputValue(
                            "startDate"
                        );

                const startTime =
                    interaction.fields
                        .getTextInputValue(
                            "startTime"
                        );

                const maxPlayers =
                    Number(playersText);

                if (
                    !Number.isInteger(
                        maxPlayers
                    ) ||
                    maxPlayers < 2 ||
                    maxPlayers > 100
                ) {
                    await interaction.reply({
                        content:
                            "❌ Maximum Players must be a number between 2 and 100.",
                        flags:
                        MessageFlags.Ephemeral
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
                        flags:
                        MessageFlags.Ephemeral
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
                            flags:
                            MessageFlags.Ephemeral
                        });

                        return;
                    }

                    /*
                     * Send initial post first.
                     * We need Discord's message ID
                     * to use as the race ID.
                     */
                    const temporaryRace = {
                        hostId:
                        interaction.user.id,

                        hostTag:
                        interaction.user.tag,

                        duration,
                        startingGp,
                        maxPlayers,
                        unixTimestamp,

                        acceptedPlayers:
                            new Set(),

                        pendingPlayers:
                            new Set(),

                        messageId:
                            "pending"
                    };

                    const raceMessage =
                        await findRaceChannel.send({
                            embeds: [
                                buildRaceEmbed(
                                    temporaryRace
                                )
                            ]
                        });

                    temporaryRace.messageId =
                        raceMessage.id;

                    discordRaces.set(
                        raceMessage.id,
                        temporaryRace
                    );

                    await raceMessage.edit({
                        embeds: [
                            buildRaceEmbed(
                                temporaryRace
                            )
                        ],
                        components: [
                            buildRaceButtons(
                                temporaryRace
                            )
                        ]
                    });

                    await interaction.reply({
                        content:
                            `✅ Your race has been posted in <#${findRaceChannelId}>.`,
                        flags:
                        MessageFlags.Ephemeral
                    });

                    console.log(
                        `[Discord] Race ${raceMessage.id} posted by ${interaction.user.tag}`
                    );

                } catch (error) {

                    console.error(
                        "[Discord] Failed to post race:",
                        error
                    );

                    if (
                        !interaction.replied
                    ) {
                        await interaction.reply({
                            content:
                                "❌ Something went wrong while posting the race.",
                            flags:
                            MessageFlags.Ephemeral
                        });
                    }
                }

                return;
            }

            /*
             * APPLY TO RACE
             */
            if (
                interaction.isButton() &&
                interaction.customId.startsWith(
                    "applyRace:"
                )
            ) {

                const raceId =
                    interaction.customId
                        .split(":")[1];

                const race =
                    discordRaces.get(
                        raceId
                    );

                if (!race) {
                    await interaction.reply({
                        content:
                            "❌ This race is no longer active. The bot may have restarted.",
                        flags:
                        MessageFlags.Ephemeral
                    });

                    return;
                }

                const applicantId =
                    interaction.user.id;

                if (
                    applicantId ===
                    race.hostId
                ) {
                    await interaction.reply({
                        content:
                            "👑 You're the host of this race — you don't need to apply.",
                        flags:
                        MessageFlags.Ephemeral
                    });

                    return;
                }

                if (
                    race.acceptedPlayers.has(
                        applicantId
                    )
                ) {
                    await interaction.reply({
                        content:
                            "✅ You're already accepted into this race.",
                        flags:
                        MessageFlags.Ephemeral
                    });

                    return;
                }

                if (
                    race.pendingPlayers.has(
                        applicantId
                    )
                ) {
                    await interaction.reply({
                        content:
                            "⏳ You've already applied to this race. The host hasn't responded yet.",
                        flags:
                        MessageFlags.Ephemeral
                    });

                    return;
                }

                const currentPlayers =
                    1 +
                    race.acceptedPlayers.size;

                if (
                    currentPlayers >=
                    race.maxPlayers
                ) {
                    await interaction.reply({
                        content:
                            "❌ This race is already full.",
                        flags:
                        MessageFlags.Ephemeral
                    });

                    return;
                }

                try {
                    const host =
                        await client.users.fetch(
                            race.hostId
                        );

                    const acceptButton =
                        new ButtonBuilder()
                            .setCustomId(
                                `acceptRace:${raceId}:${applicantId}`
                            )
                            .setLabel(
                                "Accept"
                            )
                            .setEmoji("✅")
                            .setStyle(
                                ButtonStyle.Success
                            );

                    const declineButton =
                        new ButtonBuilder()
                            .setCustomId(
                                `declineRace:${raceId}:${applicantId}`
                            )
                            .setLabel(
                                "Decline"
                            )
                            .setEmoji("❌")
                            .setStyle(
                                ButtonStyle.Danger
                            );

                    const approvalRow =
                        new ActionRowBuilder()
                            .addComponents(
                                acceptButton,
                                declineButton
                            );

                    await host.send({
                        content:
                            `🏁 **New Race Application**\n\n` +
                            `**Player:** <@${applicantId}>\n` +
                            `**Duration:** ${race.duration}\n` +
                            `**Starting GP:** ${race.startingGp}\n` +
                            `**Start:** <t:${race.unixTimestamp}:F>\n` +
                            `**Starts:** <t:${race.unixTimestamp}:R>\n\n` +
                            `Would you like to accept this player?`,
                        components: [
                            approvalRow
                        ]
                    });

                    race.pendingPlayers.add(
                        applicantId
                    );

                    await interaction.reply({
                        content:
                            "✅ Application sent to the race host. You'll be notified when they accept or decline you.",
                        flags:
                        MessageFlags.Ephemeral
                    });

                    console.log(
                        `[Discord] ${interaction.user.tag} applied to race ${raceId}`
                    );

                } catch (error) {

                    console.error(
                        "[Discord] Failed to send host application DM:",
                        error
                    );

                    await interaction.reply({
                        content:
                            "❌ I couldn't send the race host a DM. The host may have Discord DMs disabled.",
                        flags:
                        MessageFlags.Ephemeral
                    });
                }

                return;
            }

            /*
             * ACCEPT APPLICANT
             */
            if (
                interaction.isButton() &&
                interaction.customId.startsWith(
                    "acceptRace:"
                )
            ) {

                const parts =
                    interaction.customId
                        .split(":");

                const raceId =
                    parts[1];

                const applicantId =
                    parts[2];

                const race =
                    discordRaces.get(
                        raceId
                    );

                if (!race) {
                    await interaction.reply({
                        content:
                            "❌ This race is no longer active.",
                        flags:
                        MessageFlags.Ephemeral
                    });

                    return;
                }

                if (
                    interaction.user.id !==
                    race.hostId
                ) {
                    await interaction.reply({
                        content:
                            "❌ Only the race host can accept applicants.",
                        flags:
                        MessageFlags.Ephemeral
                    });

                    return;
                }

                if (
                    !race.pendingPlayers.has(
                        applicantId
                    )
                ) {
                    await interaction.reply({
                        content:
                            "⚠️ This application has already been handled.",
                        flags:
                        MessageFlags.Ephemeral
                    });

                    return;
                }

                const currentPlayers =
                    1 +
                    race.acceptedPlayers.size;

                if (
                    currentPlayers >=
                    race.maxPlayers
                ) {
                    race.pendingPlayers.delete(
                        applicantId
                    );

                    await interaction.update({
                        content:
                            "❌ Race is already full. This applicant could not be accepted.",
                        components: []
                    });

                    return;
                }

                race.pendingPlayers.delete(
                    applicantId
                );

                race.acceptedPlayers.add(
                    applicantId
                );

                await interaction.update({
                    content:
                        `✅ <@${applicantId}> has been accepted into the race.`,
                    components: []
                });

                await updateRacePost(
                    race
                );

                try {
                    const applicant =
                        await client.users.fetch(
                            applicantId
                        );

                    await applicant.send(
                        `🏁 **Application Accepted!**\n\n` +
                        `You've been accepted into <@${race.hostId}>'s 0GP Race.\n\n` +
                        `**Duration:** ${race.duration}\n` +
                        `**Starting GP:** ${race.startingGp}\n` +
                        `**Start:** <t:${race.unixTimestamp}:F>\n` +
                        `**Starts:** <t:${race.unixTimestamp}:R>\n\n` +
                        `The private RuneLite room code will be added to this system next.`
                    );

                } catch (error) {
                    console.error(
                        "[Discord] Could not DM accepted applicant:",
                        error
                    );
                }

                console.log(
                    `[Discord] Applicant ${applicantId} accepted into race ${raceId}`
                );

                return;
            }

            /*
             * DECLINE APPLICANT
             */
            if (
                interaction.isButton() &&
                interaction.customId.startsWith(
                    "declineRace:"
                )
            ) {

                const parts =
                    interaction.customId
                        .split(":");

                const raceId =
                    parts[1];

                const applicantId =
                    parts[2];

                const race =
                    discordRaces.get(
                        raceId
                    );

                if (!race) {
                    await interaction.reply({
                        content:
                            "❌ This race is no longer active.",
                        flags:
                        MessageFlags.Ephemeral
                    });

                    return;
                }

                if (
                    interaction.user.id !==
                    race.hostId
                ) {
                    await interaction.reply({
                        content:
                            "❌ Only the race host can decline applicants.",
                        flags:
                        MessageFlags.Ephemeral
                    });

                    return;
                }

                if (
                    !race.pendingPlayers.has(
                        applicantId
                    )
                ) {
                    await interaction.reply({
                        content:
                            "⚠️ This application has already been handled.",
                        flags:
                        MessageFlags.Ephemeral
                    });

                    return;
                }

                race.pendingPlayers.delete(
                    applicantId
                );

                await interaction.update({
                    content:
                        `❌ <@${applicantId}>'s application was declined.`,
                    components: []
                });

                try {
                    const applicant =
                        await client.users.fetch(
                            applicantId
                        );

                    await applicant.send(
                        `❌ **Race Application Declined**\n\n` +
                        `Your application for <@${race.hostId}>'s 0GP Race wasn't accepted this time.\n\n` +
                        `You can apply for another race in <#${findRaceChannelId}>.`
                    );

                } catch (error) {
                    console.error(
                        "[Discord] Could not DM declined applicant:",
                        error
                    );
                }

                console.log(
                    `[Discord] Applicant ${applicantId} declined from race ${raceId}`
                );

                return;
            }
        }
    );

    client.login(token)
        .catch(error => {
            console.error(
                "[Discord] Failed to log in:",
                error
            );
        });
}