import React, { useState, useEffect, useMemo, useRef } from 'react';
import {
  Dialog, DialogTitle, DialogContent, DialogActions,
  Button, Box, Typography, TextField, MenuItem,
  FormControlLabel, Checkbox, Table, TableBody,
  TableCell, TableContainer, TableHead, TableRow,
  Paper, Select, InputLabel, FormControl, IconButton,
} from '@mui/material';
import UploadFileIcon from '@mui/icons-material/UploadFile';
import CloseIcon from '@mui/icons-material/Close';

const DELIMITERS: { label: string; value: string }[] = [
  { label: 'Comma (,)', value: ',' },
  { label: 'Tab (\\t)', value: '\t' },
  { label: 'Semicolon (;)', value: ';' },
  { label: 'Pipe (|)', value: '|' },
];

const QUOTE_CHARS: { label: string; value: string }[] = [
  { label: 'None', value: '' },
  { label: 'Double quote (")', value: '"' },
  { label: "Single quote (')", value: "'" },
];

function guessDelimiter(text: string): string {
  const firstLines = text.split('\n').slice(0, 10).join('\n');
  const counts: { delim: string; count: number }[] = [
    { delim: '\t', count: (firstLines.match(/\t/g) || []).length },
    { delim: ',', count: (firstLines.match(/,/g) || []).length },
    { delim: ';', count: (firstLines.match(/;/g) || []).length },
    { delim: '|', count: (firstLines.match(/\|/g) || []).length },
  ];
  counts.sort((a, b) => b.count - a.count);
  return counts[0].count > 0 ? counts[0].delim : ',';
}

