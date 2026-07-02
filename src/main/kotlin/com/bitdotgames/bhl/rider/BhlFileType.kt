package com.bitdotgames.bhl.rider

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object BhlFileType : LanguageFileType(BhlLanguage.INSTANCE) {
    override fun getName(): String = "BHL File"

    override fun getDescription(): String = "BHL script"

    override fun getDefaultExtension(): String = "bhl"

    override fun getIcon(): Icon = BhlIcons.FILE
}
