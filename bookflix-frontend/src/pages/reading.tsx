import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Heart } from 'lucide-react'
import { Link, useLocation, useParams, useSearchParams } from 'react-router-dom'
import { useMe, useSession } from '../app/session'
import { BookCard, BookCarousel, BookCover, GenreChip, Pagination, RatingStars } from '../components/catalog'
import { ConfirmDialog, EmptyState, ErrorState, FormError, LoadingSkeleton, NotFound, useToast } from '../components/states'
import { BookshelfStatusControl } from '../features/shelf-control'
import { interactionKeys, useInvalidate, useRecommendations, useShelf } from '../hooks/catalog'
import { api, ApiError } from '../lib/api'
import { shelfLabels, shelfStatuses } from '../lib/labels'
import type { BookDetail, Review, ShelfStatus } from '../types/api'

function ReviewForm({ bookId, review }: { bookId: number; review?: Review }) {
  const [confirming, setConfirming] = useState(false)
  const [rating, setRating] = useState(String(review?.rating ?? ''))
  const [comment, setComment] = useState(review?.comment ?? '')
  const toast = useToast()
  const invalidate = useInvalidate()
  const save = useMutation({ mutationFn: () => api.review(bookId, { rating: Number(rating), comment: comment.trim() }), onSuccess: async () => {
    await invalidate(interactionKeys)
    toast('Review saved.')
  } })
  const remove = useMutation({ mutationFn: () => api.removeReview(bookId), onSuccess: async () => {
    setConfirming(false)
    await invalidate(interactionKeys)
    toast('Review removed.')
  } })

  return <>
    <form className="form" onSubmit={event => { event.preventDefault(); save.mutate() }}>
      <label className="self-start">Your rating<select aria-label="Your rating" required value={rating} onChange={event => setRating(event.target.value)}>
        <option value="" disabled>Select a rating</option>
        {[5, 4, 3, 2, 1].map(value => <option key={value} value={value}>{value} {value === 1 ? 'star' : 'stars'}</option>)}
      </select></label>
      <label>Your review (optional)<textarea aria-label="Your review (optional)" rows={4} maxLength={2000} value={comment} onChange={event => setComment(event.target.value)} /></label>
      <FormError error={save.error} />
      <div className="actions">
        <button className="btn primary" disabled={save.isPending || remove.isPending}>{save.isPending ? 'Saving…' : review ? 'Update review' : 'Post review'}</button>
        {review && <button type="button" className="btn secondary" disabled={save.isPending || remove.isPending} onClick={() => { remove.reset(); setConfirming(true) }}>Delete review</button>}
      </div>
    </form>
    {confirming && <ConfirmDialog title="Delete your review?" pending={remove.isPending} error={remove.error}
      cancel={() => setConfirming(false)} confirm={() => remove.mutate()}>This removes your rating and any written feedback.</ConfirmDialog>}
  </>
}

function BookReviews({ book }: { book: BookDetail }) {
  const me = useMe()
  const { signedIn } = useSession()
  const [page, setPage] = useState(0)
  const reviews = useQuery({ queryKey: ['reviews', book.id, page], queryFn: ({ signal }) => api.reviews(book.id, page, signal) })
  const userId = me.data?.id
  const mine = useQuery({ queryKey: ['reviews', book.id, 'mine', userId],
    enabled: userId !== undefined && book.me?.rating != null,
    queryFn: async ({ signal }) => {
      for (let index = 0;; index++) {
        const result = await api.reviews(book.id, index, signal)
        const own = result.items.find(review => review.user.id === userId)
        if (own) return own
        if (index + 1 >= result.totalPages) return null
      }
    },
  })

  function setReviewPage(index: number) { setPage(index) }

  return <section className="surface stacked"><h2>Reader reviews</h2>
    {signedIn && (book.me?.rating != null && !mine.data ? (
      mine.isError ? <ErrorState error={mine.error} retry={() => void mine.refetch()} /> :
        mine.isPending ? <LoadingSkeleton /> : <ReviewForm bookId={book.id} />
    ) : <ReviewForm key={book.me?.rating == null ? 'new' : mine.data?.updatedAt} bookId={book.id} review={book.me?.rating == null ? undefined : mine.data ?? undefined} />)}
    {reviews.isPending ? <LoadingSkeleton /> : reviews.isError ? <ErrorState error={reviews.error} retry={() => void reviews.refetch()} /> :
      <>
        {reviews.data.items.length ? <div className="review-list">{reviews.data.items.map(item =>
          <article key={item.id}><div className="review-head"><strong>{item.user.name}</strong><RatingStars average={item.rating} />
            <time dateTime={item.createdAt}>{new Date(item.createdAt).toLocaleDateString()}</time></div>{item.comment && <p>{item.comment}</p>}</article>)}</div> :
          page > 0 ? <EmptyState title="No reviews on this page."><button className="btn secondary" onClick={() => setReviewPage(0)}>Back to first page</button></EmptyState> :
            <EmptyState title="No reader reviews yet." />}
        <Pagination page={page} total={reviews.data.totalPages} onChange={setReviewPage} />
      </>}
  </section>
}

