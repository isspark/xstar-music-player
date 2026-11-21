package life.xstar.music.player.ui.playlist

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import life.xstar.music.player.data.preferences.PlaylistStyle
import kotlin.math.cos
import kotlin.math.sin

/** Shared native artwork keeps previews and the live library on the same theme tokens. */
@Composable
internal fun MixArt(seed: Long, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier) {
        drawRect(colors.primaryContainer)
        val w = size.width; val h = size.height
        when ((seed % 3).toInt()) {
            0 -> {
                val p = Path().apply { moveTo(0f,h); lineTo(w*.43f,h*.12f); lineTo(w*.72f,h); close() }
                drawPath(p, colors.primary.copy(alpha=.4f))
                drawCircle(colors.primary, size.minDimension*.13f, Offset(w*.79f,h*.23f))
                drawLine(colors.onPrimaryContainer.copy(alpha=.6f), Offset(0f,h*.87f), Offset(w,h*.7f), 2.dp.toPx())
            }
            1 -> repeat(5) { i -> drawCircle(if(i%2==0) colors.primary else colors.surface, size.minDimension*(.46f-i*.08f), Offset(w*.5f,h*.55f)) }
            else -> repeat(4) { i ->
                val p = Path().apply { moveTo(0f,h); lineTo(0f,h*(.4f+i*.15f)); cubicTo(w*.3f,h*(-.1f+i*.2f),w*.7f,h*(.9f-i*.1f),w,h*(.4f+i*.15f)); lineTo(w,h); close() }
                drawPath(p, if(i%2==0) colors.primary.copy(alpha=.3f+i*.15f) else colors.surfaceVariant)
            }
        }
    }
}

