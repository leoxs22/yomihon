package eu.kanade.domain.dictionary

import mihon.domain.dictionary.interactor.ParserLanguage
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class DictionaryPreferences(
    private val preferenceStore: PreferenceStore,
) {
    /**
     * When set to anything other than [ParserLanguage.AUTO] every search call will use
     * that specific parse pipeline regardless of what script the query text contains.
     */
    fun parserLanguageOverride() = preferenceStore.getEnum(
        key = "pref_dictionary_parser_language_override",
        defaultValue = ParserLanguage.AUTO,
    )
}