function BookActions({ book }: { book: BookDetail }) {
  const { signedIn } = useSession()
  const location = useLocation()
  const invalidate = useInvalidate()
  const toast = useToast()
  const like = useMutation({ mutationFn: () => api.like(book.id, !book.me?.liked), onSuccess: async response => {
    await invalidate(interactionKeys)
    toast(response.liked ? 'Book liked.' : 'Like removed.')
  } })
  if (!signedIn) return <Link className="btn primary" to="/login" state={{ next: location.pathname }}>Sign in to save or review</Link>
  if (!book.me) return <LoadingSkeleton />
  return <div className="stacked">
    <div><button className="btn secondary" onClick={() => like.mutate()} aria-pressed={book.me.liked} disabled={like.isPending}>
      <Heart size={18} aria-hidden="true" fill={book.me.liked ? 'currentColor' : 'none'} /> {book.me.liked ? 'Liked' : 'Like book'}
    </button><FormError error={like.error} /></div>
    <BookshelfStatusControl key={book.me.shelfStatus + ':' + book.me.progress} bookId={book.id} current={book.me.shelfStatus} progress={book.me.progress ?? 0} />
  </div>
}

function SimilarBooks({ id }: { id: number }) {
  const similar = useRecommendations('similar/' + id as `similar/${number}`)
  if (similar.isPending) return <section className="shelf"><h2>Similar books</h2><LoadingSkeleton cards /></section>
  if (similar.isError) return <section className="shelf"><h2>Similar books</h2><ErrorState error={similar.error} retry={() => void similar.refetch()} /></section>
  if (!similar.data.items.length) return <section className="shelf"><h2>Similar books</h2><EmptyState title="No similar books found." /></section>
  return <BookCarousel title="Similar books" items={similar.data.items} note={similar.data.degraded ? 'Basic recommendations' : undefined} />
}

function BookDetails({ id }: { id: number }) {
  const detail = useQuery({ queryKey: ['book', id], queryFn: ({ signal }) => api.book(id, signal) })
  if (detail.isPending) return <div className="container section"><LoadingSkeleton /></div>
  if (detail.isError) {
    if (detail.error instanceof ApiError && detail.error.status === 404) return <NotFound />
    return <div className="container section"><ErrorState error={detail.error} retry={() => void detail.refetch()} /></div>
  }
  const book = detail.data
  return <div className="container section stacked">
    <section className="detail-hero">
      <div className="detail-cover"><BookCover book={book} eager /></div>
      <div className="stacked detail-copy">
        <div><h1>{book.title}</h1><p className="detail-author">by {book.author.name}</p></div>
        <div className="actions"><RatingStars average={book.averageRating} count={book.ratingCount} /><span className="muted">{book.likeCount} {book.likeCount === 1 ? 'like' : 'likes'}</span></div>
        <div className="chips">{book.genres.map(genre => <GenreChip genre={genre} key={genre.id} />)}</div>
        <p className="description">{book.description || 'A description is unavailable for this book.'}</p>
        <dl className="metadata">
          {book.publishedYear !== null && <div><dt>Published</dt><dd>{book.publishedYear}</dd></div>}
          <div><dt>Language</dt><dd>{book.language}</dd></div>
          {book.isbn && <div><dt>ISBN</dt><dd>{book.isbn}</dd></div>}
        </dl>
        <BookActions book={book} />
      </div>
    </section>
    <BookReviews book={book} />
    <SimilarBooks id={id} />
  </div>
}

export function BookPage() {
  const { id } = useParams()
  const number = Number(id)
  if (!id || !/^\d+$/.test(id) || !Number.isSafeInteger(number) || number < 1) return <NotFound />
  return <BookDetails key={number} id={number} />
}

export function BookshelfPage() {
  const [params, setParams] = useSearchParams()
  const candidate = params.get('status') as ShelfStatus
  const status = shelfStatuses.includes(candidate) ? candidate : 'WANT_TO_READ'
  const reading = useShelf(status)
  return <div className="container section stacked">
    <header className="page-heading"><h1>My Bookshelf</h1><p>Keep track of what you want to read and what you’ve finished.</p></header>
    <nav className="tabs" aria-label="Bookshelf statuses">{shelfStatuses.map(item =>
      <button key={item} type="button" aria-current={item === status ? 'page' : undefined} onClick={() => setParams({ status: item })}>{shelfLabels[item]}</button>)}</nav>
    {reading.isPending ? <LoadingSkeleton /> : reading.isError ? <ErrorState error={reading.error} retry={() => void reading.refetch()} /> :
      reading.data.length ? <div className="shelf-list">
        {reading.data.map(entry => <section className="surface shelf-entry" aria-label={entry.book.title} key={entry.bookId + ':' + entry.updatedAt}>
          <BookCard book={entry.book} progress={entry.progress} />
          <BookshelfStatusControl bookId={entry.bookId} current={entry.status} progress={entry.progress} />
        </section>)}
      </div> : <EmptyState title={status === 'READING' ? 'You’re between books.' : 'This shelf is empty.'}>
        <Link className="btn secondary" to="/discover">Discover books</Link>
      </EmptyState>}
  </div>
}
