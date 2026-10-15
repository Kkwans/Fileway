package io.github.kkwans.nasfilebrowser.ui

import org.junit.Assert.*
import org.junit.Test

class FilePresentationGeometryTest {
    @Test fun wideBreadcrumbShowsEveryAncestor() {
        val plan = planBreadcrumbWidths(listOf(48, 64, 96, 120), 400, 12, 48, 48)
        assertEquals(0, plan.firstVisible)
        assertEquals(listOf(48, 64, 96, 120), plan.widths)
    }

    @Test fun breadcrumbCollapsesOnlyWhatDoesNotFit() {
        val plan = planBreadcrumbWidths(listOf(96, 64, 96, 120), 380, 12, 48, 48)
        assertEquals(1, plan.firstVisible)
        assertEquals(listOf(64, 96, 120), plan.widths)
    }

    @Test fun parentCannotDisplaceCurrentDirectory() {
        val plan = planBreadcrumbWidths(listOf(48, 180, 160), 280, 12, 48, 48)
        assertEquals(1, plan.firstVisible)
        assertEquals(listOf(48, 160), plan.widths)
        assertTrue(plan.widths.sum() + 2 * 12 + 48 <= 280)
    }

    @Test fun longCurrentStillLeavesItsParentVisible() {
        val plan = planBreadcrumbWidths(listOf(48, 180, 400), 280, 12, 48, 48)
        assertEquals(1, plan.firstVisible)
        assertEquals(listOf(48, 160), plan.widths)
    }

    @Test fun rootDoesNotInventAParent() {
        val plan = planBreadcrumbWidths(listOf(48), 320, 12, 48, 48)
        assertEquals(0, plan.firstVisible)
        assertEquals(listOf(48), plan.widths)
    }

    @Test fun narrowPathKeepsTwoActualLevels() {
        val plan = planBreadcrumbWidths(listOf(48, 180, 160), 160, 12, 48, 48)
        assertEquals(2, plan.widths.size)
        assertTrue(plan.widths.all { it > 0 })
        assertTrue(plan.widths.sum() + 2 * 12 + 48 <= 160)
    }

    @Test fun anchorFollowsIdentityAcrossReordering() {
        val anchor = CollectionAnchor.capture(listOf("a", "b", "c"), "b", 37)
        assertEquals(2 to 37, anchor.resolve(listOf("c", "a", "b")))
    }

    @Test fun deletedAnchorRestoresNearestSurvivingNeighbour() {
        val anchor = CollectionAnchor.capture(listOf("a", "b", "c", "d"), "b", 37)
        assertEquals(1 to 0, anchor.resolve(listOf("a", "c", "d")))
        assertEquals(0 to 0, anchor.resolve(listOf("a", "d")))
    }

    @Test fun completelyReplacedCollectionStartsAtBeginning() {
        val anchor = CollectionAnchor.capture(listOf("a", "b", "c"), "b", 37)
        assertEquals(0 to 0, anchor.resolve(listOf("x", "y")))
    }

    @Test fun extensionOrphanMovesSomeStemWithTheExtension() {
        val name = "横向构图.png"
        val split = extensionOrphanBreak(name, name.indexOf('.')) { it.length <= 6 }
        assertNotNull(split)
        assertEquals("横向构", name.substring(0, split!!))
        assertEquals("图.png", name.substring(split))
        assertEquals(name, name.substring(0, split) + name.substring(split))
    }

    @Test fun normalSecondLineDoesNotGetReflowed() {
        assertNull(extensionOrphanBreak("横向构图.png", 2) { true })
        assertNull(extensionOrphanBreak(".hidden", 0) { true })
        assertNull(extensionOrphanBreak("no-extension", 3) { true })
    }

    @Test fun extensionBreakNeverSplitsASurrogatePair() {
        val name = "AB\uD83D\uDE03.png"
        val split = extensionOrphanBreak(name, name.indexOf('.')) { it.codePointCount(0, it.length) <= 5 }
        assertEquals(2, split)
        assertEquals("\uD83D\uDE03.png", name.substring(split!!))
    }
}
