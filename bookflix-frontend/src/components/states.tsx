import { createContext, useContext, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { BookOpen, CircleAlert, LockKeyhole, X } from 'lucide-react'
import { Link } from 'react-router-dom'
import { ApiError } from '../lib/api'

export function LoadingSkeleton({ cards = false }: { cards?: boolean }) {
  return <div className={cards ? 'book-grid' : 'skeleton-stack'} role="status" aria-label="Loading">
    {Array.from({ length: cards ? 6 : 3 }, (_, index) => <span className={cards ? 'skeleton skeleton-cover' : 'skeleton'} key={index} />)}
  </div>
}

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return <div className="state"><BookOpen aria-hidden="true" size={22} /><strong>{title}</strong>{children && <div>{children}</div>}</div>
}

export function ErrorState({ error, retry }: { error: Error; retry?: () => void }) {
  const status = error instanceof ApiError ? error.status : -1
  const title = status === 0 ? 'Connection unavailable' : status === 403 ? 'Access denied' : status === 404 ? 'Item not found' : 'Something went wrong'
  return <div className="state state-error" role="alert"><CircleAlert aria-hidden="true" size={22} /><strong>{title}</strong><p>{error.message}</p>
    {retry && status !== 403 && status !== 404 && <button className="btn secondary" onClick={retry}>Try again</button>}</div>
}

export function FormError({ error }: { error: Error | null }) {
  if (!error) return null
  return <div className="form-error" role="alert"><p>{error.message}</p>
    {error instanceof ApiError && error.details.length > 0 && <ul>{error.details.map(({ field, message }, index) => <li key={field + index}>{field} {message}</li>)}</ul>}
  </div>
}

function SystemPage({ status, heading, copy }: { status: number; heading: string; copy: string }) {
  return <div className="container section system-page">
    <span className="eyebrow">{status}</span><h1>{heading}</h1><p>{copy}</p><Link className="btn primary" to="/discover">Explore books</Link>
  </div>
}

export function NotFound() {
  return <SystemPage status={404} heading="This page is missing" copy="The book or page may have moved." />
}

export function Forbidden() {
  return <SystemPage status={403} heading="Access denied" copy="Your account does not have permission to open this page." />
}

const ToastContext = createContext<(message: string) => void>(() => {})

export function Toast({ message, dismiss }: { message: string; dismiss: () => void }) {
  return <div className="toast" role="status"><span>{message}</span><button className="icon-btn" onClick={dismiss} aria-label="Dismiss notification"><X size={16} /></button></div>
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState<{ text: string } | null>(null)
  useEffect(() => {
    if (!message) return
    const timeout = setTimeout(() => setMessage(null), 4500)
    return () => clearTimeout(timeout)
  }, [message])
  return <ToastContext.Provider value={(text) => setMessage({ text })}>
    {children}{message && <Toast message={message.text} dismiss={() => setMessage(null)} />}
  </ToastContext.Provider>
}

export function useToast() { return useContext(ToastContext) }

export function ConfirmDialog({ title, children, label = 'Delete', pending, error, cancel, confirm }: {
  title: string; children: ReactNode; label?: string; pending: boolean; error: Error | null; cancel: () => void; confirm: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const element = dialog.current
    element?.showModal()
    return () => element?.close()
  }, [])
  return <dialog ref={dialog} className="confirm-dialog" aria-label={title} onCancel={event => { event.preventDefault(); if (!pending) cancel() }}>
    <LockKeyhole size={22} aria-hidden="true" />
    <h2>{title}</h2><p>{children}</p><FormError error={error} />
    <div className="actions">
      <button autoFocus className="btn secondary" onClick={cancel} disabled={pending}>Cancel</button>
      <button className="btn primary" onClick={confirm} disabled={pending}>{pending ? 'Working…' : label}</button>
    </div>
  </dialog>
}
