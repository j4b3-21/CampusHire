import { NextRequest, NextResponse } from "next/server";

const AUTH_COOKIE = "campushire_auth";
const PUBLIC_PATHS = ["/login", "/register", "/forgot-password"];
export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const isAuthenticated = request.cookies.get(AUTH_COOKIE)?.value === "1";

  if (pathname === "/") {
    return NextResponse.redirect(new URL(isAuthenticated ? "/dashboard" : "/login", request.url));
  }

  if (PUBLIC_PATHS.some((path) => pathname.startsWith(path)) && isAuthenticated) {
    return NextResponse.redirect(new URL("/dashboard", request.url));
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/", "/login/:path*", "/register/:path*", "/forgot-password/:path*", "/dashboard/:path*"],
};
