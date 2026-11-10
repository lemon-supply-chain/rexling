package dev.shulhi.rex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class AppBuildInfo(val version: String,val number: Int,val type: String,val revision: String,val modified: Boolean) {
    val commit: String get()=revision+if(modified) " (local changes)" else ""
    val description: String get()="Rexling $version (build $number)\n$type · $commit"
    companion object {
        val current get()=AppBuildInfo(BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE,BuildConfig.BUILD_TYPE,BuildConfig.GIT_REVISION,BuildConfig.LOCAL_CHANGES)
        const val repository="https://github.com/shulhi/rexling"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AboutSheet(onClose: ()->Unit) {
    val context=LocalContext.current
    val uri=LocalUriHandler.current
    ModalBottomSheet(onDismissRequest=onClose,containerColor=MaterialTheme.colorScheme.surface) {
        AboutContent(AppBuildInfo.current,onClose,onCopy={text->
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Rexling build",text))
        },onOpen={url->
            runCatching {uri.openUri(url)}.onFailure {Toast.makeText(context,context.getString(R.string.no_browser),Toast.LENGTH_SHORT).show()}
        })
    }
}

@Composable internal fun AboutContent(info: AppBuildInfo,onClose: ()->Unit,onCopy: (String)->Unit,onOpen: (String)->Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max=620.dp).verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(stringResource(R.string.about),Modifier.weight(1f),fontSize=20.sp,fontWeight=FontWeight.Bold)
            ChromeButton(ChromeIcon.CLOSE,stringResource(R.string.close_about),onClick=onClose)
        }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Image(painterResource(R.drawable.rexlink_dinosaur),contentDescription=null,
                modifier=Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(4.dp))
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.app_name),fontSize=24.sp,fontWeight=FontWeight.Bold)
                Text(stringResource(R.string.app_subtitle),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            BuildInfoRow(stringResource(R.string.version),info.version)
            BuildInfoRow(stringResource(R.string.build),"${info.number} · ${info.type}")
            BuildInfoRow(stringResource(R.string.commit),info.commit)
        }
        OutlinedButton(onClick={onCopy(info.description)}) {ChromeSymbol(ChromeIcon.COPY,Modifier.size(16.dp));Spacer(Modifier.width(8.dp));Text(stringResource(R.string.copy_build_info))}
        Column {
            TextButton(onClick={onOpen(AppBuildInfo.repository)}) {Text(stringResource(R.string.source_code_github))}
            TextButton(onClick={onOpen("${AppBuildInfo.repository}/blob/main/LICENSE")}) {Text(stringResource(R.string.apache_license))}
            TextButton(onClick={onOpen("${AppBuildInfo.repository}/blob/main/THIRD_PARTY_NOTICES.md")}) {Text(stringResource(R.string.opensource_notices))}
        }
        Text(stringResource(R.string.about_built_with),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.about_warranty),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun BuildInfoRow(label: String,value: String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.Top) {
        Text(label,Modifier.width(64.dp),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,fontSize=13.sp,fontWeight=FontWeight.Medium)
    }
}
