export interface ApiResponse<T> {
  success: boolean;
  message: string;
  data: T;
}

export interface AuthUser {
  id: string;
  keycloakId: string;
  email: string;
  username: string;
  firstName: string;
  lastName: string;
  profilePicture: string | null;
  instituteId: string | null;
  active: boolean;
  roles: string[];
  lastLoginAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface LoginFormValues {
  email: string;
  password: string;
}

export interface RegisterFormValues {
  firstName: string;
  lastName: string;
  email: string;
}

export interface ForgotPasswordFormValues {
  email: string;
}

export type AuthProvider = "password" | "google";
