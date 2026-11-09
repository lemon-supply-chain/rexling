package dev.shulhi.rex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable internal fun ProgramIcon(program: ProgramIdentity, modifier: Modifier = Modifier, color: Color? = null) {
    val dark = LocalTerminalPalette.current.dark
    val resource = when(program.kind) {
        ProgramKind.CLAUDE -> R.drawable.ic_program_claude
        ProgramKind.CODEX -> R.drawable.ic_program_openai
        ProgramKind.ANTIGRAVITY -> R.drawable.ic_program_antigravity
        ProgramKind.NEOVIM -> R.drawable.ic_program_neovim
        ProgramKind.VIM -> R.drawable.ic_program_vim
        ProgramKind.GIT -> R.drawable.ic_program_git
        ProgramKind.PYTHON -> R.drawable.ic_program_python
        ProgramKind.NODE -> R.drawable.ic_program_nodedotjs
        ProgramKind.CLOUDFLARE -> R.drawable.ic_program_cloudflare
        ProgramKind.REX -> R.drawable.ic_program_rex
        else -> null
    }
    val tint = color ?: when(program.kind) {
        ProgramKind.CLAUDE -> Color(if(dark) 0xffe6a28a else 0xffb65f42)
        ProgramKind.ANTIGRAVITY -> Color(if(dark) 0xff818cf8 else 0xff6366f1)
        ProgramKind.NEOVIM, ProgramKind.VIM -> Color(if(dark) 0xff81be79 else 0xff43853d)
        ProgramKind.GIT -> Color(if(dark) 0xfff2876c else 0xffc7462b)
        ProgramKind.PYTHON -> Color(if(dark) 0xff8cbde5 else 0xff326f9f)
        ProgramKind.NODE -> Color(if(dark) 0xff8bc77a else 0xff477b32)
        ProgramKind.CLOUDFLARE -> Color(if(dark) 0xffffb85c else 0xffb85b00)
        ProgramKind.REX -> Color(if(dark) 0xff7293ff else 0xff3156f4)
        else -> MaterialTheme.colorScheme.onSurface
    }
    if(resource != null) Icon(painterResource(resource), program.label, modifier.size(16.dp), tint)
    else Canvas(modifier.size(16.dp).semantics { contentDescription=program.label }) {
        val u=size.width/24f
        fun line(x: Float,y: Float,x2: Float,y2: Float) = drawLine(tint,Offset(x*u,y*u),Offset(x2*u,y2*u),1.8f*u,StrokeCap.Round)
        when(program.kind) {
            ProgramKind.TMUX -> {
                drawRoundRect(tint,Offset(2*u,3*u),Size(20*u,18*u),CornerRadius(2*u),style=Stroke(1.7f*u))
                line(12f,3f,12f,21f);line(12f,12f,22f,12f)
            }
            ProgramKind.MONITOR -> {line(3f,20f,3f,12f);line(9f,20f,9f,4f);line(15f,20f,15f,9f);line(21f,20f,21f,2f)}
            ProgramKind.SSH -> {
                drawRoundRect(tint,Offset(8*u,10*u),Size(13*u,11*u),CornerRadius(2*u),style=Stroke(1.7f*u))
                drawArc(tint,180f,180f,false,Offset(11*u,3*u),Size(7*u,12*u),style=Stroke(1.7f*u));line(3f,5f,6f,8f);line(6f,8f,3f,11f)
            }
            else -> {line(3f,5f,9f,11f);line(9f,11f,3f,17f);line(12f,18f,21f,18f)}
        }
    }
}
