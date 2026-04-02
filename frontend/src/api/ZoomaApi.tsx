
let apiUrl = process.env.REACT_APP_APIURL
if(apiUrl?.endsWith('/')) {
    apiUrl = apiUrl.slice(0, -1)
}

export interface SearchProperty {
}

export interface SearchParams {
    properties: { 
        textToMap: string
        propertyType: string
    }[]

    doNotSearchDatasources:boolean
    requiredSources:string[]
    preferredSources:string[]

    doNotSearchOntologies:boolean
    
    // Target ontologies for semantic search
    targetOntologies:string[]
    includeOtherOntologies:boolean
    useLlmSearch:boolean
    llmModel:string
}

export interface SearchResult {
    propertyType:string
    textToMap:string
    ontologyTermLabel:string
    ontologyTermSynonyms:string
    mappingConfidence:string
    ontologyTermID:string
    ontologyURI:string
    datasource:string
    mappingProvenance?: MappingProvenanceStep[]
    error?: string
}

// V3 API response types
export interface V3MapResponse {
    mappings: V3PropertyMapping[]
}

export interface V3PropertyMapping {
    propertyType: string
    textToMap: string
    candidates: V3MappingCandidate[]
    error?: string
}

export interface V3MappingCandidate {
    termId: string
    label: string
    synonyms: string[]
    ontology: string
    uri: string
    confidence: number | null
    datasource: string
    mappingProvenance: MappingProvenanceStep[]
}

export interface MappingProvenanceStep {
    method: string       // "lexical" | "semantic" | "curated" | "cross_reference"
    matchType?: string   // "exact_label" | "synonym" | "embedding_similarity" | "skos:exactMatch"
    source?: string      // "atlas" | "gwas" | "ols"
    model?: string       // embedding model if semantic
    confidence?: number
    input: string
    matchedText?: string
    similarity?: number
    target: string
}

export interface Datasource {
    type:string
    name:string
    longName:string|undefined
    description:string
    uri:string
    title:string
}

export interface Model {
    name: string
    canEmbed: boolean
    hasPrecomputedEmbeddings: boolean
}

// Alias for backward compatibility
export type LlmModel = Model;

export async function getModels():Promise<Model[]> {
    try {
        let res = await fetch(apiUrl + '/v3/api/models', {
            method: 'GET',
            headers: {
                'accept': 'application/json'
            }
        })
        if (!res.ok) {
            console.warn('Failed to fetch models:', res.status)
            return []
        }
        return (await res.json()) as Model[]
    } catch (e) {
        console.warn('Failed to fetch models:', e)
        return []
    }
}

// Alias for backward compatibility
export const getLlmModels = getModels;
export const getEmbeddingModels = getModels;

export interface OntologyPreset {
    name: string
    description: string
    ontologies: string[]
}

export async function getOntologyPresets():Promise<OntologyPreset[]> {
    try {
        let res = await fetch(apiUrl + '/v3/api/ontology-presets', {
            method: 'GET',
            headers: {
                'accept': 'application/json'
            }
        })
        if (!res.ok) {
            console.warn('Failed to fetch ontology presets:', res.status)
            return []
        }
        return (await res.json()) as OntologyPreset[]
    } catch (e) {
        console.warn('Failed to fetch ontology presets:', e)
        return []
    }
}

function buildFilter(params: SearchParams) {
    return {
        required: params.doNotSearchDatasources ? [] : params.requiredSources,
        preferred: params.doNotSearchDatasources ? [] : params.preferredSources,
    }
}

/**
 * Re-map a single property, excluding specific term IDs (for thumbs-down).
 */