@Composable
internal fun StyledMixCover(style: PlaylistStyle, title: String, seed: Long, modifier: Modifier = Modifier, spinning: Boolean = false) {
    val c = MaterialTheme.colorScheme
    when (style) {
        PlaylistStyle.Tape -> Surface(modifier, shape=RoundedCornerShape(12.dp), color=c.surfaceVariant, border=androidx.compose.foundation.BorderStroke(1.dp,c.outlineVariant)) {
            Column(Modifier.padding(10.dp)) {
                Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(5.dp))) {
                    MixArt(seed,Modifier.fillMaxSize())
                    Row(Modifier.align(Alignment.Center).fillMaxWidth(.68f).height(42.dp).clip(RoundedCornerShape(22.dp)).background(c.surface.copy(alpha=.8f)), verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceAround) {
                        repeat(2) { Box(Modifier.size(30.dp).border(5.dp,c.outline,CircleShape).background(c.background,CircleShape)) }
                    }
                    Text("XSTAR / MIX",Modifier.padding(7.dp),fontSize=8.sp,color=c.onPrimaryContainer,fontFamily=FontFamily.Monospace)
                }
                Row(Modifier.fillMaxWidth().padding(top=9.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(title,maxLines=1,fontSize=13.sp,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f))
                    Text("A / B",fontSize=9.sp,color=c.primary,fontFamily=FontFamily.Monospace)
                }
            }
        }
        PlaylistStyle.Turntable -> Box(modifier.background(c.surfaceVariant,RoundedCornerShape(6.dp))) {
            val angle = if (spinning) {
                val transition = rememberInfiniteTransition(label = "vinyl")
                val rotation by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(30_000, easing = LinearEasing)), label = "rotation")
                rotation
            } else 0f
            Canvas(Modifier.fillMaxSize()) {
                val center=Offset(size.width*.41f,size.height*.43f); val r=size.minDimension*.49f
                drawCircle(c.inverseSurface,r,center)
                repeat(12) { drawCircle(c.inverseOnSurface.copy(alpha=.13f),r*(.35f+it*.05f),center,style=Stroke(1.dp.toPx())) }
                rotate(angle, center) {
                    drawCircle(c.primary,r*.32f,center)
                    drawLine(c.onPrimary.copy(alpha=.6f),Offset(center.x-r*.2f,center.y-r*.12f),Offset(center.x+r*.2f,center.y-r*.12f),2.dp.toPx())
                    drawLine(c.onPrimary.copy(alpha=.6f),Offset(center.x-r*.15f,center.y+r*.12f),Offset(center.x+r*.15f,center.y+r*.12f),2.dp.toPx())
                }
                drawCircle(c.onPrimary,r*.035f,center)
                drawLine(c.outline,Offset(size.width*.82f,size.height*.13f),Offset(size.width*.74f,size.height*.63f),7.dp.toPx())
                drawLine(c.primary,Offset(size.width*.74f,size.height*.61f),Offset(size.width*.72f,size.height*.73f),11.dp.toPx())
            }
            Text(title,Modifier.align(Alignment.BottomStart).padding(13.dp),fontSize=12.sp,maxLines=1)
            Text("33⅓",Modifier.align(Alignment.TopEnd).padding(12.dp),fontSize=15.sp,color=c.primary)
        }
        PlaylistStyle.Desktop -> Box(modifier.padding(9.dp)) {
            Box(Modifier.fillMaxSize().rotate(-5f).border(1.dp,c.outline).background(c.surfaceVariant))
            Box(Modifier.fillMaxSize().rotate(4f).border(1.dp,c.primary).background(c.surface))
            Column(Modifier.fillMaxSize().rotate(-1f).border(1.dp,c.outline).background(c.surface)) {
                Row(Modifier.fillMaxWidth().background(c.primary).padding(6.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("● ● ●",fontSize=8.sp,color=c.onPrimary);Text("mix.xstar  − □ ×",fontSize=8.sp,color=c.onPrimary,fontFamily=FontFamily.Monospace)
                }
                Row(Modifier.weight(1f).padding(13.dp),verticalAlignment=Alignment.CenterVertically) {
                    MixArt(seed,Modifier.size(58.dp).rotate(-5f));Spacer(Modifier.width(12.dp))
                    Column { Text(title,fontSize=14.sp,maxLines=2);Text("audio files / ready",fontSize=8.sp,color=c.primary,fontFamily=FontFamily.Monospace) }
                }
            }
        }
        PlaylistStyle.Cinema -> Column(modifier.background(c.inverseSurface)) {
            FilmHoles(Modifier.fillMaxWidth().height(13.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) { MixArt(seed,Modifier.fillMaxSize());Text("SCENE ${seed.toString().padStart(2,'0')}",Modifier.padding(10.dp),fontSize=9.sp,color=c.onPrimaryContainer,fontFamily=FontFamily.Monospace) }
            Text(title,Modifier.padding(12.dp),color=c.inverseOnSurface,fontSize=17.sp,maxLines=1)
            FilmHoles(Modifier.fillMaxWidth().height(13.dp))
        }
        PlaylistStyle.Bookshelf -> Row(modifier.padding(vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.Bottom) {
            repeat(4) { i ->
                val selected=i==1; val bg=if(selected)c.primary else c.surfaceVariant
                Column(Modifier.weight(1f).fillMaxHeight(if(i==2)1f else if(i%2==0).9f else .82f).background(bg,RoundedCornerShape(topStart=4.dp)).border(1.dp,c.outlineVariant).padding(vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("0${i+1}",fontSize=8.sp,color=if(selected)c.onPrimary else c.onSurfaceVariant,fontFamily=FontFamily.Monospace)
                    Spacer(Modifier.height(10.dp))
                    title.take(6).forEach { Text(it.toString(),fontSize=14.sp,color=if(selected)c.onPrimary else c.onSurface) }
                }
            }
        }
        PlaylistStyle.Radar -> Box(modifier) {
            RadarDrawing(Modifier.fillMaxSize(),0f)
            Canvas(Modifier.fillMaxSize()) {
                repeat(5) { i ->
                    val a = i * 1.25f
                    drawCircle(c.primary, 3.dp.toPx(), Offset(center.x + cos(a) * size.minDimension * .3f, center.y + sin(a) * size.minDimension * .3f))
                }
            }
            Text(title,Modifier.align(Alignment.BottomCenter).background(c.surface.copy(alpha=.85f)).padding(8.dp),fontSize=12.sp,color=c.primary)
        }
    }
}

@Composable
internal fun FilmHoles(modifier: Modifier) {
    val c=MaterialTheme.colorScheme
    Canvas(modifier) { drawRect(c.inverseSurface);repeat(22) { drawRect(c.inverseOnSurface.copy(alpha=.4f),Offset(it*size.width/22+2.dp.toPx(),3.dp.toPx()),Size(5.dp.toPx(),5.dp.toPx())) } }
}

@Composable
internal fun RadarDrawing(modifier: Modifier, angle: Float) {
    val c=MaterialTheme.colorScheme
    Canvas(modifier) {
        val r=size.minDimension*.47f
        drawCircle(c.surfaceVariant.copy(alpha=.5f),r)
        repeat(4) { drawCircle(c.primary.copy(alpha=.25f),r*(it+1)/4,style=Stroke(1.dp.toPx())) }
        drawLine(c.primary.copy(alpha=.15f),Offset(center.x-r,center.y),Offset(center.x+r,center.y))
        drawLine(c.primary.copy(alpha=.15f),Offset(center.x,center.y-r),Offset(center.x,center.y+r))
        drawArc(c.primary.copy(alpha=.14f),angle-55f,55f,true,Offset(center.x-r,center.y-r),Size(r*2,r*2))
        val a=angle*Math.PI/180
        drawLine(c.primary.copy(alpha=.7f),center,Offset(center.x+cos(a).toFloat()*r,center.y+sin(a).toFloat()*r),1.dp.toPx())
        drawCircle(c.primary,3.dp.toPx(),center)
    }
}

@Composable
fun PlaylistAppearancePreview(style: PlaylistStyle, modifier: Modifier = Modifier) {
    StyledMixCover(style,"开往凌晨",3,modifier)
}
