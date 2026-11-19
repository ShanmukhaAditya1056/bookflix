import assert from 'node:assert/strict'
import { test } from 'node:test'
import { selectHomeShelves } from '../src/features/home-shelves.ts'

function candidate(id, reason = 'API reason for ' + id) {
  return { book: { id }, reason, score: 0.8, strategy: 'API_STRATEGY' }
}

test('keeps current reads out of discovery and gives overlapping shelves a first pick', () => {
  const overlap = [1, 2, 3, 4, 5, 6].map(id => candidate(id))
  const shelves = selectHomeShelves({
    anchored: overlap, genres: overlap, personal: overlap, trending: overlap,
  }, [{ book: { id: 1 }, progress: 35 }])

  assert.deepEqual(shelves.anchored.map(item => item.book.id), [2, 6])
  assert.deepEqual(shelves.genres.map(item => item.book.id), [3])
  assert.deepEqual(shelves.personal.map(item => item.book.id), [4])
  assert.deepEqual(shelves.trending.map(item => item.book.id), [5])
})

test('retains API order and reasons and caps the shelf at eight cards', () => {
  const items = Array.from({ length: 10 }, (_, index) => candidate(10 - index))
  const shelves = selectHomeShelves({ anchored: undefined, genres: [], personal: items, trending: [] })

  assert.deepEqual(shelves.personal, items.slice(0, 8))
  assert.equal(shelves.personal[0].reason, 'API reason for 10')
  assert.deepEqual(shelves.anchored, [])
})

test('never emits duplicate IDs within a shelf or invents replacement items', () => {
  const item = candidate(42)
  const shelves = selectHomeShelves({ anchored: [item, item], genres: [item], personal: [], trending: [item] })

  assert.deepEqual(shelves, { anchored: [item], genres: [], personal: [], trending: [] })
})
