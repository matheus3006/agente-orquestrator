import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

/** O `cn` padrão shadcn (copy-in): clsx + tailwind-merge. */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
