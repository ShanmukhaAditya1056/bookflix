import type { Recommendation, ShelfEntry } from '../types/api'

const priority = ['anchored', 'genres', 'personal', 'trending'] as const
type Shelf = typeof priority[number]
type Item = Recommendation['items'][number]
type Sources = Record<Shelf, readonly Item[] | undefined>

export const HOME_SHELF_LIMIT = 24
const visibleLimit = 8

export function selectHomeShelves(sources: Sources, reading: readonly ShelfEntry[] = []) {
  const seen = new Set(reading.map(entry => entry.book.id))
  const shelves: Record<Shelf, Item[]> = { anchored: [], genres: [], personal: [], trending: [] }

  // Give each shelf a first pick before filling rows from the remaining candidates.
  for (const limit of [1, visibleLimit]) {
    for (const key of priority) {
      const shelf = shelves[key]
      for (const item of sources[key] ?? []) {
        if (shelf.length >= limit) break
        if (seen.has(item.book.id)) continue
        seen.add(item.book.id)
        shelf.push(item)
      }
    }
  }

  return shelves
}
