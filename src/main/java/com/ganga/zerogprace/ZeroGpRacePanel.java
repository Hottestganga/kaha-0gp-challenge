package com.ganga.zerogprace;

import com.ganga.zerogprace.engine.TransactionEngine;
import com.ganga.zerogprace.model.OwnershipType;
import com.ganga.zerogprace.model.RaceSource;
import com.ganga.zerogprace.model.RaceTransaction;
import com.ganga.zerogprace.model.TransactionStatus;
import com.ganga.zerogprace.stats.RaceStatistics;
import com.ganga.zerogprace.network.RacePlayerSnapshot;
import com.ganga.zerogprace.network.RaceRoomSnapshot;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

final class ZeroGpRacePanel extends PluginPanel
{
    private static final Color GOLD = new Color(212, 175, 55);
    private static final Color GREEN = new Color(80, 200, 120);
    private static final Color LIGHT_GOLD = new Color(245, 210, 100);
    private static final Color RED = new Color(220, 80, 80);
    private static final Color CARD_BG = new Color(34, 34, 34);
    private static final Color PANEL_BG = new Color(24, 24, 24);
    private static final Color MUTED = new Color(165, 165, 165);
    private static final Color CYAN = new Color(90, 190, 210);
    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_EVENT_LINES = 12;
    private static final long NEGATIVE_BALANCE_GRACE_MS = 30_000L;

    private final ZeroGpRacePlugin plugin;

    private final JLabel statusValue = valueLabel("Waiting");
    private final JLabel raceNameValue = valueLabel("No active race");
    private final JLabel roomValue = valueLabel("Not set");
    private final JLabel playerValue = valueLabel("Not logged in");
    private final JLabel timeValue = valueLabel("--:--:--");
    private final JLabel gpValue = valueLabel("0 GP");
    private final JLabel bankValue = valueLabel("0 GP");
    private final JLabel acceptedValue = valueLabel("0");
    private final JLabel multiplayerValue = valueLabel("Local only");

    // Dedicated pause/resume guidance so the wealth difference is never
    // hidden by the narrow one-line Status field.
    private final JPanel pauseGuidePanel = new JPanel(new GridLayout(4, 2, 6, 6));
    private final JLabel pauseRequiredValue = valueLabel("0 GP");
    private final JLabel pauseCurrentValue = valueLabel("0 GP");
    private final JLabel pauseDifferenceValue = valueLabel("--");
    private final JLabel pauseReadyValue = valueLabel("Waiting");

    private final JTextArea playerListArea = new JTextArea(5, 20);
    private final JTextArea recentLootArea = new JTextArea(10, 20);

    private final JButton createRaceButton = new JButton("Create Race");
    private final JButton joinRaceButton = new JButton("Join Race");
    private final JButton dashboardButton = new JButton("Open Dashboard");
    private final JButton saveProgressButton = new JButton("Save Race Progress");
    private final JButton pauseResumeButton = new JButton("Pause Race");
    private final JButton leaveRaceButton = new JButton("Leave Race");
    private final JButton statsButton = new JButton("Race Stats");

    private final Timer countdownTimer;
    private final Timer budgetGraceTimer;
    private final TransactionEngine transactionEngine = new TransactionEngine();

    private String activeRoomCode = "";
    private String activeRaceName = "";
    private long remainingMilliseconds;
    private long lastResumeAt;
    private long gpEarned;
    private long bankValueGp;
    private long raceStartingAllowance;
    private long acceptedItems;
    private long negativeBalanceDeadlineMs;
    private boolean raceRunning;
    private boolean manualPaused;
    private boolean raceProgressSaved;
    private long savedRaceProgressGp = -1L;
    private long manualPauseTargetGp;
    private long manualPauseCurrentGp;
    private long manualPauseEquipmentGp;
    private boolean manualPauseValueReady;
    private boolean loggedIn;
    private String multiplayerState = "IDLE";
    private String currentPlayerName = "";

