import { describe, expect, it } from 'vitest'
import { COLUMN_WIDTH, ROW_HEIGHT, getConnectedNodeIds, getConnections, layoutNodes } from './ArchitectureGraph'

describe('layoutNodes', () => {
  it('places every node in the input', () => {
    const positions = layoutNodes(['a/x.ts', 'a/y.ts', 'b/z.ts'])
    expect(positions.size).toBe(3)
    for (const id of ['a/x.ts', 'a/y.ts', 'b/z.ts']) {
      expect(positions.has(id)).toBe(true)
    }
  })

  it('groups files by top-level directory into distinct columns', () => {
    const positions = layoutNodes(['app/a.py', 'app/b.py', 'lib/c.py'])
    const appX = new Set(['app/a.py', 'app/b.py'].map((id) => positions.get(id)?.x))
    const libX = positions.get('lib/c.py')?.x

    // Both files under app/ share a column; lib/ gets a different one.
    expect(appX.size).toBe(1)
    expect([...appX][0]).not.toBe(libX)
  })

  it('stacks files within the same group at increasing row offsets', () => {
    const positions = layoutNodes(['app/a.py', 'app/b.py', 'app/c.py'])
    const ys = ['app/a.py', 'app/b.py', 'app/c.py'].map((id) => positions.get(id)?.y)
    const uniqueYs = new Set(ys)

    expect(uniqueYs.size).toBe(3)
    expect(Math.max(...(ys as number[]))).toBeLessThanOrEqual(2 * ROW_HEIGHT)
  })

  it('treats top-level (no-slash) files as their own "(root)" group', () => {
    const positions = layoutNodes(['README.md', 'app/main.py'])
    const rootPos = positions.get('README.md')
    const appPos = positions.get('app/main.py')

    expect(rootPos).toBeDefined()
    expect(appPos).toBeDefined()
    expect(rootPos?.x).not.toBe(appPos?.x)
  })

  it('gives repeated calls with the same input identical layouts (deterministic)', () => {
    const ids = ['app/a.py', 'lib/b.py', 'app/c.py']
    const first = layoutNodes(ids)
    const second = layoutNodes(ids)
    for (const id of ids) {
      expect(first.get(id)).toEqual(second.get(id))
    }
  })

  it('splits nested files sharing one top-level directory into separate columns by their own parent dir', () => {
    // Real bug: an 87-file repo groups almost everything under "src/", so grouping by
    // top-level-only dumped 80+ files into one column. Grouping by the FULL immediate parent
    // directory instead spreads them across "src/components/ui", "src/pages", "src/lib", etc.
    const positions = layoutNodes([
      'src/components/ui/button.tsx',
      'src/components/ui/input.tsx',
      'src/pages/Index.tsx',
      'src/lib/utils.ts',
    ])
    const uiX = new Set(
      ['src/components/ui/button.tsx', 'src/components/ui/input.tsx'].map((id) => positions.get(id)?.x),
    )
    const pagesX = positions.get('src/pages/Index.tsx')?.x
    const libX = positions.get('src/lib/utils.ts')?.x

    expect(uiX.size).toBe(1) // both ui/ files share a column
    expect(new Set([...uiX, pagesX, libX]).size).toBe(3) // but ui/, pages/, and lib/ are 3 distinct columns
  })

  it('columns are spaced by COLUMN_WIDTH apart', () => {
    const positions = layoutNodes(['a/x.py', 'b/y.py'])
    const xs = [...new Set([positions.get('a/x.py')?.x, positions.get('b/y.py')?.x])].sort(
      (a, b) => (a ?? 0) - (b ?? 0),
    )
    expect(xs[1]! - xs[0]!).toBe(COLUMN_WIDTH)
  })
})

describe('getConnections', () => {
  const edges = [
    { source: 'App.tsx', target: 'utils.ts' },
    { source: 'App.tsx', target: 'Button.tsx' },
    { source: 'Button.tsx', target: 'utils.ts' },
  ]

  it('returns empty lists when nothing is active', () => {
    expect(getConnections(null, edges)).toEqual({ imports: [], importedBy: [] })
  })

  it('splits a node\'s edges into what it imports vs what imports it', () => {
    expect(getConnections('utils.ts', edges)).toEqual({
      imports: [],
      importedBy: ['App.tsx', 'Button.tsx'],
    })
    expect(getConnections('App.tsx', edges)).toEqual({
      imports: ['utils.ts', 'Button.tsx'],
      importedBy: [],
    })
  })

  it('a node can appear on both sides if it both imports and is imported', () => {
    expect(getConnections('Button.tsx', edges)).toEqual({
      imports: ['utils.ts'],
      importedBy: ['App.tsx'],
    })
  })
})

describe('getConnectedNodeIds', () => {
  const edges = [
    { source: 'a.ts', target: 'utils.ts' },
    { source: 'b.ts', target: 'utils.ts' },
    { source: 'c.ts', target: 'd.ts' },
  ]

  it('returns null when nothing is active', () => {
    expect(getConnectedNodeIds(null, edges)).toBeNull()
  })

  it('includes the active node plus everything it points to or is pointed at by', () => {
    const connected = getConnectedNodeIds('utils.ts', edges)
    expect(connected).toEqual(new Set(['utils.ts', 'a.ts', 'b.ts']))
  })

  it('excludes nodes with no edge to the active node', () => {
    const connected = getConnectedNodeIds('utils.ts', edges)
    expect(connected?.has('c.ts')).toBe(false)
    expect(connected?.has('d.ts')).toBe(false)
  })

  it('works symmetrically regardless of which side of the edge the active node is on', () => {
    const fromSource = getConnectedNodeIds('c.ts', edges)
    const fromTarget = getConnectedNodeIds('d.ts', edges)
    expect(fromSource).toEqual(new Set(['c.ts', 'd.ts']))
    expect(fromTarget).toEqual(new Set(['d.ts', 'c.ts']))
  })

  it('an isolated node with no edges is connected only to itself', () => {
    expect(getConnectedNodeIds('lonely.ts', edges)).toEqual(new Set(['lonely.ts']))
  })
})
