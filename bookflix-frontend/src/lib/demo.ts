import catalog from './demo-catalog.json'
import { ApiError } from './api'
import { shelfStatuses } from './labels'
import type { Author, Book, BookDetail, BookRequest, Genre, Page, Recommendation, Review, ShelfEntry, ShelfStatus, User } from '../types/api'

type Account = User & { passwordHash?: string }
type StoredBook = Omit<BookDetail, 'me'>
type Entry = Omit<ShelfEntry, 'book'> & { userId: number }
type Database = {
  nextId: number; users: Account[]; genres: Genre[]; authors: Author[]; books: StoredBook[]
  likes: { userId: number; bookId: number; updatedAt: string }[]
  entries: Entry[]; reviews: Review[]; deletedBooks: number
}

const databaseKey = 'bookflix.demo.database.v1'
const demoDate = '2026-01-01T00:00:00.000Z'
const seedAuthors = catalog.authors.map(author => ({ ...author, createdAt: demoDate }))
const seedBooks = catalog.books.map(({ authorId, year, genres, ...book }) => {
  const author = seedAuthors.find(item => item.id === authorId)!
  return { ...book, author: { id: author.id, name: author.name }, publishedYear: year,
    genres: catalog.genres.filter(genre => genres.includes(genre.slug)), coverUrl: null, isbn: null,
    language: 'en', createdAt: demoDate, updatedAt: demoDate, likeCount: 0, averageRating: 0, ratingCount: 0 }
})
const demoAdmin: Account = { id: 1, name: 'Demo Admin', email: 'admin@example.test', role: 'ADMIN', onboardingCompleted: true, genres: [], createdAt: demoDate }
let db: Database = { nextId: 1000, users: [demoAdmin], genres: catalog.genres, authors: seedAuthors, books: seedBooks, likes: [], entries: [], reviews: [], deletedBooks: 0 }
try {
  const saved = localStorage.getItem(databaseKey)
  if (saved) db = JSON.parse(saved)
} catch { /* Demo can run in memory. */ }

function save() {
  try { localStorage.setItem(databaseKey, JSON.stringify(db)) } catch { /* Demo can run in memory. */ }
}

const now = () => new Date().toISOString()
const byName = <T extends { name: string }>(a: T, b: T) => a.name.localeCompare(b.name)

function fail(status: number, code: string, message: string): never {
  throw new ApiError(status, code, message)
}

function missing(label: string): never { return fail(404, 'NOT_FOUND', label + ' could not be found.') }
function conflict(message: string): never { return fail(409, 'CONFLICT', message) }
function invalid(message: string): never { return fail(400, 'BAD_REQUEST', message) }

function publicUser(user: Account): User {
  const { passwordHash, ...profile } = user
  void passwordHash
  return profile
}

async function hash(email: string, password: string) {
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(email + '\0' + password))
  return Array.from(new Uint8Array(bytes), byte => byte.toString(16).padStart(2, '0')).join('')
}

function getBook(id: number) { return db.books.find(book => book.id === id) ?? missing('Book') }

function summary(book: StoredBook): Book {
  const ratings = db.reviews.filter(review => review.bookId === book.id)
  const author = db.authors.find(item => item.id === book.author.id)!
  return { id: book.id, title: book.title, author: { id: author.id, name: author.name },
    coverUrl: book.coverUrl, publishedYear: book.publishedYear,
    genres: book.genres.flatMap(genre => db.genres.find(item => item.id === genre.id) ?? []),
    likeCount: db.likes.filter(like => like.bookId === book.id).length, ratingCount: ratings.length,
    averageRating: ratings.length ? Math.round(ratings.reduce((total, review) => total + review.rating, 0) / ratings.length * 100) / 100 : 0 }
}

function detail(book: StoredBook, user?: Account): BookDetail {
  const shelf = db.entries.find(entry => entry.bookId === book.id && entry.userId === user?.id)
  return { ...book, ...summary(book), me: user ? {
    liked: db.likes.some(like => like.bookId === book.id && like.userId === user.id),
    rating: db.reviews.find(review => review.bookId === book.id && review.user.id === user.id)?.rating ?? null,
    shelfStatus: shelf?.status ?? null, progress: shelf?.progress ?? null,
  } : null }
}

function page<T>(items: T[], params: URLSearchParams, defaultSize = 20): Page<T> {
  const index = Number(params.get('page') ?? 0)
  const size = Number(params.get('size') ?? defaultSize)
  return { items: items.slice(index * size, (index + 1) * size), page: index, size, totalElements: items.length, totalPages: Math.ceil(items.length / size) }
}

