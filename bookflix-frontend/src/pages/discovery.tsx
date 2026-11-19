import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { BookOpen, Search, Sparkles } from 'lucide-react'
import { Link, Navigate, useSearchParams } from 'react-router-dom'
import { useSession } from '../app/session'
import { BookCard, BookCarousel, BookCover, Pagination, SearchBar } from '../components/catalog'
import { EmptyState, ErrorState, LoadingSkeleton } from '../components/states'
import { useGenres, useRecommendations, useShelf } from '../hooks/catalog'
import { api } from '../lib/api'
import { sortLabels } from '../lib/labels'
import type { Book, BookSort } from '../types/api'

function HeroBanner({ featured, reader }: { featured?: Book; reader: boolean }) {
  return <section className={'hero' + (reader ? ' reader-hero' : '')}>
    <div className="container hero-inner">
      <div className="hero-copy">
        <span className="eyebrow"><Sparkles size={15} aria-hidden="true" /> {reader && featured ? 'Featured read' : 'A home for curious readers'}</span>
        <h1>{reader ? featured?.title ?? 'Find your next read.' : <>Find your next<br /><em>favorite book.</em></>}</h1>
        <p>{reader && featured ? featured.author.name + (featured.publishedYear === null ? '' : ' · ' + featured.publishedYear) + ' · ' + (featured.genres[0]?.name ?? 'Featured book') : 'Explore stories worth getting lost in, find recommendations that fit your taste, and give every good read a place on your shelf.'}</p>
        <div className="actions">
          {reader && featured && <Link className="btn primary" to={'/books/' + featured.id}><BookOpen size={18} aria-hidden="true" /> View book</Link>}
          {!reader && <Link className="btn primary" to="/register"><BookOpen size={18} aria-hidden="true" /> Start reading</Link>}
          <Link className="btn secondary" to="/discover"><Search size={18} aria-hidden="true" /> Explore catalog</Link>
        </div>
      </div>
      {featured && <div className="hero-book"><BookCover book={featured} eager /></div>}
    </div>
  </section>
}

function RecommendationShelf({ title, result, empty, hideUnanchored }: {
  title: string; result: ReturnType<typeof useRecommendations>; empty: string; hideUnanchored?: boolean
}) {
  if (result.isPending) return <section className="shelf"><h2>{title}</h2><LoadingSkeleton cards /></section>
  if (result.isError) return <section className="shelf"><h2>{title}</h2><ErrorState error={result.error} retry={() => void result.refetch()} /></section>
  if (hideUnanchored && !result.data.sourceBook) return null
  if (!result.data.items.length) return <section className="shelf"><h2>{title}</h2><EmptyState title={empty} /></section>
  return <BookCarousel title={title} items={result.data.items} note={result.data.degraded ? 'Basic recommendations' : undefined} />
}

export function LandingPage() {
  const { signedIn } = useSession()
  const trending = useRecommendations('trending')
  if (signedIn) return <Navigate to="/home" replace />
  const featured = trending.data?.items[0]?.book
  return <>
    <HeroBanner featured={featured} reader={false} />
    <div className="container section stacked">
      <RecommendationShelf title="Trending books" result={trending} empty="Featured books will appear here." />
    </div>
  </>
}

export function HomePage() {
  const personal = useRecommendations('for-you')
  const anchored = useRecommendations('because-you-liked')
  const trending = useRecommendations('trending')
  const genres = useRecommendations('genres')
  const reading = useShelf('READING')
  const featured = personal.data?.items[0]?.book ?? trending.data?.items[0]?.book
  return <>
    <HeroBanner featured={featured} reader />
    <div className="container section stacked">
      {reading.data && reading.data.length > 0 && <BookCarousel title="Continue Reading" items={reading.data} />}
      {reading.isError && <section className="shelf"><h2>Continue Reading</h2><ErrorState error={reading.error} retry={() => void reading.refetch()} /></section>}
      <RecommendationShelf title="For You" result={personal} empty="You’re caught up with your recommendations." />
      <RecommendationShelf title="Because You Liked" result={anchored} empty="No related books are available." hideUnanchored />
      <RecommendationShelf title="Trending" result={trending} empty="No trending books yet." />
      <RecommendationShelf title="Based on Your Genres" result={genres} empty="Select favorite genres in your profile to see matching books." />
    </div>
  </>
}

export function DiscoverPage() {
  const [params, setParams] = useSearchParams()
  const query = params.get('q')?.slice(0, 100) ?? ''
  const [input, setInput] = useState(query)
  const genre = Number(params.get('genre')) || undefined
  const rawSort = params.get('sort') ?? ''
  const sort: BookSort = Object.hasOwn(sortLabels, rawSort) ? rawSort as BookSort : 'title'
  const rawPage = Number(params.get('page') ?? 0)
  const page = Number.isSafeInteger(rawPage) && rawPage >= 0 ? rawPage : 0
  const genres = useGenres()
  const catalog = useQuery({ queryKey: ['books', 'discover', query, genre, sort, page],
    queryFn: ({ signal }) => api.books({ q: query, genreId: genre, sort, page, size: 18 }, signal) })

  useEffect(() => { setInput(query) }, [query])
  useEffect(() => {
    if (input.trim() === query) return
    const timer = setTimeout(() => {
      setParams(previous => {
        const next = new URLSearchParams(previous)
        if (input.trim()) next.set('q', input.trim())
        else next.delete('q')
        next.delete('page')
        return next
      }, { replace: true })
    }, 350)
    return () => clearTimeout(timer)
  }, [input, query, setParams])

  function change(key: string, value: string) {
    setParams(previous => {
      const next = new URLSearchParams(previous)
      if (value) next.set(key, value)
      else next.delete(key)
      if (key !== 'page') next.delete('page')
      return next
    })
  }

  return <div className="container section stacked">
    <header className="page-heading"><h1>Discover</h1><p>Explore the catalog and find something worth reading.</p></header>
    <div className="filters">
      <SearchBar value={input} onChange={setInput} />
      <label>Genre<select aria-label="Genre" disabled={genres.isPending || genres.isError} value={genre ?? ''} onChange={event => change('genre', event.target.value)}>
        <option value="">All genres</option>
        {genre && !genres.data?.some(item => item.id === genre) && <option value={genre}>Selected genre</option>}
        {genres.data?.map(item => <option value={item.id} key={item.id}>{item.name}</option>)}
      </select></label>
      <label>Sort by<select aria-label="Sort by" value={sort} onChange={event => change('sort', event.target.value)}>
        {Object.entries(sortLabels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
      </select></label>
    </div>
    {genres.isError && <ErrorState error={genres.error} retry={() => void genres.refetch()} />}
    {catalog.isPending ? <LoadingSkeleton cards /> : catalog.isError ? <ErrorState error={catalog.error} retry={() => void catalog.refetch()} /> :
      <>
        <p className="muted" aria-live="polite">{catalog.data.totalElements} {catalog.data.totalElements === 1 ? 'book' : 'books'}</p>
        {catalog.data.items.length ? <div className="book-grid">{catalog.data.items.map(book => <BookCard key={book.id} book={book} />)}</div> :
          <EmptyState title="No books found">{query || genre || page > 0 ?
            <button className="btn secondary" onClick={() => { setInput(''); setParams({}) }}>Clear filters</button> : 'Check back when more books have been added.'}</EmptyState>}
        <Pagination page={page} total={catalog.data.totalPages} onChange={value => change('page', value ? String(value) : '')} />
      </>}
  </div>
}
