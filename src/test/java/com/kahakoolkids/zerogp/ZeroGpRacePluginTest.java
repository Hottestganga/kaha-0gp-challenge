package com.kahakoolkids.zerogp;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ZeroGpRacePluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(ZeroGpRacePlugin.class);
        RuneLite.main(args);
    }
}
