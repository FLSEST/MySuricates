import { describe, expect, it } from 'vitest';
import { chainLink, scheduleLink, scheduleTriggerLink } from '../entityLinks';

// 3a35ced1: chains and schedules have no detail page – filter links select the entry instead
describe('entityLinks', () => {
  it('points at the chains and schedules pages with the id as filter', () => {
    expect(chainLink('c-1')).toBe('/chains?chain=c-1');
    expect(scheduleLink('s 1')).toBe('/schedules?schedule=s+1');
  });

  it('links a trigger to its schedule only when a schedule started the run and its id is known', () => {
    expect(scheduleTriggerLink('SCHEDULE', 's1')).toBe('/schedules?schedule=s1');
    expect(scheduleTriggerLink('SCHEDULE', null)).toBeNull();
    expect(scheduleTriggerLink('CHAIN', 's1')).toBeNull();
    expect(scheduleTriggerLink(undefined, 's1')).toBeNull();
  });
});
