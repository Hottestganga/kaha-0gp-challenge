package com.kahakoolkids.zerogp;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
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
import javax.swing.SwingConstants;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

final class ZeroGpRacePanel extends PluginPanel
{
    private static final Color GOLD = new Color(212, 175, 55);
    private static final Color GREEN = new Color(80, 200, 120);
    private static final Color LIGHT_GOLD = new Color(245, 210, 100);

    private final ZeroGpRacePlugin plugin;

    private final JLabel statusValue = valueLabel("Waiting");
    private final JLabel roomValue = valueLabel("Not set");
    private final JLabel playerValue = valueLabel("Not logged in");
    private final JLabel timeValue = valueLabel("--:--:--");
    private final JLabel gpValue = valueLabel("0 GP");
    private final JLabel acceptedValue = valueLabel("0");
    private final JTextArea recentLootArea = new JTextArea(6, 20);

    private final JButton createRaceButton = new JButton("Create Race");
    private final JButton joinRaceButton = new JButton("Join Race");
    private final JButton dashboardButton = new JButton("Open Dashboard");
    private final JButton leaveRaceButton = new JButton("Leave Race");

    private final Timer countdownTimer;

    private String activeRoomCode = "";
    private String activeRaceName = "";
    private long remainingMilliseconds;
    private long lastResumeAt;
    private long gpEarned;
    private long acceptedItems;
    private boolean raceRunning;
    private boolean loggedIn;

    ZeroGpRacePanel(ZeroGpRacePlugin plugin)
    {
        this.plugin = plugin;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(ColorScheme.DARK_GRAY_COLOR);
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel title = new JLabel("KAHA 0GP RACE", SwingConstants.CENTER);
        title.setAlignmentX(CENTER_ALIGNMENT);
        title.setForeground(GOLD);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        content.add(title);
        content.add(Box.createRigidArea(new Dimension(0, 12)));
        content.add(infoGrid());
        content.add(Box.createRigidArea(new Dimension(0, 12)));
        content.add(recentLootPanel());
        content.add(Box.createRigidArea(new Dimension(0, 12)));

        prepareButton(createRaceButton, true);
        prepareButton(joinRaceButton, false);
        prepareButton(dashboardButton, false);
        prepareButton(leaveRaceButton, false);

        createRaceButton.addActionListener(event -> showCreateRaceDialog());
        joinRaceButton.addActionListener(event -> showJoinRaceDialog());
        dashboardButton.addActionListener(event -> plugin.openDashboard(activeRoomCode));
        leaveRaceButton.addActionListener(event -> leaveRace());

        content.add(createRaceButton);
        content.add(Box.createRigidArea(new Dimension(0, 7)));
        content.add(joinRaceButton);
        content.add(Box.createRigidArea(new Dimension(0, 7)));
        content.add(dashboardButton);
        content.add(Box.createRigidArea(new Dimension(0, 7)));
        content.add(leaveRaceButton);

        JLabel note = new JLabel(
            "<html><center>The timer only runs while logged in. Loot scoring is the next milestone.</center></html>");
        note.setAlignmentX(CENTER_ALIGNMENT);
        note.setForeground(Color.LIGHT_GRAY);
        note.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        content.add(note);

        add(content, BorderLayout.NORTH);

        countdownTimer = new Timer(1000, event -> updateCountdown());
        updateButtonStates();
    }

    private JPanel infoGrid()
    {
        JPanel grid = new JPanel(new GridLayout(6, 2, 6, 6));
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
        return grid;
    }