export async function remapOne(
    params: SearchParams,
    textToMap: string,
    propertyType: string,
    excludeTermIds: string[]
): Promise<SearchResult[]> {
    const requestBody = {
        properties: [{ textToMap, propertyType }],
        model: params.llmModel || 'text-embedding-3-small',
        targetOntologies: params.targetOntologies || [],
        includeOtherOntologies: params.includeOtherOntologies,
        filter: buildFilter(params),
        excludeTermIds
    }

    let res = await fetch(apiUrl + '/v3/api/services/map', {
        method: 'POST',
        body: JSON.stringify(requestBody),
        headers: {
            'content-type': 'application/json',
            'accept': 'application/json'
        }
    })

    if (!res.ok) {
        throw new Error(`HTTP ${res.status}: ${await res.text()}`)
    }

    const v3Response = (await res.json()) as V3MapResponse
    const results: SearchResult[] = []
    for (const mapping of v3Response.mappings) {
        if (mapping.error) {
            results.push({
                propertyType: mapping.propertyType,
                textToMap: mapping.textToMap,
                ontologyTermLabel: '',
                ontologyTermSynonyms: '',
                mappingConfidence: '',
                ontologyTermID: '',
                ontologyURI: '',
                datasource: '',
                error: mapping.error
            })
            continue
        }
        for (const candidate of mapping.candidates) {
            results.push({
                propertyType: mapping.propertyType,
                textToMap: mapping.textToMap,
                ontologyTermLabel: candidate.label,
                ontologyTermSynonyms: candidate.synonyms?.join('|') || '',
                mappingConfidence: candidate.confidence?.toString() || '',
                ontologyTermID: candidate.termId,
                ontologyURI: candidate.uri,
                datasource: candidate.datasource,
                mappingProvenance: candidate.mappingProvenance
            })
        }
    }
    return results
}

/**
 * Fetch all candidate mappings for a single property (light dedup only).
 * Used for the alternative mappings modal.
 */
export async function fetchAllCandidates(
    params: SearchParams,
    textToMap: string,
    propertyType: string,
    excludeTermIds: string[]
): Promise<SearchResult[]> {
    const requestBody = {
        properties: [{ textToMap, propertyType }],
        model: params.llmModel || 'text-embedding-3-small',
        targetOntologies: params.targetOntologies || [],
        includeOtherOntologies: params.includeOtherOntologies,
        filter: buildFilter(params),
        excludeTermIds,
        returnAll: true
    }

    let res = await fetch(apiUrl + '/v3/api/services/map', {
        method: 'POST',
        body: JSON.stringify(requestBody),
        headers: {
            'content-type': 'application/json',
            'accept': 'application/json'
        }
    })

    if (!res.ok) {
        throw new Error(`HTTP ${res.status}: ${await res.text()}`)
    }

    const v3Response = (await res.json()) as V3MapResponse
    const results: SearchResult[] = []
    for (const mapping of v3Response.mappings) {
        if (mapping.error) {
            results.push({
                propertyType: mapping.propertyType,
                textToMap: mapping.textToMap,
                ontologyTermLabel: '',
                ontologyTermSynonyms: '',
                mappingConfidence: '',
                ontologyTermID: '',
                ontologyURI: '',
                datasource: '',
                error: mapping.error
            })
            continue
        }
        for (const candidate of mapping.candidates) {
            results.push({
                propertyType: mapping.propertyType,
                textToMap: mapping.textToMap,
                ontologyTermLabel: candidate.label,
                ontologyTermSynonyms: candidate.synonyms?.join('|') || '',
                mappingConfidence: candidate.confidence?.toString() || '',
                ontologyTermID: candidate.termId,
                ontologyURI: candidate.uri,
                datasource: candidate.datasource,
                mappingProvenance: candidate.mappingProvenance
            })
        }
    }
    return results
}

export async function recordVote(
    textToMap: string,
    propertyType: string,
    termId: string,
    termLabel: string,
    ontology: string,
    vote: 'up' | 'down'
): Promise<void> {
    await fetch(apiUrl + '/v3/api/votes', {
        method: 'POST',
        body: JSON.stringify({ textToMap, propertyType, termId, termLabel, ontology, vote }),
        headers: { 'content-type': 'application/json' }
    })
}

export async function search(params:SearchParams):Promise<SearchResult[]> {

    // Build V3 request body
    const requestBody = {
        properties: params.properties,
        model: params.llmModel || 'text-embedding-3-small',
        targetOntologies: params.targetOntologies || [],
        includeOtherOntologies: params.includeOtherOntologies,
        filter: buildFilter(params)
    }

    let res = await fetch(apiUrl + '/v3/api/services/map', {
        method: 'POST',
        body: JSON.stringify(requestBody),
        headers: {
            'content-type': 'application/json',
            'accept': 'application/json'
        }
    })

    if (!res.ok) {
        throw new Error(`HTTP ${res.status}: ${await res.text()}`)
    }

    const v3Response = (await res.json()) as V3MapResponse

    // Convert V3 response to legacy SearchResult[] format for backward compatibility
    const results: SearchResult[] = []
    for (const mapping of v3Response.mappings) {
        if (mapping.error) {
            results.push({
                propertyType: mapping.propertyType,
                textToMap: mapping.textToMap,
                ontologyTermLabel: '',
                ontologyTermSynonyms: '',
                mappingConfidence: '',
                ontologyTermID: '',
                ontologyURI: '',
                datasource: '',
                error: mapping.error
            })
            continue
        }
        for (const candidate of mapping.candidates) {
            results.push({
                propertyType: mapping.propertyType,
                textToMap: mapping.textToMap,
                ontologyTermLabel: candidate.label,
                ontologyTermSynonyms: candidate.synonyms?.join('|') || '',
                mappingConfidence: candidate.confidence?.toString() || '',
                ontologyTermID: candidate.termId,
                ontologyURI: candidate.uri,
                datasource: candidate.datasource,
                mappingProvenance: candidate.mappingProvenance
            })
        }
    }

    return results
}

