package com.clawdbot.android.testchat.v2

import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

@Composable
internal fun rememberV2Markwon(fontSize: TextUnit): Markwon {
  val context = LocalContext.current
  val px =
    with(LocalDensity.current) {
      if (fontSize.isUnspecified) 15.sp.toPx() else fontSize.toPx()
    }
  return remember(px) {
    Markwon.builder(context)
      .usePlugin(TablePlugin.create(context))
      .usePlugin(LinkifyPlugin.create())
      .usePlugin(JLatexMathPlugin.create(px))
      .build()
  }
}

@Composable
internal fun V2MarkdownText(
  markdown: Markwon,
  text: String,
  textColor: Color,
  modifier: Modifier = Modifier,
) {
  AndroidView(
    factory = { ctx ->
      TextView(ctx).apply {
        movementMethod = LinkMovementMethod.getInstance()
        setTextIsSelectable(true)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
      }
    },
    update = { view ->
      view.setTextColor(textColor.toArgb())
      markdown.setMarkdown(view, text)
    },
    modifier = modifier,
  )
}
