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

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stars
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.sfg.orchard.connect.BuildConfig
import dev.sfg.orchard.mobile.MobileUpdateMetadata
import dev.sfg.orchard.mobile.UpdateState
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
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
    val parsedTree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)

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
    val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(text)
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

private fun categoryIcon(category: ReleaseNoteCategory): ImageVector = when (category) {
    ReleaseNoteCategory.NEW -> Icons.Rounded.AutoAwesome
    ReleaseNoteCategory.FIXED -> Icons.Rounded.TaskAlt
    ReleaseNoteCategory.CHANGED -> Icons.Rounded.Tune
    ReleaseNoteCategory.SECURITY -> Icons.Rounded.Shield
    ReleaseNoteCategory.OTHER -> Icons.Rounded.Stars
}

@Composable
private fun categoryColor(category: ReleaseNoteCategory): Color = when (category) {
    ReleaseNoteCategory.NEW -> LocalAccent.current
    ReleaseNoteCategory.FIXED -> Color(0xFF4ADE80)
    ReleaseNoteCategory.CHANGED -> CanopyColors.SecondaryAccent
    ReleaseNoteCategory.SECURITY -> CanopyColors.Warning
    ReleaseNoteCategory.OTHER -> CanopyColors.Eyebrow
}

/**
 * Modern in-app update prompt matching Orchard Desktop's changelog dialog experience.
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    onInstall: (MobileUpdateMetadata) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        UpdateState.Idle -> Unit

        is UpdateState.Available -> {
            val metadata = state.metadata
            val context = LocalContext.current
            val currentVersion = remember(context) {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
                    .getOrNull() ?: BuildConfig.VERSION_NAME
            }
            val sections = remember(metadata.releaseNotes) { parseReleaseNoteSections(metadata.releaseNotes) }
            val totalChanges = remember(sections) { sections.sumOf { it.items.size } }

            Dialog(
                onDismissRequest = onDismiss,
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = CanopyColors.Surface,
                    border = BorderStroke(1.dp, CanopyColors.RuleStrong),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 24.dp)
                        .widthIn(max = 440.dp)
                        .heightIn(max = 680.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                    ) {
                        // Dialog Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .background(LocalAccent.current.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Rounded.SystemUpdate,
                                        contentDescription = null,
                                        tint = LocalAccent.current,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        "Orchard update",
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                        color = CanopyColors.Text,
                                    )
                                    Text(
                                        if (metadata.publishedAt.isNotBlank()) "Published ${metadata.publishedAt}" else "Update available",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CanopyColors.Muted,
                                    )
                                }
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "Close update prompt",
                                    tint = CanopyColors.Muted,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // Versions stats panel
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = CanopyColors.Canvas,
                            border = BorderStroke(1.dp, CanopyColors.Rule),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column {
                                    Text(
                                        "INSTALLED",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.8.sp,
                                        ),
                                        color = CanopyColors.Eyebrow,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "v$currentVersion",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = CanopyColors.MutedStrong,
                                    )
                                }

                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowForward,
                                    contentDescription = null,
                                    tint = CanopyColors.Muted.copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp),
                                )

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        "AVAILABLE",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.8.sp,
                                        ),
                                        color = LocalAccent.current,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "v${metadata.version}",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = CanopyColors.Text,
                                        )
                                        if (metadata.codename.isNotBlank()) {
                                            Spacer(Modifier.width(6.dp))
                                            Surface(
                                                color = LocalAccent.current.copy(alpha = 0.14f),
                                                shape = RoundedCornerShape(6.dp),
                                            ) {
                                                Text(
                                                    metadata.codename,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    color = LocalAccent.current,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // Release Notes Section
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 2.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Release notes",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp,
                                ),
                                color = CanopyColors.Eyebrow,
                            )
                            if (totalChanges > 0) {
                                Surface(
                                    color = CanopyColors.Canvas,
                                    shape = CircleShape,
                                ) {
                                    Text(
                                        if (totalChanges == 1) "1 change" else "$totalChanges changes",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                        color = CanopyColors.Muted,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        // Scrollable Release Notes Container
                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            if (sections.isNotEmpty()) {
                                sections.forEachIndexed { index, section ->
                                    if (index > 0) Spacer(Modifier.height(10.dp))
                                    ReleaseNoteSectionCard(section)
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = CanopyColors.Canvas.copy(alpha = 0.6f),
                                    border = BorderStroke(1.dp, CanopyColors.Rule),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "Includes general performance improvements and bug fixes.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = CanopyColors.Muted,
                                        modifier = Modifier.padding(14.dp),
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(18.dp))

                        // Actions Footer
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = onDismiss,
                                shape = CircleShape,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp),
                            ) {
                                Text(
                                    "Not now",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = CanopyColors.Muted,
                                )
                            }

                            Button(
                                onClick = { onInstall(metadata) },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = LocalAccent.current,
                                    contentColor = Color.Black,
                                ),
                                modifier = Modifier
                                    .weight(1.3f)
                                    .height(46.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Update now",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                )
                            }
                        }
                    }
                }
            }
        }

        is UpdateState.Downloading -> {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 0.92f,
                targetValue = 1.08f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "scale",
            )

            Dialog(
                onDismissRequest = {},
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = CanopyColors.Surface,
                    border = BorderStroke(1.dp, CanopyColors.RuleStrong),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .widthIn(max = 400.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .scale(pulseScale)
                                .background(LocalAccent.current.copy(alpha = 0.16f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.CloudDownload,
                                contentDescription = null,
                                tint = LocalAccent.current,
                                modifier = Modifier.size(28.dp),
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        Text(
                            "Downloading update",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = CanopyColors.Text,
                        )

                        Spacer(Modifier.height(4.dp))

                        Text(
                            "Orchard ${state.version}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = LocalAccent.current,
                        )

                        Spacer(Modifier.height(12.dp))

                        Text(
                            "The installer will open automatically when finished. You can continue listening to music in the background.",
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                            color = CanopyColors.Muted,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(Modifier.height(18.dp))

                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape),
                            color = LocalAccent.current,
                            trackColor = CanopyColors.Canvas,
                        )

                        Spacer(Modifier.height(20.dp))

                        TextButton(
                            onClick = onDismiss,
                            shape = CircleShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                        ) {
                            Text(
                                "Hide",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = CanopyColors.Muted,
                            )
                        }
                    }
                }
            }
        }

        is UpdateState.Failed -> {
            Dialog(
                onDismissRequest = onDismiss,
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = CanopyColors.Surface,
                    border = BorderStroke(1.dp, CanopyColors.RuleStrong),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .widthIn(max = 400.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(CanopyColors.Danger.copy(alpha = 0.16f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = CanopyColors.Danger,
                                modifier = Modifier.size(28.dp),
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        Text(
                            "Update failed",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = CanopyColors.Text,
                        )

                        Spacer(Modifier.height(8.dp))

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = CanopyColors.Canvas,
                            border = BorderStroke(1.dp, CanopyColors.Danger.copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "${state.reason} Orchard ${state.version} was not installed.",
                                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                                color = CanopyColors.MutedStrong,
                                modifier = Modifier.padding(14.dp),
                            )
                        }

                        Spacer(Modifier.height(20.dp))

                        Button(
                            onClick = onDismiss,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LocalAccent.current,
                                contentColor = Color.Black,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                        ) {
                            Text(
                                "OK",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
            }
        }

        is UpdateState.ReadyToInstall -> {
            Dialog(
                onDismissRequest = onDismiss,
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = CanopyColors.Surface,
                    border = BorderStroke(1.dp, CanopyColors.RuleStrong),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .widthIn(max = 400.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(LocalAccent.current.copy(alpha = 0.16f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = LocalAccent.current,
                                modifier = Modifier.size(28.dp),
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        Text(
                            "Update ready to install",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = CanopyColors.Text,
                        )

                        Spacer(Modifier.height(6.dp))

                        Text(
                            "Orchard ${state.version} has finished downloading and can be installed now.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CanopyColors.Muted,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(Modifier.height(20.dp))

                        Button(
                            onClick = onDismiss,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LocalAccent.current,
                                contentColor = Color.Black,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                        ) {
                            Text(
                                "Install now",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Clean card rendering an individual release note category section with header, icon, and bullet items.
 */
