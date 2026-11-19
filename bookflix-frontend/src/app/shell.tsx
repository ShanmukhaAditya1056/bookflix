import { BookOpen, Search, UserRound } from 'lucide-react'
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom'
import { useEffect } from 'react'
import { demoMode } from '../lib/api'
import { useMe, useSession } from './session'

export function Navbar() {
  const session = useSession()
  const me = useMe()
  const ready = me.data?.onboardingCompleted === true
  return <header className="navbar"><div className="container nav-inner">
    <Link className="brand" to={session.signedIn ? ready ? '/home' : '/onboarding' : '/'}><span className="brand-icon"><BookOpen size={19} aria-hidden="true" /></span>Bookflix</Link>
    <nav aria-label="Main navigation" className="main-nav">
      {ready && <NavLink to="/home">Home</NavLink>}
      <NavLink to="/discover"><Search size={16} aria-hidden="true" /> Discover</NavLink>
      {ready && <NavLink to="/bookshelf">My Bookshelf</NavLink>}
      {ready && me.data?.role === 'ADMIN' && <NavLink to="/admin">Admin</NavLink>}
    </nav>
    <div className="nav-account">
      {session.signedIn ? <>
        {ready && <NavLink to="/profile" className="icon-btn" aria-label="Profile"><UserRound size={19} /></NavLink>}
        <button className="text-button" onClick={session.signOut}>Sign out</button>
      </> : <>
        <Link className="text-button" to="/login">Sign in</Link>
        <Link className="btn primary small" to="/register">Sign up</Link>
      </>}
    </div>
  </div></header>
}

export function Footer() {
  return <footer className="footer"><div className="container footer-inner">
    <Link className="brand" to="/"><BookOpen size={18} aria-hidden="true" /> Bookflix</Link>
    <Link to="/discover">Browse the catalog</Link>
  </div></footer>
}

export function AppShell() {
  const { pathname } = useLocation()
  useEffect(() => {
    window.scrollTo(0, 0)
  }, [pathname])
  return <div className="shell">
    <a className="skip-link" href="#main">Skip to content</a>
    <Navbar />
    {demoMode && <div className="demo-banner">Demo mode · Activity stays in this browser</div>}
    <main id="main" tabIndex={-1}><Outlet /></main>
    <Footer />
  </div>
}
