package com.bitdotgames.bhl.rider

import com.intellij.lang.Language

class BhlLanguage private constructor() : Language("BHL") {
    companion object {
        val INSTANCE = BhlLanguage()
    }
}
