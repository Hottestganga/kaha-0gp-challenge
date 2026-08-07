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
        description = "Connect Create/Join Race to the multiplayer API",
        position = 0
    )
    default boolean multiplayerEnabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "apiUrl",
        name = "Multiplayer API URL",
        description = "Hosted API address. Use your public HTTPS server URL, or http://127.0.0.1:8787 for local testing",
        position = 1
    )
    default String apiUrl()
    {
        return "http://127.0.0.1:8787";
    }

    @ConfigItem(
        keyName = "dashboardUrl",
        name = "Dashboard URL",
        description = "Public HTTPS address for the future race dashboard",
        position = 2
    )
    default String dashboardUrl()
    {
        return "";
    }

    @ConfigItem(
        keyName = "roomCode",
        name = "Room code",
        description = "Optional saved room code",
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
