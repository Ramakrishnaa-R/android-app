import fs from "node:fs/promises";
import { FileBlob, SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const inputPath =
  "C:/Users/Admin/Downloads/Mobile-Captured Pharmaceutical Medication Packages/drug list.xlsx";
const outputDir = "outputs";
const outputPath = `${outputDir}/drug list - injection only.xlsx`;

const input = await FileBlob.load(inputPath);
const sourceWorkbook = await SpreadsheetFile.importXlsx(input);
const sourceSheet = sourceWorkbook.worksheets.getItem("Sheet1");
const values = sourceSheet.getUsedRange(true).values;

const headers = values[0];
const rows = values.slice(1);
const typeIndex = headers.findIndex((h) => String(h).toLowerCase() === "type");
const nameIndex = headers.findIndex((h) => String(h).toLowerCase() === "name");

const injectionPattern = /\b(inj|injection|inject|vial|amp|ampoule|prefilled|pfs|cartridge|penfill|flexpen)\b/i;

const filteredRows = rows.filter((row) => {
  const type = typeIndex >= 0 ? String(row[typeIndex] ?? "") : "";
  const name = nameIndex >= 0 ? String(row[nameIndex] ?? "") : "";
  return injectionPattern.test(type) || injectionPattern.test(name);
});

const workbook = Workbook.create();
const sheet = workbook.worksheets.add("Injection Only");

sheet.getRangeByIndexes(0, 0, 1, headers.length).values = [headers];

if (filteredRows.length > 0) {
  sheet.getRangeByIndexes(1, 0, filteredRows.length, headers.length).values =
    filteredRows;
}

const usedRows = Math.max(filteredRows.length + 1, 2);
const usedRange = sheet.getRangeByIndexes(0, 0, usedRows, headers.length);

sheet.getRangeByIndexes(0, 0, 1, headers.length).format = {
  fill: "#1F4E79",
  font: { bold: true, color: "#FFFFFF" },
};

usedRange.format = {
  font: { name: "Calibri", size: 11 },
  wrapText: false,
};

sheet.freezePanes.freezeRows(1);
sheet.tables.add(
  `A1:${String.fromCharCode(64 + headers.length)}${usedRows}`,
  true,
  "InjectionOnlyTable",
);

sheet.getRange("A:A").format.columnWidthPx = 70;
sheet.getRange("B:B").format.columnWidthPx = 180;
sheet.getRange("C:C").format.columnWidthPx = 100;
sheet.getRange("D:D").format.columnWidthPx = 90;
sheet.getRange("E:E").format.columnWidthPx = 110;

await fs.mkdir(outputDir, { recursive: true });

const preview = await workbook.render({
  sheetName: "Injection Only",
  autoCrop: "all",
  scale: 1,
  format: "png",
});
await fs.writeFile(
  `${outputDir}/drug-list-injection-only-preview.png`,
  new Uint8Array(await preview.arrayBuffer()),
);

const exported = await SpreadsheetFile.exportXlsx(workbook);
await exported.save(outputPath);

console.log(
  JSON.stringify({
    outputPath,
    rowCount: filteredRows.length,
    rows: filteredRows,
  }),
);
