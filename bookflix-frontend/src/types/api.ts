export type Role = 'READER' | 'ADMIN'
export type ShelfStatus = 'WANT_TO_READ' | 'READING' | 'COMPLETED'
export type BookSort = 'title' | 'newest' | 'year_desc' | 'year_asc' | 'rating' | 'popular'
export type Genre = { id: number; name: string; slug: string }
export type Author = { id: number; name: string; bio: string | null; createdAt: string }
export type User = { id: number; name: string; email: string; role: Role; onboardingCompleted: boolean; genres: Genre[]; createdAt: string }
export type Auth = { accessToken: string; tokenType: 'Bearer'; expiresIn: number; user: User }
export type Book = { id: number; title: string; author: Pick<Author, 'id' | 'name'>; coverUrl: string | null; publishedYear: number | null; genres: Genre[]; averageRating: number; ratingCount: number; likeCount: number }
export type BookState = { liked: boolean; rating: number | null; shelfStatus: ShelfStatus | null; progress: number | null }
export type BookDetail = Book & { description: string | null; isbn: string | null; language: string; createdAt: string; updatedAt: string; me: BookState | null }
export type BookRequest = { title: string; description?: string | null; authorId: number; isbn?: string | null; coverUrl?: string | null; publishedYear?: number | null; language?: string; genreIds: number[] }
export type Page<T> = { items: T[]; page: number; size: number; totalElements: number; totalPages: number }
export type Review = { id: number; bookId: number; user: Pick<User, 'id' | 'name'>; rating: number; comment: string | null; createdAt: string; updatedAt: string }
export type ShelfEntry = { bookId: number; book: Book; status: ShelfStatus; progress: number; updatedAt: string }
export type LikeStatus = { bookId: number; liked: boolean; likeCount: number }
export type UserStats = { likedCount: number; reviewCount: number; averageRatingGiven: number | null; bookshelf: Record<ShelfStatus, number> }
export type Recommendation = {
  shelf: 'FOR_YOU' | 'BECAUSE_YOU_LIKED' | 'SIMILAR' | 'TRENDING' | 'BY_GENRES'
  strategy: string; degraded: boolean; modelVersion: string | null; sourceBook: Book | null
  items: { book: Book; score: number; reason: string; strategy: string }[]
}
export type AdminStats = {
  users: number; readers: number; admins: number; books: number; deletedBooks: number; authors: number; genres: number; reviews: number; likes: number; bookshelfEntries: number
  model: { enabled: boolean; available: boolean; modelVersion: string | null; builtAt: string | null; bookCount: number | null; stale: boolean; lastError: string | null }
}
export type Rebuild = { status: 'REBUILT'; modelVersion: string; bookCount: number; builtAt: string; evaluation: { status: 'ok' | 'insufficient_data'; [key: string]: unknown } }
export type ErrorDetail = { field: string; message: string }
