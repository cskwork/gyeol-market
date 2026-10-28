// Minimal DOM test helpers (no testing-library dependency): render into jsdom
// with React's act() and drive controlled inputs the way a browser would.
import { act, type ReactNode } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true

export interface Rendered {
  container: HTMLElement
  unmount: () => void
}

export function render(ui: ReactNode): Rendered {
  const container = document.createElement('div')
  document.body.appendChild(container)
  let root: Root
  act(() => {
    root = createRoot(container)
    root.render(ui)
  })
  return {
    container,
    unmount: () => {
      act(() => root.unmount())
      container.remove()
    },
  }
}

/** Renders `element` at `path` inside a router and a fresh, retry-free query client. */
export function renderRoute(element: ReactNode, { path = '/', route = path }: { path?: string; route?: string } = {}): Rendered {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route path={path} element={element} />
          <Route path="*" element={<p>other route</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Flushes pending promises (fetch stubs, query resolution) and the renders they cause. */
export async function settle(times = 5): Promise<void> {
  for (let i = 0; i < times; i++) {
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
  }
}

export function click(el: Element | null): void {
  if (!el) throw new Error('click target not found')
  act(() => {
    el.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  })
}

/** Sets a controlled input/select value through the native setter so React sees the change. */
export function changeValue(el: HTMLInputElement | HTMLSelectElement | null, value: string): void {
  if (!el) throw new Error('input not found')
  const proto = el instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype
  Object.getOwnPropertyDescriptor(proto, 'value')!.set!.call(el, value)
  act(() => {
    el.dispatchEvent(new Event(el instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }))
  })
}

export function byText<T extends Element = HTMLElement>(root: ParentNode, selector: string, text: string | RegExp): T | null {
  return (Array.from(root.querySelectorAll<T>(selector)).find((el) =>
    typeof text === 'string' ? el.textContent?.includes(text) : text.test(el.textContent ?? ''),
  ) ?? null)
}

/** JSON envelope response like the Spring Boot backend's ApiResponse. */
export function envelope(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

export function errorEnvelope(status: number, code: string, message: string): Response {
  return new Response(JSON.stringify({ success: false, error: { code, message } }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
