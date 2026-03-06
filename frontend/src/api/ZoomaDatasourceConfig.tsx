import { ZoomaDatasources } from "./ZoomaDatasources"

export interface ZoomaDatasourceConfig {

    doNotSearchDatasources:boolean

    excludedDatasources:string[]
    unrankedDatasources:string[]
    rankedDatasources:string[]

    doNotSearchOntologies:boolean

    // Target ontologies for semantic search
    targetOntologies:string[]
    includeOtherOntologies:boolean
    useLlmSearch:boolean
    llmModel:string

}
