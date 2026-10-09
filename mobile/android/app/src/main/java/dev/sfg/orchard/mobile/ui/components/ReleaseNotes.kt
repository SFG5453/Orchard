/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stars
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

/** Category classifications for release note sections matching desktop changelogs. */
enum class ReleaseNoteCategory {
    NEW,
    FIXED,
    CHANGED,
    SECURITY,
    OTHER,
}

/** Structured release note section extracted from markdown. */
data class ReleaseNoteSection(
    val title: String,
    val items: List<String>,
    val category: ReleaseNoteCategory = categorizeSection(title),
)

/**
 * Classifies release note heading titles into categories for visual badges and icons.
 */
fun categorizeSection(title: String): ReleaseNoteCategory {
    val lower = title.lowercase()
    return when {
        lower.contains("security") || lower.contains("vulnerability") ->
            ReleaseNoteCategory.SECURITY
        lower.contains("fix") || lower.contains("bug") || lower.contains("resolved") || lower.contains("patch") ->
            ReleaseNoteCategory.FIXED
        lower.contains("new") || lower.contains("add") || lower.contains("feature") || lower.contains("improved") || lower.contains("improvement") ->
            ReleaseNoteCategory.NEW
        lower.contains("change") || lower.contains("update") || lower.contains("tweak") || lower.contains("maintenance") || lower.contains("refactor") || lower.contains("remov") || lower.contains("deprecat") ->
            ReleaseNoteCategory.CHANGED
        else ->
            ReleaseNoteCategory.OTHER
    }
}

/**
 * Parses markdown release notes into structured sections using JetBrains Markdown AST parser.
 */
fun parseReleaseNoteSections(markdown: String): List<ReleaseNoteSection> {
    if (markdown.isBlank()) return emptyList()

    val flavour = GFMFlavourDescriptor()
    val parsedTree = MarkdownParser(
        flavour = flavour,
        cancellationToken = CancellationToken.NonCancellable,
    ).buildMarkdownTreeFromString(markdown as CharSequence)

    val sections = mutableListOf<ReleaseNoteSection>()
    var currentTitle: String? = null
    val currentItems = mutableListOf<String>()

    fun flushSection() {
        val title = currentTitle
        if (title != null && currentItems.isNotEmpty()) {
            sections.add(ReleaseNoteSection(title = title, items = currentItems.toList()))
            currentItems.clear()
        } else if (title == null && currentItems.isNotEmpty()) {
            sections.add(ReleaseNoteSection(title = "What's new", items = currentItems.toList()))
            currentItems.clear()
        }
    }

    fun cleanHeadingText(raw: String): String {
        return raw.trim()
            .removePrefix("######").removePrefix("#####").removePrefix("####")
            .removePrefix("###").removePrefix("##").removePrefix("#")
            .trim()
            .removePrefix("**").removeSuffix("**")
            .removeSuffix(":")
            .trim()
    }

    fun isDocTitleHeading(text: String): Boolean {
        val lower = text.lowercase()
        val versionRegex = Regex("""\b\d+\.\d+\.\d+\b""")
        return (lower.startsWith("orchard") && versionRegex.containsMatchIn(lower)) ||
            (lower.startsWith("v") && versionRegex.containsMatchIn(lower)) ||
            (lower.matches(Regex("""^#*\s*v?\d+\.\d+\.\d+.*"""))) ||
            lower == "release notes"
    }

    fun cleanListItemText(raw: String): String {
        return raw.trim()
            .replaceFirst(Regex("""^([-*+]|\d+[.)]|•)\s*"""), "")
            .trim()
    }

    for (child in parsedTree.children) {
        when (child.type) {
            MarkdownElementTypes.ATX_1,
            MarkdownElementTypes.ATX_2,
            MarkdownElementTypes.ATX_3,
            MarkdownElementTypes.ATX_4,
            MarkdownElementTypes.ATX_5,
            MarkdownElementTypes.ATX_6 -> {
                val headingText = cleanHeadingText(child.getTextInNode(markdown).toString())
                if (headingText.isNotBlank()) {
                    if (isDocTitleHeading(headingText) && sections.isEmpty() && currentTitle == null) {
                        continue
                    }
                    flushSection()
                    currentTitle = headingText
                }
            }

            MarkdownElementTypes.UNORDERED_LIST,
            MarkdownElementTypes.ORDERED_LIST -> {
                for (listItem in child.children) {
                    if (listItem.type == MarkdownElementTypes.LIST_ITEM) {
                        val itemText = cleanListItemText(listItem.getTextInNode(markdown).toString())
                        if (itemText.isNotBlank()) {
                            currentItems.add(itemText)
                        }
                    }
                }
            }

            MarkdownElementTypes.PARAGRAPH -> {
                val paragraphText = child.getTextInNode(markdown).toString().trim()
                val boldHeadingMatch = Regex("""^\*\*(.+?)\*\*[:]?$""").matchEntire(paragraphText)
                if (boldHeadingMatch != null) {
                    val heading = boldHeadingMatch.groupValues[1].trim().removeSuffix(":")
                    if (heading.isNotBlank()) {
                        flushSection()
                        currentTitle = heading
                    }
                } else {
                    val lines = paragraphText.lines().map { it.trim() }.filter { it.isNotBlank() }
                    for (line in lines) {
                        val lineHeading = Regex("""^\*\*(.+?)\*\*[:]?$""").matchEntire(line)
                        if (lineHeading != null) {
                            flushSection()
                            currentTitle = lineHeading.groupValues[1].trim().removeSuffix(":")
                        } else if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") || line.startsWith("• ")) {
                            val itemText = cleanListItemText(line)
                            if (itemText.isNotBlank()) {
                                currentItems.add(itemText)
                            }
                        } else {
                            currentItems.add(line)
                        }
                    }
                }
            }
        }
    }

    flushSection()

    if (sections.isEmpty() && markdown.isNotBlank()) {
        val lines = markdown.lines().map { it.trim() }.filter { it.isNotBlank() }
        val fallbackItems = lines.map { cleanListItemText(it) }.filter { it.isNotBlank() }
        if (fallbackItems.isNotEmpty()) {
            sections.add(ReleaseNoteSection(title = "What's new", items = fallbackItems))
        }
    }

    return sections
}

