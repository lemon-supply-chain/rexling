package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class ToolbarLayoutTest {
    @Test
    fun oldInstallKeepsItsToolbarAndEmptyIsExplicit() {
        assertEquals(7, ToolbarLayout.decode(null).size)
        assertEquals(ToolbarLayout.defaults, ToolbarLayout.decode(null))
        assertEquals(emptyList<ToolbarKey>(), ToolbarLayout.decode(""))
        assertEquals(ToolbarLayout.defaults, ToolbarLayout.decode("unknown"))
    }

    @Test
    fun persistenceFiltersUnknownDuplicateAndExcessKeys() {
        val all = ToolbarKey.entries
        assertEquals(
            all.take(7),
            ToolbarLayout.decode(all.joinToString(",") { it.id } + ",escape,unknown"),
        )
        val custom = listOf(ToolbarKey.PASTE, ToolbarKey.ENTER, ToolbarKey.CTRL_C)
        assertEquals(custom, ToolbarLayout.decode(ToolbarLayout.encode(custom)))
        assertEquals(listOf(ToolbarKey.PASTE), ToolbarLayout.decode("paste,unknown,paste"))
    }

    @Test
    fun replacingWithAnExistingKeySwapsWithoutLosingAnotherKey() {
        val before = listOf(ToolbarKey.ESCAPE, ToolbarKey.TAB, ToolbarKey.ENTER)
        assertEquals(
            listOf(ToolbarKey.ENTER, ToolbarKey.TAB, ToolbarKey.ESCAPE),
            ToolbarLayout.assign(before, 0, ToolbarKey.ENTER),
        )
        assertEquals(
            listOf(ToolbarKey.ESCAPE, ToolbarKey.PASTE, ToolbarKey.ENTER),
            ToolbarLayout.assign(before, 1, ToolbarKey.PASTE),
        )
        assertEquals(before, ToolbarLayout.assign(before, 3, ToolbarKey.ESCAPE))
        assertEquals(
            ToolbarLayout.defaults,
            ToolbarLayout.assign(ToolbarLayout.defaults, 7, ToolbarKey.PASTE),
        )
    }

    @Test
    fun reorderKeepsEveryKeyAndCannotCrossEdges() {
        val before = listOf(ToolbarKey.ESCAPE, ToolbarKey.PASTE, ToolbarKey.ENTER)
        assertEquals(
            listOf(ToolbarKey.PASTE, ToolbarKey.ESCAPE, ToolbarKey.ENTER),
            ToolbarLayout.move(before, 1, -1),
        )
        assertEquals(before, ToolbarLayout.move(before, 0, -1))
        assertEquals(before, ToolbarLayout.move(before, 2, 1))
    }

    @Test
    fun draggingMovesInsertsOrReplacesWithoutExceedingTheLimit() {
        assertEquals(
            listOf(ToolbarKey.CONTROL, ToolbarKey.ALT, ToolbarKey.ESCAPE) +
                ToolbarLayout.defaults.drop(3),
            ToolbarLayout.drop(ToolbarLayout.defaults, ToolbarKey.ESCAPE, 2),
        )
        val short = listOf(ToolbarKey.ESCAPE, ToolbarKey.ENTER)
        assertEquals(
            listOf(ToolbarKey.ESCAPE, ToolbarKey.ENTER, ToolbarKey.PASTE),
            ToolbarLayout.drop(short, ToolbarKey.PASTE, 2),
        )
        val replaced = ToolbarLayout.drop(ToolbarLayout.defaults, ToolbarKey.PASTE, 4)
        assertEquals(7, replaced.size)
        assertEquals(ToolbarKey.PASTE, replaced[4])
        assertFalse(ToolbarKey.TAB in replaced)
    }
}
