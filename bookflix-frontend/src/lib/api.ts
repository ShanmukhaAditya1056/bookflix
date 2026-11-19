import type { AdminStats, Auth, Author, Book, BookDetail, BookRequest, BookSort, ErrorDetail, Genre, LikeStatus, Page, Rebuild, Recommendation, Review, ShelfEntry, ShelfStatus, User, UserStats } from '../types/api'

export const demoMode = import.meta.env.VITE_API_MODE === 'demo'
const base = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/+$/, '')
const storageKey = demoMode ? 'bookflix.demo.session' : 'bookflix.live.session'
let session: { token: string; expiresAt: number } | null = readSession()
let unauthorized: () => void = () => {}

function readSession() {
  try { return JSON.parse(sessionStorage.getItem(storageKey) ?? 'null') } catch { return null }
}

export function setSession(auth: Auth | null) {
  session = auth ? { token: auth.accessToken, expiresAt: Date.now() + auth.expiresIn * 1000 } : null
  try {
    if (session) sessionStorage.setItem(storageKey, JSON.stringify(session))
    else sessionStorage.removeItem(storageKey)
  } catch { /* Memory remains available when storage is disabled. */ }
}

export function getSession() {
  if (session && session.expiresAt <= Date.now()) {
    setSession(null)
    unauthorized()
  }
  return session?.token ?? null
}

export function onUnauthorized(handler: () => void) {
  unauthorized = handler
  return () => { unauthorized = () => {} }
}

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public details: ErrorDetail[] = []) { super(message) }
}

function httpError(status: number, payload?: { code?: string; message?: string; details?: ErrorDetail[] }) {
  const messages: Record<number, string> = {
    401: 'Sign in to continue.', 403: 'You do not have access to this action.',
    404: 'This item could not be found.', 429: 'Too many requests. Please try again shortly.',
  }
  return new ApiError(status, payload?.code ?? 'REQUEST_FAILED',
    status >= 500 && status !== 503 ? 'Bookflix could not complete this request. Try again shortly.' : payload?.message || messages[status] || 'Something went wrong.', payload?.details)
}

type Options = { method?: string; body?: unknown; signal?: AbortSignal; auth?: boolean }
async function request<T>(path: string, { method = 'GET', body, signal, auth = true }: Options = {}): Promise<T> {
  const token = auth ? getSession() : null
  try {
    if (demoMode) {
      const { demoRequest } = await import('./demo')
      signal?.throwIfAborted()
      return await demoRequest(path, method, body, token) as T
    }
    let response: Response
    try {
      response = await fetch(base + '/api' + path, {
        method, signal,
        headers: { Accept: 'application/json', ...(token && { Authorization: 'Bearer ' + token }), ...(body !== undefined && { 'Content-Type': 'application/json' }) },
        body: body === undefined ? undefined : JSON.stringify(body),
      })
    } catch (error) {
      if (signal?.aborted) throw error
      throw new ApiError(0, 'NETWORK_ERROR', 'Bookflix could not reach the server. Check your connection and try again.')
    }
    if (!response.ok) throw httpError(response.status, await response.json().catch(() => undefined))
    return response.status === 204 ? undefined as T : response.json()
  } catch (error) {
    if (error instanceof ApiError && error.status === 401 && token && session?.token === token) {
      setSession(null)
      unauthorized()
    }
    throw error
  }
}

function query(path: string, values: Record<string, string | number | undefined>) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(values)) if (value !== undefined && value !== '') params.set(key, String(value))
  return params.size ? path + '?' + params : path
}

type Catalog = { page?: number; size?: number; q?: string; genreId?: number; sort?: BookSort }
function write<T>(path: string, method: 'POST' | 'PUT' | 'DELETE', body?: unknown) {
  return request<T>(path, { method, body })
}

export const api = {
  authenticate: (mode: 'login' | 'register', body: { email: string; password: string; name?: string }) => request<Auth>('/auth/' + mode, { method: 'POST', auth: false, body }),
  me: (signal?: AbortSignal) => request<User>('/users/me', { signal }),
  updateName: (name: string) => write<User>('/users/me', 'PUT', { name }),
  updateGenres: (genreIds: number[]) => write<User>('/users/me/genres', 'PUT', { genreIds }),
  stats: (signal?: AbortSignal) => request<UserStats>('/users/me/stats', { signal }),
  books: (options: Catalog = {}, signal?: AbortSignal) => request<Page<Book>>(query('/books', options), { signal }),
  book: (id: number, signal?: AbortSignal) => request<BookDetail>('/books/' + id, { signal }),
  genres: (signal?: AbortSignal) => request<Genre[]>('/genres', { signal }),
  authors: (signal?: AbortSignal) => request<Author[]>('/authors', { signal }),
  like: (id: number, liked: boolean) => write<LikeStatus>('/books/' + id + '/like', liked ? 'POST' : 'DELETE'),
  reviews: (id: number, page: number, signal?: AbortSignal) => request<Page<Review>>(query('/books/' + id + '/reviews', { page, size: 10 }), { signal }),
  review: (id: number, body: { rating: number; comment?: string }) => write<Review>('/books/' + id + '/reviews', 'POST', body),
  removeReview: (id: number) => write<void>('/books/' + id + '/reviews/me', 'DELETE'),
  shelf: (status: ShelfStatus, signal?: AbortSignal) => request<ShelfEntry[]>(query('/bookshelf', { status }), { signal }),
  saveShelf: (id: number, body: { status: ShelfStatus; progress?: number }) => write<ShelfEntry>('/bookshelf/' + id, 'PUT', body),
  removeShelf: (id: number) => write<void>('/bookshelf/' + id, 'DELETE'),
  recommendations: (shelf: 'for-you' | 'because-you-liked' | 'trending' | 'genres' | `similar/${number}`, signal?: AbortSignal) =>
    request<Recommendation>(query('/recommendations/' + shelf, { limit: 12 }), { signal }),
  adminStats: (signal?: AbortSignal) => request<AdminStats>('/admin/stats', { signal }),
  rebuild: () => write<Rebuild>('/admin/recommendations/rebuild', 'POST'),
  saveBook: (id: number | null, body: BookRequest) => write<BookDetail>(id ? '/admin/books/' + id : '/admin/books', id ? 'PUT' : 'POST', body),
  saveAuthor: (id: number | null, body: { name: string; bio?: string }) => write<Author>(id ? '/admin/authors/' + id : '/admin/authors', id ? 'PUT' : 'POST', body),
  saveGenre: (id: number | null, body: { name: string; slug?: string }) => write<Genre>(id ? '/admin/genres/' + id : '/admin/genres', id ? 'PUT' : 'POST', body),
  deleteCatalog: (entity: 'books' | 'authors' | 'genres', id: number) => write<void>('/admin/' + entity + '/' + id, 'DELETE'),
}
