package io.github.kkwans.nasfilebrowser.ui.adaptive

import io.github.kkwans.nasfilebrowser.app.MediaKind
import io.github.kkwans.nasfilebrowser.data.DocumentPreviewKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilewayTabletAdaptiveTest {

    @Test
    fun windowLayoutBucketsMatchBreakpoints() {
        val phone = FilewayWindowLayout.from(412, 915)
        assertEquals(FilewayWindowClass.COMPACT, phone.windowClass)
        assertFalse(phone.useNavigationRail)
        assertFalse(phone.useDualPane)
        assertFalse(phone.useLargeDialogs)

        val compactEdge = FilewayWindowLayout.from(599, 800)
        assertEquals(FilewayWindowClass.COMPACT, compactEdge.windowClass)

        val tabletPortrait = FilewayWindowLayout.from(768, 1024)
        assertEquals(FilewayWindowClass.MEDIUM, tabletPortrait.windowClass)
        assertEquals(FilewayHeightClass.EXPANDED, tabletPortrait.heightClass)
        assertTrue(tabletPortrait.useNavigationRail)
        assertFalse(tabletPortrait.useDualPane)
        assertTrue(tabletPortrait.useLargeDialogs)

        val mediumEdge = FilewayWindowLayout.from(839, 700)
        assertEquals(FilewayWindowClass.MEDIUM, mediumEdge.windowClass)

        val tabletLandscape = FilewayWindowLayout.from(1120, 720)
        assertEquals(FilewayWindowClass.EXPANDED, tabletLandscape.windowClass)
        assertTrue(tabletLandscape.useNavigationRail)
        assertTrue(tabletLandscape.useDualPane)
        assertFalse(tabletLandscape.usePermanentDrawerDefault)

        val largeTablet = FilewayWindowLayout.from(1280, 800)
        assertEquals(FilewayWindowClass.LARGE, largeTablet.windowClass)
        assertTrue(largeTablet.useNavigationRail)
        assertTrue(largeTablet.useDualPane)
        assertTrue(largeTablet.usePermanentDrawerDefault)
    }

    @Test
    fun windowLayoutRejectsOutOfRangeHingeFractions() {
        assertNull(FilewayWindowLayout.from(900, 800, hingeCenterFraction = 0.05f).hingeCenterFraction)
        assertNull(FilewayWindowLayout.from(900, 800, hingeCenterFraction = 0.95f).hingeCenterFraction)
        assertEquals(0.5f, FilewayWindowLayout.from(900, 800, hingeCenterFraction = 0.5f).hingeCenterFraction!!, 0.0001f)
    }

    @Test
    fun splitPaneDegradesGracefullyWhenTooNarrow() {
        val spec = SplitPaneSpec(defaultRatio = 0.38f, minLeftDp = 300f, minRightDp = 360f, dividerDp = 12f)
        val narrow = SplitPaneMath.computeLayout(
            totalWidthDp = 640f,
            ratio = 0.38f,
            leftCollapsed = false,
            detailFullscreen = false,
            spec = spec,
        )
        assertFalse(narrow.canSplit)
        assertEquals(640f, narrow.leftWidthDp, 0.01f)
        assertEquals(0f, narrow.rightWidthDp, 0.01f)
    }

    @Test
    fun splitPaneRespectsMinBoundsAndFullscreenAndCollapse() {
        val spec = SplitPaneSpec(defaultRatio = 0.38f, minLeftDp = 300f, minRightDp = 360f, dividerDp = 12f)

        val normal = SplitPaneMath.computeLayout(1000f, 0.38f, leftCollapsed = false, detailFullscreen = false, spec = spec)
        assertTrue(normal.canSplit)
        assertEquals(1000f, normal.leftWidthDp + normal.dividerWidthDp + normal.rightWidthDp, 0.01f)
        assertTrue(normal.leftWidthDp >= 300f)
        assertTrue(normal.rightWidthDp >= 360f)

        val clampedLeft = SplitPaneMath.computeLayout(1000f, 0.05f, leftCollapsed = false, detailFullscreen = false, spec = spec)
        assertEquals(300f, clampedLeft.leftWidthDp, 0.01f)
        assertEquals(688f, clampedLeft.rightWidthDp, 0.01f)

        val clampedRight = SplitPaneMath.computeLayout(1000f, 0.95f, leftCollapsed = false, detailFullscreen = false, spec = spec)
        assertEquals(360f, clampedRight.rightWidthDp, 0.01f)
        assertEquals(628f, clampedRight.leftWidthDp, 0.01f)

        val fullscreen = SplitPaneMath.computeLayout(1000f, 0.38f, leftCollapsed = false, detailFullscreen = true, spec = spec)
        assertTrue(fullscreen.canSplit)
        assertEquals(0f, fullscreen.leftWidthDp, 0.01f)
        assertEquals(1000f, fullscreen.rightWidthDp, 0.01f)

        val collapsed = SplitPaneMath.computeLayout(1000f, 0.38f, leftCollapsed = true, detailFullscreen = false, spec = spec)
        assertTrue(collapsed.canSplit)
        assertEquals(0f, collapsed.leftWidthDp, 0.01f)
        assertEquals(1000f, collapsed.rightWidthDp, 0.01f)
    }

    @Test
    fun splitPaneDragDeltaSnapsAndCollapsesCleanly() {
        val spec = SplitPaneSpec(defaultRatio = 0.38f, minLeftDp = 300f, minRightDp = 360f, collapseLeftDp = 160f, dividerDp = 12f)
        // Drag near 0.50f anchor on a 1012dp container (usable = 1000dp)
        val snapStep = SplitPaneMath.applyDragDelta(
            currentRatio = 0.45f,
            rawLeftWidthDp = 490f,
            deltaDp = 12f,
            totalWidthDp = 1012f,
            spec = spec,
        )
        assertFalse(snapStep.collapsed)
        assertEquals(0.50f, snapStep.snappedAnchor!!, 0.001f)

        // Drag past collapseLeftDp (160dp) triggers left collapse
        val collapseStep = SplitPaneMath.applyDragDelta(
            currentRatio = 0.30f,
            rawLeftWidthDp = 175f,
            deltaDp = -30f,
            totalWidthDp = 1012f,
            spec = spec,
        )
        assertTrue(collapseStep.collapsed)
    }

    @Test
    fun workspaceSingleTapPolicyRoutesEachFileTypeCorrectly() {
        assertEquals(
            TabletClickAction.ENTER_DIRECTORY,
            TabletWorkspacePolicy.actionForSingleTap("Movies", directory = true, type = "dir", mediaKind = null, documentKind = DocumentPreviewKind.OTHER, isArchive = false),
        )
        assertEquals(
            TabletClickAction.STAGE_VIDEO,
            TabletWorkspacePolicy.actionForSingleTap("demo.mkv", directory = false, type = "video", mediaKind = MediaKind.VIDEO, documentKind = DocumentPreviewKind.OTHER, isArchive = false),
        )
        assertEquals(
            TabletClickAction.OPEN_EMBEDDED,
            TabletWorkspacePolicy.actionForSingleTap("photo.png", directory = false, type = "image", mediaKind = MediaKind.IMAGE, documentKind = DocumentPreviewKind.OTHER, isArchive = false),
        )
        assertEquals(
            TabletClickAction.OPEN_EMBEDDED,
            TabletWorkspacePolicy.actionForSingleTap("track.flac", directory = false, type = "audio", mediaKind = MediaKind.AUDIO, documentKind = DocumentPreviewKind.OTHER, isArchive = false),
        )
        assertEquals(
            TabletClickAction.OPEN_EMBEDDED,
            TabletWorkspacePolicy.actionForSingleTap("README.md", directory = false, type = "text", mediaKind = null, documentKind = DocumentPreviewKind.TEXT, isArchive = false),
        )
        assertEquals(
            TabletClickAction.OPEN_EMBEDDED,
            TabletWorkspacePolicy.actionForSingleTap("spec.pdf", directory = false, type = "pdf", mediaKind = null, documentKind = DocumentPreviewKind.PDF, isArchive = false),
        )
        assertEquals(
            TabletClickAction.OPEN_EMBEDDED,
            TabletWorkspacePolicy.actionForSingleTap("release.zip", directory = false, type = "blob", mediaKind = null, documentKind = DocumentPreviewKind.OTHER, isArchive = true),
        )
        assertEquals(
            TabletClickAction.INSPECT_FILE,
            TabletWorkspacePolicy.actionForSingleTap("firmware.bin", directory = false, type = "blob", mediaKind = null, documentKind = DocumentPreviewKind.OTHER, isArchive = false),
        )
    }

    @Test
    fun tabletLayoutConfigCustomizationsResolveAccurately() {
        val expanded = FilewayWindowLayout.from(1024, 768)
        val large = FilewayWindowLayout.from(1280, 800)
        val medium = FilewayWindowLayout.from(720, 1080)

        // Split mode: AUTO vs ALWAYS_SINGLE
        val autoCfg = TabletLayoutConfig(splitMode = TabletSplitMode.AUTO)
        assertTrue(autoCfg.useWorkspaceDualPane(expanded))
        assertTrue(autoCfg.useWorkspaceDualPane(large))
        assertFalse(autoCfg.useWorkspaceDualPane(medium))

        val singleCfg = TabletLayoutConfig(splitMode = TabletSplitMode.ALWAYS_SINGLE)
        assertFalse(singleCfg.useWorkspaceDualPane(expanded))
        assertFalse(singleCfg.useWorkspaceDualPane(large))

        // Sidebar style: AUTO vs EXPANDED vs RAIL
        assertFalse(TabletLayoutConfig(sidebarStyle = TabletSidebarStyle.AUTO).canToggleExpandedSidebar(expanded))
        assertTrue(TabletLayoutConfig(sidebarStyle = TabletSidebarStyle.AUTO).canToggleExpandedSidebar(large))
        assertTrue(TabletLayoutConfig(sidebarStyle = TabletSidebarStyle.EXPANDED).canToggleExpandedSidebar(expanded))
        assertTrue(TabletLayoutConfig(sidebarStyle = TabletSidebarStyle.EXPANDED).defaultSidebarExpanded(expanded))
        assertFalse(TabletLayoutConfig(sidebarStyle = TabletSidebarStyle.RAIL).canToggleExpandedSidebar(large))
        assertFalse(TabletLayoutConfig(sidebarStyle = TabletSidebarStyle.RAIL).defaultSidebarExpanded(large))

        // Video tap behavior overrides only STAGE_VIDEO
        assertEquals(
            TabletClickAction.STAGE_VIDEO,
            TabletLayoutConfig(videoTapBehavior = TabletVideoTapBehavior.STAGE_FIRST).resolveClickAction(TabletClickAction.STAGE_VIDEO),
        )
        assertEquals(
            TabletClickAction.OPEN_EMBEDDED,
            TabletLayoutConfig(videoTapBehavior = TabletVideoTapBehavior.PLAY_EMBEDDED).resolveClickAction(TabletClickAction.STAGE_VIDEO),
        )
        assertEquals(
            TabletClickAction.OPEN_FULLSCREEN,
            TabletLayoutConfig(videoTapBehavior = TabletVideoTapBehavior.PLAY_FULLSCREEN).resolveClickAction(TabletClickAction.STAGE_VIDEO),
        )
        assertEquals(
            TabletClickAction.ENTER_DIRECTORY,
            TabletLayoutConfig(videoTapBehavior = TabletVideoTapBehavior.PLAY_FULLSCREEN).resolveClickAction(TabletClickAction.ENTER_DIRECTORY),
        )

        // Split presets
        assertEquals(0.32f, TabletLayoutConfig(splitPreset = TabletSplitPreset.COMPACT_LIST).primaryWorkspaceDefaultRatio(), 0.001f)
        assertEquals(0.58f, TabletLayoutConfig(splitPreset = TabletSplitPreset.WIDE_LIST).primaryWorkspaceDefaultRatio(), 0.001f)
    }

    @Test
    fun splitPaneDragRespectsDisableCollapseAndDisableSnapFlags() {
        val spec = SplitPaneSpec(defaultRatio = 0.38f, minLeftDp = 300f, minRightDp = 360f, collapseLeftDp = 160f, dividerDp = 12f)
        val noCollapse = SplitPaneMath.applyDragDelta(
            currentRatio = 0.30f,
            rawLeftWidthDp = 175f,
            deltaDp = -30f,
            totalWidthDp = 1012f,
            spec = spec,
            allowCollapse = false,
            magneticSnap = true,
        )
        assertFalse(noCollapse.collapsed)
        assertEquals(300f / 1000f, noCollapse.ratio, 0.001f)

        val noSnap = SplitPaneMath.applyDragDelta(
            currentRatio = 0.45f,
            rawLeftWidthDp = 490f,
            deltaDp = 12f,
            totalWidthDp = 1012f,
            spec = spec,
            allowCollapse = true,
            magneticSnap = false,
        )
        assertNull(noSnap.snappedAnchor)
        assertEquals(0.502f, noSnap.ratio, 0.001f)
    }

    @Test
    fun phoneLandscapeNeverTriggersTabletDualPaneOrRail() {
        val xiaomiLandscape = FilewayWindowLayout.from(915, 412, smallestScreenWidthDp = 412)
        assertEquals(FilewayWindowClass.COMPACT, xiaomiLandscape.windowClass)
        assertEquals(FilewayHeightClass.COMPACT, xiaomiLandscape.heightClass)
        assertFalse(xiaomiLandscape.useNavigationRail)
        assertFalse(xiaomiLandscape.useDualPane)
        assertFalse(xiaomiLandscape.useLargeDialogs)
        assertFalse(xiaomiLandscape.usePermanentDrawerDefault)

        val compactPhoneLandscape = FilewayWindowLayout.from(890, 390)
        assertEquals(FilewayWindowClass.COMPACT, compactPhoneLandscape.windowClass)
        assertFalse(compactPhoneLandscape.useNavigationRail)
        assertFalse(compactPhoneLandscape.useDualPane)

        val shortNarrowLandscape = FilewayWindowLayout.from(860, 500, smallestScreenWidthDp = 420)
        assertEquals(FilewayWindowClass.COMPACT, shortNarrowLandscape.windowClass)
        assertFalse(shortNarrowLandscape.useDualPane)

        val realTabletLandscape = FilewayWindowLayout.from(1024, 640, smallestScreenWidthDp = 640)
        assertEquals(FilewayWindowClass.EXPANDED, realTabletLandscape.windowClass)
        assertTrue(realTabletLandscape.useNavigationRail)
        assertTrue(realTabletLandscape.useDualPane)

        val largeTablet = FilewayWindowLayout.from(1280, 800, smallestScreenWidthDp = 800)
        assertEquals(FilewayWindowClass.LARGE, largeTablet.windowClass)
        assertTrue(largeTablet.useDualPane)
        assertTrue(largeTablet.usePermanentDrawerDefault)

        val tabletPortrait = FilewayWindowLayout.from(720, 1100, smallestScreenWidthDp = 720)
        assertEquals(FilewayWindowClass.MEDIUM, tabletPortrait.windowClass)
        assertTrue(tabletPortrait.useNavigationRail)
        assertFalse(tabletPortrait.useDualPane)
    }
}
