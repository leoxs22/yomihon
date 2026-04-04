package eu.kanade.presentation.dictionary.components

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import com.turtlekazu.furiganable.compose.m3.TextWithReading as FuriganableTextWithReading

val DictResultContentScale = compositionLocalOf { 1f }

@Composable
fun DictTextWithReading(
    formattedText: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = TextStyle.Default,
    furiganaEnabled: Boolean = true,
    furiganaGap: TextUnit = TextUnit.Unspecified,
    furiganaFontSize: TextUnit = TextUnit.Unspecified,
    furiganaLineHeight: TextUnit = TextUnit.Unspecified,
    furiganaLetterSpacing: TextUnit = TextUnit.Unspecified,
) {
    // APIs 28+ don't respect scale of dictionary popup for furiganable
    val popupScale =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            DictResultContentScale.current
        } else {
            1f
        }
    val effectiveScale = popupScale.coerceAtLeast(0.1f)
    val scaledStyle = remember(style, effectiveScale) { style.scaleTextUnits(effectiveScale) }

    FuriganableTextWithReading(
        formattedText = formattedText,
        modifier = modifier,
        color = color,
        fontSize = fontSize.scaleOrKeep(effectiveScale),
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing.scaleOrKeep(effectiveScale),
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight.scaleOrKeep(effectiveScale),
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        style = scaledStyle,
        furiganaEnabled = furiganaEnabled,
        furiganaGap = furiganaGap.scaleOrKeep(effectiveScale),
        furiganaFontSize = furiganaFontSize.scaleOrKeep(effectiveScale),
        furiganaLineHeight = furiganaLineHeight.scaleOrKeep(effectiveScale),
        furiganaLetterSpacing = furiganaLetterSpacing.scaleOrKeep(effectiveScale),
    )
}

private fun TextStyle.scaleTextUnits(scale: Float): TextStyle {
    if (scale == 1f) return this

    return copy(
        fontSize = fontSize.scaleOrKeep(scale),
        letterSpacing = letterSpacing.scaleOrKeep(scale),
        lineHeight = lineHeight.scaleOrKeep(scale),
        textIndent =
        textIndent?.let {
            TextIndent(
                firstLine = it.firstLine.scaleOrKeep(scale),
                restLine = it.restLine.scaleOrKeep(scale),
            )
        },
    )
}

private fun TextUnit.scaleOrKeep(scale: Float): TextUnit =
    if (!isSpecified) {
        TextUnit.Unspecified
    } else {
        this * scale
    }
