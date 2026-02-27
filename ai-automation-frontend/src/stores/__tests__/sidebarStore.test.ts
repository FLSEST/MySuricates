import { describe, it, expect, beforeEach } from 'vitest';
import { useSidebarStore } from '../sidebarStore';

describe('sidebarStore', () => {
  beforeEach(() => {
    useSidebarStore.setState({ collapsed: false, mobileOpen: false });
  });

  it('has correct initial state', () => {
    const state = useSidebarStore.getState();
    expect(state.collapsed).toBe(false);
    expect(state.mobileOpen).toBe(false);
  });

  it('toggle flips collapsed from false to true', () => {
    useSidebarStore.getState().toggle();
    expect(useSidebarStore.getState().collapsed).toBe(true);
  });

  it('toggle flips collapsed from true to false', () => {
    useSidebarStore.setState({ collapsed: true });
    useSidebarStore.getState().toggle();
    expect(useSidebarStore.getState().collapsed).toBe(false);
  });

  it('double toggle returns to original state', () => {
    useSidebarStore.getState().toggle();
    useSidebarStore.getState().toggle();
    expect(useSidebarStore.getState().collapsed).toBe(false);
  });

  it('setCollapsed sets collapsed to specific value', () => {
    useSidebarStore.getState().setCollapsed(true);
    expect(useSidebarStore.getState().collapsed).toBe(true);
    useSidebarStore.getState().setCollapsed(false);
    expect(useSidebarStore.getState().collapsed).toBe(false);
  });

  it('setMobileOpen opens mobile menu', () => {
    useSidebarStore.getState().setMobileOpen(true);
    expect(useSidebarStore.getState().mobileOpen).toBe(true);
  });

  it('setMobileOpen closes mobile menu', () => {
    useSidebarStore.getState().setMobileOpen(true);
    useSidebarStore.getState().setMobileOpen(false);
    expect(useSidebarStore.getState().mobileOpen).toBe(false);
  });

  it('toggle does not affect mobileOpen', () => {
    useSidebarStore.getState().setMobileOpen(true);
    useSidebarStore.getState().toggle();
    expect(useSidebarStore.getState().mobileOpen).toBe(true);
  });
});
