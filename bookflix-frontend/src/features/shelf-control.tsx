import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { api } from '../lib/api'
import { shelfLabels, shelfStatuses } from '../lib/labels'
import { interactionKeys, useInvalidate } from '../hooks/catalog'
import type { ShelfStatus } from '../types/api'
import { ConfirmDialog, FormError, useToast } from '../components/states'

export function BookshelfStatusControl({ bookId, current, progress }: { bookId: number; current: ShelfStatus | null; progress: number }) {
  const [status, setStatus] = useState<ShelfStatus>(current ?? 'WANT_TO_READ')
  const [percent, setPercent] = useState(String(progress))
  const [confirming, setConfirming] = useState(false)
  const toast = useToast()
  const invalidate = useInvalidate()
  const amount = status === 'COMPLETED' ? 100 : Number(percent)
  const dirty = status !== current || amount !== progress
  const update = useMutation({ mutationFn: () => api.saveShelf(bookId, { status, progress: amount }), onSuccess: async () => {
    await invalidate(interactionKeys)
    toast('Bookshelf updated.')
  } })
  const remove = useMutation({ mutationFn: () => api.removeShelf(bookId), onSuccess: async () => {
    setConfirming(false)
    await invalidate(interactionKeys)
    toast('Book removed from your shelf.')
  } })
  const pending = update.isPending || remove.isPending

  return <>
    <form className="form shelf-control" onSubmit={event => { event.preventDefault(); if (dirty) update.mutate() }}>
      <div className="shelf-fields">
        <label>Bookshelf status<select aria-label="Bookshelf status" value={status} disabled={pending} onChange={event => setStatus(event.target.value as ShelfStatus)}>
          {shelfStatuses.map(item => <option key={item} value={item}>{shelfLabels[item]}</option>)}
        </select></label>
        <label>Progress (%)<input type="number" required min={0} max={100} step={1} value={status === 'COMPLETED' ? '100' : percent}
          disabled={pending || status === 'COMPLETED'} onChange={event => setPercent(event.target.value)} /></label>
      </div>
      <FormError error={update.error} />
      <div className="actions">
        <button className="btn primary" disabled={pending || !dirty}>{update.isPending ? 'Saving…' : current ? 'Save progress' : 'Add to bookshelf'}</button>
        {current && <button type="button" className="btn secondary" disabled={pending} onClick={() => { remove.reset(); setConfirming(true) }}>Remove</button>}
      </div>
    </form>
    {confirming && <ConfirmDialog title="Remove this book?" label="Remove book" pending={remove.isPending} error={remove.error}
      cancel={() => setConfirming(false)} confirm={() => remove.mutate()}>Your saved status and progress will be removed.</ConfirmDialog>}
  </>
}
