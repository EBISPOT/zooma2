
let apiUrl = process.env.REACT_APP_APIURL

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
}

export interface Datasource {
    type:string
    name:string
    longName:string|undefined
    description:string
    uri:string
    title:string
}

export async function search(params:SearchParams):Promise<SearchResult[]> {

    let filter = ''

    if(params.doNotSearchDatasources === true) {
        filter += 'required:[none]'
    } else {
        if(params.requiredSources.length > 0) {
            filter += 'required:[' + params.requiredSources.join(',') + ']'
        }
        if (params.preferredSources.length > 0) {
            filter += 'preferred:[' + params.preferredSources.join(',') + ']'
        }
    }

    if(params.doNotSearchOntologies === true) {
        filter += 'ontologies:[none]'
    } else {
        if(params.ontologySources.length > 0) {
            filter += 'ontologies:[' + params.ontologySources.join(',') + ']'
        }
    }

    if(filter !== '') {
        filter = 'filter=' + filter
    }

    let res = await fetch(apiUrl + '/services/map?' + filter, {
        method: 'POST',
        body: JSON.stringify(params.properties),
        credentials: 'include',

        headers: {
            'content-type': 'application/json',
            'accept': 'text/plain'
        }
    })

    return (await res.json()) as SearchResult[]
}

