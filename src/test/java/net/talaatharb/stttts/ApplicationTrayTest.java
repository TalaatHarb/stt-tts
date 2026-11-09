package net.talaatharb.stttts;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.CheckboxMenuItem;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

class ApplicationTrayTest {
    @Test
    void offersSettingsAndMcpAlongsideExistingActions() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Native AWT menus require a desktop");
        EventQueue.invokeAndWait(() -> {
            List<String> actions = new ArrayList<>();
            var menu = ApplicationTray.createMenu(() -> actions.add("maximize"), () -> actions.add("settings"),
                    () -> actions.add("mcp"), () -> actions.add("exit"), false);
            assertEquals(5, menu.getItemCount());
            assertEquals("Settings...", menu.getItem(1).getLabel());
            CheckboxMenuItem mcp = (CheckboxMenuItem) menu.getItem(2);
            assertFalse(mcp.getState());
            invoke(menu.getItem(0));
            invoke(menu.getItem(1));
            mcp.setState(true);
            for (var listener : mcp.getItemListeners()) {
                listener.itemStateChanged(new ItemEvent(mcp, ItemEvent.ITEM_STATE_CHANGED, mcp, ItemEvent.SELECTED));
            }
            invoke(menu.getItem(4));
            assertEquals(List.of("maximize", "settings", "mcp", "exit"), actions);
            assertTrue(((CheckboxMenuItem) ApplicationTray.createMenu(() -> {}, () -> {}, () -> {}, () -> {}, true)
                    .getItem(2)).getState());
        });
    }

    private static void invoke(MenuItem item) {
        for (var listener : item.getActionListeners()) {
            listener.actionPerformed(new ActionEvent(item, ActionEvent.ACTION_PERFORMED, item.getLabel()));
        }
    }
}
