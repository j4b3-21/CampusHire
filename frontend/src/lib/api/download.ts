import { request, type QueryInit } from "@/lib/api/client";

/** File name from `Content-Disposition` (`filename*=UTF-8''…` wins over `filename="…"`). */
export function filenameFromDisposition(header: string | null, fallback: string) {
  if (!header) return fallback;
  const extended = header.match(/filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)/);
  if (extended) {
    try {
      return decodeURIComponent(extended[1].trim().replace(/^"|"$/g, ""));
    } catch {
      // fall through to the plain parameter
    }
  }
  const plain = header.match(/filename\s*=\s*"?([^";]+)"?/);
  return plain ? plain[1].trim() : fallback;
}

export function saveBlob(blob: Blob, name: string) {
  const objectUrl = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = objectUrl;
  anchor.download = name;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(objectUrl), 1_000);
}

/** Authenticated GET of a binary resource, saved under the server's file name (or `fallbackName`). */
export async function downloadBlob(path: string, { query, fallbackName }: { query?: QueryInit; fallbackName: string }) {
  const result = await request<Blob>(path, { query, responseType: "blob" });
  const name = filenameFromDisposition(result.headers.get("Content-Disposition"), fallbackName);
  saveBlob(result.data, name);
  return name;
}
