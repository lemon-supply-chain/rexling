package dev.shulhi.rex

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable internal fun NewSessionDialog(serverLabel: String,enabled: Boolean,onCreate: (String)->Boolean,onDismiss: ()->Unit) {
    var name by rememberSaveable {mutableStateOf("")}
    AlertDialog(onDismissRequest=onDismiss,containerColor=MaterialTheme.colorScheme.surface,
        title={Text(stringResource(R.string.new_session),style=SettingsType.title)},text={
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.on_server_format,serverLabel),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),singleLine=true,
                    textStyle=SettingsType.row,label={Text(stringResource(R.string.session_name_optional),style=SettingsType.detail)},
                    supportingText={Text(stringResource(R.string.session_name_hint),style=SettingsType.detail)})
            }
        },confirmButton={Button(onClick={if(onCreate(name)) onDismiss()},enabled=enabled) {Text(stringResource(R.string.create),style=SettingsType.row)}},
        dismissButton={TextButton(onClick=onDismiss) {Text(stringResource(R.string.cancel),style=SettingsType.row)}})
}

@Composable internal fun EmptySessionContent(serverLabel: String,enabled: Boolean,onNew: ()->Unit,modifier: Modifier=Modifier) {
    Column(modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(10.dp,Alignment.CenterVertically),horizontalAlignment=Alignment.CenterHorizontally) {
        Text(stringResource(R.string.no_sessions_yet),style=SettingsType.title)
        Text(stringResource(R.string.start_session_on_format,serverLabel),style=SettingsType.body,color=MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick=onNew,enabled=enabled) {
            ChromeSymbol(ChromeIcon.ADD,Modifier.size(17.dp));Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.new_session),style=SettingsType.row)
        }
    }
}
