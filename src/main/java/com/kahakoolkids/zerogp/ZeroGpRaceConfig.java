package com.kahakoolkids.zerogp;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(ZeroGpRaceConfig.GROUP)
public interface ZeroGpRaceConfig extends Config
{
    String GROUP = "kahazerogp";

    @ConfigItem(
        keyName = "dashboardUrl",
        name = "Dashboard URL",
        description = "Public HTTPS address for the race dashboard",
        position = 0
    )
    default String dashboardUrl()
    {
        return "";
    }

    @ConfigItem(
        keyName = "roomCode",
        name = "Room code",
        description = "Room code supplied by the race host",
        position = 1
    )
    default String roomCode()
    {
        return "";
    }

    @ConfigItem(
        keyName = "trackingEnabled",
        name = "Tracking enabled",
        description = "Send eligible picked-up loot to the race server",
        position = 2
    )
    default boolean trackingEnabled()
    {
        return false;
    }
}
