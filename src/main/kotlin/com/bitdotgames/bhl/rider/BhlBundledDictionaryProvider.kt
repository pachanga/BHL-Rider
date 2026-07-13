package com.bitdotgames.bhl.rider

import com.intellij.spellchecker.BundledDictionaryProvider

/**
 * Ships a word list of BHL keywords (see grammar/bhlLexer.g) and primitive type names so the
 * IDE spell checker doesn't flag them as typos. `.bhl` files have no dedicated PSI/language
 * for spell-check scoping (see BhlParameterInfoHandler for why), so without this every
 * keyword in every `.bhl` file would otherwise be checked as English prose.
 */
class BhlBundledDictionaryProvider : BundledDictionaryProvider {
    override fun getBundledDictionaries(): Array<String> = arrayOf("/dictionaries/bhl.dic")
}
