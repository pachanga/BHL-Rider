package com.bitdotgames.bhl.rider

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

/**
 * BHL-specific attribute keys for LSP semantic tokens whose platform-default mapping is
 * visually plain in stock IntelliJ color schemes (types/classes, namespaces, variables,
 * parameters — and functions on light schemes). Colors are supplied per scheme via the
 * `additionalTextAttributes` files in resources/colorSchemes; the fallbacks below apply if
 * a scheme defines none.
 */
object BhlTextAttributes {
    val TYPE: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("BHL_TYPE", DefaultLanguageHighlighterColors.CLASS_NAME)

    // Deliberately distinct from TYPE's color family so namespace and type/class names aren't
    // confusable at a glance — VSCode's Dark+/Light+ themes color "namespace" as a control-keyword
    // purple rather than reusing the type teal, for the same reason.
    val NAMESPACE: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("BHL_NAMESPACE", DefaultLanguageHighlighterColors.KEYWORD)

    val FUNCTION: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("BHL_FUNCTION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)

    val VARIABLE: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("BHL_VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE)

    val PARAMETER: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("BHL_PARAMETER", DefaultLanguageHighlighterColors.PARAMETER)
}
