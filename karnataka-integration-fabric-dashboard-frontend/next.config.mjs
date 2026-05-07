/** @type {import('next').NextConfig} */
const nextConfig = {
  output: "standalone",
  async rewrites() {
    // In Docker (production), default to the container name 'fabric-api'
    const isProd = process.env.NODE_ENV === "production";
    const apiUrl = process.env.API_URL || (isProd ? "http://fabric-api:8080" : "http://localhost:8080");
    return [
      {
        source: "/api/:path*",
        destination: `${apiUrl}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
