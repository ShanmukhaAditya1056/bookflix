import type { BookSort, ShelfStatus } from '../types/api'

export const shelfLabels: Record<ShelfStatus, string> = {
  WANT_TO_READ: 'Want to read', READING: 'Reading', COMPLETED: 'Completed',
}
export const shelfStatuses = Object.keys(shelfLabels) as ShelfStatus[]

export const recommendationFallback = 'Showing catalog-based picks while the recommendation service is unavailable.'

export const sortLabels: Record<BookSort, string> = {
  title: 'Title A–Z', newest: 'Recently added', year_desc: 'Publication year ↓',
  year_asc: 'Publication year ↑', rating: 'Highest rated', popular: 'Most liked',
}
