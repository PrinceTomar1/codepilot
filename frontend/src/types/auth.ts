export interface User {
  id: string
  email: string
  name: string
}

export interface AuthResponse {
  token: string
  user: User
}

export interface LoginRequest {
  email: string
  password: string
}

export interface RegisterRequest {
  email: string
  password: string
  name: string
}

export interface RegisterResponse {
  user: User
  message: string
}

export interface MessageResponse {
  message: string
}
