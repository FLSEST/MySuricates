import { useLayoutEffect, type CSSProperties, type RefObject } from 'react';

/**
 * Schwebende Karten am Zeitstrahl (c60bf914): Sie hängen per Portal an `document.body` und stehen
 * `position: fixed` am Auslöser. So schneidet sie der Zeitstrahl nicht ab, der auf schmalen
 * Bildschirmen in sich scrollt (`overflow-x: auto` erzwingt dort auch senkrechtes Abschneiden).
 */

/** Wo die Karte am Auslöser ausgerichtet ist: linke Kante, rechte Kante oder Mitte. */
export type FloatingAlign = 'start' | 'end' | 'center';

/** Rechteck, an dem die Karte steht – meist das des Auslösers, beim Klick auf die Achse ein Punkt. */
export type FloatingAnchor = () => DOMRect | null;

const GAP = 4;
const MARGIN = 8;

/**
 * Lage der Karte zum Anker: darunter, passt sie dort nicht und oben ist mehr Platz, darüber; immer
 * innerhalb des Fensters. Rein und testbar.
 */
export function floatingPosition(
  anchor: Pick<DOMRect, 'left' | 'right' | 'top' | 'bottom' | 'width'>,
  size: { width: number; height: number },
  viewport: { width: number; height: number },
  align: FloatingAlign,
): { left: number; top: number } {
  const wanted = align === 'start' ? anchor.left : align === 'end' ? anchor.right - size.width : anchor.left + anchor.width / 2 - size.width / 2;
  const left = Math.max(MARGIN, Math.min(wanted, viewport.width - size.width - MARGIN));
  const below = anchor.bottom + GAP;
  const above = anchor.top - GAP - size.height;
  const fitsBelow = below + size.height <= viewport.height - MARGIN;
  const top = fitsBelow || above < MARGIN || viewport.height - anchor.bottom >= anchor.top ? below : above;
  return { left, top: Math.max(MARGIN, top) };
}

/** Anfangsstil einer schwebenden Karte: unsichtbar, bis `useFloating` sie gemessen und gesetzt hat. */
export const FLOATING_STYLE: CSSProperties = { position: 'fixed', left: 0, top: 0, visibility: 'hidden' };

/**
 * Setzt eine schwebende Karte an ihren Anker – beim Öffnen, beim Scrollen (auch im Zeitstrahl) und
 * bei geänderter Fenstergröße, so wandert sie mit ihrem Auslöser. Schreibt die Lage direkt an das
 * Element (kein State, kein zusätzlicher Render); React fasst `left`/`top` nicht wieder an, solange
 * der Anfangsstil gleich bleibt.
 */
export function useFloating(anchor: FloatingAnchor, ref: RefObject<HTMLElement | null>, align: FloatingAlign) {
  useLayoutEffect(() => {
    let frame = 0;
    const place = () => {
      const rect = anchor();
      const element = ref.current;
      if (!rect || !element) return;
      const { left, top } = floatingPosition(rect, { width: element.offsetWidth, height: element.offsetHeight },
        { width: window.innerWidth, height: window.innerHeight }, align);
      element.style.left = `${left}px`;
      element.style.top = `${top}px`;
      element.style.visibility = 'visible';
    };
    const schedule = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(place);
    };
    place();
    window.addEventListener('scroll', schedule, true);
    window.addEventListener('resize', schedule);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener('scroll', schedule, true);
      window.removeEventListener('resize', schedule);
    };
  }, [anchor, ref, align]);
}

/**
 * Gibt beim Schließen den Fokus an den Auslöser zurück – nach Speichern wie nach Abbrechen –, außer
 * jemand hat ihn inzwischen bewusst woandershin gesetzt (Klick auf ein anderes Bedienelement).
 */
export function returnFocus(trigger: HTMLElement | null | undefined, container: HTMLElement | null) {
  const active = document.activeElement;
  if (!trigger || !trigger.isConnected) return;
  if (!active || active === document.body || (container && container.contains(active))) trigger.focus({ preventScroll: true });
}
