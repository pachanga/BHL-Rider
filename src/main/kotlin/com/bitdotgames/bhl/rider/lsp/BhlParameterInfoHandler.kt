@file:Suppress("UnstableApiUsage")

package com.bitdotgames.bhl.rider.lsp

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.editor.Document
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.SignatureInformation

/**
 * Bridges IntelliJ's parameter-info popup (Ctrl+P / auto-popup on `(` and `,`) to the BHL
 * server's `textDocument/signatureHelp`. Rider's built-in LSP client (as of 2025.1) does not
 * implement signature help at all — it does not even advertise the client capability — so
 * the plugin asks the server directly and renders the result.
 */
class BhlParameterInfoHandler : ParameterInfoHandler<PsiElement, SignatureInformation> {

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        val help = requestSignatureHelp(context.file, context.editor.document, context.offset)
        val signatures = help?.signatures.orEmpty()
        if (help == null || signatures.isEmpty()) return null
        context.itemsToShow = signatures.toTypedArray()
        return context.file.findElementAt(context.offset) ?: context.file
    }

    override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) {
        context.showHint(element, context.offset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? =
        context.file.findElementAt(context.offset) ?: context.parameterOwner

    override fun updateParameterInfo(parameterOwner: PsiElement, context: UpdateParameterInfoContext) {
        val help = requestSignatureHelp(context.file, context.editor.document, context.offset)
        if (help == null || help.signatures.isNullOrEmpty()) {
            context.removeHint()
            return
        }
        context.setCurrentParameter(help.activeParameter ?: 0)
    }

    override fun updateUI(signature: SignatureInformation?, context: ParameterInfoUIContext) {
        if (signature == null) {
            context.isUIComponentEnabled = false
            return
        }
        val label = signature.label.orEmpty()
        var highlightStart = -1
        var highlightEnd = -1
        val parameters = signature.parameters.orEmpty()
        val index = context.currentParameterIndex
        if (index in parameters.indices) {
            val paramLabel = parameters[index].label
            if (paramLabel.isRight) {
                // [start, end) offsets into the signature label
                highlightStart = paramLabel.right.first
                highlightEnd = paramLabel.right.second
            } else {
                val text = paramLabel.left.orEmpty()
                val at = label.indexOf(text)
                if (at >= 0 && text.isNotEmpty()) {
                    highlightStart = at
                    highlightEnd = at + text.length
                }
            }
        }
        context.setupUIComponentPresentation(
            label,
            highlightStart,
            highlightEnd,
            !context.isUIComponentEnabled,
            false,
            false,
            context.defaultParameterColor,
        )
    }

    private fun requestSignatureHelp(file: PsiFile, document: Document, offset: Int): SignatureHelp? {
        val virtualFile = file.virtualFile ?: return null
        val server = LspServerManager.getInstance(file.project)
            .getServersForProvider(BhlLspServerSupportProvider::class.java)
            .firstOrNull { it.state == LspServerState.Running && it.descriptor.isSupportedFile(virtualFile) }
            ?: return null
        val position = offsetToPosition(document, offset)
        val params = SignatureHelpParams(server.getDocumentIdentifier(virtualFile), position)
        return runCatching {
            server.sendRequestSync(REQUEST_TIMEOUT_MS) { it.textDocumentService.signatureHelp(params) }
        }.getOrNull()
    }

    private fun offsetToPosition(document: Document, offset: Int): Position {
        val safeOffset = offset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(safeOffset)
        return Position(line, safeOffset - document.getLineStartOffset(line))
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 2_000
    }
}
