package dev.shulhi.rex

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable internal fun ProgramIcon(program: ProgramIdentity, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val assets=LocalContext.current.assets
    val tint=if(color==Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    val glyph=remember(program.kind,program.executable,program.label,assets) {ProgramCatalog.glyph(program,assets)}
    val picture=remember(assets,glyph,tint) {glyph?.let {ProgramVectorPicture.loadGlyph(assets,it,tint.toArgb())}}
    Canvas(modifier.semantics { contentDescription=program.label }) {
        if(picture!=null) {
            picture.draw(drawContext.canvas.nativeCanvas,size.width,size.height)
        } else {
            val u=size.width/24f
            fun line(x: Float,y: Float,x2: Float,y2: Float) = drawLine(tint,Offset(x*u,y*u),Offset(x2*u,y2*u),1.8f*u,StrokeCap.Round)
            line(3f,5f,9f,11f);line(9f,11f,3f,17f);line(12f,18f,21f,18f)
        }
    }
}
