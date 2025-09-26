#!/bin/bash

set -e

DEST_DIR="ftp"

URLS=(
  "ftp://ftp.ebi.ac.uk/pub/databases/biosamples/zooma/biosamples_zooma_curations.csv"
  "ftp://ftp.ebi.ac.uk/pub/databases/microarray/data/atlas/curation/zoomage_report.CURATED.tsv"
  "ftp://ftp.ebi.ac.uk/pub/databases/spot/zooma/data/annotations/ebisc/latest/ebisc.tsv"
  "ftp://ftp.ebi.ac.uk/pub/databases/spot/zooma/data/annotations/uniprot/latest/uniprot_disease.csv"
  "ftp://ftp.ebi.ac.uk/pub/databases/spot/zooma/data/annotations/cbi/latest/biosample_plant.csv"
  "ftp://ftp.ebi.ac.uk/pub/databases/eva/ClinVar/latest/eva_clinvar.txt"
  "ftp://ftp.ebi.ac.uk/pub/databases/eva/ClinVar/latest/clinvar_xrefs.txt"
  "ftp://ftp.ebi.ac.uk/pub/databases/metabolights/eb-eye/metabolights_zooma.tsv"
)

echo "🚀 Starting EBI data download..."

mkdir -p "${DEST_DIR}"

for url in "${URLS[@]}"; do
  output_file="${DEST_DIR}/$(basename "${url}")"
  curl ${url} | pigz --best > ${output_file}.gz
done

echo -e "\n✅ All files have been successfully downloaded and updated in ${DEST_DIR}"

exit 0
