package dev.lemon.rex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/** Interactive device preview for [TabStackFan] over a draggable [FloatingTabBar]. */
class TabStackFanPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { RexTheme(true, ThemeCatalog.defaults[0]) { TabStackFanPreviewScreen() } }
    }
}

@Composable
private fun TabStackFanPreviewScreen() {
    val palette = remember {
        listOf(
                "codex",
                "claude",
                "fish",
                "nvim",
                "zsh",
                "python",
                "rust",
                "docker",
                "git",
                "node",
                "go",
                "postgres",
            )
            .map { ProgramIdentity.resolve(listOf(it)) }
    }
    val singleProgram = remember { listOf(ProgramIdentity.resolve(listOf("zsh"))) }
    var paneCount by remember { mutableIntStateOf(5) }
    var selectedPane by remember { mutableIntStateOf(0) }
    var activeTab by remember { mutableIntStateOf(1) }
    var fanned by remember { mutableStateOf(false) }
    var horizontal by remember { mutableStateOf(true) }
    var barPosition by remember { mutableStateOf(Offset(0.5f, 1f)) }
    var anchorBounds by remember { mutableStateOf(Rect.Zero) }
    var barBounds by remember { mutableStateOf(Rect.Zero) }

    val programs = remember(paneCount, palette) { palette.take(paneCount) }
    val fanItems =
        remember(programs, selectedPane) {
            programs.mapIndexed { index, program ->
                TabFanItem(
                    id = "pane-$index",
                    program = program,
                    title = program.label,
                    selected = index == selectedPane,
                )
            }
        }

    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(2, 3, 5, 6, 12).forEach { count ->
                FilterChip(
                    selected = paneCount == count,
                    onClick = {
                        paneCount = count
                        selectedPane = selectedPane.coerceIn(0, count - 1)
                        fanned = false
                    },
                    label = { Text("$count") },
                )
            }
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = horizontal,
                onClick = {
                    horizontal = !horizontal
                    barPosition = if (horizontal) Offset(0.5f, 1f) else Offset(0f, 0.5f)
                    fanned = false
                },
                label = { Text(if (horizontal) "Bar" else "Rail") },
            )
        }

        Box(Modifier.weight(1f).fillMaxSize()) {
            TabStackFan(
                visible = fanned && activeTab == 1,
                items = fanItems,
                anchorBounds = anchorBounds,
                barBounds = barBounds,
                preferredAxis = if (horizontal) Orientation.Horizontal else Orientation.Vertical,
                onSelect = { id ->
                    val index = id.removePrefix("pane-").toIntOrNull()
                    if (index != null) selectedPane = index
                    fanned = false
                },
                onDismiss = { fanned = false },
                bar = {
                    FloatingTabBar(
                        position = barPosition,
                        onPosition = {
                            barPosition = it
                            fanned = false
                        },
                        hideAtEdges = false,
                    ) { handle ->
                        val shape = RexTokens.shape.tabCell
                        if (horizontal) {
                            Row(
                                Modifier.width(FloatingTabBarWidth)
                                    .height(FloatingTabBarHeight)
                                    .shadow(3.dp, shape, clip = false)
                                    .clip(shape)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .onGloballyPositioned { barBounds = it.boundsInRoot() }
                                    .padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                handle()
                                Box(
                                    Modifier.height(44.dp)
                                        .width(56.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (activeTab == 0)
                                                MaterialTheme.colorScheme.surfaceVariant
                                            else androidx.compose.ui.graphics.Color.Transparent,
                                        )
                                        .clickable {
                                            activeTab = 0
                                            fanned = false
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    ProgramStack(singleProgram, 0, fixedSlot = true)
                                }
                                Box(
                                    Modifier.height(44.dp)
                                        .width(84.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (activeTab == 1)
                                                MaterialTheme.colorScheme.surfaceVariant
                                            else androidx.compose.ui.graphics.Color.Transparent,
                                        )
                                        .onGloballyPositioned { anchorBounds = it.boundsInRoot() }
                                        .clickable {
                                            if (activeTab == 1) fanned = !fanned
                                            else {
                                                activeTab = 1
                                                fanned = false
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    ProgramStack(programs, selectedPane, fixedSlot = true)
                                }
                            }
                        } else {
                            Column(
                                Modifier.width(48.dp)
                                    .shadow(3.dp, shape, clip = false)
                                    .clip(shape)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .onGloballyPositioned { barBounds = it.boundsInRoot() }
                                    .padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                handle()
                                Box(
                                    Modifier.size(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (activeTab == 0)
                                                MaterialTheme.colorScheme.surfaceVariant
                                            else androidx.compose.ui.graphics.Color.Transparent,
                                        )
                                        .clickable {
                                            activeTab = 0
                                            fanned = false
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    ProgramStack(singleProgram, 0, fixedSlot = true)
                                }
                                Box(
                                    Modifier.size(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (activeTab == 1)
                                                MaterialTheme.colorScheme.surfaceVariant
                                            else androidx.compose.ui.graphics.Color.Transparent,
                                        )
                                        .onGloballyPositioned { anchorBounds = it.boundsInRoot() }
                                        .clickable {
                                            if (activeTab == 1) fanned = !fanned
                                            else {
                                                activeTab = 1
                                                fanned = false
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    ProgramStack(programs, selectedPane, fixedSlot = true)
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}