/**
 * Converts markdown inline text (bold, code, links, emphasis) into a styled Compose [AnnotatedString].
 */
fun formatMarkdownInline(
    text: String,
    baseColor: Color = CanopyColors.Text,
    accentColor: Color = CanopyColors.Accent,
): AnnotatedString {
    if (text.isBlank()) return AnnotatedString("")

    val flavour = GFMFlavourDescriptor()
    val tree = MarkdownParser(
        flavour = flavour,
        cancellationToken = CancellationToken.NonCancellable,
    ).buildMarkdownTreeFromString(text as CharSequence)
    val builder = AnnotatedString.Builder()

    fun appendNode(node: ASTNode) {
        when (node.type) {
            MarkdownElementTypes.STRONG -> {
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor))
                for (child in node.children) {
                    if (child.type != MarkdownTokenTypes.EMPH) {
                        appendNode(child)
                    }
                }
                builder.pop()
            }
            MarkdownElementTypes.EMPH -> {
                builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                for (child in node.children) {
                    if (child.type != MarkdownTokenTypes.EMPH) {
                        appendNode(child)
                    }
                }
                builder.pop()
            }
            MarkdownElementTypes.CODE_SPAN -> {
                builder.pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        color = accentColor,
                        background = CanopyColors.Chrome.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                    ),
                )
                val raw = node.getTextInNode(text).toString()
                val clean = raw.removePrefix("`").removeSuffix("`")
                builder.append(clean)
                builder.pop()
            }
            MarkdownElementTypes.INLINE_LINK -> {
                val linkTextNode = node.findChildOfType(MarkdownElementTypes.LINK_TEXT)
                val linkText = linkTextNode?.getTextInNode(text)?.toString()?.removePrefix("[")?.removeSuffix("]")
                    ?: node.getTextInNode(text).toString()
                builder.pushStyle(SpanStyle(color = accentColor, textDecoration = TextDecoration.Underline))
                builder.append(linkText)
                builder.pop()
            }
            MarkdownTokenTypes.TEXT -> {
                builder.append(node.getTextInNode(text).toString())
            }
            MarkdownTokenTypes.WHITE_SPACE -> {
                builder.append(" ")
            }
            MarkdownTokenTypes.EOL -> {
                builder.append("\n")
            }
            else -> {
                if (node.children.isEmpty()) {
                    val tokenText = node.getTextInNode(text).toString()
                    if (tokenText != "**" && tokenText != "__" && tokenText != "`") {
                        builder.append(tokenText)
                    }
                } else {
                    for (child in node.children) {
                        appendNode(child)
                    }
                }
            }
        }
    }

    for (child in tree.children) {
        appendNode(child)
    }

    val result = builder.toAnnotatedString()
    return if (result.text.isNotBlank()) result else AnnotatedString(text)
}

internal fun categoryIcon(category: ReleaseNoteCategory): ImageVector = when (category) {
    ReleaseNoteCategory.NEW -> Icons.Rounded.AutoAwesome
    ReleaseNoteCategory.FIXED -> Icons.Rounded.TaskAlt
    ReleaseNoteCategory.CHANGED -> Icons.Rounded.Tune
    ReleaseNoteCategory.SECURITY -> Icons.Rounded.Shield
    ReleaseNoteCategory.OTHER -> Icons.Rounded.Stars
}

@Composable
internal fun categoryColor(category: ReleaseNoteCategory): Color = when (category) {
    ReleaseNoteCategory.NEW -> LocalAccent.current
    ReleaseNoteCategory.FIXED -> Color(0xFF4ADE80)
    ReleaseNoteCategory.CHANGED -> CanopyColors.SecondaryAccent
    ReleaseNoteCategory.SECURITY -> CanopyColors.Warning
    ReleaseNoteCategory.OTHER -> CanopyColors.Eyebrow
}
