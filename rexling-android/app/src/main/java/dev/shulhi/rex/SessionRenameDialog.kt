package dev.shulhi.rex

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun sessionNameError(raw: String): String? {
    val name=raw.trim()
    return when {
        name.isEmpty()->"Enter a session name"
        name.codePointCount(0,name.length)>128->"Use at most 128 characters"
        name.any {it.isISOControl()}->"Session names cannot contain control characters"
        else->null
    }
}

@Composable internal fun sessionErrorMessage(error: String?): String? = when(error) {
    "Enter a session name"->stringResource(R.string.session_name_empty)
    "Use at most 128 characters"->stringResource(R.string.session_name_too_long)
    "Session names cannot contain control characters"->stringResource(R.string.session_name_control_chars)
    else->error
}

@Composable internal fun SessionRenameDialog(target: SessionTarget,enabled: Boolean,onSave: (String)->Unit,onDismiss: ()->Unit) {
    var value by remember(target.key) {mutableStateOf(TextFieldValue(target.session.label,TextRange(0,target.session.label.length)))}
    val error=sessionNameError(value.text)
    val canSave=enabled && error==null && value.text.trim()!=target.session.label
    val focus=remember {FocusRequester()}
    LaunchedEffect(target.key) {focus.requestFocus()}
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.rename_session))},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(target.server.label,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value,{value=it},Modifier.fillMaxWidth().focusRequester(focus),label={Text(stringResource(R.string.session_name))},singleLine=true,
                enabled=enabled,isError=error!=null,supportingText={sessionErrorMessage(error)?.let {Text(it)}},
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={if(canSave) onSave(value.text.trim())}))
        }
    },confirmButton={Button(enabled=canSave,onClick={onSave(value.text.trim())}){Text(stringResource(R.string.save))}},
        dismissButton={TextButton(onClick=onDismiss){Text(stringResource(R.string.cancel))}})
}
