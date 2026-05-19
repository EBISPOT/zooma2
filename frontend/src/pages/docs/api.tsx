import React, { Fragment, useEffect, useState } from "react";
import {
  Box,
  Button,
  CircularProgress,
  Container,
  Divider,
  Grid,
  Link as MUILink,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import CheckIcon from "@mui/icons-material/Check";
import ContentCopyIcon from "@mui/icons-material/ContentCopy";
import Header from "../../components/Header";
import { getModels } from "../../api/ZoomaApi";

type CodeLanguage = "json" | "ndjson" | "schema" | "text";

function tokenColor(code: string, offset: number, token: string, language: CodeLanguage) {
  if (language === "schema") {
    if (/^"/.test(token)) return "#0f766e";
    if (/^(string|number|boolean|null|true|false)$/.test(token)) return "#b45309";
    if (/^[A-Z]/.test(token)) return "#2563eb";
    return "#7c3aed";
  }
  if (/^"/.test(token)) {
    return /^\s*:/.test(code.slice(offset + token.length)) ? "#7c3aed" : "#0f766e";
  }
  if (/^(true|false|null)$/.test(token)) return "#b45309";
  return "#2563eb";
}

function highlightCode(code: string, language: CodeLanguage) {
  if (language === "text") {
    return [code];
  }

  const tokenPattern =
    language === "schema"
      ? /"(?:\\.|[^"\\])*"|\b(?:string|number|boolean|null|true|false)\b|[A-Z][A-Za-z0-9]*(?=\s*\{)|\b[A-Za-z][A-Za-z0-9_]*(?=\??:)/g
      : /"(?:\\.|[^"\\])*"|\b(?:true|false|null)\b|-?\d+(?:\.\d+)?/g;
  const parts: React.ReactNode[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = tokenPattern.exec(code)) !== null) {
    if (match.index > lastIndex) {
      parts.push(code.slice(lastIndex, match.index));
    }
    parts.push(
      <Box component="span" key={`${match.index}-${match[0]}`} sx={{ color: tokenColor(code, match.index, match[0], language) }}>
        {match[0]}
      </Box>
    );
    lastIndex = match.index + match[0].length;
  }

  if (lastIndex < code.length) {
    parts.push(code.slice(lastIndex));
  }

  return parts;
}

const CodeBlock: React.FC<React.PropsWithChildren<{ title?: string; language?: CodeLanguage }>> = ({
  title,
  language = "json",
  children,
}) => {
  const [copied, setCopied] = React.useState(false);
  const code = typeof children === "string" ? children : String(children || "");

  const copyCode = () => {
    navigator.clipboard.writeText(code).then(() => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1400);
    });
  };

  return (
    <Box my={2}>
      <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 2, mb: 0.5 }}>
        {title ? (
          <Typography variant="subtitle2" sx={{ textTransform: "uppercase", letterSpacing: 0.4 }}>
            {title}
          </Typography>
        ) : (
          <Box />
        )}
        <Button
          size="small"
          variant="outlined"
          onClick={copyCode}
          startIcon={copied ? <CheckIcon fontSize="small" /> : <ContentCopyIcon fontSize="small" />}
          sx={{ minWidth: 96 }}
        >
          {copied ? "Copied" : "Copy"}
        </Button>
      </Box>
      <Paper
        variant="outlined"
        sx={{
          p: 2,
          bgcolor: (theme) => (theme.palette.mode === "dark" ? "background.default" : "grey.50"),
          overflow: "auto",
          fontFamily:
            "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace",
          fontSize: 14,
          lineHeight: 1.6,
        }}
        component="pre"
      >
        {highlightCode(code, language)}
      </Paper>
    </Box>
  );
};

const InlineCode: React.FC<React.PropsWithChildren<{}>> = ({ children }) => (
  <Typography
    component="code"
    sx={{
      px: 0.75,
      py: 0.25,
      border: "1px solid",
      borderColor: "divider",
      borderRadius: 1,
      fontFamily: "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace",
      fontSize: 13,
    }}
  >
    {children}
  </Typography>
);

