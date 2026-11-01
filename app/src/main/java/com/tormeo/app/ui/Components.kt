package com.tormeo.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.tormeo.app.TormeoApp

/** Horizontal/vertical safe padding: TVs need overscan margins. */
val screenPadding: PaddingValues
    get() = if (TormeoApp.instance.isTv) PaddingValues(horizontal = 48.dp, vertical = 24.dp) else PaddingValues(horizontal = 16.dp, vertical = 12.dp)

@Composable
fun posterWidth(): Dp {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        TormeoApp.instance.isTv -> 124.dp
        w < 400 -> 104.dp
        w < 700 -> 118.dp
        else -> 132.dp
    }
}

/**
 * Focusable + clickable container with a clear focus ring (D-pad) that also works with touch.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    scaleOnFocus: Boolean = true,
    requestFocus: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fr = remember { FocusRequester() }
    if (requestFocus) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Box(
        modifier
            .graphicsLayer {
                val s = if (focused && scaleOnFocus) 1.06f else 1f
                scaleX = s; scaleY = s
            }
            .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .clip(shape)
            .focusRequester(fr)
            .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick),
    ) { content(focused) }
}

@Composable
fun TvButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    requestFocus: Boolean = false,
    onClick: () -> Unit,
) {
    FocusBox(modifier, RoundedCornerShape(24.dp), scaleOnFocus = false, requestFocus = requestFocus, onClick = onClick) { focused ->
        Text(
            text,
            color = if (focused) Bg else Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            modifier = Modifier
                .background(if (focused) Color.White else if (primary) Accent else Surface2)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

@Composable
fun Poster(
    title: String,
    image: String?,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    subtitle: String? = null,
    requestFocus: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val w = posterWidth()
    Column(modifier.width(w)) {
        FocusBox(Modifier.width(w).height(w * 1.5f), requestFocus = requestFocus, onLongClick = onLongClick, onClick = onClick) {
            Box(Modifier.fillMaxSize().background(Surface2)) {
                if (image != null) {
                    AsyncImage(model = image, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(title, Modifier.align(Alignment.Center).padding(8.dp), color = TextDim, fontSize = 13.sp)
                }
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                        color = Accent,
                        trackColor = Color(0x66000000),
                    )
                }
            }
        }
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        if (subtitle != null) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = TextDim)
    }
}

@Composable
fun Badge(text: String, color: Color = Surface2, textColor: Color = Color.White) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = textColor,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(vertical = 8.dp))
}

@Composable
fun SwitchRow(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    FocusBox(Modifier.fillMaxWidth().padding(vertical = 3.dp), scaleOnFocus = false, onClick = { onChange(!checked) }) { focused ->
        Row(
            Modifier.fillMaxWidth().background(if (focused) Surface2 else Surface1).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp)
                if (desc != null) Text(desc, fontSize = 12.sp, color = TextDim)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
fun ValueRow(title: String, value: String, desc: String? = null, onClick: () -> Unit) {
    FocusBox(Modifier.fillMaxWidth().padding(vertical = 3.dp), scaleOnFocus = false, onClick = onClick) { focused ->
        Row(
            Modifier.fillMaxWidth().background(if (focused) Surface2 else Surface1).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp)
                if (desc != null) Text(desc, fontSize = 12.sp, color = TextDim)
            }
            Text(value, color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

@Composable
fun CenterMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = TextDim)
    }
}

@Composable
fun Dot() = Box(Modifier.padding(horizontal = 6.dp).size(3.dp).clip(RoundedCornerShape(2.dp)).background(TextDim))
