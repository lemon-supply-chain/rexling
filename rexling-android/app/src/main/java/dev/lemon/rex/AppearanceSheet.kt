package dev.lemon.rex

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
    TerminalMenuItem(stringResource(if(dark) R.string.toggle_light_theme else R.string.toggle_dark_theme),if(dark) ChromeIcon.SUN else ChromeIcon.MOON,enabled=true,onClick={onDark(!dark)})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AppearanceSheet(appearance: Appearance,themes: List<AppTheme>,light: AppTheme,dark: AppTheme,
    onAppearance: (Appearance)->Unit,onTheme: (String)->Unit,hazeState: dev.chrisbanes.haze.HazeState?=null,onClose: ()->Unit) {
    var editing by remember {mutableStateOf<Boolean?>(null)}
    BackHandler(editing!=null) {editing=null}
    SettingsDrawer(if(editing==null) stringResource(R.string.appearance) else if(editing==true) stringResource(R.string.dark_theme) else stringResource(R.string.light_theme),
        stringResource(R.string.close_appearance),onClose,hazeState,onBack=if(editing!=null) ({editing=null}) else null) {
        Column(Modifier.fillMaxWidth().heightIn(max=600.dp).padding(bottom=12.dp)) {
            if(editing==null) {
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Appearance.entries.forEach {mode->FilterChip(selected=appearance==mode,onClick={onAppearance(mode)},
                        label={Text(stringResource(when(mode){Appearance.SYSTEM->R.string.appearance_system;Appearance.DARK->R.string.appearance_dark;Appearance.LIGHT->R.string.appearance_light}))})}
                }
                for((labelRes,preset) in listOf(R.string.light_theme to light,R.string.dark_theme to dark)) {
                    Row(Modifier.fillMaxWidth().clickable {editing=preset.terminal.dark}.padding(horizontal=16.dp,vertical=12.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        ThemeSwatch(preset)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            Text(stringResource(labelRes),style=SettingsType.row)
                            Text(preset.name,style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ChromeSymbol(ChromeIcon.RIGHT,Modifier.size(16.dp),MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                val selected=if(editing==true) dark else light
                val options=remember(themes,editing) {themes.filter {it.terminal.dark==editing}}
                LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false),contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    items(options,key={it.id}) {theme->
                        val active=theme.id==selected.id
                        val themeDescription=stringResource(R.string.a11y_theme_format,theme.name)
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f))  // alpha-ok translucent theme card
                            .then(if(active) Modifier.border(1.dp,MaterialTheme.colorScheme.primary,RoundedCornerShape(12.dp)) else Modifier)
                            .semantics(mergeDescendants=true) {contentDescription=themeDescription;this.selected=active;role=Role.RadioButton}
                            .clickable {onTheme(theme.id);editing=null}.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            ThemeSwatch(theme)
                            Text(theme.name,Modifier.weight(1f),fontSize=13.sp,fontWeight=if(active) FontWeight.SemiBold else FontWeight.Normal)
                            if(active) ChromeSymbol(ChromeIcon.CHECK,Modifier.size(18.dp),MaterialTheme.colorScheme.primary)
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
        .border(1.dp,Color(palette.foreground).copy(alpha=.15f),RoundedCornerShape(9.dp)).padding(7.dp),verticalArrangement=Arrangement.SpaceBetween) {  // alpha-ok terminal preview chrome
        Text("❯ Aa",fontFamily=MonoUi,fontSize=11.sp,color=Color(palette.foreground),lineHeight=12.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(3.dp)) {for(i in listOf(1,2,3,4,5)) Box(Modifier.size(7.dp,3.dp).background(Color(palette.ansi[i]),RoundedCornerShape(1.dp)))}
    }
}

@Composable internal fun FontSizeDialog(size: Int,onSize: (Int)->Unit,hazeState: dev.chrisbanes.haze.HazeState?=null,onClose: ()->Unit) {
    val decreaseFont=stringResource(R.string.a11y_decrease_font_size)
    val increaseFont=stringResource(R.string.a11y_increase_font_size)
    val fontSizeLabel=stringResource(R.string.a11y_font_size)
    SettingsDrawer(stringResource(R.string.font_size),stringResource(R.string.font_size),onClose,hazeState) {
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(top=8.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                IconButton(onClick={onSize(size-1)},enabled=size>8,modifier=Modifier.semantics {contentDescription=decreaseFont}) {Text("−",fontSize=24.sp)}
                Text("$size sp",fontFamily=MonoUi,fontSize=16.sp)
                IconButton(onClick={onSize(size+1)},enabled=size<20,modifier=Modifier.semantics {contentDescription=increaseFont}) {Text("+",fontSize=24.sp)}
            }
            Slider(value=size.toFloat(),onValueChange={onSize(it.roundToInt())},valueRange=8f..20f,steps=11,modifier=Modifier.semantics {contentDescription=fontSizeLabel})
            Text("Aa 0123 / ~",fontFamily=MonoUi,fontSize=size.sp)
            TextButton(onClick=onClose,modifier=Modifier.align(Alignment.End)){Text(stringResource(R.string.done))}
        }
    }
}