function recommendations(route: string, limit: number, user?: Account, id?: number): Recommendation {
  const books = db.books.map(summary)
  const ranked = [...books].sort((a, b) => b.likeCount - a.likeCount || b.averageRating - a.averageRating || a.id - b.id)
  const scoreGenre = (book: Book, genreIds: number[]) => book.genres.filter(genre => genreIds.includes(genre.id)).length
  const matching = (genreIds: number[]) => ranked.filter(book => scoreGenre(book, genreIds))
    .sort((a, b) => scoreGenre(b, genreIds) - scoreGenre(a, genreIds))
  const personalGenres = user?.genres.map(genre => genre.id) ?? []
  const activeBook = (bookId: number) => books.find(book => book.id === bookId)
  const liked = db.likes.filter(like => like.userId === user?.id)
  const reviewed = db.reviews.filter(review => review.user.id === user?.id)
  const seen = new Set([...liked, ...reviewed, ...db.entries.filter(entry => entry.userId === user?.id)].map(item => item.bookId))
  const lastLike = [...liked].reverse().map(like => activeBook(like.bookId)).find(Boolean)
  const lastRated = reviewed.filter(review => review.rating >= 4).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)).map(review => activeBook(review.bookId)).find(Boolean)
  const sourceBook = route === 'similar' ? summary(getBook(id!)) : route === 'because-you-liked' ? lastLike ?? lastRated ?? null : null
  const shelves = {
    'trending': { shelf: 'TRENDING', strategy: 'TRENDING', candidates: ranked, reason: 'Popular in the demo catalog' },
    'for-you': { shelf: 'FOR_YOU', strategy: 'FALLBACK_GENRE_POPULARITY',
      candidates: [...new Map([...matching(personalGenres), ...ranked].map(book => [book.id, book])).values()].filter(book => !seen.has(book.id)), reason: 'Demo picks from the catalog' },
    'genres': { shelf: 'BY_GENRES', strategy: 'GENRE_MATCH', candidates: matching(personalGenres), reason: 'Matches your selected genres' },
    'similar': { shelf: 'SIMILAR', strategy: 'FALLBACK_METADATA_SIMILARITY', candidates: matching(sourceBook?.genres.map(genre => genre.id) ?? []), reason: 'Shares genres with this book' },
    'because-you-liked': { shelf: 'BECAUSE_YOU_LIKED', strategy: sourceBook ? 'FALLBACK_METADATA_SIMILARITY' : 'NONE',
      candidates: matching(sourceBook?.genres.map(genre => genre.id) ?? []), reason: 'Because you liked ' + sourceBook?.title },
  } as const
  const result = shelves[route as keyof typeof shelves]
  if (!result) missing('Shelf')
  return { shelf: result.shelf, strategy: result.strategy, sourceBook, modelVersion: null,
    degraded: route === 'for-you' || route === 'similar' || (route === 'because-you-liked' && sourceBook !== null),
    items: result.candidates.filter(book => book.id !== sourceBook?.id).slice(0, limit).map((book, index) => ({
      book, score: 1 / (index + 1), reason: result.reason, strategy: result.strategy,
    })) }
}

