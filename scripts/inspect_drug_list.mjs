import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const inputPath =
  process.argv[2] ??
  "C:/Users/Admin/Downloads/Mobile-Captured Pharmaceutical Medication Packages/drug list.xlsx";

const input = await FileBlob.load(inputPath);
const workbook = await SpreadsheetFile.importXlsx(input);

const summary = await workbook.inspect({
  kind: "workbook,sheet,table",
  maxChars: 8000,
  tableMaxRows: 12,
  tableMaxCols: 12,
  tableMaxCellChars: 120,
});

console.log(summary.ndjson);
