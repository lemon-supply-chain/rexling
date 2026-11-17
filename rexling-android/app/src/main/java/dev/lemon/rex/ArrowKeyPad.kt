package dev.lemon.rex

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Discrete tap/hold arrows, restored as the joystick's double-tap alternative. */
@Composable internal fun ArrowKeyPad(enabled: Boolean,modifier: Modifier=Modifier,onKey: (String)->Unit,readable: Boolean=enabled,inputKey: String="") {
    val keys=listOf(listOf("pageup","up","pagedown"),listOf("left","down","right"))
    val icons=mapOf("left" to ChromeIcon.LEFT,"down" to ChromeIcon.DOWN,"up" to ChromeIcon.UP,"right" to ChromeIcon.RIGHT)
    Column(modifier.semantics {contentDescription="Arrow directions"},verticalArrangement=Arrangement.spacedBy(4.dp)) {
        keys.forEach {row->
            Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                row.forEach {key->
                    val page=key=="pageup" || key=="pagedown"
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(11.dp))
                        .semantics {contentDescription=when(key) {"pageup"->"Page up";"pagedown"->"Page down";else->"${key.replaceFirstChar {it.uppercaseChar()}} arrow"}}
                        .repeatKey(enabled,inputKey,repeatIntervalMillis=if(page) 160L else 65L) {onKey(key)},contentAlignment=Alignment.Center) {
                        val color=MaterialTheme.colorScheme.onSurface.copy(alpha=if(readable) 1f else .38f)
                        if(page) Text(if(key=="pageup") "PgUp" else "PgDn",fontFamily=MonoUi,fontSize=11.sp,fontWeight=FontWeight.Medium,color=color)
                        else ChromeSymbol(icons.getValue(key),Modifier.size(22.dp),color)
                    }
                }
            }
        }
    }
}
