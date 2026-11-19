import { useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Eye, EyeOff } from 'lucide-react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useMe, useSession } from '../app/session'
import { GenreChip } from '../components/catalog'
import { EmptyState, ErrorState, FormError, LoadingSkeleton, useToast } from '../components/states'
import { useGenres, useInvalidate } from '../hooks/catalog'
import { api, demoMode } from '../lib/api'
import { shelfLabels, shelfStatuses } from '../lib/labels'
import type { User } from '../types/api'

export function AuthPage({ mode }: { mode: 'register' | 'login' }) {
  const registering = mode === 'register'
  const { signedIn, signIn } = useSession()
  const location = useLocation()
  const [visible, setVisible] = useState(false)
  const [validation, setValidation] = useState<Error | null>(null)
  const routeState = location.state as { next?: string; expired?: boolean } | null
  const returnTo = routeState?.next?.startsWith('/') && !routeState.next.startsWith('//') && !routeState.next.startsWith('/login') ? routeState.next : '/home'
  const auth = useMutation({ mutationFn: (input: { email: string; password: string; name?: string }) => api.authenticate(mode, input), onSuccess: signIn })
  if (signedIn) return <Navigate replace to={auth.data?.user.onboardingCompleted === false ? '/onboarding' : returnTo} />

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const fields = new FormData(event.currentTarget)
    const name = String(fields.get('name') ?? '').trim()
    const email = String(fields.get('email')).trim()
    const password = String(fields.get('password'))
    setValidation(null)
    if (registering && !name) { setValidation(new Error('Enter your display name.')); return }
    if (registering && new TextEncoder().encode(password).length > 72) {
      setValidation(new Error('Your password is too long. Use up to 72 bytes.')); return
    }
    auth.mutate({ email, password, ...(registering && { name }) })
  }

  return <div className="container section auth-wrap">
    <div className="surface auth-panel">
      <h1>{registering ? 'Create your account' : 'Welcome back'}</h1>
      <p className="muted">{registering ? 'Save books and get recommendations based on what you enjoy.' : 'Sign in to pick up where you left off.'}</p>
      {routeState?.expired && <p className="form-error" role="status">Your session expired. Sign in to continue.</p>}
      <form className="form" onSubmit={submit}>
        {registering && <label>Display name<input name="name" autoComplete="name" required maxLength={100} /></label>}
        <label>Email<input name="email" type="email" autoComplete="email" required maxLength={254} /></label>
        <div className="field"><label htmlFor="auth-password">Password</label><div className="password-input"><input id="auth-password" name="password" type={visible ? 'text' : 'password'} autoComplete={registering ? 'new-password' : 'current-password'}
          required minLength={registering ? 8 : undefined} maxLength={registering ? 72 : undefined} />
          <button type="button" className="icon-btn" aria-label={visible ? 'Hide password' : 'Show password'} onClick={() => setVisible(!visible)}>{visible ? <EyeOff size={19} /> : <Eye size={19} />}</button></div></div>
        {registering && <p className="muted">Use at least 8 characters.</p>}
        <FormError error={validation ?? auth.error} />
        <button className="btn primary" disabled={auth.isPending}>{auth.isPending ? 'Please wait…' : registering ? 'Create account' : 'Sign in'}</button>
      </form>
      <p>{registering ? 'Already have an account? ' : 'New to Bookflix? '}<Link className="inline-link" to={registering ? '/login' : '/register'} state={routeState}>{registering ? 'Sign in' : 'Create an account'}</Link></p>
      {!registering && demoMode && <p className="muted">Demo admin credentials are listed in the frontend README.</p>}
    </div>
  </div>
}

