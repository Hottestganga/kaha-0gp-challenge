package com.ganga.zerogprace;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.ganga.zerogprace.network.CreateRoomRequest;
import com.ganga.zerogprace.network.JoinRoomRequest;
import com.ganga.zerogprace.network.LeaveRoomRequest;
import com.ganga.zerogprace.network.MultiplayerClient;
import com.ganga.zerogprace.network.RaceRoomSnapshot;
import com.ganga.zerogprace.network.RaceSyncPayload;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
    name = "0GP Race",
    description = "Tracks race-earned wealth during timed 0GP races",
    tags = {"race", "loot", "challenge", "pvp", "clan"}
)
public class ZeroGpRacePlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(ZeroGpRacePlugin.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long DROP_LIFETIME_MS = 300_000L;
    private static final long TAKE_CLICK_LIFETIME_MS = 12_000L;
    private static final long DIRECT_GAIN_LIFETIME_MS = 2_000L;
    private static final long REWARD_GAIN_LIFETIME_MS = 12_000L;
    private static final long SKILL_GAIN_LIFETIME_MS = 3_000L;
    private static final long RACE_ITEM_OPEN_LIFETIME_MS = 3_000L;
    private static final long MULTIPLAYER_SYNC_INTERVAL_MS = 2_000L;

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private OkHttpClient httpClient;
    @Inject private Gson gson;
    @Inject private ItemManager itemManager;
    @Inject private ZeroGpRaceConfig config;
    @Inject private ClientToolbar clientToolbar;

    private final Map<Integer, Integer> previousInventory = new HashMap<>();
    private final Map<Integer, Integer> previousBank = new HashMap<>();
    private final Map<Integer, Integer> raceOwnedInventory = new HashMap<>();
    private final Map<Integer, Integer> raceOwnedBank = new HashMap<>();
    // Quantity of pre-race/imported items currently outside protected storage.
    // Returning matching quantities to the bank refunds the original score debit.
    private final Map<Integer, Integer> importedOutstanding = new HashMap<>();
    private final Deque<EligibleDrop> eligibleDrops = new ArrayDeque<>();
    private final Deque<TakeClick> takeClicks = new ArrayDeque<>();
    private final Deque<DirectGainSource> directGainSources = new ArrayDeque<>();

    private ZeroGpRacePanel panel;
    private NavigationButton navigationButton;
    private MultiplayerClient multiplayerClient;
    private long multiplayerSequence;
    private long lastMultiplayerSyncAt;
    private String multiplayerPlayerName = "";
    private boolean inventoryPrimed;
    private boolean bankPrimed;
    private String recentSkillSource = "";
    private long recentSkillSourceExpiresAt;

    @Provides
    ZeroGpRaceConfig provideConfig(ConfigManager manager)
    {
        return manager.getConfig(ZeroGpRaceConfig.class);
    }

    @Override
    protected void startUp()
    {
        resetTrackingState();
        panel = new ZeroGpRacePanel(this);
        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/panel_icon.png");
        navigationButton = NavigationButton.builder()
            .tooltip("0GP Race")
            .icon(icon)
            .priority(8)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navigationButton);

        multiplayerClient = new MultiplayerClient(httpClient, gson);
        lastMultiplayerSyncAt = 0L;
        panel.setMultiplayerStatus(config.multiplayerEnabled() ? "Ready" : "Local only");

        if (client.getGameState() == GameState.LOGGED_IN)
        {
            panel.onLoggedIn(currentPlayerName());
            primeInventory();
        }
        else
        {
            panel.onLoggedOut();
        }
    }

    @Override
    protected void shutDown()
    {
        if (multiplayerClient != null)
        {
            multiplayerClient.reset();
            multiplayerClient = null;
        }

        if (navigationButton != null)
        {
            clientToolbar.removeNavigation(navigationButton);
            navigationButton = null;
        }
        panel = null;
        multiplayerPlayerName = "";
        lastMultiplayerSyncAt = 0L;
        resetTrackingState();
    }

    void createMultiplayerRoom(String raceName, String roomCode, long durationMilliseconds, long startingAllowance)
    {
        if (!config.multiplayerEnabled())
        {
            if (panel != null)
            {
                panel.setMultiplayerStatus("Local only");
            }
            return;
        }

        String apiBase = multiplayerApiBase();
        String player = currentPlayerName();
        if (apiBase == null || player.isEmpty() || multiplayerClient == null || panel == null)
        {
            if (panel != null)
            {
                panel.onMultiplayerError(apiBase == null ? "Set a valid Multiplayer API URL" : "Log in before creating an online room");
            }
            return;
        }

        multiplayerPlayerName = player;
        panel.setMultiplayerStatus("Creating...");
        CreateRoomRequest request = new CreateRoomRequest(
            roomCode, raceName, durationMilliseconds, startingAllowance, player,
            panel.getScore(), panel.getRemainingMilliseconds(), panel.isLoggedInForSync());

        multiplayerClient.createRoom(apiBase, request, new MultiplayerClient.ResultCallback()
        {
            @Override
            public void onSuccess(RaceRoomSnapshot room)
            {
                SwingUtilities.invokeLater(() ->
                {
                    if (panel != null)
                    {
                        panel.onMultiplayerRoomCreated(room);
                    }
                });
            }

            @Override
            public void onError(String message)
            {
                SwingUtilities.invokeLater(() ->
                {
                    if (panel != null)
                    {
                        panel.onMultiplayerError(message);
                    }
                });
            }
        });
    }

    void joinMultiplayerRoom(String roomCode)
    {
        if (!config.multiplayerEnabled())
        {
            if (panel != null)
            {
                panel.onMultiplayerError("Enable multiplayer in plugin settings");
            }
            return;
        }

        String apiBase = multiplayerApiBase();
        String player = currentPlayerName();
        if (apiBase == null || player.isEmpty() || multiplayerClient == null)
        {
            if (panel != null)
            {
                panel.onMultiplayerError(apiBase == null ? "Set a valid Multiplayer API URL" : "Log in before joining a room");
            }
            return;
        }

        multiplayerPlayerName = player;

        multiplayerClient.joinRoom(apiBase, new JoinRoomRequest(normaliseRoom(roomCode), player),
            new MultiplayerClient.ResultCallback()
            {
                @Override
                public void onSuccess(RaceRoomSnapshot room)
                {
                    SwingUtilities.invokeLater(() ->
                    {
                        if (panel != null)
                        {
                            panel.startJoinedRace(room);
                        }
                    });
                }

                @Override
                public void onError(String message)
                {
                    SwingUtilities.invokeLater(() ->
                    {
                        if (panel != null)
                        {
                            panel.onMultiplayerError(message);
                        }
                    });
                }
            });
    }

    void leaveMultiplayerRoom(String roomCode)
    {
        String apiBase = multiplayerApiBase();
        String player = multiplayerPlayerName.isEmpty() ? currentPlayerName() : multiplayerPlayerName;
        String room = normaliseRoom(roomCode);
        if (!config.multiplayerEnabled() || apiBase == null || player.isEmpty()
            || room.isEmpty() || multiplayerClient == null)
        {
            return;
        }

        multiplayerClient.leave(apiBase, new LeaveRoomRequest(room, player), new MultiplayerClient.ResultCallback()
        {
            @Override
            public void onSuccess(RaceRoomSnapshot snapshot)
            {
                // No UI update required after leaving.
            }

            @Override
            public void onError(String message)
            {
                log.debug("Unable to leave multiplayer room cleanly: {}", message);
            }
        });
    }

    private void syncMultiplayerState()
    {
        if (!config.multiplayerEnabled() || multiplayerClient == null || panel == null)
        {
            return;
        }

        String room = normaliseRoom(panel.getActiveRoomCode());
        String player = multiplayerPlayerName;

        if (player.isEmpty())
        {
            player = panel.getCurrentPlayerName();
        }

        if (player.isEmpty())
        {
            player = currentPlayerName();
        }

        String apiBase = multiplayerApiBase();

        if (room.isEmpty() || player.isEmpty() || apiBase == null)
        {
            return;
        }

        // Keep a player name even after RuneLite clears client.getLocalPlayer() on logout.
        multiplayerPlayerName = player;

        // RuneLite GameState is the source of truth for online/paused state.
        boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;

        String raceState = panel.getMultiplayerState();
        if (panel.isRaceRunning())
        {
            raceState = loggedIn ? "RUNNING" : "PAUSED";
        }

        long remainingMilliseconds = panel.getRemainingMilliseconds();

        log.info(
            "0GP SYNC | room={} | player={} | gameState={} | loggedIn={} | raceState={} | remainingMs={}",
            room,
            player,
            client.getGameState(),
            loggedIn,
            raceState,
            remainingMilliseconds
        );

        RaceSyncPayload payload = new RaceSyncPayload(
            room,
            player,
            panel.getScore(),
            remainingMilliseconds,
            loggedIn,
            raceState,
            ++multiplayerSequence
        );

        multiplayerClient.sync(apiBase, payload, new MultiplayerClient.ResultCallback()
        {
            @Override
            public void onSuccess(RaceRoomSnapshot snapshot)
            {
                SwingUtilities.invokeLater(() ->
                {
                    if (panel != null && room.equalsIgnoreCase(panel.getActiveRoomCode()))
                    {
                        panel.setMultiplayerStatus("Connected");
                        panel.updateMultiplayerPlayers(snapshot);
                    }
                });
            }

            @Override
            public void onError(String message)
            {
                SwingUtilities.invokeLater(() ->
                {
                    if (panel != null && room.equalsIgnoreCase(panel.getActiveRoomCode()))
                    {
                        panel.setMultiplayerStatus("Reconnecting...");
                    }
                });
            }
        });
    }

    private String multiplayerApiBase()
    {
        String value = config.apiUrl();
        if (value == null)
        {
            return null;
        }
        String result = value.trim().replaceAll("/+$", "");
        return result.startsWith("http://") || result.startsWith("https://") ? result : null;
    }

    void openDashboard(String roomCode)
    {
        String base = cleanHttpsBase(config.dashboardUrl());
        if (base == null)
        {
            LinkBrowser.browse("https://github.com/Hottestganga/kaha-0gp-challenge");
            return;
        }

        String room = normaliseRoom(roomCode);
        String url = room.isEmpty() ? base : base + (base.contains("?") ? "&" : "?")
            + "room=" + URLEncoder.encode(room, StandardCharsets.UTF_8);
        LinkBrowser.browse(url);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (panel == null)
        {
            return;
        }

        if (event.getGameState() == GameState.LOGGED_IN)
        {
            String playerName = currentPlayerName();
            if (!playerName.isEmpty())
            {
                multiplayerPlayerName = playerName;
            }

            // Push RUNNING immediately using RuneLite's real game state.
            syncMultiplayerState();

            SwingUtilities.invokeLater(() ->
            {
                if (panel != null)
                {
                    panel.onLoggedIn(playerName);
                }
            });

            primeInventory();
            return;
        }

        switch (event.getGameState())
        {
            case LOGIN_SCREEN:
            case LOGIN_SCREEN_AUTHENTICATOR:
            case CONNECTION_LOST:
            case HOPPING:
                previousInventory.clear();
                inventoryPrimed = false;

                // Push PAUSED immediately, before client.getLocalPlayer() disappears.
                syncMultiplayerState();

                SwingUtilities.invokeLater(() ->
                {
                    if (panel != null)
                    {
                        panel.onLoggedOut();
                    }
                });
                break;

            default:
                break;
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        long now = System.currentTimeMillis();
        if (now - lastMultiplayerSyncAt < MULTIPLAYER_SYNC_INTERVAL_MS)
        {
            return;
        }

        lastMultiplayerSyncAt = now;
        syncMultiplayerState();
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        if (!canTrackLoot() || event == null)
        {
            return;
        }

        String source = skillSource(event.getSkill());
        if (source != null)
        {
            armSkillSource(source);
        }
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event)
    {
        rememberDrops(event.getItems(), "NPC");
    }

    @Subscribe
    public void onPlayerLootReceived(PlayerLootReceived event)
    {
        rememberDrops(event.getItems(), "PVP");
    }

    @Subscribe
    public void onItemSpawned(ItemSpawned event)
    {
        if (!canTrackLoot())
        {
            return;
        }

        TileItem item = event.getItem();
        if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
        {
            return;
        }

        // Natural map spawns have no player ownership. Player-dropped items are
        // SELF/OTHER/GROUP and are deliberately not made eligible here. NPC and
        // PvP loot are already registered by their dedicated loot events.
        if (item.getOwnership() == TileItem.OWNERSHIP_NONE)
        {
            eligibleDrops.addLast(new EligibleDrop(
                item.getId(),
                item.getQuantity(),
                "WORLD SPAWN",
                System.currentTimeMillis() + DROP_LIFETIME_MS));
            purgeExpired();
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (!canTrackLoot())
        {
            return;
        }

        String option = event.getMenuOption();
        String target = stripTags(event.getMenuTarget());

        // Explicitly exclude stored-point / pre-stackable reward systems.
        if (isExcludedStoredRewardInteraction(option, target))
        {
            return;
        }

        // Keyed chests only count when the key itself was earned during the race.
        DirectGainSource keyedReward = classifyKeyedRewardInteraction(option, target);
        if (keyedReward != null)
        {
            directGainSources.addLast(keyedReward);
            purgeExpired();
            return;
        }

        // Raids, Gauntlet, Barrows and other immediate reward chests.
        String rewardSource = classifyRewardInteraction(option, target, event.getMenuAction());
        if (rewardSource != null)
        {
            directGainSources.addLast(new DirectGainSource(
                rewardSource,
                -1,
                System.currentTimeMillis() + REWARD_GAIN_LIFETIME_MS));
            purgeExpired();
            return;
        }

        if ("Pickpocket".equalsIgnoreCase(option))
        {
            directGainSources.addLast(new DirectGainSource(
                "THIEVING",
                -1,
                System.currentTimeMillis() + DIRECT_GAIN_LIFETIME_MS));
            purgeExpired();
            return;
        }

        if (isThievingObjectAction(option, event.getMenuAction()))
        {
            directGainSources.addLast(new DirectGainSource(
                "THIEVING",
                -1,
                System.currentTimeMillis() + DIRECT_GAIN_LIFETIME_MS));
            purgeExpired();
            return;
        }

        // Menu-backed skilling provenance catches the first product even when
        // RuneLite's StatChanged event arrives after the inventory update.
        String skillSource = classifySkillInteraction(option, target);
        if (skillSource != null)
        {
            armSkillSource(skillSource);

            // Use a direct source too so a single click that immediately creates
            // an item is accepted before the XP event arrives.
            directGainSources.addLast(new DirectGainSource(
                skillSource,
                -1,
                System.currentTimeMillis() + SKILL_GAIN_LIFETIME_MS));
            purgeExpired();
        }

        // A race-earned clue casket / impling jar / container passes provenance
        // into the contents. Pre-race containers do not count.
        if (isRaceOwnedContainerAction(option))
        {
            int itemId = event.getItemId();
            if (itemId > 0 && raceOwnedInventory.getOrDefault(itemId, 0) > 0)
            {
                String itemName = itemManager.getItemComposition(itemId).getName();
                if (!isExcludedStoredRewardItem(itemName))
                {
                    String source = itemName.toLowerCase().contains("casket")
                        ? "CLUE REWARD"
                        : "RACE ITEM";

                    directGainSources.addLast(new DirectGainSource(
                        source,
                        itemId,
                        System.currentTimeMillis() + RACE_ITEM_OPEN_LIFETIME_MS));
                    purgeExpired();
                    return;
                }
            }
        }

        if (!"Take".equalsIgnoreCase(option))
        {
            return;
        }

        MenuAction action = event.getMenuAction();
        if (action != MenuAction.GROUND_ITEM_FIRST_OPTION
            && action != MenuAction.GROUND_ITEM_SECOND_OPTION
            && action != MenuAction.GROUND_ITEM_THIRD_OPTION
            && action != MenuAction.GROUND_ITEM_FOURTH_OPTION
            && action != MenuAction.GROUND_ITEM_FIFTH_OPTION)
        {
            return;
        }

        int itemId = event.getId();
        if (itemId > 0 && hasEligibleDrop(itemId))
        {
            takeClicks.addLast(new TakeClick(itemId, System.currentTimeMillis() + TAKE_CLICK_LIFETIME_MS));
        }
        purgeExpired();
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (!canTrackLoot())
        {
            return;
        }

        ItemContainer bank = client.getItemContainer(InventoryID.BANK);
        if (bank != null && event.getItemContainer() == bank)
        {
            handleBankChange(bank);
            return;
        }

        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        if (inventory == null || event.getItemContainer() != inventory)
        {
            return;
        }

        Map<Integer, Integer> current = snapshot(inventory);
        if (!inventoryPrimed)
        {
            previousInventory.clear();
            previousInventory.putAll(current);
            inventoryPrimed = true;
            return;
        }

        purgeExpired();

        DirectGainSource directGain = activeDirectGainSource();
        String directSource = directGain == null ? null : directGain.source;
        String skillSource = activeSkillSource();

        // Direct interaction provenance wins; otherwise use the recent XP skill.
        String earningSource = directSource != null ? directSource : skillSource;

        /*
         * Production skills transform existing value (e.g. logs -> bows,
         * essence -> runes, herbs -> potions). Remove the value of race-owned
         * inputs as they are consumed, then credit the finished output. That
         * makes score change equal to the real value added instead of counting
         * both the input and output.
         */
        if (earningSource != null && isProcessingSkillSource(earningSource))
        {
            consumeProcessingInputs(current, earningSource);
        }

        // Containers / keyed rewards consume a race-owned source item.
        if (directGain != null && directGain.sourceItemId > 0)
        {
            int lost = previousInventory.getOrDefault(directGain.sourceItemId, 0)
                - current.getOrDefault(directGain.sourceItemId, 0);
            if (lost > 0)
            {
                int owned = raceOwnedInventory.getOrDefault(directGain.sourceItemId, 0);
                changeQuantity(raceOwnedInventory, directGain.sourceItemId, -Math.min(lost, owned));

                int imported = importedOutstanding.getOrDefault(directGain.sourceItemId, 0);
                changeQuantity(importedOutstanding, directGain.sourceItemId, -Math.min(lost, imported));
            }
        }

        boolean usedDirectSource = false;

        for (Map.Entry<Integer, Integer> entry : current.entrySet())
        {
            int itemId = entry.getKey();
            int gained = entry.getValue() - previousInventory.getOrDefault(itemId, 0);
            if (gained <= 0)
            {
                continue;
            }

            int remainingGain = gained;

            // NPC/PvP/world-spawn provenance.
            if (consumeTakeClick(itemId))
            {
                AcceptedLoot accepted = consumeEligibleDrop(itemId, remainingGain);
                if (accepted.quantity > 0)
                {
                    acceptPickup(itemId, accepted.quantity, accepted.source);
                    remainingGain -= accepted.quantity;
                }
            }

            // Reward chest / skilling / clue / container provenance.
            if (remainingGain > 0 && earningSource != null)
            {
                acceptPickup(itemId, remainingGain, earningSource);
                usedDirectSource = directSource != null;
            }
        }

        if (directSource != null && usedDirectSource)
        {
            consumeDirectGainSource(directSource);
        }

        /*
         * Clue completion frequently replaces a race-earned clue scroll with a
         * casket in the same inventory update without a useful menu event.
         * Detect that inheritance directly.
         */
        inheritClueCasketIfPresent(current);

        previousInventory.clear();
        previousInventory.putAll(current);
    }

    private void rememberDrops(Iterable<ItemStack> items, String source)
    {
        if (!canTrackLoot())
        {
            return;
        }

        long expiresAt = System.currentTimeMillis() + DROP_LIFETIME_MS;
        for (ItemStack stack : items)
        {
            if (stack.getId() > 0 && stack.getQuantity() > 0)
            {
                eligibleDrops.addLast(new EligibleDrop(stack.getId(), stack.getQuantity(), source, expiresAt));
            }
        }
        purgeExpired();
    }

    private void acceptPickup(int itemId, int quantity, String source)
    {
        ZeroGpRacePanel currentPanel = panel;

        int unitPrice = Math.max(0, itemManager.getItemPrice(itemId));
        long totalValue = (long) unitPrice * quantity;
        String itemName = itemManager.getItemComposition(itemId).getName();

        raceOwnedInventory.merge(itemId, quantity, Integer::sum);

        if (currentPanel != null)
        {
            SwingUtilities.invokeLater(() ->
                currentPanel.addAcceptedLoot(itemName, quantity, totalValue, source));
        }

        log.info("Accepted {} loot: {} x{} worth {} gp", source, itemName, quantity, totalValue);

        String base = cleanHttpsBase(config.dashboardUrl());
        String room = currentPanel == null ? "" : normaliseRoom(currentPanel.getActiveRoomCode());
        if (base == null || room.isEmpty() || !config.trackingEnabled())
        {
            return;
        }

        PickupPayload payload = new PickupPayload(room, currentPlayerName(), itemId, quantity, source);
        Request request = new Request.Builder()
            .url(base + "/api/pickup")
            .post(RequestBody.create(JSON, gson.toJson(payload)))
            .build();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException exception)
            {
                log.warn("Unable to submit 0GP race pickup", exception);
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                response.close();
            }
        });
    }


    private void logNeutralBankEvent(int itemId, int quantity, String source)
    {
        ZeroGpRacePanel currentPanel = panel;
        if (currentPanel == null || quantity <= 0)
        {
            return;
        }

        String itemName = itemManager.getItemComposition(itemId).getName();
        SwingUtilities.invokeLater(() -> currentPanel.addNeutralTransaction(itemName, quantity, source));
    }

    void onLocalRaceStarted()
    {
        resetTrackingState();

        // RuneLite client containers must be read on the client thread.
        // The race starts at a true 0 GP balance: anything already in the
        // inventory or equipment is treated as imported value and debited.
        clientThread.invokeLater(() ->
        {
            primeInventory();
            primeBank();
            primeVisibleWorldSpawns();
            debitStartingHoldings();
        });
    }

    /**
     * RuneLite only emits ItemSpawned when an item enters the loaded scene.
     * A natural map spawn can already be visible before the race starts, so
     * there may be no ItemSpawned event after tracking begins. Seed those
     * currently-visible, unowned ground items when the race starts so the
     * very first pickup is eligible too.
     */
    private void primeVisibleWorldSpawns()
    {
        if (!canTrackLoot())
        {
            return;
        }

        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null)
        {
            return;
        }

        Scene scene = worldView.getScene();
        if (scene == null)
        {
            return;
        }

        long expiresAt = System.currentTimeMillis() + DROP_LIFETIME_MS;
        int primed = 0;
        Tile[][][] tiles = scene.getTiles();
        if (tiles == null)
        {
            return;
        }

        for (Tile[][] plane : tiles)
        {
            if (plane == null)
            {
                continue;
            }

            for (Tile[] column : plane)
            {
                if (column == null)
                {
                    continue;
                }

                for (Tile tile : column)
                {
                    if (tile == null || tile.getGroundItems() == null)
                    {
                        continue;
                    }

                    for (TileItem item : tile.getGroundItems())
                    {
                        if (item != null
                            && item.getId() > 0
                            && item.getQuantity() > 0
                            && item.getOwnership() == TileItem.OWNERSHIP_NONE)
                        {
                            eligibleDrops.addLast(new EligibleDrop(
                                item.getId(),
                                item.getQuantity(),
                                "WORLD SPAWN",
                                expiresAt));
                            primed++;
                        }
                    }
                }
            }
        }

        purgeExpired();
        log.debug("Primed {} visible world-spawn ground item stacks at race start", primed);
    }

    private void debitStartingHoldings()
    {
        debitContainer(client.getItemContainer(InventoryID.INVENTORY), "STARTING INVENTORY");
        debitContainer(client.getItemContainer(InventoryID.EQUIPMENT), "STARTING EQUIPMENT");
    }

    private void debitContainer(ItemContainer container, String source)
    {
        if (container == null || panel == null || !panel.isRaceRunning())
        {
            return;
        }

        Map<Integer, Integer> items = snapshot(container);
        for (Map.Entry<Integer, Integer> entry : items.entrySet())
        {
            debitImported(entry.getKey(), entry.getValue(), source);
            if (panel == null || !panel.isRaceRunning())
            {
                return;
            }
        }
    }

    private void primeBank()
    {
        ItemContainer bank = client.getItemContainer(InventoryID.BANK);
        previousBank.clear();
        if (bank == null)
        {
            bankPrimed = false;
            return;
        }

        previousBank.putAll(snapshot(bank));
        bankPrimed = true;
    }

    private void handleBankChange(ItemContainer bank)
    {
        Map<Integer, Integer> current = snapshot(bank);
        if (!bankPrimed)
        {
            previousBank.clear();
            previousBank.putAll(current);
            bankPrimed = true;
            return;
        }

        // Deposits first repay any outstanding imported/pre-race quantity. That
        // reverses the debit originally applied when the item entered the race.
        // Any remaining deposited quantity can then be moved into race-owned bank
        // storage without changing score. Items are fungible by item ID, so if a
        // player holds both an imported and a race-earned copy, returning one copy
        // is enough to reduce the imported exposure by one.
        for (Map.Entry<Integer, Integer> entry : current.entrySet())
        {
            int itemId = entry.getKey();
            int increase = entry.getValue() - previousBank.getOrDefault(itemId, 0);
            if (increase <= 0)
            {
                continue;
            }

            int remainingDeposit = increase;

            int importedHeld = importedOutstanding.getOrDefault(itemId, 0);
            int importedReturn = Math.min(remainingDeposit, importedHeld);
            if (importedReturn > 0)
            {
                changeQuantity(importedOutstanding, itemId, -importedReturn);
                refundImported(itemId, importedReturn, "BANK RETURN");
                remainingDeposit -= importedReturn;
            }

            if (remainingDeposit > 0)
            {
                int ownedInInventory = raceOwnedInventory.getOrDefault(itemId, 0);
                int ownedDeposit = Math.min(remainingDeposit, ownedInInventory);
                if (ownedDeposit > 0)
                {
                    changeQuantity(raceOwnedInventory, itemId, -ownedDeposit);
                    changeQuantity(raceOwnedBank, itemId, ownedDeposit);
                    logNeutralBankEvent(itemId, ownedDeposit, "RACE BANK DEPOSIT");
                }
            }
        }

        // Withdrawals: race-owned banked items are free to withdraw. Anything
        // beyond that is pre-race bank value and is debited from the score.
        for (Map.Entry<Integer, Integer> entry : previousBank.entrySet())
        {
            int itemId = entry.getKey();
            int decrease = entry.getValue() - current.getOrDefault(itemId, 0);
            if (decrease <= 0)
            {
                continue;
            }

            int ownedInBank = raceOwnedBank.getOrDefault(itemId, 0);
            int ownedWithdrawal = Math.min(decrease, ownedInBank);
            if (ownedWithdrawal > 0)
            {
                changeQuantity(raceOwnedBank, itemId, -ownedWithdrawal);
                changeQuantity(raceOwnedInventory, itemId, ownedWithdrawal);
                logNeutralBankEvent(itemId, ownedWithdrawal, "RACE BANK WITHDRAWAL");
            }

            int imported = decrease - ownedWithdrawal;
            if (imported > 0)
            {
                debitImported(itemId, imported, "BANK WITHDRAWAL");
            }
        }

        previousBank.clear();
        previousBank.putAll(current);
    }

    private void debitImported(int itemId, int quantity, String source)
    {
        if (quantity <= 0 || panel == null || !panel.isRaceRunning())
        {
            return;
        }

        int unitPrice = Math.max(0, itemManager.getItemPrice(itemId));
        long totalValue = (long) unitPrice * quantity;
        if (totalValue <= 0L)
        {
            return;
        }

        changeQuantity(importedOutstanding, itemId, quantity);

        String itemName = itemManager.getItemComposition(itemId).getName();
        ZeroGpRacePanel currentPanel = panel;
        SwingUtilities.invokeLater(() ->
            currentPanel.addImportedValue(itemName, quantity, totalValue, source));
        log.info("Debited imported value: {} x{} worth {} gp [{}]", itemName, quantity, totalValue, source);
    }

    private void refundImported(int itemId, int quantity, String source)
    {
        if (quantity <= 0 || panel == null || !panel.isRaceRunning())
        {
            return;
        }

        int unitPrice = Math.max(0, itemManager.getItemPrice(itemId));
        long totalValue = (long) unitPrice * quantity;
        if (totalValue <= 0L)
        {
            return;
        }

        String itemName = itemManager.getItemComposition(itemId).getName();
        ZeroGpRacePanel currentPanel = panel;
        SwingUtilities.invokeLater(() ->
            currentPanel.addImportedRefund(itemName, quantity, totalValue, source));
        log.info("Refunded returned imported value: {} x{} worth {} gp [{}]", itemName, quantity, totalValue, source);
    }

    private static void changeQuantity(Map<Integer, Integer> map, int itemId, int delta)
    {
        int next = map.getOrDefault(itemId, 0) + delta;
        if (next <= 0)
        {
            map.remove(itemId);
        }
        else
        {
            map.put(itemId, next);
        }
    }

    private void primeInventory()
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            inventoryPrimed = false;
            previousInventory.clear();
            return;
        }

        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        if (inventory == null)
        {
            inventoryPrimed = false;
            previousInventory.clear();
            return;
        }

        previousInventory.clear();
        previousInventory.putAll(snapshot(inventory));
        inventoryPrimed = true;
    }

    private boolean canTrackLoot()
    {
        return panel != null
            && panel.isRaceRunning()
            && client.getGameState() == GameState.LOGGED_IN;
    }

    private static Map<Integer, Integer> snapshot(ItemContainer inventory)
    {
        Map<Integer, Integer> result = new HashMap<>();
        for (Item item : inventory.getItems())
        {
            if (item.getId() > 0 && item.getQuantity() > 0)
            {
                result.merge(item.getId(), item.getQuantity(), Integer::sum);
            }
        }
        return result;
    }

    private boolean hasEligibleDrop(int itemId)
    {
        for (EligibleDrop drop : eligibleDrops)
        {
            if (drop.itemId == itemId && drop.remaining > 0)
            {
                return true;
            }
        }
        return false;
    }

    private boolean consumeTakeClick(int itemId)
    {
        Iterator<TakeClick> iterator = takeClicks.iterator();
        while (iterator.hasNext())
        {
            TakeClick click = iterator.next();
            if (click.itemId == itemId)
            {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private AcceptedLoot consumeEligibleDrop(int itemId, int wanted)
    {
        int accepted = 0;
        String source = "NPC";
        Iterator<EligibleDrop> iterator = eligibleDrops.iterator();
        while (iterator.hasNext() && accepted < wanted)
        {
            EligibleDrop drop = iterator.next();
            if (drop.itemId != itemId)
            {
                continue;
            }

            int used = Math.min(drop.remaining, wanted - accepted);
            drop.remaining -= used;
            accepted += used;
            source = drop.source;
            if (drop.remaining == 0)
            {
                iterator.remove();
            }
        }
        return new AcceptedLoot(accepted, source);
    }

    private DirectGainSource activeDirectGainSource()
    {
        purgeExpired();
        return directGainSources.peekLast();
    }

    private void consumeDirectGainSource(String sourceName)
    {
        Iterator<DirectGainSource> iterator = directGainSources.descendingIterator();
        while (iterator.hasNext())
        {
            DirectGainSource source = iterator.next();
            if (source.source.equals(sourceName))
            {
                iterator.remove();
                return;
            }
        }
    }

    /**
     * Returns a race earning source for reward-style interactions, or null if
     * the click is not a reward collection action.
     *
     * This intentionally keys off both the action and target name so normal
     * doors/bank chests do not become earning sources.
     */
    private void armSkillSource(String source)
    {
        if (source == null || source.trim().isEmpty())
        {
            return;
        }

        recentSkillSource = source;
        recentSkillSourceExpiresAt = System.currentTimeMillis() + SKILL_GAIN_LIFETIME_MS;
    }

    private String activeSkillSource()
    {
        if (recentSkillSourceExpiresAt < System.currentTimeMillis())
        {
            recentSkillSource = "";
            recentSkillSourceExpiresAt = 0L;
            return null;
        }

        return recentSkillSource.isEmpty() ? null : recentSkillSource;
    }

    private static String skillSource(Skill skill)
    {
        if (skill == null)
        {
            return null;
        }

        switch (skill)
        {
            case MINING:
                return "MINING";
            case WOODCUTTING:
                return "WOODCUTTING";
            case FISHING:
                return "FISHING";
            case HUNTER:
                return "HUNTER";
            case FARMING:
                return "FARMING";
            case RUNECRAFT:
                return "RUNECRAFTING";
            case AGILITY:
                return "AGILITY";
            case THIEVING:
                return "THIEVING";
            case HERBLORE:
                return "HERBLORE";
            case CRAFTING:
                return "CRAFTING";
            case FLETCHING:
                return "FLETCHING";
            case SMITHING:
                return "SMITHING";
            case COOKING:
                return "COOKING";
            case FIREMAKING:
                return "FIREMAKING";
            case CONSTRUCTION:
                return "CONSTRUCTION";
            default:
                return null;
        }
    }

    private static String classifySkillInteraction(String option, String target)
    {
        if (option == null)
        {
            return null;
        }

        String o = option.trim().toLowerCase();
        String t = target == null ? "" : target.trim().toLowerCase();

        if (o.equals("mine"))
        {
            return "MINING";
        }

        if (o.equals("chop down") || o.equals("chop"))
        {
            return "WOODCUTTING";
        }

        if (o.equals("net") || o.equals("bait") || o.equals("lure")
            || o.equals("cage") || o.equals("harpoon") || o.equals("fish")
            || o.equals("big net") || o.equals("small net"))
        {
            return "FISHING";
        }

        if (o.equals("harvest") || o.equals("pick-herbs") || o.equals("pick herbs")
            || (o.equals("pick") && (t.contains("patch") || t.contains("herb")
                || t.contains("allotment") || t.contains("bush") || t.contains("fruit"))))
        {
            return "FARMING";
        }

        if (o.contains("craft-rune") || o.contains("craft rune"))
        {
            return "RUNECRAFTING";
        }

        if (o.equals("check") && (t.contains("trap") || t.contains("bird")
            || t.contains("box") || t.contains("net")))
        {
            return "HUNTER";
        }

        // Production actions. StatChanged normally replaces this generic label
        // with the exact skill, but this catches the first inventory update.
        if (o.equals("make") || o.equals("make-all") || o.equals("make all")
            || o.equals("craft") || o.equals("fletch") || o.equals("cook")
            || o.equals("smelt") || o.equals("smith") || o.equals("mix")
            || o.equals("clean") || o.equals("grind") || o.equals("spin")
            || o.equals("tan") || o.equals("cut"))
        {
            return "SKILLING";
        }

        return null;
    }

    private static boolean isProcessingSkillSource(String source)
    {
        if (source == null)
        {
            return false;
        }

        switch (source.toUpperCase())
        {
            case "SKILLING":
            case "RUNECRAFTING":
            case "HERBLORE":
            case "CRAFTING":
            case "FLETCHING":
            case "SMITHING":
            case "COOKING":
            case "FIREMAKING":
            case "CONSTRUCTION":
                return true;
            default:
                return false;
        }
    }

    private void consumeProcessingInputs(Map<Integer, Integer> current, String source)
    {
        for (Map.Entry<Integer, Integer> entry : previousInventory.entrySet())
        {
            int itemId = entry.getKey();
            int lost = entry.getValue() - current.getOrDefault(itemId, 0);
            if (lost <= 0)
            {
                continue;
            }

            int remainingLost = lost;

            // Race-earned inputs were already counted positively, so subtract
            // their market value when they are consumed.
            int raceOwned = Math.min(
                remainingLost,
                raceOwnedInventory.getOrDefault(itemId, 0));

            if (raceOwned > 0)
            {
                changeQuantity(raceOwnedInventory, itemId, -raceOwned);
                removeRaceOwnedValue(itemId, raceOwned, source + " INPUT");
                remainingLost -= raceOwned;
            }

            // Imported inputs were already debited when they entered the race.
            // Remove only their outstanding quantity; do not debit them twice.
            if (remainingLost > 0)
            {
                int imported = Math.min(
                    remainingLost,
                    importedOutstanding.getOrDefault(itemId, 0));

                if (imported > 0)
                {
                    changeQuantity(importedOutstanding, itemId, -imported);
                }
            }
        }
    }

    private void removeRaceOwnedValue(int itemId, int quantity, String source)
    {
        if (quantity <= 0 || panel == null)
        {
            return;
        }

        int unitPrice = Math.max(0, itemManager.getItemPrice(itemId));
        long value = (long) unitPrice * quantity;
        if (value <= 0L)
        {
            return;
        }

        String itemName = itemManager.getItemComposition(itemId).getName();
        ZeroGpRacePanel currentPanel = panel;

        SwingUtilities.invokeLater(() ->
            currentPanel.addRaceOwnedConsumption(itemName, quantity, value, source));
    }

    private void inheritClueCasketIfPresent(Map<Integer, Integer> current)
    {
        int lostClueId = -1;

        for (Map.Entry<Integer, Integer> entry : previousInventory.entrySet())
        {
            int itemId = entry.getKey();
            int lost = entry.getValue() - current.getOrDefault(itemId, 0);
            if (lost <= 0 || raceOwnedInventory.getOrDefault(itemId, 0) <= 0)
            {
                continue;
            }

            String name = itemManager.getItemComposition(itemId).getName().toLowerCase();
            if (name.contains("clue scroll"))
            {
                lostClueId = itemId;
                break;
            }
        }

        if (lostClueId <= 0)
        {
            return;
        }

        for (Map.Entry<Integer, Integer> entry : current.entrySet())
        {
            int itemId = entry.getKey();
            int gained = entry.getValue() - previousInventory.getOrDefault(itemId, 0);
            if (gained <= 0)
            {
                continue;
            }

            String name = itemManager.getItemComposition(itemId).getName().toLowerCase();
            if (name.contains("casket"))
            {
                changeQuantity(raceOwnedInventory, lostClueId, -1);
                acceptPickup(itemId, gained, "CLUE REWARD");
                return;
            }
        }
    }

    private DirectGainSource classifyKeyedRewardInteraction(String option, String target)
    {
        if (option == null || target == null)
        {
            return null;
        }

        String o = option.trim().toLowerCase();
        String t = target.trim().toLowerCase();

        if (!(o.equals("open") || o.equals("unlock") || o.equals("use")))
        {
            return null;
        }

        if (t.contains("brimstone chest"))
        {
            int keyId = findRaceOwnedItemByName("brimstone key");
            return keyId > 0
                ? new DirectGainSource("BRIMSTONE CHEST", keyId,
                    System.currentTimeMillis() + REWARD_GAIN_LIFETIME_MS)
                : null;
        }

        if (t.contains("larran"))
        {
            int keyId = findRaceOwnedItemByName("larran's key");
            return keyId > 0
                ? new DirectGainSource("LARRAN'S CHEST", keyId,
                    System.currentTimeMillis() + REWARD_GAIN_LIFETIME_MS)
                : null;
        }

        if (t.contains("crystal chest"))
        {
            int keyId = findRaceOwnedItemByName("crystal key");
            return keyId > 0
                ? new DirectGainSource("CRYSTAL CHEST", keyId,
                    System.currentTimeMillis() + REWARD_GAIN_LIFETIME_MS)
                : null;
        }

        return null;
    }

    private int findRaceOwnedItemByName(String wantedName)
    {
        String wanted = wantedName == null ? "" : wantedName.trim().toLowerCase();
        if (wanted.isEmpty())
        {
            return -1;
        }

        for (Map.Entry<Integer, Integer> entry : raceOwnedInventory.entrySet())
        {
            if (entry.getValue() <= 0)
            {
                continue;
            }

            String name = itemManager.getItemComposition(entry.getKey()).getName();
            if (name != null && name.trim().toLowerCase().equals(wanted))
            {
                return entry.getKey();
            }
        }

        return -1;
    }

    private static boolean isExcludedStoredRewardInteraction(String option, String target)
    {
        String t = target == null ? "" : target.trim().toLowerCase();

        return t.contains("reward pool")
            || t.contains("rewards guardian")
            || t.contains("reward guardian")
            || t.contains("hallowed coffin")
            || t.contains("hallowed sepulchre")
            || t.contains("pest control")
            || t.contains("soul wars")
            || t.contains("last man standing")
            || t.contains("lms");
    }

    private static boolean isExcludedStoredRewardItem(String itemName)
    {
        if (itemName == null)
        {
            return false;
        }

        String name = itemName.trim().toLowerCase();

        // Wintertodt crates are intentionally excluded by race rule.
        return name.equals("supply crate");
    }

    private static String classifyRewardInteraction(String option, String target, MenuAction action)
    {
        if (option == null)
        {
            return null;
        }

        String normalisedOption = option.trim().toLowerCase();
        String normalisedTarget = target == null ? "" : target.trim().toLowerCase();

        if (isExcludedStoredRewardInteraction(option, target))
        {
            return null;
        }

        // Never arm provenance from banking/storage furniture.
        if (normalisedTarget.contains("bank")
            || normalisedTarget.contains("deposit box")
            || normalisedTarget.contains("group storage")
            || normalisedTarget.contains("seed vault"))
        {
            return null;
        }

        boolean rewardAction =
            normalisedOption.equals("open")
            || normalisedOption.equals("search")
            || normalisedOption.equals("loot")
            || normalisedOption.equals("claim")
            || normalisedOption.equals("collect")
            || normalisedOption.equals("collect-reward")
            || normalisedOption.equals("collect reward")
            || normalisedOption.equals("take-reward")
            || normalisedOption.equals("take reward")
            || normalisedOption.equals("receive")
            || normalisedOption.equals("rewards");

        if (!rewardAction)
        {
            return null;
        }

        // Specific raid / activity labels first.
        if (normalisedTarget.contains("ancient chest"))
        {
            return "CHAMBERS OF XERIC";
        }

        if (normalisedTarget.contains("monumental chest"))
        {
            return "THEATRE OF BLOOD";
        }

        if (normalisedTarget.contains("grand chest"))
        {
            return "TOMBS OF AMASCUT";
        }

        if (normalisedTarget.contains("reward chest"))
        {
            return "GAUNTLET";
        }

        if (normalisedTarget.contains("barrows") || normalisedTarget.equals("chest"))
        {
            return "REWARD CHEST";
        }

        // Generic reward wording used by many minigames and bosses.
        if (normalisedTarget.contains("reward")
            || normalisedTarget.contains("treasure")
            || normalisedTarget.contains("coffer")
            || normalisedTarget.contains("cache")
            || normalisedTarget.contains("crate")
            || normalisedTarget.contains("chest"))
        {
            return "REWARD CHEST";
        }

        // Some reward objects have sparse target text. Restrict this fallback to
        // actual game-object interactions to avoid tagging ordinary menu actions.
        if (isGameObjectAction(action)
            && (normalisedOption.equals("claim")
                || normalisedOption.equals("collect-reward")
                || normalisedOption.equals("collect reward")
                || normalisedOption.equals("take-reward")
                || normalisedOption.equals("take reward")))
        {
            return "ACTIVITY REWARD";
        }

        return null;
    }

    private static String stripTags(String value)
    {
        if (value == null || value.isEmpty())
        {
            return "";
        }

        return value.replaceAll("<[^>]*>", "").trim();
    }

    private static boolean isThievingObjectAction(String option, MenuAction action)
    {
        if (option == null || !isGameObjectAction(action))
        {
            return false;
        }

        String normalised = option.trim().toLowerCase();
        return normalised.equals("steal-from")
            || normalised.equals("steal from")
            || normalised.equals("pick-lock")
            || normalised.equals("pick lock")
            || normalised.equals("search")
            || normalised.equals("loot")
            || normalised.equals("open");
    }

    private static boolean isGameObjectAction(MenuAction action)
    {
        return action == MenuAction.GAME_OBJECT_FIRST_OPTION
            || action == MenuAction.GAME_OBJECT_SECOND_OPTION
            || action == MenuAction.GAME_OBJECT_THIRD_OPTION
            || action == MenuAction.GAME_OBJECT_FOURTH_OPTION
            || action == MenuAction.GAME_OBJECT_FIFTH_OPTION;
    }

    private static boolean isRaceOwnedContainerAction(String option)
    {
        if (option == null)
        {
            return false;
        }

        String normalised = option.trim().toLowerCase();
        return normalised.equals("open")
            || normalised.equals("open-all")
            || normalised.equals("open all")
            || normalised.equals("loot")
            || normalised.equals("search")
            || normalised.equals("claim")
            || normalised.equals("redeem");
    }

    private void purgeExpired()
    {
        long now = System.currentTimeMillis();
        eligibleDrops.removeIf(drop -> drop.expiresAt < now || drop.remaining <= 0);
        takeClicks.removeIf(click -> click.expiresAt < now);
        directGainSources.removeIf(source -> source.expiresAt < now);
    }

    private String currentPlayerName()
    {
        return client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null
            ? "" : client.getLocalPlayer().getName();
    }

    private void resetTrackingState()
    {
        previousInventory.clear();
        previousBank.clear();
        raceOwnedInventory.clear();
        raceOwnedBank.clear();
        importedOutstanding.clear();
        eligibleDrops.clear();
        takeClicks.clear();
        directGainSources.clear();
        inventoryPrimed = false;
        bankPrimed = false;
        recentSkillSource = "";
        recentSkillSourceExpiresAt = 0L;
    }

    private static String cleanHttpsBase(String value)
    {
        if (value == null)
        {
            return null;
        }
        String result = value.trim().replaceAll("/+$", "");
        return result.startsWith("https://") ? result : null;
    }

    private static String normaliseRoom(String value)
    {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private static final class EligibleDrop
    {
        private final int itemId;
        private int remaining;
        private final String source;
        private final long expiresAt;

        private EligibleDrop(int itemId, int remaining, String source, long expiresAt)
        {
            this.itemId = itemId;
            this.remaining = remaining;
            this.source = source;
            this.expiresAt = expiresAt;
        }
    }

    private static final class TakeClick
    {
        private final int itemId;
        private final long expiresAt;

        private TakeClick(int itemId, long expiresAt)
        {
            this.itemId = itemId;
            this.expiresAt = expiresAt;
        }
    }

    private static final class DirectGainSource
    {
        private final String source;
        private final int sourceItemId;
        private final long expiresAt;

        private DirectGainSource(String source, int sourceItemId, long expiresAt)
        {
            this.source = source;
            this.sourceItemId = sourceItemId;
            this.expiresAt = expiresAt;
        }
    }

    private static final class AcceptedLoot
    {
        private final int quantity;
        private final String source;

        private AcceptedLoot(int quantity, String source)
        {
            this.quantity = quantity;
            this.source = source;
        }
    }

    private static final class PickupPayload
    {
        private final String room;
        private final String player;
        private final int itemId;
        private final int quantity;
        private final String source;

        private PickupPayload(String room, String player, int itemId, int quantity, String source)
        {
            this.room = room;
            this.player = player;
            this.itemId = itemId;
            this.quantity = quantity;
            this.source = source;
        }
    }
}
