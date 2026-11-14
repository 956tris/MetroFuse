package com.metrolist.shared.settings

import com.metrolist.shared.ui.navigation.Screen
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationTabPreferencesTest {
    @Test
    fun legacyDesktopSidebarAddsPluginsAfterTogetherAndKeepsCustomization() {
        val settings = Settings(MapSettings())
        settings.sidebarNavItems = "history:false,together:true,home:true,library:false"

        assertEquals(
            listOf(
                NavItem(NavigationId.HISTORY, false),
                NavItem(NavigationId.LISTEN_TOGETHER),
                NavItem(NavigationId.PLUGINS),
                NavItem(NavigationId.HOME),
                NavItem(NavigationId.LIBRARY, false),
            ),
            settings.desktopSidebarNavItems(),
        )
    }

    @Test
    fun savedPluginsPositionAndVisibilitySurviveSidebarReload() {
        val backing = MapSettings()
        val settings = Settings(backing)
        settings.sidebarNavItems = "plugins:false,home:true,together:false"

        assertEquals(
            listOf(
                NavItem(NavigationId.PLUGINS, false),
                NavItem(NavigationId.HOME),
                NavItem(NavigationId.LISTEN_TOGETHER, false),
            ),
            Settings(backing).desktopSidebarNavItems(),
        )
    }

    @Test
    fun floatingNavigationBarIsOffByDefault() {
        assertFalse(Settings(MapSettings()).floatingNavBar)
    }

    @Test
    fun floatingTopBarIsOffByDefault() {
        assertFalse(Settings(MapSettings()).floatingTopBar)
    }

    @Test
    fun launchSelectionPersistsAndResetsThePreviousLibraryFilter() {
        val backing = MapSettings()
        val settings = Settings(backing)
        assertEquals("HOME", settings.defaultOpenTab)
        assertEquals("", settings.musicFrontendOwner)
        settings.libraryFilter = LibraryFilterType.ALBUMS
        settings.updateDefaultOpenTab("TRACKS")
        val restored = Settings(backing)
        assertEquals("TRACKS", restored.defaultOpenTab)
        assertEquals(LibraryFilterType.TRACKS, restored.initialLibraryFilter())
        restored.libraryFilter = LibraryFilterType.PLAYLISTS
        restored.updateDefaultOpenTab("FAVORITES")
        assertEquals(LibraryFilterType.DEFAULT, restored.libraryFilter)
        assertEquals("LIBRARY", defaultOpenTabRoot(restored.defaultOpenTab))
        assertEquals(Screen.AutoPlaylist("liked"), defaultOpenTabScreen(restored.defaultOpenTab))
        restored.updateDefaultOpenTab("LIBRARY")
        assertEquals(LibraryFilterType.ALL, restored.initialLibraryFilter())
    }

    @Test
    fun hiddenLibraryRemovesItsLaunchDestinationsOnBothPlatforms() {
        val settings = Settings(MapSettings())
        for (tab in listOf("LIBRARY", "SONGS", "TRACKS", "FAVORITES", "ARTISTS", "ALBUMS", "PLAYLISTS")) {
            assertEquals(if (tab == "SONGS") "LIBRARY" else tab, normalizeDefaultOpenTab(tab, true, true, true))
            assertEquals("HOME", normalizeDefaultOpenTab(tab, true, true, false))
            assertEquals("SEARCH", normalizeDefaultOpenTab(tab, false, true, false))
            assertEquals("MYSTERIOUS", normalizeDefaultOpenTab(tab, false, false, false))
            settings.defaultOpenTab = tab
            settings.sidebarNavItems = "${NavigationId.HOME}:false,${NavigationId.LIBRARY}:true"
            assertEquals(if (tab == "SONGS") "LIBRARY" else tab, settings.desktopDefaultOpenTab())
            settings.sidebarNavItems = "${NavigationId.HOME}:false,${NavigationId.LIBRARY}:false"
            assertEquals(listOf("SEARCH"), settings.desktopDefaultOpenTabs())
            assertEquals("SEARCH", settings.desktopDefaultOpenTab())
        }
        settings.sidebarNavItems = "${NavigationId.HOME}:true,${NavigationId.LIBRARY}:true"
        assertTrue("FAVORITES" in settings.desktopDefaultOpenTabs())
        assertFalse("SONGS" in settings.desktopDefaultOpenTabs())
    }

    @Test
    fun savedLibraryFilterIsRetainedUntilANewLaunchResetsIt() {
        val settings = Settings(MapSettings())
        settings.updateDefaultOpenTab("TRACKS")
        settings.libraryFilter = LibraryFilterType.ALBUMS
        assertEquals(LibraryFilterType.ALBUMS, settings.initialLibraryFilter())
        settings.libraryFilter = LibraryFilterType.DEFAULT
        assertEquals(LibraryFilterType.TRACKS, settings.initialLibraryFilter())
    }
}