@Composable
private fun ReleaseNoteSectionCard(section: ReleaseNoteSection) {
    val categoryColor = categoryColor(section.category)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CanopyColors.Canvas.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, CanopyColors.Rule),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(categoryColor.copy(alpha = 0.16f), RoundedCornerShape(7.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            categoryIcon(section.category),
                            contentDescription = null,
                            tint = categoryColor,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        section.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = CanopyColors.Text,
                    )
                }
                Surface(
                    color = CanopyColors.Surface,
                    shape = CircleShape,
                ) {
                    Text(
                        "${section.items.size}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = CanopyColors.Muted,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                section.items.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 7.dp, end = 8.dp)
                                .size(5.dp)
                                .background(categoryColor.copy(alpha = 0.8f), CircleShape),
                        )
                        Text(
                            text = formatMarkdownInline(item, CanopyColors.Text, LocalAccent.current),
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 19.sp),
                            color = CanopyColors.MutedStrong,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Displays release notes for the current installed version or past releases.
 */
@Composable
fun ReleaseNotesDialog(
    version: String = BuildConfig.VERSION_NAME,
    codename: String = BuildConfig.CODENAME,
    releaseNotes: String = dev.sfg.orchard.mobile.MobileChangelog.CURRENT_RELEASE_NOTES,
    onDismiss: () -> Unit,
) {
    val sections = remember(releaseNotes) { parseReleaseNoteSections(releaseNotes) }
    val totalChanges = remember(sections) { sections.sumOf { it.items.size } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = CanopyColors.Surface,
            border = BorderStroke(1.dp, CanopyColors.RuleStrong),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .widthIn(max = 440.dp)
                .heightIn(max = 680.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(LocalAccent.current.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = LocalAccent.current,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "What's new",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = CanopyColors.Text,
                            )
                            Text(
                                if (codename.isNotBlank()) "Orchard $version \"$codename\"" else "Orchard $version",
                                style = MaterialTheme.typography.bodySmall,
                                color = CanopyColors.Muted,
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Close release notes",
                            tint = CanopyColors.Muted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Current version card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CanopyColors.Canvas,
                    border = BorderStroke(1.dp, CanopyColors.Rule),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                "CURRENT VERSION",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                ),
                                color = CanopyColors.Eyebrow,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "v$version",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = CanopyColors.Text,
                            )
                        }

                        if (codename.isNotBlank()) {
                            Surface(
                                color = LocalAccent.current.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    codename,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                                    color = LocalAccent.current,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Release Notes Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Release notes",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                        ),
                        color = CanopyColors.Eyebrow,
                    )
                    if (totalChanges > 0) {
                        Surface(
                            color = CanopyColors.Canvas,
                            shape = CircleShape,
                        ) {
                            Text(
                                if (totalChanges == 1) "1 change" else "$totalChanges changes",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = CanopyColors.Muted,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Scrollable Release Notes
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (sections.isNotEmpty()) {
                        sections.forEachIndexed { index, section ->
                            if (index > 0) Spacer(Modifier.height(10.dp))
                            ReleaseNoteSectionCard(section)
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = CanopyColors.Canvas.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, CanopyColors.Rule),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Includes general performance improvements and bug fixes.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CanopyColors.Muted,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                Button(
                    onClick = onDismiss,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LocalAccent.current,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                ) {
                    Text(
                        "Done",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}
