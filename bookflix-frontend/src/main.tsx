import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ApiError } from './lib/api'
import { App } from './app/app'
import './app/styles.css'

const client = new QueryClient({ defaultOptions: { queries: {
  staleTime: 30_000, networkMode: 'always',
  retry: (attempt, error) => attempt < 1 && (!(error instanceof ApiError) || error.status >= 500),
}, mutations: { networkMode: 'always' } } })

createRoot(document.getElementById('root')!).render(
  <StrictMode><QueryClientProvider client={client}><BrowserRouter><App /></BrowserRouter></QueryClientProvider></StrictMode>,
)
