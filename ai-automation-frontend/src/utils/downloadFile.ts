/**
 * Trigger a file download from a Blob.
 */
export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/**
 * Download a JSON object as a .json file.
 */
export function downloadJSON(data: unknown, filename: string) {
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
  downloadBlob(blob, filename);
}

/**
 * Download a file from a fetch Response, extracting filename from Content-Disposition header.
 */
export async function downloadFromResponse(res: Response, fallbackFilename: string) {
  const blob = await res.blob();
  const filename =
    res.headers.get('content-disposition')?.match(/filename="(.+)"/)?.[1] ?? fallbackFilename;
  downloadBlob(blob, filename);
}
