package dev.shulhi.rex

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The phone equivalent of `rex forward ls`, with a per-forward close. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PortForwardsSheet(onClose: () -> Unit) {
    val states by PortForwardRegistry.states.collectAsState()
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Port forwards", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                ChromeButton(ChromeIcon.CLOSE, "Close port forwards", onClick = onClose)
            }
            if (states.isEmpty()) {
                Text("No port forwards are running.", Modifier.padding(vertical = 12.dp),
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            states.forEach { state ->
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            if (state.targetPort > 0) "${state.targetHost}:${state.targetPort}" else "Waiting for Rex",
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            when {
                                state.active -> "127.0.0.1:${state.localPort} · ${state.connections} open"
                                state.awaitingSelection -> "${state.choices.size} ports found · choose one"
                                state.terminal -> "Closed"
                                else -> "Starting"
                            },
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.error.isNotBlank()) {
                            Text(state.error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (!state.terminal) TextButton(onClick = { PortForwardRegistry.close(state.handle) }) { Text("Close") }
                }
            }
        }
    }
}