export interface StreamProgress {
    completed: number
    total: number
    results: SearchResult[]  // mutable reference, only snapshot when needed
}

/**
 * Streaming version of search. Calls onProgress as each property completes mapping.
 * Returns an AbortController that can be used to cancel the request.
 */
export function searchStream(
    params: SearchParams,
    onProgress: (progress: StreamProgress) => void,
    onDone: (results: SearchResult[]) => void,
    onError: (error: Error) => void
): AbortController {
    const controller = new AbortController()

    const requestBody = {
        properties: params.properties,
        model: params.llmModel || 'text-embedding-3-small',
        targetOntologies: params.targetOntologies || [],
        includeOtherOntologies: params.includeOtherOntologies,
        filter: buildFilter(params)
    }

    const allResults: SearchResult[] = []

    fetch(apiUrl + '/v3/api/services/map-stream', {
        method: 'POST',
        body: JSON.stringify(requestBody),
        headers: {
            'content-type': 'application/json',
            'accept': 'application/x-ndjson'
        },
        signal: controller.signal
    }).then(async (res) => {
        if (!res.ok) {
            throw new Error(`HTTP ${res.status}: ${await res.text()}`)
        }
        const reader = res.body!.getReader()
        const decoder = new TextDecoder()
        let buffer = ''

        while (true) {
            const { done, value } = await reader.read()
            if (done) break

            buffer += decoder.decode(value, { stream: true })
            const lines = buffer.split('\n')
            buffer = lines.pop() || ''

            for (const line of lines) {
                if (!line.trim()) continue
                const event = JSON.parse(line)

                if (event.type === 'result') {
                    const mapping = event.mapping as V3PropertyMapping
                    if (mapping.error) {
                        allResults.push({
                            propertyType: mapping.propertyType,
                            textToMap: mapping.textToMap,
                            ontologyTermLabel: '',
                            ontologyTermSynonyms: '',
                            mappingConfidence: '',
                            ontologyTermID: '',
                            ontologyURI: '',
                            datasource: '',
                            error: mapping.error
                        })
                    } else {
                    for (const candidate of mapping.candidates) {
                        allResults.push({
                            propertyType: mapping.propertyType,
                            textToMap: mapping.textToMap,
                            ontologyTermLabel: candidate.label,
                            ontologyTermSynonyms: candidate.synonyms?.join('|') || '',
                            mappingConfidence: candidate.confidence?.toString() || '',
                            ontologyTermID: candidate.termId,
                            ontologyURI: candidate.uri,
                            datasource: candidate.datasource,
                            mappingProvenance: candidate.mappingProvenance
                        })
                    }
                    // If the property had no candidates, add a "did not map" entry
                    if (mapping.candidates.length === 0) {
                        allResults.push({
                            propertyType: mapping.propertyType,
                            textToMap: mapping.textToMap,
                            ontologyTermLabel: mapping.textToMap,
                            ontologyTermSynonyms: '',
                            mappingConfidence: 'Did not map',
                            ontologyTermID: '',
                            ontologyURI: '',
                            datasource: '',
                        })
                    }
                    }
                    onProgress({
                        completed: event.completed,
                        total: event.total,
                        results: allResults
                    })
                } else if (event.type === 'done') {
                    onDone(allResults)
                }
            }
        }
        // Handle any remaining buffer
        if (buffer.trim()) {
            const event = JSON.parse(buffer)
            if (event.type === 'done') {
                onDone(allResults)
            }
        }
    }).catch((err) => {
        if (err.name !== 'AbortError') {
            onError(err)
        }
    })

    return controller
}

