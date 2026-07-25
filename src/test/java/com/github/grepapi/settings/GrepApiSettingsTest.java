package com.github.grepapi.settings;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GrepApiSettingsTest {
    @Test
    void keepsTenMostRecentlyOpenedRoutesInReverseChronologicalOrder() {
        GrepApiSettings settings = new GrepApiSettings();

        for (int index = 1; index <= 11; index++) {
            settings.recordRecentRoute("route-" + index);
        }

        assertEquals(
                List.of(
                        "route-11", "route-10", "route-9", "route-8", "route-7",
                        "route-6", "route-5", "route-4", "route-3", "route-2"
                ),
                settings.getRecentRouteKeys()
        );

        settings.recordRecentRoute("route-5");

        assertEquals(
                List.of(
                        "route-5", "route-11", "route-10", "route-9", "route-8",
                        "route-7", "route-6", "route-4", "route-3", "route-2"
                ),
                settings.getRecentRouteKeys()
        );
    }

    @Test
    void limitsPreviouslySavedHistoryToTenRoutes() {
        GrepApiSettings settings = new GrepApiSettings();
        settings.recentRouteKeys = new ArrayList<>();
        for (int index = 1; index <= 15; index++) {
            settings.recentRouteKeys.add("route-" + index);
        }

        assertEquals(
                List.of(
                        "route-1", "route-2", "route-3", "route-4", "route-5",
                        "route-6", "route-7", "route-8", "route-9", "route-10"
                ),
                settings.getRecentRouteKeys()
        );
    }
}
