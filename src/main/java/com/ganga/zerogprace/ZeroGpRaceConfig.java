package com.ganga.zerogprace;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(ZeroGpRaceConfig.GROUP)
public interface ZeroGpRaceConfig extends Config
{
    String GROUP = "zerogprace";

    @ConfigItem(
            keyName = "multiplayerEnabled",
            name = "Multiplayer enabled",
            description = "Connects to the 0GP Race third-party multiplayer server. "
                    + "When enabled, the plugin may send your RuneScape display name, race room code, "
                    + "race score, race status, remaining race time, and race transaction data required "
                    + "for multiplayer scoring and verification.",
            position = 0
    )
    default boolean multiplayerEnabled()
    {
        return true;
    }

    @ConfigItem(
            keyName = "apiUrl",
            name = "Multiplayer API URL",
            description = "Address of the third-party 0GP Race multiplayer API.",
            position = 1
    )
    default String apiUrl()
    {
        return "https://kaha-0gp-challenge.onrender.com";
    }

    @ConfigItem(
            keyName = "dashboardUrl",
            name = "Dashboard URL",
            description = "Public HTTPS address for the 0GP Race live dashboard.",
            position = 2
    )
    default String dashboardUrl()
    {
        return "";
    }

    @ConfigItem(
            keyName = "roomCode",
            name = "Room code",
            description = "Optional saved multiplayer race room code.",
            position = 3
    )
    default String roomCode()
    {
        return "";
    }

    @ConfigItem(
            keyName = "trackingEnabled",
            name = "Legacy pickup API",
            description = "Legacy pickup-only sync from early alpha builds. Leave disabled.",
            position = 4
    )
    default boolean trackingEnabled()
    {
        return false;
    }
}