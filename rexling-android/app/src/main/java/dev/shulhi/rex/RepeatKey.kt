package dev.shulhi.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** A scrolling drag never sends a key; a stationary hold repeats until cancelled. */
internal fun Modifier.repeatKey(enabled: Boolean,target: String,repeatIntervalMillis: Long=65,onKey: ()->Unit): Modifier=composed {
    val scope=rememberCoroutineScope();val configuration=LocalViewConfiguration.current
    val haptics=LocalHapticFeedback.current
    val latest by rememberUpdatedState(onKey)
    var pressed by remember {mutableStateOf(false)}
    val fire={
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        latest()
    }
    background(if(pressed) MaterialTheme.colorScheme.primary.copy(alpha=.16f) else Color.Transparent)
        .semantics {role=Role.Button;if(enabled) onClick {fire();true} else disabled()}
        .pointerInput(enabled,target,repeatIntervalMillis) {
            if(!enabled) return@pointerInput
            awaitEachGesture {
                val down=awaitFirstDown();down.consume();pressed=true
                var moved=false
                fire()
                val job=scope.launch {
                    delay(configuration.longPressTimeoutMillis)
                    while(isActive) {fire();delay(repeatIntervalMillis)}
                }
                try {
                    while(true) {
                        val change=awaitPointerEvent().changes.firstOrNull {it.id==down.id} ?: break
                        if(change.isConsumed) break
                        if(abs(change.position.x-down.position.x)>configuration.touchSlop || abs(change.position.y-down.position.y)>configuration.touchSlop) {
                            moved=true;pressed=false;job.cancel()
                        }
                        if(!change.pressed) {
                            job.cancel()
                            change.consume();break
                        }
                        // Leave moves unconsumed so parent gestures can take over.
                    }
                } finally {job.cancel();pressed=false}
            }
        }
}