    ZeroGpRacePanel(ZeroGpRacePlugin plugin)
    {
        this.plugin = plugin;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(ColorScheme.DARK_GRAY_COLOR);
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 12, 10));

        JLabel title = new JLabel("0GP Race", SwingConstants.CENTER);
        title.setAlignmentX(CENTER_ALIGNMENT);
        title.setForeground(GOLD);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        content.add(title);

        JLabel subtitle = new JLabel("Competitive race tracker", SwingConstants.CENTER);
        subtitle.setAlignmentX(CENTER_ALIGNMENT);
        subtitle.setForeground(Color.GRAY);
        subtitle.setFont(subtitle.getFont().deriveFont(10f));
        content.add(subtitle);

        content.add(Box.createRigidArea(new Dimension(0, 10)));
        content.add(buildStatusPanel());
        content.add(Box.createRigidArea(new Dimension(0, 8)));
        content.add(buildWealthPanel());
        content.add(Box.createRigidArea(new Dimension(0, 8)));
        content.add(infoGrid());
        content.add(Box.createRigidArea(new Dimension(0, 8)));
        content.add(buildPauseGuidePanel());
        content.add(Box.createRigidArea(new Dimension(0, 10)));
        content.add(playerListPanel());
        content.add(Box.createRigidArea(new Dimension(0, 10)));
        content.add(recentLootPanel());
        content.add(Box.createRigidArea(new Dimension(0, 10)));

        prepareButton(createRaceButton, true);
        prepareButton(joinRaceButton, false);
        prepareButton(dashboardButton, false);
        prepareButton(saveProgressButton, false);
        prepareButton(pauseResumeButton, false);
        prepareButton(leaveRaceButton, false);
        prepareButton(statsButton, false);

        createRaceButton.addActionListener(event -> showCreateRaceDialog());
        joinRaceButton.addActionListener(event -> showJoinRaceDialog());
        dashboardButton.addActionListener(event -> plugin.openDashboard(activeRoomCode));
        saveProgressButton.addActionListener(event -> plugin.requestSaveRaceProgress());
        pauseResumeButton.addActionListener(event -> toggleManualPause());
        leaveRaceButton.addActionListener(event -> leaveRace());
        statsButton.addActionListener(event -> showRaceStatistics());

        content.add(buildActionPanel());

        JLabel note = new JLabel(
            "<html><center><font color='#9b9b9b'>"
                + "Race Score = earned value / realised P&amp;L<br>"
                + "Bank Value = available race purchasing power"
                + "</font></center></html>");
        note.setAlignmentX(CENTER_ALIGNMENT);
        note.setFont(note.getFont().deriveFont(9f));
        note.setBorder(BorderFactory.createEmptyBorder(9, 4, 0, 4));
        content.add(note);

        add(content, BorderLayout.NORTH);

        countdownTimer = new Timer(1000, event -> updateCountdown());
        budgetGraceTimer = new Timer(250, event -> updateNegativeBalanceGrace());
        updateButtonStates();
    }

    private JPanel buildStatusPanel()
    {
        JPanel statusPanel = new JPanel(new BorderLayout(6, 0));
        statusPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        statusPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(68, 68, 68)),
            BorderFactory.createEmptyBorder(7, 8, 7, 8)));
        statusPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));

        JLabel label = new JLabel("Status");
        label.setForeground(Color.LIGHT_GRAY);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));

        statusValue.setHorizontalAlignment(SwingConstants.RIGHT);
        statusValue.setFont(statusValue.getFont().deriveFont(Font.BOLD, 11f));

        statusPanel.add(label, BorderLayout.WEST);
        statusPanel.add(statusValue, BorderLayout.CENTER);
        return statusPanel;
    }

    private JPanel buildWealthPanel()
    {
        JPanel panel = sectionPanel("Race progress");
        JPanel grid = new JPanel(new GridLayout(3, 2, 6, 6));
        grid.setOpaque(false);

        timeValue.setForeground(Color.WHITE);
        timeValue.setFont(timeValue.getFont().deriveFont(Font.BOLD));

        gpValue.setForeground(GOLD);
        gpValue.setFont(gpValue.getFont().deriveFont(Font.BOLD));

        bankValue.setForeground(LIGHT_GOLD);
        bankValue.setFont(bankValue.getFont().deriveFont(Font.BOLD));

        addRow(grid, "Time left", timeValue);
        addRow(grid, "Race score", gpValue);
        addRow(grid, "Bank value", bankValue);

        panel.add(grid, BorderLayout.CENTER);
        return panel;
    }

    private JPanel sectionPanel(String title)
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(68, 68, 68)),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)));

        if (title != null && !title.isEmpty())
        {
            JLabel heading = new JLabel(title);
            heading.setForeground(GOLD);
            heading.setFont(heading.getFont().deriveFont(Font.BOLD, 11f));
            heading.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
            panel.add(heading, BorderLayout.NORTH);
        }

        return panel;
    }

    private JPanel buildActionPanel()
    {
        JPanel outer = sectionPanel("Controls");

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));

        JPanel setupRow = new JPanel(new GridLayout(1, 2, 5, 0));
        setupRow.setOpaque(false);
        setupRow.add(createRaceButton);
        setupRow.add(joinRaceButton);
        setupRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));

        JPanel utilityRow = new JPanel(new GridLayout(1, 2, 5, 0));
        utilityRow.setOpaque(false);
        utilityRow.add(dashboardButton);
        utilityRow.add(statsButton);
        utilityRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));

        buttons.add(setupRow);
        buttons.add(Box.createRigidArea(new Dimension(0, 5)));
        buttons.add(utilityRow);
        buttons.add(Box.createRigidArea(new Dimension(0, 8)));
        buttons.add(saveProgressButton);
        buttons.add(Box.createRigidArea(new Dimension(0, 5)));
        buttons.add(pauseResumeButton);
        buttons.add(Box.createRigidArea(new Dimension(0, 5)));
        buttons.add(leaveRaceButton);

        outer.add(buttons, BorderLayout.CENTER);
        return outer;
    }

    private JPanel infoGrid()
    {
        JPanel outer = sectionPanel("Race details");
        JPanel grid = new JPanel(new GridLayout(5, 2, 6, 5));
        grid.setOpaque(false);

        addRow(grid, "Race", raceNameValue);
        addRow(grid, "Room", roomValue);
        addRow(grid, "Player", playerValue);
        addRow(grid, "Accepted loot", acceptedValue);
        addRow(grid, "Multiplayer", multiplayerValue);

        outer.add(grid, BorderLayout.CENTER);
        return outer;
    }

    private JPanel buildPauseGuidePanel()
    {
        pauseGuidePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        pauseGuidePanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(68, 68, 68)),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)));
        pauseGuidePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 135));

        addRow(pauseGuidePanel, "Required wealth", pauseRequiredValue);
        addRow(pauseGuidePanel, "Current wealth", pauseCurrentValue);
        addRow(pauseGuidePanel, "Difference", pauseDifferenceValue);
        addRow(pauseGuidePanel, "Resume", pauseReadyValue);

        pauseDifferenceValue.setFont(
            pauseDifferenceValue.getFont().deriveFont(Font.BOLD));
        pauseReadyValue.setFont(
            pauseReadyValue.getFont().deriveFont(Font.BOLD));

        pauseGuidePanel.setVisible(false);
        return pauseGuidePanel;
    }

    private void refreshPauseGuide()
    {
        pauseGuidePanel.setVisible(manualPaused);

        if (!manualPaused)
        {
            pauseRequiredValue.setText("0 GP");
            pauseCurrentValue.setText("0 GP");
            pauseDifferenceValue.setText("--");
            pauseReadyValue.setText("Waiting");
            return;
        }

        pauseRequiredValue.setText(String.format(
            Locale.US,
            "%,d GP",
            manualPauseTargetGp));

        if (!manualPauseValueReady)
        {
            pauseCurrentValue.setText("Reading...");
            pauseDifferenceValue.setText("Calculating...");
            pauseDifferenceValue.setForeground(LIGHT_GOLD);
            pauseReadyValue.setText("WAIT");
            pauseReadyValue.setForeground(LIGHT_GOLD);
            return;
        }

        pauseCurrentValue.setText(String.format(
            Locale.US,
            "%,d GP",
            manualPauseCurrentGp));

        if (manualPauseEquipmentGp > 0L)
        {
            pauseDifferenceValue.setText(String.format(
                Locale.US,
                "Remove %,d GP gear",
                manualPauseEquipmentGp));
            pauseDifferenceValue.setForeground(RED);
            pauseReadyValue.setText("BLOCKED");
            pauseReadyValue.setForeground(RED);
            return;
        }

        long difference = manualPauseTargetGp - manualPauseCurrentGp;

        if (difference > 0L)
        {
            pauseDifferenceValue.setText(String.format(
                Locale.US,
                "Need %,d GP more",
                difference));
            pauseDifferenceValue.setForeground(RED);
            pauseReadyValue.setText("NOT READY");
            pauseReadyValue.setForeground(RED);

            pauseResumeButton.setText(String.format(
                Locale.US,
                "Resume (Need %,d GP)",
                difference));
            return;
        }

        if (difference < 0L)
        {
            long excess = Math.abs(difference);
            pauseDifferenceValue.setText(String.format(
                Locale.US,
                "%,d GP too much",
                excess));
            pauseDifferenceValue.setForeground(RED);
            pauseReadyValue.setText("NOT READY");
            pauseReadyValue.setForeground(RED);

            pauseResumeButton.setText(String.format(
                Locale.US,
                "Resume (Remove %,d GP)",
                excess));
            return;
        }

        pauseDifferenceValue.setText("Exact wealth matched");
        pauseDifferenceValue.setForeground(GREEN);
        pauseReadyValue.setText("READY");
        pauseReadyValue.setForeground(GREEN);
        pauseResumeButton.setText("Resume Race");
    }

    private JPanel playerListPanel()
    {
        JPanel panel = sectionPanel("Live leaderboard");
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));

        playerListArea.setEditable(false);
        playerListArea.setFocusable(false);
        playerListArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        playerListArea.setForeground(Color.WHITE);
        playerListArea.setFont(playerListArea.getFont().deriveFont(11f));
        playerListArea.setText("Start or join an online room.");

        JScrollPane scrollPane = new JScrollPane(playerListArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);

        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel recentLootPanel()
    {
        JPanel panel = sectionPanel("Recent activity");
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 225));

        recentLootArea.setEditable(false);
        recentLootArea.setFocusable(false);
        recentLootArea.setLineWrap(true);
        recentLootArea.setWrapStyleWord(true);
        recentLootArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        recentLootArea.setForeground(Color.WHITE);
        recentLootArea.setFont(recentLootArea.getFont().deriveFont(10.5f));
        recentLootArea.setText("No race activity yet.");

        JScrollPane scrollPane = new JScrollPane(recentLootArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);

        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private static void addRow(JPanel panel, String label, JLabel value)
    {
        JLabel key = new JLabel(label + ":");
        key.setForeground(Color.LIGHT_GRAY);
        key.setFont(key.getFont().deriveFont(10.5f));

        value.setHorizontalAlignment(SwingConstants.RIGHT);

        panel.add(key);
        panel.add(value);
    }

    private static JLabel valueLabel(String text)
    {
        JLabel label = new JLabel(text);
        label.setForeground(Color.WHITE);
        return label;
    }

    private void prepareButton(JButton button, boolean gold)
    {
        button.setAlignmentX(CENTER_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        button.setMinimumSize(new Dimension(0, 32));
        button.setFocusPainted(false);
        button.setFont(button.getFont().deriveFont(Font.BOLD, 10.5f));

        if (gold)
        {
            button.setBackground(GOLD);
            button.setForeground(Color.BLACK);
        }
        else
        {
            button.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
            button.setForeground(Color.WHITE);
        }
    }

    private void showCreateRaceDialog()
    {
        JTextField nameField = new JTextField("0GP Race");
        JTextField durationField = new JTextField("4");
        JTextField allowanceField = new JTextField("0");
        JComboBox<String> unitBox = new JComboBox<>(new String[]{"Minutes", "Hours", "Days"});
        unitBox.setSelectedItem("Hours");

        JPanel form = new JPanel(new GridLayout(0, 1, 5, 5));
        form.add(new JLabel("Race name:"));
        form.add(nameField);
        form.add(new JLabel("Duration:"));
        form.add(durationField);
        form.add(unitBox);
        form.add(new JLabel("Starting GP allowance:"));
        form.add(allowanceField);

        int result = JOptionPane.showConfirmDialog(
            this,
            form,
            "Create Race",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION)
        {
            return;
        }

        String raceName = nameField.getText().trim();
        if (raceName.isEmpty())
        {
            showError("Enter a race name.");
            return;
        }

        int amount;
        try
        {
            amount = Integer.parseInt(durationField.getText().trim());
        }
        catch (NumberFormatException ex)
        {
            showError("Enter a whole number for the duration.");
            return;
        }

        if (amount <= 0)
        {
            showError("Duration must be greater than zero.");
            return;
        }

        long startingAllowance;
        try
        {
            startingAllowance = parseGpAllowance(allowanceField.getText());
        }
        catch (NumberFormatException ex)
        {
            showError("Enter a valid starting GP allowance, for example 0, 50000 or 50k.");
            return;
        }

        long durationSeconds = toSeconds(amount, String.valueOf(unitBox.getSelectedItem()));

        plugin.requestCleanRaceStartCheck(() ->
            startRace(
                raceName,
                generateRoomCode(),
                durationSeconds,
                startingAllowance,
                true));
    }

    private void showJoinRaceDialog()
    {
        JTextField roomField = new JTextField();
        JPanel form = new JPanel(new GridLayout(0, 1, 5, 5));
        form.add(new JLabel("Room code:"));
        form.add(roomField);

        int result = JOptionPane.showConfirmDialog(
            this,
            form,
            "Join Online Race",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION)
        {
            return;
        }

        String room = normaliseRoom(roomField.getText());
        if (room.isEmpty())
        {
            showError("Enter a room code.");
            return;
        }

        plugin.requestCleanRaceStartCheck(() ->
        {
            setMultiplayerStatus("Joining...");
            joinRaceButton.setEnabled(false);
            createRaceButton.setEnabled(false);
            plugin.joinMultiplayerRoom(room);
        });
    }

    private void startRace(String raceName, String roomCode, long durationSeconds, long startingAllowance, boolean createOnlineRoom)
    {
        activeRaceName = raceName;
        activeRoomCode = roomCode;
        remainingMilliseconds = durationSeconds * 1000L;
        lastResumeAt = 0L;
        transactionEngine.reset(0L);
        raceStartingAllowance = Math.max(0L, startingAllowance);

        // V6: allowance is available bank purchasing power, not Race Score.
        plugin.startWalletRace(raceStartingAllowance);
        gpEarned = plugin.currentWalletScore();
        bankValueGp = plugin.currentWalletBankValue();
        acceptedItems = 0L;
        raceRunning = true;
        manualPaused = false;
        raceProgressSaved = false;
        savedRaceProgressGp = -1L;
        manualPauseTargetGp = 0L;
        manualPauseCurrentGp = 0L;
        manualPauseEquipmentGp = 0L;
        manualPauseValueReady = false;
        clearNegativeBalanceGrace(false);

        raceNameValue.setText(activeRaceName);
        roomValue.setText(activeRoomCode);
        timeValue.setText(formatSeconds(durationSeconds));
        gpValue.setText(String.format(Locale.US, "%,d GP", gpEarned));
        gpValue.setForeground(Color.WHITE);
        bankValue.setText(String.format(Locale.US, "%,d GP", bankValueGp));
        bankValue.setForeground(bankValueGp < 0L ? RED : Color.WHITE);
        acceptedValue.setText("0");
        recentLootArea.setText("No race activity yet.");
        addLedgerEvent(String.format(Locale.US, "RACE STARTED | %s | %s", activeRoomCode, formatSeconds(durationSeconds)));
        if (raceStartingAllowance > 0L)
        {
            transactionEngine.record(new RaceTransaction(
                System.currentTimeMillis(),
                RaceSource.ALLOWANCE,
                "Starting allowance",
                1,
                raceStartingAllowance,
                0L,
                OwnershipType.RACE_OWNED,
                TransactionStatus.INFO,
                "Starting bank purchasing power"));

            addLedgerEvent(String.format(
                Locale.US,
                "ALLOWANCE | Bank value %,d GP",
                raceStartingAllowance));
        }
        plugin.onLocalRaceStarted();
        multiplayerState = "RUNNING";
        if (createOnlineRoom)
        {
            plugin.createMultiplayerRoom(activeRaceName, activeRoomCode,
                durationSeconds * 1000L, startingAllowance);
        }

        if (loggedIn)
        {
            resumeTimer();
        }
        else
        {
            setStatus("Paused - log in", LIGHT_GOLD);
        }

        updateButtonStates();

        JOptionPane.showMessageDialog(
            this,
            "Race: " + activeRaceName + "\nRoom: " + activeRoomCode
                + "\nDuration: " + formatSeconds(durationSeconds)
                + "\nStarting allowance: " + String.format(Locale.US, "%,d GP", raceStartingAllowance)
                + "\n\nThe timer pauses whenever you are logged out."
                + "\nThis race is locked to the account that started/joined it.",
            "Race Started",
            JOptionPane.INFORMATION_MESSAGE);
    }

    void onLoggedIn(String playerName)
    {
        loggedIn = true;
        currentPlayerName = isBlank(playerName) ? "" : playerName;
        playerValue.setText(isBlank(playerName) ? "Logged in" : playerName);
        if (raceRunning)
        {
            if (manualPaused)
            {
                multiplayerState = "PAUSED";
                setStatus(manualPauseStatusText(), LIGHT_GOLD);
                plugin.refreshManualPauseValue();
            }
            else
            {
                addLedgerEvent("LOGIN | Timer resumed");
                multiplayerState = negativeBalanceDeadlineMs > 0L ? "OVER_BUDGET" : "RUNNING";
                resumeTimer();
                if (negativeBalanceDeadlineMs > 0L)
                {
                    updateNegativeBalanceGrace();
                }
            }
        }
        else
        {
            setStatus("Ready", GREEN);
        }
    }

    void onRaceAccountMismatch(
        String expectedPlayer,
        String actualPlayer)
    {
        loggedIn = false;
        lastResumeAt = 0L;
        countdownTimer.stop();

        currentPlayerName =
            isBlank(actualPlayer)
                ? ""
                : actualPlayer;

        playerValue.setText(
            isBlank(actualPlayer)
                ? "Wrong account"
                : actualPlayer + " (LOCKED)");

        multiplayerState = "PAUSED";

        setStatus(
            "ACCOUNT LOCKED",
            RED);

        addLedgerEvent(
            "ACCOUNT LOCK | Race belongs to "
                + expectedPlayer
                + " | Current login "
                + actualPlayer);

        JOptionPane.showMessageDialog(
            this,
            "This active race is locked to:\n\n"
                + expectedPlayer
                + "\n\nYou are currently logged in as:\n\n"
                + actualPlayer
                + "\n\nThe race will remain paused and no wealth, loot, "
                + "bank or GE activity will count on this account.\n\n"
                + "Log back into "
                + expectedPlayer
                + " to continue the race.",
            "Race Account Locked",
            JOptionPane.WARNING_MESSAGE);

        updateButtonStates();
    }

    void onLoggedOut()
    {
        if (raceRunning && loggedIn)
        {
            if (manualPaused)
            {
                multiplayerState = "PAUSED";
                lastResumeAt = 0L;
                countdownTimer.stop();
            }
            else
            {
                addLedgerEvent("LOGOUT | Timer paused");
                if (negativeBalanceDeadlineMs <= 0L)
                {
                    multiplayerState = "PAUSED";
                }
                pauseTimer();
                if (negativeBalanceDeadlineMs > 0L)
                {
                    updateNegativeBalanceGrace();
                }
            }
        }
        loggedIn = false;
        playerValue.setText("Not logged in");
        if (!raceRunning)
        {
            setStatus("Waiting for login", Color.LIGHT_GRAY);
        }
    }

    private void resumeTimer()
    {
        if (!raceRunning || !loggedIn || manualPaused)
        {
            return;
        }
        lastResumeAt = System.currentTimeMillis();
        if (negativeBalanceDeadlineMs <= 0L)
        {
            setStatus("Race running", GREEN);
        }
        if (!countdownTimer.isRunning())
        {
            countdownTimer.start();
        }
    }

    private void pauseTimer()
    {
        updateCountdown();
        lastResumeAt = 0L;
        countdownTimer.stop();
        if (negativeBalanceDeadlineMs <= 0L)
        {
            setStatus("Paused - logged out", LIGHT_GOLD);
        }
    }

    private void updateCountdown()
    {
        if (!raceRunning || !loggedIn || manualPaused || lastResumeAt == 0L)
        {
            return;
        }

        long now = System.currentTimeMillis();
        long elapsed = now - lastResumeAt;
        lastResumeAt = now;
        remainingMilliseconds = Math.max(0L, remainingMilliseconds - elapsed);
        timeValue.setText(formatSeconds((remainingMilliseconds + 999L) / 1000L));

        if (remainingMilliseconds <= 0L)
        {
            finishRace();
        }
    }

    private void finishRace()
    {
        if (bankValueGp < 0L)
        {
            disqualifyForNegativeBalance();
            return;
        }

        clearNegativeBalanceGrace(false);
        raceRunning = false;
        manualPaused = false;
        manualPauseTargetGp = 0L;
        lastResumeAt = 0L;
        countdownTimer.stop();
        timeValue.setText("00:00:00");
        setStatus("Race finished", GOLD);
        multiplayerState = "FINISHED";
        addLedgerEvent(String.format(Locale.US, "RACE FINISHED | Final %,d GP", gpEarned));
        updateButtonStates();

        JOptionPane.showMessageDialog(
            this,
            "Race finished.\nFinal GP: " + String.format(Locale.US, "%,d GP", gpEarned),
            "0GP Race",
            JOptionPane.INFORMATION_MESSAGE);
    }

    private void leaveRace()
    {
        if (raceRunning)
        {
            int answer = JOptionPane.showConfirmDialog(
                this,
                "Leave the active race?",
                "Leave Race",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION)
            {
                return;
            }
        }

        plugin.leaveMultiplayerRoom(activeRoomCode);
        countdownTimer.stop();
        activeRoomCode = "";
        activeRaceName = "";
        remainingMilliseconds = 0L;
        lastResumeAt = 0L;
        transactionEngine.reset(0L);
        plugin.startWalletRace(0L);
        gpEarned = 0L;
        bankValueGp = 0L;
        bankValue.setText("0 GP");
        bankValue.setForeground(Color.WHITE);
        acceptedItems = 0L;
        raceRunning = false;
        manualPaused = false;
        raceProgressSaved = false;
        savedRaceProgressGp = -1L;
        manualPauseTargetGp = 0L;
        manualPauseCurrentGp = 0L;
        manualPauseEquipmentGp = 0L;
        clearNegativeBalanceGrace(false);
        multiplayerState = "IDLE";

        raceNameValue.setText("No active race");
        roomValue.setText("Not set");
        timeValue.setText("--:--:--");
        gpValue.setText("0 GP");
        gpValue.setForeground(Color.WHITE);
        acceptedValue.setText("0");
        recentLootArea.setText("No race activity yet.");
        playerListArea.setText("Start or join an online room.");
        multiplayerValue.setText("Local only");
        setStatus(loggedIn ? "Ready" : "Waiting for login", loggedIn ? GREEN : Color.LIGHT_GRAY);
        updateButtonStates();
    }

    void onSaveRaceProgressCheck(
        long inventoryValue,
        long equipmentValue,
        long currentBankValue)
    {
        if (!raceRunning || manualPaused)
        {
            return;
        }

        if (currentBankValue < 0L)
        {
            showError(String.format(
                Locale.US,
                "Cannot save race progress while Bank Value is negative.\n\n"
                    + "Current Bank Value: %,d GP\n\n"
                    + "Deposit enough value to restore Bank Value to 0 GP or higher first.",
                currentBankValue));
            return;
        }

        if (equipmentValue > 0L)
        {
            showError(String.format(
                Locale.US,
                "Remove all equipped gear before saving race progress.\n\n"
                    + "Equipped value: %,d GP",
                equipmentValue));
            return;
        }

        int answer = JOptionPane.showConfirmDialog(
            this,
            String.format(
                Locale.US,
                "SAVE RACE PROGRESS?\n\n"
                    + "IMPORTANT:\n"
                    + "Put ALL race wealth you want to preserve into your inventory before saving.\n\n"
                    + "Anything left in your bank will NOT be included in the saved race state "
                    + "and may be lost from the race when you resume.\n\n"
                    + "Starting allowance is NOT added again.\n"
                    + "Race Score is NOT added again.\n"
                    + "Bank Value is NOT added again.\n\n"
                    + "Current inventory value: %,d GP\n\n"
                    + "Save this exact value?",
                inventoryValue),
            "Save Race Progress",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);

        if (answer != JOptionPane.YES_OPTION)
        {
            return;
        }

        savedRaceProgressGp = Math.max(0L, inventoryValue);
        raceProgressSaved = true;
        manualPauseTargetGp = savedRaceProgressGp;

        plugin.confirmSaveRaceProgress(savedRaceProgressGp);

        addLedgerEvent(String.format(
            Locale.US,
            "RACE PROGRESS SAVED | Inventory %,d GP",
            savedRaceProgressGp));

        setStatus(String.format(
            Locale.US,
            "Inventory snapshot locked: %,d GP | Ready to pause",
            savedRaceProgressGp), GREEN);

        updateButtonStates();
    }

    boolean hasSavedRaceProgress()
    {
        return raceProgressSaved && savedRaceProgressGp >= 0L;
    }



    private void toggleManualPause()
    {
        if (!raceRunning)
        {
            return;
        }

        if (!manualPaused)
        {
            beginManualPause();
            return;
        }

        if (!loggedIn)
        {
            showError("Log back in before resuming the race.");
            return;
        }

        if (!manualPauseValueReady)
        {
            showError("Wait a moment for the plugin to read your current inventory value.");
            plugin.refreshManualPauseValue();
            return;
        }

        if (!isManualPauseReadyForResume())
        {
            showError(manualPauseStatusText());
            plugin.refreshManualPauseValue();
            return;
        }

        plugin.requestManualResume();
    }

    private void beginManualPause()
    {
        if (!raceRunning || manualPaused)
        {
            return;
        }

        if (!hasSavedRaceProgress())
        {
            showError(
                "Save Race Progress before pausing.\n\n"
                    + "Put ALL race wealth you want to preserve into your inventory, "
                    + "then press Save Race Progress.");
            return;
        }

        updateCountdown();

        manualPauseTargetGp = savedRaceProgressGp;
        manualPauseCurrentGp = 0L;
        manualPauseEquipmentGp = 0L;
        manualPauseValueReady = false;
        manualPaused = true;
        lastResumeAt = 0L;
        countdownTimer.stop();
        clearNegativeBalanceGrace(false);
        multiplayerState = "PAUSED";

        pauseResumeButton.setText("Resume Race");

        addLedgerEvent(String.format(
            Locale.US,
            "MANUAL PAUSE | Saved inventory wealth %,d GP",
            manualPauseTargetGp));

        setStatus("Paused", LIGHT_GOLD);
        refreshPauseGuide();
        updateButtonStates();

        plugin.onManualRacePaused(manualPauseTargetGp);
    }

    void updateManualPauseValue(long inventoryValue, long equipmentValue)
    {
        if (!manualPaused)
        {
            return;
        }

        manualPauseCurrentGp = Math.max(0L, inventoryValue);
        manualPauseEquipmentGp = Math.max(0L, equipmentValue);
        manualPauseValueReady = true;
        setStatus("Paused", LIGHT_GOLD);
        refreshPauseGuide();
        updateButtonStates();
    }

    private String manualPauseStatusText()
    {
        if (!manualPaused)
        {
            return "Race running";
        }

        if (!manualPauseValueReady)
        {
            return "Paused | Reading current race wealth...";
        }

        if (manualPauseEquipmentGp > 0L)
        {
            return String.format(
                Locale.US,
                "Paused | Deposit equipped gear: %,d GP",
                manualPauseEquipmentGp);
        }

        long difference = manualPauseTargetGp - manualPauseCurrentGp;
        if (difference > 0L)
        {
            return String.format(
                Locale.US,
                "Paused | Need %,d GP more",
                difference);
        }

        if (difference < 0L)
        {
            return String.format(
                Locale.US,
                "Paused | You have %,d GP too much",
                Math.abs(difference));
        }

        return String.format(
            Locale.US,
            "Paused | Exact %,d GP matched - ready",
            manualPauseTargetGp);
    }

    void onManualResumeCheck(long currentInventoryValue, long equipmentValue)
    {
        updateManualPauseValue(currentInventoryValue, equipmentValue);

        if (!manualPaused)
        {
            return;
        }

        if (equipmentValue > 0L)
        {
            JOptionPane.showMessageDialog(
                this,
                String.format(
                    Locale.US,
                    "Cannot resume yet.\\n\\n"
                        + "Required race wealth: %,d GP\\n"
                        + "Inventory value: %,d GP\\n"
                        + "Equipped gear value: %,d GP\\n\\n"
                        + "Deposit the equipped gear, then match the exact paused value in your inventory.",
                    manualPauseTargetGp,
                    currentInventoryValue,
                    equipmentValue),
                "Resume Race",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        long difference = manualPauseTargetGp - currentInventoryValue;
        if (difference != 0L)
        {
            String instruction = difference > 0L
                ? String.format(Locale.US, "Withdraw another %,d GP worth of value.", difference)
                : String.format(Locale.US, "Deposit/remove %,d GP worth of value.", Math.abs(difference));

            JOptionPane.showMessageDialog(
                this,
                String.format(
                    Locale.US,
                    "Cannot resume yet.\\n\\n"
                        + "Required race wealth: %,d GP\\n"
                        + "Current race wealth: %,d GP\\n\\n%s",
                    manualPauseTargetGp,
                    currentInventoryValue,
                    instruction),
                "Resume Race",
                JOptionPane.WARNING_MESSAGE);
        }
    }

    void completeManualResume()
    {
        if (!manualPaused || !raceRunning)
        {
            return;
        }

        long restoredValue = manualPauseTargetGp;

        manualPaused = false;
        raceProgressSaved = false;
        savedRaceProgressGp = -1L;
        manualPauseCurrentGp = restoredValue;
        manualPauseTargetGp = 0L;
        manualPauseEquipmentGp = 0L;
        manualPauseValueReady = false;
        multiplayerState = "RUNNING";
        pauseResumeButton.setText("Pause Race");
        refreshPauseGuide();

        addLedgerEvent(String.format(
            Locale.US,
            "MANUAL RESUME | Exact %,d GP restored | Save snapshot cleared",
            restoredValue));

        setStatus("Race running", GREEN);
        resumeTimer();
        updateButtonStates();
    }

    void updateBankValue(long valueGp)
    {
        bankValueGp = valueGp;
        bankValue.setText(String.format(
            Locale.US,
            "%,d GP",
            bankValueGp));
        bankValue.setForeground(
            bankValueGp < 0L ? RED : Color.WHITE);

        if (!raceRunning)
        {
            return;
        }

        if (bankValueGp < 0L)
        {
            startNegativeBalanceGraceIfNeeded();
        }
        else
        {
            clearNegativeBalanceGrace(true);
        }
    }

    void addAcceptedLoot(String itemName, int quantity, long value, String source)
    {
        if (!raceRunning || !loggedIn || quantity <= 0)
        {
            return;
        }

        acceptedItems += quantity;
        acceptedValue.setText(Long.toString(acceptedItems));
        applyScoreChange(itemName, quantity, Math.max(0L, value), source);
    }

    void addImportedValue(String itemName, int quantity, long value, String source)
    {
        if (!raceRunning || quantity <= 0 || value <= 0L)
        {
            return;
        }

        applyScoreChange(itemName, quantity, -Math.abs(value), source);
    }

    void addRaceOwnedConsumption(String itemName, int quantity, long value, String source)
    {
        if (!raceRunning || quantity <= 0 || value <= 0L)
        {
            return;
        }

        long signedValue = -Math.abs(value);
        RaceSource raceSource = RaceSource.fromLabel(source);

        transactionEngine.record(new RaceTransaction(
            System.currentTimeMillis(), raceSource, itemName, quantity, Math.abs(signedValue), signedValue,
            OwnershipType.RACE_OWNED, TransactionStatus.ACCEPTED, source));

        gpEarned = plugin.applyWalletScoreChange(signedValue);
        gpValue.setText(String.format(Locale.US, "%,d GP", gpEarned));
        gpValue.setForeground(gpEarned < 0L ? RED : Color.WHITE);

        addLedgerEvent(String.format(
            Locale.US,
            "%s x%d  -%,d GP [%s]",
            itemName, quantity, Math.abs(signedValue), source));

    }

    void addImportedRefund(String itemName, int quantity, long value, String source)
    {
        if (!raceRunning || quantity <= 0 || value <= 0L)
        {
            return;
        }

        applyScoreChange(itemName, quantity, Math.abs(value), source);
    }

    void addGeValueChange(String itemName, int quantity, long signedValue, String source)
    {
        if (!raceRunning || quantity <= 0 || signedValue == 0L)
        {
            return;
        }

        applyScoreChange(itemName, quantity, signedValue, source);
    }

    private void applyScoreChange(String itemName, int quantity, long signedValue, String source)
    {
        RaceSource raceSource = RaceSource.fromLabel(source);
        OwnershipType ownership = signedValue < 0L ? OwnershipType.IMPORTED : OwnershipType.RACE_OWNED;
        TransactionStatus status = TransactionStatus.ACCEPTED;
        long marketValue = Math.abs(signedValue);

        transactionEngine.record(new RaceTransaction(
            System.currentTimeMillis(), raceSource, itemName, quantity, marketValue, signedValue,
            ownership, status, source));

        gpEarned = plugin.applyWalletScoreChange(signedValue);
        gpValue.setText(String.format(Locale.US, "%,d GP", gpEarned));
        gpValue.setForeground(gpEarned < 0L ? RED : Color.WHITE);

        String line = String.format(
            Locale.US,
            "%s x%d  %s%,d GP [%s]",
            itemName, quantity, signedValue >= 0L ? "+" : "", signedValue, source);
        addLedgerEvent(line);

    }

    private void showRaceStatistics()
    {
        RaceStatistics stats = transactionEngine.getStatistics();
        String biggest = stats.getBiggestValue() > 0L
            ? stats.getBiggestItem() + " (" + String.format(Locale.US, "%,d GP", stats.getBiggestValue()) + ")"
            : "None yet";

        String message = String.format(
            Locale.US,
            "Current score: %,d GP\n"
                + "Positive GP generated: %,d GP\n"
                + "Imported value used: %,d GP\n"
                + "Scoring transactions: %,d\n"
                + "Biggest scoring event: %s\n"
                + "Recorded transactions: %,d",
            stats.getScore(), stats.getPositiveGp(), stats.getImportedGp(),
            stats.getAcceptedTransactions(), biggest, transactionEngine.getTransactions().size());

        JOptionPane.showMessageDialog(
            this, message, "0GP Race Statistics", JOptionPane.INFORMATION_MESSAGE);
    }

    void addNeutralTransaction(String itemName, int quantity, String source)
    {
        if (!raceRunning || quantity <= 0)
        {
            return;
        }

        transactionEngine.record(new RaceTransaction(
            System.currentTimeMillis(), RaceSource.fromLabel(source), itemName, quantity, 0L, 0L,
            OwnershipType.INTERNAL, TransactionStatus.INTERNAL_TRANSFER, source));
        addLedgerEvent(itemName + " x" + quantity + "  0 GP [" + source + "]");
    }

    void addLedgerEvent(String message)
    {
        if (message == null || message.trim().isEmpty())
        {
            return;
        }

        String line = EVENT_TIME.format(LocalTime.now()) + "  " + message.trim();
        String oldText = recentLootArea.getText();
        if (oldText == null || oldText.equals("No race activity yet.") || oldText.equals("Nothing accepted yet."))
        {
            recentLootArea.setText(line);
            recentLootArea.setCaretPosition(0);
            return;
        }

        recentLootArea.setText(line + "\n" + oldText);
        String[] lines = recentLootArea.getText().split("\n");
        if (lines.length > MAX_EVENT_LINES)
        {
            StringBuilder trimmed = new StringBuilder();
            for (int i = 0; i < MAX_EVENT_LINES; i++)
            {
                if (i > 0)
                {
                    trimmed.append('\n');
                }
                trimmed.append(lines[i]);
            }
            recentLootArea.setText(trimmed.toString());
        }
        recentLootArea.setCaretPosition(0);
    }

    private void startNegativeBalanceGraceIfNeeded()
    {
        if (!raceRunning || bankValueGp >= 0L || negativeBalanceDeadlineMs > 0L)
        {
            return;
        }

        negativeBalanceDeadlineMs = System.currentTimeMillis() + NEGATIVE_BALANCE_GRACE_MS;
        budgetGraceTimer.start();
        multiplayerState = "OVER_BUDGET";
        addLedgerEvent(String.format(Locale.US,
            "BANK OVERDRAWN | %,d GP | 30 second grace period started", bankValueGp));
        updateNegativeBalanceGrace();
    }

    private void updateNegativeBalanceGrace()
    {
        if (negativeBalanceDeadlineMs <= 0L)
        {
            budgetGraceTimer.stop();
            return;
        }

        if (!raceRunning)
        {
            clearNegativeBalanceGrace(false);
            return;
        }

        if (bankValueGp >= 0L)
        {
            clearNegativeBalanceGrace(true);
            return;
        }

        long millisLeft = negativeBalanceDeadlineMs - System.currentTimeMillis();
        if (millisLeft <= 0L)
        {
            disqualifyForNegativeBalance();
            return;
        }

        long secondsLeft = (millisLeft + 999L) / 1000L;
        setStatus(String.format(Locale.US,
            "BANK OVERDRAWN %,d GP - DQ in %ds", bankValueGp, secondsLeft), RED);
        multiplayerState = "OVER_BUDGET";
    }

    private void clearNegativeBalanceGrace(boolean restored)
    {
        if (negativeBalanceDeadlineMs <= 0L)
        {
            return;
        }

        negativeBalanceDeadlineMs = 0L;
        budgetGraceTimer.stop();

        if (restored && raceRunning)
        {
            addLedgerEvent(String.format(Locale.US,
                "BANK VALUE RESTORED | %,d GP | DQ countdown cancelled", bankValueGp));
            multiplayerState = loggedIn ? "RUNNING" : "PAUSED";
            setStatus(loggedIn ? "Race running" : "Paused - logged out",
                loggedIn ? GREEN : LIGHT_GOLD);
        }
    }

    private void disqualifyForNegativeBalance()
    {
        negativeBalanceDeadlineMs = 0L;
        budgetGraceTimer.stop();
        raceRunning = false;
        lastResumeAt = 0L;
        countdownTimer.stop();
        setStatus("DISQUALIFIED - over budget", RED);
        multiplayerState = "DQ";
        addLedgerEvent(String.format(Locale.US,
            "DISQUALIFIED | Bank value %,d GP remained negative for 30 seconds", bankValueGp));
        updateButtonStates();

        JOptionPane.showMessageDialog(
            this,
            "Your Bank Value remained below 0 GP for 30 seconds.\n\nCurrent bank value: "
                + String.format(Locale.US, "%,d GP", bankValueGp)
                + "\n\nThe grace period expired, so this run has been disqualified.",
            "Race Disqualified",
            JOptionPane.ERROR_MESSAGE);
    }

    void startJoinedRace(RaceRoomSnapshot room)
    {
        if (room == null)
        {
            setMultiplayerStatus("Join failed");
            updateButtonStates();
            return;
        }

        long durationMs = Math.max(1_000L, room.getDurationMilliseconds());
        startRace(room.getRaceName(), room.getRoomCode(), durationMs / 1000L,
            Math.max(0L, room.getStartingAllowance()), false);
        setMultiplayerStatus("Connected");
        updateMultiplayerPlayers(room);
        addLedgerEvent("MULTIPLAYER | Joined room " + room.getRoomCode());
    }

    void onMultiplayerRoomCreated(RaceRoomSnapshot room)
    {
        setMultiplayerStatus("Connected");
        updateMultiplayerPlayers(room);
        addLedgerEvent("MULTIPLAYER | Room online");
    }

    void onMultiplayerError(String message)
    {
        setMultiplayerStatus("Offline");
        addLedgerEvent("MULTIPLAYER ERROR | " + (message == null ? "Unknown error" : message));
        updateButtonStates();
    }

    void updateMultiplayerPlayers(RaceRoomSnapshot room)
    {
        if (room == null)
        {
            return;
        }

        List<RacePlayerSnapshot> players = new ArrayList<>(room.getPlayers());
        players.sort(Comparator.comparingLong(RacePlayerSnapshot::getScore).reversed()
            .thenComparing(RacePlayerSnapshot::getPlayerName, String.CASE_INSENSITIVE_ORDER));

        if (players.isEmpty())
        {
            playerListArea.setText("No players synced yet.");
            return;
        }

        StringBuilder out = new StringBuilder();
        int position = 1;
        for (RacePlayerSnapshot player : players)
        {
            if (out.length() > 0)
            {
                out.append('\n');
            }
            out.append(position++).append(". ")
                .append(player.getPlayerName())
                .append("  ")
                .append(String.format(Locale.US, "%,d GP", player.getScore()))
                .append("  [").append(player.getRaceState()).append(']');
        }
        playerListArea.setText(out.toString());
        playerListArea.setCaretPosition(0);
    }

    void setMultiplayerStatus(String text)
    {
        multiplayerValue.setText(text == null || text.trim().isEmpty() ? "Offline" : text);
    }

    long getScore()
    {
        return gpEarned;
    }

    long getRemainingMilliseconds()
    {
        if (raceRunning && loggedIn && !manualPaused && lastResumeAt > 0L)
        {
            return Math.max(0L, remainingMilliseconds - (System.currentTimeMillis() - lastResumeAt));
        }
        return Math.max(0L, remainingMilliseconds);
    }

    boolean isLoggedInForSync()
    {
        return loggedIn;
    }

    String getCurrentPlayerName()
    {
        return currentPlayerName;
    }

    String getMultiplayerState()
    {
        return multiplayerState;
    }

    boolean isRaceRunning()
    {
        return raceRunning;
    }

    boolean isManualPaused()
    {
        return manualPaused;
    }

    boolean isManualPauseReadyForResume()
    {
        return manualPaused
            && manualPauseValueReady
            && manualPauseEquipmentGp <= 0L
            && manualPauseCurrentGp == manualPauseTargetGp;
    }

    long getManualPauseTargetGp()
    {
        return manualPauseTargetGp;
    }

    String getActiveRoomCode()
    {
        return activeRoomCode;
    }

    private void updateButtonStates()
    {
        pauseGuidePanel.setVisible(manualPaused);

        createRaceButton.setEnabled(!raceRunning);
        joinRaceButton.setEnabled(!raceRunning);
        dashboardButton.setEnabled(!activeRoomCode.isEmpty());

        saveProgressButton.setEnabled(
            raceRunning
                && loggedIn
                && !manualPaused);

        if (!raceRunning)
        {
            saveProgressButton.setText("Save Race Progress");
        }
        else if (!manualPaused && raceProgressSaved)
        {
            saveProgressButton.setText(String.format(
                Locale.US,
                "Resave (%,d GP)",
                savedRaceProgressGp));
        }
        else
        {
            saveProgressButton.setText("Save Race Progress");
        }

        boolean pauseButtonEnabled =
            raceRunning
                && loggedIn
                && (manualPaused
                    ? isManualPauseReadyForResume()
                    : hasSavedRaceProgress());

        pauseResumeButton.setEnabled(pauseButtonEnabled);

        if (!manualPaused)
        {
            pauseResumeButton.setText(
                hasSavedRaceProgress()
                    ? "Pause Race"
                    : "Pause (Save First)");
        }
        else if (isManualPauseReadyForResume())
        {
            pauseResumeButton.setText("Resume Race");
        }

        leaveRaceButton.setEnabled(
            raceRunning || !activeRoomCode.isEmpty());

        statsButton.setEnabled(
            raceRunning
                || !activeRoomCode.isEmpty()
                || !transactionEngine
                    .getTransactions()
                    .isEmpty());
    }

    private void setStatus(String text, Color colour)
    {
        statusValue.setText(text);
        statusValue.setForeground(colour);
        statusValue.setToolTipText(text);
    }

    private static long parseGpAllowance(String text)
    {
        if (text == null)
        {
            return 0L;
        }

        String value = text.trim().toLowerCase(Locale.ROOT).replace(",", "").replace(" ", "");
        if (value.isEmpty())
        {
            return 0L;
        }

        long multiplier = 1L;
        if (value.endsWith("k"))
        {
            multiplier = 1_000L;
            value = value.substring(0, value.length() - 1);
        }
        else if (value.endsWith("m"))
        {
            multiplier = 1_000_000L;
            value = value.substring(0, value.length() - 1);
        }
        else if (value.endsWith("b"))
        {
            multiplier = 1_000_000_000L;
            value = value.substring(0, value.length() - 1);
        }

        if (value.isEmpty() || value.startsWith("-"))
        {
            throw new NumberFormatException("invalid allowance");
        }

        double numeric = Double.parseDouble(value);
        if (numeric < 0.0 || Double.isInfinite(numeric) || Double.isNaN(numeric))
        {
            throw new NumberFormatException("invalid allowance");
        }

        double result = numeric * multiplier;
        if (result > Long.MAX_VALUE)
        {
            throw new NumberFormatException("allowance too large");
        }
        return (long) result;
    }

    private static long toSeconds(int amount, String unit)
    {
        if ("Days".equals(unit))
        {
            return amount * 86_400L;
        }
        if ("Hours".equals(unit))
        {
            return amount * 3_600L;
        }
        return amount * 60L;
    }

    private static String formatSeconds(long totalSeconds)
    {
        long hours = totalSeconds / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private static String generateRoomCode()
    {
        int number = ThreadLocalRandom.current().nextInt(1000, 10_000);
        return "0GP-" + number;
    }

    private static String normaliseRoom(String value)
    {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }

    void onCleanRaceStartBlocked(String message)
    {
        showError(message == null || message.trim().isEmpty()
            ? "Empty your inventory and equipment before starting or joining a race."
            : message);
        updateButtonStates();
    }

    private void showError(String message)
    {
        JOptionPane.showMessageDialog(
            this,
            message,
            "0GP Race",
            JOptionPane.ERROR_MESSAGE);
    }
}
