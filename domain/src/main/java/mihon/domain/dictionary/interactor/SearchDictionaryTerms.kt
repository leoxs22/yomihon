package mihon.domain.dictionary.interactor

import dev.esnault.wanakana.core.Wanakana
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.repository.DictionaryRepository
import mihon.domain.dictionary.service.Candidate
import mihon.domain.dictionary.service.EnglishDeinflector
import mihon.domain.dictionary.service.InflectionType
import mihon.domain.dictionary.service.JapaneseDeinflector
import java.util.LinkedHashMap

/**
 * Interactor for searching dictionary terms with multilingual support.
 * The parser (Japanese deinflection vs. direct lookup) is chosen automatically
 * by detecting the script of the query text, so no language hint is needed.
 */
class SearchDictionaryTerms(
    private val dictionaryRepository: DictionaryRepository,
) {
    data class FirstWordMatch(
        val word: String,
        val sourceOffset: Int,
        val sourceLength: Int,
        val isDictionaryMatch: Boolean = false,
    )

    private val dictionaryScriptCache = java.util.concurrent.ConcurrentHashMap<Long, Set<Script>>()

    private val punctuationCharSet: Set<Char> get() = PUNCTUATION_CHARS

    /** Script families used to select the right search/segmentation pipeline. */
    private enum class Script { JAPANESE, KOREAN, CHINESE, ENGLISH }

    private fun Script.isNonCjk(): Boolean =
        this != Script.JAPANESE && this != Script.CHINESE && this != Script.KOREAN

    private suspend fun getAllowedScripts(dictionaryIds: List<Long>): Set<Script>? {
        val allowed = mutableSetOf<Script>()
        for (id in dictionaryIds) {
            val scripts = dictionaryScriptCache.getOrPut(id) {
                val dict = dictionaryRepository.getDictionary(id) ?: return@getOrPut emptySet()
                val src = dict.sourceLanguage.orEmpty()

                if (src.isEmpty() || src == "unrestricted") {
                    emptySet()
                } else {
                    val srcScript = mapLanguageToScript(src)
                    setOfNotNull(srcScript)
                }
            }
            if (scripts.isEmpty()) return null // emptySet represents unrestricted, so return null
            allowed.addAll(scripts)
        }
        return allowed.ifEmpty { null }
    }

    private fun mapLanguageToScript(language: String): Script? {
        val code = language.lowercase().substringBefore('-')
        return when (code) {
            "ja", "jpn" -> Script.JAPANESE
            "ko", "kor" -> Script.KOREAN
            "zh", "zho", "chi" -> Script.CHINESE
            "en", "eng" -> Script.ENGLISH
            else -> null
        }
    }

    /**
     * Detects the dominant script of [text] by scanning up to [SCRIPT_DETECT_WINDOW] meaningful characters.
     */
    private fun detectScript(text: String, allowedScripts: Set<Script>?): Script {
        var hasCjk = false
        var scanned = 0
        for (ch in text) {
            if (ch in punctuationCharSet || ch.isWhitespace()) continue
            if (ch in '\u3041'..'\u309F' || ch in '\u30A0'..'\u30FF') return Script.JAPANESE
            if (ch in '\uAC00'..'\uD7A3' || ch in '\u1100'..'\u11FF') return Script.KOREAN
            if (ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF') {
                hasCjk = true
            } else if (ch.isLetter() && ch.code < 0x300) {
                return when {
                    allowedScripts == null -> Script.ENGLISH
                    Script.JAPANESE in allowedScripts && allowedScripts.size == 1 -> Script.JAPANESE
                    else -> Script.ENGLISH
                }
            }
            if (++scanned >= SCRIPT_DETECT_WINDOW) break
        }
        return if (hasCjk) {
            when {
                allowedScripts == null -> Script.JAPANESE
                Script.JAPANESE in allowedScripts -> Script.JAPANESE
                Script.CHINESE in allowedScripts -> Script.CHINESE
                Script.KOREAN in allowedScripts -> Script.KOREAN
                else -> Script.JAPANESE
            }
        } else {
            allowedScripts?.firstOrNull() ?: Script.JAPANESE
        }
    }

    /**
     * Returns the [Script] to use, honouring [override] when it is not [ParserLanguage.AUTO].
     * When [override] is [ParserLanguage.AUTO] the script is detected from [text] automatically.
     */
    private fun resolveScript(text: String, override: ParserLanguage, allowedScripts: Set<Script>?): Script =
        when (override) {
            ParserLanguage.AUTO -> detectScript(text, allowedScripts)
            ParserLanguage.JAPANESE -> Script.JAPANESE
            ParserLanguage.KOREAN -> Script.KOREAN
            ParserLanguage.CHINESE -> Script.CHINESE
            ParserLanguage.ENGLISH -> Script.ENGLISH
        }

    /**
     * Searches for dictionary terms matching [query].
     * The parser is chosen automatically from the query's script unless [parserLanguage]
     * is set to a specific value.
     * For Latin text, direct search runs first; if empty, the Japanese parser is used to cover romaji.
     */
    suspend fun search(
        query: String,
        dictionaryIds: List<Long>,
        parserLanguage: ParserLanguage = ParserLanguage.AUTO,
    ): List<DictionaryTerm> {
        if (dictionaryIds.isEmpty()) return emptyList()

        val normalizedQuery = query.trim { it in punctuationCharSet || it.isWhitespace() }
        val allowedScripts = getAllowedScripts(dictionaryIds)
        val script = resolveScript(normalizedQuery, parserLanguage, allowedScripts)
        val isJapaneseAllowed = allowedScripts == null || Script.JAPANESE in allowedScripts

        val primaryResult = when (script) {
            Script.JAPANESE -> searchJa(normalizedQuery, dictionaryIds)
            Script.ENGLISH -> searchEn(normalizedQuery, dictionaryIds)
            else -> searchExact(normalizedQuery, dictionaryIds)
        }

        return if (primaryResult.isEmpty() && script.isNonCjk() && isJapaneseAllowed) {
            searchJa(normalizedQuery, dictionaryIds)
        } else {
            primaryResult
        }
    }

    private suspend fun searchDeinflected(
        query: String,
        dictionaryIds: List<Long>,
        isJapanese: Boolean,
        deinflect: (String) -> List<Candidate>,
    ): List<DictionaryTerm> {
        val formattedQuery = if (isJapanese) convertToKana(query.trim()) else query.trim()
        if (formattedQuery.isBlank()) return emptyList()

        val candidateQueries = deinflect(formattedQuery)
        if (candidateQueries.isEmpty()) return emptyList()

        val candidatesByTerm = candidateQueries.groupBy { if (isJapanese) it.term else it.term.lowercase() }
        val results = LinkedHashMap<Long, DictionaryTerm>(minOf(candidateQueries.size * 4, MAX_RESULTS * 2))

        candidateLoop@ for (candidate in candidateQueries) {
            val term = candidate.term
            if (term.isBlank()) continue

            val matches = dictionaryRepository.searchTerms(term, dictionaryIds).toMutableList()
            if (!isJapanese && term != term.lowercase()) {
                matches += dictionaryRepository.searchTerms(term.lowercase(), dictionaryIds)
            }

            for (dbTerm in matches) {
                if (dbTerm.id in results) continue

                val lookupKeyExpr = if (isJapanese) dbTerm.expression else dbTerm.expression.lowercase()
                val lookupKeyRead = if (isJapanese) dbTerm.reading else dbTerm.reading.lowercase()

                val candidatesForTerm = candidatesByTerm[lookupKeyExpr]
                    ?: candidatesByTerm[lookupKeyRead]
                    ?: listOf(candidate)

                if (isValidMatch(dbTerm, candidatesForTerm)) {
                    results[dbTerm.id] = dbTerm
                    if (results.size >= MAX_RESULTS) break@candidateLoop
                }
            }
        }
        return results.values.toList()
    }

    /** Japanese search: romaji -> kana conversion + deinflection. */
    private suspend fun searchJa(query: String, dictionaryIds: List<Long>): List<DictionaryTerm> {
        return searchDeinflected(query, dictionaryIds, true) { JapaneseDeinflector.deinflect(it) }
    }

    /** Direct search (no deinflection/kana). Also tries lowercase for case-insensitivity. */
    private suspend fun searchExact(query: String, dictionaryIds: List<Long>): List<DictionaryTerm> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val results = LinkedHashMap<Long, DictionaryTerm>(MAX_RESULTS * 2)
        val matches = dictionaryRepository.searchTerms(trimmed, dictionaryIds)
        for (dbTerm in matches) {
            if (dbTerm.id !in results) {
                results[dbTerm.id] = dbTerm
                if (results.size >= MAX_RESULTS) break
            }
        }

        // Also try lowercase for case-insensitive fallback
        val lowered = trimmed.lowercase()
        if (lowered != trimmed && results.size < MAX_RESULTS) {
            val lowerMatches = dictionaryRepository.searchTerms(lowered, dictionaryIds)
            for (dbTerm in lowerMatches) {
                if (dbTerm.id !in results) {
                    results[dbTerm.id] = dbTerm
                    if (results.size >= MAX_RESULTS) break
                }
            }
        }

        return results.values.toList()
    }

    /** English search: uses EnglishDeinflector to support verb/noun/adjective inflections. */
    private suspend fun searchEn(query: String, dictionaryIds: List<Long>): List<DictionaryTerm> {
        return searchDeinflected(query, dictionaryIds, false) { EnglishDeinflector.deinflect(it) }
    }

    /** Returns the first matched word of [sentence]. See [findFirstWordMatch]. */
    suspend fun findFirstWord(
        sentence: String,
        dictionaryIds: List<Long>,
        parserLanguage: ParserLanguage = ParserLanguage.AUTO,
    ): String = findFirstWordMatch(sentence, dictionaryIds, parserLanguage).word

    /** Segments [sentence] by finding the longest dictionary match prefix. */
    suspend fun findFirstWordMatch(
        sentence: String,
        dictionaryIds: List<Long>,
        parserLanguage: ParserLanguage = ParserLanguage.AUTO,
    ): FirstWordMatch {
        if (sentence.isBlank() || dictionaryIds.isEmpty()) return FirstWordMatch("", 0, 0)

        val allowedScripts = getAllowedScripts(dictionaryIds)
        val script = resolveScript(sentence, parserLanguage, allowedScripts)
        val isJapaneseAllowed = allowedScripts == null || Script.JAPANESE in allowedScripts

        val primaryResult = when (script) {
            Script.JAPANESE -> firstWordJa(sentence, dictionaryIds)
            Script.ENGLISH -> firstWordEn(sentence, dictionaryIds)
            else -> firstWordDirect(sentence, dictionaryIds, script)
        }

        if (!script.isNonCjk() || !isJapaneseAllowed) {
            return primaryResult
        }

        val jaResult = firstWordJa(sentence, dictionaryIds)

        return when {
            jaResult.isDictionaryMatch && !primaryResult.isDictionaryMatch -> jaResult
            primaryResult.isDictionaryMatch && !jaResult.isDictionaryMatch -> primaryResult
            else -> if (jaResult.sourceLength >= primaryResult.sourceLength) jaResult else primaryResult
        }
    }

    private fun stripLeadingPunctuation(sentence: String): Pair<Int, String> {
        val leadingTrimmedCount = sentence.indexOfFirst { it !in punctuationCharSet }
            .let { if (it == -1) sentence.length else it }
        return leadingTrimmedCount to sentence.drop(leadingTrimmedCount)
    }

    private suspend fun findFirstWordDeinflected(
        sentence: String,
        dictionaryIds: List<Long>,
        isJapanese: Boolean,
        maxLength: Int,
        deinflect: (String) -> List<Candidate>,
    ): FirstWordMatch {
        val (leadingTrimmedCount, sanitized) = stripLeadingPunctuation(sentence)
        if (sanitized.isEmpty()) return FirstWordMatch("", leadingTrimmedCount, 0)

        val normalized = if (isJapanese) convertToKana(sanitized) else sanitized
        val actualMaxLength = minOf(normalized.length, maxLength)

        // Descending prefix search
        for (len in actualMaxLength downTo 1) {
            val substring = normalized.take(len)

            if (!isJapanese && len > 1 && substring.last().isWhitespace()) continue

            val candidates = deinflect(substring)
            for (candidate in candidates) {
                val term = candidate.term
                if (term.isBlank()) continue

                var matches = dictionaryRepository.searchTerms(term, dictionaryIds)

                if (!isJapanese && matches.isEmpty() && term.lowercase() != term) {
                    matches = dictionaryRepository.searchTerms(term.lowercase(), dictionaryIds)
                }

                if (matches.isNotEmpty()) {
                    val candidatesForTerm = candidates.filter { c ->
                        c.term == term ||
                            (!isJapanese && c.term.equals(term, ignoreCase = true)) ||
                            matches.any { m -> m.reading.equals(c.term, ignoreCase = !isJapanese) }
                    }

                    if (matches.any { dbTerm -> isValidMatch(dbTerm, candidatesForTerm) }) {
                        val sourceLength = if (isJapanese) mapSourceLength(sanitized, substring) else len
                        return FirstWordMatch(substring, leadingTrimmedCount, sourceLength, true)
                    }
                }
            }
        }

        val fallbackLength = if (isJapanese) {
            mapSourceLength(sanitized, normalized.take(1))
        } else {
            calcFallbackWordLen(sanitized)
        }

        val fallbackWord = if (isJapanese) normalized.take(1) else sanitized.take(fallbackLength)

        return FirstWordMatch(
            word = fallbackWord,
            sourceOffset = leadingTrimmedCount,
            sourceLength = fallbackLength, // fallbackLength equals sourceLength for English
            isDictionaryMatch = false,
        )
    }

    /** Japanese segmentation: strips leading punctuation, converts romaji, then deinflects. */
    private suspend fun firstWordJa(sentence: String, dictionaryIds: List<Long>): FirstWordMatch {
        return findFirstWordDeinflected(
            sentence = sentence,
            dictionaryIds = dictionaryIds,
            isJapanese = true,
            maxLength = MAX_WORD_LENGTH,
            deinflect = { JapaneseDeinflector.deinflect(it) },
        )
    }

    /** English segmentation: strips leading punctuation, extracts bounding word, then deinflects. */
    private suspend fun firstWordEn(sentence: String, dictionaryIds: List<Long>): FirstWordMatch {
        return findFirstWordDeinflected(
            sentence = sentence,
            dictionaryIds = dictionaryIds,
            isJapanese = false,
            maxLength = 40,
            deinflect = { EnglishDeinflector.deinflect(it) },
        )
    }

    /** Direct segmentation (Character-by-character longest match for non-Japanese scripts) */
    private suspend fun firstWordDirect(sentence: String, dictionaryIds: List<Long>, script: Script): FirstWordMatch {
        val (leadingTrimmedCount, sanitized) = stripLeadingPunctuation(sentence)
        if (sanitized.isEmpty()) return FirstWordMatch("", leadingTrimmedCount, 0)

        val maxLength = minOf(sanitized.length, 40)

        for (len in maxLength downTo 1) {
            val substring = sanitized.take(len)

            // Optimization for spaces at the end: don't look up if ending in space, unless it's only 1 char
            if (len > 1 && substring.last().isWhitespace()) continue

            val matches = dictionaryRepository.searchTerms(substring, dictionaryIds)
            if (matches.isNotEmpty()) {
                return FirstWordMatch(substring, leadingTrimmedCount, len, true)
            }

            if (script == Script.ENGLISH) {
                val lowered = substring.lowercase()
                if (lowered != substring) {
                    val lowerMatches = dictionaryRepository.searchTerms(lowered, dictionaryIds)
                    if (lowerMatches.isNotEmpty()) {
                        return FirstWordMatch(substring, leadingTrimmedCount, len, true)
                    }
                }
            }
        }

        // No match found: calculate fallback word length based on script boundaries
        val fallbackLength = if (script.isNonCjk()) {
            calcFallbackWordLen(sanitized)
        } else {
            1
        }

        val fallbackWord = sanitized.take(fallbackLength)
        return FirstWordMatch(fallbackWord, leadingTrimmedCount, fallbackLength, false)
    }

    private fun isBoundary(c: Char): Boolean =
        c.isWhitespace() || (c in punctuationCharSet && c != '\'' && c != '\u2019')

    private fun calcFallbackWordLen(sanitized: String): Int {
        if (sanitized.isEmpty()) return 0

        val boundaryIndex = sanitized.indexOfFirst { isBoundary(it) }
        return when (boundaryIndex) {
            -1 -> sanitized.length
            0 -> 1
            else -> boundaryIndex
        }
    }

    /** Validates that a dictionary term matches at least one candidate condition. */
    private fun isValidMatch(term: DictionaryTerm, candidates: List<Candidate>): Boolean {
        val dbRuleMask = InflectionType.parseRules(term.rules)

        for (candidate in candidates) {
            if (candidate.conditions == InflectionType.ALL) return true
            if (dbRuleMask == InflectionType.UNSPECIFIED) return true
            if (InflectionType.conditionsMatch(candidate.conditions, dbRuleMask)) return true
        }
        return false
    }

    suspend fun getTermMeta(
        expressions: List<String>,
        dictionaryIds: List<Long>,
    ): Map<String, List<DictionaryTermMeta>> =
        expressions.associateWith { expression ->
            dictionaryRepository.getTermMetaForExpression(expression, dictionaryIds)
        }

    private fun convertToKana(input: String): String {
        return input.trim().let {
            if (it.any(Char::isLatinLetter) || Wanakana.isRomaji(it) || Wanakana.isMixed(it)) {
                Wanakana.toKana(it)
            } else {
                it
            }
        }
    }

    /**
     * Maps the length of the normalized prefix back to the source string, accounting for romaji
     */
    private fun mapSourceLength(source: String, normalizedPrefix: String): Int {
        if (normalizedPrefix.isEmpty()) return 0

        for (index in 1..source.length) {
            val convertedPrefix = convertToKana(source.take(index))
            if (convertedPrefix.length >= normalizedPrefix.length &&
                convertedPrefix.startsWith(normalizedPrefix)
            ) {
                return index
            }
        }

        return minOf(source.length, normalizedPrefix.length)
    }
}

private fun Char.isLatinLetter(): Boolean =
    (this in 'a'..'z') || (this in 'A'..'Z')

private const val MAX_RESULTS = 100
private const val MAX_WORD_LENGTH = 20
private const val SCRIPT_DETECT_WINDOW = 30

private val PUNCTUATION_CHARS: Set<Char> = setOf(
    '「', '」', '『', '』', '（', '）', '(', ')', '【', '】',
    '〔', '〕', '《', '》', '〈', '〉',
    '・', '、', '。', '！', '？', '：', '；',
    ' ', '\t', '\n', '\r', '\u3000', // whitespace characters
    '\u201C', '\u201D', // double quotation marks
    '\u2018', '\u2019', // single quotation marks
    '"', '\'', // ASCII quotes
    '.', ',', '…', // punctuation and ellipsis (U+2026)
    '-', '\u2010', '\u2013', '\u2014', // hyphen variants
    '«', '»', '<', '>', '[', ']', '{', '}', '/', '\\',
    '〜', '\u301C', '\uFF5E', // tildes / wave dash
)
