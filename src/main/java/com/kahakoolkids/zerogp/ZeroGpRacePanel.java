package com.kahakoolkids.zerogp;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

final class ZeroGpRacePanel extends PluginPanel
{
    private static final Color GOLD = new Color(212, 175, 55);

    private final JLabel statusValue = valueLabel("Waiting");
    private final JLabel roomValue = valueLabel("Not set");
    private final JLabel playerValue = valueLabel("Not logged in");
    private final JLabel acceptedValue = valueLabel("0");

    ZeroGpRacePanel(ZeroGpRacePlugin plugin)
    {
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

        JButton dashboard = new JButton("Open Dashboard");
        dashboard.setAlignmentX(CENTER_ALIGNMENT);
        dashboard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        dashboard.addActionListener(event -> plugin.openDashboard());
        content.add(dashboard);

        JLabel settingsNote = new JLabel(
            "<html><center>Use the RuneLite plugin settings to enter the dashboard URL and room code.</center></html>");
        settingsNote.setAlignmentX(CENTER_ALIGNMENT);
        settingsNote.setForeground(Color.LIGHT_GRAY);
        settingsNote.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        content.add(settingsNote);

        JLabel ruleNote = new JLabel(
            "<html><center>Only eligible NPC and PvP loot actually picked up is submitted.</center></html>");
        ruleNote.setAlignmentX(CENTER_ALIGNMENT);
        ruleNote.setForeground(Color.LIGHT_GRAY);
        ruleNote.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        content.add(ruleNote);

        add(content, BorderLayout.NORTH);
    }

    private JPanel infoGrid()
    {
        JPanel grid = new JPanel(new GridLayout(4, 2, 6, 6));
        grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        grid.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(GOLD.darker()),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)));
        addRow(grid, "Status", statusValue);
        addRow(grid, "Room", roomValue);
        addRow(grid, "Player", playerValue);
        addRow(grid, "Accepted items", acceptedValue);
        return grid;
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

    void updateState(String status, String room, String player, long accepted)
    {
        statusValue.setText(status);
        roomValue.setText(isBlank(room) ? "Not set" : room);
        playerValue.setText(isBlank(player) ? "Not logged in" : player);
        acceptedValue.setText(Long.toString(accepted));
    }

    private static boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }
}
