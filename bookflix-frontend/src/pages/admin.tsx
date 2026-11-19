import { useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Plus, RotateCw } from 'lucide-react'
import { Link, useSearchParams } from 'react-router-dom'
import { Pagination } from '../components/catalog'
import { ConfirmDialog, EmptyState, ErrorState, FormError, LoadingSkeleton, useToast } from '../components/states'
import { interactionKeys, useGenres, useInvalidate } from '../hooks/catalog'
import { api } from '../lib/api'
import type { AdminStats, Author, BookDetail, BookRequest, Genre } from '../types/api'

const catalogKeys = [...interactionKeys, 'authors', 'genres', 'me']
const collections = { books: 'Books', authors: 'Authors', genres: 'Genres' }
type Entity = keyof typeof collections
const singular = { books: 'book', authors: 'author', genres: 'genre' }
type Target = { id: number; name: string }
type Editor = { id: number | null }

function DashboardStats() {
  const stats = useQuery({ queryKey: ['adminStats'], queryFn: ({ signal }) => api.adminStats(signal) })
  const invalidate = useInvalidate()
  const toast = useToast()
  const rebuild = useMutation({ mutationFn: api.rebuild, onSuccess: async () => {
    await invalidate(['adminStats', 'recommendations'])
    toast('Recommendation model rebuilt.')
  }, onError: () => invalidate(['adminStats']) })
  const labels: { label: string; key: Exclude<keyof AdminStats, 'model'> }[] = [
    { label: 'Users', key: 'users' }, { label: 'Readers', key: 'readers' }, { label: 'Admins', key: 'admins' },
    { label: 'Active books', key: 'books' }, { label: 'Archived books', key: 'deletedBooks' }, { label: 'Authors', key: 'authors' },
    { label: 'Genres', key: 'genres' }, { label: 'Reviews', key: 'reviews' }, { label: 'Likes', key: 'likes' }, { label: 'Bookshelf entries', key: 'bookshelfEntries' },
  ]
  if (stats.isPending) return <LoadingSkeleton />
  if (stats.isError) return <ErrorState error={stats.error} retry={() => void stats.refetch()} />
  const model = stats.data.model
  return <div className="stacked">
    <dl className="stats-grid">{labels.map(({ label, key }) => <div key={key}><dt>{label}</dt><dd>{stats.data[key]}</dd></div>)}</dl>
    <section className="surface stacked">
      <div className="shelf-head"><div><h2>Recommendation model</h2><p className="muted">
        {!model.enabled ? 'Disabled' : model.available ? model.stale ? 'Available · rebuild recommended' : 'Available' : 'Unavailable'}
        {model.modelVersion && ' · ' + model.modelVersion}</p></div>
        <button className="btn secondary" disabled={rebuild.isPending} onClick={() => rebuild.mutate()}><RotateCw size={17} aria-hidden="true" /> {rebuild.isPending ? 'Rebuilding…' : 'Rebuild model'}</button></div>
      {(model.bookCount !== null || model.builtAt) && <dl className="metadata">
        {model.bookCount !== null && <div><dt>Indexed books</dt><dd>{model.bookCount}</dd></div>}
        {model.builtAt && <div><dt>Last build</dt><dd>{new Date(model.builtAt).toLocaleString()}</dd></div>}
      </dl>}
      {model.lastError && <p className="muted">{model.lastError}</p>}
      <FormError error={rebuild.error} />
      {rebuild.data && <details open><summary>Rebuild evaluation</summary><pre>{JSON.stringify(rebuild.data.evaluation, null, 2)}</pre></details>}
    </section>
  </div>
}

