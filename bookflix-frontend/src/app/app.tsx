import { Route, Routes } from 'react-router-dom'
import { ToastProvider, NotFound } from '../components/states'
import { AdminPage } from '../pages/admin'
import { AuthPage, OnboardingPage, ProfilePage } from '../pages/account'
import { BookPage, BookshelfPage } from '../pages/reading'
import { DiscoverPage, HomePage, LandingPage } from '../pages/discovery'
import { AdminRoute, ProtectedRoute, SessionProvider } from './session'
import { AppShell } from './shell'

export function App() {
  return <SessionProvider><ToastProvider><Routes><Route element={<AppShell />}>
    <Route index element={<LandingPage />} />
    <Route path="discover" element={<DiscoverPage />} />
    <Route path="books/:id" element={<BookPage />} />
    <Route path="login" element={<AuthPage key="login" mode="login" />} />
    <Route path="register" element={<AuthPage key="register" mode="register" />} />
    <Route element={<ProtectedRoute />}>
      <Route path="onboarding" element={<OnboardingPage />} />
      <Route path="home" element={<HomePage />} />
      <Route path="bookshelf" element={<BookshelfPage />} />
      <Route path="profile" element={<ProfilePage />} />
      <Route element={<AdminRoute />}><Route path="admin" element={<AdminPage />} /></Route>
    </Route>
    <Route path="*" element={<NotFound />} />
  </Route></Routes></ToastProvider></SessionProvider>
}
