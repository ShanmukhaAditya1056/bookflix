import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import type { ShelfStatus } from '../types/api'

export function useGenres() {
  return useQuery({ queryKey: ['genres'], queryFn: ({ signal }) => api.genres(signal) })
}

export function useShelf(status: ShelfStatus) {
  return useQuery({ queryKey: ['bookshelf', status], queryFn: ({ signal }) => api.shelf(status, signal) })
}

export function useRecommendations(shelf: Parameters<typeof api.recommendations>[0]) {
  return useQuery({ queryKey: ['recommendations', shelf], queryFn: ({ signal }) => api.recommendations(shelf, signal) })
}

export function useInvalidate() {
  const client = useQueryClient()
  return (keys: string[]) => Promise.all(keys.map(key => client.invalidateQueries({ queryKey: [key] })))
}

export const interactionKeys = ['book', 'books', 'bookshelf', 'reviews', 'recommendations', 'userStats', 'adminStats']
