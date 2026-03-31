package mihon.data.dictionary

import java.util.concurrent.ConcurrentHashMap
import mihon.domain.dictionary.model.DictionaryIdPartition
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.partitionDictionaryIdsByBackend
import mihon.domain.dictionary.repository.DictionaryRepository
import mihon.domain.dictionary.service.DictionaryLookupMatch
import mihon.domain.dictionary.service.DictionarySearchBackend
import mihon.domain.dictionary.service.DictionarySearchEntry
import mihon.domain.dictionary.service.DictionarySearchGateway

class DictionarySearchGatewayImpl(
    private val dictionaryRepository: DictionaryRepository,
    private val dictionarySearchBackend: DictionarySearchBackend,
) : DictionarySearchGateway {

    private val hoshiTermMetaCache = ConcurrentHashMap<String, List<DictionaryTermMeta>>()

    override suspend fun exactSearch(
        expression: String,
        dictionaryIds: List<Long>,
    ): List<DictionarySearchEntry> {
        if (dictionaryIds.isEmpty()) return emptyList()

        val partition = partitionDictionaryIds(dictionaryIds)
        return buildList {
            if (partition.legacyIds.isNotEmpty()) {
                addAll(
                    dictionaryRepository.searchTerms(expression, partition.legacyIds)
                        .map { DictionarySearchEntry(it, emptyList()) },
                )
            }
            if (partition.hoshiIds.isNotEmpty()) {
                val entries = dictionarySearchBackend.exactSearch(expression, partition.hoshiIds)
                cacheBackendEntries(entries)
                addAll(entries)
            }
        }
    }

    override suspend fun lookup(
        text: String,
        dictionaryIds: List<Long>,
        maxResults: Int,
    ): List<DictionaryLookupMatch> {
        if (dictionaryIds.isEmpty()) return emptyList()

        val hoshiIds = partitionDictionaryIds(dictionaryIds).hoshiIds
        if (hoshiIds.isEmpty()) return emptyList()

        return dictionarySearchBackend.lookup(text, hoshiIds, maxResults).also { matches ->
            cacheBackendEntries(
                matches.map { match ->
                    DictionarySearchEntry(
                        term = match.term,
                        termMeta = match.termMeta,
                    )
                },
            )
        }
    }

    override suspend fun getTermMeta(
        expressions: List<String>,
        dictionaryIds: List<Long>,
    ): Map<String, List<DictionaryTermMeta>> {
        if (expressions.isEmpty()) return emptyMap()
        if (dictionaryIds.isEmpty()) {
            return expressions.associateWith { emptyList() }
        }

        val partition = partitionDictionaryIds(dictionaryIds)
        val requestedIds = dictionaryIds.toSet()

        return expressions.associateWith { expression ->
            buildList {
                if (partition.legacyIds.isNotEmpty()) {
                    addAll(dictionaryRepository.getTermMetaForExpression(expression, partition.legacyIds))
                }
                if (partition.hoshiIds.isNotEmpty()) {
                    addAll(loadHoshiTermMeta(expression, partition.hoshiIds, requestedIds))
                }
            }
        }
    }

    private suspend fun partitionDictionaryIds(dictionaryIds: List<Long>): DictionaryIdPartition {
        val dictionariesById = dictionaryRepository.getAllDictionaries().associateBy { it.id }
        return partitionDictionaryIdsByBackend(dictionaryIds, dictionariesById)
    }

    private fun cacheBackendEntries(entries: List<DictionarySearchEntry>) {
        entries.forEach { entry ->
            val cacheKey = "${entry.term.dictionaryId}|${entry.term.expression}"
            val merged = hoshiTermMetaCache[cacheKey].orEmpty() + entry.termMeta
            hoshiTermMetaCache[cacheKey] = merged.distinctBy(::metaKey)
        }
    }

    private fun cachedHoshiTermMeta(expression: String, dictionaryIds: List<Long>): List<DictionaryTermMeta> {
        return dictionaryIds.flatMap { dictionaryId ->
            hoshiTermMetaCache["$dictionaryId|$expression"].orEmpty()
        }
    }

    private suspend fun loadHoshiTermMeta(
        expression: String,
        requestedDictionaryIds: List<Long>,
        requestedIds: Set<Long>,
    ): List<DictionaryTermMeta> {
        cachedHoshiTermMeta(expression, requestedDictionaryIds)
            .filter { it.dictionaryId in requestedIds }
            .takeIf { it.isNotEmpty() }
            ?.let { return it.distinctBy(::metaKey) }

        val exactEntries = dictionarySearchBackend.exactSearch(expression, requestedDictionaryIds)
        cacheBackendEntries(exactEntries)
        exactEntries
            .flatMap { it.termMeta }
            .filter { it.dictionaryId in requestedIds }
            .takeIf { it.isNotEmpty() }
            ?.let { return it.distinctBy(::metaKey) }

        val lookupEntries = dictionarySearchBackend.lookup(
            text = expression,
            dictionaryIds = requestedDictionaryIds,
            maxResults = 100,
        ).map { match ->
            DictionarySearchEntry(
                term = match.term,
                termMeta = match.termMeta,
            )
        }
        cacheBackendEntries(lookupEntries)
        return lookupEntries
            .flatMap { it.termMeta }
            .filter { it.dictionaryId in requestedIds }
            .distinctBy(::metaKey)
    }

    private fun metaKey(meta: DictionaryTermMeta): String {
        return "${meta.dictionaryId}|${meta.expression}|${meta.mode}|${meta.data}"
    }
}
