import { useCallback, useEffect, useState } from 'react';

/**
 * The open modal layers: every `Modal` and the sheet of the goal side panel (d86f27b2). The one
 * with the highest `order` is on top. A confirmation can open above another dialog (23635ef6): Escape
 * then closes only the top one, the focus trap applies only to the top one, and the page stays locked
 * until the last closes. The order is taken when a layer renders open (parents render before their
 * children, later siblings after earlier ones), not when its effect runs – effects run children first,
 * so an outer and an inner dialog opening in the same commit would otherwise put the outer one on top
 * (0d9072fa).
 */
const openLayers = new Set<{ order: number }>();
let layerCounter = 0;

function isTopLayer(layer: { order: number }): boolean {
  for (const other of openLayers) if (other.order > layer.order) return false;
  return openLayers.has(layer);
}

/**
 * Joins the stack of modal layers while the calling component is mounted and locks the page scroll
 * until the last layer closes. Returns a check whether this layer is the top one right now – ask it
 * when a key arrives, not while rendering.
 */
export function useModalLayer(): () => boolean {
  // Taken while rendering, see openLayers
  const [self] = useState(() => ({ order: ++layerCounter }));
  useEffect(() => {
    openLayers.add(self);
    document.body.style.overflow = 'hidden';
    return () => {
      openLayers.delete(self);
      if (openLayers.size === 0) document.body.style.overflow = '';
    };
  }, [self]);
  return useCallback(() => isTopLayer(self), [self]);
}

/** Whether a modal layer is open – a panel that is not modal itself then leaves Escape to it. */
export function hasOpenModalLayer(): boolean {
  return openLayers.size > 0;
}

const TABBABLE = [
  'a[href]', 'area[href]', 'button:not([disabled])', 'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])', 'textarea:not([disabled])', 'iframe', '[contenteditable="true"]',
  '[tabindex]:not([tabindex="-1"])',
].join(', ');

/**
 * Whether the user can see the element: `checkVisibility` in the browser; jsdom lacks it (and lays nothing out,
 * so `getClientRects` is always empty there), then the computed `display`/`visibility` of the element and its
 * ancestors decide.
 */
export function isVisible(element: HTMLElement): boolean {
  if (typeof element.checkVisibility === 'function') return element.checkVisibility({ visibilityProperty: true });
  for (let node: HTMLElement | null = element; node; node = node.parentElement) {
    if (getComputedStyle(node).display === 'none') return false;
  }
  return getComputedStyle(element).visibility !== 'hidden';
}

/** The elements of `container` that Tab reaches, in document order. */
export function tabbables(container: HTMLElement | null): HTMLElement[] {
  if (!container) return [];
  return [...container.querySelectorAll<HTMLElement>(TABBABLE)]
    .filter((element) => element.tabIndex >= 0 && !element.closest('[hidden], [inert]') && isVisible(element));
}
