package dev.shulhi.rex

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

internal val controlShortcuts=listOf("c","d","z","r","l","a","e","w","j","]")
internal enum class ControlShortcutGesture { HOLD, DOUBLE_TAP }
private const val CONTROL_SHORTCUT_COLUMNS=3
private val controlShortcutRows=(controlShortcuts.size+CONTROL_SHORTCUT_COLUMNS-1)/CONTROL_SHORTCUT_COLUMNS

@Composable internal fun ControlDockKey(enabled: Boolean,active: Boolean,readable: Boolean,modifier: Modifier,
    onToggle: ()->Unit,onShortcut: (String)->Unit,gesture: ControlShortcutGesture=ControlShortcutGesture.HOLD) {
    var visible by remember {mutableStateOf(false)}
    var pressed by remember {mutableStateOf(false)}
    var highlighted by remember {mutableIntStateOf(-1)}
    var anchor by remember {mutableStateOf(Rect.Zero)}
    var screenAnchor by remember {mutableStateOf(Rect.Zero)}
    var popup by remember {mutableStateOf(IntRect.Zero)}
    val scope=rememberCoroutineScope()
    val toggle by rememberUpdatedState(onToggle)
    val send by rememberUpdatedState(onShortcut)
    val latched by rememberUpdatedState(active)
    val density=LocalDensity.current
    val padding=with(density) {8.dp.toPx()}
    val gap=with(density) {4.dp.toPx()}
    val configuration=LocalViewConfiguration.current
    val haptics=LocalHapticFeedback.current
    fun dismiss() {visible=false;highlighted=-1}
    fun closeFromTrigger() {dismiss();if(latched) toggle()}
    LaunchedEffect(enabled,gesture) {dismiss();pressed=false}
    fun choose(index: Int) {if(enabled) send(controlShortcuts[index]);dismiss()}
    fun hit(x: Float,y: Float): Int {
        val px=x-popup.left-padding;val py=y-popup.top-padding
        val w=(popup.width-2*padding-(CONTROL_SHORTCUT_COLUMNS-1)*gap)/CONTROL_SHORTCUT_COLUMNS
        val h=(popup.height-2*padding-(controlShortcutRows-1)*gap)/controlShortcutRows
        if(w<=0 || h<=0 || px<0 || py<0) return -1
        val col=(px/(w+gap)).toInt();val row=(py/(h+gap)).toInt()
        val index=row*CONTROL_SHORTCUT_COLUMNS+col
        return if(col in 0 until CONTROL_SHORTCUT_COLUMNS && row in 0 until controlShortcutRows && index in controlShortcuts.indices && px-col*(w+gap)<w && py-row*(h+gap)<h) index else -1
    }
    BackHandler(visible) {dismiss()}
    val provider=rememberSpeechBubblePositioner {popup=it}
    val selected=active || pressed || visible
    Box(modifier.fillMaxHeight().onGloballyPositioned {
        anchor=it.boundsInWindow()
        screenAnchor=Rect(it.localToScreen(Offset.Zero),androidx.compose.ui.geometry.Size(it.size.width.toFloat(),it.size.height.toFloat()))
    }
        .clip(RoundedCornerShape(11.dp)).background(if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
        .semantics {
            contentDescription="Control";this.selected=active;role=Role.Button
            if(enabled) {onClick {if(visible) closeFromTrigger() else toggle();true};onLongClick(label="Control shortcuts") {visible=true;true}} else disabled()
        }.pointerInput(enabled,gesture) {
            if(!enabled) return@pointerInput
            var lastTap: Long?=null
            awaitEachGesture {
                val down=awaitFirstDown();down.consume();pressed=true
                // Trigger taps bypass outside dismissal, so the release can close and clear Ctrl.
                val closingPopup=visible
                val doubleTap=gesture==ControlShortcutGesture.DOUBLE_TAP && lastTap?.let {
                    down.uptimeMillis-it in configuration.doubleTapMinTimeMillis..configuration.doubleTapTimeoutMillis
                }==true
                lastTap=null
                var opened=false;var released=false;var moved=false
                val timer=if(!closingPopup && gesture==ControlShortcutGesture.HOLD) scope.launch {delay(configuration.longPressTimeoutMillis);opened=true;visible=true;haptics.performHapticFeedback(HapticFeedbackType.LongPress)} else null
                try {
                    while(true) {
                        val change=awaitPointerEvent().changes.firstOrNull {it.id==down.id} ?: break
                        if(change.isConsumed) break
                        change.consume()
                        if(abs(change.position.x-down.position.x)>configuration.touchSlop || abs(change.position.y-down.position.y)>configuration.touchSlop) {moved=true;if(!opened) timer?.cancel()}
                        if(opened && visible) highlighted=hit(change.position.x+anchor.left,change.position.y+anchor.top)
                        if(!change.pressed) {
                            released=true;timer?.cancel()
                            if(closingPopup) {closeFromTrigger();lastTap=null}
                            else if(opened) {if(visible && highlighted>=0) choose(highlighted)}
                            else if(!moved && change.position.x in 0f..size.width.toFloat() && change.position.y in 0f..size.height.toFloat()) {
                                // Each tap toggles immediately; the second restores the first toggle.
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                toggle()
                                if(doubleTap && change.uptimeMillis-down.uptimeMillis<configuration.longPressTimeoutMillis) {
                                    visible=true;haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                } else {
                                    if(gesture==ControlShortcutGesture.DOUBLE_TAP && change.uptimeMillis-down.uptimeMillis<configuration.longPressTimeoutMillis) lastTap=change.uptimeMillis
                                }
                            }
                            break
                        }
                    }
                } finally {timer?.cancel();pressed=false;if(!released || opened) dismiss()}
            }
        },contentAlignment=Alignment.Center) {
        ChromeSymbol(ChromeIcon.CONTROL,Modifier.size(20.dp),
            (if(selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface).copy(alpha=if(readable) 1f else .38f))
        if(visible) Popup(popupPositionProvider=provider,onDismissRequest={dismiss()},
            properties=PopupProperties(focusable=false,dismissOnClickOutside=true)) {
            val popupView=LocalView.current.rootView
            val triggerBounds by rememberUpdatedState(screenAnchor)
            DisposableEffect(popupView) {
                // ACTION_OUTSIDE arrives before the underlying trigger's DOWN. Keep this
                // popup open only for that trigger; its pointer handler owns the close.
                popupView.setOnTouchListener {_,event->
                    event.actionMasked==MotionEvent.ACTION_OUTSIDE && triggerBounds.contains(Offset(event.rawX,event.rawY))
                }
                onDispose {popupView.setOnTouchListener(null)}
            }
            SpeechBubble(provider,Modifier.width(244.dp).semantics {contentDescription="Control shortcuts"},shadowElevation=0.dp) {
            Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                repeat(controlShortcutRows) {row->
                    Row(Modifier.fillMaxWidth().height(44.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        repeat(CONTROL_SHORTCUT_COLUMNS) {col->
                            val index=row*CONTROL_SHORTCUT_COLUMNS+col;val letter=controlShortcuts.getOrNull(index);val current=highlighted==index
                            if(letter==null) Spacer(Modifier.weight(1f).fillMaxHeight())
                            else Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(11.dp))
                                .background(if(current) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                                .semantics {contentDescription="Control ${letter.uppercase()}";this.selected=current}
                                .clickable(enabled=enabled,role=Role.Button) {choose(index)},contentAlignment=Alignment.Center) {
                                Text("^${letter.uppercase()}",fontFamily=MonoUi,fontSize=13.sp,fontWeight=FontWeight.Bold,
                                    color=if(current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
            }
        }
    }
}