function adminCatalog(collection: string, id: number, method: string, body: unknown) {
  if (collection === 'books') {
    if (method === 'DELETE') {
      getBook(id)
      db.books = db.books.filter(book => book.id !== id)
      db.deletedBooks++
      save()
      return
    }
    const input = body as BookRequest
    const existing = id ? getBook(id) : null
    const author = db.authors.find(item => item.id === input.authorId) ?? invalid('Select an existing author.')
    const genres = db.genres.filter(genre => input.genreIds.includes(genre.id))
    if (genres.length < 1 || genres.length > 10) invalid('Select between one and ten genres.')
    if (input.isbn && db.books.some(book => book.id !== id && book.isbn === input.isbn?.replaceAll('-', ''))) conflict('This ISBN is already in use.')
    const book: StoredBook = { id: existing?.id ?? db.nextId++, title: input.title, author, description: input.description ?? null,
      isbn: input.isbn?.replaceAll('-', '') || null, coverUrl: input.coverUrl ?? null, publishedYear: input.publishedYear ?? null,
      language: input.language || 'en', genres, averageRating: 0, ratingCount: 0, likeCount: 0,
      createdAt: existing?.createdAt ?? now(), updatedAt: now() }
    db.books = [...db.books.filter(item => item.id !== book.id), book]
    save()
    return detail(book)
  }
  if (collection === 'authors') {
    const existing = id ? db.authors.find(item => item.id === id) ?? missing('Author') : null
    if (method === 'DELETE') {
      if (db.books.some(book => book.author.id === id)) conflict('Books still reference this author.')
      db.authors = db.authors.filter(item => item.id !== id)
      save()
      return
    }
    const input = body as { name: string; bio?: string }
    const author = { id: existing?.id ?? db.nextId++, name: input.name, bio: input.bio || null, createdAt: existing?.createdAt ?? now() }
    db.authors = [...db.authors.filter(item => item.id !== author.id), author]
    save()
    return author
  }
  if (collection === 'genres') {
    const existing = id ? db.genres.find(item => item.id === id) ?? missing('Genre') : null
    if (method === 'DELETE') {
      if (db.books.some(book => book.genres.some(genre => genre.id === id))) conflict('Active books still use this genre.')
      db.genres = db.genres.filter(item => item.id !== id)
      db.users.forEach(user => { user.genres = user.genres.filter(genre => genre.id !== id) })
      save()
      return
    }
    const input = body as { name: string; slug?: string }
    const slug = input.slug || input.name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
    if (db.genres.some(genre => genre.id !== id && (genre.slug === slug || genre.name.toLowerCase() === input.name.toLowerCase()))) conflict('This genre name or slug is already in use.')
    const genre = { id: existing?.id ?? db.nextId++, name: input.name, slug }
    db.genres = [...db.genres.filter(item => item.id !== genre.id), genre]
    db.users.forEach(user => { user.genres = user.genres.map(item => item.id === genre.id ? genre : item) })
    save()
    return genre
  }
  missing('Endpoint')
}