function InputForm({ entity, author, genre, book, authorOptions = [], genreOptions = [], onClose }: {
  entity: Entity; author?: Author; genre?: Genre; book?: BookDetail; authorOptions?: Author[]; genreOptions?: Genre[]; onClose: () => void
}) {
  const id = author?.id ?? genre?.id ?? book?.id ?? null
  const toast = useToast()
  const invalidate = useInvalidate()
  const [validation, setValidation] = useState<Error | null>(null)
  const mutation = useMutation({ mutationFn: async (fields: FormData) => {
    const value = (name: string) => String(fields.get(name) ?? '').trim()
    if (entity === 'authors') return api.saveAuthor(id, { name: value('name'), bio: value('bio') })
    if (entity === 'genres') return api.saveGenre(id, { name: value('name'), slug: value('slug') || undefined })
    const request: BookRequest = { title: value('title'), authorId: Number(value('authorId')), description: value('description') || null,
      isbn: value('isbn') || null, coverUrl: value('coverUrl') || null, language: value('language') || 'en',
      publishedYear: value('publishedYear') ? Number(value('publishedYear')) : null, genreIds: fields.getAll('genreIds').map(Number) }
    return api.saveBook(id, request)
  }, onSuccess: async () => {
    await invalidate(catalogKeys)
    toast(id ? 'Changes saved.' : 'Catalog entry created.')
    onClose()
  } })

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const fields = new FormData(event.currentTarget)
    setValidation(null)
    if (!String(fields.get(entity === 'books' ? 'title' : 'name') ?? '').trim()) {
      setValidation(new Error('Enter a name or title.'))
      return
    }
    if (entity === 'books') {
      const count = fields.getAll('genreIds').length
      const url = String(fields.get('coverUrl') ?? '').trim()
      if (count < 1 || count > 10) { setValidation(new Error('Choose between one and ten genres.')); return }
      if (url && !/^https?:\/\//i.test(url)) { setValidation(new Error('Enter an HTTP or HTTPS cover URL.')); return }
    }
    mutation.mutate(fields)
  }

  return <section className="surface stacked" aria-label={id ? 'Edit ' + singular[entity] : 'Add ' + singular[entity]}>
    <h3>{id ? 'Edit ' : 'Add '}{singular[entity]}</h3>
    <form className="form" onSubmit={submit}>
      {entity === 'books' ? <>
        <label>Title<input name="title" required maxLength={255} defaultValue={book?.title} /></label>
        <div className="form-columns">
          <label>Author<select aria-label="Author" name="authorId" required defaultValue={book?.author.id ?? ''}><option value="" disabled>Select an author</option>
            {authorOptions.map(item => <option value={item.id} key={item.id}>{item.name}</option>)}
          </select></label>
          <label>Publication year<input name="publishedYear" type="number" min={-3000} max={2100} defaultValue={book?.publishedYear ?? ''} /></label>
          <label>ISBN<input name="isbn" maxLength={32} defaultValue={book?.isbn ?? ''} /></label>
          <label>Language code<input name="language" defaultValue={book?.language ?? 'en'} maxLength={12} /></label>
        </div>
        <label>Cover URL<input name="coverUrl" type="url" maxLength={512} defaultValue={book?.coverUrl ?? ''} /></label>
        <label>Description<textarea aria-label="Description" name="description" rows={4} maxLength={10000} defaultValue={book?.description ?? ''} /></label>
        <fieldset><legend>Genres (choose 1–10)</legend><div className="checkbox-grid">{genreOptions.map(item =>
          <label key={item.id}><input type="checkbox" name="genreIds" value={item.id} defaultChecked={book?.genres.some(value => value.id === item.id)} /> {item.name}</label>
        )}</div></fieldset>
        {(!authorOptions.length || !genreOptions.length) && <p className="muted">Add an author and a genre before saving a book.</p>}
      </> : <>
        <label>Name<input name="name" required maxLength={entity === 'authors' ? 200 : 80} defaultValue={author?.name ?? genre?.name} /></label>
        {entity === 'authors' ? <label>Biography (optional)<textarea aria-label="Biography (optional)" name="bio" rows={4} maxLength={5000} defaultValue={author?.bio ?? ''} /></label> :
          <label>Slug (optional)<input name="slug" pattern="[a-z0-9]+(-[a-z0-9]+)*" maxLength={80} defaultValue={genre?.slug} placeholder="generated from name" /></label>}
      </>}
      <FormError error={validation ?? mutation.error} />
      <div className="actions"><button className="btn primary" disabled={mutation.isPending || (entity === 'books' && (!authorOptions.length || !genreOptions.length))}>
        {mutation.isPending ? 'Saving…' : 'Save ' + singular[entity]}</button>
        <button type="button" className="btn secondary" disabled={mutation.isPending} onClick={onClose}>Cancel</button></div>
    </form>
  </section>
}

function BookEditor({ id, close }: { id: number | null; close: () => void }) {
  const detail = useQuery({ queryKey: ['book', id], queryFn: ({ signal }) => api.book(id!, signal), enabled: id !== null })
  const authors = useAuthors()
  const genres = useGenres()
  if (detail.isError || authors.isError || genres.isError) return <div className="stacked">
    <ErrorState error={(detail.error ?? authors.error ?? genres.error)!} retry={() => {
      if (detail.isError) void detail.refetch()
      if (authors.isError) void authors.refetch()
      if (genres.isError) void genres.refetch()
    }} /><button className="btn secondary self-start" onClick={close}>Close editor</button>
  </div>
  if ((id !== null && detail.isPending) || authors.isPending || genres.isPending) return <LoadingSkeleton />
  return <InputForm entity="books" book={detail.data} authorOptions={authors.data} genreOptions={genres.data} onClose={close} />
}

