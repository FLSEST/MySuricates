/**
 * Tells the Monaco wrapper whether a `value` prop is an echo of its own `onChange` or a change from
 * outside that belongs in the editor.
 *
 * `@monaco-editor/react` replaces the whole model as soon as `value` differs from it. A parent that
 * reflects every keystroke through its state re-renders later than the next key arrives, so the
 * echo of an older keystroke overwrote newer text (instructions editor: 227 characters typed at
 * 20 ms per key, 140 saved). The wrapper therefore keeps the editor uncontrolled, records what it
 * reported, and writes a `value` into the model only when it is none of those reports.
 *
 * Why a list and not just the last report: a parent echoes the reports in order, possibly skipping
 * some — when it renders one, the model may already be several keystrokes further.
 */
export class MonacoValueSync {
  private reported: string[] = [];

  /**
   * @param limit reports kept while the parent does not echo them — bounds the memory of a parent
   *   that never feeds `onChange` back (an uncontrolled caller), far above any render lag.
   */
  constructor(private readonly limit = 64) {}

  /** The editor reported this text through `onChange`. */
  reportedChange(text: string): void {
    this.reported.push(text);
    if (this.reported.length > this.limit) this.reported.shift();
  }

  /** Whether `value` must be written into a model that currently holds `current`. */
  shouldApply(value: string, current: string): boolean {
    if (value === current) {
      // The parent has caught up. React renders the latest state and runs pending effects before
      // the next render, so no older report can come back after this one.
      this.reported = [];
      return false;
    }
    const index = this.reported.indexOf(value);
    if (index >= 0) {
      // Echo of an older keystroke: the model is already further along.
      this.reported.splice(0, index + 1);
      return false;
    }
    this.reported = [];
    return true;
  }
}
