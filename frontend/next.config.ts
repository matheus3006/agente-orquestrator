import type { NextConfig } from "next";

// Proxy same-origin (ADR-0013): o browser fala /api/* e o Next repassa ao
// Spring — sem CORS, e o cookie de sessão futuro nasce first-party. Tenancy
// NÃO passa por aqui (sem middleware/rewrite de tenant): a company vive no
// segmento [companySlug] e o CompanyLink prefixa os hrefs.
const backendOrigin = process.env.BACKEND_ORIGIN ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${backendOrigin}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
