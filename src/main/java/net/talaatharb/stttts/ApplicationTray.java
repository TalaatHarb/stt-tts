package net.talaatharb.stttts;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.CheckboxMenuItem;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.stage.Stage;

/** Bridges native AWT tray events to the JavaFX application thread. */
final class ApplicationTray implements AutoCloseable {
    private final Stage stage;
    private final Runnable openSettings;
    private final Runnable toggleMcp;
    private final ReadOnlyBooleanProperty mcpEnabled;
    private final ChangeListener<Boolean> mcpListener;
    private CheckboxMenuItem mcpItem;
    private TrayIcon icon;

    ApplicationTray(Stage stage, Runnable openSettings, Runnable toggleMcp, ReadOnlyBooleanProperty mcpEnabled) {
        this.stage = stage;
        this.openSettings = openSettings;
        this.toggleMcp = toggleMcp;
        this.mcpEnabled = mcpEnabled;
        mcpListener = (observable, oldValue, enabled) -> EventQueue.invokeLater(() -> {
            if (mcpItem != null) {
                mcpItem.setState(enabled);
            }
        });
    }

    void install() throws AWTException {
        if (!SystemTray.isSupported()) {
            throw new AWTException("The system tray is not supported on this desktop.");
        }
        AWTException[] failure = new AWTException[1];
        boolean enabled = mcpEnabled.get();
        onAwtThread(() -> {
            PopupMenu menu = createMenu(() -> Platform.runLater(this::maximize),
                    () -> Platform.runLater(() -> {
                        maximize();
                        openSettings.run();
                    }), () -> Platform.runLater(() -> {
                        toggleMcp.run();
                        // Refresh even when starting fails and the enabled property remains false.
                        boolean state = mcpEnabled.get();
                        EventQueue.invokeLater(() -> {
                            if (mcpItem != null) {
                                mcpItem.setState(state);
                            }
                        });
                    }), () -> Platform.runLater(Platform::exit), enabled);
            mcpItem = (CheckboxMenuItem) menu.getItem(2);
            TrayIcon candidate = new TrayIcon(createIcon(), "STT-TTS Studio", menu);
            candidate.setImageAutoSize(true);
            candidate.addActionListener(e -> Platform.runLater(this::maximize));
            try {
                SystemTray.getSystemTray().add(candidate);
                icon = candidate;
            } catch (AWTException e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            mcpItem = null;
            throw failure[0];
        }
        mcpEnabled.addListener(mcpListener);
        Platform.setImplicitExit(false);
        stage.setOnCloseRequest(event -> {
            event.consume();
            stage.hide();
        });
    }

    static PopupMenu createMenu(Runnable maximizeAction, Runnable settingsAction, Runnable mcpAction,
            Runnable exitAction, boolean enabled) {
        PopupMenu menu = new PopupMenu();
        MenuItem maximize = new MenuItem("Maximize");
        maximize.addActionListener(event -> maximizeAction.run());
        MenuItem settings = new MenuItem("Settings...");
        settings.addActionListener(event -> settingsAction.run());
        CheckboxMenuItem mcp = new CheckboxMenuItem("MCP enabled", enabled);
        mcp.addItemListener(event -> mcpAction.run());
        MenuItem exit = new MenuItem("Exit");
        exit.addActionListener(event -> exitAction.run());
        menu.add(maximize);
        menu.add(settings);
        menu.add(mcp);
        menu.addSeparator();
        menu.add(exit);
        return menu;
    }

    void maximize() {
        stage.setIconified(false);
        stage.setMaximized(true);
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    private static BufferedImage createIcon() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(37, 99, 235));
            graphics.fillRoundRect(0, 0, 32, 32, 8, 8);
            graphics.setColor(Color.WHITE);
            int[] heights = {6, 14, 22, 12, 18, 8};
            for (int i = 0; i < heights.length; i++) {
                int x = 5 + i * 4;
                graphics.fillRoundRect(x, 16 - heights[i] / 2, 3, heights[i], 2, 2);
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static void onAwtThread(Runnable action) {
        if (EventQueue.isDispatchThread()) {
            action.run();
            return;
        }
        try {
            EventQueue.invokeAndWait(action);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while updating the application tray", e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Could not update the application tray", e.getCause());
        }
    }

    @Override
    public void close() {
        mcpEnabled.removeListener(mcpListener);
        onAwtThread(() -> {
            if (icon != null) {
                SystemTray.getSystemTray().remove(icon);
                icon = null;
            }
            mcpItem = null;
        });
        stage.setOnCloseRequest(null);
    }
}
