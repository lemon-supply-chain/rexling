package dev.lemon.rex

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState

/** Shared chrome for secondary settings drawers; scrolling stays in their content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SettingsDrawer(title: String,closeDescription: String,onClose: ()->Unit,
    hazeState: HazeState?=null,onBack: (() -> Unit)?=null,swipeToDismiss: Boolean=true,content: @Composable ColumnScope.()->Unit) {
    val localHaze=remember {HazeState()}
    @Composable fun Body(modifier: Modifier=Modifier) {
        val frost=if(swipeToDismiss) Modifier.drawerFrost(hazeState,BottomSheetDefaults.ExpandedShape)
            else Modifier.paneFrost(hazeState ?: localHaze,BottomSheetDefaults.ExpandedShape,edge=false,opacity=.72f,blur=24.dp)
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(modifier.then(frost).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                DrawerHandle()
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    onBack?.let {ChromeButton(ChromeIcon.LEFT,"Back to appearance",onClick=it)}
                    Text(title,Modifier.weight(1f),style=SettingsType.row,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onSurface)
                    ChromeButton(ChromeIcon.CLOSE,closeDescription,onClick=onClose)
                }
                content()
            }
        }
    }
    if(!swipeToDismiss) {
        // Editors with drag-and-drop must not compete with a sheet's draggable
        // or nested-scroll dismiss handlers. Retain the same drawer chrome in a
        // fixed bottom dialog; the palette owns its normal scrolling/flinging.
        Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
                Box(Modifier.matchParentSize().clickable(interactionSource=remember {MutableInteractionSource()},indication=null,onClick=onClose))
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    Body(Modifier.align(Alignment.BottomCenter).widthIn(max=BottomSheetDefaults.SheetMaxWidth).pointerInput(Unit) {detectTapGestures(onTap={})})
                }
            }
        }
    } else {
        ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
            containerColor=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface,tonalElevation=0.dp,
            dragHandle=null,contentWindowInsets={WindowInsets(0,0,0,0)}) {Body()}
    }
}
