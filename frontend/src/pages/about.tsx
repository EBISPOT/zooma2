import React, { Fragment } from "react";
import Header from "../components/Header";
import { Typography } from "@mui/material";

export default function About() {

  return(
    <Fragment>
      <Header section="about" />
      <main>
        <div className="row">
          <div className="columns medium-12 padding-top-large">
              <Typography variant="h3" gutterBottom>
                About ZOOMA
                </Typography>
            <p>
              ZOOMA is an application for discovering optimal ontology mappings, developed by the <a
                href="//www.ebi.ac.uk/about/spot-team">Samples, Phenotypes and Ontologies Team</a> at EBI.
                It can be used to automatically annotate &quot;properties&quot; (plain text, descriptive values about
                biological entities) with &quot;semantic tags&quot; (ontology classes).
            </p>
          </div>
        </div>

      </main>
    </Fragment>
  )

}
