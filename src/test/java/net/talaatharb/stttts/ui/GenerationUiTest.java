package net.talaatharb.stttts.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import com.google.inject.Guice;
import com.google.inject.Injector;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TabPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.stttts.config.AppModule;
import net.talaatharb.stttts.config.AppSettings;

@EnabledIfSystemProperty(named = "stttts.ui", matches = "true")
class GenerationUiTest {
    @TempDir Path temp;

    @Test
    void loadsAllTabsAndRequiresLicenseAcceptance() throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        FutureTask<Void> load = new FutureTask<>(() -> {
            Injector injector = Guice.createInjector(new AppModule(temp.resolve("models"),
                    new AppSettings(temp.resolve("settings.properties"))));
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/net/talaatharb/stttts/ui/main.fxml"));
                loader.setControllerFactory(injector::getInstance);
                Parent root = loader.load();
                TabPane tabs = (TabPane) root.lookupAll(".tab-pane").stream().findFirst().orElseThrow();
                assertEquals(4, tabs.getTabs().size());
                Parent generation = (Parent) tabs.getTabs().get(3).getContent();
                ComboBox<?> engines = (ComboBox<?>) generation.lookup("#engineCombo");
                CheckBox accepted = (CheckBox) generation.lookup("#licenseAccepted");
                Button generate = (Button) generation.lookup("#generateButton");
                Button install = (Button) generation.lookup("#installButton");
                assertEquals(3, engines.getItems().size());
                assertTrue(generate.isDisabled());
                assertTrue(install.isDisabled());
                accepted.setSelected(true);
                assertFalse(generate.isDisabled());
                assertFalse(install.isDisabled());
                engines.getSelectionModel().select(1);
                assertFalse(accepted.isSelected());
                assertTrue(generate.isDisabled());
            } finally {
                injector.getInstance(TaskRunner.class).shutdown();
            }
            return null;
        });
        try {
            Platform.runLater(load);
            load.get(30, TimeUnit.SECONDS);
        } finally {
            Platform.exit();
        }
    }
}
