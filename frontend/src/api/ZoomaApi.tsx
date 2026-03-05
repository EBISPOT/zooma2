
let apiUrl = process.env.REACT_APP_APIURL
if(apiUrl?.endsWith('/')) {
    apiUrl = apiUrl.slice(0, -1)
}

export interface SearchProperty {
}

export interface SearchParams {
    properties: { 
        propertyValue: string
        propertyType: string
    }[]

    doNotSearchDatasources:boolean
    requiredSources:string[]
    preferredSources:string[]

    doNotSearchOntologies:boolean
    ontologySources:string[]
    
    // New: preferred ontologies for semantic search
    preferredOntologies:string[]
    includeOtherOntologies:boolean
    useLlmSearch:boolean
    llmModel:string
}

export interface SearchResult {
    propertyType:string
    propertyValue:string
    ontologyTermLabel:string
    ontologyTermSynonyms:string
    mappingConfidence:string
    ontologyTermID:string
    ontologyURI:string
    datasource:string
    mappingProvenance?: MappingProvenanceStep[]
}

// V3 API response types
export interface V3MapResponse {
    mappings: V3PropertyMapping[]
}

export interface V3PropertyMapping {
    propertyType: string
    propertyValue: string
    candidates: V3MappingCandidate[]
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

function buildFilter(params: SearchParams) {
    return {
        required: params.doNotSearchDatasources ? [] : params.requiredSources,
        preferred: params.doNotSearchDatasources ? [] : params.preferredSources,
        ontologies: !params.includeOtherOntologies && params.preferredOntologies?.length
            ? params.preferredOntologies
            : (params.doNotSearchOntologies ? [] : params.ontologySources)
    }
}

/**
 * Re-map a single property, excluding specific term IDs (for thumbs-down).
 */
export async function remapOne(
    params: SearchParams,
    propertyValue: string,
    propertyType: string,
    excludeTermIds: string[]
): Promise<SearchResult[]> {
    const requestBody = {
        properties: [{ propertyValue, propertyType }],
        model: params.llmModel || 'text-embedding-3-small',
        preferredOntologies: params.preferredOntologies || [],
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

    const v3Response = (await res.json()) as V3MapResponse
    const results: SearchResult[] = []
    for (const mapping of v3Response.mappings) {
        for (const candidate of mapping.candidates) {
            results.push({
                propertyType: mapping.propertyType,
                propertyValue: mapping.propertyValue,
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
    propertyValue: string,
    propertyType: string,
    termId: string,
    termLabel: string,
    ontology: string,
    vote: 'up' | 'down'
): Promise<void> {
    await fetch(apiUrl + '/v3/api/votes', {
        method: 'POST',
        body: JSON.stringify({ propertyValue, propertyType, termId, termLabel, ontology, vote }),
        headers: { 'content-type': 'application/json' }
    })
}

export async function search(params:SearchParams):Promise<SearchResult[]> {

    // Build V3 request body
    const requestBody = {
        properties: params.properties,
        model: params.llmModel || 'text-embedding-3-small',
        preferredOntologies: params.preferredOntologies || [],
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

    const v3Response = (await res.json()) as V3MapResponse

    // Convert V3 response to legacy SearchResult[] format for backward compatibility
    const results: SearchResult[] = []
    for (const mapping of v3Response.mappings) {
        for (const candidate of mapping.candidates) {
            results.push({
                propertyType: mapping.propertyType,
                propertyValue: mapping.propertyValue,
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
    results: SearchResult[]
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
        preferredOntologies: params.preferredOntologies || [],
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
                    for (const candidate of mapping.candidates) {
                        allResults.push({
                            propertyType: mapping.propertyType,
                            propertyValue: mapping.propertyValue,
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
                            propertyValue: mapping.propertyValue,
                            ontologyTermLabel: mapping.propertyValue,
                            ontologyTermSynonyms: '',
                            mappingConfidence: 'Did not map',
                            ontologyTermID: '',
                            ontologyURI: '',
                            datasource: '',
                        })
                    }
                    onProgress({
                        completed: event.completed,
                        total: event.total,
                        results: [...allResults]
                    })
                } else if (event.type === 'done') {
                    onDone([...allResults])
                }
            }
        }
        // Handle any remaining buffer
        if (buffer.trim()) {
            const event = JSON.parse(buffer)
            if (event.type === 'done') {
                onDone([...allResults])
            }
        }
    }).catch((err) => {
        if (err.name !== 'AbortError') {
            onError(err)
        }
    })

    return controller
}
