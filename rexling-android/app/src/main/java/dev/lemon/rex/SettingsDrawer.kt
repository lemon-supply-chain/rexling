package dev.lemon.rex

import androidx.compose.foundation.background
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState

/** Shared drawer chrome; scrolling stays in the content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SettingsDrawer(title: String,closeDescription: String,onClose: ()->Unit,
    hazeState: HazeState?=null,onBack: (() -> Unit)?=null,swipeToDismiss: Boolean=true,backDescription: String=stringResource(R.string.back_to_appearance),
    subtitle: String?=null,surfaceColor: Color=Color.Transparent,headerActions: @Composable RowScope.()->Unit={},content: @Composable ColumnScope.()->Unit) {
    val localHaze=remember {HazeState()}
    val shape=BottomSheetDefaults.ExpandedShape
    // Built outside the sheet on purpose: drawerFrost binds to the host window's ViewTreeObserver to
    // capture backdrop blur on open, disarming once captured so host redraws do not loop.
    val frost=if(swipeToDismiss) Modifier.drawerFrost(hazeState,shape)
        else Modifier.paneFrost(hazeState ?: localHaze,shape,edge=false,opacity=RexTokens.frost.drawerOpacity,blur=RexTokens.frost.drawerBlur)
    val surface=if(surfaceColor==Color.Transparent) frost else Modifier.background(surfaceColor,shape).then(frost)
    @Composable fun Body(modifier: Modifier=Modifier) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(modifier.then(surface).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                DrawerHandle()
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    onBack?.let {ChromeButton(ChromeIcon.LEFT,backDescription,onClick=it)}
                    Column(Modifier.weight(1f)) {
                        if(subtitle==null) Text(title,style=SettingsType.row,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onSurface)
                        else {
                            Text(title,style=SettingsType.row,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onSurface,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(subtitle,style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                    }
                    headerActions()
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
            dragHandle=null,contentWindowInsets={WindowInsets.safeDrawing.only(WindowInsetsSides.Top)}) {Body()}
    }
}
