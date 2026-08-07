package com.ganga.zerogprace.model;

import java.util.Locale;

public enum RaceSource
{
    ALLOWANCE,
    NPC_LOOT,
    PVP_LOOT,
    PICKPOCKET,
    THIEVING,
    WORLD_SPAWN,
    RACE_ITEM,
    BANK_WITHDRAWAL,
    BANK_RETURN,
    RACE_BANK_DEPOSIT,
    RACE_BANK_WITHDRAWAL,
    STARTING_INVENTORY,
    STARTING_EQUIPMENT,
    LOGIN,
    LOGOUT,
    SYSTEM,
    UNKNOWN;

    public static RaceSource fromLabel(String label)
    {
        if (label == null)
        {
            return UNKNOWN;
        }

        String normalised = label.trim().toUpperCase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_');

        if ("NPC".equals(normalised)) return NPC_LOOT;
        if ("PVP".equals(normalised)) return PVP_LOOT;
        if ("WORLD_SPAWN".equals(normalised)) return WORLD_SPAWN;
        if ("RACE_ITEM".equals(normalised)) return RACE_ITEM;
        if ("BANK_RETURN".equals(normalised)) return BANK_RETURN;
        if ("RACE_BANK_DEPOSIT".equals(normalised)) return RACE_BANK_DEPOSIT;
        if ("RACE_BANK_WITHDRAWAL".equals(normalised)) return RACE_BANK_WITHDRAWAL;
        if ("STARTING_INVENTORY".equals(normalised)) return STARTING_INVENTORY;
        if ("STARTING_EQUIPMENT".equals(normalised)) return STARTING_EQUIPMENT;
        if (normalised.contains("BANK") && normalised.contains("WITHDRAW")) return BANK_WITHDRAWAL;

        try
        {
            return valueOf(normalised);
        }
        catch (IllegalArgumentException ex)
        {
            return UNKNOWN;
        }
    }
}