function guessQuoteChar(text: string): string {
  const firstLines = text.split('\n').slice(0, 10).join('\n');
  const dq = (firstLines.match(/"/g) || []).length;
  const sq = (firstLines.match(/'/g) || []).length;
  if (dq >= 2) return '"';
  if (sq >= 2) return "'";
  return '';
}

function guessHasHeader(rows: string[][]): boolean {
  if (rows.length < 2) return false;
  const first = rows[0];
  const firstAllText = first.every(v => isNaN(Number(v)) && v.length < 50);
  if (!firstAllText) return false;
  return true;
}

function parseLine(line: string, delimiter: string, quoteChar: string): string[] {
  if (!quoteChar) {
    return line.split(delimiter).map(cell => cell.trim());
  }
  const fields: string[] = [];
  let current = '';
  let inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (inQuotes) {
      if (ch === quoteChar) {
        if (i + 1 < line.length && line[i + 1] === quoteChar) {
          current += quoteChar;
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        current += ch;
      }
    } else if (ch === quoteChar) {
      inQuotes = true;
    } else if (ch === delimiter) {
      fields.push(current.trim());
      current = '';
    } else {
      current += ch;
    }
  }
  fields.push(current.trim());
  return fields;
}

function parseCsv(text: string, delimiter: string, quoteChar: string): string[][] {
  const lines = text.split('\n').filter(l => l.trim().length > 0);
  return lines.map(line => parseLine(line, delimiter, quoteChar));
}

interface CsvImportDialogProps {
  onImport: (terms: string) => void;
}

export default function CsvImportDialog({ onImport }: CsvImportDialogProps) {
  const [open, setOpen] = useState(false);
  const [rawText, setRawText] = useState('');
  const [delimiter, setDelimiter] = useState(',');
  const [quoteChar, setQuoteChar] = useState('');
  const [hasHeader, setHasHeader] = useState(true);
  const [termColumn, setTermColumn] = useState(0);
  const [typeColumn, setTypeColumn] = useState<number | ''>('');
  const fileInputRef = useRef<HTMLInputElement>(null);

  const rows = useMemo(() => parseCsv(rawText, delimiter, quoteChar), [rawText, delimiter, quoteChar]);
  const dataRows = hasHeader && rows.length > 1 ? rows.slice(1) : rows;
  const headerRow = hasHeader && rows.length > 0 ? rows[0] : null;
  const columnCount = rows.length > 0 ? Math.max(...rows.map(r => r.length)) : 0;

  // Determine which columns are completely empty
  const nonEmptyColumns = useMemo(() => {
    const cols: number[] = [];
    for (let c = 0; c < columnCount; c++) {
      const hasValue = rows.some(row => (row[c] || '').length > 0);
      if (hasValue) cols.push(c);
    }
    return cols;
  }, [rows, columnCount]);

  // Preview up to 10 rows
  const previewRows = dataRows.slice(0, 10);

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (ev) => {
      const text = ev.target?.result as string;
      setRawText(text);
      const guessedDelim = guessDelimiter(text);
      const guessedQuote = guessQuoteChar(text);
      setDelimiter(guessedDelim);
      setQuoteChar(guessedQuote);
      const parsed = parseCsv(text, guessedDelim, guessedQuote);
      setHasHeader(guessHasHeader(parsed));
      setTermColumn(0);
      setTypeColumn(parsed[0]?.length > 1 ? 1 : '');
      setOpen(true);
    };
    reader.readAsText(file);
    // Reset so the same file can be re-selected
    e.target.value = '';
  };

  // When delimiter/quote changes, re-evaluate columns
  useEffect(() => {
    if (rows.length > 0) {
      if (termColumn >= columnCount) setTermColumn(0);
      if (typeColumn !== '' && typeColumn >= columnCount) setTypeColumn('');
    }
  }, [delimiter, quoteChar, columnCount]);

  const handleImport = () => {
    const lines = dataRows.map(row => {
      const term = row[termColumn] || '';
      const type = typeColumn !== '' ? (row[typeColumn] || '') : '';
      if (type) return `${term}\t${type}`;
      return term;
    }).filter(line => line.trim().length > 0);
    onImport(lines.join('\n'));
    setOpen(false);
    setRawText('');
  };

  const columnOptions = nonEmptyColumns;

  return (
    <>
      <input
        ref={fileInputRef}
        type="file"
        accept=".csv,.tsv,.txt"
        style={{ display: 'none' }}
        onChange={handleFileSelect}
      />
      <Typography
        variant="body2"
        sx={{ color: '#2e7d32', cursor: 'pointer', '&:hover': { textDecoration: 'underline' }, display: 'flex', alignItems: 'center', gap: 0.5 }}
        onClick={() => fileInputRef.current?.click()}
      >
        <UploadFileIcon sx={{ fontSize: 16 }} />
        Upload CSV
      </Typography>

      <Dialog
        open={open}
        onClose={() => setOpen(false)}
        maxWidth="md"
        fullWidth
      >
        <DialogTitle sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          CSV Import Preview
          <IconButton onClick={() => setOpen(false)} size="small">
            <CloseIcon />
          </IconButton>
        </DialogTitle>
        <DialogContent dividers>
          {/* Controls */}
          <Box sx={{ display: 'flex', gap: 3, mb: 3, flexWrap: 'wrap', alignItems: 'center' }}>
            <FormControl size="small" sx={{ minWidth: 150 }}>
              <InputLabel>Delimiter</InputLabel>
              <Select
                value={delimiter}
                label="Delimiter"
                onChange={(e) => setDelimiter(e.target.value)}
              >
                {DELIMITERS.map(d => (
                  <MenuItem key={d.value} value={d.value}>{d.label}</MenuItem>
                ))}
              </Select>
            </FormControl>

            <FormControl size="small" sx={{ minWidth: 150 }}>
              <InputLabel>Quote character</InputLabel>
              <Select
                value={quoteChar}
                label="Quote character"
                onChange={(e) => setQuoteChar(e.target.value)}
              >
                {QUOTE_CHARS.map(q => (
                  <MenuItem key={q.value} value={q.value}>{q.label}</MenuItem>
                ))}
              </Select>
            </FormControl>

            <FormControlLabel
              control={
                <Checkbox
                  checked={hasHeader}
                  onChange={(e) => setHasHeader(e.target.checked)}
                  color="success"
                />
              }
              label="First row is header"
            />

            <FormControl size="small" sx={{ minWidth: 150 }}>
              <InputLabel>Term column</InputLabel>
              <Select
                value={termColumn}
                label="Term column"
                onChange={(e) => setTermColumn(e.target.value as number)}
              >
                {columnOptions.map(i => (
                  <MenuItem key={i} value={i}>
                    {headerRow ? `${headerRow[i] || `Column ${i + 1}`}` : `Column ${i + 1}`}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>

            <FormControl size="small" sx={{ minWidth: 150 }}>
              <InputLabel>Type column</InputLabel>
              <Select
                value={typeColumn}
                label="Type column"
                onChange={(e) => setTypeColumn(e.target.value as number | '')}
              >
                <MenuItem value="">
                  <em>None</em>
                </MenuItem>
                {columnOptions.map(i => (
                  <MenuItem key={i} value={i}>
                    {headerRow ? `${headerRow[i] || `Column ${i + 1}`}` : `Column ${i + 1}`}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
          </Box>

          {/* Data summary */}
          <Typography variant="body2" sx={{ mb: 1.5, color: 'text.secondary' }}>
            {dataRows.length} row{dataRows.length !== 1 ? 's' : ''} found
            {dataRows.length > 10 && ` (showing first 10)`}
          </Typography>

          {/* Preview table */}
          <TableContainer component={Paper} variant="outlined" sx={{ maxHeight: 400 }}>
            <Table size="small" stickyHeader>
              <TableHead>
                <TableRow>
                  {columnOptions.map(i => (
                    <TableCell
                      key={i}
                      sx={{
                        fontWeight: 600,
                        bgcolor: i === termColumn ? '#e8f5e9' : i === typeColumn ? '#e3f2fd' : '#fafafa',
                      }}
                    >
                      <Box>
                        {headerRow ? (headerRow[i] || `Column ${i + 1}`) : `Column ${i + 1}`}
                      </Box>
                      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                        {i === termColumn ? '→ Term' : i === typeColumn ? '→ Type' : ''}
                      </Typography>
                    </TableCell>
                  ))}
                </TableRow>
              </TableHead>
              <TableBody>
                {previewRows.map((row, ri) => (
                  <TableRow key={ri}>
                    {columnOptions.map(ci => (
                      <TableCell
                        key={ci}
                        sx={{
                          bgcolor: ci === termColumn ? '#f1f8e9' : ci === typeColumn ? '#e8f4fd' : undefined,
                        }}
                      >
                        {row[ci] || ''}
                      </TableCell>
                    ))}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          <Button onClick={() => setOpen(false)} sx={{ textTransform: 'none' }}>
            Cancel
          </Button>
          <Button
            variant="contained"
            color="success"
            onClick={handleImport}
            disabled={dataRows.length === 0}
            sx={{ textTransform: 'none' }}
          >
            Import {dataRows.length} term{dataRows.length !== 1 ? 's' : ''}
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
