import { ZoomaDatasources } from "./ZoomaDatasources"

export interface ZoomaDatasourceConfig {

    doNotSearchDatasources:boolean

    excludedDatasources:string[]
    unrankedDatasources:string[]
    rankedDatasources:string[]

    doNotSearchOntologies:boolean
    ontologySources:string[]

    // New: preferred ontologies for LLM semantic search
    preferredOntologies:string[]
    includeOtherOntologies:boolean
    useLlmSearch:boolean
    llmModel:string

}
