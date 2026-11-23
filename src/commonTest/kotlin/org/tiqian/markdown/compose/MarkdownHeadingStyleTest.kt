package org.tiqian.markdown.compose

import org.tiqian.markdown.*

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.tiqian.core.Ic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownHeadingStyleTest {
    @Test
    fun defaultHeadingGeometryScalesFromBodyFontSize() {
        val style = MarkdownStyle(
            body = TextStyle(fontSize = 18.sp, lineHeight = 30.sp),
        )

        assertSp(28f, style.heading(1).fontSize)
        assertSp(39.2f, style.heading(1).lineHeight)
        assertSp(24f, style.heading(2).fontSize)
        assertSp(34.8f, style.heading(2).lineHeight)
        assertSp(20f, style.heading(3).fontSize)
        assertSp(31f, style.heading(3).lineHeight)
        assertSp(15.75f, style.heading(6).fontSize)
        assertSp(29.25f, style.heading(6).lineHeight)
        assertEquals(FontWeight.Medium, style.heading(1).fontWeight)
        assertEquals(FontWeight.Medium, style.heading(2).fontWeight)
        assertEquals(FontWeight.Bold, style.heading(4).fontWeight)
        assertEquals(FontWeight.Bold, style.heading(5).fontWeight)
        assertEquals(FontWeight.Bold, style.heading(6).fontWeight)
    }

    @Test
    fun headingTextStyleCanDecorateAndOverrideRelativeGeometry() {
        val style = MarkdownStyle(
            body = TextStyle(color = Color.Black, fontSize = 20.sp, lineHeight = 32.sp),
            heading2 = TextStyle(
                color = Color.Red,
                fontSize = 1.6.em,
                lineHeight = 1.2.em,
                textDecoration = TextDecoration.Underline,
            ),
        )

        val heading = style.heading(2)
        assertEquals(32.sp, heading.fontSize)
        assertEquals(38.4.sp, heading.lineHeight)
        assertEquals(Color.Red, heading.color)
        assertEquals(TextDecoration.Underline, heading.textDecoration)
    }

    @Test
    fun allHeadingGapsUseBodyLineHeight() {
        val spacing = MarkdownHeadingSpacing(
            betweenBodyLines = 1f,
        )

        assertEquals(1f, spacing.linesBetween(previousLevel = null, nextLevel = 1))
        assertEquals(1f / 2f, spacing.linesBetween(previousLevel = 2, nextLevel = null))
        assertEquals(1f / 2f, spacing.linesBetween(previousLevel = null, nextLevel = 3))
        assertEquals(1f / 4f, spacing.linesBetween(previousLevel = 4, nextLevel = null))
        assertEquals(1f / 2f, spacing.linesBetween(previousLevel = null, nextLevel = 5))
        assertEquals(1f / 2f, spacing.linesBetween(previousLevel = null, nextLevel = 6))
        assertEquals(0f, spacing.linesBetween(previousLevel = 6, nextLevel = null))
        assertEquals(1f, spacing.linesBetween(previousLevel = 2, nextLevel = 3))
        assertNull(spacing.linesBetween(previousLevel = null, nextLevel = null))
        assertEquals(1f, MarkdownStyle().displayBlockSpacingBodyLines)
        assertEquals(0f, MarkdownStyle().tightListItemSpacingBodyLines)
        assertEquals(1f / 4f, MarkdownStyle().listBlockSpacingBodyLines)
        assertEquals(1f / 2f, MarkdownStyle().listItemSpacingBodyLines)
    }

    @Test
    fun explicitBodyLineHeightIsTheSpacingBasis() {
        assertEquals(
            30.sp,
            MarkdownStyle(body = TextStyle(fontSize = 18.sp, lineHeight = 30.sp)).bodyLineHeightSpOrNull(),
        )
        assertEquals(
            27.sp,
            MarkdownStyle(body = TextStyle(fontSize = 18.sp, lineHeight = 1.5.em)).bodyLineHeightSpOrNull(),
        )
        assertNull(MarkdownStyle(body = TextStyle(fontSize = 18.sp)).bodyLineHeightSpOrNull())
    }

    @Test
    fun footnoteDefinitionGeometryResolvesAgainstTheConfiguredBody() {
        val footnote = MarkdownStyle(
            body = TextStyle(fontSize = 18.sp, lineHeight = 30.sp),
        ).footnoteContentTextStyle()

        assertSp(15.75f, footnote.fontSize)
        assertSp(26.25f, footnote.lineHeight)
    }

    @Test
    fun secondaryLabelsUseTheSmallerEditorialScale() {
        val style = MarkdownStyle(
            body = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
        )

        assertSp(13f, style.codeMeta.fontSize)
        assertSp(21.125f, style.codeMeta.lineHeight)
        assertSp(11.375f, style.codeLanguage.fontSize)
        assertSp(21.125f, style.codeLanguage.lineHeight)
        assertEquals(FontWeight.SemiBold, style.codeLanguage.fontWeight)
        assertSp(13f, style.caption.fontSize)
        assertSp(21.125f, style.caption.lineHeight)
        assertEquals(Ic(0.5f), style.captionHorizontalIndent)
    }

    private fun assertSp(expected: Float, actual: TextUnit) {
        assertTrue(actual.isSp, "expected an sp value, got $actual")
        assertEquals(expected, actual.value, absoluteTolerance = 0.0001f)
    }
}