const Section: React.FC<React.PropsWithChildren<{ title: string; subtitle?: string; id?: string }>> = ({
  id,
  title,
  subtitle,
  children,
}) => (
  <Box id={id} my={4} sx={{ scrollMarginTop: 96 }}>
    <Typography variant="h4" gutterBottom>
      {title}
    </Typography>
    {subtitle && (
      <Typography variant="subtitle1" color="text.secondary" gutterBottom>
        {subtitle}
      </Typography>
    )}
    {children}
  </Box>
);

const endpoints = [
  { method: "GET", path: "/health", description: "Plain-text health check.", target: "health" },
  { method: "GET", path: "/status", description: "Current backend status, including OLS URL and default embedding model.", target: "status" },
  { method: "GET", path: "/sources", description: "Available curated datasources and ontologies.", target: "sources" },
  { method: "GET", path: "/models", description: "Embedding models available from OLS.", target: "models" },
  { method: "GET", path: "/ontology-presets", description: "Configured ontology presets for common searches.", target: "ontology-presets" },
  { method: "POST", path: "/services/map", description: "Map one or more properties to ontology term candidates.", target: "mapping" },
  { method: "POST", path: "/services/map-stream", description: "Stream property mapping progress as NDJSON.", target: "streaming-map" },
  { method: "POST", path: "/services/annotate-text-stream", description: "Segment free text and stream mappings for extracted phrases.", target: "annotate-text" },
];

const healthResponse = `All systems are operational.`;

const sourcesResponse = `[
  {
    "type": "DATABASE",
    "name": "atlas",
    "uri": "atlas"
  },
  {
    "type": "ONTOLOGY",
    "name": "efo",
    "title": "Experimental Factor Ontology",
    "description": "An application ontology covering experimental variables.",
    "uri": "efo"
  }
]`;

const ontologyPresetsResponse = `[
  {
    "name": "Phenotypes",
    "description": "Common phenotype ontologies.",
    "ontologies": ["hp", "mp", "zp"]
  }
]`;

function makeMappingRequest(model: string) { return `{
  "properties": [
    {
      "propertyType": "organism",
      "textToMap": "mus musculus"
    }
  ],
  "model": "${model}",
  "targetOntologies": ["ncbitaxon"],
  "includeOtherOntologies": true,
  "filter": {
    "required": ["atlas", "gwas"],
    "preferred": ["atlas"]
  },
  "excludeTermIds": [],
  "returnAll": false,
  "deep": false
}`; }

const mappingResponse = `{
  "mappings": [
    {
      "propertyType": "organism",
      "textToMap": "mus musculus",
      "candidates": [
        {
          "termId": "NCBITaxon:10090",
          "label": "Mus musculus",
          "synonyms": ["mouse"],
          "ontology": "ncbitaxon",
          "uri": "ncbitaxon",
          "confidence": 1.0,
          "datasource": "atlas",
          "mappingProvenance": [
            {
              "method": "curated",
              "matchType": "ZOOMA_CURATED",
              "source": "atlas",
              "input": "mus musculus",
              "target": "NCBITaxon:10090",
              "confidence": 1.0
            }
          ]
        }
      ],
      "error": null
    }
  ]
}`;

const mapStreamEvents = `{"type":"ping"}
{"type":"result","mapping":{"propertyType":"organism","textToMap":"mus musculus","candidates":[]},"completed":1,"total":2}
{"type":"done","completed":2,"total":2}`;

function makeAnnotateTextRequest(model: string) { return `{
  "text": "The sample was collected from Mus musculus liver.",
  "model": "${model}",
  "targetOntologies": ["ncbitaxon", "uberon"],
  "includeOtherOntologies": true,
  "filter": {
    "required": [],
    "preferred": []
  }
}`; }

const annotateTextEvents = `{"type":"segments","segments":[{"text":"Mus musculus","start":30,"end":42},{"text":"liver","start":43,"end":48}],"originalText":"The sample was collected from Mus musculus liver."}
{"type":"ping"}
{"type":"result","mapping":{"propertyType":null,"textToMap":"Mus musculus","candidates":[]},"completed":1,"total":2}
{"type":"done","completed":2,"total":2}`;

