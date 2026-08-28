import { apiClient } from './client'
import type {
  AuthResponse,
  LoginRequest,
  MessageResponse,
  RegisterRequest,
  RegisterResponse,
  User,
} from '../types'

export async function login(payload: LoginRequest): Promise<AuthResponse> {
  const { data } = await apiClient.post<AuthResponse>('/auth/login', payload)
  return data
}

export async function register(
  payload: RegisterRequest,
): Promise<RegisterResponse> {
  const { data } = await apiClient.post<RegisterResponse>(
    '/auth/register',
    payload,
  )
  return data
}

export async function verifyEmail(token: string): Promise<MessageResponse> {
  const { data } = await apiClient.get<MessageResponse>('/auth/verify', {
    params: { token },
  })
  return data
}

export async function resendVerification(
  email: string,
): Promise<MessageResponse> {
  const { data } = await apiClient.post<MessageResponse>(
    '/auth/resend-verification',
    { email },
  )
  return data
}

export async function verifyCode(
  email: string,
  code: string,
): Promise<MessageResponse> {
  const { data } = await apiClient.post<MessageResponse>('/auth/verify-code', {
    email,
    code,
  })
  return data
}

export async function getCurrentUser(): Promise<User> {
  const { data } = await apiClient.get<User>('/auth/me')
  return data
}
