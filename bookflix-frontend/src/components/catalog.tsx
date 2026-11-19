import { useEffect, useRef, useState } from 'react'
import { BookOpen, ChevronLeft, ChevronRight, Search, Star } from 'lucide-react'
import { Link } from 'react-router-dom'
import type { Book, Genre } from '../types/api'

const palettes = ['rose', 'teal', 'violet', 'gold', 'navy', 'green']

export function BookCover({ book, eager = false }: { book: Book; eager?: boolean }) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null)
  const palette = palettes[((book.id % palettes.length) + palettes.length) % palettes.length]
  const fallback = !book.coverUrl || failedUrl === book.coverUrl
  return <div className={'book-cover cover-' + palette} aria-hidden="true">
    {fallback ? <div className="cover-print">
      <BookOpen className="cover-mark" size={30} strokeWidth={1} />
      <span className="cover-title">{book.title}</span>
      <span className="cover-author">{book.author.name}</span>
    </div> : <img loading={eager ? 'eager' : 'lazy'} src={book.coverUrl ?? undefined} alt="" referrerPolicy="no-referrer" onError={() => setFailedUrl(book.coverUrl)} />}
  </div>
}

export function RatingStars({ average, count }: { average: number; count?: number }) {
  if (count === 0) return <span className="muted">No ratings yet</span>
  return <span className="rating" aria-label={average.toFixed(1) + ' out of 5' + (count === undefined ? '' : ', ' + count + ' ratings')}>
    <Star aria-hidden="true" size={16} fill="currentColor" /><span>{average.toFixed(1)}</span>
    {count !== undefined && <span className="muted">({count})</span>}
  </span>
}

export function BookCard({ book, reason, progress }: { book: Book; reason?: string; progress?: number }) {
  return <article className="book-card">
    <Link to={'/books/' + book.id} className="book-link">
      <BookCover book={book} />
      <h3 title={book.title}>{book.title}</h3>
    </Link>
    <p className="book-author">{book.author.name}</p>
    {reason && <p className="book-reason">{reason}</p>}
    {progress !== undefined && <div className="card-progress"><progress value={progress} max={100} aria-label="Reading progress" /><span>{progress}%</span></div>}
  </article>
}

export function BookCarousel({ title, items, note }: {
  title: string; items: { book: Book; reason?: string; progress?: number }[]; note?: string
}) {
  const track = useRef<HTMLDivElement>(null)
  const [edges, setEdges] = useState({ start: true, end: true })

  useEffect(() => {
    const element = track.current
    if (!element) return
    function update() {
      if (!element) return
      setEdges({ start: element.scrollLeft <= 1, end: element.scrollLeft + element.clientWidth >= element.scrollWidth - 1 })
    }
    const observer = new ResizeObserver(update)
    observer.observe(element)
    element.addEventListener('scroll', update, { passive: true })
    update()
    return () => {
      observer.disconnect()
      element.removeEventListener('scroll', update)
    }
  }, [items.length])

  function scroll(direction: number) {
    const element = track.current
    if (element) element.scrollBy({ left: element.clientWidth * 0.85 * direction,
      behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })
  }
  return <section className="shelf" aria-label={title}>
    <div className="shelf-head"><div><h2>{title}</h2>{note && <p className="muted">{note}</p>}</div>
      {(!edges.start || !edges.end) && <div className="carousel-actions">
        <button className="icon-btn" aria-label={'Previous books in ' + title} disabled={edges.start} onClick={() => scroll(-1)}><ChevronLeft size={20} /></button>
        <button className="icon-btn" aria-label={'Next books in ' + title} disabled={edges.end} onClick={() => scroll(1)}><ChevronRight size={20} /></button>
      </div>}
    </div>
    <div className="carousel" ref={track}>{items.map(item => <BookCard key={item.book.id} {...item} />)}</div>
  </section>
}

export function GenreChip({ genre, selected, onToggle }: { genre: Genre; selected?: boolean; onToggle?: () => void }) {
  if (!onToggle) return <Link className="chip" to={'/discover?genre=' + genre.id}>{genre.name}</Link>
  return <button type="button" className="chip" aria-pressed={selected} onClick={onToggle}>{genre.name}</button>
}

export function SearchBar({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  return <label className="search-bar"><Search aria-hidden="true" size={20} />
    <input aria-label="Search books" type="search" maxLength={100} value={value} onChange={event => onChange(event.target.value)} placeholder="Search by title, author, or description" />
  </label>
}

export function Pagination({ page, total, onChange }: { page: number; total: number; onChange: (page: number) => void }) {
  if (total <= 1) return null
  return <nav className="pagination" aria-label="Pagination">
    <button className="btn secondary" disabled={page <= 0} onClick={() => onChange(page - 1)}>Previous</button>
    <span aria-live="polite">Page {page + 1} of {total}</span>
    <button className="btn secondary" disabled={page >= total - 1} onClick={() => onChange(page + 1)}>Next</button>
  </nav>
}
