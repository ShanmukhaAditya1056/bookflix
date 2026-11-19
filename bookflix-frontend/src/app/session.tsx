import { createContext, useContext, useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api, getSession, onUnauthorized, setSession } from '../lib/api'
import type { Auth } from '../types/api'
import { ErrorState, Forbidden, LoadingSkeleton } from '../components/states'

type Session = { signedIn: boolean; signIn: (auth: Auth) => void; signOut: () => void }
const SessionContext = createContext<Session | null>(null)

export function SessionProvider({ children }: { children: ReactNode }) {
  const [signedIn, setSignedIn] = useState(() => Boolean(getSession()))
  const client = useQueryClient()
  const navigate = useNavigate()
  const location = useLocation()

  useEffect(() => onUnauthorized(() => {
    const next = location.pathname + location.search
    client.clear()
    setSignedIn(false)
    navigate('/login', { replace: true, state: { next, expired: true } })
  }), [client, location.pathname, location.search, navigate])

  function signIn(auth: Auth) {
    client.clear()
    setSession(auth)
    client.setQueryData(['me'], auth.user)
    setSignedIn(true)
  }

  function signOut() {
    setSession(null)
    client.clear()
    setSignedIn(false)
    navigate('/', { replace: true })
  }

  return <SessionContext.Provider value={{ signedIn, signIn, signOut }}>{children}</SessionContext.Provider>
}

export function useSession() {
  const session = useContext(SessionContext)
  if (!session) throw new Error('SessionProvider is missing.')
  return session
}

export function useMe() {
  const { signedIn } = useSession()
  return useQuery({ queryKey: ['me'], queryFn: ({ signal }) => api.me(signal), enabled: signedIn })
}

export function ProtectedRoute() {
  const session = useSession()
  const me = useMe()
  const location = useLocation()
  if (!session.signedIn) return <Navigate to="/login" replace state={{ next: location.pathname + location.search }} />
  if (me.isPending) return <div className="container section"><LoadingSkeleton /></div>
  if (me.isError) return <div className="container section"><ErrorState error={me.error} retry={() => void me.refetch()} /></div>
  if (!me.data.onboardingCompleted && location.pathname !== '/onboarding') return <Navigate to="/onboarding" replace />
  return <Outlet />
}

export function AdminRoute() {
  const me = useMe()
  return me.data?.role === 'ADMIN' ? <Outlet /> : <Forbidden />
}
