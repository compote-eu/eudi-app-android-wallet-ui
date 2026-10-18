/*
 * Copyright (c) 2026 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */

package eu.europa.ec.uilogic.component.wrap

/**
 * Copies an SVG's simple class rules into the `style` attribute of each element that names them, for a
 * renderer that ignores `<style>` blocks.
 *
 * Skia's SVG module — what Coil decodes with off Android — reads presentation attributes and `style`
 * attributes but no stylesheet, so a drawing coloured through classes (Illustrator's default export:
 * `.st0{fill:#BBBDBF;}` and `class="st0"`) renders every shape in the default black. Android decodes with
 * AndroidSVG, which applies stylesheets itself, so this is wired in on iOS only.
 *
 * Deliberately narrow: only rules whose every selector is a single class (`.a`, `.a, .b`) are applied,
 * in source order so that a later rule wins as in CSS, and an element's own `style` stays last so it still
 * wins over them. Any other selector is left to the renderer, and a declaration that cannot be written
 * into an attribute as it stands (one holding `"`, `<` or `&`) is skipped rather than escaped.
 */
internal fun inlineSvgClassStyles(svg: String): String {
    val rules = STYLE_BLOCK.findAll(svg)
        .flatMap { block -> classRules(block.groupValues[1]) }
        .toList()
    if (rules.isEmpty()) return svg

    return CLASS_ATTRIBUTE_TAG.replace(svg) { tag ->
        val classes = tag.groupValues[2].split(WHITESPACE).filter { it.isNotEmpty() }.toSet()
        val declarations = rules.filter { it.className in classes }.flatMap { it.declarations }
        if (declarations.isEmpty()) return@replace tag.value

        val element = tag.value
        val existing = STYLE_ATTRIBUTE.find(element)
        val merged = buildString {
            declarations.forEach { append(it).append(';') }
            existing?.groupValues?.get(2)?.trim()?.let { append(it) }
        }
        if (existing != null) {
            element.replaceRange(existing.range, " style=\"$merged\"")
        } else {
            // Group 1 starts the tag, so the new attribute goes straight after it, before `class`.
            val head = tag.groupValues[1]
            head + " style=\"$merged\"" + element.substring(head.length)
        }
    }
}

private data class ClassRule(val className: String, val declarations: List<String>)

private fun classRules(css: String): List<ClassRule> {
    val text = css.replace(CDATA_MARKERS, "").replace(CSS_COMMENT, "")
    return CSS_RULE.findAll(text).flatMap { rule ->
        val selectors = rule.groupValues[1].split(',').map { it.trim() }
        if (selectors.isEmpty() || !selectors.all { CLASS_SELECTOR.matches(it) }) return@flatMap emptyList()
        val declarations = rule.groupValues[2].split(';')
            .map { it.trim() }
            .filter { it.contains(':') && it.none { char -> char == '"' || char == '<' || char == '&' } }
        if (declarations.isEmpty()) return@flatMap emptyList()
        selectors.map { ClassRule(className = it.removePrefix("."), declarations = declarations) }
    }.toList()
}

private val STYLE_BLOCK = Regex("""<style\b[^>]*>(.*?)</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val CDATA_MARKERS = Regex("""<!\[CDATA\[|]]>""")
private val CSS_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
private val CSS_RULE = Regex("""([^{}]+)\{([^{}]*)}""")
private val CLASS_SELECTOR = Regex("""\.[A-Za-z_][\w-]*""")
private val WHITESPACE = Regex("""\s+""")

/** A start tag carrying a `class` attribute; group 1 is the tag up to the attribute, group 2 its value. */
private val CLASS_ATTRIBUTE_TAG = Regex("""(<[A-Za-z][\w:.-]*\b[^<>]*?)\s+class\s*=\s*["']([^"']*)["'][^<>]*>""")

/** An element's own `style` attribute; group 2 is its value. */
private val STYLE_ATTRIBUTE = Regex("""\s+style\s*=\s*(["'])(.*?)\1""", RegexOption.DOT_MATCHES_ALL)
