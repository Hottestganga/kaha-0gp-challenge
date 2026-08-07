package com.kahakoolkids.zerogp;

import com.google.gson.Gson;
import com.google.inject.Provides;
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
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
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
    name = "Kaha 0GP Race",
    description = "Tracks eligible NPC and PvP loot picked up during timed zero-GP races",
    tags = {"race", "loot", "challenge", "pvp", "clan"}
)
public class ZeroGpRacePlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(ZeroGpRacePlugin.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long DROP_LIFETIME_MS = 300_000L;
    private static final long TAKE_CLICK_LIFETIME_MS = 12_000L;

    @Inject private Client client;
    @Inject private OkHttpClient httpClient;
    @Inject private Gson gson;
    @Inject private ZeroGpRaceConfig config;
    @Inject private ClientToolbar clientToolbar;

    private final Map<Integer, Integer> previousInventory = new HashMap<>();
    private final Deque<EligibleDrop> eligibleDrops = new ArrayDeque<>();
    private final Deque<TakeClick> takeClicks = new ArrayDeque<>();

    private ZeroGpRacePanel panel;
    private NavigationButton navigationButton;
    private boolean inventoryPrimed;
    private long acceptedItemCount;

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
            .tooltip("Kaha 0GP Race")
            .icon(icon)
            .priority(8)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navigationButton);
        refreshPanel("Waiting");
    }

    @Override
    protected void shutDown()
    {
        if (navigationButton != null)
        {
            clientToolbar.removeNavigation(navigationButton);
            navigationButton = null;
        }
        panel = null;
        resetTrackingState();
    }

    void openDashboard()
    {
        String base = cleanHttpsBase(config.dashboardUrl());
        if (base == null)
        {
            refreshPanel("Set dashboard URL");
            return;
        }

        String room = normaliseRoom(config.roomCode());
        String url = room.isEmpty() ? base : base + (base.contains("?") ? "&" : "?")
            + "room=" + URLEncoder.encode(room, StandardCharsets.UTF_8);
        LinkBrowser.browse(url);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() != GameState.LOGGED_IN)
        {
            previousInventory.clear();
            inventoryPrimed = false;
            refreshPanel("Waiting for login");
        }
        else
        {
            refreshPanel(canTrack() ? "Tracking" : "Configure plugin");
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
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (!canTrack() || !"Take".equalsIgnoreCase(event.getMenuOption()))
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
        if (!canTrack())
        {
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
        for (Map.Entry<Integer, Integer> entry : current.entrySet())
        {
            int itemId = entry.getKey();
            int gained = entry.getValue() - previousInventory.getOrDefault(itemId, 0);
            if (gained <= 0 || !consumeTakeClick(itemId))
            {
                continue;
            }

            AcceptedLoot accepted = consumeEligibleDrop(itemId, gained);
            if (accepted.quantity > 0)
            {
                sendAcceptedPickup(itemId, accepted.quantity, accepted.source);
            }
        }

        previousInventory.clear();
        previousInventory.putAll(current);
    }

    private void rememberDrops(Iterable<ItemStack> items, String source)
    {
        if (!canTrack())
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

    private void sendAcceptedPickup(int itemId, int quantity, String source)
    {
        String base = cleanHttpsBase(config.dashboardUrl());
        if (base == null)
        {
            return;
        }

        PickupPayload payload = new PickupPayload(
            normaliseRoom(config.roomCode()), currentPlayerName(), itemId, quantity, source);
        Request request = new Request.Builder()
            .url(base + "/api/pickup")
            .post(RequestBody.create(JSON, gson.toJson(payload)))
            .build();

        refreshPanel("Sending loot");
        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException exception)
            {
                log.warn("Unable to submit 0GP race pickup", exception);
                refreshPanel("Connection failed");
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try (Response ignored = response)
                {
                    if (response.isSuccessful())
                    {
                        acceptedItemCount += quantity;
                        refreshPanel("Tracking");
                    }
                    else
                    {
                        refreshPanel("Server rejected pickup");
                    }
                }
            }
        });
    }

    private boolean canTrack()
    {
        return config.trackingEnabled()
            && client.getGameState() == GameState.LOGGED_IN
            && cleanHttpsBase(config.dashboardUrl()) != null
            && !normaliseRoom(config.roomCode()).isEmpty();
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

    private void purgeExpired()
    {
        long now = System.currentTimeMillis();
        eligibleDrops.removeIf(drop -> drop.expiresAt < now || drop.remaining <= 0);
        takeClicks.removeIf(click -> click.expiresAt < now);
    }

    private void refreshPanel(String status)
    {
        ZeroGpRacePanel currentPanel = panel;
        if (currentPanel == null)
        {
            return;
        }

        String room = normaliseRoom(config.roomCode());
        String player = currentPlayerName();
        SwingUtilities.invokeLater(
            () -> currentPanel.updateState(status, room, player, acceptedItemCount));
    }

    private String currentPlayerName()
    {
        return client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null
            ? "" : client.getLocalPlayer().getName();
    }

    private void resetTrackingState()
    {
        previousInventory.clear();
        eligibleDrops.clear();
        takeClicks.clear();
        inventoryPrimed = false;
        acceptedItemCount = 0;
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
