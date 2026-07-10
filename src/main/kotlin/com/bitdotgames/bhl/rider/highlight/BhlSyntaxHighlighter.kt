package com.bitdotgames.bhl.rider.highlight

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

/**
 * Baseline (lexer-based) highlighting: comments and strings. Everything else is colored by
 * the LSP semantic tokens layered on top.
 */
class BhlSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = BhlHighlightingLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = when (tokenType) {
        BhlTokenTypes.LINE_COMMENT -> LINE_COMMENT_KEYS
        BhlTokenTypes.BLOCK_COMMENT -> BLOCK_COMMENT_KEYS
        BhlTokenTypes.STRING -> STRING_KEYS
        else -> TextAttributesKey.EMPTY_ARRAY
    }

    companion object {
        private val LINE_COMMENT_KEYS = arrayOf(DefaultLanguageHighlighterColors.LINE_COMMENT)
        private val BLOCK_COMMENT_KEYS = arrayOf(DefaultLanguageHighlighterColors.BLOCK_COMMENT)
        private val STRING_KEYS = arrayOf(DefaultLanguageHighlighterColors.STRING)
    }
}

class BhlSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        BhlSyntaxHighlighter()
}