// ==================== Annotate Text (NLP segmentation + mapping) ====================

export interface TextSegment {
    text: string
    start: number
    end: number
}

export interface AnnotateTextParams {
    text: string
    doNotSearchDatasources: boolean
    requiredSources: string[]
    preferredSources: string[]
    doNotSearchOntologies: boolean
    targetOntologies: string[]
    includeOtherOntologies: boolean
    llmModel: string
}

export interface AnnotateTextSegmentsResult {
    segments: TextSegment[]
    originalText: string
}

/**
 * Streaming annotate-text endpoint. First sends segments (NLP-extracted noun phrases),
 * then streams mapping results as each segment completes.
 * Returns an AbortController that can be used to cancel the request.
 */
export function annotateTextStream(
    params: AnnotateTextParams,
    onSegments: (result: AnnotateTextSegmentsResult) => void,
    onProgress: (progress: StreamProgress) => void,
    onDone: (results: SearchResult[]) => void,
    onError: (error: Error) => void
): AbortController {
    const controller = new AbortController()

    const requestBody = {
        text: params.text,
        model: params.llmModel || 'text-embedding-3-small',
        targetOntologies: params.targetOntologies || [],
        includeOtherOntologies: params.includeOtherOntologies,
        filter: {
            required: params.doNotSearchDatasources ? [] : params.requiredSources,
            preferred: params.doNotSearchDatasources ? [] : params.preferredSources,
        }
    }

    const allResults: SearchResult[] = []

    fetch(apiUrl + '/v3/api/services/annotate-text-stream', {
        method: 'POST',
        body: JSON.stringify(requestBody),
        headers: {
            'content-type': 'application/json',
            'accept': 'application/x-ndjson'
        },
        signal: controller.signal
    }).then(async (res) => {
        if (!res.ok) {
            throw new Error(`HTTP ${res.status}: ${await res.text()}`)
        }
        const reader = res.body!.getReader()
        const decoder = new TextDecoder()
        let buffer = ''

        while (true) {
            const { done, value } = await reader.read()
            if (done) break

            buffer += decoder.decode(value, { stream: true })
            const lines = buffer.split('\n')
            buffer = lines.pop() || ''

            for (const line of lines) {
                if (!line.trim()) continue
                const event = JSON.parse(line)

                if (event.type === 'segments') {
                    onSegments({
                        segments: event.segments as TextSegment[],
                        originalText: event.originalText as string
                    })
                } else if (event.type === 'result') {
                    const mapping = event.mapping as V3PropertyMapping
                    if (mapping.error) {
                        allResults.push({
                            propertyType: mapping.propertyType,
                            textToMap: mapping.textToMap,
                            ontologyTermLabel: '',
                            ontologyTermSynonyms: '',
                            mappingConfidence: '',
                            ontologyTermID: '',
                            ontologyURI: '',
                            datasource: '',
                            error: mapping.error
                        })
                    } else {
                        for (const candidate of mapping.candidates) {
                            allResults.push({
                                propertyType: mapping.propertyType,
                                textToMap: mapping.textToMap,
                                ontologyTermLabel: candidate.label,
                                ontologyTermSynonyms: candidate.synonyms?.join('|') || '',
                                mappingConfidence: candidate.confidence?.toString() || '',
                                ontologyTermID: candidate.termId,
                                ontologyURI: candidate.uri,
                                datasource: candidate.datasource,
                                mappingProvenance: candidate.mappingProvenance
                            })
                        }
                        if (mapping.candidates.length === 0) {
                            allResults.push({
                                propertyType: mapping.propertyType,
                                textToMap: mapping.textToMap,
                                ontologyTermLabel: mapping.textToMap,
                                ontologyTermSynonyms: '',
                                mappingConfidence: 'Did not map',
                                ontologyTermID: '',
                                ontologyURI: '',
                                datasource: '',
                            })
                        }
                    }
                    onProgress({
                        completed: event.completed,
                        total: event.total,
                        results: allResults
                    })
                } else if (event.type === 'done') {
                    onDone(allResults)
                }
                // ignore 'ping' events
            }
        }
        // Handle remaining buffer
        if (buffer.trim()) {
            const event = JSON.parse(buffer)
            if (event.type === 'done') {
                onDone(allResults)
            }
        }
    }).catch((err) => {
        if (err.name !== 'AbortError') {
            onError(err)
        }
    })

    return controller
}
