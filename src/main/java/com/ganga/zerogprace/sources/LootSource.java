package com.ganga.zerogprace.sources;

import com.ganga.zerogprace.model.RaceSource;

/**
 * Marker contract for future source-specific detectors. Existing v0.9 source
 * detection remains in ZeroGpRacePlugin while it is migrated incrementally.
 */
public interface LootSource
{
    RaceSource getSource();
    String getDisplayName();
}
