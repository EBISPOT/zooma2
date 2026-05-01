import React, { Fragment } from "react";
import { Link as RouterLink } from "react-router-dom";
import {
  Box,
  Button,
  Container,
  Divider,
  Grid,
  Link as MUILink,
  Typography,
} from "@mui/material";
import ApiIcon from "@mui/icons-material/Api";
import OpenInNewIcon from "@mui/icons-material/OpenInNew";
import SearchIcon from "@mui/icons-material/Search";
import Header from "../components/Header";

const Section: React.FC<React.PropsWithChildren<{ title: string }>> = ({ title, children }) => (
  <Box component="section" sx={{ my: 5 }}>
    <Typography variant="h4" gutterBottom>
      {title}
    </Typography>
    {children}
  </Box>
);

export default function About() {
  return (
    <Fragment>
      <Header section="about" />
      <main>
        <Container maxWidth="lg" sx={{ py: 5 }}>
          <Grid container spacing={5}>
            <Grid item xs={12} md={8}>
              <Typography variant="h3" gutterBottom>
                About ZOOMA
              </Typography>
              <Typography variant="h6" color="text.secondary" paragraph>
                ZOOMA helps turn free-text into ontology-backed annotations that can be searched,
                compared, and reused across biological datasets.
              </Typography>
              <Typography paragraph>
                Descriptions of concepts such as organisms, tissues, diseases, phenotypes,
                cell types, or experimental factors, are often represented as plain text, making linking biological data difficult.
                ZOOMA predicts candidate ontology terms for those values and returns confidence scores plus
                provenance so users can understand why each mapping was suggested.
              </Typography>
              <Box sx={{ display: "flex", flexWrap: "wrap", gap: 1.5, mt: 3 }}>
                <Button component={RouterLink} to="/" variant="contained" startIcon={<SearchIcon />}>
                  Search ZOOMA
                </Button>
                <Button component={RouterLink} to="/docs" variant="outlined" startIcon={<ApiIcon />}>
                  API Docs
                </Button>
              </Box>
            </Grid>
            <Grid item xs={12} md={4}>
              <Box sx={{ borderLeft: { md: "1px solid" }, borderColor: "divider", pl: { md: 4 } }}>
                <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                  Maintained by
                </Typography>
                <Typography variant="h6" gutterBottom>
                  Samples, Phenotypes and Ontologies Team
                </Typography>
                <Typography color="text.secondary" paragraph>
                  European Bioinformatics Institute, EMBL-EBI
                </Typography>
                <MUILink
                  href="https://www.ebi.ac.uk/about/spot-team"
                  target="_blank"
                  rel="noopener"
                  underline="hover"
                  sx={{ display: "inline-flex", alignItems: "center", gap: 0.5 }}
                >
                  Learn about the team <OpenInNewIcon fontSize="inherit" />
                </MUILink>
              </Box>
            </Grid>
          </Grid>

          <Divider sx={{ my: 5 }} />

          <Section title="How It Works">
            <Typography paragraph>
              ZOOMA combines dictionary based lookup in ontologies, curated mappings, and LLM embeddings to produce ranked candidate terms. The API returns a structured mapping response with
              candidate labels, synonyms, ontology identifiers, confidence scores, datasources, and a provenance chain.
            </Typography>
            <Typography paragraph>
              Search can be restricted to selected curated datasources or target ontologies.
            </Typography>
          </Section>

          <Section title="When To Use It">
            <Grid container spacing={4}>
              <Grid item xs={12} md={6}>
                <Typography variant="h6" gutterBottom>
                  Interactive curation
                </Typography>
                <Typography color="text.secondary">
                  Paste values or text into the web interface, inspect suggested mappings, adjust datasource and ontology
                  filters, and choose the best candidates for your dataset.
                </Typography>
              </Grid>
              <Grid item xs={12} md={6}>
                <Typography variant="h6" gutterBottom>
                  Programmatic annotation
                </Typography>
                <Typography color="text.secondary">
                  Use the REST API to map batches of properties or annotate free text programatically.
                </Typography>
              </Grid>
            </Grid>
          </Section>
        </Container>
      </main>
    </Fragment>
  );
}