function GenrePreferences({ user, onboarding }: { user: User; onboarding?: boolean }) {
  const genres = useGenres()
  const [selected, setSelected] = useState(() => user.genres.map(genre => genre.id))
  const client = useQueryClient()
  const invalidate = useInvalidate()
  const toast = useToast()
  const navigate = useNavigate()
  const save = useMutation({ mutationFn: api.updateGenres, onSuccess: async profile => {
    client.setQueryData(['me'], profile)
    await invalidate(['recommendations'])
    toast(onboarding ? 'Your recommendations are ready.' : 'Favorite genres saved.')
    if (onboarding) navigate('/home', { replace: true })
  } })

  function toggle(id: number) {
    setSelected(prev => prev.includes(id) ? prev.filter(value => value !== id) : prev.length < 20 ? [...prev, id] : prev)
  }

  return <form className="form" onSubmit={event => { event.preventDefault(); save.mutate(selected) }}>
    {genres.isPending ? <LoadingSkeleton /> : genres.isError ? <ErrorState error={genres.error} retry={() => void genres.refetch()} /> :
      genres.data.length ? <fieldset><legend className="sr-only">Favorite genres</legend><div className="chips">
        {genres.data.map(genre => <GenreChip key={genre.id} genre={genre} selected={selected.includes(genre.id)} onToggle={() => toggle(genre.id)} />)}
      </div>{selected.length === 20 && <p className="muted">You’ve selected the maximum of 20 genres.</p>}</fieldset> : <EmptyState title="No genres available yet." />}
    <FormError error={save.error} />
    <div className="actions">
      <button className="btn primary" disabled={save.isPending || !genres.data?.length}>{save.isPending ? 'Saving…' : onboarding ? 'Continue' : 'Save genres'}</button>
      {onboarding && <button type="button" className="btn secondary" disabled={save.isPending} onClick={() => save.mutate([])}>Skip for now</button>}
    </div>
  </form>
}

export function OnboardingPage() {
  const { data: user } = useMe()
  if (!user) return <div className="container section"><LoadingSkeleton /></div>
  if (user.onboardingCompleted) return <Navigate to="/home" replace />
  return <div className="container section auth-wrap"><section className="surface auth-panel preference-panel">
    <h1>What do you like to read?</h1><p className="muted">Choose up to 20 genres to shape your recommendations.</p>
    <GenrePreferences user={user} onboarding />
  </section></div>
}

function NameForm({ user }: { user: User }) {
  const [name, setName] = useState(user.name)
  const client = useQueryClient()
  const toast = useToast()
  const mutation = useMutation({ mutationFn: () => api.updateName(name.trim()), onSuccess: async updated => {
    client.setQueryData(['me'], updated)
    await client.invalidateQueries({ queryKey: ['reviews'] })
    toast('Display name saved.')
  } })
  return <form className="form" onSubmit={event => { event.preventDefault(); if (name.trim()) mutation.mutate() }}>
    <label>Display name<input required maxLength={100} autoComplete="name" value={name} onChange={event => setName(event.target.value)} /></label>
    <p className="muted">{user.email}</p>
    <FormError error={mutation.error} />
    <button className="btn primary self-start" disabled={mutation.isPending || !name.trim() || name.trim() === user.name}>{mutation.isPending ? 'Saving…' : 'Save display name'}</button>
  </form>
}

export function ProfilePage() {
  const me = useMe()
  const stats = useQuery({ queryKey: ['userStats'], queryFn: ({ signal }) => api.stats(signal) })
  if (!me.data) return <div className="container section"><LoadingSkeleton /></div>
  return <div className="container section stacked">
    <header className="page-heading"><h1>Profile & preferences</h1></header>
    <div className="profile-grid">
      <section className="surface stacked"><h2>Your account</h2><NameForm key={me.data.name} user={me.data} /></section>
      <section className="surface stacked"><h2>Favorite genres</h2><GenrePreferences key={me.data.genres.map(genre => genre.id).join(',')} user={me.data} /></section>
    </div>
    <section className="surface stacked"><h2>Reading stats</h2>
      {stats.isPending ? <LoadingSkeleton /> : stats.isError ? <ErrorState error={stats.error} retry={() => void stats.refetch()} /> :
        <dl className="stats-grid">
          {shelfStatuses.map(status => <div key={status}><dt>{shelfLabels[status]}</dt><dd>{stats.data.bookshelf[status]}</dd></div>)}
          <div><dt>Likes</dt><dd>{stats.data.likedCount}</dd></div><div><dt>Reviews</dt><dd>{stats.data.reviewCount}</dd></div>
          <div><dt>Average rating given</dt><dd>{stats.data.averageRatingGiven?.toFixed(1) ?? '—'}</dd></div>
        </dl>}
    </section>
  </div>
}