function CatalogManager({ entity }: { entity: Entity }) {
  const [page, setPage] = useState(0)
  const [editor, setEditor] = useState<Editor | null>(null)
  const [deleting, setDeleting] = useState<Target | null>(null)
  const toast = useToast()
  const invalidate = useInvalidate()
  const books = useQuery({ queryKey: ['books', 'admin', page], enabled: entity === 'books',
    queryFn: ({ signal }) => api.books({ size: 15, page }, signal) })
  const authors = useAuthors(entity === 'authors')
  const genres = useQuery({ queryKey: ['genres'], queryFn: ({ signal }) => api.genres(signal), enabled: entity === 'genres' })
  const listing = entity === 'books' ? books : entity === 'authors' ? authors : genres
  const rows = entity === 'books' ? books.data?.items ?? [] : entity === 'authors' ? authors.data ?? [] : genres.data ?? []
  const remove = useMutation({ mutationFn: (id: number) => api.deleteCatalog(entity, id), onSuccess: async () => {
    setDeleting(null)
    if (editor?.id === deleting?.id) setEditor(null)
    if (entity === 'books' && rows.length === 1 && page > 0) setPage(page - 1)
    await invalidate(catalogKeys)
    toast(entity === 'books' ? 'Book archived.' : 'Catalog entry deleted.')
  } })

  return <section className="stacked" aria-label={'Manage ' + collections[entity].toLowerCase()}>
    <div className="shelf-head"><h2>{collections[entity]}</h2>
      <button className="btn primary" onClick={() => setEditor({ id: null })}><Plus size={18} aria-hidden="true" /> Add {singular[entity]}</button></div>
    {editor && (entity === 'books' ? <BookEditor key={editor.id ?? 'new'} id={editor.id} close={() => setEditor(null)} /> :
      <InputForm key={editor.id ?? 'new'} entity={entity} author={authors.data?.find(item => item.id === editor.id)}
        genre={genres.data?.find(item => item.id === editor.id)} onClose={() => setEditor(null)} />)}
    {listing.isPending ? <LoadingSkeleton /> : listing.isError ? <ErrorState error={listing.error} retry={() => void listing.refetch()} /> :
      rows.length ? <div className="table-wrap"><table>
        <thead><tr><th scope="col">{entity === 'books' ? 'Title' : 'Name'}</th><th scope="col">{entity === 'books' ? 'Author' : entity === 'genres' ? 'Slug' : 'Biography'}</th><th scope="col">Actions</th></tr></thead>
        <tbody>{rows.map(item => {
          const name = 'title' in item ? item.title : item.name
          return <tr key={item.id}><th scope="row">{'title' in item ? <Link to={'/books/' + item.id}>{name}</Link> : name}</th>
            <td>{'author' in item ? item.author.name : 'slug' in item ? item.slug : item.bio || '—'}</td>
            <td><div className="actions"><button className="btn secondary small" aria-label={'Edit ' + name} onClick={() => setEditor({ id: item.id })}>Edit</button>
              <button className="btn secondary small" aria-label={(entity === 'books' ? 'Archive ' : 'Delete ') + name} onClick={() => { remove.reset(); setDeleting({ id: item.id, name }) }}>
                {entity === 'books' ? 'Archive' : 'Delete'}</button></div></td></tr>
        })}</tbody>
      </table></div> : <EmptyState title={'No ' + collections[entity].toLowerCase() + ' to show.'} />}
    {entity === 'books' && books.data && <Pagination page={page} total={books.data.totalPages} onChange={setPage} />}
    {deleting && <ConfirmDialog title={(entity === 'books' ? 'Archive ' : 'Delete ') + deleting.name + '?'}
      label={entity === 'books' ? 'Archive book' : 'Delete ' + singular[entity]} pending={remove.isPending} error={remove.error}
      cancel={() => setDeleting(null)} confirm={() => remove.mutate(deleting.id)}>
      {entity === 'books' ? 'This removes the book from the public catalog.' : 'This entry will be removed from the catalog.'}
    </ConfirmDialog>}
  </section>
}

function useAuthors(enabled = true) {
  return useQuery({ queryKey: ['authors'], queryFn: ({ signal }) => api.authors(signal), enabled })
}

export function AdminPage() {
  const [params, setParams] = useSearchParams()
  const requested = params.get('catalog') ?? ''
  const entity = Object.hasOwn(collections, requested) ? requested as Entity : 'books'
  return <div className="container section stacked">
    <header className="page-heading"><h1>Admin dashboard</h1><p>Manage the catalog and monitor recommendation health.</p></header>
    <DashboardStats />
    <nav className="tabs" aria-label="Catalog sections">{Object.entries(collections).map(([key, label]) =>
      <button key={key} aria-current={entity === key ? 'page' : undefined} onClick={() => setParams({ catalog: key })}>{label}</button>)}</nav>
    <CatalogManager key={entity} entity={entity} />
  </div>
}
