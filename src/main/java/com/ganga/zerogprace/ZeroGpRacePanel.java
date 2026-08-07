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
    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_EVENT_LINES = 12;
    private static final long NEGATIVE_BALANCE_GRACE_MS = 30_000L;

    private final ZeroGpRacePlugin plugin;

    private final JLabel statusValue = valueLabel("Waiting");
    private final JLabel roomValue = valueLabel("Not set");
    private final JLabel playerValue = valueLabel("Not logged in");
    private final JLabel timeValue = valueLabel("--:--:--");
    private final JLabel gpValue = valueLabel("0 GP");
    private final JLabel acceptedValue = valueLabel("0");
    private final JLabel multiplayerValue = valueLabel("Local only");
    private final JTextArea playerListArea = new JTextArea(5, 20);
    private final JTextArea recentLootArea = new JTextArea(10, 20);

    private final JButton createRaceButton = new JButton("Create Race");
    private final JButton joinRaceButton = new JButton("Join Race");
    private final JButton dashboardButton = new JButton("Open Dashboard");
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
    private long acceptedItems;
    private long negativeBalanceDeadlineMs;
    private boolean raceRunning;
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
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel title = new JLabel("0GP RACE", SwingConstants.CENTER);
        title.setAlignmentX(CENTER_ALIGNMENT);
        title.setForeground(GOLD);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        content.add(title);
        content.add(Box.createRigidArea(new Dimension(0, 12)));
        content.add(infoGrid());
        content.add(Box.createRigidArea(new Dimension(0, 12)));
        content.add(playerListPanel());
        content.add(Box.createRigidArea(new Dimension(0, 12)));
        content.add(recentLootPanel());
        content.add(Box.createRigidArea(new Dimension(0, 12)));

        prepareButton(createRaceButton, true);
        prepareButton(joinRaceButton, false);
        prepareButton(dashboardButton, false);
        prepareButton(leaveRaceButton, false);
        prepareButton(statsButton, false);

        createRaceButton.addActionListener(event -> showCreateRaceDialog());
        joinRaceButton.addActionListener(event -> showJoinRaceDialog());
        dashboardButton.addActionListener(event -> plugin.openDashboard(activeRoomCode));
        leaveRaceButton.addActionListener(event -> leaveRace());
        statsButton.addActionListener(event -> showRaceStatistics());

        content.add(createRaceButton);
        content.add(Box.createRigidArea(new Dimension(0, 7)));
        content.add(joinRaceButton);
        content.add(Box.createRigidArea(new Dimension(0, 7)));
        content.add(dashboardButton);
        content.add(Box.createRigidArea(new Dimension(0, 7)));
        content.add(leaveRaceButton);
        content.add(Box.createRigidArea(new Dimension(0, 7)));
        content.add(statsButton);

        JLabel note = new JLabel(
            "<html><center>Score starts at the host allowance. Imported inventory/equipment and bank withdrawals are debited.</center></html>");
        note.setAlignmentX(CENTER_ALIGNMENT);
        note.setForeground(Color.LIGHT_GRAY);
        note.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        content.add(note);

        add(content, BorderLayout.NORTH);

        countdownTimer = new Timer(1000, event -> updateCountdown());
        budgetGraceTimer = new Timer(250, event -> updateNegativeBalanceGrace());
        updateButtonStates();
    }

    private JPanel infoGrid()
    {
        JPanel grid = new JPanel(new GridLayout(7, 2, 6, 6));
        grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        grid.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(GOLD.darker()),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)));

        addRow(grid, "Status", statusValue);
        addRow(grid, "Room", roomValue);
        addRow(grid, "Player", playerValue);
        addRow(grid, "Time left", timeValue);
        addRow(grid, "GP earned", gpValue);
        addRow(grid, "Accepted items", acceptedValue);
        addRow(grid, "Multiplayer", multiplayerValue);
        return grid;
    }

    private JPanel playerListPanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(GOLD.darker()),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 135));

        JLabel heading = new JLabel("Live players");
        heading.setForeground(GOLD);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));

        playerListArea.setEditable(false);
        playerListArea.setFocusable(false);
        playerListArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        playerListArea.setForeground(Color.WHITE);
        playerListArea.setText("Start or join an online room.");

        JScrollPane scrollPane = new JScrollPane(playerListArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);

        panel.add(heading, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel recentLootPanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(GOLD.darker()),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 245));

        JLabel heading = new JLabel("Race activity log");
        heading.setForeground(GOLD);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));

        recentLootArea.setEditable(false);
        recentLootArea.setFocusable(false);
        recentLootArea.setLineWrap(true);
        recentLootArea.setWrapStyleWord(true);
        recentLootArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        recentLootArea.setForeground(Color.WHITE);
        recentLootArea.setText("No race activity yet.");

        JScrollPane scrollPane = new JScrollPane(recentLootArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);

        panel.add(heading, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private static void addRow(JPanel panel, String label, JLabel value)
    {
        JLabel key = new JLabel(label + ":");
        key.setForeground(Color.LIGHT_GRAY);
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
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        button.setFocusPainted(false);
        if (gold)
        {
            button.setBackground(GOLD);
            button.setForeground(Color.BLACK);
            button.setFont(button.getFont().deriveFont(Font.BOLD));
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
        startRace(raceName, generateRoomCode(), durationSeconds, startingAllowance, true);
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

        setMultiplayerStatus("Joining...");
        joinRaceButton.setEnabled(false);
        createRaceButton.setEnabled(false);
        plugin.joinMultiplayerRoom(room);
    }

    private void startRace(String raceName, String roomCode, long durationSeconds, long startingAllowance, boolean createOnlineRoom)
    {
        activeRaceName = raceName;
        activeRoomCode = roomCode;
        remainingMilliseconds = durationSeconds * 1000L;
        lastResumeAt = 0L;
        transactionEngine.reset(startingAllowance);
        gpEarned = transactionEngine.getScore();
        acceptedItems = 0L;
        raceRunning = true;
        clearNegativeBalanceGrace(false);

        roomValue.setText(activeRoomCode);
        timeValue.setText(formatSeconds(durationSeconds));
        gpValue.setText(String.format(Locale.US, "%,d GP", gpEarned));
        gpValue.setForeground(Color.WHITE);
        acceptedValue.setText("0");
        recentLootArea.setText("No race activity yet.");
        addLedgerEvent(String.format(Locale.US, "RACE STARTED | %s | %s", activeRoomCode, formatSeconds(durationSeconds)));
        if (gpEarned > 0L)
        {
            transactionEngine.record(new RaceTransaction(
                System.currentTimeMillis(), RaceSource.ALLOWANCE, "Starting allowance", 1,
                gpEarned, 0L, OwnershipType.RACE_OWNED, TransactionStatus.INFO, "Starting balance"));
            addLedgerEvent(String.format(Locale.US, "ALLOWANCE | +%,d GP", gpEarned));
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
                + "\nStarting allowance: " + String.format(Locale.US, "%,d GP", gpEarned)
                + "\n\nThe timer pauses whenever you are logged out.",
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
            addLedgerEvent("LOGIN | Timer resumed");
            multiplayerState = negativeBalanceDeadlineMs > 0L ? "OVER_BUDGET" : "RUNNING";
            resumeTimer();
            if (negativeBalanceDeadlineMs > 0L)
            {
                updateNegativeBalanceGrace();
            }
        }
        else
        {
            setStatus("Ready", GREEN);
        }
    }

    void onLoggedOut()
    {
        if (raceRunning && loggedIn)
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
        loggedIn = false;
        playerValue.setText("Not logged in");
        if (!raceRunning)
        {
            setStatus("Waiting for login", Color.LIGHT_GRAY);
        }
    }

    private void resumeTimer()
    {
        if (!raceRunning || !loggedIn)
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
        if (!raceRunning || !loggedIn || lastResumeAt == 0L)
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
        if (gpEarned < 0L)
        {
            disqualifyForNegativeBalance();
            return;
        }

        clearNegativeBalanceGrace(false);
        raceRunning = false;
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
        gpEarned = 0L;
        acceptedItems = 0L;
        raceRunning = false;
        clearNegativeBalanceGrace(false);
        multiplayerState = "IDLE";

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

    void addImportedRefund(String itemName, int quantity, long value, String source)
    {
        if (!raceRunning || quantity <= 0 || value <= 0L)
        {
            return;
        }

        applyScoreChange(itemName, quantity, Math.abs(value), source);
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

        gpEarned = transactionEngine.getScore();
        gpValue.setText(String.format(Locale.US, "%,d GP", gpEarned));
        gpValue.setForeground(gpEarned < 0L ? RED : Color.WHITE);

        String line = String.format(
            Locale.US,
            "%s x%d  %s%,d GP [%s]",
            itemName, quantity, signedValue >= 0L ? "+" : "", signedValue, source);
        addLedgerEvent(line);

        if (gpEarned < 0L)
        {
            startNegativeBalanceGraceIfNeeded();
        }
        else
        {
            clearNegativeBalanceGrace(true);
        }
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
        if (!raceRunning || gpEarned >= 0L || negativeBalanceDeadlineMs > 0L)
        {
            return;
        }

        negativeBalanceDeadlineMs = System.currentTimeMillis() + NEGATIVE_BALANCE_GRACE_MS;
        budgetGraceTimer.start();
        multiplayerState = "OVER_BUDGET";
        addLedgerEvent(String.format(Locale.US,
            "OVER BUDGET | %,d GP | 30 second grace period started", gpEarned));
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

        if (gpEarned >= 0L)
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
            "OVER BUDGET %,d GP - DQ in %ds", gpEarned, secondsLeft), RED);
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
                "BUDGET RESTORED | %,d GP | DQ countdown cancelled", gpEarned));
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
            "DISQUALIFIED | Negative balance %,d GP remained for 30 seconds", gpEarned));
        updateButtonStates();

        JOptionPane.showMessageDialog(
            this,
            "Your race score remained below 0 GP for 30 seconds.\n\nCurrent score: "
                + String.format(Locale.US, "%,d GP", gpEarned)
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
        if (raceRunning && loggedIn && lastResumeAt > 0L)
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

    String getActiveRoomCode()
    {
        return activeRoomCode;
    }

    private void updateButtonStates()
    {
        createRaceButton.setEnabled(!raceRunning);
        joinRaceButton.setEnabled(!raceRunning);
        dashboardButton.setEnabled(!activeRoomCode.isEmpty());
        leaveRaceButton.setEnabled(raceRunning || !activeRoomCode.isEmpty());
        statsButton.setEnabled(raceRunning || !activeRoomCode.isEmpty() || !transactionEngine.getTransactions().isEmpty());
    }

    private void setStatus(String text, Color colour)
    {
        statusValue.setText(text);
        statusValue.setForeground(colour);
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

    private void showError(String message)
    {
        JOptionPane.showMessageDialog(
            this,
            message,
            "0GP Race",
            JOptionPane.ERROR_MESSAGE);
    }
}