const schemas = `V3MapRequest {
  properties: V3StringToMap[]
  model?: string
  targetOntologies?: string[]
  includeOtherOntologies?: boolean
  filter?: V3Filter
  excludeTermIds?: string[]
  returnAll?: boolean
  deep?: boolean
}

V3StringToMap {
  propertyType?: string
  textToMap: string
}

V3Filter {
  required?: string[]
  preferred?: string[]
}

V3MapResponse {
  mappings: V3PropertyMapping[]
}

V3PropertyMapping {
  propertyType?: string
  textToMap: string
  candidates: V3MappingCandidate[]
  error?: string
}

V3MappingCandidate {
  termId: string
  label: string
  synonyms: string[]
  ontology: string
  uri: string
  confidence: number | null
  datasource: string
  mappingProvenance: V3MappingProvenanceStep[]
}

V3MappingProvenanceStep {
  method: "lexical" | "semantic" | "curated" | "cross_reference" | "ontology_traversal"
  matchType?: string
  source?: string
  model?: string
  confidence?: number
  input: string
  matchedText?: string
  similarity?: number
  target: string
}`;

export default function Docs() {
  const [defaultModel, setDefaultModel] = useState<string | null>(null);

  useEffect(() => {
    getModels().then(models => {
      const embeddable = models.find(m => m.canEmbed);
      setDefaultModel(embeddable ? embeddable.name : "");
    });
  }, []);

  if (defaultModel === null) {
    return (
      <Fragment>
        <Header section="api" />
        <main>
          <Box display="flex" justifyContent="center" alignItems="center" minHeight="50vh">
            <CircularProgress />
          </Box>
        </main>
      </Fragment>
    );
  }

  const statusResponse = `{
  "olsUrl": "https://www.ebi.ac.uk/ols4",
  "defaultModel": "${defaultModel}"
}`;

  const modelsResponse = `[
  {
    "name": "${defaultModel}",
    "can_embed": true
  }
]`;

  const mappingRequest = makeMappingRequest(defaultModel);
  const annotateTextRequest = makeAnnotateTextRequest(defaultModel);

  return (
    <Fragment>
      <Header section="api" />
      <main>
        <Container maxWidth="lg" sx={{ py: 4 }}>
          <Grid container spacing={3}>
            <Grid item xs={12}>
              <Typography variant="h3" gutterBottom>
                REST API Documentation
              </Typography>

              <Divider sx={{ my: 3 }} />

              <Section id="base-url" title="Base URL">
                <Typography paragraph>
                  The API is located at <InlineCode>https://www.ebi.ac.uk/spot/zooma/v3/api</InlineCode>.
                </Typography>
                <Typography paragraph>
                  JSON endpoints expect <InlineCode>Content-Type: application/json</InlineCode>. Streaming endpoints
                  return newline-delimited JSON with <InlineCode>Content-Type: application/x-ndjson</InlineCode>.
                </Typography>
              </Section>

              <Section id="endpoints" title="Endpoints">
                <TableContainer component={Paper} variant="outlined">
                  <Table size="small" aria-label="v3 endpoints">
                    <TableHead>
                      <TableRow>
                        <TableCell>Method</TableCell>
                        <TableCell>Path</TableCell>
                        <TableCell>Description</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {endpoints.map(({ method, path, description, target }) => (
                        <TableRow key={`${method} ${path}`}>
                          <TableCell>
                            <InlineCode>{method}</InlineCode>
                          </TableCell>
                          <TableCell>
                            <MUILink href={`#${target}`} underline="hover">
                              <InlineCode>{path}</InlineCode>
                            </MUILink>
                          </TableCell>
                          <TableCell>{description}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
              </Section>

              <Section id="health" title="GET /health" subtitle="Plain-text health check">
                <Typography paragraph>
                  Returns a lightweight liveness response. Use this for simple uptime checks where JSON is not needed.
                </Typography>
                <CodeBlock title="Response" language="text">{healthResponse}</CodeBlock>
              </Section>

              <Section id="status" title="GET /status" subtitle="Backend status metadata">
                <Typography paragraph>
                  Returns runtime metadata useful for diagnostics, including the OLS instance the backend is using and
                  the default embedding model selected from OLS.
                </Typography>
                <CodeBlock title="Response">{statusResponse}</CodeBlock>
              </Section>

              <Section id="sources" title="GET /sources" subtitle="Curated datasources and ontologies">
                <Typography paragraph>
                  Lists the sources available for filtering and mapping. Curated sources have <InlineCode>type</InlineCode>{" "}
                  set to <InlineCode>DATABASE</InlineCode>; ontology sources have <InlineCode>type</InlineCode> set to{" "}
                  <InlineCode>ONTOLOGY</InlineCode> and may include title and description metadata from OLS.
                </Typography>
                <CodeBlock title="Response">{sourcesResponse}</CodeBlock>
              </Section>

              <Section id="models" title="GET /models" subtitle="Embedding model metadata">
                <Typography paragraph>
                  Returns embedding model metadata from OLS. Use a model whose <InlineCode>can_embed</InlineCode> value
                  is <InlineCode>true</InlineCode> in mapping requests. If a request omits <InlineCode>model</InlineCode>,
                  ZOOMA uses the first embeddable model returned by OLS, falling back to{" "}
                  <InlineCode>{defaultModel}</InlineCode>.
                </Typography>
                <CodeBlock title="Response">{modelsResponse}</CodeBlock>
              </Section>

              <Section id="ontology-presets" title="GET /ontology-presets" subtitle="Configured ontology groups">
                <Typography paragraph>
                  Returns named ontology groups configured for the deployment. Clients can use these presets to populate
                  common target-ontology choices without hard-coding ontology lists.
                </Typography>
                <CodeBlock title="Response">{ontologyPresetsResponse}</CodeBlock>
              </Section>

              <Section id="mapping" title="POST /services/map" subtitle="Batch property mapping">
                <Typography paragraph>
                  Maps one or more input properties to ranked ontology term candidates. Results are grouped by input
                  property.
                </Typography>
                <CodeBlock title="Request" language="json">{mappingRequest}</CodeBlock>
                <CodeBlock title="Response" language="json">{mappingResponse}</CodeBlock>
                <Typography paragraph>
                  <InlineCode>includeOtherOntologies</InlineCode> defaults to <InlineCode>true</InlineCode>. When set to{" "}
                  <InlineCode>false</InlineCode>, returned candidates are restricted to <InlineCode>targetOntologies</InlineCode>.
                  Use <InlineCode>returnAll</InlineCode> with <InlineCode>deep</InlineCode> to retrieve a broader candidate list (slower).
                </Typography>
              </Section>

              <Section id="streaming-map" title="POST /services/map-stream" subtitle="Streaming batch property mapping">
                <Typography paragraph>
                  Accepts the same request body as <InlineCode>/services/map</InlineCode> and streams NDJSON events as
                  each property completes. <InlineCode>ping</InlineCode> events are heartbeats and can be ignored.
                </Typography>
                <CodeBlock title="Events" language="ndjson">{mapStreamEvents}</CodeBlock>
              </Section>

              <Section id="annotate-text" title="POST /services/annotate-text-stream" subtitle="Free-text segmentation and mapping">
                <Typography paragraph>
                  Segments free text. Emits the selected phrase offsets followed by streamed mappings for each
                  unique segment.
                </Typography>
                <CodeBlock title="Request" language="json">{annotateTextRequest}</CodeBlock>
                <CodeBlock title="Events" language="ndjson">{annotateTextEvents}</CodeBlock>
              </Section>

              <Section id="schemas" title="Schemas">
                <CodeBlock language="schema">{schemas}</CodeBlock>
              </Section>

              <Section id="errors" title="Errors">
                <Typography paragraph>
                  Invalid requests return JSON with <InlineCode>error</InlineCode> and <InlineCode>message</InlineCode>.
                  For example, <InlineCode>/services/map</InlineCode> requires a non-empty <InlineCode>properties</InlineCode>{" "}
                  array and <InlineCode>/services/annotate-text-stream</InlineCode> requires non-empty <InlineCode>text</InlineCode>.
                </Typography>
              </Section>
            </Grid>
          </Grid>
        </Container>
      </main>
    </Fragment>
  );
}
