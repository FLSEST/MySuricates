import { useId, useState, type KeyboardEvent, type RefObject } from 'react';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import { dateOfDay, dayAtX, xOfDay, type TimelineAxis } from './timelineLayout';

/**
 * Tageswähler über einer Zeile des Zeitstrahls (c60bf914) – in der Meilenstein-Zeile und in der
 * Kopfzeile eines Projektbands: Ein Klick legt am Tag darunter an. Per Tastatur (Rolle „slider“)
 * wählen ← → (Bild ↑ ↓ je Woche, Pos1/Ende) den Tag, Enter oder Leertaste legt an. Der gewählte Tag
 * steht als Strich, solange der Wähler den Fokus hat; er bleibt gewählt, wenn der Fokus kurz in ein
 * Eingabefeld wandert und zurückkommt.
 */
export function DayPicker({ axis, width, label, title, pickerRef, testId, onPick }: {
  axis: TimelineAxis;
  width: number;
  label: string;
  /** Tooltip für die Maus. */
  title: string;
  pickerRef: RefObject<HTMLDivElement | null>;
  testId: string;
  onPick: (day: number, x: number) => void;
}) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const helpId = useId();
  const [cursor, setCursor] = useState<number | null>(null);
  const [focused, setFocused] = useState(false);
  const clamp = (day: number) => Math.min(axis.endDay, Math.max(axis.startDay, day));
  const shown = clamp(cursor ?? axis.today);

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const step: Record<string, number> = { ArrowLeft: -1, ArrowRight: 1, PageUp: -7, PageDown: 7 };
    if (event.key in step) setCursor(clamp(shown + step[event.key]!));
    else if (event.key === 'Home') setCursor(axis.startDay);
    else if (event.key === 'End') setCursor(axis.endDay);
    else if (event.key === 'Enter' || event.key === ' ') onPick(shown, xOfDay(axis, shown, width));
    else return;
    event.preventDefault();
  };

  return (
    <>
      <span id={helpId} className="sr-only">{t.goals.timeline.pickerHelp}</span>
      <div ref={pickerRef} role="slider" tabIndex={0} aria-label={label} aria-describedby={helpId} title={title}
        aria-valuemin={axis.startDay} aria-valuemax={axis.endDay} aria-valuenow={shown}
        aria-valuetext={formatDate(dateOfDay(shown), locale)}
        data-testid={testId}
        onKeyDown={onKeyDown}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        onClick={(event) => {
          const px = event.clientX - event.currentTarget.getBoundingClientRect().left;
          const day = dayAtX(axis, px, width);
          setCursor(day);
          onPick(day, px);
        }}
        className="absolute inset-0 cursor-copy rounded-sm focus-ring">
        {focused && (
          <span aria-hidden="true" className="absolute inset-y-1 w-0.5 -translate-x-1/2 rounded bg-accent"
            style={{ left: xOfDay(axis, shown, width) }} />
        )}
      </div>
    </>
  );
}
