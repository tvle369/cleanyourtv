package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * AppAdapter.resolvedItemCount: hide_app_grid caps the adapter at the dock's own width, never a grid tile.
 *
 * rows_below_dock only ever changed padding - the full grid was always created and laid out underneath it,
 * so a tile row could still peek into the small bottom margin it reserved (MainActivity.java's own comment
 * on the fix that was reverted). hide_app_grid removes the content at the source instead: the adapter is
 * never asked for a position past the dock, so no padding or scroll math can leak one into view.
 */
public class HideAppGridTest {

    @Test
    public void falseKeepsTheFullCountPlusLibrary_dockRowTrue() {
        // Default (hide_app_grid off): every existing theme's current behavior is untouched.
        assertEquals(40, AppAdapter.resolvedItemCount(40, 6, 2, true, false, false));
        assertEquals(41, AppAdapter.resolvedItemCount(40, 6, 2, true, false, true));
    }

    @Test
    public void falseKeepsTheFullCountPlusLibrary_dockRowFalse() {
        assertEquals(40, AppAdapter.resolvedItemCount(40, 6, 2, false, false, false));
        assertEquals(41, AppAdapter.resolvedItemCount(40, 6, 2, false, false, true));
    }

    @Test
    public void trueCapsAtTheDockWidthWithABandedDock() {
        // dock_row true (trac-cinematic's own default): the row is dockSize wide, docked apps plus fill_dock's
        // empty slots alike, so the cap is dockSize regardless of how many of those slots hold a real app.
        assertEquals(6, AppAdapter.resolvedItemCount(200, 6, 2, true, true, false));
        // fill_dock true and enough apps to fill every slot: still exactly dockSize, never more.
        assertEquals(6, AppAdapter.resolvedItemCount(200, 6, 6, true, true, false));
    }

    @Test
    public void trueCapsAtTheDockedCountWithoutABand() {
        // dock_row false: the dock apps are the first tiles of one plain grid, with no reserved empty slots,
        // so the real boundary is `docked`, not the column count - capping at dockSize here would let
        // (dockSize - docked) grid tiles leak through before the cap ever applied.
        assertEquals(2, AppAdapter.resolvedItemCount(200, 6, 2, false, true, false));
    }

    @Test
    public void trueHandlesFewerInstalledOrDockedAppsSafely() {
        // Fewer resolved apps than the dock has slots for (a near-empty TV, or a theme folder with no
        // installed streaming apps yet): the cap must never exceed what is actually there, and never go
        // negative, however docked and dockSize are configured.
        assertEquals(3, AppAdapter.resolvedItemCount(3, 6, 2, true, true, false));   // itemsSize < dockSize
        assertEquals(0, AppAdapter.resolvedItemCount(0, 6, 0, true, true, false));   // nothing resolved at all
        assertEquals(0, AppAdapter.resolvedItemCount(5, 6, 0, false, true, false));  // dock_row false, no docked apps
    }

    @Test
    public void libraryItemIsExcludedWhenTrueEvenIfShowLibraryIsOn() {
        // hide_app_grid removes the "+1" library row along with the grid: there is nothing below the dock to
        // browse into, so the App library pill's extra item must not appear either.
        assertEquals(6, AppAdapter.resolvedItemCount(200, 6, 2, true, true, true));
        assertEquals(2, AppAdapter.resolvedItemCount(200, 6, 2, false, true, true));
    }
}