export async function demoRequest(path: string, method: string, body: unknown, token: string | null): Promise<unknown> {
  const [pathname, search] = path.split('?')
  const params = new URLSearchParams(search)
  const [, resource, action, nested, leaf] = pathname.split('/')
  const user = token ? db.users.find(account => token === 'demo-' + account.id) : undefined
  if (token && !user) fail(401, 'UNAUTHORIZED', 'Sign in to continue.')
  const account = () => user ?? fail(401, 'UNAUTHORIZED', 'Sign in to continue.')
  const bookId = Number(action)

  if (resource === 'auth') {
    const input = body as { email: string; password: string; name?: string }
    const email = input.email.trim().toLowerCase()
    const passwordHash = await hash(email, input.password)
    let current = db.users.find(item => item.email === email)
    if (action === 'register') {
      if (current) fail(409, 'EMAIL_ALREADY_EXISTS', 'An account already uses this email.')
      current = { id: db.nextId++, name: input.name!, email, passwordHash, role: 'READER', onboardingCompleted: false, genres: [], createdAt: now() }
      db.users.push(current)
      save()
    } else if (!current || (current.id === demoAdmin.id ? input.password !== 'bookflix-demo' : current.passwordHash !== passwordHash)) {
      fail(401, 'INVALID_CREDENTIALS', 'Check your email and password.')
    }
    return { tokenType: 'Bearer', accessToken: 'demo-' + current.id, expiresIn: 7200, user: publicUser(current) }
  }

  if (resource === 'users') {
    const current = account()
    if (nested === 'stats') {
      const reviews = db.reviews.filter(review => review.user.id === current.id)
      return { likedCount: db.likes.filter(like => like.userId === current.id && db.books.some(book => book.id === like.bookId)).length,
        reviewCount: reviews.length, averageRatingGiven: reviews.length ? reviews.reduce((sum, review) => sum + review.rating, 0) / reviews.length : null,
        bookshelf: Object.fromEntries(shelfStatuses.map(status => [status, db.entries.filter(entry => entry.userId === current.id && entry.status === status && db.books.some(book => book.id === entry.bookId)).length])),
      }
    }
    if (method === 'PUT') {
      if (nested === 'genres') {
        const { genreIds } = body as { genreIds: number[] }
        current.genres = db.genres.filter(genre => genreIds.includes(genre.id))
        current.onboardingCompleted = true
      } else current.name = (body as { name: string }).name
      save()
    }
    return publicUser(current)
  }

  if (resource === 'genres') return [...db.genres].sort(byName)
  if (resource === 'authors') return [...db.authors].sort(byName)

  if (resource === 'books' && !action) {
    const words = (params.get('q') ?? '').trim().toLowerCase().split(/\s+/).filter(Boolean)
    const genreId = Number(params.get('genreId'))
    const books = db.books.map(summary).filter(book => {
      const haystack = (book.title + ' ' + book.author.name + ' ' + getBook(book.id).description).toLowerCase()
      return words.every(word => haystack.includes(word)) && (!genreId || book.genres.some(genre => genre.id === genreId))
    })
    const sort = params.get('sort')
    const compareTitle = (a: Book, b: Book) => a.title.localeCompare(b.title)
    books.sort((a, b) => {
      switch (sort) {
        case 'newest': return getBook(b.id).createdAt.localeCompare(getBook(a.id).createdAt) || b.id - a.id
        case 'year_desc': return (b.publishedYear ?? -Infinity) - (a.publishedYear ?? -Infinity) || compareTitle(a, b)
        case 'year_asc': return (a.publishedYear ?? Infinity) - (b.publishedYear ?? Infinity) || compareTitle(a, b)
        case 'rating': return b.averageRating - a.averageRating || compareTitle(a, b)
        case 'popular': return b.likeCount - a.likeCount || compareTitle(a, b)
        default: return compareTitle(a, b)
      }
    })
    return page(books, params)
  }

  if (resource === 'books') {
    const book = getBook(bookId)
    if (nested === 'like') {
      const { id } = account()
      const liked = method === 'POST'
      const matches = (like: Database['likes'][number]) => like.userId === id && like.bookId === bookId
      if (liked && !db.likes.some(matches)) db.likes.push({ userId: id, bookId, updatedAt: now() })
      if (!liked) db.likes = db.likes.filter(like => !matches(like))
      save()
      return { bookId, liked, likeCount: summary(book).likeCount }
    }
    if (nested === 'reviews') {
      if (method === 'GET') return page(db.reviews.filter(review => review.bookId === bookId)
        .map(review => ({ ...review, user: { ...review.user, name: db.users.find(item => item.id === review.user.id)?.name ?? review.user.name } }))
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt)), params, 10)
      const { id, name } = account()
      const matches = (review: Review) => review.bookId === bookId && review.user.id === id
      if (method === 'DELETE' && leaf === 'me') {
        db.reviews = db.reviews.filter(review => !matches(review))
        save()
        return
      }
      const existing = db.reviews.find(matches)
      const input = body as { rating: number; comment?: string }
      const review: Review = { id: existing?.id ?? db.nextId++, bookId, user: { id, name }, rating: input.rating,
        comment: input.comment || null, createdAt: existing?.createdAt ?? now(), updatedAt: now() }
      db.reviews = [...db.reviews.filter(item => !matches(item)), review]
      save()
      return review
    }
    return detail(book, user)
  }

  if (resource === 'bookshelf') {
    const { id } = account()
    const asEntry = ({ bookId, status, progress, updatedAt }: Entry): ShelfEntry => ({ bookId, status, progress, updatedAt, book: summary(getBook(bookId)) })
    if (method === 'GET') return db.entries.filter(entry => entry.userId === id && db.books.some(book => book.id === entry.bookId)
      && (!params.has('status') || params.get('status') === entry.status))
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)).map(asEntry)
    getBook(bookId)
    const matches = (entry: Entry) => entry.userId === id && entry.bookId === bookId
    const existing = db.entries.find(matches)
    db.entries = db.entries.filter(entry => !matches(entry))
    if (method === 'DELETE') {
      save()
      return
    }
    const { status, progress } = body as { status: ShelfStatus; progress?: number }
    const entry = { userId: id, bookId, status, progress: status === 'COMPLETED' ? 100 : progress ?? existing?.progress ?? 0, updatedAt: now() }
    db.entries.push(entry)
    save()
    return asEntry(entry)
  }

  if (resource === 'recommendations') {
    if (action !== 'similar' && action !== 'trending') account()
    return recommendations(action, Number(params.get('limit') ?? 12), user, Number(nested))
  }

  if (resource === 'admin') {
    if (account().role !== 'ADMIN') fail(403, 'FORBIDDEN', 'Administrator access is required.')
    if (action === 'stats') return {
      users: db.users.length, readers: db.users.length - 1, admins: 1, books: db.books.length, deletedBooks: db.deletedBooks, authors: db.authors.length,
      genres: db.genres.length, reviews: db.reviews.length, likes: db.likes.length, bookshelfEntries: db.entries.length,
      model: { enabled: false, available: false, modelVersion: null, builtAt: null, bookCount: null, stale: false, lastError: null },
    }
    if (action === 'recommendations') fail(503, 'ML_UNAVAILABLE', 'Rebuilding the recommendation model requires the live API.')
    return adminCatalog(action, Number(nested), method, body)
  }
  missing('Endpoint')
}
