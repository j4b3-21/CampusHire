import { NextResponse, type NextRequest } from "next/server";

/**
 * Redirect-only routing hints. The cookie is a non-sensitive "probably signed in" flag written by the browser after
 * keycloak-js confirms a session; real authentication happens client-side (tokens never reach this server) and the
 * API validates every bearer token itself.
 */
const AUTH_HINT_COOKIE = "campushire_session_hint";

export function proxy(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const hinted = request.cookies.get(AUTH_HINT_COOKIE)?.value === "1";

  if (pathname === "/") {
    return NextResponse.redirect(new URL(hinted ? "/app" : "/login", request.url));
  }

  if (pathname.startsWith("/app") && !hinted) {
    const login = new URL("/login", request.url);
    login.searchParams.set("next", `${pathname}${search}`);
    return NextResponse.redirect(login);
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/", "/app/:path*"],
};
