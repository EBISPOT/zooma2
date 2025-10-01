import React, { Fragment } from "react";
import { Link as RouterLink } from "react-router-dom";
import {
  Box,
  Container,
  Divider,
  Grid,
  Link as MUILink,
  List,
  ListItem,
  ListItemText,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import Header from "../../components/Header";
import examples from "../../data/api-response-examples.json";

// Reusable code block
const CodeBlock: React.FC<React.PropsWithChildren<{ title?: string }>> = ({ title, children }) => (
  <Box my={2}>
    {title && (
      <Typography variant="subtitle2" sx={{ mb: 0.5, textTransform: "uppercase", letterSpacing: 0.4 }}>
        {title}
      </Typography>
    )}
    <Paper
      variant="outlined"
      sx={{
        p: 2,
        bgcolor: (theme) => (theme.palette.mode === "dark" ? "background.default" : "grey.50"),
        overflow: "auto",
        fontFamily: "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace",
        fontSize: 14,
        lineHeight: 1.6,
      }}
      component="pre"
    >
      {children}
    </Paper>
  </Box>
);

// Section wrapper
const Section: React.FC<React.PropsWithChildren<{ title: string; subtitle?: string }>> = ({
  title,
  subtitle,
  children,
}) => (
  <Box my={4}>
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

export default function Docs() {
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

              <Divider sx={{ mb: 3 }} />

              <Section title="Introduction">
                <Typography paragraph>
                  This page describes how to develop against the ZOOMA REST API to search for and retrieve ZOOMA objects.
                </Typography>
                <Typography paragraph>
                  All requests should be made to the root URL of the Zooma API, which is not shown in the example
                  requests. The root URL for the API is{" "}
                  <Paper
                    variant="outlined"
                    sx={{
                      display: "inline-block",
                      px: 1,
                      py: 0.25,
                      mx: 0.5,
                      fontFamily:
                        "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace",
                      fontSize: 14,
                    }}
                    component="code"
                  >
                    www.ebi.ac.uk/spot/zooma/v2/api
                  </Paper>
                  .
                </Typography>
              </Section>

              <Section title="Predicting Annotations">
                <Typography paragraph>
                  You can use Zooma to predict an ontology annotation given a property value (and optionally a property
                  type).
                </Typography>

                <Typography variant="h6" gutterBottom>
                  Example Request
                </Typography>
                <Typography paragraph>Predict an ontology annotation for the text value &quot;mus musculus&quot;.</Typography>
                <CodeBlock>GET /services/annotate?propertyValue=mus+musculus</CodeBlock>

                <Typography variant="h6" gutterBottom>
                  Response
                </Typography>
                <CodeBlock>{JSON.stringify(examples["2"], null, 2)}</CodeBlock>

                <Typography paragraph>
                  This example predicts that &apos;mus musculus&apos; should be annotated with the ontology term{" "}
                  <MUILink
                    href="http://purl.obolibrary.org/obo/NCBITaxon_10090"
                    target="_blank"
                    rel="noopener"
                    underline="hover"
                  >
                    http://purl.obolibrary.org/obo/NCBITaxon_10090
                  </MUILink>
                  This annotation was predicted based on curated mappings in the &apos;ExpressionAtlas (atlas)&apos; database.
                </Typography>

                <Typography variant="h6" gutterBottom>
                  Additional Parameters
                </Typography>
                <Typography paragraph>
                  Zooma supports the option to specify the data sources it will search from. By default (specifying nothing),
                  Zooma will search its available databases containing curated mappings (and that do not include ontology sources),
                  and if nothing is found it will look in the Ontology Lookup Service (OLS) to predict ontology annotations.
                </Typography>

                <Typography paragraph>Filters you can apply to modify the default behavior:</Typography>
                <List dense>
                  <ListItem>
                    <ListItemText primary="required:[datasource1,datasource2,…]" />
                  </ListItem>
                  <ListItem>
                    <ListItemText primary="preferred:[datasource2,datasource1,…]" />
                  </ListItem>
                  <ListItem>
                    <ListItemText primary="ontologies:[efo,go,…]" />
                  </ListItem>
                </List>

                <Typography paragraph>
                  where <em>datasource1,datasource2</em>, etc., are the database names of the datasources (see table below).
                </Typography>

                <List dense>
                  <ListItem>
                    <ListItemText
                      primary={
                        <span>
                          <em>required</em> will limit the search to the given datasources
                        </span>
                      }
                    />
                  </ListItem>
                  <ListItem>
                    <ListItemText
                      primary={
                        <span>
                          <em>preferred</em> will provide a ranking for those datasources
                        </span>
                      }
                    />
                  </ListItem>
                  <ListItem>
                    <ListItemText
                      primary={
                        <span>
                          and <em>ontologies</em> will limit the OLS search to the given ontologies
                        </span>
                      }
                    />
                  </ListItem>
                </List>

                <Typography paragraph>
                  If &apos;required:[none]&apos; is specified, Zooma will search OLS without looking into the datasources. If
                  &apos;ontologies:[none]&apos; is specified, Zooma will not search OLS if the datasource search fails to make
                  any predictions.
                </Typography>

                <Typography paragraph>
                  In the table below you can see the available databases containing curated mappings in Zooma.
                  <br />
                  To define the source(s) you want Zooma to search in, use the <em>Database name</em> in the &apos;required:[]&apos; field.
                  <br />
                  e.g. use &apos;required:[cttv]&apos; to look in OpenTargets.
                </Typography>

                <CodeBlock>GET /services/annotate?propertyValue=disease&amp;filter=required:[cttv]</CodeBlock>

                <TableContainer component={Paper} variant="outlined" sx={{ my: 2 }}>
                  <Table size="small" aria-label="datasources table">
                    <TableHead>
                      <TableRow>
                        <TableCell>Display name</TableCell>
                        <TableCell>Database name</TableCell>
                        <TableCell>Learn more</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {[
                        {
                          display: "OpenTargets",
                          db: "cttv",
                          href: "//www.targetvalidation.org",
                          text: "www.targetvalidation.org",
                        },
                        { display: "ClinVar", db: "eva-clinvar", href: "//www.ebi.ac.uk/eva", text: "www.ebi.ac.uk/eva" },
                        {
                          display: "CellularPhenoTypes",
                          db: "sysmicro",
                          href: "//www.ebi.ac.uk/fg/sym",
                          text: "www.ebi.ac.uk/fg/sym",
                        },
                        { display: "ExpressionAtlas", db: "atlas", href: "//www.ebi.ac.uk/gxa", text: "www.ebi.ac.uk/gxa" },
                        { display: "EBiSC", db: "ebisc", href: "//cells.ebisc.org/", text: "www.cells.ebisc.org" },
                        { display: "UniProt", db: "uniprot", href: "//www.ebi.ac.uk/uniprot", text: "www.ebi.ac.uk/uniprot" },
                        { display: "GWAS", db: "gwas", href: "//www.ebi.ac.uk/gwas/", text: "www.ebi.ac.uk/gwas" },
                        { display: "CBI", db: "cbi", href: "//www.ebi.ac.uk/biosamples/", text: "www.ebi.ac.uk/biosamples" },
                        {
                          display: "ClinVarXRefs",
                          db: "clinvar-xrefs",
                          href: "//www.ncbi.nlm.nih.gov/clinvar",
                          text: "www.ncbi.nlm.nih.gov/clinvar",
                        },
                      ].map((row) => (
                        <TableRow key={row.db}>
                          <TableCell>{row.display}</TableCell>
                          <TableCell>
                            <Typography
                              component="code"
                              sx={{
                                px: 0.75,
                                py: 0.25,
                                border: "1px solid",
                                borderColor: "divider",
                                borderRadius: 1,
                                fontSize: 13,
                              }}
                            >
                              {row.db}
                            </Typography>
                          </TableCell>
                          <TableCell>
                            <MUILink href={row.href} target="_blank" rel="noopener" underline="hover">
                              {row.text}
                            </MUILink>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>

                <Stack spacing={2}>
                  <Box>
                    <Typography variant="subtitle1">Property type filter</Typography>
                    <Typography variant="body2" color="text.secondary">
                      Predict an ontology annotation for the text value &quot;mus musculus&quot; and type &quot;organism&quot;.
                    </Typography>
                    <CodeBlock>GET /services/annotate?propertyValue=mus+musculus&amp;propertyType=organism</CodeBlock>
                  </Box>

                  <Box>
                    <Typography variant="subtitle1">Datasources filter</Typography>
                    <Typography variant="body2" color="text.secondary">
                      Predict using annotations present in a defined list of datasources.
                    </Typography>
                    <CodeBlock>
                      GET /services/annotate?propertyValue=mus+musculus&amp;propertyType=organism&amp;filter=required:[atlas,gwas]
                    </CodeBlock>
                  </Box>

                  <Box>
                    <Typography variant="subtitle1">Limit OLS lookup</Typography>
                    <Typography variant="body2" color="text.secondary">
                      Predict for &quot;ear inflorescence&quot; searching a datasource only and skipping OLS if none found.
                    </Typography>
                    <CodeBlock>
                      GET /services/annotate?propertyValue=ear+inflorescence&amp;filter=required:[sysmicro],ontologies:[none]
                    </CodeBlock>
                    <Typography variant="body2" color="text.secondary">
                      The &apos;ontologies:[none]&apos; parameter restrains Zooma from looking in the OLS if no annotation is found.
                    </Typography>
                  </Box>

                  <Box>
                    <Typography variant="subtitle1">Preferred ranking</Typography>
                    <Typography variant="body2" color="text.secondary">
                      Prefer GWAS within the required set to influence scoring.
                    </Typography>
                    <CodeBlock>
                      GET /services/annotate?propertyValue=lung+adenocarcinoma&amp;filter=required:[atlas,gwas],preferred:[gwas]
                    </CodeBlock>
                    <Typography variant="body2" color="text.secondary">
                      The &apos;preferred&apos; parameter sets an order of trusted datasources that affects the score.
                    </Typography>
                  </Box>

                  <Box>
                    <Typography variant="subtitle1">Ontologies only</Typography>
                    <Typography variant="body2" color="text.secondary">
                      Use only specified ontologies (no datasource lookups).
                    </Typography>
                    <CodeBlock>
                      GET /services/annotate?propertyValue=mus+musculus&amp;propertyType=organism&amp;filter=required:[none],ontologies:[efo,mirnao]
                    </CodeBlock>
                  </Box>
                </Stack>
              </Section>

              <Section title="Retrieving Resources" subtitle="How to fetch more information about Zooma resource types">
                <Box mb={3}>
                  <Typography variant="h5" gutterBottom>
                    Property Types
                  </Typography>
                  <Typography>Retrieve all property types.</Typography>
                  <CodeBlock>GET /properties/types?limit=10</CodeBlock>

                  <Typography variant="subtitle2" sx={{ mt: 2 }}>
                    Request
                  </Typography>
                  <CodeBlock>{JSON.stringify(examples["4"], null, 2)}</CodeBlock>

                  <Typography variant="subtitle2">Response</Typography>
                  <CodeBlock>{JSON.stringify(examples["4"], null, 2)}</CodeBlock>
                </Box>
              </Section>

            </Grid>
          </Grid>
        </Container>
      </main>
    </Fragment>
  );
}