    private JPanel recentLootPanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(GOLD.darker()),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 145));

        JLabel heading = new JLabel("Recent accepted loot");
        heading.setForeground(GOLD);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));

        recentLootArea.setEditable(false);
        recentLootArea.setFocusable(false);
        recentLootArea.setLineWrap(true);
        recentLootArea.setWrapStyleWord(true);
        recentLootArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        recentLootArea.setForeground(Color.WHITE);
        recentLootArea.setText("Nothing accepted yet.");

        panel.add(heading, BorderLayout.NORTH);
        panel.add(recentLootArea, BorderLayout.CENTER);
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
        JTextField nameField = new JTextField("Kaha 0GP Race");
        JTextField durationField = new JTextField("4");
        JComboBox<String> unitBox = new JComboBox<>(new String[]{"Minutes", "Hours", "Days"});
        unitBox.setSelectedItem("Hours");

        JPanel form = new JPanel(new GridLayout(0, 1, 5, 5));
        form.add(new JLabel("Race name:"));
        form.add(nameField);
        form.add(new JLabel("Duration:"));
        form.add(durationField);
        form.add(unitBox);

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

        long durationSeconds = toSeconds(amount, String.valueOf(unitBox.getSelectedItem()));
        startRace(raceName, generateRoomCode(), durationSeconds);
    }

    private void showJoinRaceDialog()
    {
        JTextField roomField = new JTextField();
        JTextField durationField = new JTextField("4");
        JComboBox<String> unitBox = new JComboBox<>(new String[]{"Minutes", "Hours", "Days"});
        unitBox.setSelectedItem("Hours");

        JPanel form = new JPanel(new GridLayout(0, 1, 5, 5));
        form.add(new JLabel("Room code:"));
        form.add(roomField);
        form.add(new JLabel("Temporary local duration:"));
        form.add(durationField);
        form.add(unitBox);

        int result = JOptionPane.showConfirmDialog(
            this,
            form,
            "Join Race",
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

        startRace("Joined Race", room, toSeconds(amount, String.valueOf(unitBox.getSelectedItem())));
    }

    private void startRace(String raceName, String roomCode, long durationSeconds)
    {
        activeRaceName = raceName;
        activeRoomCode = roomCode;
        remainingMilliseconds = durationSeconds * 1000L;
        lastResumeAt = 0L;
        gpEarned = 0L;
        acceptedItems = 0L;
        raceRunning = true;

        roomValue.setText(activeRoomCode);
        timeValue.setText(formatSeconds(durationSeconds));
        gpValue.setText("0 GP");
        acceptedValue.setText("0");
        recentLootArea.setText("Nothing accepted yet.");
        plugin.onLocalRaceStarted();

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
                + "\n\nThe timer pauses whenever you are logged out.",
            "Race Started",
            JOptionPane.INFORMATION_MESSAGE);
    }

    void onLoggedIn(String playerName)
    {
        loggedIn = true;
        playerValue.setText(isBlank(playerName) ? "Logged in" : playerName);
        if (raceRunning)
        {
            resumeTimer();
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
            pauseTimer();
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
        setStatus("Race running", GREEN);
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
        setStatus("Paused - logged out", LIGHT_GOLD);
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
        raceRunning = false;
        lastResumeAt = 0L;
        countdownTimer.stop();
        timeValue.setText("00:00:00");
        setStatus("Race finished", GOLD);
        updateButtonStates();

        JOptionPane.showMessageDialog(
            this,
            "Race finished.\nFinal GP: " + String.format(Locale.US, "%,d GP", gpEarned),
            "Kaha 0GP Race",
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

        countdownTimer.stop();
        activeRoomCode = "";
        activeRaceName = "";
        remainingMilliseconds = 0L;
        lastResumeAt = 0L;
        gpEarned = 0L;
        acceptedItems = 0L;
        raceRunning = false;

        roomValue.setText("Not set");
        timeValue.setText("--:--:--");
        gpValue.setText("0 GP");
        acceptedValue.setText("0");
        recentLootArea.setText("Nothing accepted yet.");
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
        gpEarned += Math.max(0L, value);
        acceptedValue.setText(Long.toString(acceptedItems));
        gpValue.setText(String.format(Locale.US, "%,d GP", gpEarned));

        String line = String.format(
            Locale.US,
            "%s x%d  +%,d GP [%s]",
            itemName, quantity, Math.max(0L, value), source);

        String oldText = recentLootArea.getText();
        if (oldText == null || oldText.equals("Nothing accepted yet."))
        {
            recentLootArea.setText(line);
        }
        else
        {
            recentLootArea.setText(line + "\n" + oldText);
            String[] lines = recentLootArea.getText().split("\n");
            if (lines.length > 6)
            {
                StringBuilder trimmed = new StringBuilder();
                for (int i = 0; i < 6; i++)
                {
                    if (i > 0)
                    {
                        trimmed.append('\n');
                    }
                    trimmed.append(lines[i]);
                }
                recentLootArea.setText(trimmed.toString());
            }
        }
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
    }

    private void setStatus(String text, Color colour)
    {
        statusValue.setText(text);
        statusValue.setForeground(colour);
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
        return "KAHA-" + number;
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
            "Kaha 0GP Race",
            JOptionPane.ERROR_MESSAGE);
    }
}
