import axios, { AxiosError, type InternalAxiosRequestConfig } from "axios";

import { clearAuthHintCookie } from "@/lib/session-cookie";
import { getAccessToken, refreshToken, logout } from "@/lib/keycloak";

declare module "axios" {
  export interface InternalAxiosRequestConfig {
    _retry?: boolean;
  }
}

const baseURL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export const api = axios.create({
  baseURL,
  withCredentials: true,
});

api.interceptors.request.use(async (config: InternalAxiosRequestConfig) => {
  const token = getAccessToken();
  if (token) {
    config.headers = config.headers ?? {};
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const status = error.response?.status;
    const originalRequest = error.config as InternalAxiosRequestConfig | undefined;

    if (status === 401 && originalRequest && !originalRequest._retry) {
      originalRequest._retry = true;

      try {
        await refreshToken(10);
        return api.request(originalRequest);
      } catch {
        clearAuthHintCookie();
        if (typeof window !== "undefined") {
          await logout({ redirectUri: `${window.location.origin}/login` });
        }
      }
    }

    return Promise.reject(error);
  }
);

export async function fetchCurrentUser<T>() {
  console.log("Fetching current user...");
  const response = await api.get<T>("/auth/me");
  console.log("Current user fetched:", response.data);
  return response.data;
}
