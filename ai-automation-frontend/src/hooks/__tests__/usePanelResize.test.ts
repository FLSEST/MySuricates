import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { usePanelResize } from '../usePanelResize';

// jsdom has no pointer capture; the handlers only need these fields of the event.
function pointer(x: number, y: number) {
  return {
    clientX: x,
    clientY: y,
    pointerId: 7,
    currentTarget: { setPointerCapture: vi.fn() },
    preventDefault: vi.fn(),
  } as unknown as React.PointerEvent<HTMLElement>;
}

const original = { width: window.innerWidth, height: window.innerHeight };
function setWindow(width: number, height: number) {
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: width });
  Object.defineProperty(window, 'innerHeight', { configurable: true, value: height });
}

beforeEach(() => setWindow(1000, 800));
afterEach(() => setWindow(original.width, original.height));

function setup(size = { width: 420, height: 600 }) {
  const onWidth = vi.fn();
  const onHeight = vi.fn();
  const hook = renderHook(() => usePanelResize({
    width: { size: size.width, onChange: onWidth, min: 280, margin: 80 },
    height: { size: size.height, onChange: onHeight, min: 300, margin: 60 },
  }));
  return { ...hook, onWidth, onHeight };
}

describe('usePanelResize', () => {
  it('widens the panel while the left edge is dragged left and captures the pointer', () => {
    const { result, onWidth, onHeight } = setup();
    const down = pointer(500, 400);
    act(() => result.current.widthHandle.onPointerDown(down));
    expect(down.currentTarget.setPointerCapture).toHaveBeenCalledWith(7);
    expect(down.preventDefault).toHaveBeenCalled();
    expect(result.current.isResizing).toBe(true);

    act(() => result.current.widthHandle.onPointerMove(pointer(450, 100)));
    expect(onWidth).toHaveBeenLastCalledWith(470);
    act(() => result.current.widthHandle.onPointerMove(pointer(560, 100)));
    expect(onWidth).toHaveBeenLastCalledWith(360);
    expect(onHeight).not.toHaveBeenCalled();
  });

  it('keeps the width between the minimum and the window minus the margin', () => {
    const { result, onWidth } = setup();
    act(() => result.current.widthHandle.onPointerDown(pointer(500, 0)));
    act(() => result.current.widthHandle.onPointerMove(pointer(900, 0)));
    expect(onWidth).toHaveBeenLastCalledWith(280);
    act(() => result.current.widthHandle.onPointerMove(pointer(-400, 0)));
    expect(onWidth).toHaveBeenLastCalledWith(920);

    // The bound follows the window during the drag
    setWindow(700, 800);
    act(() => result.current.widthHandle.onPointerMove(pointer(-400, 0)));
    expect(onWidth).toHaveBeenLastCalledWith(620);
  });

  it('grows the panel while the top edge is dragged up, within its own bounds', () => {
    const { result, onWidth, onHeight } = setup();
    act(() => result.current.heightHandle.onPointerDown(pointer(0, 500)));
    act(() => result.current.heightHandle.onPointerMove(pointer(0, 400)));
    expect(onHeight).toHaveBeenLastCalledWith(700);
    act(() => result.current.heightHandle.onPointerMove(pointer(0, 100)));
    expect(onHeight).toHaveBeenLastCalledWith(740);
    act(() => result.current.heightHandle.onPointerMove(pointer(0, 900)));
    expect(onHeight).toHaveBeenLastCalledWith(300);
    expect(onWidth).not.toHaveBeenCalled();
  });

  it('ends the drag on pointercancel and on lost capture, not only on release', () => {
    const { result, onWidth, onHeight } = setup();
    act(() => result.current.widthHandle.onPointerDown(pointer(500, 500)));
    act(() => result.current.widthHandle.onPointerCancel());
    expect(result.current.isResizing).toBe(false);
    // Passing over the handle after a cancelled drag must not resize any more
    act(() => result.current.widthHandle.onPointerMove(pointer(100, 100)));
    expect(onWidth).not.toHaveBeenCalled();

    act(() => result.current.heightHandle.onPointerDown(pointer(500, 500)));
    act(() => result.current.heightHandle.onLostPointerCapture());
    expect(result.current.isResizing).toBe(false);
    act(() => result.current.heightHandle.onPointerMove(pointer(100, 100)));
    expect(onHeight).not.toHaveBeenCalled();
  });

  it('keeps touch input on the handles from scrolling the page', () => {
    const { result } = setup();
    expect(result.current.widthHandle.style).toEqual({ touchAction: 'none' });
    expect(result.current.heightHandle.style).toEqual({ touchAction: 'none' });
  });

  it('ignores moves before the press, after the release and on the other handle', () => {
    const { result, onWidth, onHeight } = setup();
    act(() => result.current.widthHandle.onPointerMove(pointer(100, 100)));
    expect(onWidth).not.toHaveBeenCalled();

    act(() => result.current.widthHandle.onPointerDown(pointer(500, 500)));
    act(() => result.current.heightHandle.onPointerMove(pointer(100, 100)));
    expect(onHeight).not.toHaveBeenCalled();

    act(() => result.current.widthHandle.onPointerUp());
    expect(result.current.isResizing).toBe(false);
    act(() => result.current.widthHandle.onPointerMove(pointer(100, 100)));
    expect(onWidth).not.toHaveBeenCalled();
  });
});
