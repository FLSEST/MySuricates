-- The phase a wish was in when it ended FAILED or CANCELLED (TODO 92bffe64). The wish timeline guessed it
-- from the iteration history; a refinement that failed before its history entry existed showed "evaluating".
-- Set by Wish.setStatus on every stop. Existing failed/cancelled wishes stay NULL — the timeline keeps its
-- old guess for them, nothing invented.
ALTER TABLE wishes ADD COLUMN stopped_phase TEXT;
