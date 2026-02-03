
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

export async function search(params:SearchParams):Promise<SearchResult[]> {

    // Build V3 request body
    const requestBody = {
        properties: params.properties,
        model: params.llmModel || 'text-embedding-3-small',
        preferredOntologies: params.preferredOntologies || [],
        filter: {
            required: params.doNotSearchDatasources ? [] : params.requiredSources,
            preferred: params.doNotSearchDatasources ? [] : params.preferredSources,
            ontologies: params.doNotSearchOntologies ? [] : params.ontologySources
        }
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

