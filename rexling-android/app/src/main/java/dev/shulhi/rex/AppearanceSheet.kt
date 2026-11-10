package dev.shulhi.rex

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable internal fun QuickThemeMenuItem(dark: Boolean,onDark: (Boolean)->Unit) {
    TerminalMenuItem(stringResource(if(dark) R.string.toggle_light_theme else R.string.toggle_dark_theme),if(dark) ChromeIcon.SUN else ChromeIcon.MOON,onClick={onDark(!dark)})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AppearanceSheet(appearance: Appearance,themes: List<AppTheme>,light: AppTheme,dark: AppTheme,
    onAppearance: (Appearance)->Unit,onTheme: (String)->Unit,onClose: ()->Unit) {
    var editing by remember {mutableStateOf<Boolean?>(null)}
    BackHandler(editing!=null) {editing=null}
    ModalBottomSheet(onDismissRequest=onClose,containerColor=MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max=650.dp).padding(bottom=24.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                if(editing!=null) ChromeButton(ChromeIcon.LEFT,stringResource(R.string.back_to_appearance)) {editing=null}
                Text(if(editing==null) stringResource(R.string.appearance) else if(editing==true) stringResource(R.string.dark_theme) else stringResource(R.string.light_theme),Modifier.weight(1f),fontSize=20.sp,fontWeight=FontWeight.Bold)
                ChromeButton(ChromeIcon.CLOSE,stringResource(R.string.close_appearance),onClick=onClose)
            }
            if(editing==null) {
                Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Appearance.entries.forEach {mode->FilterChip(selected=appearance==mode,onClick={onAppearance(mode)},
                        label={Text(stringResource(when(mode){Appearance.SYSTEM->R.string.appearance_system;Appearance.DARK->R.string.appearance_dark;Appearance.LIGHT->R.string.appearance_light}))})}
                }
                for((labelRes,preset) in listOf(R.string.light_theme to light,R.string.dark_theme to dark)) {
                    Row(Modifier.fillMaxWidth().clickable {editing=preset.terminal.dark}.padding(horizontal=20.dp,vertical=12.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        ThemeSwatch(preset)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            Text(stringResource(labelRes),fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                            Text(preset.name,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ChromeSymbol(ChromeIcon.RIGHT,Modifier.size(16.dp),MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                val selected=if(editing==true) dark else light
                val options=remember(themes,editing) {themes.filter {it.terminal.dark==editing}}
                LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false),contentPadding=PaddingValues(horizontal=12.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    items(options,key={it.id}) {theme->
                        val active=theme.id==selected.id
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                            .then(if(active) Modifier.border(1.dp,MaterialTheme.colorScheme.primary,RoundedCornerShape(16.dp)) else Modifier)
                            .semantics(mergeDescendants=true) {contentDescription="Theme ${theme.name}";this.selected=active;role=Role.RadioButton}
                            .clickable {onTheme(theme.id);editing=null}.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            ThemeSwatch(theme)
                            Text(theme.name,Modifier.weight(1f),fontSize=13.sp,fontWeight=if(active) FontWeight.SemiBold else FontWeight.Normal)
                            if(active) Text("✓",color=MaterialTheme.colorScheme.primary,fontSize=18.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ThemeSwatch(theme: AppTheme) {
    val palette=theme.terminal
    Column(Modifier.size(64.dp,42.dp).clip(RoundedCornerShape(9.dp)).background(Color(palette.background))
        .border(1.dp,Color(palette.foreground).copy(alpha=.15f),RoundedCornerShape(9.dp)).padding(7.dp),verticalArrangement=Arrangement.SpaceBetween) {
        Text("❯ Aa",fontFamily=MonoUi,fontSize=11.sp,color=Color(palette.foreground),lineHeight=12.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(3.dp)) {for(i in listOf(1,2,3,4,5)) Box(Modifier.size(7.dp,3.dp).background(Color(palette.ansi[i]),RoundedCornerShape(1.dp)))}
    }
}

@Composable internal fun FontSizeDialog(size: Int,onSize: (Int)->Unit,onClose: ()->Unit) {
    AlertDialog(onDismissRequest=onClose,title={Text(stringResource(R.string.font_size),style=SettingsType.title)},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                IconButton(onClick={onSize(size-1)},enabled=size>8,modifier=Modifier.semantics {contentDescription="Decrease font size"}) {Text("−",fontSize=24.sp)}
                Text("$size sp",fontFamily=MonoUi,fontSize=16.sp)
                IconButton(onClick={onSize(size+1)},enabled=size<20,modifier=Modifier.semantics {contentDescription="Increase font size"}) {Text("+",fontSize=24.sp)}
            }
            Slider(value=size.toFloat(),onValueChange={onSize(it.roundToInt())},valueRange=8f..20f,steps=11,modifier=Modifier.semantics {contentDescription="Font size"})
            Text("Aa 0123 / ~",fontFamily=MonoUi,fontSize=size.sp)
        }
    },confirmButton={TextButton(onClick=onClose){Text(stringResource(R.string.done))}})
}
