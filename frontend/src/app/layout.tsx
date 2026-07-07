import type { Metadata } from "next";
import { Chakra_Petch, IBM_Plex_Mono, IBM_Plex_Sans } from "next/font/google";
import { Providers } from "./providers";
import "./globals.css";

const chakra = Chakra_Petch({
  subsets: ["latin"],
  weight: ["400", "500", "600", "700"],
  variable: "--font-chakra",
});

const plexSans = IBM_Plex_Sans({
  subsets: ["latin"],
  weight: ["400", "500", "600"],
  variable: "--font-plex",
});

const plexMono = IBM_Plex_Mono({
  subsets: ["latin"],
  weight: ["400", "500", "600"],
  variable: "--font-plex-mono",
});

export const metadata: Metadata = {
  title: "Orquestrador",
  description: "Control-plane de orquestração de times de agentes de IA",
};

// Anti-flash de tema (ADR-0013): v1 é dark-only, então a classe ESTÁTICA no
// <html> servido já elimina qualquer flash — nenhum script pré-paint a rodar.
// Quando o light theme existir, entra aqui o script inline lendo `orq.theme`.
export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="pt-BR" className="dark">
      <body className={`${chakra.variable} ${plexSans.variable} ${plexMono.variable}`}>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
